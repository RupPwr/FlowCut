package com.flowcut.app.editor

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.flowcut.app.engine.perf.DeviceProfile
import com.flowcut.app.engine.perf.DeviceProfileProbe
import com.flowcut.app.engine.perf.Level
import com.flowcut.app.engine.perf.QualityBudget
import com.flowcut.app.engine.perf.QualityGovernor
import com.flowcut.app.engine.perf.ThermalMonitor
import com.flowcut.app.engine.perf.TierPolicy
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowcut.app.audio.ClipAudioContributor
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.MainIndex
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.ActiveLayerSet
import com.flowcut.app.engine.AnimatedTransformEvaluator
import com.flowcut.app.engine.AspectRatioMode
import com.flowcut.app.engine.BackgroundType
import com.flowcut.app.engine.ClipBackground
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.ClipTransform
import com.flowcut.app.engine.CompiledLayerPlan
import com.flowcut.app.engine.ExportEngine
import com.flowcut.app.engine.export.Aspect
import com.flowcut.app.engine.export.ExportCoordinator
import com.flowcut.app.engine.export.ExportRequest
import com.flowcut.app.engine.export.OutCodec
import com.flowcut.app.engine.export.OutputSpec
import com.flowcut.app.engine.export.Quality
import com.flowcut.app.engine.LayerTrackGroup
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.OverlayKeyframe
import com.flowcut.app.engine.PreviewEngine
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.engine.RenderStateStore
import com.flowcut.app.engine.canAddLayer
import com.flowcut.app.engine.canAddLayerItem
import com.flowcut.app.engine.getLayerCap
import com.flowcut.app.engine.clampItemTrimBoundsOnRow
import com.flowcut.app.engine.findAvailableRowOrClamp
import com.flowcut.app.engine.media.ColorInfoCheck
import com.flowcut.app.engine.trackGroup
import com.flowcut.app.ui.timeline.TimelineFocusState
import com.flowcut.app.model.AUTO_PLAY_MARGIN_MS
import com.flowcut.app.model.Clip
import com.flowcut.app.engine.OverlayTextureCache
import com.flowcut.app.engine.proxy.PipProxyGenerator
import com.flowcut.app.engine.proxy.ProxyStore
import com.flowcut.app.model.MAX_4K_LONG_SIDE
import com.flowcut.app.model.MAX_4K_SHORT_SIDE
import com.flowcut.app.model.MAX_CLIPS
import com.flowcut.app.model.MAX_TOTAL_MS
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.Project
import com.flowcut.app.model.TRANSITION_DEFAULT_MS
import com.flowcut.app.model.Transition
import com.flowcut.app.model.TransitionType
import com.flowcut.app.storage.ClipImporter
import com.flowcut.app.storage.DraftLibraryUtils
import com.flowcut.app.storage.FullProjectDraft
import com.flowcut.app.storage.JunctionTransitionDraft
import com.flowcut.app.storage.NewerProjectException
import com.flowcut.app.storage.ProjectDraftSummary
import com.flowcut.app.storage.ProjectStore
import com.flowcut.app.storage.toDomain
import com.flowcut.app.storage.toDraft
import com.flowcut.app.shared.ExportState
import com.flowcut.app.ui.media.ThumbnailService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

enum class AppScreen {
    HOME,
    EDITOR,
    EXPORT
}

data class EditorUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
    val activeDraftId: String? = null,
    val activeProjectTitle: String = "Project 1",
    val savedDrafts: List<ProjectDraftSummary> = emptyList(),
    val project: Project = Project(),
    val currentMs: Long = 0L,
    val isPlaying: Boolean = false,
    val selectedClipIndex: Int? = null,
    val selectedJunctionIndex: Int? = null,
    val selectedOverlayId: String? = null,
    val focusMode: TimelineFocusState = TimelineFocusState.S0_NONE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isImporting: Boolean = false,
    val isProcessingEdit: Boolean = false,
    val importProgressText: String = "",
    val durationSheetVisible: Boolean = false,
    val exportSheetVisible: Boolean = false,
    val exportState: ExportState = ExportState.Ready,
    val exportedUri: Uri? = null,
    val exportErrorMessage: String? = null,
    val exportFallbackNotice: String? = null,
    val exportResolution: Int = 1080,
    val exportFps: Int = 30,
    val exportBitratePreset: Int = 1,
    val exportBitrateBps: Int = 14_000_000,
    val toastMessage: String? = null,
    val isMainTrackMuted: Boolean = false,
    val coverTimeUs: Long = 0L,
    val customCoverPath: String? = null,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_9_16,
    val canvasBgMode: Int = 0,
    val canvasBlurLevel: Float = 0.5f,
    val canvasColorArgb: Int = 0xFF000000.toInt(),
    val canvasGradientId: Int = -1
) {
    val isProcessing: Boolean get() = isProcessingEdit || isImporting

    val aspectRatioFloat: Float get() = when {
        aspectRatioMode == AspectRatioMode.ORIGINAL -> {
            val c = project.clips.firstOrNull()
            if (c != null && c.displayWidth > 0 && c.displayHeight > 0) {
                c.displayWidth.toFloat() / c.displayHeight.toFloat()
            } else {
                9f / 16f
            }
        }
        aspectRatioMode.aspect > 0f -> aspectRatioMode.aspect
        else -> 9f / 16f
    }
}

data class SplitResult(
    val newSnapshot: TimelineSnapshot,
    val newRightClipIndex: Int
)

data class DuplicateResult(
    val newSnapshot: TimelineSnapshot,
    val newIndex: Int,
    val newStartMs: Long
)

data class FreezeResult(
    val newSnapshot: TimelineSnapshot,
    val freezeIndex: Int,
    val freezeStartMs: Long
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    val deviceProfile: DeviceProfile = DeviceProfileProbe(application).probe()
    val qualityBudget: QualityBudget = TierPolicy.budget(deviceProfile)

    private val perfExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "FlowCut-PerfGovernor")
    }
    private val thermalMonitor = ThermalMonitor(application, perfExecutor)
    val qualityGovernor = QualityGovernor()

    private val isMemoryPressureActive = AtomicBoolean(false)
    private var lastLateFrames: Long = 0L
    private var lastRenderedFrames: Long = 0L

    private val projectStore = ProjectStore(application)
    private val proxyStore = ProxyStore(application)
    private val pipProxyGenerator = PipProxyGenerator(application, proxyStore, viewModelScope)
    private val clipImporter = ClipImporter(application)
    private val exportEngine = ExportEngine(application)
    private val undoHistory = EditorUndoHistory()
    private val animEvaluator = AnimatedTransformEvaluator()

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    val loadingManager = LoadingStateManager(viewModelScope)
    val isLoadingVisible: StateFlow<Boolean> = loadingManager.isLoadingVisible
    val loadingMessage: StateFlow<String> = loadingManager.loadingMessage

    fun setLoading(source: String, isLoading: Boolean, message: String = "Processing...") {
        loadingManager.setLoading(source, isLoading, message)
    }

    fun startLoading(source: String, message: String = "Processing...") {
        loadingManager.startLoading(source, message)
    }

    fun stopLoading(source: String) {
        loadingManager.stopLoading(source)
    }

    fun isSourceLoading(source: String): Boolean = loadingManager.isSourceLoading(source)

    val timeline: ProgramTimeline
        get() = ProgramTimeline(_uiState.value.project)

    var previewEngine: PreviewEngine? = null

    private var autoSaveJob: Job? = null
    private var isNewerProjectBlocked = false
    private var exportJob: Job? = null
    private var autoPlayJob: Job? = null

    private var preDragProjectSnapshot: Project? = null

    val clipTimeMaps = mutableMapOf<String, TimeMap>()
    val clipNaturalDurations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val pathNaturalDurationsCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Per-clip volume (0.0f to 2.0f, default 1.0f) and pre-mute memory
    val clipVolumes = mutableMapOf<String, Float>()
    val savedPreMuteVolumes = mutableMapOf<String, Float>()

    // Per-clip transform (rotation: 0/90/180/270, isFlippedH: Boolean)
    val clipTransforms = mutableMapOf<String, ClipTransform>()

    // Per-clip color adjustments (Requirement 1 & 4)
    val clipColorAdjusts = mutableMapOf<String, ClipColorAdjust>()

    // Per-clip canvas background (NONE, COLOR, BLUR)
    val clipBackgrounds = mutableMapOf<String, ClipBackground>()

    // Multi-layer Overlay Items
    private var preSheetAspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_9_16
    private var isAspectRatioUserLocked: Boolean = false
    private var preSheetUserLocked: Boolean = false
    private val _layerItems = mutableStateListOf<OverlayItem>()
    val layerItems: List<OverlayItem> get() = _layerItems

    private fun syncLayerPlan() {
        val items = _layerItems.toList()
        val plan = CompiledLayerPlan.compile(items)
        PreviewRenderer.setLayerPlan(plan)
        val audioOverlays = items.filter { it.type == LayerType.AUDIO || (it.type == LayerType.VIDEO && it.volume > 0.001f) }
        ClipAudioContributor.syncAudioOverlays(audioOverlays)
        for (item in audioOverlays) {
            if (item.mediaPath.isNotEmpty()) {
                ClipAudioContributor.prewarmAudioPath(item.mediaPath)
            }
        }
        scheduleAutoSave()
    }

    fun getClipBackground(clipId: String): ClipBackground =
        clipBackgrounds[clipId] ?: ClipBackground.DEFAULT

    fun commitClipBackground(clipIndex: Int, background: ClipBackground) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return

        pushUndoState(projectSnapshot = currentProject)

        clipBackgrounds[clip.id] = background
        PreviewRenderer.setClipBackground(clip.id, background)

        val bgMode = if (background.gradientIndex in 0..5) 3 else when (background.type) {
            BackgroundType.NONE -> 0
            BackgroundType.COLOR -> 1
            BackgroundType.BLUR -> 2
        }
        _uiState.update {
            it.copy(
                canvasBgMode = bgMode,
                canvasBlurLevel = background.blurLevel,
                canvasColorArgb = background.colorArgb,
                canvasGradientId = background.gradientIndex
            )
        }

        updateProject(currentProject, recordUndo = false)
    }

    fun setClipBackgroundLive(clipIndex: Int, background: ClipBackground) {
        val clip = _uiState.value.project.clips.getOrNull(clipIndex) ?: return
        clipBackgrounds[clip.id] = background
        PreviewRenderer.setClipBackground(clip.id, background)
        val bgMode = if (background.gradientIndex in 0..5) 3 else when (background.type) {
            BackgroundType.NONE -> 0
            BackgroundType.COLOR -> 1
            BackgroundType.BLUR -> 2
        }
        _uiState.update {
            it.copy(
                canvasBgMode = bgMode,
                canvasBlurLevel = background.blurLevel,
                canvasColorArgb = background.colorArgb,
                canvasGradientId = background.gradientIndex
            )
        }
    }

    fun commitApplyBackgroundToAll(background: ClipBackground) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        setProcessingEdit(true)
        try {
            pushUndoState(projectSnapshot = currentProject)

            for (c in currentProject.clips) {
                clipBackgrounds[c.id] = background
                PreviewRenderer.setClipBackground(c.id, background)
            }

            val bgMode = if (background.gradientIndex in 0..5) 3 else when (background.type) {
                BackgroundType.NONE -> 0
                BackgroundType.COLOR -> 1
                BackgroundType.BLUR -> 2
            }
            _uiState.update {
                it.copy(
                    canvasBgMode = bgMode,
                    canvasBlurLevel = background.blurLevel,
                    canvasColorArgb = background.colorArgb,
                    canvasGradientId = background.gradientIndex
                )
            }

            updateProject(currentProject, recordUndo = false)
        } finally {
            setProcessingEdit(false)
        }
    }

    fun setApplyBackgroundToAllLive(background: ClipBackground) {
        val currentProject = _uiState.value.project
        for (c in currentProject.clips) {
            clipBackgrounds[c.id] = background
            PreviewRenderer.setClipBackground(c.id, background)
        }
        val bgMode = if (background.gradientIndex in 0..5) 3 else when (background.type) {
            BackgroundType.NONE -> 0
            BackgroundType.COLOR -> 1
            BackgroundType.BLUR -> 2
        }
        _uiState.update {
            it.copy(
                canvasBgMode = bgMode,
                canvasBlurLevel = background.blurLevel,
                canvasColorArgb = background.colorArgb,
                canvasGradientId = background.gradientIndex
            )
        }
    }

    fun getClipColorAdjust(clipId: String): ClipColorAdjust =
        clipColorAdjusts[clipId] ?: ClipColorAdjust.DEFAULT

    fun setClipColorAdjustLive(clipIndex: Int, adjust: ClipColorAdjust) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return
        clipColorAdjusts[clip.id] = adjust
        PreviewRenderer.setClipColorAdjust(clip.id, adjust)
    }

    fun setApplyColorAdjustToAllLive(adjust: ClipColorAdjust) {
        val currentProject = _uiState.value.project
        for (c in currentProject.clips) {
            clipColorAdjusts[c.id] = adjust
            PreviewRenderer.setClipColorAdjust(c.id, adjust)
        }
    }

    fun resetClipColorAdjust(clipIndex: Int) {
        setClipColorAdjustLive(clipIndex, ClipColorAdjust.DEFAULT)
    }

    fun commitClipColorAdjust(clipIndex: Int, adjust: ClipColorAdjust) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return

        pushUndoState(projectSnapshot = currentProject)

        clipColorAdjusts[clip.id] = adjust
        PreviewRenderer.setClipColorAdjust(clip.id, adjust)

        updateProject(currentProject, recordUndo = false)
    }

    fun commitApplyColorAdjustToAll(adjust: ClipColorAdjust) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        setProcessingEdit(true)
        try {
            pushUndoState(projectSnapshot = currentProject)

            for (c in currentProject.clips) {
                clipColorAdjusts[c.id] = adjust
                PreviewRenderer.setClipColorAdjust(c.id, adjust)
            }

            updateProject(currentProject, recordUndo = false)
        } finally {
            setProcessingEdit(false)
        }
    }

    fun getClipFilterState(clipId: String): com.flowcut.app.engine.ClipFilterState =
        _uiState.value.project.clips.find { it.id == clipId }?.renderParams?.filter
            ?: com.flowcut.app.engine.ClipFilterState.DEFAULT

    fun commitClipFilter(clipIndex: Int, presetId: Int, intensity: Float) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return

        PreviewRenderer.clearLiveFilterOverride(clip.id)
        val existing = clip.renderParams.filter
        val updated = existing.copy(filterPresetId = presetId, filterIntensity = intensity)
        val updatedClip = clip.copy(renderParams = clip.renderParams.copy(filter = updated))
        val updatedClips = currentProject.clips.toMutableList()
        updatedClips[clipIndex] = updatedClip
        val newProject = currentProject.copy(clips = updatedClips)

        updateProject(newProject, recordUndo = true)
    }

    fun commitApplyFilterToAll(presetId: Int, intensity: Float) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        setProcessingEdit(true)
        try {
            PreviewRenderer.clearLiveFilterOverride()
            val updatedClips = currentProject.clips.map { c ->
                val existing = c.renderParams.filter
                val updated = existing.copy(filterPresetId = presetId, filterIntensity = intensity)
                c.copy(renderParams = c.renderParams.copy(filter = updated))
            }
            val newProject = currentProject.copy(clips = updatedClips)
            updateProject(newProject, recordUndo = true)
        } finally {
            setProcessingEdit(false)
        }
    }

    fun commitClipEffect(clipIndex: Int, presetId: Int, intensity: Float) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return

        PreviewRenderer.clearLiveFilterOverride(clip.id)
        val existing = clip.renderParams.filter
        val updated = existing.copy(effectPresetId = presetId, effectIntensity = intensity)
        val updatedClip = clip.copy(renderParams = clip.renderParams.copy(filter = updated))
        val updatedClips = currentProject.clips.toMutableList()
        updatedClips[clipIndex] = updatedClip
        val newProject = currentProject.copy(clips = updatedClips)

        updateProject(newProject, recordUndo = true)
    }

    fun addFilterLayer(presetId: Int, intensity: Float, startUs: Long, durationUs: Long = 3_000_000L): OverlayItem? {
        val totalUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs }).coerceAtLeast(OverlayItem.MIN_DURATION_US)
        val clampedStart = startUs.coerceIn(0L, maxOf(0L, totalUs - OverlayItem.MIN_DURATION_US))

        val existingItem = _layerItems.firstOrNull { it.type == LayerType.FILTER && clampedStart in it.startUs until it.endUs }
        if (existingItem != null) {
            val updated = existingItem.copy(filterPresetId = presetId, filterIntensity = intensity)
            updateLayerItem(updated)
            selectOverlay(updated.id)
            return updated
        }

        if (!canAddLayerItem(_layerItems, LayerType.FILTER)) {
            showToast(getLayerCapLimitMessage(LayerType.FILTER))
            return null
        }

        val nextItem = _layerItems
            .filter { it.type == LayerType.FILTER && it.startUs > clampedStart }
            .minByOrNull { it.startUs }
        val availableEndUs = minOf(clampedStart + durationUs, nextItem?.startUs ?: totalUs, totalUs)
        if (availableEndUs - clampedStart < OverlayItem.MIN_DURATION_US) {
            showToast("Not enough space between existing filters/effects at this position")
            return null
        }

        val newItem = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.FILTER,
            laneIndex = 0,
            zIndex = (_layerItems.maxOfOrNull { it.zIndex } ?: 0) + 1,
            startUs = clampedStart,
            endUs = availableEndUs,
            filterPresetId = presetId,
            filterIntensity = intensity
        )
        return if (addLayerItem(newItem)) {
            selectOverlay(newItem.id)
            newItem
        } else null
    }

    fun addEffectLayer(presetId: Int, intensity: Float, startUs: Long, durationUs: Long = 3_000_000L): OverlayItem? {
        val totalUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs }).coerceAtLeast(OverlayItem.MIN_DURATION_US)
        val clampedStart = startUs.coerceIn(0L, maxOf(0L, totalUs - OverlayItem.MIN_DURATION_US))

        val existingItem = _layerItems.firstOrNull { it.type == LayerType.EFFECT && clampedStart in it.startUs until it.endUs }
        if (existingItem != null) {
            val updated = existingItem.copy(effectPresetId = presetId, effectIntensity = intensity)
            updateLayerItem(updated)
            selectOverlay(updated.id)
            return updated
        }

        if (!canAddLayerItem(_layerItems, LayerType.EFFECT)) {
            showToast(getLayerCapLimitMessage(LayerType.EFFECT))
            return null
        }

        val nextItem = _layerItems
            .filter { it.type == LayerType.EFFECT && it.startUs > clampedStart }
            .minByOrNull { it.startUs }
        val availableEndUs = minOf(clampedStart + durationUs, nextItem?.startUs ?: totalUs, totalUs)
        if (availableEndUs - clampedStart < OverlayItem.MIN_DURATION_US) {
            showToast("Not enough space between existing filters/effects at this position")
            return null
        }

        val newItem = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.EFFECT,
            laneIndex = 0,
            zIndex = (_layerItems.maxOfOrNull { it.zIndex } ?: 0) + 1,
            startUs = clampedStart,
            endUs = availableEndUs,
            effectPresetId = presetId,
            effectIntensity = intensity
        )
        return if (addLayerItem(newItem)) {
            selectOverlay(newItem.id)
            newItem
        } else null
    }

    fun addMosaicOverlay(startUs: Long, durationUs: Long = 3_000_000L): OverlayItem? {
        if (!canAddLayerItem(_layerItems, LayerType.MOSAIC)) {
            showToast(getLayerCapLimitMessage(LayerType.MOSAIC))
            return null
        }
        val totalUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs }).coerceAtLeast(OverlayItem.MIN_DURATION_US)
        val clampedStart = startUs.coerceIn(0L, maxOf(0L, totalUs - OverlayItem.MIN_DURATION_US))
        val endUs = minOf(clampedStart + durationUs, totalUs).coerceAtLeast(clampedStart + OverlayItem.MIN_DURATION_US)
        val newItem = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.MOSAIC,
            laneIndex = -1,
            zIndex = (_layerItems.maxOfOrNull { it.zIndex } ?: 0) + 1,
            startUs = clampedStart,
            endUs = endUs
        )
        return if (addLayerItem(newItem)) {
            selectOverlay(newItem.id)
            newItem
        } else null
    }

    fun updateSelectedFilterLayer(presetId: Int, intensity: Float): OverlayItem? {
        val selectedId = _uiState.value.selectedOverlayId ?: return null
        val idx = _layerItems.indexOfFirst { it.id == selectedId }
        if (idx < 0) return null
        val item = _layerItems[idx]
        if (item.type != LayerType.FILTER) return null
        val updated = item.copy(filterPresetId = presetId, filterIntensity = intensity)
        _layerItems[idx] = updated
        syncLayerPlan()
        return updated
    }

    fun updateSelectedEffectLayer(presetId: Int, intensity: Float): OverlayItem? {
        val selectedId = _uiState.value.selectedOverlayId ?: return null
        val idx = _layerItems.indexOfFirst { it.id == selectedId }
        if (idx < 0) return null
        val item = _layerItems[idx]
        if (item.type != LayerType.EFFECT) return null
        val updated = item.copy(effectPresetId = presetId, effectIntensity = intensity)
        _layerItems[idx] = updated
        syncLayerPlan()
        return updated
    }

    fun commitSelectedFilterLayer(presetId: Int, intensity: Float) {
        val selectedId = _uiState.value.selectedOverlayId ?: return
        val idx = _layerItems.indexOfFirst { it.id == selectedId }
        if (idx < 0) return
        pushUndoState()
        val item = _layerItems[idx]
        _layerItems[idx] = item.copy(filterPresetId = presetId, filterIntensity = intensity)
        syncLayerPlan()
    }

    fun commitSelectedEffectLayer(presetId: Int, intensity: Float) {
        val selectedId = _uiState.value.selectedOverlayId ?: return
        val idx = _layerItems.indexOfFirst { it.id == selectedId }
        if (idx < 0) return
        pushUndoState()
        val item = _layerItems[idx]
        _layerItems[idx] = item.copy(effectPresetId = presetId, effectIntensity = intensity)
        syncLayerPlan()
    }

    fun getClipTransform(clipId: String): ClipTransform =
        clipTransforms[clipId] ?: ClipTransform.DEFAULT

    fun rotateSelectedClip(): ClipTransform? {
        val selectedIndex = _uiState.value.selectedClipIndex ?: return null
        val clip = _uiState.value.project.clips.getOrNull(selectedIndex) ?: return null
        val current = clipTransforms[clip.id] ?: ClipTransform.DEFAULT
        val nextDeg = (((Math.round(current.rotationDeg / 90.0) * 90 + 90) % 360 + 360) % 360).toFloat()
        val newTransform = current.copy(rotationDeg = nextDeg)

        val currentProject = _uiState.value.project
        pushUndoState(projectSnapshot = currentProject)

        clipTransforms[clip.id] = newTransform
        PreviewRenderer.setClipTransform(clip.id, newTransform)

        updateProject(currentProject, recordUndo = false)
        return newTransform
    }

    fun flipSelectedClip(): ClipTransform? {
        val selectedIndex = _uiState.value.selectedClipIndex ?: return null
        val clip = _uiState.value.project.clips.getOrNull(selectedIndex) ?: return null
        val current = clipTransforms[clip.id] ?: ClipTransform.DEFAULT
        val newTransform = current.copy(isFlippedH = !current.isFlippedH)

        val currentProject = _uiState.value.project
        pushUndoState(projectSnapshot = currentProject)

        clipTransforms[clip.id] = newTransform
        PreviewRenderer.setClipTransform(clip.id, newTransform)

        updateProject(currentProject, recordUndo = false)
        return newTransform
    }

    fun commitClipTransform(clipIndex: Int, transform: ClipTransform) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return

        pushUndoState(projectSnapshot = currentProject)

        clipTransforms[clip.id] = transform
        PreviewRenderer.setClipTransform(clip.id, transform)

        updateProject(currentProject, recordUndo = false)
    }

    fun getClipOpacity(clipId: String): Float =
        getClipTransform(clipId).opacity

    fun setClipTransformLive(clipId: String, transform: ClipTransform) {
        clipTransforms[clipId] = transform
        PreviewRenderer.setClipTransform(clipId, transform)
    }

    fun setClipOpacityLive(clipId: String, opacity: Float) {
        val current = getClipTransform(clipId)
        val updated = current.copy(opacity = opacity.coerceIn(0f, 1f))
        clipTransforms[clipId] = updated
        PreviewRenderer.setClipTransform(clipId, updated)
    }

    fun commitClipOpacity(clipIndex: Int, opacity: Float) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return
        val current = getClipTransform(clip.id)
        val updated = current.copy(opacity = opacity.coerceIn(0f, 1f))
        commitClipTransform(clipIndex, updated)
    }

    fun applyOpacityToAll(opacity: Float) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return
        val clamped = opacity.coerceIn(0f, 1f)

        pushUndoState(projectSnapshot = currentProject)

        for (c in currentProject.clips) {
            val current = clipTransforms[c.id] ?: ClipTransform.DEFAULT
            val newTransform = current.copy(opacity = clamped)
            clipTransforms[c.id] = newTransform
            PreviewRenderer.setClipTransform(c.id, newTransform)
        }

        updateProject(currentProject, recordUndo = false)
    }

    fun resetClipOpacity(clipIndex: Int) {
        commitClipOpacity(clipIndex, 1.0f)
    }

    // --- Layer Operations (Phase 2A Foundation & Phase 3 Overlays) ---

    fun findNextAvailableLane(startUs: Long, endUs: Long, excludeItemId: String? = null): Int {
        val occupiedLanes = _layerItems
            .filter { (it.type == LayerType.TEXT || it.type == LayerType.STICKER) && it.id != excludeItemId && it.startUs < endUs && it.endUs > startUs }
            .map { it.laneIndex }
            .toSet()
        var lane = 0
        while (lane in occupiedLanes) {
            lane++
        }
        return lane
    }

    /**
     * Dedicated PIP lane allocator for LayerType.IMAGE and LayerType.VIDEO.
     * Strictly packs into lanes 0, 1, 2, 3 without vertical gaps or sharing with text/stickers.
     */
    fun findNextAvailablePipLane(startUs: Long, endUs: Long, excludeItemId: String? = null): Int {
        val occupiedLanes = _layerItems
            .filter { (it.type == LayerType.IMAGE || it.type == LayerType.VIDEO) && it.id != excludeItemId && it.startUs < endUs && it.endUs > startUs }
            .map { it.laneIndex.coerceIn(0, 3) }
            .toSet()
        for (lane in 0..3) {
            if (lane !in occupiedLanes) return lane
        }
        return 0
    }

    fun getTopZIndex(): Int {
        return (_layerItems.maxOfOrNull { it.zIndex } ?: 0) + 1
    }

    fun addLayerItem(item: OverlayItem): Boolean {
        if (!canAddLayerItem(_layerItems, item.type)) {
            showToast(getLayerCapLimitMessage(item.type))
            return false
        }
        if (item.type == LayerType.FILTER || item.type == LayerType.EFFECT) {
            val hasSameTypeOverlap = _layerItems.any {
                it.id != item.id && it.type == item.type && it.startUs < item.endUs && it.endUs > item.startUs
            }
            if (hasSameTypeOverlap) {
                showToast("Not enough space between existing filters/effects at this position")
                return false
            }
        }
        val trackGrp = item.trackGroup()
        val totalDurationUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs })

        val resolvedStartUs: Long
        val resolvedEndUs: Long
        val assignedLane: Int

        if (trackGrp != null) {
            // PIP or AUDIO: use deterministic 4-row non-overlapping allocator
            val alloc = findAvailableRowOrClamp(
                items = _layerItems,
                group = trackGrp,
                desiredStartUs = item.startUs,
                desiredDurationUs = item.durationUs,
                totalProjectDurationUs = totalDurationUs,
                excludeItemId = item.id
            )
            if (alloc == null) {
                showToast("All 4 tracks are full at this position")
                return false
            }
            assignedLane = alloc.laneIndex
            resolvedStartUs = alloc.startUs
            resolvedEndUs = alloc.endUs
        } else {
            // Text or Sticker or Mosaic (Row A Pins / Row B Bar)
            assignedLane = if (item.laneIndex < 0) findNextAvailableLane(item.startUs, item.endUs) else item.laneIndex
            resolvedStartUs = item.startUs
            resolvedEndUs = item.endUs
        }

        val assignedZ = if (item.zIndex <= 0) getTopZIndex() else item.zIndex
        val resolvedItem = item.copy(
            laneIndex = assignedLane,
            startUs = resolvedStartUs,
            endUs = resolvedEndUs,
            zIndex = assignedZ
        )

        pushUndoState()
        _layerItems.add(resolvedItem)
        syncLayerPlan()
        val newFocus = when (resolvedItem.type) {
            LayerType.TEXT -> TimelineFocusState.S4_TEXT_ITEM(resolvedItem.id)
            LayerType.STICKER, LayerType.MOSAIC -> TimelineFocusState.S3_STICKER_ITEM(resolvedItem.id)
            LayerType.VIDEO, LayerType.IMAGE -> TimelineFocusState.S2_PIP_ITEM(resolvedItem.id)
            LayerType.AUDIO -> TimelineFocusState.S6_AUDIO_ITEM(resolvedItem.id)
            LayerType.FILTER, LayerType.EFFECT -> TimelineFocusState.S7_FILTER_EFFECT_ITEM(resolvedItem.id)
            else -> TimelineFocusState.S0_NONE
        }
        _uiState.update {
            it.copy(
                selectedOverlayId = resolvedItem.id,
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                focusMode = newFocus
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
        return true
    }

    fun addAudioOverlay(
        mediaPath: String,
        title: String = "Sound",
        startUs: Long,
        durationUs: Long = 3_000_000L,
        audioSubType: Int = 0,
        sourceInUs: Long = 0L,
        volume: Float = 1.0f,
        naturalDurationUs: Long = durationUs
    ): OverlayItem? {
        if (!canAddLayerItem(_layerItems, LayerType.AUDIO)) {
            showToast(getLayerCapLimitMessage(LayerType.AUDIO))
            return null
        }
        val endUs = startUs + durationUs
        val item = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.AUDIO,
            laneIndex = -1,
            zIndex = 0,
            startUs = startUs,
            endUs = endUs,
            text = title,
            mediaPath = mediaPath,
            audioSubType = audioSubType,
            volume = volume,
            sourceInUs = sourceInUs,
            naturalDurationUs = if (naturalDurationUs > 0L) naturalDurationUs else durationUs
        )
        return if (addLayerItem(item)) _layerItems.lastOrNull() else null
    }

    fun hasTextOverlay(): Boolean {
        return _layerItems.any { it.type == LayerType.TEXT }
    }

    fun addTextOverlay(
        text: String = "",
        textColorArgb: Int = 0xFFFFFFFF.toInt(),
        bgStyle: Int = 0,
        startUs: Long,
        durationUs: Long = 3_000_000L
    ): OverlayItem? {
        if (!canAddLayerItem(_layerItems, LayerType.TEXT)) {
            showToast(getLayerCapLimitMessage(LayerType.TEXT))
            return null
        }
        val endUs = startUs + durationUs
        val item = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.TEXT,
            laneIndex = -1,
            zIndex = 0,
            startUs = startUs,
            endUs = endUs,
            text = text,
            textColorArgb = textColorArgb,
            bgStyle = bgStyle
        )
        return if (addLayerItem(item)) _layerItems.lastOrNull() else null
    }

    fun addStickerOverlay(
        stickerGlyph: String,
        startUs: Long,
        durationUs: Long = 3_000_000L
    ): OverlayItem? {
        if (!canAddLayerItem(_layerItems, LayerType.STICKER)) {
            showToast(getLayerCapLimitMessage(LayerType.STICKER))
            return null
        }
        val endUs = startUs + durationUs
        val item = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.STICKER,
            laneIndex = -1,
            zIndex = 0,
            startUs = startUs,
            endUs = endUs,
            text = stickerGlyph
        )
        return if (addLayerItem(item)) _layerItems.lastOrNull() else null
    }

    /**
     * Adds an interactive GPU Mosaic / Privacy Blur Region (Phase 9) to the timeline.
     */
    fun addMosaicOverlay(
        startUs: Long,
        durationUs: Long = 3_000_000L,
        style: Int = 0,
        intensity: Float = 0.5f,
        feather: Float = 0.05f
    ): OverlayItem? {
        if (!canAddLayerItem(_layerItems, LayerType.MOSAIC)) {
            showToast(getLayerCapLimitMessage(LayerType.MOSAIC))
            return null
        }
        val endUs = startUs + durationUs
        val item = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.MOSAIC,
            laneIndex = -1,
            zIndex = 0,
            startUs = startUs,
            endUs = endUs,
            mosaicStyle = style,
            mosaicIntensity = intensity,
            mosaicFeather = feather
        )
        return if (addLayerItem(item)) _layerItems.lastOrNull() else null
    }

    fun liveUpdateMosaic(itemId: String, style: Int, intensity: Float, feather: Float) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx >= 0) {
            val old = _layerItems[idx]
            val updated = old.copy(mosaicStyle = style, mosaicIntensity = intensity, mosaicFeather = feather)
            _layerItems[idx] = updated
            syncLayerPlan()
        }
    }

    fun updateMosaic(itemId: String, style: Int, intensity: Float, feather: Float): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        return updateLayerItem(item.copy(
            mosaicStyle = style,
            mosaicIntensity = intensity,
            mosaicFeather = feather
        ))
    }

    fun resolveEffectiveAspect(mode: AspectRatioMode = _uiState.value.aspectRatioMode): Float =
        resolveEffectiveAspectForClip(mode, _uiState.value.project.clips.firstOrNull())

    fun findMatchingAspectRatioMode(displayWidth: Int, displayHeight: Int): AspectRatioMode =
        findMatchingAspectRatioModeForDimensions(displayWidth, displayHeight)

    fun startAspectRatioEditing() {
        preSheetAspectRatioMode = _uiState.value.aspectRatioMode
        preSheetUserLocked = isAspectRatioUserLocked
    }

    fun setAspectRatioLive(mode: AspectRatioMode) {
        isAspectRatioUserLocked = true
        _uiState.update { it.copy(aspectRatioMode = mode) }
        val effective = resolveEffectiveAspect(mode)
        PreviewRenderer.setAspectRatioMode(mode, _uiState.value.project.clips.firstOrNull())
        PreviewRenderer.setCanvasAspectRatio(effective)
    }

    fun setAspectRatioLive(modeIndex: Int) {
        setAspectRatioLive(AspectRatioMode.fromIndex(modeIndex))
    }

    fun setAspectRatioMode(mode: AspectRatioMode) {
        setAspectRatioLive(mode)
    }

    fun commitAspectRatio(mode: AspectRatioMode? = null) {
        isAspectRatioUserLocked = true
        val finalMode = mode ?: _uiState.value.aspectRatioMode
        if (finalMode != preSheetAspectRatioMode) {
            pushUndoState(
                projectSnapshot = _uiState.value.project,
                aspectRatioSnapshot = preSheetAspectRatioMode
            )
            preSheetAspectRatioMode = finalMode
        }
        _uiState.update {
            it.copy(
                aspectRatioMode = finalMode,
                canUndo = undoHistory.canUndo,
                canRedo = undoHistory.canRedo
            )
        }
        val effective = resolveEffectiveAspect(finalMode)
        PreviewRenderer.setAspectRatioMode(finalMode, _uiState.value.project.clips.firstOrNull())
        PreviewRenderer.setCanvasAspectRatio(effective)
        scheduleAutoSave()
    }

    fun cancelAspectRatio() {
        isAspectRatioUserLocked = preSheetUserLocked
        _uiState.update { it.copy(aspectRatioMode = preSheetAspectRatioMode) }
        val effective = resolveEffectiveAspect(preSheetAspectRatioMode)
        PreviewRenderer.setAspectRatioMode(preSheetAspectRatioMode, _uiState.value.project.clips.firstOrNull())
        PreviewRenderer.setCanvasAspectRatio(effective)
    }

    /**
     * Imports a custom photo sticker from StickerPickerSheet into Row A / Row B as LayerType.STICKER.
     */
    fun addImageStickerOverlay(
        mediaPath: String,
        startUs: Long,
        durationUs: Long = 3_000_000L
    ): OverlayItem? {
        if (!canAddLayerItem(_layerItems, LayerType.STICKER)) {
            showToast(getLayerCapLimitMessage(LayerType.STICKER))
            return null
        }
        val endUs = startUs + durationUs
        val item = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.STICKER,
            laneIndex = -1,
            zIndex = 0,
            startUs = startUs,
            endUs = endUs,
            mediaPath = mediaPath
        )
        return if (addLayerItem(item)) _layerItems.lastOrNull() else null
    }

    fun addImageOverlay(
        mediaPath: String,
        startUs: Long,
        durationUs: Long = 3_000_000L
    ): OverlayItem? {
        if (!canAddLayerItem(_layerItems, LayerType.IMAGE)) {
            showToast(getLayerCapLimitMessage(LayerType.IMAGE))
            return null
        }
        val endUs = startUs + durationUs
        var intrinsicWidth = 0
        var intrinsicHeight = 0
        try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(mediaPath, boundsOpts)
            var w = boundsOpts.outWidth
            var h = boundsOpts.outHeight
            if (w > 0 && h > 0) {
                val exif = ExifInterface(mediaPath)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270
                ) {
                    val tmp = w
                    w = h
                    h = tmp
                }
                intrinsicWidth = w
                intrinsicHeight = h
            }
        } catch (_: Throwable) {}

        val item = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.IMAGE,
            laneIndex = -1,
            zIndex = 0,
            startUs = startUs,
            endUs = endUs,
            mediaPath = mediaPath,
            intrinsicWidth = intrinsicWidth,
            intrinsicHeight = intrinsicHeight
        )
        return if (addLayerItem(item)) _layerItems.lastOrNull() else null
    }

    fun addVideoOverlay(
        mediaPath: String,
        startUs: Long,
        durationUs: Long = 3_000_000L
    ): OverlayItem? {
        if (!canAddLayerItem(_layerItems, LayerType.VIDEO)) {
            showToast(getLayerCapLimitMessage(LayerType.VIDEO))
            try { java.io.File(mediaPath).delete() } catch (_: Throwable) {}
            return null
        }

        // Validate video track and reject HDR / 10-bit
        val extractor = MediaExtractor()
        var videoTrackFormat: MediaFormat? = null
        try {
            extractor.setDataSource(mediaPath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackFormat = format
                    break
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { extractor.release() } catch (_: Throwable) {}
        }

        if (videoTrackFormat == null) {
            showToast("Unsupported video format")
            try { java.io.File(mediaPath).delete() } catch (_: Throwable) {}
            return null
        }

        val videoMime = videoTrackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        val isAvc = videoMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
        val isHevc = videoMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
        val isDolbyVision = videoMime.contains("dolby-vision", ignoreCase = true)
        if (isDolbyVision) {
            showToast("HDR / 10-bit videos abhi support nahi hain.")
            try { java.io.File(mediaPath).delete() } catch (_: Throwable) {}
            return null
        }
        if (!isAvc && !isHevc) {
            showToast("Unsupported video format")
            try { java.io.File(mediaPath).delete() } catch (_: Throwable) {}
            return null
        }

        val verdict = ColorInfoCheck.check(videoTrackFormat)
        if (verdict != ColorInfoCheck.Verdict.OK) {
            showToast("HDR / 10-bit videos abhi support nahi hain.")
            try { java.io.File(mediaPath).delete() } catch (_: Throwable) {}
            return null
        }

        val endUs = startUs + durationUs
        var naturalDurUs = 0L
        val formatW = if (videoTrackFormat.containsKey(MediaFormat.KEY_WIDTH)) {
            videoTrackFormat.getInteger(MediaFormat.KEY_WIDTH)
        } else 0
        val formatH = if (videoTrackFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
            videoTrackFormat.getInteger(MediaFormat.KEY_HEIGHT)
        } else 0
        val formatRot = if (videoTrackFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            videoTrackFormat.getInteger(MediaFormat.KEY_ROTATION)
        } else -1

        var retrW = 0
        var retrH = 0
        var retrRot = 0

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mediaPath)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val durMsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            naturalDurUs = (durMsStr?.toLongOrNull() ?: 0L) * 1000L
            retrW = widthStr?.toIntOrNull() ?: 0
            retrH = heightStr?.toIntOrNull() ?: 0
            retrRot = rotationStr?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        val canonicalRot = when {
            formatRot in listOf(0, 90, 180, 270) -> formatRot
            retrRot in listOf(0, 90, 180, 270) -> retrRot
            else -> 0
        }

        val rawW: Int
        val rawH: Int
        if (formatW > 0 && formatH > 0) {
            rawW = formatW
            rawH = formatH
        } else if (retrW > 0 && retrH > 0) {
            if ((canonicalRot == 90 || canonicalRot == 270) && retrW < retrH) {
                // Guard against OEM MediaMetadataRetriever implementations that already return pre-rotated display dimensions
                rawW = retrH
                rawH = retrW
            } else {
                rawW = retrW
                rawH = retrH
            }
        } else {
            rawW = maxOf(formatW, retrW)
            rawH = maxOf(formatH, retrH)
        }

        val displayW = if (canonicalRot == 90 || canonicalRot == 270) rawH else rawW
        val displayH = if (canonicalRot == 90 || canonicalRot == 270) rawW else rawH
        val shortSide = minOf(displayW, displayH)
        val longSide = maxOf(displayW, displayH)

        if (shortSide > MAX_4K_SHORT_SIDE || longSide > MAX_4K_LONG_SIDE) {
            showToast("Video resolution (${displayW}x${displayH}) exceeds maximum 4K limit (${MAX_4K_LONG_SIDE}px).")
            try { java.io.File(mediaPath).delete() } catch (_: Throwable) {}
            return null
        }

        val intrinsicWidth = displayW
        val intrinsicHeight = displayH

        val item = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.VIDEO,
            laneIndex = -1,
            zIndex = 0,
            startUs = startUs,
            endUs = endUs,
            mediaPath = mediaPath,
            intrinsicWidth = intrinsicWidth,
            intrinsicHeight = intrinsicHeight,
            naturalDurationUs = if (naturalDurUs > 0L) naturalDurUs else durationUs
        )
        val added = if (addLayerItem(item)) _layerItems.lastOrNull() else null
        if (added != null && (qualityBudget.autoProxy || qualityGovernor.currentLevel != Level.FULL)) {
            pipProxyGenerator.getOrGenerateProxy(mediaPath).invokeOnCompletion {
                previewEngine?.requestRender()
            }
        }
        return added
    }

    fun updateTextOverlay(itemId: String, text: String, textColorArgb: Int, bgStyle: Int, fontIndex: Int = 0): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        val updated = item.copy(
            text = text,
            textColorArgb = textColorArgb,
            bgStyle = bgStyle,
            fontIndex = fontIndex,
            zIndex = getTopZIndex()
        )
        return updateLayerItem(updated)
    }

    fun updateTextFont(itemId: String, fontIndex: Int): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        val updated = item.copy(fontIndex = fontIndex)
        return updateLayerItem(updated)
    }

    fun getOrResolveTextOverlayAt(playheadUs: Long): OverlayItem? {
        val selectedId = _uiState.value.selectedOverlayId
        if (selectedId != null) {
            val item = _layerItems.firstOrNull { it.id == selectedId }
            if (item != null && item.type == LayerType.TEXT) {
                return item
            }
        }
        val itemUnderPlayhead = _layerItems
            .filter { it.type == LayerType.TEXT && it.startUs <= playheadUs && playheadUs <= it.endUs }
            .maxByOrNull { it.zIndex }

        if (itemUnderPlayhead != null) {
            selectOverlay(itemUnderPlayhead.id)
            return itemUnderPlayhead
        }
        return null
    }

    fun splitOverlay(itemId: String, playheadUs: Long): String? {
        selectOverlay(itemId)
        return splitSelectedOverlayAtPlayhead(playheadUs)
    }

    fun updateTextColorAndAlpha(
        itemId: String,
        textColor: Int, textAlpha: Float,
        labelColor: Int, labelAlpha: Float,
        borderColor: Int, borderAlpha: Float,
        shadowColor: Int, shadowAlpha: Float
    ): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        val updated = item.copy(
            textColorArgb = textColor,
            textAlpha = textAlpha,
            labelColorArgb = labelColor,
            labelAlpha = labelAlpha,
            borderColorArgb = borderColor,
            borderAlpha = borderAlpha,
            shadowColorArgb = shadowColor,
            shadowAlpha = shadowAlpha
        )
        return updateLayerItem(updated)
    }

    fun liveUpdateTextColorAndAlpha(
        itemId: String,
        textColor: Int, textAlpha: Float,
        labelColor: Int, labelAlpha: Float,
        borderColor: Int, borderAlpha: Float,
        shadowColor: Int, shadowAlpha: Float
    ) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val cur = _layerItems[idx]
        val updated = cur.copy(
            textColorArgb = textColor,
            textAlpha = textAlpha,
            labelColorArgb = labelColor,
            labelAlpha = labelAlpha,
            borderColorArgb = borderColor,
            borderAlpha = borderAlpha,
            shadowColorArgb = shadowColor,
            shadowAlpha = shadowAlpha
        )
        _layerItems[idx] = updated
        syncLayerPlan()
    }

    fun updateTextColorAndAlpha(
        itemId: String,
        tabIndex: Int,
        colorArgb: Int,
        alpha: Float
    ): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        val updated = when (tabIndex) {
            0 -> item.copy(textColorArgb = colorArgb, textAlpha = alpha)
            1 -> item.copy(labelColorArgb = colorArgb, labelAlpha = alpha)
            2 -> item.copy(borderColorArgb = colorArgb, borderAlpha = alpha)
            else -> item.copy(shadowColorArgb = colorArgb, shadowAlpha = alpha)
        }
        return updateLayerItem(updated)
    }

    fun liveUpdateTextColorAndAlpha(
        itemId: String,
        tabIndex: Int,
        colorArgb: Int,
        alpha: Float
    ) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val cur = _layerItems[idx]
        val updated = when (tabIndex) {
            0 -> cur.copy(textColorArgb = colorArgb, textAlpha = alpha)
            1 -> cur.copy(labelColorArgb = colorArgb, labelAlpha = alpha)
            2 -> cur.copy(borderColorArgb = colorArgb, borderAlpha = alpha)
            else -> cur.copy(shadowColorArgb = colorArgb, shadowAlpha = alpha)
        }
        _layerItems[idx] = updated
        syncLayerPlan()
    }

    fun updateTextFont(itemId: String, fontName: String, customPath: String = ""): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        val updated = item.copy(fontName = fontName, customFontPath = customPath)
        return updateLayerItem(updated)
    }

    fun updateTextAnimation(
        itemId: String,
        inType: Int,
        inDurUs: Long,
        outType: Int,
        outDurUs: Long,
        loopType: Int
    ): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        val updated = item.copy(
            animInType = inType,
            animInDurationUs = inDurUs,
            animOutType = outType,
            animOutDurationUs = outDurUs,
            animLoopType = loopType
        )
        return updateLayerItem(updated)
    }

    fun liveUpdateTextAnimation(
        itemId: String,
        inType: Int,
        inDurUs: Long,
        outType: Int,
        outDurUs: Long,
        loopType: Int
    ) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val cur = _layerItems[idx]
        _layerItems[idx] = cur.copy(
            animInType = inType,
            animInDurationUs = inDurUs,
            animOutType = outType,
            animOutDurationUs = outDurUs,
            animLoopType = loopType
        )
        syncLayerPlan()
    }

    fun updateTextAdjust(
        itemId: String,
        textAlign: Int,
        letterSpacing: Float,
        lineSpacing: Float,
        opacity: Float,
        isBold: Boolean,
        isItalic: Boolean,
        isUnderline: Boolean,
        isUppercase: Boolean
    ): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        val updated = item.copy(
            textAlign = textAlign,
            letterSpacing = letterSpacing,
            lineSpacing = lineSpacing,
            transform = item.transform.copy(opacity = opacity),
            isBold = isBold,
            isItalic = isItalic,
            isUnderline = isUnderline,
            isUppercase = isUppercase
        )
        return updateLayerItem(updated)
    }

    fun liveUpdateTextAdjust(
        itemId: String,
        textAlign: Int,
        letterSpacing: Float,
        lineSpacing: Float,
        opacity: Float,
        isBold: Boolean,
        isItalic: Boolean,
        isUnderline: Boolean,
        isUppercase: Boolean
    ) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val cur = _layerItems[idx]
        _layerItems[idx] = cur.copy(
            textAlign = textAlign,
            letterSpacing = letterSpacing,
            lineSpacing = lineSpacing,
            transform = cur.transform.copy(opacity = opacity),
            isBold = isBold,
            isItalic = isItalic,
            isUnderline = isUnderline,
            isUppercase = isUppercase
        )
        syncLayerPlan()
    }

    fun cycleSelectedTextFont(): Int {
        val selectedId = _uiState.value.selectedOverlayId ?: return 0
        val item = _layerItems.firstOrNull { it.id == selectedId } ?: return 0
        val nextFont = (item.fontIndex + 1) % 6
        updateTextFont(selectedId, nextFont)
        return nextFont
    }

    fun liveUpdateTextOverlay(itemId: String, text: String, textColorArgb: Int, bgStyle: Int, fontIndex: Int = 0) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val cur = _layerItems[idx]
        _layerItems[idx] = cur.copy(
            text = text,
            textColorArgb = textColorArgb,
            bgStyle = bgStyle,
            fontIndex = fontIndex
        )
        syncLayerPlan()
    }

    fun removeLayerItem(itemId: String): Boolean {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return false
        val itemToRemove = _layerItems[idx]
        pushUndoState()
        _layerItems.removeAt(idx)
        syncLayerPlan()
        _uiState.update {
            if (it.selectedOverlayId == itemId) {
                it.copy(selectedOverlayId = null)
            } else {
                it
            }
        }
        updateProject(_uiState.value.project, recordUndo = false)
        if (itemToRemove.type == LayerType.VIDEO && itemToRemove.mediaPath.isNotEmpty()) {
            val stillUsed = _layerItems.any { it.type == LayerType.VIDEO && it.mediaPath == itemToRemove.mediaPath }
            if (!stillUsed) {
                proxyStore.deleteProxyFor(itemToRemove.mediaPath)
            }
        }
        return true
    }

    fun updateLayerItem(item: OverlayItem): Boolean {
        val idx = _layerItems.indexOfFirst { it.id == item.id }
        if (idx < 0) return false
        pushUndoState()
        _layerItems[idx] = item
        syncLayerPlan()
        _uiState.update {
            it.copy(
                selectedOverlayId = item.id,
                selectedClipIndex = null,
                selectedJunctionIndex = null
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
        return true
    }

    // Phase 7: Advanced GPU Tools (Mask, Crop, Cutout / Chroma Key)
    fun liveUpdatePipMask(itemId: String, maskType: Int, maskFeather: Float, maskInvert: Boolean) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val cur = _layerItems[idx]
        _layerItems[idx] = cur.copy(
            maskType = maskType,
            maskFeather = maskFeather,
            maskInvert = maskInvert
        )
        syncLayerPlan()
    }

    fun updatePipMask(itemId: String, maskType: Int, maskFeather: Float, maskInvert: Boolean): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        return updateLayerItem(item.copy(
            maskType = maskType,
            maskFeather = maskFeather,
            maskInvert = maskInvert
        ))
    }

    fun liveUpdatePipCrop(itemId: String, cropLeft: Float, cropTop: Float, cropRight: Float, cropBottom: Float) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val cur = _layerItems[idx]
        _layerItems[idx] = cur.copy(
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropRight = cropRight,
            cropBottom = cropBottom
        )
        syncLayerPlan()
    }

    fun updatePipCrop(itemId: String, cropLeft: Float, cropTop: Float, cropRight: Float, cropBottom: Float): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        return updateLayerItem(item.copy(
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropRight = cropRight,
            cropBottom = cropBottom
        ))
    }

    fun liveUpdatePipCutout(itemId: String, chromaEnabled: Boolean, chromaColorArgb: Int, chromaThreshold: Float, chromaBlend: Float) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val cur = _layerItems[idx]
        _layerItems[idx] = cur.copy(
            chromaEnabled = chromaEnabled,
            chromaColorArgb = chromaColorArgb,
            chromaThreshold = chromaThreshold,
            chromaBlend = chromaBlend
        )
        syncLayerPlan()
    }

    fun updatePipCutout(itemId: String, chromaEnabled: Boolean, chromaColorArgb: Int, chromaThreshold: Float, chromaBlend: Float): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        return updateLayerItem(item.copy(
            chromaEnabled = chromaEnabled,
            chromaColorArgb = chromaColorArgb,
            chromaThreshold = chromaThreshold,
            chromaBlend = chromaBlend
        ))
    }

    // Phase 8: Sticker Replace, Color Adjustment, and Captions Generator
    fun replaceSelectedSticker(newTextOrEmoji: String = "", newMediaPath: String = ""): Boolean {
        val selectedId = _uiState.value.selectedOverlayId ?: return false
        val item = _layerItems.firstOrNull { it.id == selectedId } ?: return false

        var intrinsicW = item.intrinsicWidth
        var intrinsicH = item.intrinsicHeight
        if (newMediaPath.isNotEmpty()) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(newMediaPath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    intrinsicW = opts.outWidth
                    intrinsicH = opts.outHeight
                }
            } catch (_: Throwable) {}
        } else if (newTextOrEmoji.isNotEmpty()) {
            intrinsicW = 0
            intrinsicH = 0
        }

        val updated = item.copy(
            text = newTextOrEmoji,
            mediaPath = newMediaPath,
            intrinsicWidth = intrinsicW,
            intrinsicHeight = intrinsicH
        )
        return updateLayerItem(updated)
    }

    fun liveUpdateOverlayAdjust(
        itemId: String,
        opacity: Float,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val cur = _layerItems[idx]
        _layerItems[idx] = cur.copy(
            transform = cur.transform.copy(opacity = opacity.coerceIn(0f, 1f)),
            adjustBrightness = brightness.coerceIn(-0.5f, 0.5f),
            adjustContrast = contrast.coerceIn(0.5f, 1.8f),
            adjustSaturation = saturation.coerceIn(0.0f, 2.0f)
        )
        syncLayerPlan()
    }

    fun updateOverlayAdjust(
        itemId: String,
        opacity: Float,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ): Boolean {
        val item = _layerItems.firstOrNull { it.id == itemId } ?: return false
        return updateLayerItem(item.copy(
            transform = item.transform.copy(opacity = opacity.coerceIn(0f, 1f)),
            adjustBrightness = brightness.coerceIn(-0.5f, 0.5f),
            adjustContrast = contrast.coerceIn(0.5f, 1.8f),
            adjustSaturation = saturation.coerceIn(0.0f, 2.0f)
        ))
    }

    fun generateCaptionsFromText(
        rawText: String,
        startUs: Long = 0L,
        endUs: Long = 0L
    ): Int {
        val timelineDuration = msToUs(_uiState.value.project.clips.sumOf { it.durationMs }).coerceAtLeast(1_000_000L)
        val effectiveStart = startUs.coerceAtLeast(0L)
        val effectiveEnd = if (endUs > effectiveStart) endUs else timelineDuration
        val cues = CaptionParser.parseInputToTimedCues(
            input = rawText,
            startUs = effectiveStart,
            endUs = effectiveEnd,
            minDurationUs = OverlayItem.MIN_DURATION_US
        )
        return generateTimedCaptions(cues)
    }

    fun generateCaptions(
        phrases: List<String>,
        startUs: Long = 0L,
        endUs: Long = 0L
    ): Int {
        val timelineDuration = msToUs(_uiState.value.project.clips.sumOf { it.durationMs }).coerceAtLeast(1_000_000L)
        val effectiveStart = startUs.coerceAtLeast(0L)
        val effectiveEnd = if (endUs > effectiveStart) endUs else timelineDuration
        val cues = CaptionParser.distributePhrasesToTimedCues(
            phrases = phrases,
            startUs = effectiveStart,
            endUs = effectiveEnd,
            minDurationUs = OverlayItem.MIN_DURATION_US
        )
        return generateTimedCaptions(cues)
    }

    fun generateTimedCaptions(cues: List<CaptionCue>): Int {
        if (cues.isEmpty()) return 0
        if (!canAddLayerItem(_layerItems, LayerType.TEXT)) {
            showToast(getLayerCapLimitMessage(LayerType.TEXT))
            return 0
        }

        // Single atomic undo checkpoint for the entire caption batch
        pushUndoState()

        var addedCount = 0
        var lastAddedId: String? = null
        var nextZ = getTopZIndex()
        for (cue in cues) {
            if (!canAddLayerItem(_layerItems, LayerType.TEXT)) {
                showToast(getLayerCapLimitMessage(LayerType.TEXT))
                break
            }
            if (cue.endUs - cue.startUs < OverlayItem.MIN_DURATION_US) continue
            val assignedLane = findNextAvailableLane(cue.startUs, cue.endUs)
            val id = UUID.randomUUID().toString()
            val item = OverlayItem(
                id = id,
                type = LayerType.TEXT,
                laneIndex = assignedLane,
                zIndex = nextZ++,
                startUs = cue.startUs,
                endUs = cue.endUs,
                text = cue.text,
                textColorArgb = 0xFFFFFFFF.toInt(),
                bgStyle = 1,
                transform = ClipTransform(scale = 0.85f, panY = 0.65f)
            )
            _layerItems.add(item)
            lastAddedId = id
            addedCount++
        }

        if (addedCount > 0) {
            syncLayerPlan()
            val focusId = lastAddedId
            if (focusId != null) {
                _uiState.update {
                    it.copy(
                        selectedOverlayId = focusId,
                        selectedClipIndex = null,
                        selectedJunctionIndex = null,
                        focusMode = TimelineFocusState.S4_TEXT_ITEM(focusId)
                    )
                }
            }
            updateProject(_uiState.value.project, recordUndo = false)
        }
        return addedCount
    }

    fun copyLayerItem(itemId: String): OverlayItem? {
        val existing = _layerItems.firstOrNull { it.id == itemId } ?: return null
        if (!canAddLayerItem(_layerItems, existing.type)) {
            showToast(getLayerCapLimitMessage(existing.type))
            return null
        }

        val durUs = existing.durationUs
        val newStartUs: Long
        val newEndUs: Long

        if (existing.type == LayerType.FILTER || existing.type == LayerType.EFFECT) {
            val totalUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs }).coerceAtLeast(OverlayItem.MIN_DURATION_US)
            val sameType = _layerItems.filter { it.type == existing.type }.sortedBy { it.startUs }

            var foundSlot: Pair<Long, Long>? = null

            // 1. Check gap immediately after existing.endUs
            val afterItems = sameType.filter { it.startUs >= existing.endUs }
            val firstNextStart = afterItems.firstOrNull()?.startUs ?: totalUs
            if (firstNextStart - existing.endUs >= OverlayItem.MIN_DURATION_US) {
                foundSlot = Pair(existing.endUs, minOf(existing.endUs + durUs, firstNextStart))
            } else {
                // 2. Search gaps between subsequent items
                for (i in afterItems.indices) {
                    val gapStart = afterItems[i].endUs
                    val gapEnd = afterItems.getOrNull(i + 1)?.startUs ?: totalUs
                    if (gapEnd - gapStart >= OverlayItem.MIN_DURATION_US) {
                        foundSlot = Pair(gapStart, minOf(gapStart + durUs, gapEnd))
                        break
                    }
                }
            }

            // 3. If no slot found after existing.endUs, search earlier gaps from 0L
            if (foundSlot == null) {
                if (sameType.isNotEmpty() && sameType.first().startUs >= OverlayItem.MIN_DURATION_US) {
                    foundSlot = Pair(0L, minOf(durUs, sameType.first().startUs))
                } else {
                    for (i in 0 until (sameType.indexOf(existing).takeIf { it >= 0 } ?: (sameType.size - 1))) {
                        val gapStart = sameType[i].endUs
                        val gapEnd = sameType[i + 1].startUs
                        if (gapEnd - gapStart >= OverlayItem.MIN_DURATION_US) {
                            foundSlot = Pair(gapStart, minOf(gapStart + durUs, gapEnd))
                            break
                        }
                    }
                }
            }

            if (foundSlot == null) {
                showToast("Not enough space between existing filters/effects")
                return null
            }

            newStartUs = foundSlot.first
            newEndUs = foundSlot.second
        } else {
            newStartUs = existing.endUs
            newEndUs = newStartUs + durUs
        }

        val copy = existing.copy(
            id = UUID.randomUUID().toString(),
            laneIndex = if (existing.type == LayerType.FILTER || existing.type == LayerType.EFFECT) 0 else -1,
            startUs = newStartUs,
            endUs = newEndUs,
            zIndex = 0
        )
        return if (addLayerItem(copy)) _layerItems.lastOrNull() else null
    }

    fun duplicateSelectedOverlay(): OverlayItem? = copySelectedOverlay()

    private fun captureCurrentSnapshot(
        projectSnapshot: Project = _uiState.value.project,
        timeMapsSnapshot: Map<String, TimeMap> = clipTimeMaps,
        aspectRatioSnapshot: AspectRatioMode = _uiState.value.aspectRatioMode,
        junctionModesSnapshot: Map<Int, Int> = junctionTransitionModes
    ): EditorUndoSnapshot = EditorUndoSnapshot(
        project = projectSnapshot,
        timeMaps = timeMapsSnapshot,
        volumes = clipVolumes,
        transforms = clipTransforms,
        colorAdjusts = clipColorAdjusts,
        backgrounds = clipBackgrounds,
        layerItems = _layerItems,
        aspectRatioMode = aspectRatioSnapshot,
        junctionTransitionModes = junctionModesSnapshot
    )

    private fun pushUndoState(
        projectSnapshot: Project = _uiState.value.project,
        timeMapsSnapshot: Map<String, TimeMap> = clipTimeMaps,
        aspectRatioSnapshot: AspectRatioMode = _uiState.value.aspectRatioMode,
        junctionModesSnapshot: Map<Int, Int> = junctionTransitionModes
    ) {
        undoHistory.push(
            captureCurrentSnapshot(
                projectSnapshot = projectSnapshot,
                timeMapsSnapshot = timeMapsSnapshot,
                aspectRatioSnapshot = aspectRatioSnapshot,
                junctionModesSnapshot = junctionModesSnapshot
            )
        )
        _uiState.update {
            it.copy(
                canUndo = undoHistory.canUndo,
                canRedo = undoHistory.canRedo
            )
        }
    }

    fun selectOverlay(itemId: String?) {
        if (itemId == null) {
            _uiState.update {
                it.copy(
                    selectedOverlayId = null,
                    focusMode = TimelineFocusState.S0_NONE
                )
            }
            return
        }
        val item = _layerItems.firstOrNull { it.id == itemId }
        val newFocus = when (item?.type) {
            LayerType.TEXT -> TimelineFocusState.S4_TEXT_ITEM(itemId)
            LayerType.STICKER, LayerType.MOSAIC -> TimelineFocusState.S3_STICKER_ITEM(itemId)
            LayerType.VIDEO, LayerType.IMAGE -> TimelineFocusState.S2_PIP_ITEM(itemId)
            LayerType.AUDIO -> TimelineFocusState.S6_AUDIO_ITEM(itemId)
            LayerType.FILTER, LayerType.EFFECT -> TimelineFocusState.S7_FILTER_EFFECT_ITEM(itemId)
            else -> TimelineFocusState.S0_NONE
        }
        _uiState.update {
            it.copy(
                selectedOverlayId = itemId,
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                focusMode = newFocus
            )
        }
    }

    fun setFocusMode(mode: TimelineFocusState) {
        val itemId = mode.currentSelectedItemId
        _uiState.update {
            it.copy(
                focusMode = mode,
                selectedOverlayId = itemId,
                selectedClipIndex = if (mode is TimelineFocusState.S0_NONE) it.selectedClipIndex else null,
                selectedJunctionIndex = if (mode is TimelineFocusState.S0_NONE) it.selectedJunctionIndex else null
            )
        }
    }

    fun getOverlayItem(itemId: String): OverlayItem? =
        _layerItems.firstOrNull { it.id == itemId }

    fun getOverlayOpacity(itemId: String): Float =
        getOverlayItem(itemId)?.transform?.opacity ?: 1.0f

    fun splitSelectedOverlayAtPlayhead(playheadUs: Long): String? {
        val selectedId = _uiState.value.selectedOverlayId ?: return null
        val idx = _layerItems.indexOfFirst { it.id == selectedId }
        if (idx < 0) return null
        val item = _layerItems[idx]

        val minSplitUs = item.startUs + OverlayItem.MIN_DURATION_US
        val maxSplitUs = item.endUs - OverlayItem.MIN_DURATION_US
        if (playheadUs < minSplitUs || playheadUs > maxSplitUs) return null

        if (!canAddLayerItem(_layerItems, item.type)) {
            showToast(getLayerCapLimitMessage(item.type))
            return null
        }

        pushUndoState()

        val splitOffsetUs = playheadUs - item.startUs
        val leftKeyframes = item.keyframes.filter { it.offsetUs <= splitOffsetUs }
        val rightKeyframes = item.keyframes.filter { it.offsetUs >= splitOffsetUs }.map {
            it.copy(offsetUs = (it.offsetUs - splitOffsetUs).coerceAtLeast(0L))
        }

        val leftItem = item.copy(endUs = playheadUs, naturalDurationUs = item.naturalDurationUs, keyframes = leftKeyframes)
        val rightId = UUID.randomUUID().toString()
        val topZ = getTopZIndex()
        val leftDurationUs = playheadUs - item.startUs
        val rightSourceInUs = if (item.type == LayerType.AUDIO || item.type == LayerType.VIDEO) {
            item.sourceInUs + (leftDurationUs.toDouble() * item.speed.toDouble()).toLong()
        } else {
            item.sourceInUs
        }
        val rightItem = item.copy(
            id = rightId,
            startUs = playheadUs,
            endUs = item.endUs,
            sourceInUs = rightSourceInUs,
            naturalDurationUs = item.naturalDurationUs,
            zIndex = topZ,
            keyframes = rightKeyframes
        )

        _layerItems[idx] = leftItem
        _layerItems.add(rightItem)
        syncLayerPlan()

        val newFocus = when (item.type) {
            LayerType.VIDEO, LayerType.IMAGE -> TimelineFocusState.S2_PIP_ITEM(rightId)
            LayerType.TEXT -> TimelineFocusState.S4_TEXT_ITEM(rightId)
            LayerType.STICKER, LayerType.MOSAIC -> TimelineFocusState.S3_STICKER_ITEM(rightId)
            LayerType.AUDIO -> TimelineFocusState.S6_AUDIO_ITEM(rightId)
            LayerType.FILTER, LayerType.EFFECT -> TimelineFocusState.S7_FILTER_EFFECT_ITEM(rightId)
            else -> _uiState.value.focusMode
        }

        _uiState.update {
            it.copy(
                selectedOverlayId = rightId,
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                focusMode = newFocus
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
        return rightId
    }

    fun copySelectedOverlay(): OverlayItem? {
        val selectedId = _uiState.value.selectedOverlayId ?: return null
        return copyLayerItem(selectedId)
    }

    fun deleteSelectedOverlay(): Boolean {
        val selectedId = _uiState.value.selectedOverlayId ?: return false
        val idx = _layerItems.indexOfFirst { it.id == selectedId }
        if (idx < 0) return false
        val item = _layerItems[idx]

        pushUndoState()
        _layerItems.removeAt(idx)
        syncLayerPlan()

        val nextFocus = when (item.type) {
            LayerType.AUDIO -> TimelineFocusState.S5_AUDIO_GROUP
            LayerType.VIDEO, LayerType.IMAGE -> TimelineFocusState.S1_PIP_GROUP
            else -> TimelineFocusState.S0_NONE
        }

        _uiState.update {
            it.copy(
                selectedOverlayId = null,
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                focusMode = nextFocus
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
        return true
    }

    fun rotateSelectedOverlay(): ClipTransform? {
        val selectedId = _uiState.value.selectedOverlayId ?: return null
        val idx = _layerItems.indexOfFirst { it.id == selectedId }
        if (idx < 0) return null
        val item = _layerItems[idx]

        val current = item.transform
        val nextDeg = (((Math.round(current.rotationDeg / 90.0) * 90 + 90) % 360 + 360) % 360).toFloat()
        val newTransform = current.copy(rotationDeg = nextDeg)

        pushUndoState()
        val topZ = getTopZIndex()
        _layerItems[idx] = item.copy(transform = newTransform, zIndex = topZ)
        syncLayerPlan()
        _uiState.update {
            it.copy(
                selectedOverlayId = item.id,
                selectedClipIndex = null,
                selectedJunctionIndex = null
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
        return newTransform
    }

    fun flipSelectedOverlay(): ClipTransform? {
        val selectedId = _uiState.value.selectedOverlayId ?: return null
        val idx = _layerItems.indexOfFirst { it.id == selectedId }
        if (idx < 0) return null
        val item = _layerItems[idx]

        val current = item.transform
        val newTransform = current.copy(isFlippedH = !current.isFlippedH)

        pushUndoState()
        val topZ = getTopZIndex()
        _layerItems[idx] = item.copy(transform = newTransform, zIndex = topZ)
        syncLayerPlan()
        _uiState.update {
            it.copy(
                selectedOverlayId = item.id,
                selectedClipIndex = null,
                selectedJunctionIndex = null
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
        return newTransform
    }

    fun liveOverlayTrim(itemId: String, newStartUs: Long, newEndUs: Long) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _layerItems[idx]
        val totalDurationUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs })
        val (clampedStartUs, clampedEndUs) = clampItemTrimBoundsOnRow(
            items = _layerItems,
            itemId = itemId,
            candidateStartUs = newStartUs,
            candidateEndUs = newEndUs,
            totalProjectDurationUs = totalDurationUs
        )
        if (clampedEndUs - clampedStartUs < OverlayItem.MIN_DURATION_US) return
        val deltaStartUs = clampedStartUs - item.startUs
        val newSourceInUs = if (item.type == LayerType.AUDIO || item.type == LayerType.VIDEO) {
            (item.sourceInUs + (deltaStartUs.toDouble() * item.speed.toDouble()).toLong()).coerceAtLeast(0L)
        } else item.sourceInUs
        _layerItems[idx] = item.copy(startUs = clampedStartUs, endUs = clampedEndUs, sourceInUs = newSourceInUs)
        syncLayerPlan()
    }

    fun commitOverlayTrim(itemId: String, newStartUs: Long, newEndUs: Long) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _layerItems[idx]
        val totalDurationUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs })
        val (clampedStartUs, clampedEndUs) = clampItemTrimBoundsOnRow(
            items = _layerItems,
            itemId = itemId,
            candidateStartUs = newStartUs,
            candidateEndUs = newEndUs,
            totalProjectDurationUs = totalDurationUs
        )
        if (clampedEndUs - clampedStartUs < OverlayItem.MIN_DURATION_US) return

        pushUndoState()
        val topZ = getTopZIndex()
        val deltaStartUs = clampedStartUs - item.startUs
        val newSourceInUs = if (item.type == LayerType.AUDIO || item.type == LayerType.VIDEO) {
            (item.sourceInUs + (deltaStartUs.toDouble() * item.speed.toDouble()).toLong()).coerceAtLeast(0L)
        } else item.sourceInUs
        _layerItems[idx] = item.copy(startUs = clampedStartUs, endUs = clampedEndUs, sourceInUs = newSourceInUs, zIndex = topZ)
        syncLayerPlan()
        _uiState.update {
            it.copy(
                selectedOverlayId = itemId,
                selectedClipIndex = null,
                selectedJunctionIndex = null
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
    }

    fun alignOverlayEdge(
        itemId: String,
        isRightHandle: Boolean,
        alignment: OverlayEndAlignment
    ): Boolean {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return false
        val item = _layerItems[idx]
        val tl = ProgramTimeline(_uiState.value.project)
        if (tl.n == 0) return false

        val totalDurationUs = msToUs(tl.totalMs)
        val (targetStartUs, targetEndUs) = resolveOverlayHandleSnapBounds(
            itemStartUs = item.startUs,
            itemEndUs = item.endUs,
            isRightHandle = isRightHandle,
            action = alignment,
            tl = tl,
            minSpanUs = OverlayItem.MIN_DURATION_US
        )

        val (clampedStartUs, clampedEndUs) = clampItemTrimBoundsOnRow(
            items = _layerItems,
            itemId = itemId,
            candidateStartUs = targetStartUs,
            candidateEndUs = targetEndUs,
            totalProjectDurationUs = totalDurationUs
        )

        if (clampedStartUs == item.startUs && clampedEndUs == item.endUs) return false

        pushUndoState()
        val topZ = getTopZIndex()
        val deltaStartUs = clampedStartUs - item.startUs
        val newSourceInUs = if (item.type == LayerType.AUDIO || item.type == LayerType.VIDEO) {
            (item.sourceInUs + (deltaStartUs.toDouble() * item.speed.toDouble()).toLong()).coerceAtLeast(0L)
        } else item.sourceInUs

        _layerItems[idx] = item.copy(
            startUs = clampedStartUs,
            endUs = clampedEndUs,
            sourceInUs = newSourceInUs,
            zIndex = topZ
        )
        syncLayerPlan()
        _uiState.update {
            it.copy(
                selectedOverlayId = itemId,
                selectedClipIndex = null,
                selectedJunctionIndex = null
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
        return true
    }

    fun alignOverlayEdge(itemId: String, playheadUs: Long, alignment: OverlayEndAlignment): Boolean {
        val isRightHandle = when (alignment) {
            OverlayEndAlignment.LEFT_CLIP_END,
            OverlayEndAlignment.LEFT_VIDEO_END -> false
            OverlayEndAlignment.RIGHT_CLIP_END,
            OverlayEndAlignment.RIGHT_VIDEO_END -> true
            OverlayEndAlignment.CLIP_START,
            OverlayEndAlignment.VIDEO_START -> false
            OverlayEndAlignment.CLIP_END,
            OverlayEndAlignment.VIDEO_END -> true
        }
        return alignOverlayEdge(itemId, isRightHandle, alignment)
    }

    fun extendOverlayEndTime(itemId: String, playheadUs: Long, alignment: OverlayEndAlignment): Boolean {
        return alignOverlayEdge(itemId, playheadUs, alignment)
    }

    fun liveOverlayMove(itemId: String, newLaneIndex: Int, newStartUs: Long, newEndUs: Long) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _layerItems[idx]

        val totalTimelineUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs }).coerceAtLeast(OverlayItem.MIN_DURATION_US)
        var clampedStartUs = newStartUs
        var clampedEndUs = newEndUs
        if (item.type == LayerType.FILTER || item.type == LayerType.EFFECT) {
            val sameTypeNeighbors = _layerItems.filter { it.id != itemId && it.type == item.type }
            val prevSameTypeEndUs = sameTypeNeighbors.filter { it.endUs <= item.startUs }.maxOfOrNull { it.endUs } ?: 0L
            val nextSameTypeStartUs = sameTypeNeighbors.filter { it.startUs >= item.endUs }.minOfOrNull { it.startUs } ?: totalTimelineUs
            val dur = item.durationUs
            val minStart = maxOf(0L, prevSameTypeEndUs)
            val maxStart = (minOf(nextSameTypeStartUs, totalTimelineUs) - dur).coerceAtLeast(minStart)
            clampedStartUs = newStartUs.coerceIn(minStart, maxStart)
            clampedEndUs = clampedStartUs + dur
        }

        _layerItems[idx] = item.copy(
            laneIndex = newLaneIndex,
            startUs = clampedStartUs,
            endUs = clampedEndUs
        )
        syncLayerPlan()
    }

    fun commitOverlayMove(itemId: String, newLaneIndex: Int, newStartUs: Long, newEndUs: Long) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _layerItems[idx]

        val totalTimelineUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs }).coerceAtLeast(OverlayItem.MIN_DURATION_US)
        var clampedStartUs = newStartUs
        var clampedEndUs = newEndUs
        if (item.type == LayerType.FILTER || item.type == LayerType.EFFECT) {
            val sameTypeNeighbors = _layerItems.filter { it.id != itemId && it.type == item.type }
            val prevSameTypeEndUs = sameTypeNeighbors.filter { it.endUs <= item.startUs }.maxOfOrNull { it.endUs } ?: 0L
            val nextSameTypeStartUs = sameTypeNeighbors.filter { it.startUs >= item.endUs }.minOfOrNull { it.startUs } ?: totalTimelineUs
            val dur = item.durationUs
            val minStart = maxOf(0L, prevSameTypeEndUs)
            val maxStart = (minOf(nextSameTypeStartUs, totalTimelineUs) - dur).coerceAtLeast(minStart)
            clampedStartUs = newStartUs.coerceIn(minStart, maxStart)
            clampedEndUs = clampedStartUs + dur
        }

        pushUndoState()
        val topZ = getTopZIndex()
        _layerItems[idx] = item.copy(
            laneIndex = newLaneIndex,
            startUs = clampedStartUs,
            endUs = clampedEndUs,
            zIndex = topZ
        )
        syncLayerPlan()
        _uiState.update {
            it.copy(
                selectedOverlayId = itemId,
                selectedClipIndex = null,
                selectedJunctionIndex = null
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
    }

    fun updateAudioVolume(itemId: String, volume: Float): Boolean {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return false
        val item = _layerItems[idx]
        val clamped = volume.coerceIn(0.0f, 2.0f)
        pushUndoState()
        _layerItems[idx] = item.copy(volume = clamped)
        syncLayerPlan()
        updateProject(_uiState.value.project, recordUndo = false)
        return true
    }

    fun updateAudioFade(itemId: String, fadeInUs: Long, fadeOutUs: Long): Boolean {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return false
        val item = _layerItems[idx]
        val duration = item.endUs - item.startUs
        val maxFade = (duration / 2L).coerceAtLeast(0L)
        val clampedIn = fadeInUs.coerceIn(0L, maxFade)
        val clampedOut = fadeOutUs.coerceIn(0L, maxFade)
        pushUndoState()
        _layerItems[idx] = item.copy(fadeInUs = clampedIn, fadeOutUs = clampedOut)
        syncLayerPlan()
        updateProject(_uiState.value.project, recordUndo = false)
        return true
    }

    fun updateAudioSpeed(itemId: String, speed: Float): Boolean {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return false
        val item = _layerItems[idx]
        val clampedSpeed = speed.coerceIn(0.2f, 4.0f)
        pushUndoState()

        val newEndUs = if (item.type == LayerType.VIDEO) {
            val totalProjectDurationUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs })
                .coerceAtLeast(OverlayItem.MIN_DURATION_US)
            val currentItemDurationUs = (item.endUs - item.startUs).coerceAtLeast(OverlayItem.MIN_DURATION_US)
            val currentSpeed = if (item.speed > 0f) item.speed.toDouble() else 1.0
            val sourceDurationUs = (currentItemDurationUs.toDouble() * currentSpeed).toLong()
            val newDurationUs = (sourceDurationUs.toDouble() / clampedSpeed.toDouble()).toLong()
                .coerceAtLeast(OverlayItem.MIN_DURATION_US)

            // Clamp against neighbor items on the same PIP row (laneIndex)
            val nextNeighbor = _layerItems.filter {
                it.laneIndex == item.laneIndex && it.id != item.id && it.startUs >= item.endUs
            }.minByOrNull { it.startUs }
            val maxAllowedEndUs = nextNeighbor?.startUs ?: totalProjectDurationUs
            minOf(item.startUs + newDurationUs, maxAllowedEndUs)
                .coerceAtLeast(item.startUs + OverlayItem.MIN_DURATION_US)
        } else {
            item.endUs
        }

        _layerItems[idx] = item.copy(speed = clampedSpeed, endUs = newEndUs)
        syncLayerPlan()
        updateProject(_uiState.value.project, recordUndo = false)
        return true
    }

    fun setOverlayOpacityLive(itemId: String, opacity: Float) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _layerItems[idx]
        val clamped = opacity.coerceIn(0f, 1f)
        _layerItems[idx] = item.copy(transform = item.transform.copy(opacity = clamped))
        syncLayerPlan()
    }

    fun commitOverlayOpacity(itemId: String, opacity: Float) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _layerItems[idx]
        val clamped = opacity.coerceIn(0f, 1f)

        pushUndoState()
        _layerItems[idx] = item.copy(transform = item.transform.copy(opacity = clamped))
        syncLayerPlan()
        updateProject(_uiState.value.project, recordUndo = false)
    }

    fun resolveTargetVisualOverlay(playheadUs: Long): Pair<Int, OverlayItem>? {
        val explicitId = _uiState.value.selectedOverlayId
            ?: when (val fm = _uiState.value.focusMode) {
                is TimelineFocusState.S2_PIP_ITEM -> fm.itemId
                is TimelineFocusState.S3_STICKER_ITEM -> fm.itemId
                is TimelineFocusState.S4_TEXT_ITEM -> fm.itemId
                else -> null
            }
        if (explicitId != null) {
            val idx = _layerItems.indexOfFirst { it.id == explicitId }
            if (idx >= 0) {
                val item = _layerItems[idx]
                if (item.type == LayerType.TEXT || item.type == LayerType.STICKER ||
                    item.type == LayerType.IMAGE || item.type == LayerType.VIDEO) {
                    return Pair(idx, item)
                }
            }
        }
        // Fallback: topmost visual overlay under playheadUs
        var topIdx = -1
        var topItem: OverlayItem? = null
        for (i in _layerItems.indices) {
            val item = _layerItems[i]
            if (item.type == LayerType.TEXT || item.type == LayerType.STICKER ||
                item.type == LayerType.IMAGE || item.type == LayerType.VIDEO) {
                if (playheadUs in item.startUs..item.endUs) {
                    if (topItem == null || item.zIndex > topItem.zIndex) {
                        topItem = item
                        topIdx = i
                    }
                }
            }
        }
        return if (topIdx >= 0 && topItem != null) Pair(topIdx, topItem) else null
    }

    fun addOrUpdateKeyframeAtPlayhead(playheadUs: Long): Boolean {
        val resolved = resolveTargetVisualOverlay(playheadUs) ?: return false
        val idx = resolved.first
        val item = resolved.second

        val effectivePlayheadUs = playheadUs.coerceIn(item.startUs, item.endUs)
        val localUs = (effectivePlayheadUs - item.startUs).coerceIn(0L, item.durationUs)
        val effTransform = animEvaluator.evaluate(item, effectivePlayheadUs)

        pushUndoState()

        val newKf = OverlayKeyframe(
            offsetUs = localUs,
            panX = effTransform.panX,
            panY = effTransform.panY,
            scale = effTransform.scale,
            rotationDeg = effTransform.rotationDeg,
            opacity = effTransform.opacity
        )

        val updated = item.keyframes.filterNot { kotlin.math.abs(it.offsetUs - localUs) <= 50_000L }.toMutableList()
        updated.add(newKf)
        updated.sortBy { it.offsetUs }

        _layerItems[idx] = item.copy(keyframes = updated)
        syncLayerPlan()
        _uiState.update {
            it.copy(
                selectedOverlayId = item.id,
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                focusMode = when (item.type) {
                    LayerType.VIDEO, LayerType.IMAGE -> TimelineFocusState.S2_PIP_ITEM(item.id)
                    LayerType.TEXT -> TimelineFocusState.S4_TEXT_ITEM(item.id)
                    LayerType.STICKER -> TimelineFocusState.S3_STICKER_ITEM(item.id)
                    else -> it.focusMode
                }
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
        return true
    }

    fun removeKeyframeAtPlayhead(playheadUs: Long): Boolean {
        val resolved = resolveTargetVisualOverlay(playheadUs) ?: return false
        val idx = resolved.first
        val item = resolved.second
        if (item.keyframes.isEmpty()) return false

        val effectivePlayheadUs = playheadUs.coerceIn(item.startUs, item.endUs)
        val localUs = (effectivePlayheadUs - item.startUs).coerceIn(0L, item.durationUs)
        val hasMatch = item.keyframes.any { kotlin.math.abs(it.offsetUs - localUs) <= 50_000L }
        if (!hasMatch) return false

        pushUndoState()
        val updated = item.keyframes.filterNot { kotlin.math.abs(it.offsetUs - localUs) <= 50_000L }
        _layerItems[idx] = item.copy(keyframes = updated)
        syncLayerPlan()
        _uiState.update {
            it.copy(
                selectedOverlayId = item.id,
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                focusMode = when (item.type) {
                    LayerType.VIDEO, LayerType.IMAGE -> TimelineFocusState.S2_PIP_ITEM(item.id)
                    LayerType.TEXT -> TimelineFocusState.S4_TEXT_ITEM(item.id)
                    LayerType.STICKER -> TimelineFocusState.S3_STICKER_ITEM(item.id)
                    else -> it.focusMode
                }
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
        return true
    }

    fun setOverlayTransformLive(itemId: String, transform: ClipTransform, playheadUs: Long? = null) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _layerItems[idx]
        if (item.keyframes.isNotEmpty() && playheadUs != null && playheadUs >= item.startUs && playheadUs <= item.endUs) {
            val localUs = (playheadUs - item.startUs).coerceIn(0L, item.durationUs)
            val newKf = OverlayKeyframe(
                offsetUs = localUs,
                panX = transform.panX,
                panY = transform.panY,
                scale = transform.scale,
                rotationDeg = transform.rotationDeg,
                opacity = transform.opacity
            )
            val updated = item.keyframes.filterNot { kotlin.math.abs(it.offsetUs - localUs) <= 50_000L }.toMutableList()
            updated.add(newKf)
            updated.sortBy { it.offsetUs }
            _layerItems[idx] = item.copy(keyframes = updated)
        } else {
            _layerItems[idx] = item.copy(transform = transform)
        }
        syncLayerPlan()
    }

    fun commitOverlayTransform(itemId: String, transform: ClipTransform, playheadUs: Long? = null) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _layerItems[idx]

        pushUndoState()
        val topZ = getTopZIndex()
        if (item.keyframes.isNotEmpty() && playheadUs != null && playheadUs >= item.startUs && playheadUs <= item.endUs) {
            val localUs = (playheadUs - item.startUs).coerceIn(0L, item.durationUs)
            val newKf = OverlayKeyframe(
                offsetUs = localUs,
                panX = transform.panX,
                panY = transform.panY,
                scale = transform.scale,
                rotationDeg = transform.rotationDeg,
                opacity = transform.opacity
            )
            val updated = item.keyframes.filterNot { kotlin.math.abs(it.offsetUs - localUs) <= 50_000L }.toMutableList()
            updated.add(newKf)
            updated.sortBy { it.offsetUs }
            _layerItems[idx] = item.copy(keyframes = updated, zIndex = topZ)
        } else {
            _layerItems[idx] = item.copy(transform = transform, zIndex = topZ)
        }
        syncLayerPlan()
        _uiState.update {
            it.copy(
                selectedOverlayId = itemId,
                selectedClipIndex = null,
                selectedJunctionIndex = null
            )
        }
        updateProject(_uiState.value.project, recordUndo = false)
    }

    fun applyTransformToAll(sourceClipId: String) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return
        val sourceTransform = clipTransforms[sourceClipId] ?: ClipTransform.DEFAULT

        pushUndoState(projectSnapshot = currentProject)

        for (c in currentProject.clips) {
            val current = clipTransforms[c.id] ?: ClipTransform.DEFAULT
            val newTransform = current.copy(
                scale = sourceTransform.scale,
                panX = sourceTransform.panX,
                panY = sourceTransform.panY,
                rotationDeg = sourceTransform.rotationDeg,
                isFlippedH = sourceTransform.isFlippedH
            )
            clipTransforms[c.id] = newTransform
            PreviewRenderer.setClipTransform(c.id, newTransform)
        }

        updateProject(currentProject, recordUndo = false)
    }

    fun commitClipScale(clipIndex: Int, scale: Float, panX: Float, panY: Float) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return
        val current = clipTransforms[clip.id] ?: ClipTransform.DEFAULT
        val newTransform = current.copy(
            scale = scale.coerceIn(0.5f, 4.0f),
            panX = panX.coerceIn(-1.0f, 1.0f),
            panY = panY.coerceIn(-1.0f, 1.0f)
        )

        pushUndoState(projectSnapshot = currentProject)

        clipTransforms[clip.id] = newTransform
        PreviewRenderer.setClipTransform(clip.id, newTransform)

        updateProject(currentProject, recordUndo = false)
    }

    fun commitApplyScaleToAll(scale: Float, panX: Float, panY: Float) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        setProcessingEdit(true)
        try {
            pushUndoState(projectSnapshot = currentProject)

            val clampedScale = scale.coerceIn(0.5f, 4.0f)
            val clampedPanX = panX.coerceIn(-1.0f, 1.0f)
            val clampedPanY = panY.coerceIn(-1.0f, 1.0f)

            for (c in currentProject.clips) {
                val current = clipTransforms[c.id] ?: ClipTransform.DEFAULT
                val newTransform = current.copy(
                    scale = clampedScale,
                    panX = clampedPanX,
                    panY = clampedPanY
                )
                clipTransforms[c.id] = newTransform
                PreviewRenderer.setClipTransform(c.id, newTransform)
            }

            updateProject(currentProject, recordUndo = false)
        } finally {
            setProcessingEdit(false)
        }
    }

    fun getClipVolume(clipId: String): Float = clipVolumes[clipId] ?: 1.0f

    fun getPreMuteVolume(clipId: String): Float = savedPreMuteVolumes[clipId] ?: 1.0f

    fun isAllClipsMuted(): Boolean {
        val clips = _uiState.value.project.clips
        return clips.isNotEmpty() && clips.all { (clipVolumes[it.id] ?: 1.0f) <= 0.0001f }
    }

    /**
     * Toggles timeline track-level mute state with 100% bidirectional sync:
     * - If any clip is currently unmuted (> 0f): saves non-zero volumes and mutes all clips to 0f.
     * - If all clips are currently muted (<= 0.0001f): restores each clip to its saved pre-mute volume (or 1.0f).
     */
    fun toggleTrackMute() {
        val currentProject = _uiState.value.project
        val clips = currentProject.clips
        if (clips.isEmpty()) return

        val currentlyAllMuted = isAllClipsMuted()

        pushUndoState(projectSnapshot = currentProject)

        if (!currentlyAllMuted) {
            // Mute everything: save current non-zero volumes
            for (c in clips) {
                val currentVol = clipVolumes[c.id] ?: 1.0f
                if (currentVol > 0.0001f) {
                    savedPreMuteVolumes[c.id] = currentVol
                }
                clipVolumes[c.id] = 0.0f
                ClipAudioContributor.setClipVolume(c.id, 0.0f)
            }
            ClipAudioContributor.isMainTrackMuted = true
            _uiState.update { it.copy(isMainTrackMuted = true) }
        } else {
            // Unmute everything: restore saved pre-mute volumes or default to 1.0f
            for (c in clips) {
                val restored = savedPreMuteVolumes[c.id]?.takeIf { it > 0.0001f } ?: 1.0f
                clipVolumes[c.id] = restored
                ClipAudioContributor.setClipVolume(c.id, restored)
            }
            ClipAudioContributor.isMainTrackMuted = false
            _uiState.update { it.copy(isMainTrackMuted = false) }
        }

        updateProject(currentProject, recordUndo = false)
    }

    fun setCoverFromTimelineFrame(timeUs: Long) {
        _uiState.update { it.copy(coverTimeUs = timeUs, customCoverPath = null) }
        scheduleAutoSave()
    }

    fun setCoverFromGalleryImage(path: String) {
        _uiState.update { it.copy(customCoverPath = path, coverTimeUs = 0L) }
        scheduleAutoSave()
    }

    fun commitClipVolume(clipIndex: Int, volume: Float) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return
        val clampedVol = volume.coerceIn(0.0f, 2.0f)

        pushUndoState(projectSnapshot = currentProject)

        clipVolumes[clip.id] = clampedVol
        ClipAudioContributor.setClipVolume(clip.id, clampedVol)
        if (clampedVol > 0.0001f) {
            savedPreMuteVolumes[clip.id] = clampedVol
        }

        val isAllMuted = isAllClipsMuted()
        ClipAudioContributor.isMainTrackMuted = isAllMuted
        _uiState.update { it.copy(isMainTrackMuted = isAllMuted) }

        updateProject(currentProject, recordUndo = false)
    }

    fun commitApplyVolumeToAll(volume: Float) {
        val currentProject = _uiState.value.project
        val clampedVol = volume.coerceIn(0.0f, 2.0f)

        setProcessingEdit(true)
        try {
            pushUndoState(projectSnapshot = currentProject)

            for (c in currentProject.clips) {
                clipVolumes[c.id] = clampedVol
                ClipAudioContributor.setClipVolume(c.id, clampedVol)
                if (clampedVol > 0.0001f) {
                    savedPreMuteVolumes[c.id] = clampedVol
                }
            }

            val isAllMuted = clampedVol <= 0.0001f
            ClipAudioContributor.isMainTrackMuted = isAllMuted
            _uiState.update { it.copy(isMainTrackMuted = isAllMuted) }

            updateProject(currentProject, recordUndo = false)
        } finally {
            setProcessingEdit(false)
        }
    }

    fun getClipNaturalDurationUs(clipIndex: Int): Long {
        val clip = _uiState.value.project.clips.getOrNull(clipIndex) ?: return 0L
        return clipNaturalDurations.getOrPut(clip.id) {
            getMediaFileDurationUs(clip)
        }
    }

    private fun getMediaFileDurationUs(clip: Clip): Long {
        pathNaturalDurationsCache[clip.path]?.let { return it }
        return try {
            val file = java.io.File(clip.path)
            if (file.exists()) {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                val dur = durStr?.toLongOrNull()?.let { msToUs(it) } ?: msToUs(clip.durationMs)
                pathNaturalDurationsCache[clip.path] = dur
                dur
            } else {
                msToUs(clip.durationMs)
            }
        } catch (_: Exception) {
            msToUs(clip.durationMs)
        }
    }

    fun commitClipTrim(clipIndex: Int, newInUs: Long, newOutUs: Long, targetDurationMs: Long? = null) {
        val currentProject = _uiState.value.project
        if (clipIndex !in currentProject.clips.indices) return
        val oldClip = currentProject.clips[clipIndex]
        val oldMap = clipTimeMaps[oldClip.id] ?: TimeMap(0L, msToUs(oldClip.durationMs))
        val speedNum = oldMap.speedNum
        val speedDen = oldMap.speedDen

        val otherClipsDurUs = msToUs(currentProject.clips.filterIndexed { idx, _ -> idx != clipIndex }.sumOf { it.durationMs })
        val proposedTimelineDurUs = if (speedNum == 0) {
            // Freeze clip (speedNum == 0): inUs == outUs == freezeSourceUs.
            // Allow changing timeline durationMs freely between 200ms and 30,000ms.
            val dur = targetDurationMs ?: run {
                val diffUs = kotlin.math.abs(newOutUs - newInUs)
                if (diffUs > 0L) usToMs(diffUs) else oldClip.durationMs
            }
            msToUs(dur.coerceIn(200L, 30_000L))
        } else {
            ((newOutUs - newInUs) * speedDen.toLong()) / speedNum.toLong()
        }

        val effectiveTimelineDurUs: Long
        val finalMap: TimeMap
        val newDurationMs: Long

        if (otherClipsDurUs + proposedTimelineDurUs > MAX_TIMELINE_DURATION_US) {
            val remainingUs = MAX_TIMELINE_DURATION_US - otherClipsDurUs
            if (remainingUs < OverlayItem.MIN_DURATION_US) {
                showToast("Maximum timeline duration is 15 minutes")
                return
            }
            effectiveTimelineDurUs = remainingUs
            newDurationMs = usToMs(remainingUs)
            if (speedNum == 0) {
                finalMap = TimeMap(oldMap.inUs, oldMap.inUs, 0, 100)
            } else {
                val allowedMediaDeltaUs = (remainingUs * speedNum.toLong()) / speedDen.toLong()
                val adjustedOutUs = newInUs + allowedMediaDeltaUs
                finalMap = TimeMap(newInUs, adjustedOutUs, speedNum, speedDen)
            }
            showToast("Maximum timeline duration is 15 minutes")
        } else {
            effectiveTimelineDurUs = proposedTimelineDurUs
            newDurationMs = usToMs(effectiveTimelineDurUs)
            if (newDurationMs < 200L) return
            finalMap = if (speedNum == 0) {
                TimeMap(oldMap.inUs, oldMap.inUs, 0, 100)
            } else {
                TimeMap(newInUs, newOutUs, speedNum, speedDen)
            }
        }

        val newClip = oldClip.copy(durationMs = newDurationMs)
        val newClips = currentProject.clips.toMutableList()
        newClips[clipIndex] = newClip
        clipTimeMaps[newClip.id] = finalMap

        // Adjust adjacent transitions if needed (Section 4 invariants: transition <= min(d1, d2) / 2)
        val newTransitions = currentProject.transitions.toMutableList()
        clampAdjacentTransitionDurations(newClips, newTransitions, clipIndex - 1, clipIndex)

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)
        pushUndoState(projectSnapshot = currentProject)
        clipTimeMaps[newClip.id] = finalMap
        updateProject(newProject, recordUndo = false)
    }

    fun commitGlobalTimelineTrim(globalStartUs: Long, globalEndUs: Long): Boolean {
        val currentProject = _uiState.value.project
        val n = currentProject.clips.size
        if (n == 0) return false
        val snapshot = getSnapshot()
        val mainIndex = snapshot.main
        val totalMs = mainIndex.totalMs
        val totalUs = msToUs(totalMs)
        val clampedStart = globalStartUs.coerceIn(0L, totalUs)
        val clampedEnd = globalEndUs.coerceIn(clampedStart + 200_000L, totalUs)
        if (clampedEnd - clampedStart < 200_000L) return false

        data class KeptClip(
            val originalIndex: Int,
            val clip: Clip,
            val newInUs: Long,
            val newOutUs: Long,
            val newDurationMs: Long
        )

        val kept = mutableListOf<KeptClip>()
        for (i in 0 until n) {
            val clipStartUs = msToUs(mainIndex.startMs[i])
            val clipEndUs = msToUs(mainIndex.endMs[i])

            if (clipEndUs <= clampedStart || clipStartUs >= clampedEnd) {
                continue
            }

            val oldClip = currentProject.clips[i]
            val oldMap = clipTimeMaps[oldClip.id] ?: TimeMap(0L, msToUs(oldClip.durationMs))
            val speedNum = oldMap.speedNum
            val speedDen = oldMap.speedDen

            val trimStartTimelineUs = maxOf(clipStartUs, clampedStart)
            val trimEndTimelineUs = minOf(clipEndUs, clampedEnd)
            val remainingTimelineUs = trimEndTimelineUs - trimStartTimelineUs

            val deltaStartTimelineUs = trimStartTimelineUs - clipStartUs
            val deltaEndTimelineUs = clipEndUs - trimEndTimelineUs

            val newInUs: Long
            val newOutUs: Long
            val newDurationMs: Long

            if (speedNum == 0) {
                newInUs = oldMap.inUs
                newOutUs = oldMap.inUs
                newDurationMs = usToMs(remainingTimelineUs).coerceIn(200L, 30_000L)
            } else {
                val deltaStartSourceUs = (deltaStartTimelineUs * speedNum.toLong()) / speedDen.toLong()
                val deltaEndSourceUs = (deltaEndTimelineUs * speedNum.toLong()) / speedDen.toLong()
                newInUs = oldMap.inUs + deltaStartSourceUs
                newOutUs = oldMap.outUs - deltaEndSourceUs
                val timelineDurUs = ((newOutUs - newInUs) * speedDen.toLong()) / speedNum.toLong()
                newDurationMs = usToMs(timelineDurUs).coerceAtLeast(200L)
            }

            kept.add(KeptClip(i, oldClip.copy(durationMs = newDurationMs), newInUs, newOutUs, newDurationMs))
        }

        if (kept.isEmpty()) {
            val midIdx = (n / 2).coerceIn(0, n - 1)
            val clip = currentProject.clips[midIdx]
            val oldMap = clipTimeMaps[clip.id] ?: TimeMap(0L, msToUs(clip.durationMs))
            kept.add(KeptClip(midIdx, clip.copy(durationMs = 200L), oldMap.inUs, oldMap.inUs + 200_000L, 200L))
        }

        pushUndoState()

        val newClips = kept.map { it.clip }
        val newTransitions = mutableListOf<Transition>()
        for (j in 0 until newClips.size - 1) {
            val origA = kept[j].originalIndex
            val origB = kept[j + 1].originalIndex
            val trans = if (origB == origA + 1 && origA < currentProject.transitions.size) {
                val existing = currentProject.transitions[origA]
                val maxAllowed = minOf(newClips[j].durationMs, newClips[j + 1].durationMs) / 2L
                if (existing.durationMs > maxAllowed) existing.copy(durationMs = maxAllowed) else existing
            } else {
                Transition(type = TransitionType.NONE, durationMs = 0L)
            }
            newTransitions.add(trans)
        }

        for (k in kept) {
            val oldMap = clipTimeMaps[k.clip.id] ?: TimeMap(0L, msToUs(k.clip.durationMs))
            clipTimeMaps[k.clip.id] = TimeMap(k.newInUs, k.newOutUs, oldMap.speedNum, oldMap.speedDen)
        }

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)
        updateProject(newProject, recordUndo = false)
        return true
    }

    fun getClipSpeed(clipId: String): Float {
        val map = clipTimeMaps[clipId] ?: return 1.0f
        return if (map.speedDen > 0 && map.speedNum > 0L) {
            val raw = map.speedNum.toFloat() / map.speedDen.toFloat()
            (kotlin.math.round(raw * 100f) / 100f).coerceIn(0.2f, 4.0f)
        } else 1.0f
    }

    fun getMaxSpeedForClip(clipIndex: Int): Float {
        val clip = _uiState.value.project.clips.getOrNull(clipIndex) ?: return 4.0f
        val oldMap = clipTimeMaps[clip.id] ?: TimeMap(0L, msToUs(clip.durationMs))
        if (oldMap.speedNum == 0) return 1.0f
        val sourceSpanUs = maxOf(200_000L, oldMap.outUs - oldMap.inUs)
        return (sourceSpanUs.toFloat() / 200_000f).coerceIn(0.2f, 4.0f)
    }

    fun setLiveClipSpeed(clipIndex: Int, speed: Float) {
        val currentProject = _uiState.value.project
        if (clipIndex !in currentProject.clips.indices) return
        val oldClip = currentProject.clips[clipIndex]
        val oldMap = clipTimeMaps[oldClip.id] ?: TimeMap(0L, msToUs(oldClip.durationMs))
        val sourceSpanUs = oldMap.outUs - oldMap.inUs

        val maxSpeed = (sourceSpanUs.toFloat() / 200_000f).coerceIn(0.2f, 4.0f)
        val clampedSpeed = speed.coerceIn(0.2f, maxSpeed)
        val rawSpeedNum = maxOf(1, (clampedSpeed * 100f).roundToInt())
        val speedDen = 100

        val timelineDurUs = (sourceSpanUs * speedDen.toLong()) / rawSpeedNum.toLong()

        val otherClipsDurUs = msToUs(currentProject.clips.filterIndexed { idx, _ -> idx != clipIndex }.sumOf { it.durationMs })
        val effectiveTimelineDurUs: Long
        val finalSpeedNum: Int

        if (otherClipsDurUs + timelineDurUs > MAX_TIMELINE_DURATION_US) {
            val remainingUs = MAX_TIMELINE_DURATION_US - otherClipsDurUs
            if (remainingUs < OverlayItem.MIN_DURATION_US) {
                showToast("Maximum timeline duration is 15 minutes")
                return
            }
            effectiveTimelineDurUs = remainingUs
            finalSpeedNum = maxOf(1, ((sourceSpanUs * speedDen.toLong()) / effectiveTimelineDurUs).toInt())
            showToast("Maximum timeline duration is 15 minutes")
        } else {
            effectiveTimelineDurUs = timelineDurUs
            finalSpeedNum = rawSpeedNum
        }

        val newDurMs = maxOf(200L, usToMs(effectiveTimelineDurUs))

        val newClip = oldClip.copy(durationMs = newDurMs)
        val newClips = currentProject.clips.toMutableList()
        newClips[clipIndex] = newClip

        val newTransitions = currentProject.transitions.toMutableList()
        clampAdjacentTransitionDurations(newClips, newTransitions, clipIndex - 1, clipIndex)

        clipTimeMaps[oldClip.id] = TimeMap(oldMap.inUs, oldMap.outUs, finalSpeedNum, speedDen)
        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)
        updateProject(newProject, recordUndo = false)
    }

    fun commitClipSpeed(
        clipIndex: Int,
        speed: Float,
        initialProject: Project? = null,
        initialTimeMaps: Map<String, TimeMap>? = null
    ) {
        val currentProject = _uiState.value.project
        if (clipIndex !in currentProject.clips.indices) return

        val snapProject = initialProject ?: currentProject
        val snapTimeMaps = initialTimeMaps ?: HashMap(clipTimeMaps)

        pushUndoState(projectSnapshot = snapProject, timeMapsSnapshot = snapTimeMaps)

        setLiveClipSpeed(clipIndex, speed)
    }

    fun commitApplySpeedToAll(
        speed: Float,
        initialProject: Project? = null,
        initialTimeMaps: Map<String, TimeMap>? = null
    ) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        setProcessingEdit(true)
        try {
            val snapProject = initialProject ?: currentProject
            val snapTimeMaps = initialTimeMaps ?: HashMap(clipTimeMaps)

            pushUndoState(projectSnapshot = snapProject, timeMapsSnapshot = snapTimeMaps)

            val speedDen = 100
            val newClips = currentProject.clips.toMutableList()
            val tempMaps = mutableMapOf<String, TimeMap>()
            var accumulatedUs = 0L
            var hitCap = false

            for (i in newClips.indices) {
                val clip = newClips[i]
                val oldMap = clipTimeMaps[clip.id] ?: TimeMap(0L, msToUs(clip.durationMs))
                val sourceSpanUs = oldMap.outUs - oldMap.inUs
                val maxSpeed = (sourceSpanUs.toFloat() / 200_000f).coerceIn(0.2f, 4.0f)
                val clampedSpeed = speed.coerceIn(0.2f, maxSpeed)
                val rawSpeedNum = maxOf(1, (clampedSpeed * 100f).roundToInt())

                val timelineDurUs = (sourceSpanUs * speedDen.toLong()) / rawSpeedNum.toLong()

                if (accumulatedUs + timelineDurUs <= MAX_TIMELINE_DURATION_US) {
                    val newDurMs = maxOf(200L, usToMs(timelineDurUs))
                    newClips[i] = clip.copy(durationMs = newDurMs)
                    tempMaps[clip.id] = TimeMap(oldMap.inUs, oldMap.outUs, rawSpeedNum, speedDen)
                    accumulatedUs += timelineDurUs
                } else {
                    val remainingUs = MAX_TIMELINE_DURATION_US - accumulatedUs
                    if (remainingUs >= OverlayItem.MIN_DURATION_US) {
                        val newDurMs = usToMs(remainingUs)
                        newClips[i] = clip.copy(durationMs = newDurMs)
                        val adjustedSpeedNum = maxOf(1, ((sourceSpanUs * speedDen.toLong()) / remainingUs).toInt())
                        tempMaps[clip.id] = TimeMap(oldMap.inUs, oldMap.outUs, adjustedSpeedNum, speedDen)
                        accumulatedUs = MAX_TIMELINE_DURATION_US
                    } else {
                        tempMaps[clip.id] = oldMap
                    }
                    hitCap = true
                    break
                }
            }

            if (hitCap) {
                showToast("Maximum timeline duration is 15 minutes")
            }

            clipTimeMaps.putAll(tempMaps)

            val newTransitions = currentProject.transitions.toMutableList()
            clampAdjacentTransitionDurations(newClips, newTransitions)

            val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)
            updateProject(newProject, recordUndo = false)
        } finally {
            setProcessingEdit(false)
        }
    }

    fun revertSpeedEdit(originalProject: Project, originalTimeMaps: Map<String, TimeMap>) {
        clipTimeMaps.clear()
        clipTimeMaps.putAll(originalTimeMaps)
        updateProject(originalProject, recordUndo = false)
    }

    fun splitAtPlayhead(playheadUs: Long, preferredClipIndex: Int? = null): SplitResult? {
        val currentProject = _uiState.value.project
        val tl = ProgramTimeline(currentProject)
        if (tl.n == 0) {
            showToast("No clips to split")
            return null
        }

        val tMs = usToMs(playheadUs)

        // 1. Exact Target Clip & Local Cut Position at playhead.timeUs
        val targetK: Int = if (preferredClipIndex != null && preferredClipIndex in 0 until tl.n) {
            val clipStartUs = msToUs(tl.startMs[preferredClipIndex])
            val clipEndUs = msToUs(tl.endMs[preferredClipIndex])
            if (playheadUs > clipStartUs && playheadUs < clipEndUs) {
                preferredClipIndex
            } else {
                val candidate = tl.lastStartAtOrBefore(tMs)
                if (candidate in 0 until tl.n && tMs >= tl.startMs[candidate] && tMs < tl.endMs[candidate]) {
                    candidate
                } else -1
            }
        } else {
            val candidate = tl.lastStartAtOrBefore(tMs)
            if (candidate in 0 until tl.n && tMs >= tl.startMs[candidate] && tMs < tl.endMs[candidate]) {
                candidate
            } else -1
        }

        if (targetK !in 0 until tl.n) {
            showToast("No clip under playhead to split")
            return null
        }

        // 2. Enforce 0.2s (200 ms / 200_000L us) Minimum Clip Duration Rule
        val startUs = msToUs(tl.startMs[targetK])
        val localCutUs = playheadUs - startUs
        val clipDurationUs = msToUs(tl.durMs[targetK])

        if (localCutUs < 200_000L || (clipDurationUs - localCutUs) < 200_000L) {
            showToast("Cannot split: clips must be at least 0.2s")
            return null
        }

        // 3. Frame-Accurate TimeMap Split & Natural Duration Preservation
        setProcessingEdit(true)
        try {
            val oldClip = currentProject.clips[targetK]
            val oldMap = clipTimeMaps[oldClip.id] ?: TimeMap(0L, msToUs(oldClip.durationMs))

            val leftDurMs = usToMs(localCutUs)
            val alignedLocalCutUs = msToUs(leftDurMs)
            val splitSourceUs = msToUs(usToMs(oldMap.sourceUs(alignedLocalCutUs)))
            val rightDurMs = oldClip.durationMs - leftDurMs

            val leftMap = TimeMap(
                inUs = oldMap.inUs,
                outUs = splitSourceUs,
                speedNum = oldMap.speedNum,
                speedDen = oldMap.speedDen
            )
            val rightClipId = java.util.UUID.randomUUID().toString()
            val rightMap = TimeMap(
                inUs = splitSourceUs,
                outUs = oldMap.outUs,
                speedNum = oldMap.speedNum,
                speedDen = oldMap.speedDen
            )

            val leftClip = oldClip.copy(durationMs = leftDurMs)
            val rightClip = oldClip.copy(id = rightClipId, durationMs = rightDurMs)

            val newClips = currentProject.clips.toMutableList()
            newClips[targetK] = leftClip
            newClips.add(targetK + 1, rightClip)

            // 4. Transitions: insert NONE at junction targetK and preserve existing transitions
            val newTransitions = ArrayList<Transition>(currentProject.transitions.size + 1)
            for (i in 0 until targetK) {
                newTransitions.add(currentProject.transitions[i])
            }
            newTransitions.add(Transition(TransitionType.NONE, 0L))
            for (i in targetK until currentProject.transitions.size) {
                newTransitions.add(currentProject.transitions[i])
            }

            // Adjust adjacent transitions if needed (duration <= min(d1, d2) / 2)
            clampAdjacentTransitionDurations(newClips, newTransitions, targetK - 1, targetK + 1)

            val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)

            // Push current state onto Undo stack BEFORE applying the split
            pushUndoState(projectSnapshot = currentProject)

            // Shift custom GL transition styles so junctions after targetK stay aligned with newTransitions
            val shiftedModes = shiftJunctionModesOnInsert(junctionTransitionModes, targetK)
            junctionTransitionModes.clear()
            junctionTransitionModes.putAll(shiftedModes)
            PreviewRenderer.syncJunctionTransitions(junctionTransitionModes)

            // Register natural duration and timeMaps for both halves
            val naturalDur = clipNaturalDurations[oldClip.id] ?: getMediaFileDurationUs(oldClip)
            clipNaturalDurations[oldClip.id] = naturalDur
            clipNaturalDurations[rightClipId] = naturalDur
            clipTimeMaps[oldClip.id] = leftMap
            clipTimeMaps[rightClipId] = rightMap

            // Inherit volume, transform, color adjust, and canvas background for both halves
            copyClipRenderAndAudioMetadata(fromClipId = oldClip.id, toClipId = rightClipId)

            // Update project and select the new Right Clip (targetK + 1)
            updateProject(newProject, recordUndo = false)
            _uiState.update {
                it.copy(
                    selectedClipIndex = targetK + 1,
                    selectedJunctionIndex = null
                )
            }

            val newSnapshot = getSnapshot()
            return SplitResult(newSnapshot, targetK + 1)
        } finally {
            setProcessingEdit(false)
        }
    }

    fun duplicateClip(clipIndex: Int): DuplicateResult? {
        val currentProject = _uiState.value.project
        if (clipIndex !in currentProject.clips.indices) return null
        val oldClip = currentProject.clips[clipIndex]

        val currentTotalUs = msToUs(currentProject.clips.sumOf { it.durationMs })
        val oldClipDurUs = msToUs(oldClip.durationMs)
        val oldMap = clipTimeMaps[oldClip.id] ?: TimeMap(0L, msToUs(oldClip.durationMs))

        val newClip: Clip
        val newMap: TimeMap

        if (currentTotalUs + oldClipDurUs > MAX_TIMELINE_DURATION_US) {
            val remainingUs = MAX_TIMELINE_DURATION_US - currentTotalUs
            if (remainingUs < OverlayItem.MIN_DURATION_US) {
                showToast("Maximum timeline duration is 15 minutes")
                return null
            }
            val clampedDurMs = usToMs(remainingUs)
            val newId = java.util.UUID.randomUUID().toString()
            newClip = oldClip.copy(id = newId, durationMs = clampedDurMs)
            val speedFactor = if (oldMap.speedDen > 0 && oldMap.speedNum > 0) {
                oldMap.speedNum.toDouble() / oldMap.speedDen.toDouble()
            } else 1.0
            val mediaDeltaUs = (remainingUs.toDouble() * speedFactor).toLong()
            newMap = TimeMap(oldMap.inUs, (oldMap.inUs + mediaDeltaUs).coerceAtMost(oldMap.outUs), oldMap.speedNum, oldMap.speedDen)
            showToast("Maximum timeline duration is 15 minutes")
        } else {
            val newId = java.util.UUID.randomUUID().toString()
            newClip = oldClip.copy(id = newId)
            newMap = oldMap
        }
        val newId = newClip.id

        val newClips = currentProject.clips.toMutableList()
        val insertIndex = clipIndex + 1
        newClips.add(insertIndex, newClip)

        // Transitions: insert NONE at junction clipIndex
        val newTransitions = ArrayList<Transition>(currentProject.transitions.size + 1)
        for (i in 0 until clipIndex) {
            newTransitions.add(currentProject.transitions[i])
        }
        newTransitions.add(Transition(TransitionType.NONE, 0L))
        for (i in clipIndex until currentProject.transitions.size) {
            newTransitions.add(currentProject.transitions[i])
        }

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)

        // Push current state onto Undo stack
        pushUndoState(projectSnapshot = currentProject)

        // Shift custom GL transition styles so junctions after clipIndex stay aligned with newTransitions
        val shiftedModes = shiftJunctionModesOnInsert(junctionTransitionModes, clipIndex)
        junctionTransitionModes.clear()
        junctionTransitionModes.putAll(shiftedModes)
        PreviewRenderer.syncJunctionTransitions(junctionTransitionModes)

        // Copy ALL per-clip metadata from oldClip.id to newId
        clipTimeMaps[newId] = newMap
        val natDur = clipNaturalDurations[oldClip.id] ?: getMediaFileDurationUs(oldClip)
        clipNaturalDurations[newId] = natDur
        copyClipRenderAndAudioMetadata(fromClipId = oldClip.id, toClipId = newId)

        updateProject(newProject, recordUndo = false)

        val newTimeline = ProgramTimeline(newProject)
        val newStartMs = newTimeline.startMs[insertIndex]

        _uiState.update {
            it.copy(
                selectedClipIndex = insertIndex,
                selectedJunctionIndex = null,
                currentMs = newStartMs
            )
        }

        val snapshot = getSnapshot()
        return DuplicateResult(snapshot, insertIndex, newStartMs)
    }

    fun replaceSelectedClip(clipIndex: Int, newImportedClip: Clip): TimelineSnapshot? {
        val currentProject = _uiState.value.project
        if (clipIndex !in currentProject.clips.indices) return null
        val oldClip = currentProject.clips[clipIndex]

        val newNaturalMs = newImportedClip.durationMs
        val targetDurMs: Long
        if (newNaturalMs >= oldClip.durationMs) {
            targetDurMs = oldClip.durationMs
        } else {
            targetDurMs = maxOf(200L, newNaturalMs)
        }
        val targetDurUs = msToUs(targetDurMs)
        val newMap = TimeMap(inUs = 0L, outUs = targetDurUs, speedNum = 100, speedDen = 100)

        val newClipId = newImportedClip.id
        val updatedClip = Clip(
            id = newClipId,
            path = newImportedClip.path,
            durationMs = targetDurMs,
            width = newImportedClip.width,
            height = newImportedClip.height,
            rotation = newImportedClip.rotation,
            frameRate = newImportedClip.frameRate
        )

        val newClips = currentProject.clips.toMutableList()
        newClips[clipIndex] = updatedClip

        val newTransitions = currentProject.transitions.toMutableList()
        if (newNaturalMs < oldClip.durationMs) {
            clampAdjacentTransitionDurations(newClips, newTransitions, clipIndex - 1, clipIndex)
        }

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)

        // Push current state onto Undo stack
        pushUndoState(projectSnapshot = currentProject)

        // Clean up oldClip.id mappings and register newClipId
        val fileMediaDurUs = getMediaFileDurationUs(newImportedClip)
        val trueSourceDurationUs = maxOf(msToUs(newNaturalMs), fileMediaDurUs)

        clipNaturalDurations.remove(oldClip.id)
        clipNaturalDurations[newClipId] = trueSourceDurationUs
        if (oldClip.path != newImportedClip.path) {
            pathNaturalDurationsCache.remove(oldClip.path)
        }
        pathNaturalDurationsCache[newImportedClip.path] = trueSourceDurationUs

        clipTimeMaps.remove(oldClip.id)
        clipTimeMaps[newClipId] = newMap

        val origVol = clipVolumes.remove(oldClip.id) ?: 1.0f
        clipVolumes[newClipId] = origVol
        savedPreMuteVolumes.remove(oldClip.id)

        clipTransforms.remove(oldClip.id)
        clipTransforms[newClipId] = ClipTransform.DEFAULT
        clipColorAdjusts.remove(oldClip.id)
        clipColorAdjusts[newClipId] = ClipColorAdjust.DEFAULT
        clipBackgrounds.remove(oldClip.id)
        clipBackgrounds[newClipId] = ClipBackground.DEFAULT

        RenderStateStore.removeClipMetadata(oldClip.id)
        RenderStateStore.setClipTransform(newClipId, ClipTransform.DEFAULT)
        RenderStateStore.setClipColorAdjust(newClipId, ClipColorAdjust.DEFAULT)
        RenderStateStore.setClipBackground(newClipId, ClipBackground.DEFAULT)
        RenderStateStore.setClipVolume(newClipId, origVol)

        updateProject(newProject, recordUndo = false)

        _uiState.update {
            it.copy(
                selectedClipIndex = clipIndex,
                selectedJunctionIndex = null
            )
        }

        return getSnapshot()
    }

    fun replaceSelectedClipUri(clipIndex: Int, uri: android.net.Uri, onReplaced: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importProgressText = "Replacing clip...") }
            val imported = clipImporter.importUris(
                uris = listOf(uri),
                currentTotalMs = 0L,
                onProgress = { _, _ -> },
                onError = { message -> showToast(message) }
            )
            if (imported.isNotEmpty()) {
                replaceSelectedClip(clipIndex, imported[0])
                onReplaced()
            }
            _uiState.update { it.copy(isImporting = false, importProgressText = "") }
        }
    }

    fun freezeSelectedClip(playheadUs: Long): FreezeResult? {
        val currentProject = _uiState.value.project
        val selectedK = _uiState.value.selectedClipIndex ?: return null
        if (selectedK !in currentProject.clips.indices) return null

        val tl = ProgramTimeline(currentProject)
        val startUs = msToUs(tl.startMs[selectedK])
        val endUs = msToUs(tl.endMs[selectedK])
        val oldClip = currentProject.clips[selectedK]
        val oldMap = clipTimeMaps[oldClip.id] ?: TimeMap(0L, msToUs(oldClip.durationMs))

        val currentTotalUs = msToUs(currentProject.clips.sumOf { it.durationMs })
        val proposedFreezeDurUs = 3_000_000L
        val freezeDurationMs: Long

        if (currentTotalUs + proposedFreezeDurUs > MAX_TIMELINE_DURATION_US) {
            val remainingUs = MAX_TIMELINE_DURATION_US - currentTotalUs
            if (remainingUs < OverlayItem.MIN_DURATION_US) {
                showToast("Maximum timeline duration is 15 minutes")
                return null
            }
            freezeDurationMs = usToMs(remainingUs)
            showToast("Maximum timeline duration is 15 minutes")
        } else {
            freezeDurationMs = 3000L
        }

        val freezeClipId = java.util.UUID.randomUUID().toString()

        val newClips = currentProject.clips.toMutableList()
        val newTransitions: ArrayList<Transition>
        val freezeIndex: Int
        val freezeSourceUs: Long

        val preFreezeTimeMaps = HashMap(clipTimeMaps)
        var rightClipId: String? = null
        var rightMap: TimeMap? = null

        when {
            // Case B: Near start (< startUs + 200_000L) -> insert before clip k
            playheadUs < startUs + 200_000L -> {
                freezeSourceUs = oldMap.inUs
                freezeIndex = selectedK
                val freezeClip = oldClip.copy(id = freezeClipId, durationMs = freezeDurationMs)
                newClips.add(freezeIndex, freezeClip)

                newTransitions = ArrayList(currentProject.transitions.size + 1)
                for (i in 0 until selectedK) {
                    newTransitions.add(currentProject.transitions[i])
                }
                newTransitions.add(Transition(TransitionType.NONE, 0L))
                for (i in selectedK until currentProject.transitions.size) {
                    newTransitions.add(currentProject.transitions[i])
                }
            }
            // Case C: Near end (> endUs - 200_000L) -> insert after clip k
            playheadUs > endUs - 200_000L -> {
                freezeSourceUs = (oldMap.outUs - 33_000L).coerceAtLeast(oldMap.inUs)
                freezeIndex = selectedK + 1
                val freezeClip = oldClip.copy(id = freezeClipId, durationMs = freezeDurationMs)
                newClips.add(freezeIndex, freezeClip)

                newTransitions = ArrayList(currentProject.transitions.size + 1)
                for (i in 0 until selectedK) {
                    newTransitions.add(currentProject.transitions[i])
                }
                newTransitions.add(Transition(TransitionType.NONE, 0L))
                for (i in selectedK until currentProject.transitions.size) {
                    newTransitions.add(currentProject.transitions[i])
                }
            }
            // Case A: Strictly inside clip k (>= 200_000L on left and right) -> split and insert in between
            else -> {
                val localCutUs = playheadUs - startUs
                val leftDurMs = usToMs(localCutUs)
                val alignedLocalCutUs = msToUs(leftDurMs)
                val splitSourceUs = msToUs(usToMs(oldMap.sourceUs(alignedLocalCutUs)))
                val rightDurMs = oldClip.durationMs - leftDurMs

                freezeSourceUs = splitSourceUs
                freezeIndex = selectedK + 1

                val leftClip = oldClip.copy(durationMs = leftDurMs)
                val freezeClip = oldClip.copy(id = freezeClipId, durationMs = freezeDurationMs)
                val rClipId = java.util.UUID.randomUUID().toString()
                rightClipId = rClipId
                val rightClip = oldClip.copy(id = rClipId, durationMs = rightDurMs)

                newClips[selectedK] = leftClip
                newClips.add(freezeIndex, freezeClip)
                newClips.add(freezeIndex + 1, rightClip)

                clipTimeMaps[oldClip.id] = TimeMap(oldMap.inUs, splitSourceUs, oldMap.speedNum, oldMap.speedDen)
                rightMap = TimeMap(splitSourceUs, oldMap.outUs, oldMap.speedNum, oldMap.speedDen)

                newTransitions = ArrayList(currentProject.transitions.size + 2)
                for (i in 0 until selectedK) {
                    newTransitions.add(currentProject.transitions[i])
                }
                newTransitions.add(Transition(TransitionType.NONE, 0L))
                newTransitions.add(Transition(TransitionType.NONE, 0L))
                for (i in selectedK until currentProject.transitions.size) {
                    newTransitions.add(currentProject.transitions[i])
                }
            }
        }

        // Clamp adjacent transitions if needed (duration <= min(d1, d2) / 2)
        clampAdjacentTransitionDurations(newClips, newTransitions, selectedK - 1, freezeIndex + 1)

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)

        // Push pre-freeze state onto Undo stack (with preFreezeTimeMaps before Case A mutation)
        pushUndoState(projectSnapshot = currentProject, timeMapsSnapshot = preFreezeTimeMaps)

        // Set up freeze clip metadata (silent still frame inheriting visual style)
        clipTimeMaps[freezeClipId] = TimeMap(
            inUs = freezeSourceUs,
            outUs = freezeSourceUs,
            speedNum = 0,
            speedDen = 100
        )
        val origNaturalDur = clipNaturalDurations[oldClip.id] ?: getMediaFileDurationUs(oldClip)
        clipNaturalDurations[freezeClipId] = origNaturalDur
        copyClipRenderAndAudioMetadata(fromClipId = oldClip.id, toClipId = freezeClipId, overrideVolume = 0f)

        // If Case A, also register rightClip metadata
        if (rightClipId != null && rightMap != null) {
            clipTimeMaps[rightClipId] = rightMap
            clipNaturalDurations[rightClipId] = origNaturalDur
            copyClipRenderAndAudioMetadata(fromClipId = oldClip.id, toClipId = rightClipId)
        }

        updateProject(newProject, recordUndo = false)

        val newTimeline = ProgramTimeline(newProject)
        val freezeStartMs = newTimeline.startMs[freezeIndex]

        _uiState.update {
            it.copy(
                selectedClipIndex = freezeIndex,
                selectedJunctionIndex = null,
                currentMs = freezeStartMs
            )
        }

        val snapshot = getSnapshot()
        return FreezeResult(snapshot, freezeIndex, freezeStartMs)
    }

    init {
        viewModelScope.launch {
            _uiState.collect { state ->
                loadingManager.setLoading(
                    LoadingSources.IMPORTING,
                    state.isImporting,
                    state.importProgressText.ifBlank { "Importing media..." }
                )
            }
        }
        OverlayTextureCache.defaultProxyStore = proxyStore
        OverlayTextureCache.defaultMaxByteBudget = qualityBudget.overlayTexBudgetMb * 1024L * 1024L
        OverlayTextureCache.defaultPipUpdateFps = qualityBudget.pipUpdateFps
        ThumbnailService.defaultMaxMemEntries = qualityBudget.thumbnailMemEntries

        // Auto-tune default export settings for low-end devices while allowing manual override
        val initRes = qualityBudget.recommendedExportResolution
        val initFps = qualityBudget.recommendedExportFps
        _uiState.update {
            it.copy(
                exportResolution = initRes,
                exportFps = initFps,
                exportBitrateBps = computeTargetExportBitrateBps(initRes, initFps, it.exportBitratePreset)
            )
        }

        thermalMonitor.start()
        perfExecutor.scheduleWithFixedDelay({
            try {
                val nowMs = SystemClock.uptimeMillis()
                val currentLate = PreviewRenderer.activeLateFrames()
                val currentRendered = PreviewRenderer.activeRenderedFrames()
                val dLate = (currentLate - lastLateFrames).coerceAtLeast(0L)
                val dRendered = (currentRendered - lastRenderedFrames).coerceAtLeast(0L)
                lastLateFrames = currentLate
                lastRenderedFrames = currentRendered

                val latePct = if (dRendered > 0L) (dLate.toFloat() / dRendered.toFloat()) * 100f else 0f
                val level = qualityGovernor.update(
                    nowMs = nowMs,
                    moderate = thermalMonitor.isModerate,
                    severe = thermalMonitor.isSevere,
                    headroom = thermalMonitor.headroom,
                    lateFramePct = latePct,
                    memLow = isMemoryPressureActive.get()
                )

                val targetFps = when (level) {
                    Level.FULL -> qualityBudget.pipUpdateFps
                    Level.REDUCED -> 15
                    Level.MINIMAL -> 10
                }
                OverlayTextureCache.defaultPipUpdateFps = targetFps

                if (level != Level.FULL) {
                    OverlayTextureCache.pruneDefaultCache()
                    val thumbCap = if (level == Level.MINIMAL) 32 else (qualityBudget.thumbnailMemEntries / 2).coerceAtLeast(48)
                    ThumbnailService.trimActiveMemory(thumbCap)
                }
            } catch (_: Throwable) {}
        }, 2, 3, TimeUnit.SECONDS)

        loadInitialProject()
        viewModelScope.launch {
            ExportCoordinator.state.collect { state ->
                when (state) {
                    is ExportState.Exporting -> {
                        _uiState.update {
                            it.copy(
                                exportState = state,
                                exportFallbackNotice = ExportCoordinator.fallbackNotice,
                                currentScreen = if (it.currentScreen == AppScreen.HOME) it.currentScreen else AppScreen.EXPORT
                            )
                        }
                    }
                    is ExportState.Done -> {
                        _uiState.update {
                            it.copy(
                                exportState = ExportState.Done,
                                exportedUri = ExportCoordinator.exportedUri,
                                exportFallbackNotice = ExportCoordinator.fallbackNotice
                            )
                        }
                    }
                    is ExportState.Failed -> {
                        _uiState.update {
                            it.copy(
                                exportState = ExportState.Failed,
                                exportErrorMessage = ExportCoordinator.errorMessage ?: "Export failed. Please try again."
                            )
                        }
                    }
                    is ExportState.Ready -> {
                        // Idle state
                    }
                }
            }
        }
    }

    fun initPreviewEngine(engine: PreviewEngine) {
        previewEngine = engine
        engine.timeline = timeline
    }

    fun buildFullProjectDraft(): FullProjectDraft {
        val currProject = _uiState.value.project
        val timeMapsDraft = clipTimeMaps.mapValues { it.value.toDraft() }
        val transformsDraft = clipTransforms.mapValues { it.value.toDraft() }
        val colorAdjustsDraft = clipColorAdjusts.mapValues { it.value.toDraft() }
        val backgroundsDraft = clipBackgrounds.mapValues { it.value.toDraft() }
        val filterStatesDraft = currProject.clips.associate { it.id to it.renderParams.filter.toDraft() }
        val junctionsDraft = mutableMapOf<Int, JunctionTransitionDraft>()
        val maxJunctions = maxOf(0, currProject.clips.size - 1)
        for (i in 0 until maxJunctions) {
            val mode = getJunctionTransitionMode(i)
            val dur = currProject.transitions.getOrNull(i)?.durationMs ?: 0L
            junctionsDraft[i] = JunctionTransitionDraft(styleMode = mode, durationMs = dur)
        }
        val layersDraft = _layerItems.map { it.toDraft() }

        val state = _uiState.value
        return FullProjectDraft(
            version = 2,
            draftId = state.activeDraftId ?: "",
            title = state.activeProjectTitle,
            updatedAtMs = System.currentTimeMillis(),
            project = currProject,
            aspectRatioModeName = state.aspectRatioMode.name,
            isMainTrackMuted = state.isMainTrackMuted,
            coverTimeUs = state.coverTimeUs,
            customCoverPath = state.customCoverPath,
            canvasBgMode = state.canvasBgMode,
            canvasBlurLevel = state.canvasBlurLevel,
            canvasColorArgb = state.canvasColorArgb,
            canvasGradientId = state.canvasGradientId,
            clipTimeMaps = timeMapsDraft,
            clipVolumes = HashMap(clipVolumes),
            savedPreMuteVolumes = HashMap(savedPreMuteVolumes),
            clipTransforms = transformsDraft,
            clipColorAdjusts = colorAdjustsDraft,
            clipBackgrounds = backgroundsDraft,
            clipFilterStates = filterStatesDraft,
            junctionTransitions = junctionsDraft,
            layerItems = layersDraft
        )
    }

    fun scheduleAutoSave() {
        if (isNewerProjectBlocked) return
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(500L)
            persistActiveDraftNow()
        }
    }

    private suspend fun persistActiveDraftNow() {
        if (isNewerProjectBlocked) return
        val draft = buildFullProjectDraft()
        if (draft.project.clips.isEmpty() && draft.draftId.isBlank()) return
        val savedSummary = projectStore.saveDraft(draft)
        val refreshedList = projectStore.listDrafts()
        _uiState.update {
            it.copy(
                activeDraftId = savedSummary.id,
                activeProjectTitle = savedSummary.title,
                savedDrafts = refreshedList
            )
        }
    }

    private fun copyClipRenderAndAudioMetadata(
        fromClipId: String,
        toClipId: String,
        overrideVolume: Float? = null
    ) {
        val origVol = clipVolumes[fromClipId] ?: 1.0f
        val targetVol = overrideVolume ?: origVol
        clipVolumes[fromClipId] = origVol
        clipVolumes[toClipId] = targetVol
        if (overrideVolume == null) {
            savedPreMuteVolumes[fromClipId]?.let { savedPreMuteVolumes[toClipId] = it }
        }

        val origTransform = clipTransforms[fromClipId] ?: ClipTransform.DEFAULT
        clipTransforms[fromClipId] = origTransform
        clipTransforms[toClipId] = origTransform

        val origAdjust = clipColorAdjusts[fromClipId] ?: ClipColorAdjust.DEFAULT
        clipColorAdjusts[fromClipId] = origAdjust
        clipColorAdjusts[toClipId] = origAdjust

        val origBg = clipBackgrounds[fromClipId] ?: ClipBackground.DEFAULT
        clipBackgrounds[fromClipId] = origBg
        clipBackgrounds[toClipId] = origBg

        RenderStateStore.setClipVolume(fromClipId, origVol)
        RenderStateStore.setClipTransform(fromClipId, origTransform)
        RenderStateStore.setClipColorAdjust(fromClipId, origAdjust)
        RenderStateStore.setClipBackground(fromClipId, origBg)
        RenderStateStore.copyClipMetadata(fromClipId, toClipId, overrideVolume)
    }

    private fun syncAllRenderAndAudioState(
        project: Project,
        aspectRatioMode: AspectRatioMode,
        mainTrackMuted: Boolean
    ) {
        val items = _layerItems.toList()
        val plan = CompiledLayerPlan.compile(items)
        RenderStateStore.syncAll(
            transforms = clipTransforms,
            colorAdjusts = clipColorAdjusts,
            backgrounds = clipBackgrounds,
            junctionTransitions = junctionTransitionModes,
            volumes = clipVolumes,
            mainTrackMuted = mainTrackMuted,
            layerPlan = plan,
            aspectRatioMode = aspectRatioMode,
            firstClip = project.clips.firstOrNull()
        )
        val audioOverlays = items.filter { it.type == LayerType.AUDIO || (it.type == LayerType.VIDEO && it.volume > 0.001f) }
        ClipAudioContributor.syncAudioOverlays(audioOverlays)
        for (item in audioOverlays) {
            if (item.mediaPath.isNotEmpty()) {
                ClipAudioContributor.prewarmAudioPath(item.mediaPath)
            }
        }
    }

    private fun applyLoadedDraft(
        draft: FullProjectDraft,
        summaries: List<ProjectDraftSummary>,
        navigateToEditor: Boolean = false
    ) {
        val rawLoaded = draft.project
        // Backward-compat: migrate legacy draft.clipFilterStates into clip.renderParams.filter if needed
        val loaded = if (draft.clipFilterStates.isNotEmpty()) {
            val updatedClips = rawLoaded.clips.map { clip ->
                if (clip.renderParams.filter == com.flowcut.app.core.model.ClipFilterParams.DEFAULT) {
                    val legacyFilter = draft.clipFilterStates[clip.id]?.toDomain()
                    if (legacyFilter != null) {
                        clip.copy(renderParams = clip.renderParams.copy(filter = legacyFilter))
                    } else clip
                } else clip
            }
            rawLoaded.copy(clips = updatedClips)
        } else rawLoaded

        undoHistory.clear()
        clipNaturalDurations.clear()
        for (clip in loaded.clips) {
            if (!clipNaturalDurations.containsKey(clip.id)) {
                clipNaturalDurations[clip.id] = getMediaFileDurationUs(clip)
            }
        }

        // Restore TimeMaps
        clipTimeMaps.clear()
        for ((clipId, tmDraft) in draft.clipTimeMaps) {
            clipTimeMaps[clipId] = tmDraft.toDomain()
        }

        // Restore Volumes & Mute State
        clipVolumes.clear()
        clipVolumes.putAll(draft.clipVolumes)
        savedPreMuteVolumes.clear()
        savedPreMuteVolumes.putAll(draft.savedPreMuteVolumes)

        // Restore Transforms, Color Adjusts, Backgrounds
        clipTransforms.clear()
        clipTransforms.putAll(draft.clipTransforms.mapValues { it.value.toDomain() })

        clipColorAdjusts.clear()
        clipColorAdjusts.putAll(draft.clipColorAdjusts.mapValues { it.value.toDomain() })

        clipBackgrounds.clear()
        clipBackgrounds.putAll(draft.clipBackgrounds.mapValues { it.value.toDomain() })

        // Restore Junction Transitions
        junctionTransitionModes.clear()
        for ((idx, transDraft) in draft.junctionTransitions) {
            if (transDraft.styleMode > 0) {
                junctionTransitionModes[idx] = transDraft.styleMode
            }
        }

        // Restore Aspect Ratio
        val restoredAspectMode = AspectRatioMode.entries.firstOrNull { it.name == draft.aspectRatioModeName }
            ?: AspectRatioMode.RATIO_9_16
        preSheetAspectRatioMode = restoredAspectMode
        isAspectRatioUserLocked = loaded.clips.isNotEmpty()

        // Restore Overlays
        _layerItems.clear()
        for (itemDraft in draft.layerItems) {
            _layerItems.add(itemDraft.toDomain())
        }
        syncAllRenderAndAudioState(
            project = loaded,
            aspectRatioMode = restoredAspectMode,
            mainTrackMuted = draft.isMainTrackMuted
        )
        warmProxiesForVideoLayers()

        val resolvedDraftId = draft.draftId.takeIf { it.isNotBlank() }
        val resolvedTitle = draft.title.trim().ifEmpty {
            summaries.firstOrNull { it.id == resolvedDraftId }?.title ?: "Project 1"
        }

        _uiState.update {
            it.copy(
                currentScreen = if (navigateToEditor) AppScreen.EDITOR else it.currentScreen,
                activeDraftId = resolvedDraftId,
                activeProjectTitle = resolvedTitle,
                savedDrafts = summaries,
                project = loaded,
                currentMs = 0L,
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                selectedOverlayId = null,
                focusMode = TimelineFocusState.S0_NONE,
                canUndo = undoHistory.canUndo,
                canRedo = undoHistory.canRedo,
                isMainTrackMuted = draft.isMainTrackMuted,
                coverTimeUs = draft.coverTimeUs,
                customCoverPath = draft.customCoverPath,
                aspectRatioMode = restoredAspectMode,
                canvasBgMode = draft.canvasBgMode,
                canvasBlurLevel = draft.canvasBlurLevel,
                canvasColorArgb = draft.canvasColorArgb,
                canvasGradientId = draft.canvasGradientId
            )
        }
        previewEngine?.let { engine ->
            engine.timeline = ProgramTimeline(loaded)
            engine.seekTo(0L)
        }
    }

    private fun clearInMemoryProjectState() {
        undoHistory.clear()
        clipTimeMaps.clear()
        clipNaturalDurations.clear()
        clipVolumes.clear()
        savedPreMuteVolumes.clear()
        clipTransforms.clear()
        clipColorAdjusts.clear()
        clipBackgrounds.clear()
        _layerItems.clear()
        preSheetAspectRatioMode = AspectRatioMode.RATIO_9_16
        isAspectRatioUserLocked = false
        junctionTransitionModes.clear()
        preSheetTransitions = null
        preSheetJunctionModes = null
        RenderStateStore.resetAll()
        syncLayerPlan()
    }

    private fun loadInitialProject() {
        viewModelScope.launch {
            try {
                val summaries = projectStore.listDrafts()
                val draft = projectStore.loadFull()
                projectStore.cleanupOrphanClipsAcrossAllDrafts(draft.project)
                applyLoadedDraft(draft = draft, summaries = summaries, navigateToEditor = false)
            } catch (e: NewerProjectException) {
                isNewerProjectBlocked = true
                showToast("Project was created with a newer version of FlowCut")
            }
        }
    }

    private fun updateProject(newProject: Project, recordUndo: Boolean = true) {
        if (recordUndo) {
            pushUndoState(projectSnapshot = _uiState.value.project)
        }
        val newTimeline = ProgramTimeline(newProject)
        val clampedMs = _uiState.value.currentMs.coerceIn(0L, maxOf(0L, newTimeline.total))

        _uiState.update {
            it.copy(
                project = newProject,
                currentMs = clampedMs,
                canUndo = undoHistory.canUndo,
                canRedo = undoHistory.canRedo
            )
        }

        previewEngine?.let { engine ->
            engine.timeline = newTimeline
            engine.seekTo(clampedMs)
        }

        // Debounced 500ms auto-save
        scheduleAutoSave()
    }

    fun onPlayheadUpdate(newMs: Long) {
        _uiState.update { it.copy(currentMs = newMs) }
    }

    fun onPlaybackStateChange(isPlaying: Boolean) {
        _uiState.update { it.copy(isPlaying = isPlaying) }
    }

    fun playPause() {
        val engine = previewEngine
        if (engine != null) {
            if (engine.isPlaying) {
                engine.pause()
            } else {
                engine.play()
            }
        } else {
            _uiState.update { it.copy(isPlaying = !it.isPlaying) }
        }
    }

    fun pause() {
        val engine = previewEngine
        if (engine != null) {
            if (engine.isPlaying) {
                engine.pause()
            }
        } else {
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    private var preciseSeekJob: Job? = null

    fun scrubTo(ms: Long) {
        val engine = previewEngine
        if (engine != null) {
            if (engine.isPlaying) {
                engine.pause()
            }
            _uiState.update { it.copy(currentMs = ms) }
            engine.seekTo(ms, fastScrub = true)

            // Debounce precise seek by 120ms (Section 19.8)
            preciseSeekJob?.cancel()
            preciseSeekJob = viewModelScope.launch {
                delay(120L)
                engine.seekTo(ms, fastScrub = false)
            }
        } else {
            _uiState.update { it.copy(currentMs = ms, isPlaying = false) }
        }
    }

    fun seekTo(ms: Long) {
        val engine = previewEngine
        if (engine != null) {
            if (engine.isPlaying) {
                engine.pause()
            }
            engine.seekTo(ms)
            _uiState.update { it.copy(currentMs = ms) }
        } else {
            _uiState.update { it.copy(currentMs = ms, isPlaying = false) }
        }
    }

    fun selectClip(index: Int) {
        _uiState.update {
            it.copy(
                selectedClipIndex = index,
                selectedJunctionIndex = null,
                selectedOverlayId = null,
                focusMode = TimelineFocusState.S0_NONE
            )
        }
    }

    fun deselectAll() {
        _uiState.update {
            it.copy(
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                selectedOverlayId = null,
                focusMode = TimelineFocusState.S0_NONE
            )
        }
    }

    fun deleteClip(index: Int) {
        val currentProject = _uiState.value.project
        if (index !in currentProject.clips.indices) return
        // Capture undo snapshot BEFORE deleting per-clip metadata so Undo restores all settings
        pushUndoState(projectSnapshot = currentProject)
        val deletedId = currentProject.clips[index].id
        clipTimeMaps.remove(deletedId)
        clipNaturalDurations.remove(deletedId)
        clipVolumes.remove(deletedId)
        savedPreMuteVolumes.remove(deletedId)
        clipTransforms.remove(deletedId)
        clipColorAdjusts.remove(deletedId)
        clipBackgrounds.remove(deletedId)
        RenderStateStore.removeClipMetadata(deletedId)
        val newProject = ProgramTimeline.deleteClip(currentProject, index)
        val shiftedModes = shiftJunctionModesOnDeleteClip(junctionTransitionModes, index, currentProject.clips.size)
        junctionTransitionModes.clear()
        junctionTransitionModes.putAll(shiftedModes)
        PreviewRenderer.syncJunctionTransitions(junctionTransitionModes)
        val isAllMuted = newProject.clips.isNotEmpty() && newProject.clips.all { (clipVolumes[it.id] ?: 1.0f) <= 0.0001f }
        RenderStateStore.isMainTrackMuted = isAllMuted
        updateProject(newProject, recordUndo = false)
        _uiState.update {
            it.copy(
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                selectedOverlayId = null,
                focusMode = TimelineFocusState.S0_NONE,
                isMainTrackMuted = isAllMuted
            )
        }
    }

    fun navigateTo(screen: AppScreen) {
        if (screen == AppScreen.HOME && _uiState.value.currentScreen == AppScreen.EDITOR) {
            autoSaveJob?.cancel()
            viewModelScope.launch {
                persistActiveDraftNow()
            }
        }
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun openDraft(draftId: String) {
        if (isNewerProjectBlocked) {
            showToast("Project was created with a newer version of FlowCut")
            return
        }
        val state = _uiState.value
        if (state.activeDraftId == draftId && state.project.clips.isNotEmpty()) {
            openExistingProject()
            return
        }
        viewModelScope.launch {
            try {
                // Persist current active draft before switching
                if (state.project.clips.isNotEmpty() && state.activeDraftId != null && state.activeDraftId != draftId) {
                    persistActiveDraftNow()
                }
                val loaded = projectStore.loadDraft(draftId)
                val summaries = projectStore.listDrafts()
                if (loaded != null) {
                    applyLoadedDraft(draft = loaded, summaries = summaries, navigateToEditor = true)
                } else {
                    _uiState.update { it.copy(savedDrafts = summaries) }
                    showToast("Draft could not be opened")
                }
            } catch (e: NewerProjectException) {
                isNewerProjectBlocked = true
                showToast("Project was created with a newer version of FlowCut")
            }
        }
    }

    fun renameDraft(draftId: String, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val updated = projectStore.renameDraft(draftId, clean)
            _uiState.update { state ->
                state.copy(
                    savedDrafts = updated,
                    activeProjectTitle = if (state.activeDraftId == draftId) clean else state.activeProjectTitle
                )
            }
        }
    }

    fun renameActiveProject(newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return
        val activeId = _uiState.value.activeDraftId
        if (activeId != null) {
            renameDraft(activeId, clean)
        } else {
            _uiState.update { it.copy(activeProjectTitle = clean) }
            scheduleAutoSave()
        }
    }

    fun duplicateDraft(draftId: String) {
        viewModelScope.launch {
            val res = projectStore.duplicateDraft(draftId)
            if (res != null) {
                _uiState.update { it.copy(savedDrafts = res.second) }
                showToast("Draft duplicated")
            }
        }
    }

    fun deleteDraft(draftId: String) {
        viewModelScope.launch {
            val wasActive = _uiState.value.activeDraftId == draftId
            val remaining = projectStore.deleteDraft(draftId)
            if (wasActive) {
                val nextId = remaining.firstOrNull()?.id
                val nextDraft = if (nextId != null) projectStore.loadDraft(nextId) else null
                if (nextDraft != null) {
                    applyLoadedDraft(draft = nextDraft, summaries = remaining, navigateToEditor = false)
                } else {
                    clearInMemoryProjectState()
                    _uiState.update {
                        it.copy(
                            activeDraftId = null,
                            activeProjectTitle = DraftLibraryUtils.generateNextDefaultTitle(emptyList()),
                            savedDrafts = remaining,
                            project = Project(),
                            currentMs = 0L,
                            selectedClipIndex = null,
                            selectedJunctionIndex = null,
                            selectedOverlayId = null,
                            focusMode = TimelineFocusState.S0_NONE,
                            canUndo = false,
                            canRedo = false,
                            coverTimeUs = 0L,
                            customCoverPath = null
                        )
                    }
                }
            } else {
                _uiState.update { it.copy(savedDrafts = remaining) }
            }
            showToast("Draft deleted")
        }
    }

    fun openExistingProject() {
        if (isNewerProjectBlocked) {
            showToast("Project was created with a newer version of FlowCut")
            return
        }
        val state = _uiState.value
        if (state.project.clips.isNotEmpty()) {
            isAspectRatioUserLocked = true
        }
        syncAllRenderAndAudioState(
            project = state.project,
            aspectRatioMode = state.aspectRatioMode,
            mainTrackMuted = state.isMainTrackMuted
        )
        warmProxiesForVideoLayers()
        _uiState.update { it.copy(currentScreen = AppScreen.EDITOR) }
        previewEngine?.seekTo(state.currentMs)
    }

    private fun warmProxiesForVideoLayers() {
        if (!qualityBudget.autoProxy && qualityGovernor.currentLevel == Level.FULL) return
        for (item in _layerItems) {
            if (item.type == LayerType.VIDEO && item.mediaPath.isNotEmpty()) {
                if (proxyStore.getReadyProxyPath(item.mediaPath) == null) {
                    pipProxyGenerator.getOrGenerateProxy(item.mediaPath).invokeOnCompletion {
                        previewEngine?.requestRender()
                    }
                }
            }
        }
    }

    fun resetProject(onComplete: () -> Unit = {}) {
        isNewerProjectBlocked = false
        viewModelScope.launch {
            projectStore.clearAll()
            proxyStore.clearAll()
            pipProxyGenerator.cancelAll()
            undoHistory.clear()
            clipTimeMaps.clear()
            clipNaturalDurations.clear()
            clipVolumes.clear()
            savedPreMuteVolumes.clear()
            clipTransforms.clear()
            clipColorAdjusts.clear()
            clipBackgrounds.clear()
            _layerItems.clear()
            preSheetAspectRatioMode = AspectRatioMode.RATIO_9_16
            isAspectRatioUserLocked = false
            junctionTransitionModes.clear()
            preSheetTransitions = null
            preSheetJunctionModes = null
            RenderStateStore.resetAll()
            syncLayerPlan()
            _uiState.update {
                it.copy(
                    project = Project(),
                    currentMs = 0L,
                    selectedClipIndex = null,
                    selectedJunctionIndex = null,
                    selectedOverlayId = null,
                    isMainTrackMuted = false,
                    coverTimeUs = 0L,
                    customCoverPath = null,
                    aspectRatioMode = AspectRatioMode.RATIO_9_16,
                    canvasBgMode = 0,
                    canvasBlurLevel = 0.5f,
                    canvasColorArgb = 0xFF000000.toInt(),
                    canvasGradientId = -1,
                    canUndo = false,
                    canRedo = false
                )
            }
            previewEngine?.let { engine ->
                engine.timeline = ProgramTimeline(Project())
                engine.seekTo(0L)
            }
            onComplete()
        }
    }

    fun createNewProject(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isNewerProjectBlocked = false
        val urisToProcess = uris.take(MAX_CLIPS)

        viewModelScope.launch {
            // Preserve any existing active project as a saved draft before creating a new one
            if (_uiState.value.project.clips.isNotEmpty()) {
                autoSaveJob?.cancel()
                persistActiveDraftNow()
            }

            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressText = "Importing 1/${urisToProcess.size}..."
                )
            }

            val imported = clipImporter.importUris(
                uris = urisToProcess,
                currentTotalMs = 0L,
                onProgress = { cur, tot ->
                    _uiState.update { it.copy(importProgressText = "Importing $cur/$tot...") }
                },
                onError = { message ->
                    showToast(message)
                }
            )

            if (imported.isNotEmpty()) {
                clearInMemoryProjectState()
                val clampedClips = mutableListOf<Clip>()
                var accumulatedUs = 0L
                var hitCap = false

                for (c in imported) {
                    val clipDurUs = msToUs(c.durationMs)
                    if (accumulatedUs + clipDurUs <= MAX_TIMELINE_DURATION_US) {
                        clampedClips.add(c)
                        clipNaturalDurations[c.id] = clipDurUs
                        accumulatedUs += clipDurUs
                    } else {
                        val remainingUs = MAX_TIMELINE_DURATION_US - accumulatedUs
                        if (remainingUs >= OverlayItem.MIN_DURATION_US) {
                            val clampedDurMs = usToMs(remainingUs)
                            val clampedClip = c.copy(durationMs = clampedDurMs)
                            clampedClips.add(clampedClip)
                            clipNaturalDurations[c.id] = clipDurUs
                            clipTimeMaps[c.id] = TimeMap(0L, remainingUs)
                            accumulatedUs = MAX_TIMELINE_DURATION_US
                        }
                        hitCap = true
                        break
                    }
                }

                if (hitCap) {
                    showToast("Maximum timeline duration is 15 minutes")
                }

                if (clampedClips.isNotEmpty()) {
                    val existingSummaries = projectStore.listDrafts()
                    val newDraftId = UUID.randomUUID().toString()
                    val newTitle = DraftLibraryUtils.generateNextDefaultTitle(existingSummaries.map { it.title })
                    val firstClip = clampedClips.first()
                    val autoMode = findMatchingAspectRatioMode(firstClip.displayWidth, firstClip.displayHeight)
                    val newProject = ProgramTimeline.addClips(Project(), clampedClips)

                    _uiState.update {
                        it.copy(
                            activeDraftId = newDraftId,
                            activeProjectTitle = newTitle,
                            currentScreen = AppScreen.EDITOR,
                            currentMs = 0L,
                            selectedClipIndex = null,
                            selectedJunctionIndex = null,
                            selectedOverlayId = null,
                            focusMode = TimelineFocusState.S0_NONE,
                            isMainTrackMuted = false,
                            coverTimeUs = 0L,
                            customCoverPath = null,
                            aspectRatioMode = autoMode,
                            canvasBgMode = 0,
                            canvasBlurLevel = 0.5f,
                            canvasColorArgb = 0xFF000000.toInt(),
                            canvasGradientId = -1
                        )
                    }
                    preSheetAspectRatioMode = autoMode
                    val effective = resolveEffectiveAspect(autoMode)
                    PreviewRenderer.setAspectRatioMode(autoMode, firstClip)
                    PreviewRenderer.setCanvasAspectRatio(effective)
                    updateProject(newProject, recordUndo = false)
                    persistActiveDraftNow()
                    previewEngine?.seekTo(0L)
                    previewEngine?.requestRender()
                }
            }

            _uiState.update {
                it.copy(isImporting = false, importProgressText = "")
            }
        }
    }

    fun importClips(uris: List<Uri>) {
        val currentClips = _uiState.value.project.clips
        val wasEmpty = currentClips.isEmpty()
        val currentClipsCount = currentClips.size
        if (currentClipsCount >= MAX_CLIPS) {
            showToast("Maximum clip limit reached ($MAX_CLIPS clips)")
            return
        }

        val currentTotalUs = msToUs(currentClips.sumOf { it.durationMs })
        if (currentTotalUs >= MAX_TIMELINE_DURATION_US) {
            showToast("Maximum timeline duration is 15 minutes")
            return
        }

        val availableSlots = MAX_CLIPS - currentClipsCount
        val urisToProcess = uris.take(availableSlots)

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressText = "Importing 1/${urisToProcess.size}..."
                )
            }

            val imported = clipImporter.importUris(
                uris = urisToProcess,
                currentTotalMs = timeline.total,
                onProgress = { cur, tot ->
                    _uiState.update { it.copy(importProgressText = "Importing $cur/$tot...") }
                },
                onError = { message ->
                    showToast(message)
                }
            )

            if (imported.isNotEmpty()) {
                val clampedClips = mutableListOf<Clip>()
                var accumulatedUs = msToUs(_uiState.value.project.clips.sumOf { it.durationMs })
                var hitCap = false

                for (c in imported) {
                    val clipDurUs = msToUs(c.durationMs)
                    if (accumulatedUs + clipDurUs <= MAX_TIMELINE_DURATION_US) {
                        clampedClips.add(c)
                        clipNaturalDurations[c.id] = clipDurUs
                        accumulatedUs += clipDurUs
                    } else {
                        val remainingUs = MAX_TIMELINE_DURATION_US - accumulatedUs
                        if (remainingUs >= OverlayItem.MIN_DURATION_US) {
                            val clampedDurMs = usToMs(remainingUs)
                            val clampedClip = c.copy(durationMs = clampedDurMs)
                            clampedClips.add(clampedClip)
                            clipNaturalDurations[c.id] = clipDurUs
                            clipTimeMaps[c.id] = TimeMap(0L, remainingUs)
                            accumulatedUs = MAX_TIMELINE_DURATION_US
                        }
                        hitCap = true
                        break
                    }
                }

                if (hitCap) {
                    showToast("Maximum timeline duration is 15 minutes")
                }

                if (clampedClips.isNotEmpty()) {
                    val newProject = ProgramTimeline.addClips(_uiState.value.project, clampedClips)
                    updateProject(newProject, recordUndo = true)

                    if (wasEmpty && !isAspectRatioUserLocked) {
                        val firstClip = clampedClips.first()
                        val autoMode = findMatchingAspectRatioMode(firstClip.displayWidth, firstClip.displayHeight)
                        _uiState.update {
                            it.copy(aspectRatioMode = autoMode)
                        }
                        preSheetAspectRatioMode = autoMode
                        val effective = resolveEffectiveAspect(autoMode)
                        PreviewRenderer.setAspectRatioMode(autoMode, firstClip)
                        PreviewRenderer.setCanvasAspectRatio(effective)
                        previewEngine?.requestRender()
                    }
                }
            }

            _uiState.update {
                it.copy(isImporting = false, importProgressText = "")
            }
        }
    }

    private val junctionTransitionModes = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private var preSheetTransitions: List<Transition>? = null
    private var preSheetJunctionModes: Map<Int, Int>? = null

    fun selectJunction(index: Int) {
        preSheetTransitions = _uiState.value.project.transitions
        preSheetJunctionModes = HashMap(junctionTransitionModes)
        _uiState.update {
            it.copy(
                selectedJunctionIndex = index,
                selectedClipIndex = null,
                selectedOverlayId = null,
                focusMode = TimelineFocusState.S0_NONE
            )
        }
    }

    fun mapTransitionModeToModel(mode: Int): TransitionType =
        mapTransitionModeToModelType(mode)

    fun getMaxTransitionDuration(junctionIndex: Int): Long {
        val clips = _uiState.value.project.clips
        if (junctionIndex < 0 || junctionIndex >= clips.size - 1) return 1000L
        val d1 = clips[junctionIndex].durationMs
        val d2 = clips[junctionIndex + 1].durationMs
        return maxOf(200L, minOf(d1, d2) - 100L)
    }

    fun getJunctionTransitionMode(junctionIndex: Int): Int {
        val stored = junctionTransitionModes[junctionIndex]
        if (stored != null) return stored
        return inferTransitionModeFromModelType(_uiState.value.project.transitions.getOrNull(junctionIndex)?.type)
    }

    fun setTransitionLive(junctionIndex: Int, mode: Int, durationMs: Long) {
        val currentProject = _uiState.value.project
        val targetSize = maxOf(0, currentProject.clips.size - 1)
        val newTransitions = currentProject.transitions.toMutableList()
        while (newTransitions.size < targetSize) {
            newTransitions.add(Transition())
        }
        if (junctionIndex !in newTransitions.indices) return
        if (mode <= 0) {
            junctionTransitionModes.remove(junctionIndex)
            PreviewRenderer.setJunctionTransitionType(junctionIndex, 0)
        } else {
            junctionTransitionModes[junctionIndex] = mode
            PreviewRenderer.setJunctionTransitionType(junctionIndex, mode)
        }
        val maxDur = getMaxTransitionDuration(junctionIndex)
        val defaultDur = (maxDur / 2L).coerceAtLeast(100L)
        val effectiveDur = if (durationMs <= 0L) defaultDur else durationMs
        val clampedDur = if (mode <= 0) 0L else effectiveDur.coerceIn(100L, maxDur)
        val modelType = if (mode <= 0) TransitionType.NONE else mapTransitionModeToModel(mode)
        newTransitions[junctionIndex] = Transition(modelType, clampedDur)
        val newProject = currentProject.copy(transitions = newTransitions)
        updateProject(newProject, recordUndo = false)
    }

    fun commitTransition(junctionIndex: Int, mode: Int, durationMs: Long) {
        val pre = preSheetTransitions
        if (pre != null) {
            val currentProject = _uiState.value.project
            pushUndoState(
                projectSnapshot = currentProject.copy(transitions = pre),
                junctionModesSnapshot = preSheetJunctionModes ?: junctionTransitionModes
            )
            preSheetTransitions = null
            preSheetJunctionModes = null
            _uiState.update {
                it.copy(
                    canUndo = undoHistory.canUndo,
                    canRedo = undoHistory.canRedo
                )
            }
        }
        setTransitionLive(junctionIndex, mode, durationMs)
    }

    fun commitApplyTransitionToAll(mode: Int, durationMs: Long) {
        val currentProject = _uiState.value.project
        val targetSize = maxOf(0, currentProject.clips.size - 1)
        if (targetSize <= 0) return
        pushUndoState(projectSnapshot = currentProject)
        val newTransitions = currentProject.transitions.toMutableList()
        while (newTransitions.size < targetSize) {
            newTransitions.add(Transition())
        }
        val modelType = if (mode <= 0) TransitionType.NONE else mapTransitionModeToModel(mode)
        for (j in newTransitions.indices) {
            val maxDur = getMaxTransitionDuration(j)
            val defaultDur = (maxDur / 2L).coerceAtLeast(100L)
            val effectiveDur = if (durationMs <= 0L) defaultDur else durationMs
            val clampedDur = if (mode <= 0) 0L else effectiveDur.coerceIn(100L, maxDur)
            newTransitions[j] = Transition(modelType, clampedDur)
            if (mode <= 0) {
                junctionTransitionModes.remove(j)
                PreviewRenderer.setJunctionTransitionType(j, 0)
            } else {
                junctionTransitionModes[j] = mode
                PreviewRenderer.setJunctionTransitionType(j, mode)
            }
        }
        val newProject = currentProject.copy(transitions = newTransitions)
        updateProject(newProject, recordUndo = false)
    }

    fun cancelTransition() {
        val pre = preSheetTransitions
        val preModes = preSheetJunctionModes
        if (pre != null) {
            val currentProject = _uiState.value.project
            val restoredProject = currentProject.copy(transitions = pre)
            if (preModes != null) {
                junctionTransitionModes.clear()
                junctionTransitionModes.putAll(preModes)
                PreviewRenderer.syncJunctionTransitions(preModes)
            }
            updateProject(restoredProject, recordUndo = false)
            preSheetTransitions = null
            preSheetJunctionModes = null
        }
    }

    fun applyTransition(type: TransitionType) {
        val junctionIndex = _uiState.value.selectedJunctionIndex ?: return
        val currentT = _uiState.value.project.transitions.getOrNull(junctionIndex) ?: return

        if (currentT.type == type) {
            // Same type: open Duration sheet
            _uiState.update { it.copy(durationSheetVisible = true) }
            return
        }

        val maxDur = timeline.maxDuration(junctionIndex)
        if (maxDur < 300L) {
            showToast("Clip is too short for this transition")
            return
        }

        val newDuration = if (currentT.type == TransitionType.NONE) {
            minOf(TRANSITION_DEFAULT_MS, maxDur)
        } else {
            minOf(currentT.durationMs, maxDur)
        }

        val newTransitions = _uiState.value.project.transitions.toMutableList()
        newTransitions[junctionIndex] = Transition(type, newDuration)
        val newProject = _uiState.value.project.copy(transitions = newTransitions)

        updateProject(newProject, recordUndo = true)

        // Auto-play preview:
        // overlapStart - 700 ms to overlapEnd + 700 ms, then pause
        val tl = ProgramTimeline(newProject)
        val overlapStart = tl.start[junctionIndex + 1]
        val overlapEnd = tl.end[junctionIndex]
        val playStart = maxOf(0L, overlapStart - AUTO_PLAY_MARGIN_MS)
        val playEnd = minOf(tl.total, overlapEnd + AUTO_PLAY_MARGIN_MS)

        seekTo(playStart)
        previewEngine?.play()

        autoPlayJob?.cancel()
        autoPlayJob = viewModelScope.launch {
            while (true) {
                delay(16L)
                val cur = _uiState.value.currentMs
                if (cur >= playEnd || !_uiState.value.isPlaying) {
                    previewEngine?.pause()
                    break
                }
            }
        }
    }

    fun onDurationSliderStart() {
        preDragProjectSnapshot = _uiState.value.project
    }

    fun updateTransitionDurationLive(durMs: Long) {
        val junctionIndex = _uiState.value.selectedJunctionIndex ?: return
        val currentT = _uiState.value.project.transitions.getOrNull(junctionIndex) ?: return
        val newTransitions = _uiState.value.project.transitions.toMutableList()
        newTransitions[junctionIndex] = currentT.copy(durationMs = durMs)
        val newProject = _uiState.value.project.copy(transitions = newTransitions)

        // Live update without undo step
        updateProject(newProject, recordUndo = false)
    }

    fun commitTransitionDuration(finalMs: Long) {
        val snapshot = preDragProjectSnapshot
        if (snapshot != null) {
            pushUndoState(projectSnapshot = snapshot)
            preDragProjectSnapshot = null
            _uiState.update {
                it.copy(
                    canUndo = undoHistory.canUndo,
                    canRedo = undoHistory.canRedo
                )
            }
        }
        updateTransitionDurationLive(finalMs)
    }

    fun removeTransition() {
        val junctionIndex = _uiState.value.selectedJunctionIndex ?: return
        val newTransitions = _uiState.value.project.transitions.toMutableList()
        newTransitions[junctionIndex] = Transition(TransitionType.NONE, 0L)
        val newProject = _uiState.value.project.copy(transitions = newTransitions)
        updateProject(newProject, recordUndo = true)
        _uiState.update { it.copy(durationSheetVisible = false) }
    }

    fun closeDurationSheet() {
        _uiState.update { it.copy(durationSheetVisible = false) }
    }

    private fun restoreSnapshot(restored: EditorUndoSnapshot) {
        clipTimeMaps.clear()
        clipTimeMaps.putAll(restored.timeMaps)

        clipVolumes.clear()
        clipVolumes.putAll(restored.volumes)

        clipTransforms.clear()
        clipTransforms.putAll(restored.transforms)

        clipColorAdjusts.clear()
        clipColorAdjusts.putAll(restored.colorAdjusts)

        clipBackgrounds.clear()
        clipBackgrounds.putAll(restored.backgrounds)

        _layerItems.clear()
        _layerItems.addAll(restored.layerItems)

        junctionTransitionModes.clear()
        junctionTransitionModes.putAll(restored.junctionTransitionModes)

        preSheetAspectRatioMode = restored.aspectRatioMode
        val isAllMuted = restored.project.clips.isNotEmpty() && restored.project.clips.all { (clipVolumes[it.id] ?: 1.0f) <= 0.0001f }

        syncAllRenderAndAudioState(
            project = restored.project,
            aspectRatioMode = restored.aspectRatioMode,
            mainTrackMuted = isAllMuted
        )
        updateProject(restored.project, recordUndo = false)
        _uiState.update {
            it.copy(
                aspectRatioMode = restored.aspectRatioMode,
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                selectedOverlayId = null,
                focusMode = TimelineFocusState.S0_NONE,
                isMainTrackMuted = isAllMuted
            )
        }
    }

    fun undo() {
        val previous = undoHistory.undo(captureCurrentSnapshot()) ?: return
        restoreSnapshot(previous)
    }

    fun redo() {
        val next = undoHistory.redo(captureCurrentSnapshot()) ?: return
        restoreSnapshot(next)
    }

    fun openExportSheet() {
        pause()
        _uiState.update {
            it.copy(
                exportSheetVisible = true,
                exportState = ExportState.Ready
            )
        }
    }

    fun closeExportSheet() {
        if (_uiState.value.exportState is ExportState.Exporting) return
        _uiState.update { it.copy(exportSheetVisible = false) }
    }

    fun setExportResolution(resolution: Int) {
        _uiState.update { it.copy(exportResolution = resolution) }
    }

    fun setExportFps(fps: Int) {
        _uiState.update { it.copy(exportFps = fps) }
    }

    fun setExportBitratePreset(preset: Int) {
        _uiState.update { it.copy(exportBitratePreset = preset) }
    }

    fun setExportBitrateBps(bitrateBps: Int) {
        _uiState.update { it.copy(exportBitrateBps = bitrateBps) }
    }

    fun setExportConfig(resolution: Int, fps: Int, bitratePreset: Int) {
        val calculatedBps = calculateTargetBitrateBps(resolution, fps, bitratePreset)
        _uiState.update {
            it.copy(
                exportResolution = resolution,
                exportFps = fps,
                exportBitratePreset = bitratePreset,
                exportBitrateBps = calculatedBps
            )
        }
    }

    fun setExportConfigWithBitrate(resolution: Int, fps: Int, bitrateBps: Int) {
        _uiState.update {
            it.copy(
                exportResolution = resolution,
                exportFps = fps,
                exportBitrateBps = bitrateBps
            )
        }
    }

    fun getTargetBitrateBps(
        resolution: Int = _uiState.value.exportResolution,
        fps: Int = _uiState.value.exportFps,
        bitratePreset: Int = _uiState.value.exportBitratePreset
    ): Int = calculateTargetBitrateBps(resolution, fps, bitratePreset)

    fun getSnapshot(): TimelineSnapshot =
        buildTimelineSnapshot(_uiState.value.project, clipTimeMaps)

    fun startExportWithConfig(
        resolution: Int = _uiState.value.exportResolution,
        fps: Int = _uiState.value.exportFps,
        bitrateBps: Int = _uiState.value.exportBitrateBps
    ) {
        pause()
        _uiState.update {
            it.copy(
                exportResolution = resolution,
                exportFps = fps,
                exportBitrateBps = bitrateBps,
                exportSheetVisible = false,
                currentScreen = AppScreen.EXPORT,
                exportState = ExportState.Exporting(0f),
                exportErrorMessage = null,
                exportFallbackNotice = null
            )
        }

        val snapshot = getSnapshot()
        val ratioMode = _uiState.value.aspectRatioMode

        val aspect = if (ratioMode == AspectRatioMode.ORIGINAL) {
            val clip0 = _uiState.value.project.clips.firstOrNull()
            if (clip0 != null) {
                val rot = clip0.rotation
                val dispW = if (rot == 90 || rot == 270) clip0.height else clip0.width
                val dispH = if (rot == 90 || rot == 270) clip0.width else clip0.height
                Aspect(dispW.coerceAtLeast(1), dispH.coerceAtLeast(1))
            } else {
                Aspect.R9_16
            }
        } else {
            Aspect(ratioMode.ratioWidth.coerceAtLeast(1), ratioMode.ratioHeight.coerceAtLeast(1))
        }

        val quality = when {
            bitrateBps >= 25_000_000 -> Quality.EXTREME
            bitrateBps >= 18_000_000 -> Quality.ULTRA
            bitrateBps >= 10_000_000 -> Quality.HIGH
            bitrateBps >= 5_000_000 -> Quality.MEDIUM
            else -> Quality.LOW
        }

        val spec = OutputSpec(
            aspect = aspect,
            shortSide = resolution,
            fps = fps,
            quality = quality,
            codec = OutCodec.AVC
        )

        val request = ExportRequest(
            snapshot = snapshot,
            spec = spec,
            explicitBitrateBps = if (bitrateBps > 0) bitrateBps else null
        )
        ExportCoordinator.start(getApplication(), request)
    }

    fun startExport() {
        val targetBitrate = if (_uiState.value.exportBitrateBps > 0) {
            _uiState.value.exportBitrateBps
        } else {
            getTargetBitrateBps(_uiState.value.exportResolution, _uiState.value.exportFps, _uiState.value.exportBitratePreset)
        }
        startExportWithConfig(
            resolution = _uiState.value.exportResolution,
            fps = _uiState.value.exportFps,
            bitrateBps = targetBitrate
        )
    }

    fun cancelExport(reason: String = "Export cancelled by user") {
        ExportCoordinator.cancel(reason)
        _uiState.update {
            it.copy(
                exportState = ExportState.Failed,
                exportErrorMessage = reason
            )
        }
    }

    fun returnToEditorFromExport() {
        if (_uiState.value.exportState is ExportState.Exporting) {
            cancelExport("Export cancelled by user")
        }
        PreviewRenderer.resumeActiveDecoders()
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.EDITOR,
                exportState = ExportState.Ready,
                exportSheetVisible = false,
                exportErrorMessage = null
            )
        }
    }

    fun onAppBackground() {
        previewEngine?.onPause()
    }

    fun onAppStop() {
    }

    fun onAppResume() {
        previewEngine?.onResume()
    }

    fun setProcessingEdit(processing: Boolean) {
        _uiState.update { it.copy(isProcessingEdit = processing) }
        loadingManager.setLoading(LoadingSources.HEAVY_PROCESSING, processing, "Processing...")
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    companion object {
        fun calculateRecommendedBitrateBps(resolution: Int, fps: Int): Int =
            computeRecommendedExportBitrateBps(resolution, fps)

        fun calculateTargetBitrateBps(resolution: Int, fps: Int, bitratePreset: Int): Int =
            computeTargetExportBitrateBps(resolution, fps, bitratePreset)
    }

    fun onTrimMemory(level: Int) {
        val isLow = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        isMemoryPressureActive.set(isLow)
        if (isLow) {
            qualityGovernor.update(
                nowMs = SystemClock.uptimeMillis(),
                moderate = thermalMonitor.isModerate,
                severe = thermalMonitor.isSevere,
                headroom = thermalMonitor.headroom,
                lateFramePct = 0f,
                memLow = true
            )
            OverlayTextureCache.pruneDefaultCache()
            ThumbnailService.trimActiveMemory(32)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            thermalMonitor.stop()
            perfExecutor.shutdownNow()
        } catch (_: Throwable) {}
        pipProxyGenerator.release()
        previewEngine?.release()
    }
}

