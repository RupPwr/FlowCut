package com.flowcut.app.ui

import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.flowcut.app.editor.EditorUiState
import com.flowcut.app.editor.EditorViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.roundToInt
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flowcut.app.ALL_FEATURES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.flowcut.app.audio.AudioEngine
import com.flowcut.app.audio.AudioFeature
import com.flowcut.app.audio.ClipAudioContributor
import com.flowcut.app.core.feature.DefaultFeatureRegistry
import com.flowcut.app.core.model.map
import com.flowcut.app.core.playback.PlayMode
import com.flowcut.app.core.playback.PlaybackController
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.ClipBackground
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.FrameDriver
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.engine.USE_NEW_CORE
import com.flowcut.app.model.AUTO_PLAY_MARGIN_MS
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.Project
import com.flowcut.app.model.Transition
import com.flowcut.app.model.TransitionType
import com.flowcut.app.ui.timeline.TimelineCanvas
import com.flowcut.app.ui.timeline.TimelineFocusState
import com.flowcut.app.ui.timeline.TimelineSelection
import com.flowcut.app.ui.timeline.TimelineZoomState

private fun hitTestPreviewOverlays(
    tapPos: Offset,
    outerW: Float,
    outerH: Float,
    overlayItems: List<com.flowcut.app.engine.OverlayItem>,
    playheadUs: Long,
    preferredTopOverlayId: String? = null
): com.flowcut.app.engine.OverlayItem? {
    val previewH = outerH
    val previewW = (outerH * (9f / 16f)).coerceAtMost(outerW).coerceAtLeast(1f)
    val previewLeft = (outerW - previewW) / 2f

    // Active visual overlays at current playhead sorted front-to-back
    val activeItems = overlayItems.filter {
        (it.type == com.flowcut.app.engine.LayerType.IMAGE ||
         it.type == com.flowcut.app.engine.LayerType.VIDEO ||
         it.type == com.flowcut.app.engine.LayerType.TEXT ||
         it.type == com.flowcut.app.engine.LayerType.STICKER) &&
        playheadUs in it.startUs..it.endUs
    }.sortedWith(
        compareBy<com.flowcut.app.engine.OverlayItem> { if (it.id == preferredTopOverlayId) 1 else 0 }
            .thenBy { it.zIndex }
            .thenBy { it.laneIndex }
    ).reversed()

    for (item in activeItems) {
        val displayText = if (item.text.isBlank()) "Your Text" else item.text
        val aTex: Float = when (item.type) {
            com.flowcut.app.engine.LayerType.STICKER -> 1.0f
            com.flowcut.app.engine.LayerType.TEXT -> {
                val lines = displayText.split("\n")
                val maxLen = lines.maxOfOrNull { it.length } ?: 6
                val lineCount = lines.size.coerceAtLeast(1)
                (maxLen.toFloat() * 0.45f / lineCount.toFloat()).coerceIn(0.8f, 4.0f)
            }
            com.flowcut.app.engine.LayerType.IMAGE, com.flowcut.app.engine.LayerType.VIDEO -> {
                val tw = if (item.intrinsicWidth > 0) item.intrinsicWidth.toFloat() else 1f
                val th = if (item.intrinsicHeight > 0) item.intrinsicHeight.toFloat() else 1f
                (tw / th).coerceIn(0.01f, 100f)
            }
            else -> 1.0f
        }
        val aScreen = 9.0f / 16.0f
        var hwInit = 0.6f
        var hhInit = hwInit / aTex
        if (hhInit * aScreen > 0.6f) {
            val factor = 0.6f / (hhInit * aScreen)
            hwInit *= factor
            hhInit *= factor
        }
        val rawQuadW = hwInit * item.transform.scale.coerceIn(0.01f, 10.0f) * previewW
        val rawQuadH = hhInit * item.transform.scale.coerceIn(0.01f, 10.0f) * previewW
        val isMedia = item.type == com.flowcut.app.engine.LayerType.IMAGE || item.type == com.flowcut.app.engine.LayerType.VIDEO
        val padPx = if (isMedia) 0f else 24f
        val boxW = if (isMedia) rawQuadW else (rawQuadW + 2f * padPx).coerceAtLeast(120f)
        val boxH = if (isMedia) rawQuadH else (rawQuadH + 2f * padPx).coerceAtLeast(80f)

        val cx_outer = previewLeft + previewW / 2f + item.transform.panX * previewW
        val cy_outer = previewH / 2f - item.transform.panY * previewH

        val dx = tapPos.x - cx_outer
        val dy = tapPos.y - cy_outer
        val rad = Math.toRadians(-item.transform.rotationDeg.toDouble())
        val localX = dx * kotlin.math.cos(rad) - dy * kotlin.math.sin(rad)
        val localY = dx * kotlin.math.sin(rad) + dy * kotlin.math.cos(rad)

        if (kotlin.math.abs(localX) <= boxW / 2f && kotlin.math.abs(localY) <= boxH / 2f) {
            return item
        }
    }
    return null
}

@Composable
fun EditorScreen(
    timeline: ProgramTimeline,
    currentMs: Long,
    isPlaying: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    selectedClipIndex: Int?,
    selectedJunctionIndex: Int?,
    glSurfaceView: View?,
    exportSheetVisible: Boolean,
    durationSheetVisible: Boolean,
    exportState: ExportState,
    onBackClick: () -> Unit,
    onExportButtonClick: () -> Unit,
    onAddClipsClick: () -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onSeekScrub: (Long) -> Unit,
    onSelectClip: (Int) -> Unit,
    onDeleteClip: (Int) -> Unit,
    onSelectJunction: (Int) -> Unit,
    onDeselectAll: () -> Unit,
    onApplyTransition: (TransitionType) -> Unit,
    onUpdateTransitionDuration: (Long) -> Unit,
    onCommitTransitionDuration: (Long) -> Unit,
    onRemoveTransition: () -> Unit,
    onCloseDurationSheet: () -> Unit,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onShareExport: () -> Unit,
    onCloseExportSheet: () -> Unit,
    modifier: Modifier = Modifier,
    onPlaybackStateChange: (Boolean) -> Unit = {},
    onPlayheadUpdate: (Long) -> Unit = {},
    onCommitTrim: (clipIndex: Int, newInUs: Long, newOutUs: Long, newDurationMs: Long) -> Unit = { _, _, _, _ -> },
    onSplitClick: (() -> Unit)? = null,
    onRotateClip: (() -> Unit)? = null,
    onFlipClip: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val colors = FlowCutTheme.colors
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val hasClips = timeline.n > 0
    val selectedTransitionName = selectedJunctionIndex?.let { j ->
        val t = timeline.project.transitions.getOrNull(j)?.type
        if (t != null && t != TransitionType.NONE) t.displayName else null
    }

    val resolvedViewModel = runCatching { androidx.lifecycle.viewmodel.compose.viewModel<com.flowcut.app.editor.EditorViewModel>() }.getOrNull()

    // New Core Foundation wiring (Section 8, 10, 15)
    val coreProject = remember(timeline.project) {
        val coreClips = timeline.project.clips.map {
            val existingTimeMap = resolvedViewModel?.clipTimeMaps?.get(it.id)
            com.flowcut.app.core.model.Clip(
                id = it.id,
                path = it.path,
                durationMs = it.durationMs,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                frameRate = it.frameRate,
                timeMap = existingTimeMap
            )
        }
        val coreTransitions = timeline.project.transitions.map {
            com.flowcut.app.core.model.Transition(
                type = when (it.type) {
                    TransitionType.NONE -> com.flowcut.app.core.model.TransitionType.NONE
                    TransitionType.SLIDE -> com.flowcut.app.core.model.TransitionType.SLIDE
                    TransitionType.CIRCLE -> com.flowcut.app.core.model.TransitionType.CIRCLE
                    TransitionType.ZOOM -> com.flowcut.app.core.model.TransitionType.ZOOM
                    TransitionType.FADE -> com.flowcut.app.core.model.TransitionType.FADE
                },
                durationMs = it.durationMs
            )
        }
        com.flowcut.app.core.model.Project(coreClips, coreTransitions)
    }

    val snapshotHolder = remember { SnapshotHolder(TimelineSnapshot(1L, coreProject)) }
    LaunchedEffect(coreProject) {
        snapshotHolder.current = TimelineSnapshot(System.nanoTime(), coreProject)
    }

    val audioContributor = remember(snapshotHolder) { ClipAudioContributor(snapshotHolder) }
    val featureRegistry = remember(audioContributor) {
        DefaultFeatureRegistry().apply {
            ALL_FEATURES.forEach { feature ->
                if (feature is AudioFeature) {
                    feature.contributor = audioContributor
                }
                feature.register(this)
            }
        }
    }
    val audioEngine = remember(featureRegistry) {
        AudioEngine(featureRegistry).apply {
            onPlaybackStopped = { audioContributor.onPlaybackStopped() }
        }
    }

    val playbackController = remember(audioEngine, snapshotHolder) {
        PlaybackController(clock = audioEngine.clock, holder = snapshotHolder)
    }
    val zoomState = remember { TimelineZoomState() }
    val frameTick = remember { mutableLongStateOf(0L) }
    val displayedMs = remember { mutableLongStateOf(currentMs) }

    val isPlayingState = rememberUpdatedState(isPlaying)
    val onPlaybackStateChangeState = rememberUpdatedState(onPlaybackStateChange)
    val onPlayheadUpdateState = rememberUpdatedState(onPlayheadUpdate)

    val stateDirtyCounter = remember { java.util.concurrent.atomic.AtomicLong(1L) }

    val previewRenderer = remember {
        PreviewRenderer(
            holder = snapshotHolder,
            playhead = playbackController.playhead,
            context = context,
            onRenderRequested = {
                stateDirtyCounter.incrementAndGet()
            }
        ).apply {
            (glSurfaceView as? android.view.SurfaceView)?.holder?.setFixedSize(720, 1280)
        }
    }

    SideEffect {
        (glSurfaceView as? android.view.SurfaceView)?.holder?.setFixedSize(720, 1280)
        (previewRenderer.glSurfaceView as? android.view.SurfaceView)?.holder?.setFixedSize(720, 1280)
    }

    val frameDriver = remember(playbackController, previewRenderer) {
        var lastReportedSec = -1L
        var lastReportedMode = PlayMode.PAUSED
        var lastRequestedTimeUs = -1L
        var lastRequestedMode = PlayMode.PAUSED
        var lastRenderDirtyVersion = -1L

        FrameDriver(
            controller = playbackController,
            frameTick = frameTick,
            onRequestRender = {
                val tUs = playbackController.playhead.timeUs
                val tMs = usToMs(tUs)
                val curSec = tMs / 1000L
                val mode = playbackController.playhead.mode

                // 1. Local fast state: updates on every frame for instant timecode display
                displayedMs.longValue = tMs

                // 2. ViewModel state: throttle to whole seconds, or emit when scrubbing/flinging completes.
                // Prevents full-screen Compose recomposition storms at 60-120fps during drag!
                val stoppedMoving = (lastReportedMode == PlayMode.SCRUBBING || lastReportedMode == PlayMode.FLINGING) && mode == PlayMode.PAUSED
                if (curSec != lastReportedSec || stoppedMoving) {
                    lastReportedSec = curSec
                    onPlayheadUpdateState.value.invoke(tMs)
                }
                lastReportedMode = mode

                if (isPlayingState.value && mode != PlayMode.PLAYING) {
                    onPlaybackStateChangeState.value.invoke(false)
                }

                // 3. Skip redundant renders when paused
                val currentDirtyVersion = stateDirtyCounter.get()
                val isDirty = currentDirtyVersion != lastRenderDirtyVersion
                val shouldRender = (mode != PlayMode.PAUSED) ||
                        (tUs != lastRequestedTimeUs) ||
                        (mode != lastRequestedMode) ||
                        isDirty

                if (shouldRender) {
                    lastRequestedTimeUs = tUs
                    lastRequestedMode = mode
                    lastRenderDirtyVersion = currentDirtyVersion
                    if (previewRenderer.glSurfaceView != null) {
                        previewRenderer.glSurfaceView.requestRender()
                    } else {
                        previewRenderer.requestRender()
                    }
                }
            }
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, playbackController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                if (playbackController.playhead.mode == PlayMode.PLAYING) {
                    playbackController.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(frameDriver, previewRenderer, audioEngine, audioContributor) {
        previewRenderer.startPrefetch(coroutineScope)
        onDispose {
            frameDriver.stop()
            previewRenderer.release()
            audioEngine.release()
            audioContributor.release()
        }
    }

    val replacePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && selectedClipIndex != null) {
            resolvedViewModel?.replaceSelectedClipUri(selectedClipIndex, uri) {
                if (USE_NEW_CORE) {
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                    }
                }
                previewRenderer.requestRender()
                frameDriver.wake()
            }
        }
    }

    val replaceFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && selectedClipIndex != null) {
            resolvedViewModel?.replaceSelectedClipUri(selectedClipIndex, uri) {
                if (USE_NEW_CORE) {
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                    }
                }
                previewRenderer.requestRender()
                frameDriver.wake()
            }
        }
    }

    val openReplacePicker: () -> Unit = {
        try {
            replacePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        } catch (_: Exception) {
            try {
                replaceFallbackLauncher.launch("video/*")
            } catch (_: Exception) {
                Toast.makeText(context, "Cannot open video picker", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val overlayPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val vm = resolvedViewModel ?: return@rememberLauncherForActivityResult
            val currentOverlayCount = vm.layerItems.count {
                it.type == com.flowcut.app.engine.LayerType.IMAGE || it.type == com.flowcut.app.engine.LayerType.VIDEO
            }
            if (currentOverlayCount >= 30) {
                Toast.makeText(context, "Maximum 30 photo/video overlays reached", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val isVideo = mimeType.startsWith("video/")
            val prefix = if (isVideo) "overlay_vid_" else "overlay_img_"
            val cachePath = copyUriToCacheFile(context, uri, prefix)
            if (cachePath != null) {
                val playheadUs = playbackController.playhead.timeUs
                if (isVideo) {
                    vm.addVideoOverlay(cachePath, playheadUs, 3_000_000L)
                } else {
                    vm.addImageOverlay(cachePath, playheadUs, 3_000_000L)
                }
                previewRenderer.requestRender()
                frameDriver.wake()
            }
        }
    }

    val overlayFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val vm = resolvedViewModel ?: return@rememberLauncherForActivityResult
            val currentOverlayCount = vm.layerItems.count {
                it.type == com.flowcut.app.engine.LayerType.IMAGE || it.type == com.flowcut.app.engine.LayerType.VIDEO
            }
            if (currentOverlayCount >= 30) {
                Toast.makeText(context, "Maximum 30 photo/video overlays reached", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val isVideo = mimeType.startsWith("video/")
            val prefix = if (isVideo) "overlay_vid_" else "overlay_img_"
            val cachePath = copyUriToCacheFile(context, uri, prefix)
            if (cachePath != null) {
                val playheadUs = playbackController.playhead.timeUs
                if (isVideo) {
                    vm.addVideoOverlay(cachePath, playheadUs, 3_000_000L)
                } else {
                    vm.addImageOverlay(cachePath, playheadUs, 3_000_000L)
                }
                previewRenderer.requestRender()
                frameDriver.wake()
            }
        }
    }

    val openOverlayPicker: () -> Unit = {
        val vm = resolvedViewModel
        val currentOverlayCount = vm?.layerItems?.count {
            it.type == com.flowcut.app.engine.LayerType.IMAGE || it.type == com.flowcut.app.engine.LayerType.VIDEO
        } ?: 0
        if (currentOverlayCount >= 30) {
            Toast.makeText(context, "Maximum 30 photo/video overlays reached", Toast.LENGTH_SHORT).show()
        } else {
            try {
                overlayPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            } catch (_: Exception) {
                try {
                    overlayFallbackLauncher.launch("*/*")
                } catch (_: Exception) {
                    Toast.makeText(context, "Cannot open media picker", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val stickerImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val vm = resolvedViewModel ?: return@rememberLauncherForActivityResult
            if (!com.flowcut.app.engine.canAddLayer(vm.layerItems, com.flowcut.app.engine.LayerType.STICKER)) {
                Toast.makeText(context, "Maximum 20 sticker overlays reached", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val cachePath = copyUriToCacheFile(context, uri, "sticker_img_")
            if (cachePath != null) {
                val playheadUs = playbackController.playhead.timeUs
                vm.addImageStickerOverlay(cachePath, playheadUs, 3_000_000L)
                previewRenderer.requestRender()
                frameDriver.wake()
            }
        }
    }

    val openImagePicker: () -> Unit = openOverlayPicker

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            if (playbackController.playhead.mode != PlayMode.PLAYING) {
                playbackController.play()
                frameDriver.wake()
            }
        } else {
            if (playbackController.playhead.mode == PlayMode.PLAYING) {
                playbackController.pause()
                frameDriver.wake()
            }
        }
    }

    val handleSplit: () -> Unit = {
        if (playbackController.playhead.mode == PlayMode.PLAYING) {
            playbackController.pause()
            frameDriver.wake()
            onPlaybackStateChange(false)
        }
        val currentPlayheadUs = playbackController.playhead.timeUs
        val result = resolvedViewModel?.splitAtPlayhead(
            playheadUs = currentPlayheadUs,
            preferredClipIndex = selectedClipIndex
        )
        if (result != null) {
            snapshotHolder.current = result.newSnapshot
            playbackController.seekTo(currentPlayheadUs)
            frameDriver.wake()
        }
    }

    var isTrimSheetVisible by remember { mutableStateOf(false) }
    var isVolumeSheetVisible by remember { mutableStateOf(false) }
    var isSpeedSheetVisible by remember { mutableStateOf(false) }
    var isAdjustSheetVisible by remember { mutableStateOf(false) }
    var isBackgroundSheetVisible by remember { mutableStateOf(false) }
    var isOpacitySheetVisible by remember { mutableStateOf(false) }
    var isTextSheetVisible by remember { mutableStateOf(false) }
    var isTextToolbarActive by remember { mutableStateOf(false) }
    var trimTargetClipIndex by remember { mutableIntStateOf(-1) }
    var editingTextOverlayId by remember { mutableStateOf<String?>(null) }
    var isNewTextOverlay by remember { mutableStateOf(false) }
    var isStickerSheetVisible by remember { mutableStateOf(false) }
    var isTextColorSheetVisible by remember { mutableStateOf(false) }
    var isTextFontSheetVisible by remember { mutableStateOf(false) }
    var isTextAnimationSheetVisible by remember { mutableStateOf(false) }
    var isTextAdjustSheetVisible by remember { mutableStateOf(false) }
    var activeTextOverlayId by remember { mutableStateOf<String?>(null) }
    var overlappingConflictItemIds by remember { mutableStateOf<List<String>?>(null) }
    var customFontPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var isAudioSoundsSheetVisible by remember { mutableStateOf(false) }
    var isAudioEffectsSheetVisible by remember { mutableStateOf(false) }
    var isAudioRecordSheetVisible by remember { mutableStateOf(false) }
    var isAudioFadeSheetVisible by remember { mutableStateOf(false) }
    var isAudioVolumeSheetVisible by remember { mutableStateOf(false) }
    var isAudioSpeedSheetVisible by remember { mutableStateOf(false) }
    var isFilterSheetVisible by remember { mutableStateOf(false) }
    var isEffectSheetVisible by remember { mutableStateOf(false) }
    var filterTargetOverlayId by remember { mutableStateOf<String?>(null) }
    var effectTargetOverlayId by remember { mutableStateOf<String?>(null) }
    var filterInitialPresetId by remember { mutableIntStateOf(1) }
    var filterInitialIntensity by remember { mutableFloatStateOf(0.8f) }
    var effectInitialPresetId by remember { mutableIntStateOf(1) }
    var effectInitialIntensity by remember { mutableFloatStateOf(0.7f) }
    var filterIsNewItem by remember { mutableStateOf(false) }
    var effectIsNewItem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val fontsDir = File(context.filesDir, "fonts")
        if (fontsDir.exists() && fontsDir.isDirectory) {
            val files = fontsDir.listFiles { f -> f.extension.equals("ttf", true) || f.extension.equals("otf", true) }
            if (files != null) {
                customFontPaths = files.map { it.absolutePath }
            }
        }
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val extension = uri.path?.substringAfterLast('.', "")?.lowercase() ?: ""
            val ext = if (extension == "otf") ".otf" else ".ttf"
            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            val fontFile = File(fontsDir, "font_${System.currentTimeMillis()}$ext")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    fontFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (fontFile.exists() && fontFile.length() > 0) {
                    customFontPaths = customFontPaths + fontFile.absolutePath
                    val targetId = activeTextOverlayId
                    if (targetId != null) {
                        resolvedViewModel?.updateTextFont(targetId, "Custom", fontFile.absolutePath)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    }
                    Toast.makeText(context, "Imported font: ${fontFile.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to load font: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val localAudioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val vm = resolvedViewModel ?: return@rememberLauncherForActivityResult
            val cachePath = copyUriToCacheFile(context, uri, "audio_local_")
            if (cachePath != null) {
                val retriever = android.media.MediaMetadataRetriever()
                var durMs = 5000L
                try {
                    retriever.setDataSource(cachePath)
                    durMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L
                } catch (_: Exception) {
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
                val playheadUs = playbackController.playhead.timeUs
                val totalUs = msToUs(vm.uiState.value.project.clips.sumOf { it.durationMs })
                val remainingUs = (totalUs - playheadUs).coerceAtLeast(1_000_000L)
                val finalDurUs = minOf(durMs * 1000L, remainingUs).coerceAtLeast(com.flowcut.app.engine.OverlayItem.MIN_DURATION_US)
                val rawName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Local Audio"
                vm.addAudioOverlay(
                    mediaPath = cachePath,
                    title = rawName,
                    startUs = playheadUs,
                    durationUs = finalDurUs,
                    audioSubType = 0
                )
                vm.setFocusMode(TimelineFocusState.S5_AUDIO_GROUP)
                previewRenderer.requestRender()
                frameDriver.wake()
            }
        }
    }

    val openLocalAudioPicker: () -> Unit = {
        try {
            localAudioPickerLauncher.launch("audio/*")
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot open audio picker", Toast.LENGTH_SHORT).show()
        }
    }

    val extractVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val vm = resolvedViewModel ?: return@rememberLauncherForActivityResult
            val cachePath = copyUriToCacheFile(context, uri, "audio_extract_")
            if (cachePath != null) {
                val retriever = android.media.MediaMetadataRetriever()
                var durMs = 5000L
                try {
                    retriever.setDataSource(cachePath)
                    durMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L
                } catch (_: Exception) {
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
                val playheadUs = playbackController.playhead.timeUs
                val totalUs = msToUs(vm.uiState.value.project.clips.sumOf { it.durationMs })
                val remainingUs = (totalUs - playheadUs).coerceAtLeast(1_000_000L)
                val finalDurUs = minOf(durMs * 1000L, remainingUs).coerceAtLeast(com.flowcut.app.engine.OverlayItem.MIN_DURATION_US)
                vm.addAudioOverlay(
                    mediaPath = cachePath,
                    title = "Extracted Audio",
                    startUs = playheadUs,
                    durationUs = finalDurUs,
                    audioSubType = 0
                )
                vm.setFocusMode(TimelineFocusState.S5_AUDIO_GROUP)
                previewRenderer.requestRender()
                frameDriver.wake()
            }
        }
    }

    val openExtractVideoPicker: () -> Unit = {
        try {
            extractVideoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot open video picker", Toast.LENGTH_SHORT).show()
        }
    }

    val density = LocalDensity.current.density
    var showCoverDialog by remember { mutableStateOf(false) }
    var customCoverBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var playPauseBoundsInRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    val galleryCoverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                val cachePath = copyUriToCacheFile(context, uri, "cover_img_")
                if (cachePath != null) {
                    resolvedViewModel?.setCoverFromGalleryImage(cachePath)
                    resolvedViewModel?.showToast("Cover updated")
                } else {
                    resolvedViewModel?.setCoverFromGalleryImage(uri.toString())
                }
                showCoverDialog = false
            }
        }
    )

    val resolveTextOverlayOrToast: () -> com.flowcut.app.engine.OverlayItem? = {
        val playheadUs = playbackController.playhead.timeUs
        val item = resolvedViewModel?.getOrResolveTextOverlayAt(playheadUs)
        if (item == null) {
            Toast.makeText(context, "No text layer at playhead", Toast.LENGTH_SHORT).show()
        }
        item
    }

    val handleOpenText: () -> Unit = {
        val vm = resolvedViewModel
        if (vm != null) {
            if (!com.flowcut.app.engine.canAddLayer(vm.layerItems, com.flowcut.app.engine.LayerType.TEXT)) {
                Toast.makeText(context, "Maximum 20 text overlays reached", Toast.LENGTH_SHORT).show()
            } else {
                if (playbackController.playhead.mode == PlayMode.PLAYING) {
                    playbackController.pause()
                    frameDriver.wake()
                    onPlaybackStateChange(false)
                }
                val playheadUs = playbackController.playhead.timeUs
                val newItem = vm.addTextOverlay("", 0xFFFFFFFF.toInt(), 0, playheadUs, 3_000_000L)
                if (newItem != null) {
                    isNewTextOverlay = true
                    editingTextOverlayId = newItem.id
                    isVolumeSheetVisible = false
                    isSpeedSheetVisible = false
                    isAdjustSheetVisible = false
                    isBackgroundSheetVisible = false
                    isOpacitySheetVisible = false
                    isTrimSheetVisible = false
                    isStickerSheetVisible = false
                    isTextSheetVisible = true
                    isTextToolbarActive = true
                    previewRenderer.requestRender()
                    frameDriver.wake()
                }
            }
        }
    }

    val handleOpenSticker: () -> Unit = {
        val vm = resolvedViewModel
        if (vm != null) {
            if (!com.flowcut.app.engine.canAddLayer(vm.layerItems, com.flowcut.app.engine.LayerType.STICKER)) {
                Toast.makeText(context, "Maximum 20 sticker overlays reached", Toast.LENGTH_SHORT).show()
            } else {
                if (playbackController.playhead.mode == PlayMode.PLAYING) {
                    playbackController.pause()
                    frameDriver.wake()
                    onPlaybackStateChange(false)
                }
                isVolumeSheetVisible = false
                isSpeedSheetVisible = false
                isAdjustSheetVisible = false
                isBackgroundSheetVisible = false
                isOpacitySheetVisible = false
                isTrimSheetVisible = false
                isTextSheetVisible = false
                isStickerSheetVisible = true
            }
        }
    }

    val handleOpenTrim: () -> Unit = {
        if (hasClips) {
            if (playbackController.playhead.mode == PlayMode.PLAYING) {
                playbackController.pause()
                frameDriver.wake()
                onPlaybackStateChange(false)
            }
            trimTargetClipIndex = selectedClipIndex ?: -1
            isVolumeSheetVisible = false
            isSpeedSheetVisible = false
            isAdjustSheetVisible = false
            isBackgroundSheetVisible = false
            isOpacitySheetVisible = false
            isTrimSheetVisible = true
        } else {
            Toast.makeText(context, "No clips in project", Toast.LENGTH_SHORT).show()
        }
    }

    val handleOpenSpeed: () -> Unit = {
        var targetIdx = selectedClipIndex
        if (targetIdx == null) {
            val playheadMs = usToMs(playbackController.playhead.timeUs)
            val main = snapshotHolder.current.main
            for (i in 0 until main.n) {
                if (playheadMs >= main.startMs[i] && playheadMs < main.endMs[i]) {
                    targetIdx = i
                    onSelectClip(i)
                    break
                }
            }
        }
        if (targetIdx != null && targetIdx in 0 until snapshotHolder.current.main.n) {
            val targetClip = snapshotHolder.current.project.clips.getOrNull(targetIdx)
            if (targetClip != null && targetClip.map().speedNum == 0) {
                Toast.makeText(context, "Speed cannot be changed on a Freeze frame", Toast.LENGTH_SHORT).show()
            } else {
                if (playbackController.playhead.mode == PlayMode.PLAYING) {
                    playbackController.pause()
                    frameDriver.wake()
                    onPlaybackStateChange(false)
                }
                isTrimSheetVisible = false
                isVolumeSheetVisible = false
                isAdjustSheetVisible = false
                isBackgroundSheetVisible = false
                isOpacitySheetVisible = false
                isSpeedSheetVisible = true
            }
        } else {
            Toast.makeText(context, "No clip selected or under playhead", Toast.LENGTH_SHORT).show()
        }
    }

    val vmUiState = resolvedViewModel?.uiState?.collectAsState()?.value
    val isMainTrackMuted = vmUiState?.isMainTrackMuted ?: false
    val currentFocus = vmUiState?.focusMode ?: com.flowcut.app.ui.timeline.TimelineFocusState.S0_NONE

    LaunchedEffect(currentFocus) {
        val selId = when (currentFocus) {
            is com.flowcut.app.ui.timeline.TimelineFocusState.S2_PIP_ITEM -> currentFocus.itemId
            is com.flowcut.app.ui.timeline.TimelineFocusState.S3_STICKER_ITEM -> currentFocus.itemId
            is com.flowcut.app.ui.timeline.TimelineFocusState.S4_TEXT_ITEM -> currentFocus.itemId
            is com.flowcut.app.ui.timeline.TimelineFocusState.S7_FILTER_EFFECT_ITEM -> currentFocus.itemId
            else -> null
        }
        if (previewRenderer.selectedOverlayIdForPreview != selId) {
            previewRenderer.selectedOverlayIdForPreview = selId
            previewRenderer.requestRender()
        }
    }

    val coverPath = vmUiState?.customCoverPath
    val coverTimeUs = vmUiState?.coverTimeUs ?: 0L
    val clips: List<com.flowcut.app.model.Clip> = vmUiState?.project?.clips ?: emptyList()

    LaunchedEffect(coverPath, coverTimeUs, clips) {
        withContext(Dispatchers.IO) {
            try {
                if (!coverPath.isNullOrEmpty()) {
                    val file = java.io.File(coverPath)
                    if (file.exists()) {
                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(coverPath, opts)
                        var sample = 1
                        while (opts.outWidth / (sample * 2) >= 80 && opts.outHeight / (sample * 2) >= 80) {
                            sample *= 2
                        }
                        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        val bmp = android.graphics.BitmapFactory.decodeFile(coverPath, decodeOpts)
                        if (bmp != null) {
                            customCoverBitmap = bmp.asImageBitmap()
                        }
                    }
                } else if (clips.isNotEmpty()) {
                    val snapshot = snapshotHolder.current
                    val n = snapshot.main.n
                    var targetClip = clips.first()
                    var clipStartUs = 0L
                    var found = false
                    for (k in 0 until n) {
                        val kStartUs = msToUs(snapshot.main.startMs[k])
                        val kEndUs = msToUs(snapshot.main.endMs[k])
                        if (coverTimeUs in kStartUs..kEndUs) {
                            targetClip = clips.getOrNull(k) ?: clips.first()
                            clipStartUs = kStartUs
                            found = true
                            break
                        }
                    }
                    if (!found && clips.isNotEmpty()) {
                        targetClip = clips.first()
                        clipStartUs = 0L
                    }

                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        val file = java.io.File(targetClip.path)
                        if (file.exists()) {
                            retriever.setDataSource(targetClip.path)
                            val tm = resolvedViewModel?.clipTimeMaps?.get(targetClip.id)
                            val inUs = tm?.inUs ?: 0L
                            val speedFactor = if (tm != null && tm.speedDen > 0) tm.speedNum.toDouble() / tm.speedDen.toDouble() else 1.0
                            val frameOffsetUs = (coverTimeUs - clipStartUs).coerceAtLeast(0L)
                            val sourceTimeUs = inUs + (frameOffsetUs * speedFactor).toLong()
                            val raw = retriever.getFrameAtTime(sourceTimeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (raw != null) {
                                val scaled = android.graphics.Bitmap.createScaledBitmap(raw, 76, 84, true)
                                if (scaled != raw) raw.recycle()
                                customCoverBitmap = scaled.asImageBitmap()
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                } else {
                    customCoverBitmap = null
                }
            } catch (_: Exception) {
                customCoverBitmap = null
            }
        }
    }

    var showApplyToAll by remember { mutableStateOf(false) }
    var applyToAllJob by remember { mutableStateOf<Job?>(null) }

    var isTransforming by remember { mutableStateOf(false) }
    var guideSnapCenterX by remember { mutableStateOf(false) }
    var guideSnapCenterY by remember { mutableStateOf(false) }
    var guideSnapEdgeLeft by remember { mutableStateOf(false) }
    var guideSnapEdgeRight by remember { mutableStateOf(false) }
    var guideSnapEdgeTop by remember { mutableStateOf(false) }
    var guideSnapEdgeBottom by remember { mutableStateOf(false) }
    var guideBadgeText by remember { mutableStateOf<String?>(null) }

    val triggerApplyToAllPill: () -> Unit = {
        applyToAllJob?.cancel()
        showApplyToAll = true
        applyToAllJob = coroutineScope.launch {
            delay(3000L)
            showApplyToAll = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface0)
            .pointerInput(playbackController) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        if (!playPauseBoundsInRoot.contains(down.position)) {
                            playbackController.pause()
                            frameDriver.wake()
                            onPlaybackStateChange(false)
                        }
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            // Main content with 12 dp horizontal padding
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                // 1. Top bar: 48 dp (Left: [←  My reel 01], Right: [9:16] [Export])
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary
                        )
                    }
                    Text(
                        text = "My reel 01",
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Box(
                        modifier = Modifier
                            .background(Color(0xFF24242A), RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "9:16",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (!hasClips) {
                                Toast.makeText(context, "Add clips first", Toast.LENGTH_SHORT).show()
                            } else {
                                onExportButtonClick()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.onAccent
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = "Export",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Preview box: weight 1f, minimum 220 dp
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 220.dp)
                        .fillMaxWidth()
                ) {
                    PreviewBox(
                        hasClips = hasClips,
                        selectedTransitionName = selectedTransitionName,
                        onAddVideoClick = onAddClipsClick,
                        glSurfaceView = if (USE_NEW_CORE && previewRenderer.glSurfaceView != null) previewRenderer.glSurfaceView else glSurfaceView,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Cover up floating 9:16 badge in PreviewBox
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(width = 54.dp, height = 32.dp)
                            .background(colors.surface1)
                    )

                    val activeOverlayId = vmUiState?.selectedOverlayId
                    val activeOverlay = if (activeOverlayId != null) resolvedViewModel?.getOverlayItem(activeOverlayId) else null
                    val animEvaluator = remember { com.flowcut.app.engine.AnimatedTransformEvaluator() }
                    val isSelectedOverlayActiveAtPlayhead = run {
                        val tick = frameTick.longValue
                        val playheadUs = playbackController.playhead.timeUs
                        activeOverlay != null && playheadUs >= activeOverlay.startUs && playheadUs <= activeOverlay.endUs
                    }

                    // On-Preview Tap Detector to select any PIP/Text/Sticker quad when nothing selected or selected overlay is outside its active time range
                    if (hasClips && selectedClipIndex == null && (vmUiState?.selectedOverlayId == null || !isSelectedOverlayActiveAtPlayhead)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(resolvedViewModel?.layerItems) {
                                    detectTapGestures { tapOffset ->
                                        val playheadUs = playbackController.playhead.timeUs
                                        val hit = hitTestPreviewOverlays(
                                            tapPos = tapOffset,
                                            outerW = size.width.toFloat(),
                                            outerH = size.height.toFloat(),
                                            overlayItems = resolvedViewModel?.layerItems ?: emptyList(),
                                            playheadUs = playheadUs
                                        )
                                        if (hit != null) {
                                            val newFocus = when (hit.type) {
                                                com.flowcut.app.engine.LayerType.IMAGE, com.flowcut.app.engine.LayerType.VIDEO ->
                                                    TimelineFocusState.S2_PIP_ITEM(hit.id)
                                                com.flowcut.app.engine.LayerType.STICKER ->
                                                    TimelineFocusState.S3_STICKER_ITEM(hit.id)
                                                com.flowcut.app.engine.LayerType.TEXT ->
                                                    TimelineFocusState.S4_TEXT_ITEM(hit.id)
                                                com.flowcut.app.engine.LayerType.FILTER, com.flowcut.app.engine.LayerType.EFFECT ->
                                                    TimelineFocusState.S7_FILTER_EFFECT_ITEM(hit.id)
                                                else -> TimelineFocusState.S0_NONE
                                            }
                                            resolvedViewModel?.selectOverlay(hit.id)
                                            resolvedViewModel?.setFocusMode(newFocus)
                                            previewRenderer.selectedOverlayIdForPreview = hit.id
                                            previewRenderer.requestRender()
                                            frameDriver.wake()
                                        }
                                    }
                                }
                        )
                    }

                    // On-Preview Free Transform Gestures overlay when a clip is selected
                    if (hasClips && selectedClipIndex != null) {
                        val activeClipId = resolvedViewModel?.uiState?.value?.project?.clips?.getOrNull(selectedClipIndex)?.id
                        val activeClip = resolvedViewModel?.uiState?.value?.project?.clips?.getOrNull(selectedClipIndex)
                        if (activeClipId != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(selectedClipIndex, activeClipId) {
                                        var lastTapTimeMs = 0L
                                        var lastTapPosition = Offset.Zero

                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val downTime = System.currentTimeMillis()
                                            val downPos = down.position
                                            var isDragOrTransform = false

                                            val initialTransform = resolvedViewModel?.getClipTransform(activeClipId)
                                                ?: PreviewRenderer.getClipTransform(activeClipId)

                                            var rawPanX = initialTransform.panX
                                            var rawPanY = initialTransform.panY
                                            var rawScale = initialTransform.scale
                                            var rawRotationDeg = initialTransform.rotationDeg
                                            val isFlippedH = initialTransform.isFlippedH

                                            var currentTransform = initialTransform

                                            var totalMovedPx = 0f

                                            var prevSnapCenterX = false
                                            var prevSnapCenterY = false
                                            var prevSnapEdgeLeft = false
                                            var prevSnapEdgeRight = false
                                            var prevSnapEdgeTop = false
                                            var prevSnapEdgeBottom = false
                                            var prevSnapScale100 = false
                                            var prevSnapRotationCardinal = false

                                            do {
                                                val event = awaitPointerEvent()
                                                val pressedList = event.changes.filter { it.pressed }
                                                if (pressedList.isEmpty()) break

                                                val outerW = size.width.toFloat().coerceAtLeast(1f)
                                                val outerH = size.height.toFloat().coerceAtLeast(1f)
                                                val previewH = outerH
                                                val previewW = (outerH * (9f / 16f)).coerceAtMost(outerW).coerceAtLeast(1f)

                                                if (pressedList.size == 1) {
                                                    val p = pressedList[0]
                                                    val dx = p.position.x - p.previousPosition.x
                                                    val dy = p.position.y - p.previousPosition.y
                                                    val moveDist = kotlin.math.sqrt(dx * dx + dy * dy)
                                                    totalMovedPx += moveDist

                                                    if (totalMovedPx > 10f) {
                                                        isDragOrTransform = true
                                                        p.consume()

                                                        rawPanX = (rawPanX + dx / previewW).coerceIn(-1.5f, 1.5f)
                                                        rawPanY = (rawPanY - dy / previewH).coerceIn(-1.5f, 1.5f)
                                                    }
                                                } else if (pressedList.size >= 2) {
                                                    isDragOrTransform = true
                                                    val p1 = pressedList[0]
                                                    val p2 = pressedList[1]
                                                    p1.consume()
                                                    p2.consume()

                                                    val prevPos1 = p1.previousPosition
                                                    val prevPos2 = p2.previousPosition
                                                    val currPos1 = p1.position
                                                    val currPos2 = p2.position

                                                    // 1. Pan (centroid delta)
                                                    val prevCenter = (prevPos1 + prevPos2) / 2f
                                                    val currCenter = (currPos1 + currPos2) / 2f
                                                    val dPan = currCenter - prevCenter
                                                    rawPanX = (rawPanX + dPan.x / previewW).coerceIn(-1.5f, 1.5f)
                                                    rawPanY = (rawPanY - dPan.y / previewH).coerceIn(-1.5f, 1.5f)

                                                    // 2. Zoom (distance ratio)
                                                    val prevDist = (prevPos1 - prevPos2).getDistance()
                                                    val currDist = (currPos1 - currPos2).getDistance()
                                                    val zoomRatio = if (prevDist > 1f) currDist / prevDist else 1f
                                                    rawScale = (rawScale * zoomRatio).coerceIn(0.25f, 5.0f)

                                                    // 3. Twist (rotation delta)
                                                    val prevAngle = Math.toDegrees(kotlin.math.atan2((prevPos2.y - prevPos1.y).toDouble(), (prevPos2.x - prevPos1.x).toDouble())).toFloat()
                                                    val currAngle = Math.toDegrees(kotlin.math.atan2((currPos2.y - currPos1.y).toDouble(), (currPos2.x - currPos1.x).toDouble())).toFloat()
                                                    var angleDelta = currAngle - prevAngle
                                                    while (angleDelta > 180f) angleDelta -= 360f
                                                    while (angleDelta < -180f) angleDelta += 360f
                                                    rawRotationDeg += angleDelta
                                                }

                                                if (isDragOrTransform) {
                                                    // --- Compute Snapping from Raw Accumulators ---
                                                    // 1. Rotation Snap
                                                    val normRot = ((rawRotationDeg % 360f) + 360f) % 360f
                                                    val nearestCardinal = (Math.round(normRot / 90f) * 90f).toFloat()
                                                    val diffRot = kotlin.math.abs(normRot - nearestCardinal)
                                                    val snapRotationCardinal = diffRot <= 3.0f
                                                    val snappedRotationDeg = if (snapRotationCardinal) (nearestCardinal % 360f) else rawRotationDeg

                                                    // 2. Scale Snap
                                                    val diffScale = kotlin.math.abs(rawScale - 1.0f)
                                                    val snapScale100 = diffScale < 0.04f
                                                    val snappedScale = if (snapScale100) 1.0f else rawScale

                                                    // 3. Effective Dimensions for Edge Snapping
                                                    val aScreen = 9.0f / 16.0f
                                                    val dispW = activeClip?.displayWidth ?: 1080
                                                    val dispH = activeClip?.displayHeight ?: 1920
                                                    val aClip = (dispW.toFloat() / dispH.toFloat()).coerceAtLeast(0.01f)

                                                    val cardinalAngle = ((Math.round(snappedRotationDeg / 90f) * 90) % 360 + 360) % 360
                                                    val isRot90or270 = (cardinalAngle == 90 || cardinalAngle == 270) && kotlin.math.abs(snappedRotationDeg - cardinalAngle) < 15f
                                                    val effAspect = if (isRot90or270) (1.0f / aClip) else aClip

                                                    val baseHw = if (effAspect >= aScreen) 0.5f * (effAspect / aScreen) else 0.5f
                                                    val baseHh = if (effAspect >= aScreen) 0.5f else 0.5f * (aScreen / effAspect)
                                                    val hw = baseHw * snappedScale
                                                    val hh = baseHh * snappedScale

                                                    // Target pan offsets for edge flush alignment
                                                    val targetPanXLeft = hw - 0.5f
                                                    val targetPanXRight = 0.5f - hw
                                                    val targetPanYBottom = hh - 0.5f
                                                    val targetPanYTop = 0.5f - hh

                                                    // 4. Center & Edge Snap for panX
                                                    var snappedPanX = rawPanX
                                                    var snapCenterX = false
                                                    var snapEdgeLeft = false
                                                    var snapEdgeRight = false

                                                    if (kotlin.math.abs(rawPanX) < 0.035f) {
                                                        snappedPanX = 0f
                                                        snapCenterX = true
                                                    } else if (kotlin.math.abs(rawPanX - targetPanXLeft) < 0.035f) {
                                                        snappedPanX = targetPanXLeft
                                                        snapEdgeLeft = true
                                                    } else if (kotlin.math.abs(rawPanX - targetPanXRight) < 0.035f) {
                                                        snappedPanX = targetPanXRight
                                                        snapEdgeRight = true
                                                    }

                                                    // 5. Center & Edge Snap for panY
                                                    var snappedPanY = rawPanY
                                                    var snapCenterY = false
                                                    var snapEdgeBottom = false
                                                    var snapEdgeTop = false

                                                    if (kotlin.math.abs(rawPanY) < 0.035f) {
                                                        snappedPanY = 0f
                                                        snapCenterY = true
                                                    } else if (kotlin.math.abs(rawPanY - targetPanYBottom) < 0.035f) {
                                                        snappedPanY = targetPanYBottom
                                                        snapEdgeBottom = true
                                                    } else if (kotlin.math.abs(rawPanY - targetPanYTop) < 0.035f) {
                                                        snappedPanY = targetPanYTop
                                                        snapEdgeTop = true
                                                    }

                                                    // --- Haptic Feedback Tick on State Transition ---
                                                    val newlySnapped = (!prevSnapCenterX && snapCenterX) ||
                                                            (!prevSnapCenterY && snapCenterY) ||
                                                            (!prevSnapEdgeLeft && snapEdgeLeft) ||
                                                            (!prevSnapEdgeRight && snapEdgeRight) ||
                                                            (!prevSnapEdgeTop && snapEdgeTop) ||
                                                            (!prevSnapEdgeBottom && snapEdgeBottom) ||
                                                            (!prevSnapScale100 && snapScale100) ||
                                                            (!prevSnapRotationCardinal && snapRotationCardinal)

                                                    if (newlySnapped) {
                                                        try {
                                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        } catch (_: Throwable) {}
                                                    }

                                                    prevSnapCenterX = snapCenterX
                                                    prevSnapCenterY = snapCenterY
                                                    prevSnapEdgeLeft = snapEdgeLeft
                                                    prevSnapEdgeRight = snapEdgeRight
                                                    prevSnapEdgeTop = snapEdgeTop
                                                    prevSnapEdgeBottom = snapEdgeBottom
                                                    prevSnapScale100 = snapScale100
                                                    prevSnapRotationCardinal = snapRotationCardinal

                                                    // --- Update Compose Guide Overlay State ---
                                                    isTransforming = true
                                                    guideSnapCenterX = snapCenterX
                                                    guideSnapCenterY = snapCenterY
                                                    guideSnapEdgeLeft = snapEdgeLeft
                                                    guideSnapEdgeRight = snapEdgeRight
                                                    guideSnapEdgeTop = snapEdgeTop
                                                    guideSnapEdgeBottom = snapEdgeBottom

                                                    // Badge text: show rotation or scale
                                                    guideBadgeText = when {
                                                        snapRotationCardinal -> "${snappedRotationDeg.toInt()}°"
                                                        snapScale100 -> "100%"
                                                        pressedList.size >= 2 && kotlin.math.abs(rawRotationDeg - initialTransform.rotationDeg) > 1.5f ->
                                                            "${Math.round(snappedRotationDeg)}°"
                                                        pressedList.size >= 2 && kotlin.math.abs(rawScale - initialTransform.scale) > 0.03f ->
                                                            "${Math.round(snappedScale * 100f)}%"
                                                        else -> null
                                                    }

                                                    currentTransform = currentTransform.copy(
                                                        scale = snappedScale,
                                                        panX = snappedPanX,
                                                        panY = snappedPanY,
                                                        rotationDeg = snappedRotationDeg,
                                                        isFlippedH = isFlippedH
                                                    )
                                                    PreviewRenderer.setClipTransform(activeClipId, currentTransform)
                                                    previewRenderer.requestRender()
                                                    frameDriver.wake()
                                                }
                                            } while (true)

                                            // Clear visual guides immediately on gesture end
                                            isTransforming = false
                                            guideSnapCenterX = false
                                            guideSnapCenterY = false
                                            guideSnapEdgeLeft = false
                                            guideSnapEdgeRight = false
                                            guideSnapEdgeTop = false
                                            guideSnapEdgeBottom = false
                                            guideBadgeText = null

                                            // Gesture completed (all fingers up)
                                            if (isDragOrTransform) {
                                                resolvedViewModel?.commitClipTransform(selectedClipIndex, currentTransform)
                                                if (USE_NEW_CORE) {
                                                    resolvedViewModel?.let { vm ->
                                                        snapshotHolder.current = vm.getSnapshot()
                                                    }
                                                }
                                                previewRenderer.requestRender()
                                                frameDriver.wake()
                                                triggerApplyToAllPill()
                                            } else {
                                                // Check for double-tap
                                                val upTime = System.currentTimeMillis()
                                                val tapDuration = upTime - downTime
                                                val tapDist = (downPos - lastTapPosition).getDistance()

                                                if (tapDuration < 300L && (upTime - lastTapTimeMs) < 350L && tapDist < 60f) {
                                                    // Double-tap detected: reset transform!
                                                    val resetTransform = currentTransform.copy(
                                                        scale = 1.0f,
                                                        panX = 0f,
                                                        panY = 0f,
                                                        rotationDeg = 0f
                                                    )
                                                    resolvedViewModel?.commitClipTransform(selectedClipIndex, resetTransform)
                                                    if (USE_NEW_CORE) {
                                                        resolvedViewModel?.let { vm ->
                                                            snapshotHolder.current = vm.getSnapshot()
                                                        }
                                                    }
                                                    PreviewRenderer.setClipTransform(activeClipId, resetTransform)
                                                    previewRenderer.requestRender()
                                                    frameDriver.wake()
                                                    triggerApplyToAllPill()
                                                    lastTapTimeMs = 0L
                                                } else if (tapDuration < 300L) {
                                                    lastTapTimeMs = upTime
                                                    lastTapPosition = downPos
                                                }
                                            }
                                        }
                                    }
                            ) {
                                // 9:16 Guide Overlay Canvas & Badge
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .aspectRatio(9f / 16f)
                                        .align(Alignment.Center)
                                ) {
                                    if (isTransforming) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            val strokeWidth1_5 = 1.5.dp.toPx()
                                            val strokeWidth2 = 2.dp.toPx()
                                            val cyanColor = Color(0xFF00E5FF)

                                            // Vertical Center Guide
                                            if (guideSnapCenterX) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(size.width / 2f, 0f),
                                                    end = Offset(size.width / 2f, size.height),
                                                    strokeWidth = strokeWidth1_5
                                                )
                                            }

                                            // Horizontal Center Guide
                                            if (guideSnapCenterY) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(0f, size.height / 2f),
                                                    end = Offset(size.width, size.height / 2f),
                                                    strokeWidth = strokeWidth1_5
                                                )
                                            }

                                            // Edge Guides (inset by strokeWidth2 / 2f so the entire 2dp stroke is visible)
                                            if (guideSnapEdgeLeft) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(strokeWidth2 / 2f, 0f),
                                                    end = Offset(strokeWidth2 / 2f, size.height),
                                                    strokeWidth = strokeWidth2
                                                )
                                            }
                                            if (guideSnapEdgeRight) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(size.width - strokeWidth2 / 2f, 0f),
                                                    end = Offset(size.width - strokeWidth2 / 2f, size.height),
                                                    strokeWidth = strokeWidth2
                                                )
                                            }
                                            if (guideSnapEdgeTop) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(0f, strokeWidth2 / 2f),
                                                    end = Offset(size.width, strokeWidth2 / 2f),
                                                    strokeWidth = strokeWidth2
                                                )
                                            }
                                            if (guideSnapEdgeBottom) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(0f, size.height - strokeWidth2 / 2f),
                                                    end = Offset(size.width, size.height - strokeWidth2 / 2f),
                                                    strokeWidth = strokeWidth2
                                                )
                                            }
                                        }

                                        // Rotation / Scale Badge
                                        if (guideBadgeText != null) {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = Color(0xCC000000),
                                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f)),
                                                modifier = Modifier
                                                    .align(Alignment.TopCenter)
                                                    .padding(top = 12.dp)
                                            ) {
                                                Text(
                                                    text = guideBadgeText!!,
                                                    color = Color(0xFF00E5FF),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // On-Preview Free Transform Gestures overlay when an Overlay Item is selected and active at current playhead
                    if (hasClips && selectedClipIndex == null && isSelectedOverlayActiveAtPlayhead && activeOverlay != null && activeOverlay.type != com.flowcut.app.engine.LayerType.FILTER && activeOverlayId != null) {
                        if (true) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(activeOverlayId) {
                                        var lastTapTimeMs = 0L
                                        var lastTapPosition = Offset.Zero

                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val downTime = System.currentTimeMillis()
                                            val downPos = down.position
                                            var isDragOrTransform = false

                                            val currentPlayheadUs = playbackController.playhead.timeUs
                                            val initialTransform = if (activeOverlay.keyframes.isNotEmpty()) {
                                                animEvaluator.evaluate(activeOverlay, currentPlayheadUs)
                                            } else {
                                                activeOverlay.transform
                                            }

                                            var rawPanX = initialTransform.panX
                                            var rawPanY = initialTransform.panY
                                            var rawScale = initialTransform.scale
                                            var rawRotationDeg = initialTransform.rotationDeg
                                            val isFlippedH = initialTransform.isFlippedH

                                            var currentTransform = initialTransform
                                            var totalMovedPx = 0f

                                            val outerW = size.width.toFloat().coerceAtLeast(1f)
                                            val outerH = size.height.toFloat().coerceAtLeast(1f)
                                            val previewH = outerH
                                            val previewW = (outerH * (9f / 16f)).coerceAtMost(outerW).coerceAtLeast(1f)
                                            val previewLeft = (outerW - previewW) / 2f

                                            // Calculate aspect-correct overlay dimensions and center in outer gesture coordinates
                                            val displayText = if (activeOverlay.text.isBlank()) "Your Text" else activeOverlay.text
                                            val aTex: Float = when (activeOverlay.type) {
                                                com.flowcut.app.engine.LayerType.STICKER -> 1.0f
                                                com.flowcut.app.engine.LayerType.TEXT -> {
                                                    val lines = displayText.split("\n")
                                                    val maxLen = lines.maxOfOrNull { it.length } ?: 6
                                                    val lineCount = lines.size.coerceAtLeast(1)
                                                    (maxLen.toFloat() * 0.45f / lineCount.toFloat()).coerceIn(0.8f, 4.0f)
                                                }
                                                com.flowcut.app.engine.LayerType.IMAGE, com.flowcut.app.engine.LayerType.VIDEO -> {
                                                    val tw = if (activeOverlay.intrinsicWidth > 0) activeOverlay.intrinsicWidth.toFloat() else 1f
                                                    val th = if (activeOverlay.intrinsicHeight > 0) activeOverlay.intrinsicHeight.toFloat() else 1f
                                                    (tw / th).coerceIn(0.01f, 100f)
                                                }
                                                else -> 1.0f
                                            }
                                            val aScreen = 9.0f / 16.0f
                                            var hwInit = 0.6f
                                            var hhInit = hwInit / aTex
                                            if (hhInit * aScreen > 0.6f) {
                                                val factor = 0.6f / (hhInit * aScreen)
                                                hwInit *= factor
                                                hhInit *= factor
                                            }
                                            val rawQuadW = hwInit * initialTransform.scale.coerceIn(0.01f, 10.0f) * previewW
                                            val rawQuadH = hhInit * initialTransform.scale.coerceIn(0.01f, 10.0f) * previewW
                                            val isMedia = activeOverlay.type == com.flowcut.app.engine.LayerType.IMAGE || activeOverlay.type == com.flowcut.app.engine.LayerType.VIDEO
                                            val padPx = if (isMedia) 0f else 12.dp.toPx()
                                            val minWPx = 96.dp.toPx()
                                            val minHPx = 64.dp.toPx()
                                            val initBoxW = if (isMedia) rawQuadW else (rawQuadW + 2f * padPx).coerceAtLeast(minWPx)
                                            val initBoxH = if (isMedia) rawQuadH else (rawQuadH + 2f * padPx).coerceAtLeast(minHPx)
                                            val handleBoxW = initBoxW.coerceAtLeast(48.dp.toPx())
                                            val handleBoxH = initBoxH.coerceAtLeast(48.dp.toPx())
                                            val cx_outer = previewLeft + previewW / 2f + initialTransform.panX * previewW
                                            val cy_outer = previewH / 2f - initialTransform.panY * previewH

                                            // Calculate handle badge positions rotated around center (cx_outer, cy_outer)
                                            val rad = Math.toRadians(initialTransform.rotationDeg.toDouble())
                                            val cosR = kotlin.math.cos(rad).toFloat()
                                            val sinR = kotlin.math.sin(rad).toFloat()

                                            fun toOuterPos(dx: Float, dy: Float): Offset {
                                                val rotX = dx * cosR - dy * sinR
                                                val rotY = dx * sinR + dy * cosR
                                                return Offset(cx_outer + rotX, cy_outer + rotY)
                                            }

                                            val tlPos = toOuterPos(-handleBoxW / 2f, -handleBoxH / 2f)
                                            val trPos = toOuterPos(handleBoxW / 2f, -handleBoxH / 2f)
                                            val blPos = toOuterPos(-handleBoxW / 2f, handleBoxH / 2f)
                                            val brPos = toOuterPos(handleBoxW / 2f, handleBoxH / 2f)
                                            val rmPos = toOuterPos(handleBoxW / 2f, 0f)

                                            val hitRadius = 28.dp.toPx()
                                            val distTL = (downPos - tlPos).getDistance()
                                            val distTR = (downPos - trPos).getDistance()
                                            val distBL = (downPos - blPos).getDistance()
                                            val distBR = (downPos - brPos).getDistance()
                                            val distRM = (downPos - rmPos).getDistance()

                                            // Determine active handle mode:
                                            // 1: Top-Left Delete, 2: Top-Right Edit (Text only), 3: Bottom-Left Copy,
                                            // 4: Bottom-Right Scale/Rotate, 5: Right Midpoint Scale, 0: Normal Body
                                            val activeHandle = when {
                                                distTL <= hitRadius -> 1
                                                activeOverlay.type == com.flowcut.app.engine.LayerType.TEXT && distTR <= hitRadius -> 2
                                                distBL <= hitRadius -> 3
                                                distBR <= hitRadius -> 4
                                                distRM <= hitRadius -> 5
                                                else -> 0
                                            }

                                            var prevSnapCenterX = false
                                            var prevSnapCenterY = false
                                            var prevSnapEdgeLeft = false
                                            var prevSnapEdgeRight = false
                                            var prevSnapEdgeTop = false
                                            var prevSnapEdgeBottom = false
                                            var prevSnapScale100 = false
                                            var prevSnapRotationCardinal = false

                                            do {
                                                val event = awaitPointerEvent()
                                                val pressedList = event.changes.filter { it.pressed }
                                                if (pressedList.isEmpty()) break

                                                if (pressedList.size == 1) {
                                                    val p = pressedList[0]
                                                    val dx = p.position.x - p.previousPosition.x
                                                    val dy = p.position.y - p.previousPosition.y
                                                    val moveDist = kotlin.math.sqrt(dx * dx + dy * dy)
                                                    totalMovedPx += moveDist

                                                    when (activeHandle) {
                                                        4 -> { // BOTTOM_RIGHT_SCALE_ROTATE: 1-finger simultaneous scale & rotate!
                                                            if (totalMovedPx > 4f) {
                                                                isDragOrTransform = true
                                                                p.consume()

                                                                val prevPos = p.previousPosition
                                                                val currPos = p.position
                                                                val centerOffset = Offset(cx_outer, cy_outer)
                                                                val prevDist = (prevPos - centerOffset).getDistance()
                                                                val currDist = (currPos - centerOffset).getDistance()
                                                                if (prevDist > 1f && currDist > 1f) {
                                                                    val scaleRatio = currDist / prevDist
                                                                    rawScale = (rawScale * scaleRatio).coerceIn(0.2f, 8.0f)
                                                                }

                                                                val prevAngle = Math.toDegrees(kotlin.math.atan2((prevPos.y - cy_outer).toDouble(), (prevPos.x - cx_outer).toDouble())).toFloat()
                                                                val currAngle = Math.toDegrees(kotlin.math.atan2((currPos.y - cy_outer).toDouble(), (currPos.x - cx_outer).toDouble())).toFloat()
                                                                var angleDelta = currAngle - prevAngle
                                                                while (angleDelta > 180f) angleDelta -= 360f
                                                                while (angleDelta < -180f) angleDelta += 360f
                                                                rawRotationDeg += angleDelta
                                                            }
                                                        }
                                                        5 -> { // RIGHT_MID_SCALE: 1-finger uniform scaling
                                                            if (totalMovedPx > 4f) {
                                                                isDragOrTransform = true
                                                                p.consume()

                                                                val prevPos = p.previousPosition
                                                                val currPos = p.position
                                                                val centerOffset = Offset(cx_outer, cy_outer)
                                                                val prevDist = (prevPos - centerOffset).getDistance()
                                                                val currDist = (currPos - centerOffset).getDistance()
                                                                if (prevDist > 1f && currDist > 1f) {
                                                                    val scaleRatio = currDist / prevDist
                                                                    rawScale = (rawScale * scaleRatio).coerceIn(0.2f, 8.0f)
                                                                }
                                                            }
                                                        }
                                                        1, 2, 3 -> {
                                                            if (totalMovedPx > 15f) {
                                                                isDragOrTransform = true
                                                            }
                                                        }
                                                        else -> { // BODY: 1-finger Pan
                                                            if (totalMovedPx > 10f) {
                                                                isDragOrTransform = true
                                                                p.consume()

                                                                rawPanX = (rawPanX + dx / previewW).coerceIn(-1.5f, 1.5f)
                                                                rawPanY = (rawPanY - dy / previewH).coerceIn(-1.5f, 1.5f)
                                                            }
                                                        }
                                                    }
                                                } else if (pressedList.size >= 2 && activeHandle == 0) {
                                                    isDragOrTransform = true
                                                    val p1 = pressedList[0]
                                                    val p2 = pressedList[1]
                                                    p1.consume()
                                                    p2.consume()

                                                    val prevPos1 = p1.previousPosition
                                                    val prevPos2 = p2.previousPosition
                                                    val currPos1 = p1.position
                                                    val currPos2 = p2.position

                                                    // 1. Pan (centroid delta)
                                                    val prevCenter = (prevPos1 + prevPos2) / 2f
                                                    val currCenter = (currPos1 + currPos2) / 2f
                                                    val dPan = currCenter - prevCenter
                                                    rawPanX = (rawPanX + dPan.x / previewW).coerceIn(-1.5f, 1.5f)
                                                    rawPanY = (rawPanY - dPan.y / previewH).coerceIn(-1.5f, 1.5f)

                                                    // 2. Zoom (distance ratio)
                                                    val prevDist = (prevPos1 - prevPos2).getDistance()
                                                    val currDist = (currPos1 - currPos2).getDistance()
                                                    val zoomRatio = if (prevDist > 1f) currDist / prevDist else 1f
                                                    rawScale = (rawScale * zoomRatio).coerceIn(0.25f, 5.0f)

                                                    // 3. Twist (rotation delta)
                                                    val prevAngle = Math.toDegrees(kotlin.math.atan2((prevPos2.y - prevPos1.y).toDouble(), (prevPos2.x - prevPos1.x).toDouble())).toFloat()
                                                    val currAngle = Math.toDegrees(kotlin.math.atan2((currPos2.y - currPos1.y).toDouble(), (currPos2.x - currPos1.x).toDouble())).toFloat()
                                                    var angleDelta = currAngle - prevAngle
                                                    while (angleDelta > 180f) angleDelta -= 360f
                                                    while (angleDelta < -180f) angleDelta += 360f
                                                    rawRotationDeg += angleDelta
                                                }

                                                if (isDragOrTransform && (activeHandle == 0 || activeHandle == 4 || activeHandle == 5)) {
                                                    // --- Compute Snapping from Raw Accumulators ---
                                                    // 1. Rotation Snap
                                                    val normRot = ((rawRotationDeg % 360f) + 360f) % 360f
                                                    val nearestCardinal = (Math.round(normRot / 90f) * 90f).toFloat()
                                                    val diffRot = kotlin.math.abs(normRot - nearestCardinal)
                                                    val snapRotationCardinal = diffRot <= 3.0f
                                                    val snappedRotationDeg = if (snapRotationCardinal) (nearestCardinal % 360f) else rawRotationDeg

                                                    // 2. Scale Snap
                                                    val diffScale = kotlin.math.abs(rawScale - 1.0f)
                                                    val snapScale100 = diffScale < 0.04f
                                                    val snappedScale = if (snapScale100) 1.0f else rawScale

                                                    // 3. Effective Dimensions for Edge Snapping
                                                    val effAspect = aTex
                                                    val baseHw = if (effAspect >= aScreen) 0.5f * (effAspect / aScreen) else 0.5f
                                                    val baseHh = if (effAspect >= aScreen) 0.5f else 0.5f * (aScreen / effAspect)
                                                    val hw = baseHw * snappedScale
                                                    val hh = baseHh * snappedScale

                                                    // Target pan offsets for edge flush alignment
                                                    val targetPanXLeft = hw - 0.5f
                                                    val targetPanXRight = 0.5f - hw
                                                    val targetPanYBottom = hh - 0.5f
                                                    val targetPanYTop = 0.5f - hh

                                                    // 4. Center & Edge Snap for panX
                                                    var snappedPanX = rawPanX
                                                    var snapCenterX = false
                                                    var snapEdgeLeft = false
                                                    var snapEdgeRight = false

                                                    if (kotlin.math.abs(rawPanX) < 0.035f) {
                                                        snappedPanX = 0f
                                                        snapCenterX = true
                                                    } else if (kotlin.math.abs(rawPanX - targetPanXLeft) < 0.035f) {
                                                        snappedPanX = targetPanXLeft
                                                        snapEdgeLeft = true
                                                    } else if (kotlin.math.abs(rawPanX - targetPanXRight) < 0.035f) {
                                                        snappedPanX = targetPanXRight
                                                        snapEdgeRight = true
                                                    }

                                                    // 5. Center & Edge Snap for panY
                                                    var snappedPanY = rawPanY
                                                    var snapCenterY = false
                                                    var snapEdgeBottom = false
                                                    var snapEdgeTop = false

                                                    if (kotlin.math.abs(rawPanY) < 0.035f) {
                                                        snappedPanY = 0f
                                                        snapCenterY = true
                                                    } else if (kotlin.math.abs(rawPanY - targetPanYBottom) < 0.035f) {
                                                        snappedPanY = targetPanYBottom
                                                        snapEdgeBottom = true
                                                    } else if (kotlin.math.abs(rawPanY - targetPanYTop) < 0.035f) {
                                                        snappedPanY = targetPanYTop
                                                        snapEdgeTop = true
                                                    }

                                                    // --- Haptic Feedback Tick on State Transition ---
                                                    val newlySnapped = (!prevSnapCenterX && snapCenterX) ||
                                                            (!prevSnapCenterY && snapCenterY) ||
                                                            (!prevSnapEdgeLeft && snapEdgeLeft) ||
                                                            (!prevSnapEdgeRight && snapEdgeRight) ||
                                                            (!prevSnapEdgeTop && snapEdgeTop) ||
                                                            (!prevSnapEdgeBottom && snapEdgeBottom) ||
                                                            (!prevSnapScale100 && snapScale100) ||
                                                            (!prevSnapRotationCardinal && snapRotationCardinal)

                                                    if (newlySnapped) {
                                                        try {
                                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        } catch (_: Throwable) {}
                                                    }

                                                    prevSnapCenterX = snapCenterX
                                                    prevSnapCenterY = snapCenterY
                                                    prevSnapEdgeLeft = snapEdgeLeft
                                                    prevSnapEdgeRight = snapEdgeRight
                                                    prevSnapEdgeTop = snapEdgeTop
                                                    prevSnapEdgeBottom = snapEdgeBottom
                                                    prevSnapScale100 = snapScale100
                                                    prevSnapRotationCardinal = snapRotationCardinal

                                                    // --- Update Compose Guide Overlay State ---
                                                    isTransforming = true
                                                    guideSnapCenterX = snapCenterX
                                                    guideSnapCenterY = snapCenterY
                                                    guideSnapEdgeLeft = snapEdgeLeft
                                                    guideSnapEdgeRight = snapEdgeRight
                                                    guideSnapEdgeTop = snapEdgeTop
                                                    guideSnapEdgeBottom = snapEdgeBottom

                                                    // Badge text: show rotation or scale
                                                    guideBadgeText = when {
                                                        snapRotationCardinal -> "${snappedRotationDeg.toInt()}°"
                                                        snapScale100 -> "100%"
                                                        activeHandle == 4 && kotlin.math.abs(rawRotationDeg - initialTransform.rotationDeg) > 1.5f ->
                                                            "${Math.round(snappedRotationDeg)}°"
                                                        (activeHandle == 4 || activeHandle == 5) && kotlin.math.abs(rawScale - initialTransform.scale) > 0.03f ->
                                                            "${Math.round(snappedScale * 100f)}%"
                                                        pressedList.size >= 2 && kotlin.math.abs(rawRotationDeg - initialTransform.rotationDeg) > 1.5f ->
                                                            "${Math.round(snappedRotationDeg)}°"
                                                        pressedList.size >= 2 && kotlin.math.abs(rawScale - initialTransform.scale) > 0.03f ->
                                                            "${Math.round(snappedScale * 100f)}%"
                                                        else -> null
                                                    }

                                                    currentTransform = currentTransform.copy(
                                                        scale = snappedScale,
                                                        panX = snappedPanX,
                                                        panY = snappedPanY,
                                                        rotationDeg = snappedRotationDeg,
                                                        isFlippedH = isFlippedH
                                                    )
                                                    resolvedViewModel?.setOverlayTransformLive(activeOverlayId, currentTransform, currentPlayheadUs)
                                                    previewRenderer.requestRender()
                                                    frameDriver.wake()
                                                }
                                            } while (true)

                                            // Clear visual guides immediately on gesture end
                                            isTransforming = false
                                            guideSnapCenterX = false
                                            guideSnapCenterY = false
                                            guideSnapEdgeLeft = false
                                            guideSnapEdgeRight = false
                                            guideSnapEdgeTop = false
                                            guideSnapEdgeBottom = false
                                            guideBadgeText = null

                                            // Gesture completed (all fingers up)
                                            if (isDragOrTransform) {
                                                if (activeHandle == 0 || activeHandle == 4 || activeHandle == 5) {
                                                    resolvedViewModel?.commitOverlayTransform(activeOverlayId, currentTransform, currentPlayheadUs)
                                                    previewRenderer.requestRender()
                                                    frameDriver.wake()
                                                }
                                            } else {
                                                val upTime = System.currentTimeMillis()
                                                val tapDuration = upTime - downTime
                                                if (tapDuration < 400L) {
                                                    when (activeHandle) {
                                                        1 -> { // Top-Left: Delete Overlay
                                                            try {
                                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            } catch (_: Throwable) {}
                                                            resolvedViewModel?.deleteSelectedOverlay()
                                                            previewRenderer.requestRender()
                                                            frameDriver.wake()
                                                        }
                                                        2 -> { // Top-Right: Edit Text Overlay
                                                            try {
                                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            } catch (_: Throwable) {}
                                                            editingTextOverlayId = activeOverlayId
                                                            isNewTextOverlay = false
                                                            isTextSheetVisible = true
                                                        }
                                                        3 -> { // Bottom-Left: Duplicate / Copy Overlay
                                                            try {
                                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            } catch (_: Throwable) {}
                                                            resolvedViewModel?.copySelectedOverlay()
                                                            previewRenderer.requestRender()
                                                            frameDriver.wake()
                                                        }
                                                        else -> { // Double-tap on Body resets transform
                                                            val tapDist = (downPos - lastTapPosition).getDistance()
                                                            if ((upTime - lastTapTimeMs) < 350L && tapDist < 60f) {
                                                                val resetTransform = currentTransform.copy(
                                                                    scale = 1.0f,
                                                                    panX = 0f,
                                                                    panY = 0f,
                                                                    rotationDeg = 0f
                                                                )
                                                                resolvedViewModel?.commitOverlayTransform(activeOverlayId, resetTransform, currentPlayheadUs)
                                                                previewRenderer.requestRender()
                                                                frameDriver.wake()
                                                                lastTapTimeMs = 0L
                                                            } else {
                                                                lastTapTimeMs = upTime
                                                                lastTapPosition = downPos

                                                                // Allow tapping directly on another PIP/Text/Sticker quad on Preview screen
                                                                val playheadUs = playbackController.playhead.timeUs
                                                                val hit = hitTestPreviewOverlays(
                                                                    tapPos = downPos,
                                                                    outerW = outerW,
                                                                    outerH = outerH,
                                                                    overlayItems = resolvedViewModel?.layerItems ?: emptyList(),
                                                                    playheadUs = playheadUs,
                                                                    preferredTopOverlayId = activeOverlayId
                                                                )
                                                                if (hit != null && hit.id != activeOverlayId) {
                                                                    val newFocus = when (hit.type) {
                                                                        com.flowcut.app.engine.LayerType.IMAGE, com.flowcut.app.engine.LayerType.VIDEO ->
                                                                            TimelineFocusState.S2_PIP_ITEM(hit.id)
                                                                        com.flowcut.app.engine.LayerType.STICKER ->
                                                                            TimelineFocusState.S3_STICKER_ITEM(hit.id)
                                                                        com.flowcut.app.engine.LayerType.TEXT ->
                                                                            TimelineFocusState.S4_TEXT_ITEM(hit.id)
                                                                        else -> TimelineFocusState.S0_NONE
                                                                    }
                                                                    resolvedViewModel?.selectOverlay(hit.id)
                                                                    resolvedViewModel?.setFocusMode(newFocus)
                                                                    previewRenderer.selectedOverlayIdForPreview = hit.id
                                                                    previewRenderer.requestRender()
                                                                    frameDriver.wake()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                            ) {
                                // 9:16 Guide Overlay Canvas & Badge
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .aspectRatio(9f / 16f)
                                        .align(Alignment.Center)
                                ) {
                                    // Interactive CapCut-Style Selection Frame + 5 Action Badges around Selected Overlay
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val selOverlay = activeOverlay
                                        val currentPlayhead = playbackController.playhead.timeUs
                                        val effTransform = if (selOverlay.keyframes.isNotEmpty()) {
                                            animEvaluator.evaluate(selOverlay, currentPlayhead)
                                        } else {
                                            selOverlay.transform
                                        }
                                        val displayText = if (selOverlay.text.isBlank()) "Your Text" else selOverlay.text
                                        val aTex: Float = when (selOverlay.type) {
                                                com.flowcut.app.engine.LayerType.STICKER -> 1.0f
                                                com.flowcut.app.engine.LayerType.TEXT -> {
                                                    val lines = displayText.split("\n")
                                                    val maxLen = lines.maxOfOrNull { it.length } ?: 6
                                                    val lineCount = lines.size.coerceAtLeast(1)
                                                    (maxLen.toFloat() * 0.45f / lineCount.toFloat()).coerceIn(0.8f, 4.0f)
                                                }
                                                com.flowcut.app.engine.LayerType.IMAGE, com.flowcut.app.engine.LayerType.VIDEO -> {
                                                    val tw = if (activeOverlay.intrinsicWidth > 0) activeOverlay.intrinsicWidth.toFloat() else 1f
                                                    val th = if (activeOverlay.intrinsicHeight > 0) activeOverlay.intrinsicHeight.toFloat() else 1f
                                                    (tw / th).coerceIn(0.01f, 100f)
                                                }
                                                else -> 1.0f
                                            }

                                            val aScreen = 9.0f / 16.0f
                                            var hw = 0.6f
                                            var hh = hw / aTex
                                            if (hh * aScreen > 0.6f) {
                                                val factor = 0.6f / (hh * aScreen)
                                                hw *= factor
                                                hh *= factor
                                            }

                                            val scale = effTransform.scale.coerceIn(0.01f, 10.0f)
                                            val rawQuadW = hw * scale * size.width
                                            val rawQuadH = hh * scale * size.width
                                            val isMedia = activeOverlay.type == com.flowcut.app.engine.LayerType.IMAGE || activeOverlay.type == com.flowcut.app.engine.LayerType.VIDEO
                                            val padPx = if (isMedia) 0f else 12.dp.toPx()
                                            val minWPx = 96.dp.toPx()
                                            val minHPx = 64.dp.toPx()
                                            val boxW = if (isMedia) rawQuadW else (rawQuadW + 2f * padPx).coerceAtLeast(minWPx)
                                            val boxH = if (isMedia) rawQuadH else (rawQuadH + 2f * padPx).coerceAtLeast(minHPx)
                                            val handleSeparationMin = 48.dp.toPx()
                                            val handleBoxW = boxW.coerceAtLeast(handleSeparationMin)
                                            val handleBoxH = boxH.coerceAtLeast(handleSeparationMin)

                                            val cx = size.width / 2f + effTransform.panX * size.width
                                            val cy = size.height / 2f - effTransform.panY * size.height

                                            withTransform({
                                                rotate(effTransform.rotationDeg, pivot = Offset(cx, cy))
                                            }) {
                                                val left = cx - boxW / 2f
                                                val top = cy - boxH / 2f
                                                val hLeft = cx - handleBoxW / 2f
                                                val hTop = cy - handleBoxH / 2f
                                                val strokeWidth = 1.dp.toPx()
                                                val cornerBadgeRadius = 12.dp.toPx()
                                                val midDotRadius = 7.dp.toPx()

                                                // 1.dp White stroke bounding frame - hugs borders 100% flush
                                                drawRect(
                                                    color = Color.White,
                                                    topLeft = Offset(left, top),
                                                    size = Size(boxW, boxH),
                                                    style = Stroke(width = strokeWidth)
                                                )

                                                // 1. Top-Left: Delete Button (✕) - Dark circle + white outline + white ✕
                                                val topLeftCenter = Offset(hLeft, hTop)
                                                drawCircle(
                                                    color = Color(0xFF1E1E24),
                                                    radius = cornerBadgeRadius,
                                                    center = topLeftCenter
                                                )
                                                drawCircle(
                                                    color = Color.White,
                                                    radius = cornerBadgeRadius,
                                                    center = topLeftCenter,
                                                    style = Stroke(width = 1.5.dp.toPx())
                                                )
                                                val crossSpan = 4.dp.toPx()
                                                drawLine(
                                                    color = Color.White,
                                                    start = topLeftCenter + Offset(-crossSpan, -crossSpan),
                                                    end = topLeftCenter + Offset(crossSpan, crossSpan),
                                                    strokeWidth = 2.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )
                                                drawLine(
                                                    color = Color.White,
                                                    start = topLeftCenter + Offset(crossSpan, -crossSpan),
                                                    end = topLeftCenter + Offset(-crossSpan, crossSpan),
                                                    strokeWidth = 2.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )

                                                // 2. Top-Right: Edit Button (✎ Pencil) - Pink circle + white pencil (Text overlays only)
                                                val topRightCenter = Offset(hLeft + handleBoxW, hTop)
                                                if (selOverlay.type == com.flowcut.app.engine.LayerType.TEXT) {
                                                    drawCircle(
                                                        color = Color(0xFFFF2D55),
                                                        radius = cornerBadgeRadius,
                                                        center = topRightCenter
                                                    )
                                                    // White diagonal pencil icon
                                                    drawLine(
                                                        color = Color.White,
                                                        start = topRightCenter + Offset(-4.dp.toPx(), 4.dp.toPx()),
                                                        end = topRightCenter + Offset(3.5.dp.toPx(), -3.5.dp.toPx()),
                                                        strokeWidth = 2.5.dp.toPx(),
                                                        cap = StrokeCap.Round
                                                    )
                                                    drawLine(
                                                        color = Color.White,
                                                        start = topRightCenter + Offset(-4.dp.toPx(), 4.dp.toPx()),
                                                        end = topRightCenter + Offset(-5.dp.toPx(), 5.dp.toPx()),
                                                        strokeWidth = 1.5.dp.toPx(),
                                                        cap = StrokeCap.Round
                                                    )
                                                }

                                                // 3. Bottom-Left: Duplicate / Copy Button (❐) - Pink circle + overlapping squares
                                                val bottomLeftCenter = Offset(hLeft, hTop + handleBoxH)
                                                drawCircle(
                                                    color = Color(0xFFFF2D55),
                                                    radius = cornerBadgeRadius,
                                                    center = bottomLeftCenter
                                                )
                                                val sqSize = 6.dp.toPx()
                                                drawRect(
                                                    color = Color.White,
                                                    topLeft = bottomLeftCenter + Offset(-2.dp.toPx(), -5.dp.toPx()),
                                                    size = Size(sqSize, sqSize),
                                                    style = Stroke(width = 1.2.dp.toPx())
                                                )
                                                drawRect(
                                                    color = Color(0xFFFF2D55),
                                                    topLeft = bottomLeftCenter + Offset(-5.dp.toPx(), -2.dp.toPx()),
                                                    size = Size(sqSize, sqSize)
                                                )
                                                drawRect(
                                                    color = Color.White,
                                                    topLeft = bottomLeftCenter + Offset(-5.dp.toPx(), -2.dp.toPx()),
                                                    size = Size(sqSize, sqSize),
                                                    style = Stroke(width = 1.2.dp.toPx())
                                                )

                                                // 4. Bottom-Right: 1-Finger Scale & Rotate Handle (⤡) - Pink circle + diagonal double arrow
                                                val bottomRightCenter = Offset(hLeft + handleBoxW, hTop + handleBoxH)
                                                drawCircle(
                                                    color = Color(0xFFFF2D55),
                                                    radius = cornerBadgeRadius,
                                                    center = bottomRightCenter
                                                )
                                                val arrowSpan = 5.dp.toPx()
                                                val arrowHead = 2.5.dp.toPx()
                                                // Diagonal line shaft
                                                drawLine(
                                                    color = Color.White,
                                                    start = bottomRightCenter + Offset(-arrowSpan, -arrowSpan),
                                                    end = bottomRightCenter + Offset(arrowSpan, arrowSpan),
                                                    strokeWidth = 1.8.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )
                                                // Top-left arrowhead
                                                drawLine(
                                                    color = Color.White,
                                                    start = bottomRightCenter + Offset(-arrowSpan, -arrowSpan),
                                                    end = bottomRightCenter + Offset(-arrowSpan + arrowHead, -arrowSpan),
                                                    strokeWidth = 1.8.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )
                                                drawLine(
                                                    color = Color.White,
                                                    start = bottomRightCenter + Offset(-arrowSpan, -arrowSpan),
                                                    end = bottomRightCenter + Offset(-arrowSpan, -arrowSpan + arrowHead),
                                                    strokeWidth = 1.8.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )
                                                // Bottom-right arrowhead
                                                drawLine(
                                                    color = Color.White,
                                                    start = bottomRightCenter + Offset(arrowSpan, arrowSpan),
                                                    end = bottomRightCenter + Offset(arrowSpan - arrowHead, arrowSpan),
                                                    strokeWidth = 1.8.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )
                                                drawLine(
                                                    color = Color.White,
                                                    start = bottomRightCenter + Offset(arrowSpan, arrowSpan),
                                                    end = bottomRightCenter + Offset(arrowSpan, arrowSpan - arrowHead),
                                                    strokeWidth = 1.8.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )

                                                // 5. Edge Midpoint: ONLY ONE Solid Red/Pink Handle Dot (●) on the RIGHT edge
                                                val edgeDotRadius = 6.dp.toPx()
                                                val edgeDotStroke = 1.dp.toPx()
                                                val pinkDotColor = Color(0xFFFF2D55)
                                                val dotBorderColor = Color.White.copy(alpha = 0.5f)

                                                // Right-Mid
                                                val rmx = hLeft + handleBoxW
                                                val rmy = hTop + handleBoxH / 2f
                                                drawCircle(color = pinkDotColor, radius = edgeDotRadius, center = Offset(rmx, rmy))
                                                drawCircle(color = dotBorderColor, radius = edgeDotRadius, center = Offset(rmx, rmy), style = Stroke(width = edgeDotStroke))
                                            }
                                        }
                                    if (isTransforming) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            val strokeWidth1_5 = 1.5.dp.toPx()
                                            val strokeWidth2 = 2.dp.toPx()
                                            val cyanColor = Color(0xFF00E5FF)

                                            // Vertical Center Guide
                                            if (guideSnapCenterX) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(size.width / 2f, 0f),
                                                    end = Offset(size.width / 2f, size.height),
                                                    strokeWidth = strokeWidth2
                                                )
                                            }

                                            // Horizontal Center Guide
                                            if (guideSnapCenterY) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(0f, size.height / 2f),
                                                    end = Offset(size.width, size.height / 2f),
                                                    strokeWidth = strokeWidth2
                                                )
                                            }

                                            // Left Screen Edge Guide
                                            if (guideSnapEdgeLeft) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(0f, 0f),
                                                    end = Offset(0f, size.height),
                                                    strokeWidth = strokeWidth1_5
                                                )
                                            }

                                            // Right Screen Edge Guide
                                            if (guideSnapEdgeRight) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(size.width, 0f),
                                                    end = Offset(size.width, size.height),
                                                    strokeWidth = strokeWidth1_5
                                                )
                                            }

                                            // Top Screen Edge Guide
                                            if (guideSnapEdgeTop) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(0f, 0f),
                                                    end = Offset(size.width, 0f),
                                                    strokeWidth = strokeWidth1_5
                                                )
                                            }

                                            // Bottom Screen Edge Guide
                                            if (guideSnapEdgeBottom) {
                                                drawLine(
                                                    color = cyanColor,
                                                    start = Offset(0f, size.height),
                                                    end = Offset(size.width, size.height),
                                                    strokeWidth = strokeWidth1_5
                                                )
                                            }
                                        }

                                        // Snapping Info Badge
                                        if (guideBadgeText != null) {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = Color(0xCC141414),
                                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f)),
                                                modifier = Modifier
                                                    .align(Alignment.TopCenter)
                                                    .padding(top = 12.dp)
                                            ) {
                                                Text(
                                                    text = guideBadgeText!!,
                                                    color = Color(0xFF00E5FF),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Floating 3-Second "Apply to All" Pill in bottom-right corner of preview
                    if (hasClips && selectedClipIndex != null && showApplyToAll) {
                        Surface(
                            onClick = {
                                applyToAllJob?.cancel()
                                showApplyToAll = false
                                val currentProject = resolvedViewModel?.uiState?.value?.project
                                val clip = currentProject?.clips?.getOrNull(selectedClipIndex)
                                if (clip != null) {
                                    resolvedViewModel?.applyTransformToAll(clip.id)
                                    if (USE_NEW_CORE) {
                                        resolvedViewModel?.let { vm ->
                                            snapshotHolder.current = vm.getSnapshot()
                                        }
                                    }
                                    previewRenderer.requestRender()
                                    frameDriver.wake()
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = colors.surface2.copy(alpha = 0.92f),
                            border = BorderStroke(1.dp, colors.border),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Apply to All",
                                    tint = colors.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Apply to All",
                                    color = colors.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Playback row: 40 dp with Diamond Keyframe Button to the left of Undo
                val currentFocus = vmUiState?.focusMode ?: TimelineFocusState.S0_NONE
                val selection = remember(selectedClipIndex, selectedJunctionIndex, vmUiState?.selectedOverlayId) {
                    selectedClipIndex?.let { TimelineSelection.ClipSelected(it) }
                        ?: selectedJunctionIndex?.let { TimelineSelection.JunctionSelected(it) }
                        ?: vmUiState?.selectedOverlayId?.let { TimelineSelection.OverlaySelected(it) }
                        ?: TimelineSelection.None
                }

                TransportControlsRow(
                    vmUiState = vmUiState,
                    resolvedViewModel = resolvedViewModel,
                    selection = selection,
                    currentFocus = currentFocus,
                    frameTick = frameTick,
                    displayedMs = displayedMs,
                    playbackController = playbackController,
                    previewRenderer = previewRenderer,
                    frameDriver = frameDriver,
                    density = density,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onUndoClick = onUndoClick,
                    onRedoClick = onRedoClick,
                    timelineTotalMs = timeline.total,
                    isPlaying = isPlaying,
                    onPlayPauseClick = onPlayPauseClick,
                    onPlaybackStateChange = onPlaybackStateChange,
                    onSeekScrub = onSeekScrub,
                    snapshotHolder = snapshotHolder,
                    onSetPlayPauseBounds = { bounds ->
                        playPauseBoundsInRoot = bounds
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 4. Timeline block: constant fixed height (New Core TimelineCanvas or legacy TimelineBlock)
                if (USE_NEW_CORE) {
                    val timelineHeight = 224.dp
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(timelineHeight)
                            .background(colors.surface1, RoundedCornerShape(8.dp))
                    ) {
                        TimelineCanvas(
                            holder = snapshotHolder,
                            playhead = playbackController.playhead,
                            controller = playbackController,
                            zoomState = zoomState,
                            frameTick = frameTick,
                            selection = selection,
                            focusMode = currentFocus,
                            overlayItems = resolvedViewModel?.layerItems ?: emptyList(),
                            customCoverBitmap = customCoverBitmap,
                            onSelect = { sel ->
                                when (sel) {
                                    is TimelineSelection.ClipSelected -> onSelectClip(sel.index)
                                    is TimelineSelection.JunctionSelected -> onSelectJunction(sel.index)
                                    is TimelineSelection.OverlaySelected -> {
                                        overlappingConflictItemIds = null
                                        resolvedViewModel?.selectOverlay(sel.itemId)
                                    }
                                    is TimelineSelection.OverlappingPinsClicked -> {
                                        overlappingConflictItemIds = sel.itemIds
                                    }
                                    TimelineSelection.PipGroupClicked -> {
                                        resolvedViewModel?.setFocusMode(TimelineFocusState.S1_PIP_GROUP)
                                    }
                                    TimelineSelection.CoverClicked -> {
                                        showCoverDialog = true
                                    }
                                    TimelineSelection.AddClipClicked -> {
                                        onAddClipsClick()
                                    }
                                    TimelineSelection.AudioGroupClicked -> {
                                        resolvedViewModel?.setFocusMode(TimelineFocusState.S5_AUDIO_GROUP)
                                    }
                                    TimelineSelection.None -> {
                                        overlappingConflictItemIds = null
                                        onDeselectAll()
                                    }
                                    is TimelineSelection.LaneItemSelected -> {}
                                    is TimelineSelection.TrimHandleLeft -> {}
                                    is TimelineSelection.TrimHandleRight -> {}
                                    TimelineSelection.TrackMuteClicked -> {
                                        resolvedViewModel?.toggleTrackMute()
                                    }
                                }
                            },
                            onLongPress = { sel ->
                                when (sel) {
                                    is TimelineSelection.JunctionSelected -> {
                                        val j = sel.index
                                        val currentT = timeline.project.transitions.getOrNull(j)
                                        if (currentT != null && currentT.type != TransitionType.NONE) {
                                            onApplyTransition(currentT.type)
                                        }
                                    }
                                    is TimelineSelection.ClipSelected -> {
                                        onDeleteClip(sel.index)
                                    }
                                    else -> {}
                                }
                            },
                            onCommitTrim = { k, inUs, outUs, durMs ->
                                resolvedViewModel?.commitClipTrim(k, inUs, outUs, durMs)
                                onCommitTrim(k, inUs, outUs, durMs)
                            },
                            onCommitOverlayTrim = { id, startUs, endUs ->
                                resolvedViewModel?.commitOverlayTrim(id, startUs, endUs)
                                previewRenderer.requestRender()
                                frameDriver.wake()
                            },
                            onLiveOverlayTrim = { id, startUs, endUs ->
                                resolvedViewModel?.liveOverlayTrim(id, startUs, endUs)
                                previewRenderer.requestRender()
                                frameDriver.wake()
                            },
                            onCommitOverlayMove = { id, laneIndex, startUs, endUs ->
                                resolvedViewModel?.commitOverlayMove(id, laneIndex, startUs, endUs)
                                previewRenderer.requestRender()
                                frameDriver.wake()
                            },
                            onLiveOverlayMove = { id, laneIndex, startUs, endUs ->
                                resolvedViewModel?.liveOverlayMove(id, laneIndex, startUs, endUs)
                                previewRenderer.requestRender()
                                frameDriver.wake()
                            },
                            onTrimPreview = { previewUs ->
                                previewRenderer.trimPreviewTimeUs = previewUs
                                frameDriver.wake()
                            },
                            getClipNaturalDurationUs = { k ->
                                resolvedViewModel?.getClipNaturalDurationUs(k)
                                    ?: run {
                                        val clip = snapshotHolder.current.project.clips.getOrNull(k)
                                        clip?.let { c ->
                                            try {
                                                val file = java.io.File(c.path)
                                                if (file.exists()) {
                                                    val retriever = android.media.MediaMetadataRetriever()
                                                    retriever.setDataSource(file.absolutePath)
                                                    val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                                    retriever.release()
                                                    durStr?.toLongOrNull()?.let { msToUs(it) } ?: msToUs(c.durationMs)
                                                } else msToUs(c.durationMs)
                                            } catch (_: Exception) {
                                                msToUs(c.durationMs)
                                            }
                                        } ?: 0L
                                    }
                            },
                            onWakeFrameDriver = { frameDriver.wake() },
                            isMainTrackMuted = isMainTrackMuted,
                            onToggleTrackMute = { resolvedViewModel?.toggleTrackMute() },
                            onToast = { resolvedViewModel?.showToast(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    TimelineBlock(
                        timeline = timeline,
                        currentMs = currentMs,
                        selectedClipIndex = selectedClipIndex,
                        selectedJunctionIndex = selectedJunctionIndex,
                        onSelectClip = onSelectClip,
                        onDeleteClip = onDeleteClip,
                        onSelectJunction = onSelectJunction,
                        onOpenDurationSheet = {
                            selectedJunctionIndex?.let { j ->
                                val currentT = timeline.project.transitions.getOrNull(j)
                                if (currentT != null && currentT.type != TransitionType.NONE) {
                                    onApplyTransition(currentT.type) // open sheet
                                }
                            }
                        },
                        onDeselectAll = onDeselectAll,
                        onScrub = onSeekScrub,
                        onAddClipsClick = onAddClipsClick,
                        onAudioTrackClick = {
                            Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 7. Bottom toolbar: 64 dp + navigation bar inset (Contextual Main vs Clip vs Overlay toolbar)
            val activeOverlayForToolbar = vmUiState?.selectedOverlayId?.let { resolvedViewModel?.getOverlayItem(it) }
            val isTextOverlay = activeOverlayForToolbar?.type == com.flowcut.app.engine.LayerType.TEXT
            val isPipActive = vmUiState?.focusMode is com.flowcut.app.ui.timeline.TimelineFocusState.S1_PIP_GROUP ||
                vmUiState?.focusMode is com.flowcut.app.ui.timeline.TimelineFocusState.S2_PIP_ITEM
            val isPipItemSelected = vmUiState?.focusMode is com.flowcut.app.ui.timeline.TimelineFocusState.S2_PIP_ITEM
            val selectedPipItem = if (isPipItemSelected) activeOverlayForToolbar else null
            val isPipVideo = selectedPipItem?.type == com.flowcut.app.engine.LayerType.VIDEO
            val currentPlayheadUs = playbackController.playhead.timeUs
            val canSplitPip = selectedPipItem != null &&
                currentPlayheadUs >= (selectedPipItem.startUs + 200_000L) &&
                currentPlayheadUs <= (selectedPipItem.endUs - 200_000L)

            val isFilterOverlay = activeOverlayForToolbar?.type == com.flowcut.app.engine.LayerType.FILTER
            val isEffectOverlay = activeOverlayForToolbar?.type == com.flowcut.app.engine.LayerType.EFFECT

            val isPipOverlay = activeOverlayForToolbar?.type == com.flowcut.app.engine.LayerType.IMAGE ||
                activeOverlayForToolbar?.type == com.flowcut.app.engine.LayerType.VIDEO ||
                isPipActive

            val isAudioGroupActive = vmUiState?.focusMode is com.flowcut.app.ui.timeline.TimelineFocusState.S5_AUDIO_GROUP
            val isAudioItemSelected = vmUiState?.focusMode is com.flowcut.app.ui.timeline.TimelineFocusState.S6_AUDIO_ITEM
            val isAudioActive = isAudioGroupActive || isAudioItemSelected
            val selectedAudioItem = if (isAudioItemSelected) activeOverlayForToolbar else null
            val canSplitAudio = selectedAudioItem != null &&
                currentPlayheadUs >= (selectedAudioItem.startUs + 200_000L) &&
                currentPlayheadUs <= (selectedAudioItem.endUs - 200_000L)

            val isOverlaySelectedForToolbar = vmUiState?.selectedOverlayId != null || isPipActive || isAudioActive

            BottomToolbar(
                isClipSelected = selectedClipIndex != null,
                isOverlaySelected = isOverlaySelectedForToolbar,
                isPipOverlaySelected = isPipOverlay,
                isPipActive = isPipActive,
                isPipItemSelected = isPipItemSelected,
                isPipVideo = isPipVideo,
                canSplitPip = canSplitPip,
                isAudioActive = isAudioActive,
                isAudioItemSelected = isAudioItemSelected,
                canSplitAudio = canSplitAudio,
                isTextOverlaySelected = isTextOverlay,
                isTextToolbarActive = isTextToolbarActive,
                isFilterOverlaySelected = isFilterOverlay,
                isEffectOverlaySelected = isEffectOverlay,
                onEditFilterOverlayClick = {
                    activeOverlayForToolbar?.let { itm ->
                        filterTargetOverlayId = itm.id
                        filterInitialPresetId = itm.filterPresetId
                        filterInitialIntensity = itm.filterIntensity
                        filterIsNewItem = false
                        isFilterSheetVisible = true
                    }
                },
                onEditEffectOverlayClick = {
                    activeOverlayForToolbar?.let { itm ->
                        effectTargetOverlayId = itm.id
                        effectInitialPresetId = itm.effectPresetId
                        effectInitialIntensity = itm.effectIntensity
                        effectIsNewItem = false
                        isEffectSheetVisible = true
                    }
                },
                onAddOverlayClick = openOverlayPicker,
                onAudioClick = {
                    resolvedViewModel?.setFocusMode(TimelineFocusState.S5_AUDIO_GROUP)
                },
                onBackToAudioGroup = {
                    resolvedViewModel?.setFocusMode(TimelineFocusState.S5_AUDIO_GROUP)
                },
                onAudioSoundsClick = {
                    isAudioSoundsSheetVisible = true
                },
                onAudioLocalClick = {
                    openLocalAudioPicker()
                },
                onAudioEffectsClick = {
                    isAudioEffectsSheetVisible = true
                },
                onAudioExtractClick = {
                    openExtractVideoPicker()
                },
                onAudioRecordClick = {
                    isAudioRecordSheetVisible = true
                },
                onAudioFadeClick = {
                    isAudioFadeSheetVisible = true
                },
                onDeleteAudioClick = {
                    resolvedViewModel?.deleteSelectedOverlay()
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onSplitClick = onSplitClick ?: {
                    if (isTextToolbarActive) {
                        val textItem = resolveTextOverlayOrToast()
                        if (textItem != null) {
                            val playheadUs = playbackController.playhead.timeUs
                            resolvedViewModel?.splitOverlay(textItem.id, playheadUs)
                            previewRenderer.requestRender()
                            frameDriver.wake()
                        }
                    } else if (vmUiState?.selectedOverlayId != null) {
                        val playheadUs = playbackController.playhead.timeUs
                        resolvedViewModel?.splitSelectedOverlayAtPlayhead(playheadUs)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    } else {
                        handleSplit()
                    }
                },
                onTrimClick = handleOpenTrim,
                onVolumeClick = {
                    if (isAudioItemSelected || (isPipItemSelected && isPipVideo)) {
                        isAudioVolumeSheetVisible = true
                    } else {
                        isAdjustSheetVisible = false
                        isBackgroundSheetVisible = false
                        isSpeedSheetVisible = false
                        isTrimSheetVisible = false
                        isOpacitySheetVisible = false
                        isVolumeSheetVisible = true
                    }
                },
                onSpeedClick = {
                    if (isAudioItemSelected || (isPipItemSelected && isPipVideo)) {
                        isAudioSpeedSheetVisible = true
                    } else {
                        handleOpenSpeed()
                    }
                },
                onAdjustClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    if (isTextToolbarActive) {
                        val textItem = resolveTextOverlayOrToast()
                        if (textItem != null) {
                            activeTextOverlayId = textItem.id
                            isVolumeSheetVisible = false
                            isSpeedSheetVisible = false
                            isAdjustSheetVisible = false
                            isBackgroundSheetVisible = false
                            isTrimSheetVisible = false
                            isOpacitySheetVisible = false
                            isTextColorSheetVisible = false
                            isTextFontSheetVisible = false
                            isTextAnimationSheetVisible = false
                            isTextAdjustSheetVisible = true
                        }
                    } else {
                        isVolumeSheetVisible = false
                        isSpeedSheetVisible = false
                        isBackgroundSheetVisible = false
                        isTrimSheetVisible = false
                        isOpacitySheetVisible = false
                        isAdjustSheetVisible = true
                    }
                },
                onBackgroundClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    isVolumeSheetVisible = false
                    isSpeedSheetVisible = false
                    isAdjustSheetVisible = false
                    isTrimSheetVisible = false
                    isOpacitySheetVisible = false
                    isBackgroundSheetVisible = true
                },
                onRotateClick = onRotateClip ?: {
                    if (vmUiState?.selectedOverlayId != null) {
                        resolvedViewModel?.rotateSelectedOverlay()
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    } else {
                        resolvedViewModel?.rotateSelectedClip()
                        if (USE_NEW_CORE) {
                            resolvedViewModel?.let { vm ->
                                snapshotHolder.current = vm.getSnapshot()
                            }
                        }
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        triggerApplyToAllPill()
                    }
                },
                onFlipClick = onFlipClip ?: {
                    if (vmUiState?.selectedOverlayId != null) {
                        resolvedViewModel?.flipSelectedOverlay()
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    } else {
                        resolvedViewModel?.flipSelectedClip()
                        if (USE_NEW_CORE) {
                            resolvedViewModel?.let { vm ->
                                snapshotHolder.current = vm.getSnapshot()
                            }
                        }
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        triggerApplyToAllPill()
                    }
                },
                onOpacityClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    if (isTextToolbarActive) {
                        val textItem = resolveTextOverlayOrToast()
                        if (textItem != null) {
                            activeTextOverlayId = textItem.id
                            isVolumeSheetVisible = false
                            isSpeedSheetVisible = false
                            isAdjustSheetVisible = false
                            isBackgroundSheetVisible = false
                            isTrimSheetVisible = false
                            isOpacitySheetVisible = false
                            isTextColorSheetVisible = false
                            isTextFontSheetVisible = false
                            isTextAnimationSheetVisible = false
                            isTextAdjustSheetVisible = true
                        }
                    } else {
                        isVolumeSheetVisible = false
                        isSpeedSheetVisible = false
                        isAdjustSheetVisible = false
                        isBackgroundSheetVisible = false
                        isTrimSheetVisible = false
                        isOpacitySheetVisible = true
                    }
                },
                onCopyClick = {
                    if (isTextToolbarActive) {
                        val textItem = resolveTextOverlayOrToast()
                        if (textItem != null) {
                            resolvedViewModel?.copySelectedOverlay()
                            previewRenderer.requestRender()
                            frameDriver.wake()
                        }
                    } else if (vmUiState?.selectedOverlayId != null) {
                        resolvedViewModel?.copySelectedOverlay()
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    } else if (selectedClipIndex != null) {
                        val result = resolvedViewModel?.duplicateClip(selectedClipIndex)
                        if (result != null) {
                            if (USE_NEW_CORE) {
                                snapshotHolder.current = result.newSnapshot
                            }
                            playbackController.seekTo(msToUs(result.newStartMs))
                            previewRenderer.requestRender()
                            frameDriver.wake()
                        }
                    }
                },
                onReplaceClick = {
                    if (selectedClipIndex != null) {
                        openReplacePicker()
                    }
                },
                onFreezeClick = {
                    if (selectedClipIndex != null) {
                        val playheadUs = playbackController.playhead.timeUs
                        val result = resolvedViewModel?.freezeSelectedClip(playheadUs)
                        if (result != null) {
                            if (USE_NEW_CORE) {
                                snapshotHolder.current = result.newSnapshot
                            }
                            playbackController.seekTo(msToUs(result.freezeStartMs))
                            previewRenderer.requestRender()
                            frameDriver.wake()
                        }
                    }
                },
                onDeleteClipClick = if (isTextToolbarActive) {
                    {
                        val textItem = resolveTextOverlayOrToast()
                        if (textItem != null) {
                            resolvedViewModel?.removeLayerItem(textItem.id)
                            previewRenderer.requestRender()
                            frameDriver.wake()
                        }
                    }
                } else if (vmUiState?.selectedOverlayId != null) {
                    {
                        resolvedViewModel?.deleteSelectedOverlay()
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    }
                } else selectedClipIndex?.let { k ->
                    {
                        onDeleteClip(k)
                        if (USE_NEW_CORE) {
                            resolvedViewModel?.let { vm ->
                                snapshotHolder.current = vm.getSnapshot()
                                frameDriver.wake()
                            }
                        }
                    }
                },
                onClearSelection = {
                    isTextToolbarActive = false
                    resolvedViewModel?.setFocusMode(TimelineFocusState.S0_NONE)
                    onDeselectAll()
                },
                onLockedItemClick = {
                    Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                },
                onTextClick = {
                    isTextToolbarActive = true
                },
                onAddTextClick = handleOpenText,
                onStickerClick = handleOpenSticker,
                onImageClick = openOverlayPicker,
                onOverlayClick = openOverlayPicker,
                onEditTextOverlayClick = {
                    val textItem = resolveTextOverlayOrToast()
                    if (textItem != null) {
                        if (playbackController.playhead.mode == PlayMode.PLAYING) {
                            playbackController.pause()
                            frameDriver.wake()
                            onPlaybackStateChange(false)
                        }
                        isNewTextOverlay = false
                        editingTextOverlayId = textItem.id
                        isVolumeSheetVisible = false
                        isSpeedSheetVisible = false
                        isAdjustSheetVisible = false
                        isBackgroundSheetVisible = false
                        isTrimSheetVisible = false
                        isOpacitySheetVisible = false
                        isTextColorSheetVisible = false
                        isTextFontSheetVisible = false
                        isTextAnimationSheetVisible = false
                        isTextAdjustSheetVisible = false
                        isTextSheetVisible = true
                    }
                },
                onFontClick = {
                    val textItem = resolveTextOverlayOrToast()
                    if (textItem != null) {
                        if (playbackController.playhead.mode == PlayMode.PLAYING) {
                            playbackController.pause()
                            frameDriver.wake()
                            onPlaybackStateChange(false)
                        }
                        activeTextOverlayId = textItem.id
                        isVolumeSheetVisible = false
                        isSpeedSheetVisible = false
                        isAdjustSheetVisible = false
                        isBackgroundSheetVisible = false
                        isTrimSheetVisible = false
                        isOpacitySheetVisible = false
                        isTextColorSheetVisible = false
                        isTextAnimationSheetVisible = false
                        isTextAdjustSheetVisible = false
                        isTextFontSheetVisible = true
                    }
                },
                onColorClick = {
                    val textItem = resolveTextOverlayOrToast()
                    if (textItem != null) {
                        if (playbackController.playhead.mode == PlayMode.PLAYING) {
                            playbackController.pause()
                            frameDriver.wake()
                            onPlaybackStateChange(false)
                        }
                        activeTextOverlayId = textItem.id
                        isVolumeSheetVisible = false
                        isSpeedSheetVisible = false
                        isAdjustSheetVisible = false
                        isBackgroundSheetVisible = false
                        isTrimSheetVisible = false
                        isOpacitySheetVisible = false
                        isTextFontSheetVisible = false
                        isTextAnimationSheetVisible = false
                        isTextAdjustSheetVisible = false
                        isTextColorSheetVisible = true
                    }
                },
                onAnimationClick = {
                    val overlayId = if (isPipItemSelected) selectedPipItem?.id else resolveTextOverlayOrToast()?.id
                    if (overlayId != null) {
                        if (playbackController.playhead.mode == PlayMode.PLAYING) {
                            playbackController.pause()
                            frameDriver.wake()
                            onPlaybackStateChange(false)
                        }
                        activeTextOverlayId = overlayId
                        isVolumeSheetVisible = false
                        isSpeedSheetVisible = false
                        isAdjustSheetVisible = false
                        isBackgroundSheetVisible = false
                        isTrimSheetVisible = false
                        isOpacitySheetVisible = false
                        isTextColorSheetVisible = false
                        isTextFontSheetVisible = false
                        isTextAdjustSheetVisible = false
                        isTextAnimationSheetVisible = true
                    }
                },
                onFilterClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    val curLayers = resolvedViewModel?.layerItems ?: emptyList()
                    val curSelId = vmUiState?.selectedOverlayId
                    val selItem = curLayers.firstOrNull { it.id == curSelId }
                    val playheadUs = playbackController.playhead.timeUs
                    val targetItem = if (selItem?.type == com.flowcut.app.engine.LayerType.FILTER) {
                        selItem
                    } else {
                        curLayers.firstOrNull { it.type == com.flowcut.app.engine.LayerType.FILTER && playheadUs in it.startUs..it.endUs }
                    }
                    if (targetItem != null) {
                        resolvedViewModel?.selectOverlay(targetItem.id)
                        filterTargetOverlayId = targetItem.id
                        filterInitialPresetId = targetItem.filterPresetId
                        filterInitialIntensity = targetItem.filterIntensity
                        filterIsNewItem = false
                    } else {
                        filterTargetOverlayId = null
                        filterInitialPresetId = 1
                        filterInitialIntensity = 0.8f
                        filterIsNewItem = true
                    }
                    isVolumeSheetVisible = false
                    isSpeedSheetVisible = false
                    isAdjustSheetVisible = false
                    isBackgroundSheetVisible = false
                    isTrimSheetVisible = false
                    isOpacitySheetVisible = false
                    isEffectSheetVisible = false
                    isFilterSheetVisible = true
                },
                onEffectClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    val curLayers = resolvedViewModel?.layerItems ?: emptyList()
                    val curSelId = vmUiState?.selectedOverlayId
                    val selItem = curLayers.firstOrNull { it.id == curSelId }
                    val playheadUs = playbackController.playhead.timeUs
                    val targetItem = if (selItem?.type == com.flowcut.app.engine.LayerType.EFFECT) {
                        selItem
                    } else {
                        curLayers.firstOrNull { it.type == com.flowcut.app.engine.LayerType.EFFECT && playheadUs in it.startUs..it.endUs }
                    }
                    if (targetItem != null) {
                        resolvedViewModel?.selectOverlay(targetItem.id)
                        effectTargetOverlayId = targetItem.id
                        effectInitialPresetId = targetItem.effectPresetId
                        effectInitialIntensity = targetItem.effectIntensity
                        effectIsNewItem = false
                    } else {
                        effectTargetOverlayId = null
                        effectInitialPresetId = 1
                        effectInitialIntensity = 0.7f
                        effectIsNewItem = true
                    }
                    isVolumeSheetVisible = false
                    isSpeedSheetVisible = false
                    isAdjustSheetVisible = false
                    isBackgroundSheetVisible = false
                    isTrimSheetVisible = false
                    isOpacitySheetVisible = false
                    isFilterSheetVisible = false
                    isEffectSheetVisible = true
                }
            )
        }

        // Duration Sheet
        if (durationSheetVisible && selectedJunctionIndex != null) {
            val j = selectedJunctionIndex
            val transition = timeline.project.transitions.getOrNull(j)
            if (transition != null && transition.type != TransitionType.NONE) {
                DurationSheet(
                    transitionType = transition.type,
                    currentDurationMs = transition.durationMs,
                    maxDurationMs = timeline.maxDuration(j),
                    onDurationChange = onUpdateTransitionDuration,
                    onDurationChangeFinished = onCommitTransitionDuration,
                    onRemoveTransition = onRemoveTransition,
                    onDismiss = onCloseDurationSheet
                )
            }
        }

        // Export Sheet
        if (exportSheetVisible) {
            ExportSheet(
                state = exportState,
                totalDurationMs = timeline.total,
                onStartExport = onStartExport,
                onCancelExport = onCancelExport,
                onShare = onShareExport,
                onDismiss = onCloseExportSheet
            )
        }

        // Trim BottomSheet / Panel for selected clip OR global timeline trim
        if (isTrimSheetVisible) {
            val activeTrimIndex = trimTargetClipIndex
            TrimSheet(
                clipIndex = activeTrimIndex,
                snapshotHolder = snapshotHolder,
                previewRenderer = previewRenderer,
                frameDriver = frameDriver,
                getClipNaturalDurationUs = { k ->
                    resolvedViewModel?.getClipNaturalDurationUs(k)
                        ?: run {
                            val clip = snapshotHolder.current.project.clips.getOrNull(k)
                            clip?.let { c ->
                                try {
                                    val file = java.io.File(c.path)
                                    if (file.exists()) {
                                        val retriever = android.media.MediaMetadataRetriever()
                                        retriever.setDataSource(file.absolutePath)
                                        val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                        retriever.release()
                                        durStr?.toLongOrNull()?.let { msToUs(it) } ?: msToUs(c.durationMs)
                                    } else msToUs(c.durationMs)
                                } catch (_: Exception) {
                                    msToUs(c.durationMs)
                                }
                            } ?: 0L
                        }
                },
                onCommitTrim = { k, inUs, outUs ->
                    val newDurMs = usToMs(outUs - inUs)
                    resolvedViewModel?.commitClipTrim(k, inUs, outUs)
                    onCommitTrim(k, inUs, outUs, newDurMs)
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                        frameDriver.wake()
                    }
                },
                onCommitGlobalTrim = { startUs, endUs ->
                    resolvedViewModel?.commitGlobalTimelineTrim(startUs, endUs)
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                        frameDriver.wake()
                    }
                    previewRenderer.requestRender()
                },
                onDismiss = {
                    isTrimSheetVisible = false
                }
            )
        }

        // Volume BottomSheet (0% to 200%) for selected clip
        if (isVolumeSheetVisible && selectedClipIndex != null) {
            VolumeSheet(
                clipIndex = selectedClipIndex,
                snapshotHolder = snapshotHolder,
                getClipVolume = { id -> resolvedViewModel?.getClipVolume(id) ?: 1.0f },
                getPreMuteVolume = { id -> resolvedViewModel?.getPreMuteVolume(id) ?: 1.0f },
                onSetLiveVolume = { id, vol -> ClipAudioContributor.setClipVolume(id, vol) },
                onCommitSingleVolume = { idx, vol -> resolvedViewModel?.commitClipVolume(idx, vol) },
                onCommitApplyAllVolume = { vol -> resolvedViewModel?.commitApplyVolumeToAll(vol) },
                onDismiss = { isVolumeSheetVisible = false }
            )
        }

        // Speed BottomSheet (0.2x to 10.0x) for selected clip
        if (isSpeedSheetVisible && selectedClipIndex != null) {
            val currentProject = vmUiState?.project ?: com.flowcut.app.model.Project()
            val initialProject = remember(selectedClipIndex) { currentProject }
            val initialTimeMaps = remember(selectedClipIndex) { HashMap(resolvedViewModel?.clipTimeMaps ?: emptyMap()) }

            SpeedSheet(
                clipIndex = selectedClipIndex,
                snapshotHolder = snapshotHolder,
                previewRenderer = previewRenderer,
                onWakeFrameDriver = { frameDriver.wake() },
                getClipSpeed = { id -> resolvedViewModel?.getClipSpeed(id) ?: 1.0f },
                getMaxSpeedForClip = { k -> resolvedViewModel?.getMaxSpeedForClip(k) ?: 10.0f },
                onSetLiveSpeed = { k, spd ->
                    resolvedViewModel?.setLiveClipSpeed(k, spd)
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                    }
                },
                onCommitSingleSpeed = { k, spd, initProj, initMaps ->
                    resolvedViewModel?.commitClipSpeed(k, spd, initProj, initMaps)
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                        frameDriver.wake()
                    }
                },
                onCommitApplyAllSpeed = { spd, initProj, initMaps ->
                    resolvedViewModel?.commitApplySpeedToAll(spd, initProj, initMaps)
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                        frameDriver.wake()
                    }
                },
                onRevertSpeed = { initProj, initMaps ->
                    resolvedViewModel?.revertSpeedEdit(initProj, initMaps)
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                        frameDriver.wake()
                    }
                },
                initialProject = initialProject,
                initialTimeMaps = initialTimeMaps,
                onDismiss = { isSpeedSheetVisible = false }
            )
        }

        // Color Adjust BottomSheet (CapCut/VN style) for selected clip
        if (isAdjustSheetVisible && selectedClipIndex != null) {
            AdjustSheet(
                clipIndex = selectedClipIndex,
                snapshotHolder = snapshotHolder,
                previewRenderer = previewRenderer,
                onWakeFrameDriver = { frameDriver.wake() },
                getClipColorAdjust = { id -> resolvedViewModel?.getClipColorAdjust(id) ?: ClipColorAdjust.DEFAULT },
                onCommitSingleAdjust = { idx, adj ->
                    resolvedViewModel?.commitClipColorAdjust(idx, adj)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onCommitApplyAllAdjust = { adj ->
                    resolvedViewModel?.commitApplyColorAdjustToAll(adj)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onDismiss = { isAdjustSheetVisible = false }
            )
        }

        // Canvas Background BottomSheet (CapCut/VN style) for selected clip
        if (isBackgroundSheetVisible && selectedClipIndex != null) {
            BackgroundSheet(
                clipIndex = selectedClipIndex,
                snapshotHolder = snapshotHolder,
                previewRenderer = previewRenderer,
                onWakeFrameDriver = { frameDriver.wake() },
                getClipBackground = { id -> resolvedViewModel?.getClipBackground(id) ?: ClipBackground.DEFAULT },
                onCommitSingleBackground = { idx, bg ->
                    resolvedViewModel?.commitClipBackground(idx, bg)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onCommitApplyAllBackground = { bg ->
                    resolvedViewModel?.commitApplyBackgroundToAll(bg)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onDismiss = { isBackgroundSheetVisible = false }
            )
        }

        // Filter Pin Layer BottomSheet
        if (isFilterSheetVisible) {
            FilterPinLayerSheet(
                targetOverlayId = filterTargetOverlayId,
                initialPresetId = filterInitialPresetId,
                initialIntensity = filterInitialIntensity,
                isNewItem = filterIsNewItem,
                playheadUs = playbackController.playhead.timeUs,
                resolvedViewModel = resolvedViewModel,
                previewRenderer = previewRenderer,
                onWakeFrameDriver = { frameDriver.wake() },
                onDismiss = { isFilterSheetVisible = false }
            )
        }

        // Effect Pin Layer BottomSheet
        if (isEffectSheetVisible) {
            EffectPinLayerSheet(
                targetOverlayId = effectTargetOverlayId,
                initialPresetId = effectInitialPresetId,
                initialIntensity = effectInitialIntensity,
                isNewItem = effectIsNewItem,
                playheadUs = playbackController.playhead.timeUs,
                resolvedViewModel = resolvedViewModel,
                previewRenderer = previewRenderer,
                onWakeFrameDriver = { frameDriver.wake() },
                onDismiss = { isEffectSheetVisible = false }
            )
        }

        // Opacity BottomSheet (0% to 100%) for selected clip
        if (isOpacitySheetVisible && selectedClipIndex != null) {
            OpacitySheet(
                clipIndex = selectedClipIndex,
                snapshotHolder = snapshotHolder,
                getClipOpacity = { id ->
                    resolvedViewModel?.getClipOpacity(id) ?: PreviewRenderer.getClipOpacity(id)
                },
                onSetLiveOpacity = { id, opacity ->
                    resolvedViewModel?.setClipOpacityLive(id, opacity)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onCommitSingleOpacity = { idx, opacity ->
                    resolvedViewModel?.commitClipOpacity(idx, opacity)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onCommitApplyAllOpacity = { opacity ->
                    resolvedViewModel?.applyOpacityToAll(opacity)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                    triggerApplyToAllPill()
                },
                onDismiss = { isOpacitySheetVisible = false }
            )
        }

        // Opacity BottomSheet (0% to 100%) for selected overlay item
        if (isOpacitySheetVisible && selectedClipIndex == null && vmUiState?.selectedOverlayId != null) {
            val activeOverlayId = vmUiState.selectedOverlayId
            val activeOverlay = resolvedViewModel?.getOverlayItem(activeOverlayId)
            if (activeOverlay != null) {
                val origOpacity = remember(activeOverlayId) { activeOverlay.transform.opacity }
                var sliderOpacity by remember(activeOverlayId) { mutableFloatStateOf(origOpacity) }
                var isCommitted by remember { mutableStateOf(false) }

                BackHandler {
                    if (!isCommitted) {
                        resolvedViewModel?.setOverlayOpacityLive(activeOverlayId, origOpacity)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    }
                    isOpacitySheetVisible = false
                }

                DisposableEffect(activeOverlayId) {
                    onDispose {
                        if (!isCommitted) {
                            resolvedViewModel?.setOverlayOpacityLive(activeOverlayId, origOpacity)
                            previewRenderer.requestRender()
                            frameDriver.wake()
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = colors.surface1,
                    shadowElevation = 16.dp,
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        // Header: Close (✕), Title + live %, Reset (100%), Done (✓)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = {
                                    resolvedViewModel?.setOverlayOpacityLive(activeOverlayId, origOpacity)
                                    previewRenderer.requestRender()
                                    frameDriver.wake()
                                    isOpacitySheetVisible = false
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = colors.textSecondary
                                )
                            }

                            val percent = (sliderOpacity * 100f).roundToInt()
                            Text(
                                text = "Opacity $percent%",
                                color = colors.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        sliderOpacity = 1.0f
                                        resolvedViewModel?.setOverlayOpacityLive(activeOverlayId, 1.0f)
                                        previewRenderer.requestRender()
                                        frameDriver.wake()
                                    }
                                ) {
                                    Text(
                                        text = "Reset",
                                        color = colors.accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        isCommitted = true
                                        resolvedViewModel?.commitOverlayOpacity(activeOverlayId, sliderOpacity)
                                        previewRenderer.requestRender()
                                        frameDriver.wake()
                                        isOpacitySheetVisible = false
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Done",
                                        tint = colors.accent
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Slider: 0% to 100%
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "0%",
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Slider(
                                value = sliderOpacity,
                                onValueChange = { newOp ->
                                    sliderOpacity = newOp
                                    resolvedViewModel?.setOverlayOpacityLive(activeOverlayId, newOp)
                                    previewRenderer.requestRender()
                                    frameDriver.wake()
                                },
                                onValueChangeFinished = {
                                    resolvedViewModel?.commitOverlayOpacity(activeOverlayId, sliderOpacity)
                                    previewRenderer.requestRender()
                                    frameDriver.wake()
                                },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = colors.accent,
                                    activeTrackColor = colors.accent,
                                    inactiveTrackColor = colors.surface2
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = "100%",
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Text Editor BottomSheet (Phase 3 Overlays)
        if (isTextSheetVisible && editingTextOverlayId != null) {
            val targetId = editingTextOverlayId!!
            val textItem = resolvedViewModel?.getOverlayItem(targetId)
            if (textItem != null) {
                val origText = remember(targetId) { textItem.text }
                val origColor = remember(targetId) { textItem.textColorArgb }
                val origStyle = remember(targetId) { textItem.bgStyle }

                TextEditorSheet(
                    initialText = textItem.text,
                    initialColorArgb = textItem.textColorArgb,
                    initialBgStyle = textItem.bgStyle,
                    onLiveChange = { txt, col, sty ->
                        resolvedViewModel?.liveUpdateTextOverlay(targetId, txt, col, sty)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    },
                    onDone = { txt, col, sty ->
                        resolvedViewModel?.updateTextOverlay(targetId, txt, col, sty)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        isTextSheetVisible = false
                        editingTextOverlayId = null
                        isNewTextOverlay = false
                    },
                    onCancel = {
                        if (isNewTextOverlay) {
                            resolvedViewModel?.removeLayerItem(targetId)
                        } else {
                            resolvedViewModel?.liveUpdateTextOverlay(targetId, origText, origColor, origStyle)
                        }
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        isTextSheetVisible = false
                        editingTextOverlayId = null
                        isNewTextOverlay = false
                    }
                )
            } else {
                isTextSheetVisible = false
                editingTextOverlayId = null
                isNewTextOverlay = false
            }
        }

        // Sticker Picker BottomSheet (Phase 3 Overlays)
        if (isStickerSheetVisible) {
            StickerPickerSheet(
                onSelectSticker = { glyph ->
                    val playheadUs = playbackController.playhead.timeUs
                    resolvedViewModel?.addStickerOverlay(glyph, playheadUs, 3_000_000L)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                    isStickerSheetVisible = false
                },
                onAddImageClick = {
                    isStickerSheetVisible = false
                    try {
                        stickerImagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    } catch (_: Exception) {
                        try {
                            overlayFallbackLauncher.launch("image/*")
                        } catch (_: Exception) {
                            Toast.makeText(context, "Cannot open image picker", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = { isStickerSheetVisible = false }
            )
        }

        // Text Color BottomSheet (Phase 3 Typography Suite)
        if (isTextColorSheetVisible && activeTextOverlayId != null) {
            val targetId = activeTextOverlayId!!
            val textItem = resolvedViewModel?.getOverlayItem(targetId)
            if (textItem != null) {
                val origTextColor = remember(targetId) { textItem.textColorArgb }
                val origTextAlpha = remember(targetId) { textItem.textAlpha }
                val origLabelColor = remember(targetId) { textItem.labelColorArgb }
                val origLabelAlpha = remember(targetId) { textItem.labelAlpha }
                val origBorderColor = remember(targetId) { textItem.borderColorArgb }
                val origBorderAlpha = remember(targetId) { textItem.borderAlpha }
                val origShadowColor = remember(targetId) { textItem.shadowColorArgb }
                val origShadowAlpha = remember(targetId) { textItem.shadowAlpha }

                TextColorSheet(
                    initialTextColor = textItem.textColorArgb,
                    initialTextAlpha = textItem.textAlpha,
                    initialLabelColor = textItem.labelColorArgb,
                    initialLabelAlpha = textItem.labelAlpha,
                    initialBorderColor = textItem.borderColorArgb,
                    initialBorderAlpha = textItem.borderAlpha,
                    initialShadowColor = textItem.shadowColorArgb,
                    initialShadowAlpha = textItem.shadowAlpha,
                    onLiveChange = { tc, ta, lc, la, bc, ba, sc, sa ->
                        resolvedViewModel?.liveUpdateTextColorAndAlpha(targetId, tc, ta, lc, la, bc, ba, sc, sa)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    },
                    onDone = { tc, ta, lc, la, bc, ba, sc, sa ->
                        resolvedViewModel?.updateTextColorAndAlpha(targetId, tc, ta, lc, la, bc, ba, sc, sa)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        isTextColorSheetVisible = false
                        activeTextOverlayId = null
                    },
                    onCancel = {
                        resolvedViewModel?.liveUpdateTextColorAndAlpha(
                            targetId,
                            origTextColor, origTextAlpha,
                            origLabelColor, origLabelAlpha,
                            origBorderColor, origBorderAlpha,
                            origShadowColor, origShadowAlpha
                        )
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        isTextColorSheetVisible = false
                        activeTextOverlayId = null
                    }
                )
            } else {
                isTextColorSheetVisible = false
                activeTextOverlayId = null
            }
        }

        // Text Font BottomSheet (Phase 3 Typography Suite)
        if (isTextFontSheetVisible && activeTextOverlayId != null) {
            val targetId = activeTextOverlayId!!
            val textItem = resolvedViewModel?.getOverlayItem(targetId)
            if (textItem != null) {
                val origFontName = remember(targetId) { textItem.fontName }
                val origCustomPath = remember(targetId) { textItem.customFontPath }

                TextFontSheet(
                    initialFontName = textItem.fontName,
                    initialCustomFontPath = textItem.customFontPath,
                    customFonts = customFontPaths,
                    onPickCustomFont = {
                        fontPickerLauncher.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "*/*"))
                    },
                    onLiveChange = { fontName, customFontPath ->
                        resolvedViewModel?.updateTextFont(targetId, fontName, customFontPath)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    },
                    onDone = { fontName, customFontPath ->
                        resolvedViewModel?.updateTextFont(targetId, fontName, customFontPath)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        isTextFontSheetVisible = false
                        activeTextOverlayId = null
                    },
                    onCancel = {
                        resolvedViewModel?.updateTextFont(targetId, origFontName, origCustomPath)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        isTextFontSheetVisible = false
                        activeTextOverlayId = null
                    }
                )
            } else {
                isTextFontSheetVisible = false
                activeTextOverlayId = null
            }
        }

        // Text Animation BottomSheet (Phase 3 Typography Suite)
        if (isTextAnimationSheetVisible && activeTextOverlayId != null) {
            val targetId = activeTextOverlayId!!
            val textItem = resolvedViewModel?.getOverlayItem(targetId)
            if (textItem != null) {
                val origInType = remember(targetId) { textItem.animInType }
                val origInDur = remember(targetId) { textItem.animInDurationUs }
                val origOutType = remember(targetId) { textItem.animOutType }
                val origOutDur = remember(targetId) { textItem.animOutDurationUs }
                val origLoopType = remember(targetId) { textItem.animLoopType }

                TextAnimationSheet(
                    initialAnimInType = textItem.animInType,
                    initialAnimInDurationUs = textItem.animInDurationUs,
                    initialAnimOutType = textItem.animOutType,
                    initialAnimOutDurationUs = textItem.animOutDurationUs,
                    initialAnimLoopType = textItem.animLoopType,
                    onLiveChange = { inType, inDur, outType, outDur, loopType ->
                        resolvedViewModel?.liveUpdateTextAnimation(targetId, inType, inDur, outType, outDur, loopType)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    },
                    onDone = { inType, inDur, outType, outDur, loopType ->
                        resolvedViewModel?.updateTextAnimation(targetId, inType, inDur, outType, outDur, loopType)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        isTextAnimationSheetVisible = false
                        activeTextOverlayId = null
                    },
                    onCancel = {
                        resolvedViewModel?.liveUpdateTextAnimation(targetId, origInType, origInDur, origOutType, origOutDur, origLoopType)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        isTextAnimationSheetVisible = false
                        activeTextOverlayId = null
                    }
                )
            } else {
                isTextAnimationSheetVisible = false
                activeTextOverlayId = null
            }
        }

        // Text Adjust BottomSheet (Phase 3 Typography Suite)
        if (isTextAdjustSheetVisible && activeTextOverlayId != null) {
            val targetId = activeTextOverlayId!!
            val textItem = resolvedViewModel?.getOverlayItem(targetId)
            if (textItem != null) {
                val origAlign = remember(targetId) { textItem.textAlign }
                val origLetter = remember(targetId) { textItem.letterSpacing }
                val origLine = remember(targetId) { textItem.lineSpacing }
                val origAlpha = remember(targetId) { textItem.textAlpha }
                val origBold = remember(targetId) { textItem.isBold }
                val origItalic = remember(targetId) { textItem.isItalic }
                val origUnderline = remember(targetId) { textItem.isUnderline }
                val origUppercase = remember(targetId) { textItem.isUppercase }

                TextAdjustSheet(
                    initialTextAlign = textItem.textAlign,
                    initialLetterSpacing = textItem.letterSpacing,
                    initialLineSpacing = textItem.lineSpacing,
                    initialTextAlpha = textItem.textAlpha,
                    initialIsBold = textItem.isBold,
                    initialIsItalic = textItem.isItalic,
                    initialIsUnderline = textItem.isUnderline,
                    initialIsUppercase = textItem.isUppercase,
                    onLiveChange = { align, letter, line, alpha, bold, italic, underline, uppercase ->
                        resolvedViewModel?.liveUpdateTextAdjust(targetId, align, letter, line, alpha, bold, italic, underline, uppercase)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    },
                    onDone = { align, letter, line, alpha, bold, italic, underline, uppercase ->
                        resolvedViewModel?.updateTextAdjust(targetId, align, letter, line, alpha, bold, italic, underline, uppercase)
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        isTextAdjustSheetVisible = false
                        activeTextOverlayId = null
                    },
                    onCancel = {
                        resolvedViewModel?.liveUpdateTextAdjust(
                            targetId,
                            origAlign, origLetter, origLine, origAlpha,
                            origBold, origItalic, origUnderline, origUppercase
                        )
                        previewRenderer.requestRender()
                        frameDriver.wake()
                        isTextAdjustSheetVisible = false
                        activeTextOverlayId = null
                    }
                )
            } else {
                isTextAdjustSheetVisible = false
                activeTextOverlayId = null
            }
        }

        // Overlapping Layer Conflict Picker Dialog (Matching reference image_2d9184.png)
        if (overlappingConflictItemIds != null) {
            val conflictIds = overlappingConflictItemIds!!
            val allLayers = resolvedViewModel?.layerItems ?: emptyList()
            val conflictItems = remember(conflictIds, allLayers) {
                conflictIds.mapNotNull { id -> allLayers.firstOrNull { it.id == id } }
            }

            if (conflictItems.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { overlappingConflictItemIds = null },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF353538),
                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { /* Prevent dismissing when clicking the card itself */ }
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val rows = conflictItems.chunked(5)
                            rows.forEach { rowItems ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    rowItems.forEach { item ->
                                        val isSelected = item.id == vmUiState?.selectedOverlayId
                                        val isText = item.type == com.flowcut.app.engine.LayerType.TEXT
                                        val isFilter = item.type == com.flowcut.app.engine.LayerType.FILTER
                                        val isEffect = item.type == com.flowcut.app.engine.LayerType.EFFECT
                                        val badgeBg = when (item.type) {
                                            com.flowcut.app.engine.LayerType.TEXT -> Color(0xFFF59E0B)
                                            com.flowcut.app.engine.LayerType.FILTER -> Color(0xFF06B6D4) // Cyan
                                            com.flowcut.app.engine.LayerType.EFFECT -> Color(0xFFA855F7) // Purple
                                            else -> Color(0xFF68C95B) // Green
                                        }

                                        Surface(
                                            modifier = Modifier
                                                .size(width = 48.dp, height = 34.dp)
                                                .clickable {
                                                    resolvedViewModel?.selectOverlay(item.id)
                                                    overlappingConflictItemIds = null
                                                },
                                            shape = RoundedCornerShape(4.dp),
                                            color = badgeBg,
                                            border = if (isSelected) BorderStroke(2.dp, Color.White) else null
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isText) {
                                                    val textSnippet = if (item.text.isNotBlank()) {
                                                        item.text.take(5)
                                                    } else "Text"
                                                    Text(
                                                        text = textSnippet,
                                                        color = Color.White,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1
                                                    )
                                                } else if (isFilter) {
                                                    Text(
                                                        text = "F",
                                                        color = Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                } else if (isEffect) {
                                                    Text(
                                                        text = "E",
                                                        color = Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                } else {
                                                    if (item.type == com.flowcut.app.engine.LayerType.STICKER && item.text.isNotBlank() && item.text.length <= 4) {
                                                        Text(
                                                            text = item.text,
                                                            fontSize = 14.sp
                                                        )
                                                    } else {
                                                        Canvas(modifier = Modifier.size(20.dp, 16.dp)) {
                                                            val strokeW = 1.4.dp.toPx()
                                                            drawRoundRect(
                                                                color = Color.White,
                                                                topLeft = Offset.Zero,
                                                                size = size,
                                                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                                                                style = Stroke(width = strokeW)
                                                            )
                                                            drawCircle(
                                                                color = Color.White,
                                                                radius = 2.dp.toPx(),
                                                                center = Offset(5.5.dp.toPx(), 5.dp.toPx())
                                                            )
                                                            val mountainPath = Path().apply {
                                                                moveTo(3.dp.toPx(), size.height - 3.dp.toPx())
                                                                lineTo(9.dp.toPx(), 8.dp.toPx())
                                                                lineTo(13.dp.toPx(), 11.dp.toPx())
                                                                lineTo(17.dp.toPx(), size.height - 3.dp.toPx())
                                                            }
                                                            drawPath(mountainPath, color = Color.White, style = Stroke(width = strokeW))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // -----------------------------------------------------------------------------------------
        // Audio BottomSheets (Phase C: 4-Row Audio System)
        // -----------------------------------------------------------------------------------------
        val currentAudioItem = vmUiState?.selectedOverlayId?.let { resolvedViewModel?.getOverlayItem(it) }

        if (isAudioSoundsSheetVisible) {
            AudioSoundsSheet(
                context = context,
                onAddSound = { path, title ->
                    val vm = resolvedViewModel ?: return@AudioSoundsSheet
                    val playheadUs = playbackController.playhead.timeUs
                    val totalUs = msToUs(vm.uiState.value.project.clips.sumOf { it.durationMs })
                    val remainingUs = (totalUs - playheadUs).coerceAtLeast(1_000_000L)
                    val durUs = minOf(5_000_000L, remainingUs).coerceAtLeast(com.flowcut.app.engine.OverlayItem.MIN_DURATION_US)
                    vm.addAudioOverlay(
                        mediaPath = path,
                        title = title,
                        startUs = playheadUs,
                        durationUs = durUs,
                        audioSubType = 0
                    )
                    vm.setFocusMode(TimelineFocusState.S5_AUDIO_GROUP)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                    isAudioSoundsSheetVisible = false
                },
                onDismiss = { isAudioSoundsSheetVisible = false }
            )
        }

        if (isAudioEffectsSheetVisible) {
            AudioEffectsSheet(
                context = context,
                onAddEffect = { path, title, durSec ->
                    val vm = resolvedViewModel ?: return@AudioEffectsSheet
                    val playheadUs = playbackController.playhead.timeUs
                    val durUs = (durSec * 1_000_000L).toLong().coerceAtLeast(com.flowcut.app.engine.OverlayItem.MIN_DURATION_US)
                    vm.addAudioOverlay(
                        mediaPath = path,
                        title = title,
                        startUs = playheadUs,
                        durationUs = durUs,
                        audioSubType = 0
                    )
                    vm.setFocusMode(TimelineFocusState.S5_AUDIO_GROUP)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                    isAudioEffectsSheetVisible = false
                },
                onDismiss = { isAudioEffectsSheetVisible = false }
            )
        }

        if (isAudioRecordSheetVisible) {
            AudioVoiceRecordSheet(
                context = context,
                onAddRecording = { path, durUs ->
                    val vm = resolvedViewModel ?: return@AudioVoiceRecordSheet
                    val playheadUs = playbackController.playhead.timeUs
                    vm.addAudioOverlay(
                        mediaPath = path,
                        title = "Voiceover",
                        startUs = playheadUs,
                        durationUs = durUs,
                        audioSubType = 1
                    )
                    vm.setFocusMode(TimelineFocusState.S5_AUDIO_GROUP)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                    isAudioRecordSheetVisible = false
                },
                onDismiss = { isAudioRecordSheetVisible = false }
            )
        }

        if (isAudioFadeSheetVisible && currentAudioItem != null) {
            AudioFadeSheet(
                item = currentAudioItem,
                onUpdateFade = { inUs, outUs ->
                    resolvedViewModel?.updateAudioFade(currentAudioItem.id, inUs, outUs)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onDismiss = { isAudioFadeSheetVisible = false }
            )
        }

        if (isAudioVolumeSheetVisible && currentAudioItem != null) {
            AudioVolumeSheet(
                item = currentAudioItem,
                onUpdateVolume = { vol ->
                    resolvedViewModel?.updateAudioVolume(currentAudioItem.id, vol)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onDismiss = { isAudioVolumeSheetVisible = false }
            )
        }

        if (isAudioSpeedSheetVisible && currentAudioItem != null) {
            AudioSpeedSheet(
                item = currentAudioItem,
                onUpdateSpeed = { spd ->
                    resolvedViewModel?.updateAudioSpeed(currentAudioItem.id, spd)
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onDismiss = { isAudioSpeedSheetVisible = false }
            )
        }

        if (showCoverDialog) {
            CoverSelectionSheet(
                onSelectCurrentFrame = {
                    val playheadUs = playbackController.playhead.timeUs
                    resolvedViewModel?.setCoverFromTimelineFrame(playheadUs)
                    resolvedViewModel?.showToast("Cover set from current frame")
                    showCoverDialog = false
                },
                onChooseGallery = {
                    try {
                        galleryCoverPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    } catch (_: Exception) {
                        Toast.makeText(context, "Cannot open image picker", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { showCoverDialog = false },
                currentPlayheadTimeFormatted = formatTimeMs(usToMs(playbackController.playhead.timeUs))
            )
        }
    }
}

@Composable
private fun CoverSelectionSheet(
    onSelectCurrentFrame: () -> Unit,
    onChooseGallery: () -> Unit,
    onDismiss: () -> Unit,
    currentPlayheadTimeFormatted: String
) {
    val colors = FlowCutTheme.colors

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Cover",
                        color = colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Option 1: Use Current Frame
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface1, RoundedCornerShape(10.dp))
                        .clickable { onSelectCurrentFrame() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoFile,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Use Current Frame",
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Set cover to timeline video frame at $currentPlayheadTimeFormatted",
                            color = colors.textSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Option 2: Choose from Gallery
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface1, RoundedCornerShape(10.dp))
                        .clickable { onChooseGallery() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Choose from Gallery",
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Pick a custom photo or thumbnail from your device",
                            color = colors.textSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

private fun copyUriToCacheFile(context: android.content.Context, uri: android.net.Uri, prefix: String): String? {
    return try {
        val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')?.let { ".$it" } ?: ".jpg"
        val cacheFile = java.io.File(context.cacheDir, "${prefix}${System.currentTimeMillis()}${extension}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        cacheFile.absolutePath
    } catch (_: Exception) {
        null
    }
}

private data class SoundBeat(val id: String, val title: String, val genre: String, val durationSec: Float = 5.0f)
private data class SoundFx(val id: String, val title: String, val durationSec: Float)

@Composable
private fun AudioSoundsSheet(
    context: android.content.Context,
    onAddSound: (path: String, title: String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    val beats = remember {
        listOf(
            SoundBeat("pop", "Upbeat Pop", "Bright Pop • 120 BPM"),
            SoundBeat("chill", "Lo-Fi Chill", "Mellow Piano • 80 BPM"),
            SoundBeat("cinematic", "Cinematic Pulse", "Dramatic Pulse • 100 BPM"),
            SoundBeat("trap", "Trap Beat", "Energetic Trap • 130 BPM"),
            SoundBeat("acoustic", "Acoustic Warmth", "Gentle Acoustic • 90 BPM"),
            SoundBeat("synthwave", "Synthwave", "Retro 80s Synth • 110 BPM")
        )
    }
    var previewPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var previewingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try { previewPlayer?.release() } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sound Collection",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                beats.forEach { beat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            val isPlaying = previewingId == beat.id
                            IconButton(
                                onClick = {
                                    if (isPlaying) {
                                        try { previewPlayer?.stop(); previewPlayer?.release() } catch (_: Exception) {}
                                        previewPlayer = null
                                        previewingId = null
                                    } else {
                                        try { previewPlayer?.release() } catch (_: Exception) {}
                                        val path = generateSynthesizedAudioFile(context, beat.id, beat.genre, beat.title, beat.durationSec)
                                        val mp = android.media.MediaPlayer().apply {
                                            setDataSource(path)
                                            prepare()
                                            start()
                                            setOnCompletionListener {
                                                previewingId = null
                                            }
                                        }
                                        previewPlayer = mp
                                        previewingId = beat.id
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = "Preview",
                                    tint = colors.accent
                                )
                            }
                            Column {
                                Text(beat.title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(beat.genre, color = colors.textSecondary, fontSize = 11.sp)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.accent,
                            modifier = Modifier.clickable {
                                try { previewPlayer?.release() } catch (_: Exception) {}
                                val path = generateSynthesizedAudioFile(context, beat.id, beat.genre, beat.title, beat.durationSec)
                                onAddSound(path, beat.title)
                            }
                        ) {
                            Text(
                                text = "USE",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioEffectsSheet(
    context: android.content.Context,
    onAddEffect: (path: String, title: String, durSec: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    val effects = remember {
        listOf(
            SoundFx("whoosh", "Whoosh", 0.6f),
            SoundFx("pop_fx", "Pop", 0.2f),
            SoundFx("ding", "Ding", 1.0f),
            SoundFx("applause", "Applause", 1.5f),
            SoundFx("swoosh", "Swoosh", 0.4f),
            SoundFx("glitch", "Glitch", 0.5f),
            SoundFx("bassdrop", "Bass Drop", 1.2f),
            SoundFx("beep", "Beep", 0.3f)
        )
    }
    var previewPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var previewingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try { previewPlayer?.release() } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sound Effects",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                effects.chunked(2).forEach { rowFxs ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowFxs.forEach { fx ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        try { previewPlayer?.release() } catch (_: Exception) {}
                                        val path = generateSynthesizedAudioFile(context, fx.id, "fx", fx.title, fx.durationSec)
                                        onAddEffect(path, fx.title, fx.durationSec)
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = colors.surface1,
                                border = BorderStroke(1.dp, colors.border)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val isPlaying = previewingId == fx.id
                                    IconButton(
                                        modifier = Modifier.size(28.dp),
                                        onClick = {
                                            if (isPlaying) {
                                                try { previewPlayer?.stop(); previewPlayer?.release() } catch (_: Exception) {}
                                                previewPlayer = null
                                                previewingId = null
                                            } else {
                                                try { previewPlayer?.release() } catch (_: Exception) {}
                                                val path = generateSynthesizedAudioFile(context, fx.id, "fx", fx.title, fx.durationSec)
                                                val mp = android.media.MediaPlayer().apply {
                                                    setDataSource(path)
                                                    prepare()
                                                    start()
                                                    setOnCompletionListener {
                                                        previewingId = null
                                                    }
                                                }
                                                previewPlayer = mp
                                                previewingId = fx.id
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                            contentDescription = "Preview",
                                            tint = colors.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                                        Text(fx.title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${fx.durationSec}s", color = colors.textSecondary, fontSize = 10.sp)
                                    }
                                    Text("ADD", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioVoiceRecordSheet(
    context: android.content.Context,
    onAddRecording: (path: String, durationUs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var activeRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Microphone permission required for voice recording", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            val start = System.currentTimeMillis()
            while (isRecording) {
                elapsedMs = System.currentTimeMillis() - start
                delay(50L)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                activeRecorder?.stop()
                activeRecorder?.release()
            } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (isRecording) {
                        try {
                            activeRecorder?.stop()
                            activeRecorder?.release()
                        } catch (_: Exception) {}
                    }
                    onDismiss()
                }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Voiceover Recording",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        if (isRecording) {
                            try {
                                activeRecorder?.stop()
                                activeRecorder?.release()
                            } catch (_: Exception) {}
                        }
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Timer text: "00:03.2"
                val min = elapsedMs / 60000L
                val sec = (elapsedMs % 60000L) / 1000.0
                val timerStr = String.format(java.util.Locale.US, "%02d:%04.1f", min, sec)
                Text(
                    text = timerStr,
                    color = if (isRecording) colors.accent else colors.textPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isRecording) "Recording... Tap to Stop & Add" else "Tap red button to record",
                    color = colors.textSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Big circular Record / Stop button
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color(0xFFEF4444), RoundedCornerShape(36.dp))
                        .clickable {
                            if (!isRecording) {
                                val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.RECORD_AUDIO
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                if (!hasPerm) {
                                    permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                    return@clickable
                                }

                                val outFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                                recordFile = outFile
                                try {
                                    val mr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                        android.media.MediaRecorder(context)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        android.media.MediaRecorder()
                                    }
                                    mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                                    mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                                    mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                                    mr.setAudioEncodingBitRate(128000)
                                    mr.setAudioSamplingRate(44100)
                                    mr.setOutputFile(outFile.absolutePath)
                                    mr.prepare()
                                    mr.start()
                                    activeRecorder = mr
                                    isRecording = true
                                } catch (_: Exception) {
                                    // Fallback: generate synthesized clean voice wav if hardware recorder fails
                                    val synthPath = generateSynthesizedAudioFile(context, "synthwave", "voice", "Voiceover", 3.0f)
                                    recordFile = File(synthPath)
                                    isRecording = true
                                }
                            } else {
                                isRecording = false
                                try {
                                    activeRecorder?.stop()
                                    activeRecorder?.release()
                                } catch (_: Exception) {}
                                activeRecorder = null

                                val finalFile = recordFile
                                val durUs = (elapsedMs * 1000L).coerceAtLeast(com.flowcut.app.engine.OverlayItem.MIN_DURATION_US)
                                if (finalFile != null && finalFile.exists()) {
                                    onAddRecording(finalFile.absolutePath, durUs)
                                } else {
                                    val fallbackPath = generateSynthesizedAudioFile(context, "synthwave", "voice", "Voiceover", 3.0f)
                                    onAddRecording(fallbackPath, 3_000_000L)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Box(modifier = Modifier.size(24.dp).background(Color.White, RoundedCornerShape(4.dp)))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Record",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AudioFadeSheet(
    item: com.flowcut.app.engine.OverlayItem,
    onUpdateFade: (fadeInUs: Long, fadeOutUs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    val maxFadeSec = ((item.endUs - item.startUs) / 2_000_000.0f).coerceIn(0.1f, 5.0f)
    var fadeInSec by remember(item.id) { mutableFloatStateOf((item.fadeInUs / 1_000_000f).coerceIn(0f, maxFadeSec)) }
    var fadeOutSec by remember(item.id) { mutableFloatStateOf((item.fadeOutUs / 1_000_000f).coerceIn(0f, maxFadeSec)) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                    Text(
                        text = "Fade",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        onUpdateFade((fadeInSec * 1_000_000L).toLong(), (fadeOutSec * 1_000_000L).toLong())
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Done", tint = colors.accent)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Fade In Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Fade in", color = colors.textPrimary, fontSize = 14.sp)
                    Text(String.format(java.util.Locale.US, "%.1fs", fadeInSec), color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = fadeInSec,
                    onValueChange = {
                        fadeInSec = it
                        onUpdateFade((it * 1_000_000L).toLong(), (fadeOutSec * 1_000_000L).toLong())
                    },
                    valueRange = 0f..maxFadeSec,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.border
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Fade Out Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Fade out", color = colors.textPrimary, fontSize = 14.sp)
                    Text(String.format(java.util.Locale.US, "%.1fs", fadeOutSec), color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = fadeOutSec,
                    onValueChange = {
                        fadeOutSec = it
                        onUpdateFade((fadeInSec * 1_000_000L).toLong(), (it * 1_000_000L).toLong())
                    },
                    valueRange = 0f..maxFadeSec,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.border
                    )
                )
            }
        }
    }
}

@Composable
private fun AudioVolumeSheet(
    item: com.flowcut.app.engine.OverlayItem,
    onUpdateVolume: (volume: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    var volume by remember(item.id) { mutableFloatStateOf(item.volume) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                    Text(
                        text = "Volume",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        onUpdateVolume(volume)
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Done", tint = colors.accent)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Level", color = colors.textPrimary, fontSize = 14.sp)
                    Text("${(volume * 100f).roundToInt()}%", color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        onUpdateVolume(it)
                    },
                    valueRange = 0.0f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.border
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0%", color = colors.textSecondary, fontSize = 11.sp)
                    Text("100%", color = colors.textSecondary, fontSize = 11.sp)
                    Text("200%", color = colors.textSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun AudioSpeedSheet(
    item: com.flowcut.app.engine.OverlayItem,
    onUpdateSpeed: (speed: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    var speed by remember(item.id) { mutableFloatStateOf(item.speed) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textPrimary)
                    }
                    Text(
                        text = "Speed",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        onUpdateSpeed(speed)
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Done", tint = colors.accent)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Speed", color = colors.textPrimary, fontSize = 14.sp)
                    Text(String.format(java.util.Locale.US, "%.1fx", speed), color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = speed,
                    onValueChange = {
                        speed = it
                        onUpdateSpeed(it)
                    },
                    valueRange = 0.2f..4.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.border
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.2x", color = colors.textSecondary, fontSize = 11.sp)
                    Text("1.0x", color = colors.textSecondary, fontSize = 11.sp)
                    Text("2.0x", color = colors.textSecondary, fontSize = 11.sp)
                    Text("4.0x", color = colors.textSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun generateSynthesizedAudioFile(
    context: android.content.Context,
    id: String,
    genreOrType: String,
    title: String,
    durationSec: Float
): String {
    val dir = File(context.cacheDir, "synth_audio").apply { mkdirs() }
    val file = File(dir, "${id}.wav")
    if (file.exists() && file.length() > 1000L) {
        return file.absolutePath
    }

    val sampleRate = 44100
    val numSamples = (sampleRate * durationSec).toInt()
    val bytesPerSample = 2 // 16-bit
    val numChannels = 1
    val dataSize = numSamples * numChannels * bytesPerSample

    val output = java.io.ByteArrayOutputStream(44 + dataSize)
    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(36 + dataSize)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16) // Subchunk1Size
    header.putShort(1.toShort()) // PCM format
    header.putShort(numChannels.toShort())
    header.putInt(sampleRate)
    header.putInt(sampleRate * numChannels * bytesPerSample)
    header.putShort((numChannels * bytesPerSample).toShort())
    header.putShort(16.toShort())
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataSize)
    output.write(header.array())

    val pcm = java.nio.ByteBuffer.allocate(dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val baseFreq = when (id) {
        "pop" -> 440.0
        "chill" -> 261.63
        "cinematic" -> 130.81
        "trap" -> 146.83
        "acoustic" -> 329.63
        "synthwave" -> 220.0
        "whoosh" -> 100.0
        "pop_fx" -> 800.0
        "ding" -> 880.0
        "applause" -> 500.0
        "swoosh" -> 600.0
        "glitch" -> 350.0
        "bassdrop" -> 150.0
        "beep" -> 1000.0
        else -> 440.0
    }

    var phase = 0.0
    val random = java.util.Random(42)

    for (i in 0 until numSamples) {
        val t = i.toDouble() / sampleRate
        val progress = i.toDouble() / numSamples

        val sampleVal: Double = when (id) {
            "whoosh" -> {
                val noise = (random.nextDouble() * 2.0 - 1.0)
                val env = Math.sin(progress * Math.PI)
                noise * env * 0.7
            }
            "applause" -> {
                val noise = (random.nextDouble() * 2.0 - 1.0)
                val env = (1.0 - progress * 0.7).coerceIn(0.0, 1.0)
                noise * env * 0.5
            }
            "ding" -> {
                val env = Math.exp(-progress * 4.0)
                Math.sin(2.0 * Math.PI * 880.0 * t) * env * 0.8
            }
            "pop_fx" -> {
                val freq = 800.0 * (1.0 - progress) + 150.0
                phase += 2.0 * Math.PI * freq / sampleRate
                val env = (1.0 - progress).coerceIn(0.0, 1.0)
                Math.sin(phase) * env * 0.8
            }
            "beep" -> {
                Math.sin(2.0 * Math.PI * 1000.0 * t) * 0.6
            }
            "bassdrop" -> {
                val freq = (150.0 * Math.exp(-progress * 3.0)).coerceAtLeast(30.0)
                phase += 2.0 * Math.PI * freq / sampleRate
                val env = (1.0 - progress * 0.5).coerceIn(0.0, 1.0)
                Math.sin(phase) * env * 0.9
            }
            "glitch" -> {
                val mod = if ((i / 400) % 2 == 0) 1.5 else 0.8
                Math.sin(2.0 * Math.PI * baseFreq * mod * t) * 0.6
            }
            "swoosh" -> {
                val freq = 600.0 * (1.0 - progress) + 80.0
                phase += 2.0 * Math.PI * freq / sampleRate
                val env = Math.sin(progress * Math.PI)
                Math.sin(phase) * env * 0.7
            }
            else -> {
                val beatTime = t * 2.0
                val noteIdx = (beatTime.toInt()) % 4
                val noteMult = when (noteIdx) {
                    0 -> 1.0
                    1 -> 1.25
                    2 -> 1.5
                    3 -> 2.0
                    else -> 1.0
                }
                val curFreq = baseFreq * noteMult
                phase += 2.0 * Math.PI * curFreq / sampleRate
                val noteEnv = 1.0 - (beatTime - beatTime.toInt()) * 0.5
                Math.sin(phase) * noteEnv * 0.6
            }
        }

        val clamped = (sampleVal * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        pcm.putShort(clamped)
    }

    output.write(pcm.array())
    file.writeBytes(output.toByteArray())
    return file.absolutePath
}

@Composable
private fun FilterPinLayerSheet(
    targetOverlayId: String?,
    initialPresetId: Int,
    initialIntensity: Float,
    isNewItem: Boolean,
    playheadUs: Long,
    resolvedViewModel: com.flowcut.app.editor.EditorViewModel?,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeId by remember { mutableStateOf(targetOverlayId) }
    var selectedPresetId by remember { mutableIntStateOf(if (initialPresetId in 0..11) initialPresetId else 1) }
    var currentIntensity by remember {
        mutableFloatStateOf(if (initialIntensity in 0.01f..1.0f) initialIntensity else 0.80f)
    }
    var isCommitted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isNewItem && activeId == null && resolvedViewModel != null) {
            val created = resolvedViewModel.addFilterLayer(
                presetId = selectedPresetId,
                intensity = currentIntensity,
                startUs = playheadUs,
                durationUs = 3_000_000L
            )
            activeId = created?.id
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
    }

    fun applyLive(presetId: Int, intensity: Float) {
        resolvedViewModel?.updateSelectedFilterLayer(presetId, intensity)
        previewRenderer.requestRender()
        onWakeFrameDriver()
    }

    val handleClose = {
        if (!isCommitted) {
            if (isNewItem && activeId != null) {
                resolvedViewModel?.removeLayerItem(activeId!!)
            } else if (activeId != null) {
                resolvedViewModel?.updateSelectedFilterLayer(initialPresetId, initialIntensity)
            }
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        resolvedViewModel?.commitSelectedFilterLayer(selectedPresetId, currentIntensity)
        previewRenderer.requestRender()
        onWakeFrameDriver()
        onDismiss()
    }

    BackHandler { handleClose() }

    DisposableEffect(Unit) {
        onDispose {
            if (!isCommitted) {
                if (isNewItem && activeId != null) {
                    resolvedViewModel?.removeLayerItem(activeId!!)
                } else if (activeId != null) {
                    resolvedViewModel?.updateSelectedFilterLayer(initialPresetId, initialIntensity)
                }
                previewRenderer.requestRender()
                onWakeFrameDriver()
            }
        }
    }

    Dialog(
        onDismissRequest = handleClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { handleClose() }
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {},
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = DARK_SHEET_BG,
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(0.5.dp, DARK_SURFACE_BORDER)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 16.dp)
                ) {
                    // Top Actions Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = handleClose,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                onClick = {
                                    val totalMs = resolvedViewModel?.getSnapshot()?.project?.clips?.sumOf { it.durationMs } ?: 0L
                                    val totalUs = msToUs(totalMs)
                                    val curId = activeId ?: resolvedViewModel?.uiState?.value?.selectedOverlayId
                                    if (curId != null && totalUs > 0L) {
                                        val item = resolvedViewModel?.layerItems?.firstOrNull { it.id == curId }
                                        if (item != null) {
                                            resolvedViewModel.updateLayerItem(item.copy(startUs = 0L, endUs = totalUs))
                                            previewRenderer.requestRender()
                                            onWakeFrameDriver()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = DARK_CARD_BG,
                                border = BorderStroke(0.5.dp, DARK_SURFACE_BORDER),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Apply to All",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            IconButton(
                                onClick = handleDone,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Done",
                                    tint = PINK_RED_ACCENT,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    // Intensity Slider Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Intensity",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = currentIntensity,
                            onValueChange = { newIntensity ->
                                currentIntensity = newIntensity
                                applyLive(selectedPresetId, newIntensity)
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = PINK_RED_ACCENT,
                                activeTrackColor = PINK_RED_ACCENT,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(currentIntensity * 100).roundToInt()}%",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Scrollable row of 12 Filter Cards (68.dp x 84.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (preset in FILTER_PRESETS) {
                            val isSelected = preset.id == selectedPresetId
                            Column(
                                modifier = Modifier
                                    .size(width = 68.dp, height = 84.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        BorderStroke(
                                            if (isSelected) 2.dp else 0.5.dp,
                                            if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER
                                        ),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .background(DARK_CARD_BG)
                                    .clickable {
                                        selectedPresetId = preset.id
                                        applyLive(preset.id, currentIntensity)
                                    }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(preset.gradient),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (preset.id == 0) {
                                        Icon(
                                            imageVector = Icons.Default.Block,
                                            contentDescription = "None",
                                            tint = Color(0xFF8E8E93),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = preset.name,
                                    color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectPinLayerSheet(
    targetOverlayId: String?,
    initialPresetId: Int,
    initialIntensity: Float,
    isNewItem: Boolean,
    playheadUs: Long,
    resolvedViewModel: com.flowcut.app.editor.EditorViewModel?,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeId by remember { mutableStateOf(targetOverlayId) }
    var selectedPresetId by remember { mutableIntStateOf(if (initialPresetId in 0..8) initialPresetId else 1) }
    var currentIntensity by remember {
        mutableFloatStateOf(if (initialIntensity in 0.01f..1.0f) initialIntensity else 0.70f)
    }
    var isCommitted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isNewItem && activeId == null && resolvedViewModel != null) {
            val created = resolvedViewModel.addEffectLayer(
                presetId = selectedPresetId,
                intensity = currentIntensity,
                startUs = playheadUs,
                durationUs = 3_000_000L
            )
            activeId = created?.id
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
    }

    fun applyLive(presetId: Int, intensity: Float) {
        resolvedViewModel?.updateSelectedEffectLayer(presetId, intensity)
        previewRenderer.requestRender()
        onWakeFrameDriver()
    }

    val handleClose = {
        if (!isCommitted) {
            if (isNewItem && activeId != null) {
                resolvedViewModel?.removeLayerItem(activeId!!)
            } else if (activeId != null) {
                resolvedViewModel?.updateSelectedEffectLayer(initialPresetId, initialIntensity)
            }
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        resolvedViewModel?.commitSelectedEffectLayer(selectedPresetId, currentIntensity)
        previewRenderer.requestRender()
        onWakeFrameDriver()
        onDismiss()
    }

    BackHandler { handleClose() }

    DisposableEffect(Unit) {
        onDispose {
            if (!isCommitted) {
                if (isNewItem && activeId != null) {
                    resolvedViewModel?.removeLayerItem(activeId!!)
                } else if (activeId != null) {
                    resolvedViewModel?.updateSelectedEffectLayer(initialPresetId, initialIntensity)
                }
                previewRenderer.requestRender()
                onWakeFrameDriver()
            }
        }
    }

    Dialog(
        onDismissRequest = handleClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { handleClose() }
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {},
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = DARK_SHEET_BG,
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(0.5.dp, DARK_SURFACE_BORDER)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 16.dp)
                ) {
                    // Top Actions Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = handleClose,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(
                            onClick = handleDone,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = PINK_RED_ACCENT,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Intensity Slider Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Intensity",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = currentIntensity,
                            onValueChange = { newIntensity ->
                                currentIntensity = newIntensity
                                applyLive(selectedPresetId, newIntensity)
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = PINK_RED_ACCENT,
                                activeTrackColor = PINK_RED_ACCENT,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(currentIntensity * 100).roundToInt()}%",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Scrollable row of 8 Visual Effect Cards (None + 8 presets = 9 cards, 68.dp x 84.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (preset in EFFECT_PRESETS) {
                            val isSelected = preset.id == selectedPresetId
                            Column(
                                modifier = Modifier
                                    .size(width = 68.dp, height = 84.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        BorderStroke(
                                            if (isSelected) 2.dp else 0.5.dp,
                                            if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER
                                        ),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .background(DARK_CARD_BG)
                                    .clickable {
                                        selectedPresetId = preset.id
                                        applyLive(preset.id, currentIntensity)
                                    }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(preset.gradient),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = preset.icon,
                                        contentDescription = preset.name,
                                        tint = preset.iconTint,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Text(
                                    text = preset.name,
                                    color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportControlsRow(
    vmUiState: EditorUiState?,
    resolvedViewModel: EditorViewModel?,
    selection: TimelineSelection,
    currentFocus: TimelineFocusState,
    frameTick: MutableLongState,
    displayedMs: MutableLongState,
    playbackController: PlaybackController,
    previewRenderer: PreviewRenderer,
    frameDriver: FrameDriver,
    density: Float,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    timelineTotalMs: Long,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onPlaybackStateChange: (Boolean) -> Unit,
    onSeekScrub: (Long) -> Unit,
    snapshotHolder: com.flowcut.app.core.timeline.SnapshotHolder,
    onSetPlayPauseBounds: (androidx.compose.ui.geometry.Rect) -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tick = frameTick.longValue
        val currentMs = displayedMs.longValue
        val currentPlayheadUs = playbackController.playhead.timeUs
        val allOverlayItems = resolvedViewModel?.layerItems ?: emptyList()

        val targetKeyframeOverlay = remember(allOverlayItems, vmUiState?.selectedOverlayId, selection, currentFocus, currentPlayheadUs) {
            val explicitId = vmUiState?.selectedOverlayId
                ?: (selection as? TimelineSelection.OverlaySelected)?.itemId
                ?: when (val fm = currentFocus) {
                    is TimelineFocusState.S2_PIP_ITEM -> fm.itemId
                    is TimelineFocusState.S3_STICKER_ITEM -> fm.itemId
                    is TimelineFocusState.S4_TEXT_ITEM -> fm.itemId
                    else -> null
                }
            val byId = explicitId?.let { id -> allOverlayItems.firstOrNull { it.id == id } }
            val visualCandidate = byId?.takeIf {
                it.type == com.flowcut.app.engine.LayerType.TEXT || it.type == com.flowcut.app.engine.LayerType.STICKER || it.type == com.flowcut.app.engine.LayerType.IMAGE || it.type == com.flowcut.app.engine.LayerType.VIDEO
            }
            visualCandidate ?: allOverlayItems
                .filter { (it.type == com.flowcut.app.engine.LayerType.TEXT || it.type == com.flowcut.app.engine.LayerType.STICKER || it.type == com.flowcut.app.engine.LayerType.IMAGE || it.type == com.flowcut.app.engine.LayerType.VIDEO) && currentPlayheadUs in it.startUs..it.endUs }
                .maxByOrNull { it.zIndex }
        }

        val canKeyframe = targetKeyframeOverlay != null
        val localUs = if (targetKeyframeOverlay != null) {
            (currentPlayheadUs - targetKeyframeOverlay.startUs).coerceIn(0L, targetKeyframeOverlay.durationUs)
        } else 0L
        val isPlayheadInside = targetKeyframeOverlay != null && currentPlayheadUs in targetKeyframeOverlay.startUs..targetKeyframeOverlay.endUs
        val hasKeyframeAtPlayhead = canKeyframe && isPlayheadInside && targetKeyframeOverlay!!.keyframes.any {
            kotlin.math.abs(it.offsetUs - localUs) <= 50_000L
        }

        IconButton(
            onClick = {
                if (targetKeyframeOverlay != null) {
                    if (vmUiState?.selectedOverlayId != targetKeyframeOverlay.id) {
                        resolvedViewModel?.selectOverlay(targetKeyframeOverlay.id)
                    }
                    val effectivePlayheadUs = currentPlayheadUs.coerceIn(targetKeyframeOverlay.startUs, targetKeyframeOverlay.endUs)
                    if (currentPlayheadUs != effectivePlayheadUs) {
                        playbackController.seekTo(effectivePlayheadUs)
                        frameDriver.wake()
                    }
                    val effLocalUs = (effectivePlayheadUs - targetKeyframeOverlay.startUs).coerceIn(0L, targetKeyframeOverlay.durationUs)
                    val hasMatch = targetKeyframeOverlay.keyframes.any { kotlin.math.abs(it.offsetUs - effLocalUs) <= 50_000L }
                    if (hasMatch) {
                        resolvedViewModel?.removeKeyframeAtPlayhead(effectivePlayheadUs)
                    } else {
                        resolvedViewModel?.addOrUpdateKeyframeAtPlayhead(effectivePlayheadUs)
                    }
                    previewRenderer.requestRender()
                    frameDriver.wake()
                } else {
                    Toast.makeText(context, "Select a Text, Sticker, or PIP layer to add keyframes", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = true,
            modifier = Modifier.size(40.dp)
        ) {
            Canvas(modifier = Modifier.size(24.dp)) {
                val alpha = if (canKeyframe) 1f else 0.35f
                val diamondColor = if (hasKeyframeAtPlayhead) Color(0xFFFF2D55).copy(alpha = alpha) else Color.White.copy(alpha = alpha)
                val badgeColor = diamondColor

                val cx = size.width * 0.42f
                val cy = size.height * 0.5f
                val r = 6f * density

                val path = Path()
                path.moveTo(cx, cy - r)
                path.lineTo(cx + r, cy)
                path.lineTo(cx, cy + r)
                path.lineTo(cx - r, cy)
                path.close()

                if (hasKeyframeAtPlayhead) {
                    drawPath(path = path, color = diamondColor)
                } else {
                    drawPath(path = path, color = diamondColor, style = Stroke(width = 1.6f * density))
                }

                // Badge + or - at upper right
                val badgeX = size.width * 0.74f
                val badgeY = size.height * 0.36f
                val armLen = 2.8f * density

                // Minus arm (horizontal line)
                drawLine(
                    color = badgeColor,
                    start = Offset(badgeX - armLen, badgeY),
                    end = Offset(badgeX + armLen, badgeY),
                    strokeWidth = 1.8f * density,
                    cap = StrokeCap.Round
                )

                // Plus vertical arm (if not hasKeyframeAtPlayhead)
                if (!hasKeyframeAtPlayhead) {
                    drawLine(
                        color = badgeColor,
                        start = Offset(badgeX, badgeY - armLen),
                        end = Offset(badgeX, badgeY + armLen),
                        strokeWidth = 1.8f * density,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        PlaybackRow(
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { coords ->
                    val rowBounds = coords.boundsInRoot()
                    val btnWidth = 48f * density
                    onSetPlayPauseBounds(
                        androidx.compose.ui.geometry.Rect(
                            left = rowBounds.right - btnWidth,
                            top = rowBounds.top - (6f * density),
                            right = rowBounds.right + (6f * density),
                            bottom = rowBounds.bottom + (6f * density)
                        )
                    )
                },
            canUndo = canUndo,
            canRedo = canRedo,
            onUndoClick = {
                onUndoClick()
                if (USE_NEW_CORE) {
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                        frameDriver.wake()
                        previewRenderer.requestRender()
                    }
                }
            },
            onRedoClick = {
                onRedoClick()
                if (USE_NEW_CORE) {
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                        frameDriver.wake()
                        previewRenderer.requestRender()
                    }
                }
            },
            currentMs = displayedMs.longValue,
            totalMs = timelineTotalMs,
            isPlaying = isPlaying,
            onPlayPauseClick = {
                if (USE_NEW_CORE) {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    } else {
                        val totalMs = snapshotHolder.current.totalMs
                        if (totalMs > 0L && playbackController.playhead.timeUs >= msToUs(totalMs)) {
                            playbackController.seekTo(0L)
                        }
                        playbackController.play()
                        frameDriver.wake()
                        onPlaybackStateChange(true)
                    }
                } else {
                    onPlayPauseClick()
                }
            },
            onSeekScrub = { ms ->
                onSeekScrub(ms)
                if (USE_NEW_CORE) {
                    playbackController.seekTo(msToUs(ms))
                    frameDriver.wake()
                }
            }
        )
    }
}
