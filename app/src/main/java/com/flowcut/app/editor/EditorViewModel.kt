package com.flowcut.app.editor

import android.app.Application
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import com.flowcut.app.engine.BackgroundType
import com.flowcut.app.engine.ClipBackground
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.ClipTransform
import com.flowcut.app.engine.CompiledLayerPlan
import com.flowcut.app.engine.ExportEngine
import com.flowcut.app.engine.LayerTrackGroup
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.PreviewEngine
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.engine.canAddLayer
import com.flowcut.app.engine.clampItemTrimBoundsOnRow
import com.flowcut.app.engine.findAvailableRowOrClamp
import com.flowcut.app.engine.trackGroup
import com.flowcut.app.ui.timeline.TimelineFocusState
import com.flowcut.app.model.AUTO_PLAY_MARGIN_MS
import com.flowcut.app.model.Clip
import com.flowcut.app.model.MAX_CLIPS
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.Project
import com.flowcut.app.model.TRANSITION_DEFAULT_MS
import com.flowcut.app.model.Transition
import com.flowcut.app.model.TransitionType
import com.flowcut.app.storage.ClipImporter
import com.flowcut.app.storage.ProjectStore
import com.flowcut.app.ui.ExportState
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
    EDITOR
}

data class EditorUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
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
    val importProgressText: String = "",
    val durationSheetVisible: Boolean = false,
    val exportSheetVisible: Boolean = false,
    val exportState: ExportState = ExportState.Ready,
    val exportedUri: Uri? = null,
    val toastMessage: String? = null,
    val isMainTrackMuted: Boolean = false
)

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

    private val projectStore = ProjectStore(application)
    private val clipImporter = ClipImporter(application)
    private val exportEngine = ExportEngine(application)
    private val undoStack = UndoStack()

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    val timeline: ProgramTimeline
        get() = ProgramTimeline(_uiState.value.project)

    var previewEngine: PreviewEngine? = null

    private var autoSaveJob: Job? = null
    private var exportJob: Job? = null
    private var autoPlayJob: Job? = null

    private var preDragProjectSnapshot: Project? = null

    private val undoTimeMaps = ArrayDeque<Map<String, TimeMap>>()
    private val redoTimeMaps = ArrayDeque<Map<String, TimeMap>>()
    val clipTimeMaps = mutableMapOf<String, TimeMap>()
    val clipNaturalDurations = mutableMapOf<String, Long>()

    // Per-clip volume (0.0f to 2.0f, default 1.0f), pre-mute memory, and undo/redo stacks
    private val undoVolumes = ArrayDeque<Map<String, Float>>()
    private val redoVolumes = ArrayDeque<Map<String, Float>>()
    val clipVolumes = mutableMapOf<String, Float>()
    val savedPreMuteVolumes = mutableMapOf<String, Float>()

    // Per-clip transform (rotation: 0/90/180/270, isFlippedH: Boolean) and undo/redo stacks
    private val undoTransforms = ArrayDeque<Map<String, ClipTransform>>()
    private val redoTransforms = ArrayDeque<Map<String, ClipTransform>>()
    val clipTransforms = mutableMapOf<String, ClipTransform>()

    // Per-clip color adjustments and undo/redo stacks (Requirement 1 & 4)
    private val undoColorAdjusts = ArrayDeque<Map<String, ClipColorAdjust>>()
    private val redoColorAdjusts = ArrayDeque<Map<String, ClipColorAdjust>>()
    val clipColorAdjusts = mutableMapOf<String, ClipColorAdjust>()

    // Per-clip canvas background (NONE, COLOR, BLUR) and undo/redo stacks
    private val undoBackgrounds = ArrayDeque<Map<String, ClipBackground>>()
    private val redoBackgrounds = ArrayDeque<Map<String, ClipBackground>>()
    val clipBackgrounds = mutableMapOf<String, ClipBackground>()

    // Multi-layer Overlay Items and undo/redo stacks
    private val undoLayerItems = ArrayDeque<List<OverlayItem>>()
    private val redoLayerItems = ArrayDeque<List<OverlayItem>>()
    private val _layerItems = mutableStateListOf<OverlayItem>()
    val layerItems: List<OverlayItem> get() = _layerItems

    private fun syncLayerPlan() {
        val items = _layerItems.toList()
        val plan = CompiledLayerPlan.compile(items)
        PreviewRenderer.setLayerPlan(plan)
        val audioOverlays = items.filter { it.type == LayerType.AUDIO }
        ClipAudioContributor.syncAudioOverlays(audioOverlays)
        for (item in audioOverlays) {
            if (item.mediaPath.isNotEmpty()) {
                ClipAudioContributor.prewarmAudioPath(item.mediaPath)
            }
        }
    }

    fun getClipBackground(clipId: String): ClipBackground =
        clipBackgrounds[clipId] ?: ClipBackground.DEFAULT

    fun commitClipBackground(clipIndex: Int, background: ClipBackground) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        clipBackgrounds[clip.id] = background
        PreviewRenderer.setClipBackground(clip.id, background)

        updateProject(currentProject, recordUndo = false)
    }

    fun commitApplyBackgroundToAll(background: ClipBackground) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        for (c in currentProject.clips) {
            clipBackgrounds[c.id] = background
            PreviewRenderer.setClipBackground(c.id, background)
        }

        updateProject(currentProject, recordUndo = false)
    }

    fun getClipColorAdjust(clipId: String): ClipColorAdjust =
        clipColorAdjusts[clipId] ?: ClipColorAdjust.DEFAULT

    fun commitClipColorAdjust(clipIndex: Int, adjust: ClipColorAdjust) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        clipColorAdjusts[clip.id] = adjust
        PreviewRenderer.setClipColorAdjust(clip.id, adjust)

        updateProject(currentProject, recordUndo = false)
    }

    fun commitApplyColorAdjustToAll(adjust: ClipColorAdjust) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        for (c in currentProject.clips) {
            clipColorAdjusts[c.id] = adjust
            PreviewRenderer.setClipColorAdjust(c.id, adjust)
        }

        updateProject(currentProject, recordUndo = false)
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
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

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
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        clipTransforms[clip.id] = newTransform
        PreviewRenderer.setClipTransform(clip.id, newTransform)

        updateProject(currentProject, recordUndo = false)
        return newTransform
    }

    fun commitClipTransform(clipIndex: Int, transform: ClipTransform) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

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

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

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
        if (!canAddLayer(_layerItems, item.type)) return false
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
            // Text or Sticker (Row A Pins / Row B Bar)
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
            LayerType.STICKER -> TimelineFocusState.S3_STICKER_ITEM(resolvedItem.id)
            LayerType.VIDEO, LayerType.IMAGE -> TimelineFocusState.S2_PIP_ITEM(resolvedItem.id)
            LayerType.AUDIO -> TimelineFocusState.S6_AUDIO_ITEM(resolvedItem.id)
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
        volume: Float = 1.0f
    ): OverlayItem? {
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
            sourceInUs = sourceInUs
        )
        return if (addLayerItem(item)) _layerItems.lastOrNull() else null
    }

    fun addTextOverlay(
        text: String = "",
        textColorArgb: Int = 0xFFFFFFFF.toInt(),
        bgStyle: Int = 0,
        startUs: Long,
        durationUs: Long = 3_000_000L
    ): OverlayItem? {
        if (!canAddLayer(_layerItems, LayerType.TEXT)) return null
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
        if (!canAddLayer(_layerItems, LayerType.STICKER)) return null
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
     * Imports a custom photo sticker from StickerPickerSheet into Row A / Row B as LayerType.STICKER.
     */
    fun addImageStickerOverlay(
        mediaPath: String,
        startUs: Long,
        durationUs: Long = 3_000_000L
    ): OverlayItem? {
        if (!canAddLayer(_layerItems, LayerType.STICKER)) return null
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
        if (!canAddLayer(_layerItems, LayerType.IMAGE)) return null
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
        if (!canAddLayer(_layerItems, LayerType.VIDEO)) return null
        val endUs = startUs + durationUs
        var intrinsicWidth = 0
        var intrinsicHeight = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mediaPath)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            var w = widthStr?.toIntOrNull() ?: 0
            var h = heightStr?.toIntOrNull() ?: 0
            val rotation = rotationStr?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val tmp = w
                w = h
                h = tmp
            }
            intrinsicWidth = w
            intrinsicHeight = h
        } catch (_: Throwable) {
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        val item = OverlayItem(
            id = UUID.randomUUID().toString(),
            type = LayerType.VIDEO,
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

    fun copyLayerItem(itemId: String): OverlayItem? {
        val existing = _layerItems.firstOrNull { it.id == itemId } ?: return null
        if (!canAddLayer(_layerItems, existing.type)) return null

        val durUs = existing.durationUs
        val newStartUs = existing.endUs
        val newEndUs = newStartUs + durUs
        val copy = existing.copy(
            id = UUID.randomUUID().toString(),
            laneIndex = -1,
            startUs = newStartUs,
            endUs = newEndUs,
            zIndex = 0
        )
        return if (addLayerItem(copy)) _layerItems.lastOrNull() else null
    }

    private fun pushUndoState() {
        val currentProject = _uiState.value.project
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()
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
            LayerType.STICKER -> TimelineFocusState.S3_STICKER_ITEM(itemId)
            LayerType.VIDEO, LayerType.IMAGE -> TimelineFocusState.S2_PIP_ITEM(itemId)
            LayerType.AUDIO -> TimelineFocusState.S6_AUDIO_ITEM(itemId)
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

        if (!canAddLayer(_layerItems, item.type)) return null

        pushUndoState()

        val leftItem = item.copy(endUs = playheadUs)
        val rightId = UUID.randomUUID().toString()
        val topZ = getTopZIndex()
        val splitOffsetUs = playheadUs - item.startUs
        val rightSourceInUs = if (item.type == LayerType.AUDIO) item.sourceInUs + splitOffsetUs else item.sourceInUs
        val rightItem = item.copy(
            id = rightId,
            startUs = playheadUs,
            endUs = item.endUs,
            sourceInUs = rightSourceInUs,
            zIndex = topZ
        )

        _layerItems[idx] = leftItem
        _layerItems.add(rightItem)
        syncLayerPlan()

        val newFocus = when (item.type) {
            LayerType.VIDEO, LayerType.IMAGE -> TimelineFocusState.S2_PIP_ITEM(rightId)
            LayerType.TEXT -> TimelineFocusState.S4_TEXT_ITEM(rightId)
            LayerType.STICKER -> TimelineFocusState.S3_STICKER_ITEM(rightId)
            LayerType.AUDIO -> TimelineFocusState.S6_AUDIO_ITEM(rightId)
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
        val newSourceInUs = if (item.type == LayerType.AUDIO) (item.sourceInUs + deltaStartUs).coerceAtLeast(0L) else item.sourceInUs
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
        val newSourceInUs = if (item.type == LayerType.AUDIO) (item.sourceInUs + deltaStartUs).coerceAtLeast(0L) else item.sourceInUs
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
        _layerItems[idx] = item.copy(speed = clampedSpeed)
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

    fun setOverlayTransformLive(itemId: String, transform: ClipTransform) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _layerItems[idx]
        _layerItems[idx] = item.copy(transform = transform)
        syncLayerPlan()
    }

    fun commitOverlayTransform(itemId: String, transform: ClipTransform) {
        val idx = _layerItems.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val item = _layerItems[idx]

        pushUndoState()
        val topZ = getTopZIndex()
        _layerItems[idx] = item.copy(transform = transform, zIndex = topZ)
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

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

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

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        clipTransforms[clip.id] = newTransform
        PreviewRenderer.setClipTransform(clip.id, newTransform)

        updateProject(currentProject, recordUndo = false)
    }

    fun commitApplyScaleToAll(scale: Float, panX: Float, panY: Float) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

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

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

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

    fun commitClipVolume(clipIndex: Int, volume: Float) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return
        val clampedVol = volume.coerceIn(0.0f, 2.0f)

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

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

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

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
    }

    fun getClipNaturalDurationUs(clipIndex: Int): Long {
        val clip = _uiState.value.project.clips.getOrNull(clipIndex) ?: return 0L
        return clipNaturalDurations.getOrPut(clip.id) {
            getMediaFileDurationUs(clip)
        }
    }

    private fun getMediaFileDurationUs(clip: Clip): Long {
        return try {
            val file = java.io.File(clip.path)
            if (file.exists()) {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                durStr?.toLongOrNull()?.let { msToUs(it) } ?: msToUs(clip.durationMs)
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

        val newDurationMs: Long
        val finalMap: TimeMap
        if (speedNum == 0) {
            // Freeze clip (speedNum == 0): inUs == outUs == freezeSourceUs.
            // Allow changing timeline durationMs freely between 200ms and 30,000ms.
            val dur = targetDurationMs ?: run {
                val diffUs = kotlin.math.abs(newOutUs - newInUs)
                if (diffUs > 0L) usToMs(diffUs) else oldClip.durationMs
            }
            newDurationMs = dur.coerceIn(200L, 30_000L)
            finalMap = TimeMap(oldMap.inUs, oldMap.inUs, 0, 100)
        } else {
            val timelineDurUs = ((newOutUs - newInUs) * speedDen.toLong()) / speedNum.toLong()
            newDurationMs = usToMs(timelineDurUs)
            if (newDurationMs < 200L) return
            finalMap = TimeMap(newInUs, newOutUs, speedNum, speedDen)
        }

        val newClip = oldClip.copy(durationMs = newDurationMs)
        val newClips = currentProject.clips.toMutableList()
        newClips[clipIndex] = newClip

        // Adjust adjacent transitions if needed (Section 4 invariants: transition <= min(d1, d2) / 2)
        val newTransitions = currentProject.transitions.toMutableList()
        for (j in maxOf(0, clipIndex - 1)..minOf(newTransitions.size - 1, clipIndex)) {
            val maxAllowed = minOf(newClips[j].durationMs, newClips[j + 1].durationMs) / 2L
            if (newTransitions[j].durationMs > maxAllowed) {
                newTransitions[j] = newTransitions[j].copy(durationMs = maxAllowed)
            }
        }

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()
        clipTimeMaps[oldClip.id] = finalMap
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
            (kotlin.math.round(raw * 100f) / 100f).coerceIn(0.2f, 10.0f)
        } else 1.0f
    }

    fun getMaxSpeedForClip(clipIndex: Int): Float {
        val clip = _uiState.value.project.clips.getOrNull(clipIndex) ?: return 10.0f
        val oldMap = clipTimeMaps[clip.id] ?: TimeMap(0L, msToUs(clip.durationMs))
        if (oldMap.speedNum == 0) return 1.0f
        val sourceSpanUs = maxOf(200_000L, oldMap.outUs - oldMap.inUs)
        return (sourceSpanUs.toFloat() / 200_000f).coerceIn(0.2f, 10.0f)
    }

    fun setLiveClipSpeed(clipIndex: Int, speed: Float) {
        val currentProject = _uiState.value.project
        if (clipIndex !in currentProject.clips.indices) return
        val oldClip = currentProject.clips[clipIndex]
        val oldMap = clipTimeMaps[oldClip.id] ?: TimeMap(0L, msToUs(oldClip.durationMs))
        val sourceSpanUs = oldMap.outUs - oldMap.inUs

        val maxSpeed = (sourceSpanUs.toFloat() / 200_000f).coerceIn(0.2f, 10.0f)
        val clampedSpeed = speed.coerceIn(0.2f, maxSpeed)
        val speedNum = maxOf(1, (clampedSpeed * 100f).roundToInt())
        val speedDen = 100

        val timelineDurUs = (sourceSpanUs * speedDen.toLong()) / speedNum.toLong()
        val newDurMs = maxOf(200L, usToMs(timelineDurUs))

        val newClip = oldClip.copy(durationMs = newDurMs)
        val newClips = currentProject.clips.toMutableList()
        newClips[clipIndex] = newClip

        val newTransitions = currentProject.transitions.toMutableList()
        for (j in maxOf(0, clipIndex - 1)..minOf(newTransitions.size - 1, clipIndex)) {
            val maxAllowed = minOf(newClips[j].durationMs, newClips[j + 1].durationMs) / 2L
            if (newTransitions[j].durationMs > maxAllowed) {
                newTransitions[j] = newTransitions[j].copy(durationMs = maxAllowed)
            }
        }

        clipTimeMaps[oldClip.id] = TimeMap(oldMap.inUs, oldMap.outUs, speedNum, speedDen)
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

        undoStack.push(snapProject)
        undoTimeMaps.addLast(HashMap(snapTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        setLiveClipSpeed(clipIndex, speed)
    }

    fun commitApplySpeedToAll(
        speed: Float,
        initialProject: Project? = null,
        initialTimeMaps: Map<String, TimeMap>? = null
    ) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        val snapProject = initialProject ?: currentProject
        val snapTimeMaps = initialTimeMaps ?: HashMap(clipTimeMaps)

        undoStack.push(snapProject)
        undoTimeMaps.addLast(HashMap(snapTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        val speedDen = 100
        val newClips = currentProject.clips.toMutableList()

        for (i in newClips.indices) {
            val clip = newClips[i]
            val oldMap = clipTimeMaps[clip.id] ?: TimeMap(0L, msToUs(clip.durationMs))
            val sourceSpanUs = oldMap.outUs - oldMap.inUs
            val maxSpeed = (sourceSpanUs.toFloat() / 200_000f).coerceIn(0.2f, 10.0f)
            val clampedSpeed = speed.coerceIn(0.2f, maxSpeed)
            val speedNum = maxOf(1, (clampedSpeed * 100f).roundToInt())

            val timelineDurUs = (sourceSpanUs * speedDen.toLong()) / speedNum.toLong()
            val newDurMs = maxOf(200L, usToMs(timelineDurUs))

            newClips[i] = clip.copy(durationMs = newDurMs)
            clipTimeMaps[clip.id] = TimeMap(oldMap.inUs, oldMap.outUs, speedNum, speedDen)
        }

        val newTransitions = currentProject.transitions.toMutableList()
        for (j in newTransitions.indices) {
            val maxAllowed = minOf(newClips[j].durationMs, newClips[j + 1].durationMs) / 2L
            if (newTransitions[j].durationMs > maxAllowed) {
                newTransitions[j] = newTransitions[j].copy(durationMs = maxAllowed)
            }
        }

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)
        updateProject(newProject, recordUndo = false)
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
        for (j in maxOf(0, targetK - 1)..minOf(newTransitions.size - 1, targetK + 1)) {
            if (j < newTransitions.size && j + 1 < newClips.size) {
                val maxAllowed = minOf(newClips[j].durationMs, newClips[j + 1].durationMs) / 2L
                if (newTransitions[j].durationMs > maxAllowed) {
                    newTransitions[j] = newTransitions[j].copy(durationMs = maxAllowed)
                }
            }
        }

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)

        // Push current state onto Undo stack BEFORE applying the split
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        // Register natural duration for both halves
        val naturalDur = clipNaturalDurations[oldClip.id] ?: getMediaFileDurationUs(oldClip)
        clipNaturalDurations[oldClip.id] = naturalDur
        clipNaturalDurations[rightClipId] = naturalDur

        // Update clipTimeMaps
        clipTimeMaps[oldClip.id] = leftMap
        clipTimeMaps[rightClipId] = rightMap

        // Inherit volume and pre-mute memory for both halves
        val origVol = clipVolumes[oldClip.id] ?: 1.0f
        clipVolumes[oldClip.id] = origVol
        clipVolumes[rightClipId] = origVol
        savedPreMuteVolumes[oldClip.id]?.let { savedPreMuteVolumes[rightClipId] = it }
        ClipAudioContributor.setClipVolume(oldClip.id, origVol)
        ClipAudioContributor.setClipVolume(rightClipId, origVol)

        // Inherit transform for both halves
        val origTransform = clipTransforms[oldClip.id] ?: ClipTransform.DEFAULT
        clipTransforms[oldClip.id] = origTransform
        clipTransforms[rightClipId] = origTransform
        PreviewRenderer.setClipTransform(oldClip.id, origTransform)
        PreviewRenderer.setClipTransform(rightClipId, origTransform)

        // Inherit color adjust for both halves (Requirement 4)
        val origAdjust = clipColorAdjusts[oldClip.id] ?: ClipColorAdjust.DEFAULT
        clipColorAdjusts[oldClip.id] = origAdjust
        clipColorAdjusts[rightClipId] = origAdjust
        PreviewRenderer.setClipColorAdjust(oldClip.id, origAdjust)
        PreviewRenderer.setClipColorAdjust(rightClipId, origAdjust)

        // Inherit canvas background for both halves (Requirement 5)
        val origBg = clipBackgrounds[oldClip.id] ?: ClipBackground.DEFAULT
        clipBackgrounds[oldClip.id] = origBg
        clipBackgrounds[rightClipId] = origBg
        PreviewRenderer.setClipBackground(oldClip.id, origBg)
        PreviewRenderer.setClipBackground(rightClipId, origBg)

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
    }

    fun duplicateClip(clipIndex: Int): DuplicateResult? {
        val currentProject = _uiState.value.project
        if (clipIndex !in currentProject.clips.indices) return null
        val oldClip = currentProject.clips[clipIndex]
        val newId = java.util.UUID.randomUUID().toString()
        val newClip = oldClip.copy(id = newId)

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
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        // Copy ALL per-clip metadata from oldClip.id to newId
        val oldMap = clipTimeMaps[oldClip.id] ?: TimeMap(0L, msToUs(oldClip.durationMs))
        clipTimeMaps[newId] = oldMap
        val natDur = clipNaturalDurations[oldClip.id] ?: getMediaFileDurationUs(oldClip)
        clipNaturalDurations[newId] = natDur

        val vol = clipVolumes[oldClip.id] ?: 1.0f
        clipVolumes[newId] = vol
        savedPreMuteVolumes[oldClip.id]?.let { savedPreMuteVolumes[newId] = it }
        ClipAudioContributor.setClipVolume(newId, vol)

        val transform = clipTransforms[oldClip.id] ?: ClipTransform.DEFAULT
        clipTransforms[newId] = transform
        PreviewRenderer.setClipTransform(newId, transform)

        val adjust = clipColorAdjusts[oldClip.id] ?: ClipColorAdjust.DEFAULT
        clipColorAdjusts[newId] = adjust
        PreviewRenderer.setClipColorAdjust(newId, adjust)

        val bg = clipBackgrounds[oldClip.id] ?: ClipBackground.DEFAULT
        clipBackgrounds[newId] = bg
        PreviewRenderer.setClipBackground(newId, bg)

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

        val updatedClip = oldClip.copy(
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
            for (j in maxOf(0, clipIndex - 1)..minOf(newTransitions.size - 1, clipIndex)) {
                if (j < newTransitions.size && j + 1 < newClips.size) {
                    val maxAllowed = minOf(newClips[j].durationMs, newClips[j + 1].durationMs) / 2L
                    if (newTransitions[j].durationMs > maxAllowed) {
                        newTransitions[j] = newTransitions[j].copy(durationMs = maxAllowed)
                    }
                }
            }
        }

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)

        // Push current state onto Undo stack
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        // Reset transforms to default
        clipTransforms[oldClip.id] = ClipTransform.DEFAULT
        PreviewRenderer.setClipTransform(oldClip.id, ClipTransform.DEFAULT)

        // Register natural duration and timeMap
        clipNaturalDurations[oldClip.id] = msToUs(newNaturalMs)
        clipTimeMaps[oldClip.id] = newMap

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

        val freezeDurationMs = 3000L
        val freezeClipId = java.util.UUID.randomUUID().toString()

        val newClips = currentProject.clips.toMutableList()
        val newTransitions: ArrayList<Transition>
        val freezeIndex: Int
        val freezeSourceUs: Long

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
        for (j in maxOf(0, selectedK - 1)..minOf(newTransitions.size - 1, freezeIndex + 1)) {
            if (j < newTransitions.size && j + 1 < newClips.size) {
                val maxAllowed = minOf(newClips[j].durationMs, newClips[j + 1].durationMs) / 2L
                if (newTransitions[j].durationMs > maxAllowed) {
                    newTransitions[j] = newTransitions[j].copy(durationMs = maxAllowed)
                }
            }
        }

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)

        // Push current state onto Undo stack
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        undoBackgrounds.addLast(HashMap(clipBackgrounds))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        redoBackgrounds.clear()
        undoLayerItems.addLast(ArrayList(_layerItems))
        redoLayerItems.clear()

        // Set up freeze clip metadata
        clipTimeMaps[freezeClipId] = TimeMap(
            inUs = freezeSourceUs,
            outUs = freezeSourceUs,
            speedNum = 0,
            speedDen = 100
        )
        val origNaturalDur = clipNaturalDurations[oldClip.id] ?: getMediaFileDurationUs(oldClip)
        clipNaturalDurations[freezeClipId] = origNaturalDur

        // Volume: 0f (silent still frame)
        clipVolumes[freezeClipId] = 0f
        ClipAudioContributor.setClipVolume(freezeClipId, 0f)

        // Inherit transform, color adjust, background
        val origTransform = clipTransforms[oldClip.id] ?: ClipTransform.DEFAULT
        clipTransforms[freezeClipId] = origTransform
        PreviewRenderer.setClipTransform(freezeClipId, origTransform)

        val origAdjust = clipColorAdjusts[oldClip.id] ?: ClipColorAdjust.DEFAULT
        clipColorAdjusts[freezeClipId] = origAdjust
        PreviewRenderer.setClipColorAdjust(freezeClipId, origAdjust)

        val origBg = clipBackgrounds[oldClip.id] ?: ClipBackground.DEFAULT
        clipBackgrounds[freezeClipId] = origBg
        PreviewRenderer.setClipBackground(freezeClipId, origBg)

        // If Case A, also register rightClip metadata
        if (rightClipId != null && rightMap != null) {
            clipTimeMaps[rightClipId] = rightMap
            clipNaturalDurations[rightClipId] = origNaturalDur

            val origVol = clipVolumes[oldClip.id] ?: 1.0f
            clipVolumes[rightClipId] = origVol
            savedPreMuteVolumes[oldClip.id]?.let { savedPreMuteVolumes[rightClipId] = it }
            ClipAudioContributor.setClipVolume(rightClipId, origVol)

            clipTransforms[rightClipId] = origTransform
            PreviewRenderer.setClipTransform(rightClipId, origTransform)

            clipColorAdjusts[rightClipId] = origAdjust
            PreviewRenderer.setClipColorAdjust(rightClipId, origAdjust)

            clipBackgrounds[rightClipId] = origBg
            PreviewRenderer.setClipBackground(rightClipId, origBg)
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
        loadInitialProject()
    }

    fun initPreviewEngine(engine: PreviewEngine) {
        previewEngine = engine
        engine.timeline = timeline
    }

    private fun loadInitialProject() {
        viewModelScope.launch {
            val loaded = projectStore.load()
            projectStore.cleanupOrphanClips(loaded)
            for (clip in loaded.clips) {
                if (!clipNaturalDurations.containsKey(clip.id)) {
                    clipNaturalDurations[clip.id] = getMediaFileDurationUs(clip)
                }
            }
            _uiState.update {
                it.copy(
                    project = loaded,
                    currentMs = 0L,
                    canUndo = undoStack.canUndo,
                    canRedo = undoStack.canRedo
                )
            }
            previewEngine?.let { engine ->
                engine.timeline = ProgramTimeline(loaded)
                engine.seekTo(0L)
            }
        }
    }

    private fun updateProject(newProject: Project, recordUndo: Boolean = true) {
        if (recordUndo) {
            undoStack.push(_uiState.value.project)
            undoTimeMaps.addLast(HashMap(clipTimeMaps))
            undoVolumes.addLast(HashMap(clipVolumes))
            undoTransforms.addLast(HashMap(clipTransforms))
            undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
            undoBackgrounds.addLast(HashMap(clipBackgrounds))
            redoTimeMaps.clear()
            redoVolumes.clear()
            redoTransforms.clear()
            redoColorAdjusts.clear()
            redoBackgrounds.clear()
            undoLayerItems.addLast(ArrayList(_layerItems))
            redoLayerItems.clear()
        }
        val newTimeline = ProgramTimeline(newProject)
        val clampedMs = _uiState.value.currentMs.coerceIn(0L, maxOf(0L, newTimeline.total))

        _uiState.update {
            it.copy(
                project = newProject,
                currentMs = clampedMs,
                canUndo = undoStack.canUndo,
                canRedo = undoStack.canRedo
            )
        }

        previewEngine?.let { engine ->
            engine.timeline = newTimeline
            engine.seekTo(clampedMs)
        }

        // Debounced 500ms auto-save
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(500L)
            projectStore.save(newProject)
        }
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

    fun selectJunction(index: Int) {
        _uiState.update {
            it.copy(
                selectedJunctionIndex = index,
                selectedClipIndex = null,
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
        val deletedId = _uiState.value.project.clips.getOrNull(index)?.id
        if (deletedId != null) {
            clipTimeMaps.remove(deletedId)
            clipNaturalDurations.remove(deletedId)
            clipVolumes.remove(deletedId)
            savedPreMuteVolumes.remove(deletedId)
            ClipAudioContributor.clipVolumes.remove(deletedId)
            clipTransforms.remove(deletedId)
            PreviewRenderer.clipTransforms.remove(deletedId)
            clipColorAdjusts.remove(deletedId)
            PreviewRenderer.clipColorAdjusts.remove(deletedId)
            clipBackgrounds.remove(deletedId)
            PreviewRenderer.clipBackgrounds.remove(deletedId)
        }
        val newProject = ProgramTimeline.deleteClip(_uiState.value.project, index)
        val isAllMuted = newProject.clips.isNotEmpty() && newProject.clips.all { (clipVolumes[it.id] ?: 1.0f) <= 0.0001f }
        ClipAudioContributor.isMainTrackMuted = isAllMuted
        updateProject(newProject, recordUndo = true)
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
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun openExistingProject() {
        _uiState.update { it.copy(currentScreen = AppScreen.EDITOR) }
        previewEngine?.seekTo(_uiState.value.currentMs)
    }

    fun resetProject(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            projectStore.clearAll()
            undoStack.clear()
            undoTimeMaps.clear()
            redoTimeMaps.clear()
            clipTimeMaps.clear()
            clipNaturalDurations.clear()
            undoVolumes.clear()
            redoVolumes.clear()
            clipVolumes.clear()
            savedPreMuteVolumes.clear()
            ClipAudioContributor.clipVolumes.clear()
            ClipAudioContributor.isMainTrackMuted = false
            undoTransforms.clear()
            redoTransforms.clear()
            clipTransforms.clear()
            PreviewRenderer.clipTransforms.clear()
            undoColorAdjusts.clear()
            redoColorAdjusts.clear()
            clipColorAdjusts.clear()
            PreviewRenderer.clipColorAdjusts.clear()
            undoBackgrounds.clear()
            redoBackgrounds.clear()
            clipBackgrounds.clear()
            PreviewRenderer.clipBackgrounds.clear()
            undoLayerItems.clear()
            redoLayerItems.clear()
            _layerItems.clear()
            PreviewRenderer.clearOverlayTextures()
            syncLayerPlan()
            _uiState.update {
                it.copy(
                    project = Project(),
                    currentMs = 0L,
                    selectedClipIndex = null,
                    selectedJunctionIndex = null,
                    selectedOverlayId = null,
                    isMainTrackMuted = false,
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
        val urisToProcess = uris.take(MAX_CLIPS)

        viewModelScope.launch {
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
                for (c in imported) {
                    clipNaturalDurations[c.id] = msToUs(c.durationMs)
                }
                val newProject = ProgramTimeline.addClips(Project(), imported)
                undoStack.clear()
                updateProject(newProject, recordUndo = false)
                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.EDITOR,
                        currentMs = 0L,
                        selectedClipIndex = null,
                        selectedJunctionIndex = null,
                        selectedOverlayId = null
                    )
                }
                previewEngine?.seekTo(0L)
            }

            _uiState.update {
                it.copy(isImporting = false, importProgressText = "")
            }
        }
    }

    fun importClips(uris: List<Uri>) {
        val currentClipsCount = _uiState.value.project.clips.size
        if (currentClipsCount >= MAX_CLIPS) {
            showToast("Maximum clip limit reached ($MAX_CLIPS clips)")
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
                for (c in imported) {
                    clipNaturalDurations[c.id] = msToUs(c.durationMs)
                }
                val newProject = ProgramTimeline.addClips(_uiState.value.project, imported)
                updateProject(newProject, recordUndo = true)
            }

            _uiState.update {
                it.copy(isImporting = false, importProgressText = "")
            }
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
            undoStack.push(snapshot)
            preDragProjectSnapshot = null
            _uiState.update {
                it.copy(
                    canUndo = undoStack.canUndo,
                    canRedo = undoStack.canRedo
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

    fun undo() {
        val previous = undoStack.undo(_uiState.value.project) ?: return
        if (undoTimeMaps.isNotEmpty()) {
            val prevMaps = undoTimeMaps.removeLast()
            redoTimeMaps.addLast(HashMap(clipTimeMaps))
            clipTimeMaps.clear()
            clipTimeMaps.putAll(prevMaps)
        }
        if (undoVolumes.isNotEmpty()) {
            val prevVols = undoVolumes.removeLast()
            redoVolumes.addLast(HashMap(clipVolumes))
            clipVolumes.clear()
            clipVolumes.putAll(prevVols)
            ClipAudioContributor.syncVolumes(clipVolumes)
        }
        if (undoTransforms.isNotEmpty()) {
            val prevTransforms = undoTransforms.removeLast()
            redoTransforms.addLast(HashMap(clipTransforms))
            clipTransforms.clear()
            clipTransforms.putAll(prevTransforms)
            PreviewRenderer.syncTransforms(clipTransforms)
        }
        if (undoColorAdjusts.isNotEmpty()) {
            val prevAdjusts = undoColorAdjusts.removeLast()
            redoColorAdjusts.addLast(HashMap(clipColorAdjusts))
            clipColorAdjusts.clear()
            clipColorAdjusts.putAll(prevAdjusts)
            PreviewRenderer.syncColorAdjusts(clipColorAdjusts)
        }
        if (undoBackgrounds.isNotEmpty()) {
            val prevBgs = undoBackgrounds.removeLast()
            redoBackgrounds.addLast(HashMap(clipBackgrounds))
            clipBackgrounds.clear()
            clipBackgrounds.putAll(prevBgs)
            PreviewRenderer.syncBackgrounds(clipBackgrounds)
        }
        if (undoLayerItems.isNotEmpty()) {
            val prevLayers = undoLayerItems.removeLast()
            redoLayerItems.addLast(ArrayList(_layerItems))
            _layerItems.clear()
            _layerItems.addAll(prevLayers)
            syncLayerPlan()
        }
        val isAllMuted = previous.clips.isNotEmpty() && previous.clips.all { (clipVolumes[it.id] ?: 1.0f) <= 0.0001f }
        ClipAudioContributor.isMainTrackMuted = isAllMuted
        updateProject(previous, recordUndo = false)
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

    fun redo() {
        val next = undoStack.redo(_uiState.value.project) ?: return
        if (redoTimeMaps.isNotEmpty()) {
            val nextMaps = redoTimeMaps.removeLast()
            undoTimeMaps.addLast(HashMap(clipTimeMaps))
            clipTimeMaps.clear()
            clipTimeMaps.putAll(nextMaps)
        }
        if (redoVolumes.isNotEmpty()) {
            val nextVols = redoVolumes.removeLast()
            undoVolumes.addLast(HashMap(clipVolumes))
            clipVolumes.clear()
            clipVolumes.putAll(nextVols)
            ClipAudioContributor.syncVolumes(clipVolumes)
        }
        if (redoTransforms.isNotEmpty()) {
            val nextTransforms = redoTransforms.removeLast()
            undoTransforms.addLast(HashMap(clipTransforms))
            clipTransforms.clear()
            clipTransforms.putAll(nextTransforms)
            PreviewRenderer.syncTransforms(clipTransforms)
        }
        if (redoColorAdjusts.isNotEmpty()) {
            val nextAdjusts = redoColorAdjusts.removeLast()
            undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
            clipColorAdjusts.clear()
            clipColorAdjusts.putAll(nextAdjusts)
            PreviewRenderer.syncColorAdjusts(clipColorAdjusts)
        }
        if (redoBackgrounds.isNotEmpty()) {
            val nextBgs = redoBackgrounds.removeLast()
            undoBackgrounds.addLast(HashMap(clipBackgrounds))
            clipBackgrounds.clear()
            clipBackgrounds.putAll(nextBgs)
            PreviewRenderer.syncBackgrounds(clipBackgrounds)
        }
        if (redoLayerItems.isNotEmpty()) {
            val nextLayers = redoLayerItems.removeLast()
            undoLayerItems.addLast(ArrayList(_layerItems))
            _layerItems.clear()
            _layerItems.addAll(nextLayers)
            syncLayerPlan()
        }
        val isAllMuted = next.clips.isNotEmpty() && next.clips.all { (clipVolumes[it.id] ?: 1.0f) <= 0.0001f }
        ClipAudioContributor.isMainTrackMuted = isAllMuted
        updateProject(next, recordUndo = false)
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

    fun openExportSheet() {
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

    fun getSnapshot(): TimelineSnapshot {
        val project = _uiState.value.project
        val coreClips = project.clips.map { clip ->
            val timeMap = clipTimeMaps[clip.id]
            com.flowcut.app.core.model.Clip(
                id = clip.id,
                path = clip.path,
                durationMs = clip.durationMs,
                width = clip.width,
                height = clip.height,
                rotation = clip.rotation,
                frameRate = clip.frameRate,
                timeMap = timeMap
            )
        }
        val coreTransitions = project.transitions.map { trans ->
            com.flowcut.app.core.model.Transition(
                type = when (trans.type) {
                    TransitionType.NONE -> com.flowcut.app.core.model.TransitionType.NONE
                    TransitionType.SLIDE -> com.flowcut.app.core.model.TransitionType.SLIDE
                    TransitionType.CIRCLE -> com.flowcut.app.core.model.TransitionType.CIRCLE
                    TransitionType.ZOOM -> com.flowcut.app.core.model.TransitionType.ZOOM
                    TransitionType.FADE -> com.flowcut.app.core.model.TransitionType.FADE
                },
                durationMs = trans.durationMs
            )
        }
        return TimelineSnapshot(System.nanoTime(), com.flowcut.app.core.model.Project(coreClips, coreTransitions))
    }

    fun startExport() {
        exportJob?.cancel()
        _uiState.update { it.copy(exportState = ExportState.Exporting(0f)) }

        val snapshot = getSnapshot()
        exportJob = viewModelScope.launch {
            try {
                val uri = exportEngine.export(snapshot) { progress ->
                    _uiState.update { it.copy(exportState = ExportState.Exporting(progress)) }
                }
                _uiState.update {
                    it.copy(
                        exportState = ExportState.Done,
                        exportedUri = uri
                    )
                }
            } catch (e: Exception) {
                if (_uiState.value.exportState !is ExportState.Ready) {
                    _uiState.update { it.copy(exportState = ExportState.Failed) }
                }
            }
        }
    }

    fun cancelExport() {
        exportEngine.cancel()
        exportJob?.cancel()
        _uiState.update {
            it.copy(
                exportState = ExportState.Ready,
                exportSheetVisible = false
            )
        }
    }

    fun onAppBackground() {
        previewEngine?.onPause()
        if (_uiState.value.exportState is ExportState.Exporting) {
            cancelExport()
            showToast("Export stopped because the app went to background")
        }
    }

    fun onAppResume() {
        previewEngine?.onResume()
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        previewEngine?.release()
    }
}

