package com.flowcut.app.ui

import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import com.flowcut.app.engine.AspectRatioMode
import com.flowcut.app.engine.export.Aspect
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
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
import androidx.compose.ui.graphics.RectangleShape
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
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
import com.flowcut.app.editor.OverlayEndAlignment
import com.flowcut.app.engine.ClipBackground
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.FrameDriver
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.engine.USE_NEW_CORE
import com.flowcut.app.model.AUTO_PLAY_MARGIN_MS
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.Project
import com.flowcut.app.model.Transition
import com.flowcut.app.editor.LoadingSources
import com.flowcut.app.editor.LoadingStateManager
import com.flowcut.app.ui.loader.FlowCutLoadingIndicator
import com.flowcut.app.model.TransitionType
import com.flowcut.app.shared.ExportState
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
    preferredTopOverlayId: String? = null,
    canvasAspect: Float = 9f / 16f
): com.flowcut.app.engine.OverlayItem? {
    val containerAspect = if (outerH > 0f) outerW / outerH else 1f
    val previewW: Float
    val previewH: Float
    if (canvasAspect > containerAspect) {
        previewW = outerW
        previewH = (outerW / canvasAspect).coerceAtMost(outerH).coerceAtLeast(1f)
    } else {
        previewH = outerH
        previewW = (outerH * canvasAspect).coerceAtMost(outerW).coerceAtLeast(1f)
    }
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
                val cropW = (item.cropRight - item.cropLeft).coerceIn(0.01f, 1.0f)
                val cropH = (item.cropBottom - item.cropTop).coerceIn(0.01f, 1.0f)
                ((tw * cropW) / (th * cropH)).coerceIn(0.01f, 100f)
            }
            else -> 1.0f
        }
        val aScreen = canvasAspect
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
        com.flowcut.app.editor.toCoreProject(
            project = timeline.project,
            clipTimeMaps = resolvedViewModel?.clipTimeMaps ?: emptyMap()
        )
    }

    val snapshotHolder = remember { SnapshotHolder(TimelineSnapshot(1L, coreProject)) }
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
        AudioEngine(featureRegistry, context.applicationContext).apply {
            onPlaybackStopped = { audioContributor.onPlaybackStopped() }
        }
    }

    val playbackController = remember(audioEngine, snapshotHolder) {
        PlaybackController(clock = audioEngine.clock, holder = snapshotHolder).apply {
            seekEventListener = object : PlaybackController.SeekEventListener {
                override fun onSeekEvent(source: String, targetUs: Long, playheadUs: Long, coalesced: Boolean) {
                    com.flowcut.app.engine.perf.PreviewEventLog.log(
                        eventType = com.flowcut.app.engine.perf.PreviewEventType.SEEK_ENTER,
                        arg0 = targetUs,
                        arg1 = playheadUs,
                        reason = if (coalesced) "${source}_coalesced" else source
                    )
                }
            }
        }
    }
    val zoomState = remember { TimelineZoomState() }
    val frameTick = remember { mutableLongStateOf(0L) }
    val displayedMs = remember { mutableLongStateOf(currentMs) }

    val isTimelineScrollingState = remember { mutableStateOf(false) }
    var isTimelineScrolling by isTimelineScrollingState
    var timelineBoundsInRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    val isPlayingState = rememberUpdatedState(isPlaying)
    val onPlaybackStateChangeState = rememberUpdatedState(onPlaybackStateChange)
    val onPlayheadUpdateState = rememberUpdatedState(onPlayheadUpdate)

    val stateDirtyCounter = remember { java.util.concurrent.atomic.AtomicLong(1L) }

    val previewRenderer = remember {
        PreviewRenderer(
            holder = snapshotHolder,
            playhead = playbackController.playhead,
            context = context,
            featureRegistry = featureRegistry
        ).apply {
            onRenderRequested = {
                stateDirtyCounter.incrementAndGet()
                this.glSurfaceView?.requestRender()
            }
        }
    }

    val frameDriver = remember(playbackController, previewRenderer) {
        var lastReportedSec = -1L
        var lastReportedMode = PlayMode.PAUSED
        var lastRequestedTimeUs = -1L
        var lastRequestedTrimPreviewUs = -1L
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

                if (mode != PlayMode.FLINGING && mode != PlayMode.SCRUBBING) {
                    if (isTimelineScrollingState.value) {
                        isTimelineScrollingState.value = false
                    }
                }

                // 1. Local fast state: updates on every frame for instant timecode display
                displayedMs.longValue = tMs

                // 2. ViewModel state: throttle to whole seconds, or emit when scrubbing/flinging completes.
                // Prevents full-screen Compose recomposition storms at 60-120fps during drag!
                val stoppedMoving = (lastReportedMode == PlayMode.SCRUBBING || lastReportedMode == PlayMode.FLINGING) && mode == PlayMode.PAUSED
                if (stoppedMoving) {
                    previewRenderer.requestAccurateSeek()
                }
                if (curSec != lastReportedSec || stoppedMoving) {
                    lastReportedSec = curSec
                    onPlayheadUpdateState.value.invoke(tMs)
                }
                lastReportedMode = mode

                if (isPlayingState.value && mode != PlayMode.PLAYING) {
                    onPlaybackStateChangeState.value.invoke(false)
                }

                // 3. Skip redundant renders when paused, but never drop renders while waiting for a frame
                val currentDirtyVersion = stateDirtyCounter.get()
                val isDirty = currentDirtyVersion != lastRenderDirtyVersion
                val currentTrimPreviewUs = previewRenderer.trimPreviewTimeUs
                val trimPreviewChanged = currentTrimPreviewUs != lastRequestedTrimPreviewUs
                val isPausedJump = (mode == PlayMode.PAUSED) &&
                        lastRequestedTimeUs != -1L &&
                        kotlin.math.abs(tUs - lastRequestedTimeUs) > 80_000L
                if (isPausedJump) {
                    previewRenderer.isWaitingForFrame = true
                }
                val shouldRender = (mode != PlayMode.PAUSED) ||
                        (tUs != lastRequestedTimeUs) ||
                        trimPreviewChanged ||
                        (mode != lastRequestedMode) ||
                        previewRenderer.isWaitingForFrame ||
                        isDirty

                if (shouldRender) {
                    lastRequestedTimeUs = tUs
                    lastRequestedTrimPreviewUs = currentTrimPreviewUs
                    lastRequestedMode = mode
                    lastRenderDirtyVersion = currentDirtyVersion
                    if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && mode == PlayMode.PLAYING) {
                        com.flowcut.app.engine.PacePerfTracker.onRequestRender(
                            com.flowcut.app.engine.RenderRequestCaller.FRAME_DRIVER_TICK,
                            mode
                        )
                    }
                    if (previewRenderer.glSurfaceView != null) {
                        previewRenderer.glSurfaceView.requestRender()
                    } else {
                        previewRenderer.requestRender()
                    }
                }
            }
        )
    }
    frameDriver.keepAlive = { previewRenderer.owesFrame }

    val stopTimelineScrollNow = {
        playbackController.pause()
        isTimelineScrolling = false
        previewRenderer.isWaitingForFrame = true
        previewRenderer.requestAccurateSeek()
        frameDriver.wake()
    }
    val currentStopTimelineScrollNow by rememberUpdatedState(stopTimelineScrollNow)

    LaunchedEffect(coreProject) {
        com.flowcut.app.engine.perf.PreviewEventLog.log(com.flowcut.app.engine.perf.PreviewEventType.SEEK_ENTER, reason = "coreProject_edit")
        snapshotHolder.current = TimelineSnapshot(System.nanoTime(), coreProject)
        previewRenderer.requestRender(com.flowcut.app.engine.RenderRequestCaller.LAUNCHED_EFFECT_PROJECT)
        frameDriver.wake()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var suspendedByLifecycle by remember { mutableStateOf(false) }
    var glPausedByLifecycle by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, playbackController, frameDriver, previewRenderer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        onPlaybackStateChangeState.value.invoke(false)
                    }
                    frameDriver.stop()
                }
                Lifecycle.Event.ON_STOP -> {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        onPlaybackStateChangeState.value.invoke(false)
                    }
                    frameDriver.stop()
                    val isExportingNow = com.flowcut.app.engine.export.ExportCoordinator.state.value is ExportState.Exporting
                    if (!isExportingNow && !suspendedByLifecycle) {
                        previewRenderer.suspendForExport()
                        suspendedByLifecycle = true
                    }
                    if (!glPausedByLifecycle) {
                        previewRenderer.glSurfaceView?.onPause()
                        glPausedByLifecycle = true
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (glPausedByLifecycle) {
                        previewRenderer.glSurfaceView?.onResume()
                        glPausedByLifecycle = false
                    }
                    val isExportingNow = com.flowcut.app.engine.export.ExportCoordinator.state.value is ExportState.Exporting
                    if (suspendedByLifecycle && !isExportingNow) {
                        suspendedByLifecycle = false
                        previewRenderer.resumeFromExport()
                    }
                    if (!isExportingNow) {
                        previewRenderer.isWaitingForFrame = true
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(frameDriver, previewRenderer, audioEngine, audioContributor, playbackController) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        audioEngine.onAudioFocusLost = {
            val pauseAction = {
                if (playbackController.playhead.mode == PlayMode.PLAYING) {
                    playbackController.pause()
                    onPlaybackStateChangeState.value.invoke(false)
                }
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                pauseAction()
            } else {
                mainHandler.post(pauseAction)
            }
        }
        previewRenderer.startPrefetch(coroutineScope)
        onDispose {
            audioEngine.onAudioFocusLost = null
            frameDriver.stop()
            previewRenderer.release()
            audioEngine.release()
            audioContributor.release()
        }
    }

    val currentSelectedClipIndex by rememberUpdatedState(selectedClipIndex)
    val handleReplaceClipUri: (android.net.Uri) -> Unit = { uri ->
        val clipIdx = currentSelectedClipIndex
        if (clipIdx != null) {
            resolvedViewModel?.replaceSelectedClipUri(clipIdx, uri) {
                previewRenderer.trimPreviewTimeUs = -1L
                if (USE_NEW_CORE) {
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                    }
                }
                val maxTimelineUs = msToUs(snapshotHolder.current.totalMs).coerceAtLeast(0L)
                val clampedPlayheadUs = playbackController.playhead.timeUs.coerceIn(0L, maxTimelineUs)
                playbackController.seekTo(clampedPlayheadUs)
                previewRenderer.invalidateDecoders()
                previewRenderer.requestAccurateSeek()
                frameDriver.wake()
            }
        }
    }

    val replacePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            handleReplaceClipUri(uri)
        }
    }

    val replaceFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handleReplaceClipUri(uri)
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
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val isVideo = mimeType.startsWith("video/")
            val targetType = if (isVideo) com.flowcut.app.engine.LayerType.VIDEO else com.flowcut.app.engine.LayerType.IMAGE
            if (!com.flowcut.app.engine.canAddLayerItem(vm.layerItems, targetType)) {
                Toast.makeText(context, com.flowcut.app.editor.getLayerCapLimitMessage(targetType), Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val prefix = if (isVideo) "overlay_vid_" else "overlay_img_"
            val playheadUs = playbackController.playhead.timeUs
            coroutineScope.launch {
                vm.setProcessingEdit(true)
                try {
                    val cachePath = withContext(Dispatchers.IO) {
                        copyUriToCacheFile(context, uri, prefix)
                    }
                    if (cachePath != null) {
                        val addedItem = if (isVideo) {
                            vm.addVideoOverlay(cachePath, playheadUs, 3_000_000L)
                        } else {
                            vm.addImageOverlay(cachePath, playheadUs, 3_000_000L)
                        }
                        if (addedItem != null) {
                            previewRenderer.requestRender()
                            frameDriver.wake()
                        }
                    } else {
                        Toast.makeText(context, "Failed to import overlay media", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    vm.setProcessingEdit(false)
                }
            }
        }
    }

    val overlayFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val vm = resolvedViewModel ?: return@rememberLauncherForActivityResult
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val isVideo = mimeType.startsWith("video/")
            val targetType = if (isVideo) com.flowcut.app.engine.LayerType.VIDEO else com.flowcut.app.engine.LayerType.IMAGE
            if (!com.flowcut.app.engine.canAddLayerItem(vm.layerItems, targetType)) {
                Toast.makeText(context, com.flowcut.app.editor.getLayerCapLimitMessage(targetType), Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val prefix = if (isVideo) "overlay_vid_" else "overlay_img_"
            val playheadUs = playbackController.playhead.timeUs
            coroutineScope.launch {
                vm.setProcessingEdit(true)
                try {
                    val cachePath = withContext(Dispatchers.IO) {
                        copyUriToCacheFile(context, uri, prefix)
                    }
                    if (cachePath != null) {
                        val addedItem = if (isVideo) {
                            vm.addVideoOverlay(cachePath, playheadUs, 3_000_000L)
                        } else {
                            vm.addImageOverlay(cachePath, playheadUs, 3_000_000L)
                        }
                        if (addedItem != null) {
                            previewRenderer.requestRender()
                            frameDriver.wake()
                        }
                    } else {
                        Toast.makeText(context, "Failed to import overlay media", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    vm.setProcessingEdit(false)
                }
            }
        }
    }

    val openOverlayPicker: () -> Unit = {
        val vm = resolvedViewModel
        if (vm != null && !com.flowcut.app.engine.canAddLayerItem(vm.layerItems, com.flowcut.app.engine.LayerType.IMAGE)) {
            Toast.makeText(context, com.flowcut.app.editor.getLayerCapLimitMessage(com.flowcut.app.engine.LayerType.IMAGE), Toast.LENGTH_SHORT).show()
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

    var isStickerReplaceMode by remember { mutableStateOf(false) }

    val stickerImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val vm = resolvedViewModel ?: return@rememberLauncherForActivityResult
            val replaceMode = isStickerReplaceMode
            if (!replaceMode && !com.flowcut.app.engine.canAddLayerItem(vm.layerItems, com.flowcut.app.engine.LayerType.STICKER)) {
                Toast.makeText(context, com.flowcut.app.editor.getLayerCapLimitMessage(com.flowcut.app.engine.LayerType.STICKER), Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val playheadUs = playbackController.playhead.timeUs
            coroutineScope.launch {
                vm.setProcessingEdit(true)
                try {
                    val cachePath = withContext(Dispatchers.IO) {
                        copyUriToCacheFile(context, uri, "sticker_img_")
                    }
                    if (cachePath != null) {
                        if (replaceMode) {
                            vm.replaceSelectedSticker(newTextOrEmoji = "", newMediaPath = cachePath)
                        } else {
                            vm.addImageStickerOverlay(cachePath, playheadUs, 3_000_000L)
                        }
                        previewRenderer.requestRender()
                        frameDriver.wake()
                    } else {
                        Toast.makeText(context, "Failed to import sticker image", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    vm.setProcessingEdit(false)
                }
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
        if (timeline.project.clips.isNotEmpty()) {
            previewRenderer.isWaitingForFrame = true
        }
        val result = resolvedViewModel?.splitAtPlayhead(
            playheadUs = currentPlayheadUs,
            preferredClipIndex = selectedClipIndex
        )
        if (result != null) {
            snapshotHolder.current = result.newSnapshot
            playbackController.seekTo(currentPlayheadUs)
            previewRenderer.requestRender()
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
    var isPipMaskSheetVisible by remember { mutableStateOf(false) }
    var isPipCropSheetVisible by remember { mutableStateOf(false) }
    var isPipCutoutSheetVisible by remember { mutableStateOf(false) }
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
    var isOverlayAdjustSheetVisible by remember { mutableStateOf(false) }
    var isAutoCaptionsSheetVisible by remember { mutableStateOf(false) }
    var isAspectRatioSheetVisible by remember { mutableStateOf(false) }

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
            val targetId = activeTextOverlayId
            coroutineScope.launch {
                try {
                    val fontFile = withContext(Dispatchers.IO) {
                        val extension = uri.path?.substringAfterLast('.', "")?.lowercase() ?: ""
                        val ext = if (extension == "otf") ".otf" else ".ttf"
                        val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
                        val outFile = File(fontsDir, "font_${System.currentTimeMillis()}$ext")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (outFile.exists() && outFile.length() > 0) outFile else null
                    }
                    if (fontFile != null) {
                        customFontPaths = customFontPaths + fontFile.absolutePath
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
    }

    val localAudioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val vm = resolvedViewModel ?: return@rememberLauncherForActivityResult
            val playheadUs = playbackController.playhead.timeUs
            val rawName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Local Audio"
            coroutineScope.launch {
                vm.setProcessingEdit(true)
                try {
                    val result = withContext(Dispatchers.IO) {
                        val cachePath = copyUriToCacheFile(context, uri, "audio_local_") ?: return@withContext null
                        val retriever = android.media.MediaMetadataRetriever()
                        var durMs = 5000L
                        try {
                            retriever.setDataSource(cachePath)
                            durMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L
                        } catch (_: Exception) {
                        } finally {
                            try { retriever.release() } catch (_: Exception) {}
                        }
                        cachePath to durMs
                    }
                    if (result != null) {
                        val (cachePath, durMs) = result
                        val totalUs = msToUs(vm.uiState.value.project.clips.sumOf { it.durationMs })
                        val remainingUs = (totalUs - playheadUs).coerceAtLeast(1_000_000L)
                        val finalDurUs = minOf(durMs * 1000L, remainingUs).coerceAtLeast(com.flowcut.app.engine.OverlayItem.MIN_DURATION_US)
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
                    } else {
                        Toast.makeText(context, "Failed to import audio file", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    vm.setProcessingEdit(false)
                }
            }
        }
    }

    val openLocalAudioPicker: () -> Unit = {
        val vm = resolvedViewModel
        if (vm != null && !com.flowcut.app.engine.canAddLayerItem(vm.layerItems, com.flowcut.app.engine.LayerType.AUDIO)) {
            Toast.makeText(context, com.flowcut.app.editor.getLayerCapLimitMessage(com.flowcut.app.engine.LayerType.AUDIO), Toast.LENGTH_SHORT).show()
        } else {
            try {
                localAudioPickerLauncher.launch("audio/*")
            } catch (_: Exception) {
                Toast.makeText(context, "Cannot open audio picker", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val extractVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val vm = resolvedViewModel ?: return@rememberLauncherForActivityResult
            coroutineScope.launch {
                vm.setProcessingEdit(true)
                try {
                    val cachePath = withContext(Dispatchers.IO) {
                        copyUriToCacheFile(context, uri, "audio_extract_")
                    }
                    if (cachePath != null) {
                        var durMs = 5000L
                        withContext(Dispatchers.IO) {
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(cachePath)
                                durMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L
                            } catch (_: Exception) {
                            } finally {
                                try { retriever.release() } catch (_: Exception) {}
                            }
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
                } finally {
                    vm.setProcessingEdit(false)
                }
            }
        }
    }

    val openExtractVideoPicker: () -> Unit = {
        val vm = resolvedViewModel
        if (vm != null && !com.flowcut.app.engine.canAddLayerItem(vm.layerItems, com.flowcut.app.engine.LayerType.AUDIO)) {
            Toast.makeText(context, com.flowcut.app.editor.getLayerCapLimitMessage(com.flowcut.app.engine.LayerType.AUDIO), Toast.LENGTH_SHORT).show()
        } else {
            try {
                extractVideoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            } catch (_: Exception) {
                Toast.makeText(context, "Cannot open video picker", Toast.LENGTH_SHORT).show()
            }
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
                coroutineScope.launch {
                    resolvedViewModel?.setProcessingEdit(true)
                    try {
                        val cachePath = withContext(Dispatchers.IO) {
                            copyUriToCacheFile(context, uri, "cover_img_")
                        }
                        if (cachePath != null) {
                            resolvedViewModel?.setCoverFromGalleryImage(cachePath)
                            resolvedViewModel?.showToast("Cover updated")
                        } else {
                            resolvedViewModel?.setCoverFromGalleryImage(uri.toString())
                        }
                        showCoverDialog = false
                    } finally {
                        resolvedViewModel?.setProcessingEdit(false)
                    }
                }
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
            if (!com.flowcut.app.engine.canAddLayerItem(vm.layerItems, com.flowcut.app.engine.LayerType.TEXT)) {
                Toast.makeText(context, com.flowcut.app.editor.getLayerCapLimitMessage(com.flowcut.app.engine.LayerType.TEXT), Toast.LENGTH_SHORT).show()
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
            if (!com.flowcut.app.engine.canAddLayerItem(vm.layerItems, com.flowcut.app.engine.LayerType.STICKER)) {
                Toast.makeText(context, com.flowcut.app.editor.getLayerCapLimitMessage(com.flowcut.app.engine.LayerType.STICKER), Toast.LENGTH_SHORT).show()
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

    val localLoadingManager = remember(coroutineScope) {
        resolvedViewModel?.loadingManager ?: LoadingStateManager(coroutineScope)
    }
    val isLoaderVisible by localLoadingManager.isLoadingVisible.collectAsState()
    val currentLoadingMessage by localLoadingManager.loadingMessage.collectAsState()

    // 1. Timeline scrolling / scrubbing must NEVER block interaction with a global Processing overlay.
    // Ensure any stale SEEKING loader clears immediately whenever user starts scrolling again.
    LaunchedEffect(isTimelineScrolling) {
        if (isTimelineScrolling) {
            localLoadingManager.setLoading(LoadingSources.SEEKING, false)
        }
    }

    // 2. Seeking / Frame catchup: only show loader when genuinely paused & waiting on a cold/slow seek,
    // with a short safety timeout so an already-latched ACCEPT_CURRENT frame can never trap the UI.
    DisposableEffect(previewRenderer, coroutineScope) {
        var seekLoaderGuardJob: Job? = null
        previewRenderer.onWaitingForFrameChanged = { waiting ->
            seekLoaderGuardJob?.cancel()
            seekLoaderGuardJob = null
            val shouldShowSeekLoader = waiting &&
                playbackController.playhead.mode == PlayMode.PAUSED &&
                !isTimelineScrollingState.value
            localLoadingManager.setLoading(LoadingSources.SEEKING, shouldShowSeekLoader, "Processing...")
            if (shouldShowSeekLoader && previewRenderer.isFirstFrameLatched) {
                seekLoaderGuardJob = coroutineScope.launch {
                    delay(1200L)
                    // If the target frame was already on screen (ACCEPT_CURRENT without a new surface buffer),
                    // clear the waiting flag so the UI and FrameDriver do not spin or stay blocked.
                    if (previewRenderer.isWaitingForFrame && previewRenderer.isFirstFrameLatched) {
                        previewRenderer.isWaitingForFrame = false
                    }
                    localLoadingManager.setLoading(LoadingSources.SEEKING, false)
                }
            }
        }
        onDispose {
            seekLoaderGuardJob?.cancel()
            previewRenderer.onWaitingForFrameChanged = null
            localLoadingManager.setLoading(LoadingSources.SEEKING, false)
        }
    }

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

    // Compute a stable cover decode key so unrelated clip edits (split, filter, volume, transform, etc.)
    // do NOT spawn a new MediaMetadataRetriever on every operation.
    val coverDecodeKey = remember(coverPath, coverTimeUs, clips) {
        if (!coverPath.isNullOrEmpty()) {
            "img:$coverPath"
        } else if (clips.isEmpty()) {
            "empty"
        } else {
            val snapshot = snapshotHolder.current
            val n = snapshot.main.n
            var targetClip = clips.first()
            var clipStartUs = 0L
            for (k in 0 until n) {
                val kStartUs = msToUs(snapshot.main.startMs[k])
                val kEndUs = msToUs(snapshot.main.endMs[k])
                if (coverTimeUs in kStartUs..kEndUs) {
                    targetClip = clips.getOrNull(k) ?: clips.first()
                    clipStartUs = kStartUs
                    break
                }
            }
            val tm = resolvedViewModel?.clipTimeMaps?.get(targetClip.id)
            val inUs = tm?.inUs ?: 0L
            val speedFactor = if (tm != null && tm.speedDen > 0) tm.speedNum.toDouble() / tm.speedDen.toDouble() else 1.0
            val frameOffsetUs = (coverTimeUs - clipStartUs).coerceAtLeast(0L)
            val sourceTimeUs = inUs + (frameOffsetUs * speedFactor).toLong()
            "vid:${targetClip.path}:${sourceTimeUs / 100_000L}"
        }
    }

    LaunchedEffect(coverDecodeKey) {
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
                            val sourceTimeUs = (inUs + (frameOffsetUs * speedFactor).toLong()).coerceAtLeast(0L)
                            val raw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(
                                    sourceTimeUs,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    128,
                                    128
                                ) ?: retriever.getFrameAtTime(
                                    sourceTimeUs,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                                )
                            } else {
                                retriever.getFrameAtTime(
                                    sourceTimeUs,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                                )
                            }
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
            .editorTouchInterceptor(
                isActivelyScrolling = {
                    isTimelineScrolling ||
                        playbackController.playhead.mode == PlayMode.FLINGING ||
                        playbackController.playhead.mode == PlayMode.SCRUBBING
                },
                isPlaying = { playbackController.playhead.mode == PlayMode.PLAYING },
                timelineBounds = { timelineBoundsInRoot },
                playPauseBounds = { playPauseBoundsInRoot },
                onStopScroll = currentStopTimelineScrollNow,
                onPausePlayback = {
                    playbackController.pause()
                    frameDriver.wake()
                    onPlaybackStateChange(false)
                }
            )
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
                val currentAspectRatioMode = vmUiState?.aspectRatioMode ?: PreviewRenderer.currentAspectRatioMode
                val currentRatioLabel = currentAspectRatioMode.label
                val targetCanvasAspect = remember(currentAspectRatioMode, timeline.project.clips) {
                    if (currentAspectRatioMode == AspectRatioMode.ORIGINAL) {
                        val clip0 = timeline.project.clips.firstOrNull()
                        if (clip0 != null) {
                            val w = if (clip0.rotation == 90 || clip0.rotation == 270) clip0.height else clip0.width
                            val h = if (clip0.rotation == 90 || clip0.rotation == 270) clip0.width else clip0.height
                            if (w > 0 && h > 0) w.toFloat() / h.toFloat() else (9f / 16f)
                        } else {
                            9f / 16f
                        }
                    } else if (currentAspectRatioMode.ratioWidth > 0 && currentAspectRatioMode.ratioHeight > 0) {
                        currentAspectRatioMode.ratioWidth.toFloat() / currentAspectRatioMode.ratioHeight.toFloat()
                    } else if (currentAspectRatioMode.aspect > 0f) {
                        currentAspectRatioMode.aspect
                    } else {
                        9f / 16f
                    }
                }

                LaunchedEffect(targetCanvasAspect, currentAspectRatioMode, timeline.project.clips.firstOrNull()?.id) {
                    val firstClip = timeline.project.clips.firstOrNull()
                    previewRenderer.setAspectRatioMode(currentAspectRatioMode, firstClip)
                    previewRenderer.setCanvasAspectRatio(targetCanvasAspect)
                    val bufW: Int
                    val bufH: Int
                    if (targetCanvasAspect >= 1f) {
                        bufW = (720f * targetCanvasAspect).toInt() and 1.inv()
                        bufH = 720
                    } else {
                        bufW = 720
                        bufH = (720f / targetCanvasAspect).toInt() and 1.inv()
                    }
                    (glSurfaceView as? android.view.SurfaceView)?.holder?.setFixedSize(bufW, bufH)
                    (previewRenderer.glSurfaceView as? android.view.SurfaceView)?.holder?.setFixedSize(bufW, bufH)
                    previewRenderer.glSurfaceView?.requestRender()
                    frameDriver.wake()
                }

                val openAspectRatioSheet = {
                    if (isPlaying) {
                        onPlayPauseClick()
                    }
                    isTrimSheetVisible = false
                    isVolumeSheetVisible = false
                    isSpeedSheetVisible = false
                    isAdjustSheetVisible = false
                    isBackgroundSheetVisible = false
                    isOpacitySheetVisible = false
                    isTextSheetVisible = false
                    isStickerSheetVisible = false
                    isTextColorSheetVisible = false
                    isTextFontSheetVisible = false
                    isTextAnimationSheetVisible = false
                    isTextAdjustSheetVisible = false
                    isAudioSoundsSheetVisible = false
                    isAudioEffectsSheetVisible = false
                    isAudioRecordSheetVisible = false
                    isAudioFadeSheetVisible = false
                    isAudioVolumeSheetVisible = false
                    isAudioSpeedSheetVisible = false
                    isPipMaskSheetVisible = false
                    isPipCropSheetVisible = false
                    isPipCutoutSheetVisible = false
                    isFilterSheetVisible = false
                    isEffectSheetVisible = false
                    isOverlayAdjustSheetVisible = false
                    isAutoCaptionsSheetVisible = false

                    resolvedViewModel?.startAspectRatioEditing()
                    isAspectRatioSheetVisible = true
                }

                // 1. Top bar: 48 dp (Left: [←  Project Title], Right: [Ratio] [Export])
                EditorTopBar(
                    projectTitle = vmUiState?.activeProjectTitle ?: "Project 1",
                    hasClips = hasClips,
                    isLoaderActive = isLoaderVisible,
                    colors = colors,
                    aspectRatioLabel = currentRatioLabel,
                    onRatioClick = openAspectRatioSheet,
                    onRenameProject = { newName -> resolvedViewModel?.renameActiveProject(newName) },
                    onBackClick = onBackClick,
                    onExportButtonClick = onExportButtonClick
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Preview box: weight 1f, minimum 220 dp
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 220.dp)
                        .fillMaxWidth()
                        .background(Color(0xFF0E0F13))
                ) {
                    PreviewBox(
                        hasClips = hasClips,
                        selectedTransitionName = selectedTransitionName,
                        onAddVideoClick = onAddClipsClick,
                        glSurfaceView = if (USE_NEW_CORE && previewRenderer.glSurfaceView != null) previewRenderer.glSurfaceView else glSurfaceView,
                        targetCanvasAspect = targetCanvasAspect,
                        aspectRatioLabel = currentRatioLabel,
                        onRatioClick = openAspectRatioSheet,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Instant thumbnail placeholder until first GL frame latches
                    FirstClipThumbnailOverlay(
                        isFirstFrameLatched = previewRenderer.isFirstFrameLatched,
                        clips = timeline.project.clips,
                        targetCanvasAspect = targetCanvasAspect
                    )

                    // Android Default Native Loader (CircularProgressIndicator + "Processing...")
                    FlowCutLoadingIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        message = currentLoadingMessage,
                        isVisible = isLoaderVisible
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
                                            playheadUs = playheadUs,
                                            canvasAspect = targetCanvasAspect
                                        )
                                        if (hit != null) {
                                            val newFocus = when (hit.type) {
                                                com.flowcut.app.engine.LayerType.IMAGE, com.flowcut.app.engine.LayerType.VIDEO ->
                                                    TimelineFocusState.S2_PIP_ITEM(hit.id)
                                                com.flowcut.app.engine.LayerType.STICKER, com.flowcut.app.engine.LayerType.MOSAIC ->
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
                            PreviewClipTransformOverlay(
                                activeClipId = activeClipId,
                                activeClip = activeClip,
                                selectedClipIndex = selectedClipIndex,
                                resolvedViewModel = resolvedViewModel,
                                previewRenderer = previewRenderer,
                                frameDriver = frameDriver,
                                snapshotHolder = snapshotHolder,
                                triggerApplyToAllPill = { triggerApplyToAllPill() },
                                targetCanvasAspect = targetCanvasAspect
                            )
                        }
                    }

                    // On-Preview Free Transform Gestures overlay when an Overlay Item is selected and active at current playhead
                    if (hasClips && selectedClipIndex == null && isSelectedOverlayActiveAtPlayhead && activeOverlay != null && activeOverlay.type != com.flowcut.app.engine.LayerType.FILTER && activeOverlayId != null) {
                        val currentOverlayState by rememberUpdatedState(activeOverlay)
                        val currentCanvasAspectState by rememberUpdatedState(targetCanvasAspect)
                        val currentViewModelState by rememberUpdatedState(resolvedViewModel)
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

                                            val curOverlay = currentOverlayState ?: activeOverlay
                                            val curAspect = currentCanvasAspectState
                                            val curVm = currentViewModelState

                                            val currentPlayheadUs = playbackController.playhead.timeUs
                                            val initialTransform = if (curOverlay.keyframes.isNotEmpty()) {
                                                animEvaluator.evaluate(curOverlay, currentPlayheadUs)
                                            } else {
                                                curOverlay.transform
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
                                            val containerAspect = if (outerH > 0f) outerW / outerH else 1f
                                            val previewW: Float
                                            val previewH: Float
                                            if (curAspect > containerAspect) {
                                                previewW = outerW
                                                previewH = (outerW / curAspect).coerceAtMost(outerH).coerceAtLeast(1f)
                                            } else {
                                                previewH = outerH
                                                previewW = (outerH * curAspect).coerceAtMost(outerW).coerceAtLeast(1f)
                                            }
                                            val previewLeft = (outerW - previewW) / 2f

                                            // Calculate aspect-correct overlay dimensions and center in outer gesture coordinates
                                            val displayText = if (curOverlay.text.isBlank()) "Your Text" else curOverlay.text
                                            val aTex: Float = when (curOverlay.type) {
                                                com.flowcut.app.engine.LayerType.STICKER -> 1.0f
                                                com.flowcut.app.engine.LayerType.TEXT -> {
                                                    val lines = displayText.split("\n")
                                                    val maxLen = lines.maxOfOrNull { it.length } ?: 6
                                                    val lineCount = lines.size.coerceAtLeast(1)
                                                    (maxLen.toFloat() * 0.45f / lineCount.toFloat()).coerceIn(0.8f, 4.0f)
                                                }
                                                com.flowcut.app.engine.LayerType.IMAGE, com.flowcut.app.engine.LayerType.VIDEO -> {
                                                    val tw = if (curOverlay.intrinsicWidth > 0) curOverlay.intrinsicWidth.toFloat() else 1f
                                                    val th = if (curOverlay.intrinsicHeight > 0) curOverlay.intrinsicHeight.toFloat() else 1f
                                                    val cropW = (curOverlay.cropRight - curOverlay.cropLeft).coerceIn(0.01f, 1.0f)
                                                    val cropH = (curOverlay.cropBottom - curOverlay.cropTop).coerceIn(0.01f, 1.0f)
                                                    ((tw * cropW) / (th * cropH)).coerceIn(0.01f, 100f)
                                                }
                                                else -> 1.0f
                                            }
                                            val aScreen = curAspect
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
                                                    curVm?.setOverlayTransformLive(activeOverlayId, currentTransform, currentPlayheadUs)
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
                                                    curVm?.commitOverlayTransform(activeOverlayId, currentTransform, currentPlayheadUs)
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
                                                            curVm?.deleteSelectedOverlay()
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
                                                            curVm?.copySelectedOverlay()
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
                                                                curVm?.commitOverlayTransform(activeOverlayId, resetTransform, currentPlayheadUs)
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
                                                                        com.flowcut.app.engine.LayerType.STICKER, com.flowcut.app.engine.LayerType.MOSAIC ->
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
                                // Aspect-locked Guide Overlay Canvas & Badge
                                BoxWithConstraints(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val containerAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f
                                    val selFrameMod = if (targetCanvasAspect > containerAspect) {
                                        Modifier.fillMaxWidth().aspectRatio(targetCanvasAspect)
                                    } else {
                                        Modifier.fillMaxHeight().aspectRatio(targetCanvasAspect)
                                    }
                                    Box(
                                        modifier = selFrameMod
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
                                                    val cropW = (activeOverlay.cropRight - activeOverlay.cropLeft).coerceIn(0.01f, 1.0f)
                                                    val cropH = (activeOverlay.cropBottom - activeOverlay.cropTop).coerceIn(0.01f, 1.0f)
                                                    ((tw * cropW) / (th * cropH)).coerceIn(0.01f, 100f)
                                                }
                                                else -> 1.0f
                                            }

                                            val aScreen = targetCanvasAspect
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

                    // Touch blocker over preview area while loader is active
                    TouchBlocker(blocked = isLoaderVisible, modifier = Modifier.matchParentSize())
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

                Box(modifier = Modifier.fillMaxWidth()) {
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
                    TouchBlocker(blocked = isLoaderVisible, modifier = Modifier.matchParentSize())
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 4. Timeline block: constant fixed height (New Core TimelineCanvas or legacy TimelineBlock)
                if (USE_NEW_CORE) {
                    EditorTimelineCanvasHost(
                        snapshotHolder = snapshotHolder,
                        playbackController = playbackController,
                        zoomState = zoomState,
                        frameTick = frameTick,
                        selection = selection,
                        currentFocus = currentFocus,
                        layerItems = resolvedViewModel?.layerItems ?: emptyList(),
                        isLoaderActive = isLoaderVisible,
                        customCoverBitmap = customCoverBitmap,
                        selectedClipIndex = selectedClipIndex,
                        timelineTransitions = timeline.project.transitions,
                        resolvedViewModel = resolvedViewModel,
                        previewRenderer = previewRenderer,
                        frameDriver = frameDriver,
                        isMainTrackMuted = isMainTrackMuted,
                        onPositionTimeline = { timelineBoundsInRoot = it },
                        onScrollingStateChanged = { isTimelineScrolling = it },
                        // Background filmstrip thumbnails and waveforms load progressively without
                        // blocking the editor or triggering the ThumbnailService.isPaused deadlock.
                        onThumbnailGeneratingChanged = {},
                        onWaveformGeneratingChanged = {},
                        onSelectClip = onSelectClip,
                        onSelectJunction = onSelectJunction,
                        onDeselectAll = onDeselectAll,
                        onApplyTransition = onApplyTransition,
                        onAddClipsClick = onAddClipsClick,
                        onCommitTrim = onCommitTrim,
                        onOpenCoverDialog = { showCoverDialog = true },
                        onSetOverlappingConflictItemIds = { overlappingConflictItemIds = it }
                    )
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

            val isStickerItem = vmUiState?.focusMode is com.flowcut.app.ui.timeline.TimelineFocusState.S3_STICKER_ITEM ||
                (activeOverlayForToolbar?.type == com.flowcut.app.engine.LayerType.STICKER ||
                 activeOverlayForToolbar?.type == com.flowcut.app.engine.LayerType.MOSAIC)
            val selectedStickerItem = if (isStickerItem) activeOverlayForToolbar else null
            val canSplitSticker = selectedStickerItem != null &&
                currentPlayheadUs >= (selectedStickerItem.startUs + 200_000L) &&
                currentPlayheadUs <= (selectedStickerItem.endUs - 200_000L)

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

            val isOverlaySelectedForToolbar = vmUiState?.selectedOverlayId != null || isPipActive || isAudioActive || isStickerItem

            Box(modifier = Modifier.fillMaxWidth()) {
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
                hasTextOverlay = resolvedViewModel?.hasTextOverlay() == true,
                isFilterOverlaySelected = isFilterOverlay,
                isEffectOverlaySelected = isEffectOverlay,
                isStickerSelected = isStickerItem,
                canSplitSticker = canSplitSticker,
                onAddStickerClick = {
                    isStickerReplaceMode = false
                    handleOpenSticker()
                },
                onStickerAnimationClick = {
                    val overlayId = selectedStickerItem?.id ?: vmUiState?.selectedOverlayId
                    if (overlayId != null) {
                        if (playbackController.playhead.mode == PlayMode.PLAYING) {
                            playbackController.pause()
                            frameDriver.wake()
                            onPlaybackStateChange(false)
                        }
                        activeTextOverlayId = overlayId
                        isTextAnimationSheetVisible = true
                    }
                },
                onStickerAdjustClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    isOverlayAdjustSheetVisible = true
                },
                onStickerReplaceClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    isStickerReplaceMode = true
                    isStickerSheetVisible = true
                },
                onPipAdjustClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    isOverlayAdjustSheetVisible = true
                },
                onCaptionsClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    isAutoCaptionsSheetVisible = true
                },
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
                onPipCutoutClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    isPipMaskSheetVisible = false
                    isPipCropSheetVisible = false
                    isPipCutoutSheetVisible = true
                },
                onPipMaskClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    isPipCutoutSheetVisible = false
                    isPipCropSheetVisible = false
                    isPipMaskSheetVisible = true
                },
                onPipCropClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    isPipCutoutSheetVisible = false
                    isPipMaskSheetVisible = false
                    isPipCropSheetVisible = true
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
                    val hasText = resolvedViewModel?.hasTextOverlay() == true
                    if (!hasText) {
                        handleOpenText()
                    } else {
                        isTextToolbarActive = true
                    }
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
                        if (resolvedViewModel != null && !com.flowcut.app.engine.canAddLayerItem(curLayers, com.flowcut.app.engine.LayerType.FILTER)) {
                            Toast.makeText(context, com.flowcut.app.editor.getLayerCapLimitMessage(com.flowcut.app.engine.LayerType.FILTER), Toast.LENGTH_SHORT).show()
                            return@BottomToolbar
                        }
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
                        if (resolvedViewModel != null && !com.flowcut.app.engine.canAddLayerItem(curLayers, com.flowcut.app.engine.LayerType.EFFECT)) {
                            Toast.makeText(context, com.flowcut.app.editor.getLayerCapLimitMessage(com.flowcut.app.engine.LayerType.EFFECT), Toast.LENGTH_SHORT).show()
                            return@BottomToolbar
                        }
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
            TouchBlocker(blocked = isLoaderVisible, modifier = Modifier.matchParentSize())
        }
    }

        // Interactive Clip-to-Clip Transition Sheet (Phase 10)
        TransitionSheetHost(
            selectedJunctionIndex = selectedJunctionIndex,
            resolvedViewModel = resolvedViewModel,
            snapshotHolder = snapshotHolder,
            playbackController = playbackController,
            frameDriver = frameDriver,
            previewRenderer = previewRenderer,
            coroutineScope = coroutineScope,
            onDeselectAll = onDeselectAll
        )

        // Export Config Sheet (Phase 10B: Two-Step Export Flow - Step 1)
        ExportConfigSheetHost(
            exportSheetVisible = exportSheetVisible,
            resolvedViewModel = resolvedViewModel,
            timelineTotal = timeline.total,
            onStartExport = onStartExport,
            onCloseExportSheet = onCloseExportSheet
        )

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

        // Speed BottomSheet (0.2x to 4.0x) for selected clip
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
                getMaxSpeedForClip = { k -> resolvedViewModel?.getMaxSpeedForClip(k) ?: 4.0f },
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

        // PIP Advanced GPU Tools BottomSheets (Phase 7: Mask, Crop, Cutout)
        PipAdvancedSheetsHost(
            isMaskVisible = isPipMaskSheetVisible,
            isCropVisible = isPipCropSheetVisible,
            isCutoutVisible = isPipCutoutSheetVisible,
            selectedOverlayId = vmUiState?.selectedOverlayId,
            resolvedViewModel = resolvedViewModel,
            previewRenderer = previewRenderer,
            onWakeFrameDriver = { frameDriver.wake() },
            onDismissMask = { isPipMaskSheetVisible = false },
            onDismissCrop = { isPipCropSheetVisible = false },
            onDismissCutout = { isPipCutoutSheetVisible = false }
        )

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

        // Opacity BottomSheet for selected overlay item
        OverlayOpacitySheetHost(
            isOpacitySheetVisible = isOpacitySheetVisible,
            selectedClipIndex = selectedClipIndex,
            selectedOverlayId = vmUiState?.selectedOverlayId,
            resolvedViewModel = resolvedViewModel,
            previewRenderer = previewRenderer,
            onWakeFrameDriver = { frameDriver.wake() },
            onDismiss = { isOpacitySheetVisible = false }
        )

        // Aspect Ratio BottomSheet
        if (isAspectRatioSheetVisible) {
            val currentMode = vmUiState?.aspectRatioMode ?: PreviewRenderer.currentAspectRatioMode
            AspectRatioSheet(
                activeRatio = currentMode,
                onRatioChanged = { mode ->
                    resolvedViewModel?.setAspectRatioLive(mode)
                    previewRenderer.setAspectRatioMode(mode, timeline.project.clips.firstOrNull())
                    previewRenderer.requestRender()
                    frameDriver.wake()
                },
                onDone = { mode ->
                    resolvedViewModel?.commitAspectRatio(mode)
                    previewRenderer.setAspectRatioMode(mode, timeline.project.clips.firstOrNull())
                    previewRenderer.requestRender()
                    frameDriver.wake()
                    isAspectRatioSheetVisible = false
                },
                onCancel = {
                    resolvedViewModel?.cancelAspectRatio()
                    val restored = resolvedViewModel?.uiState?.value?.aspectRatioMode ?: PreviewRenderer.currentAspectRatioMode
                    previewRenderer.setAspectRatioMode(restored, timeline.project.clips.firstOrNull())
                    previewRenderer.requestRender()
                    frameDriver.wake()
                    isAspectRatioSheetVisible = false
                },
                onDismiss = {
                    resolvedViewModel?.cancelAspectRatio()
                    val restored = resolvedViewModel?.uiState?.value?.aspectRatioMode ?: PreviewRenderer.currentAspectRatioMode
                    previewRenderer.setAspectRatioMode(restored, timeline.project.clips.firstOrNull())
                    previewRenderer.requestRender()
                    frameDriver.wake()
                    isAspectRatioSheetVisible = false
                }
            )
        }

        // Text & Sticker BottomSheets (Phase 3 Typography Suite)
        TextAndStickerSheetsHost(
            isTextSheetVisible = isTextSheetVisible,
            editingTextOverlayId = editingTextOverlayId,
            isNewTextOverlay = isNewTextOverlay,
            isStickerSheetVisible = isStickerSheetVisible,
            isStickerReplaceMode = isStickerReplaceMode,
            isTextColorSheetVisible = isTextColorSheetVisible,
            isTextFontSheetVisible = isTextFontSheetVisible,
            isTextAnimationSheetVisible = isTextAnimationSheetVisible,
            isTextAdjustSheetVisible = isTextAdjustSheetVisible,
            activeTextOverlayId = activeTextOverlayId,
            customFontPaths = customFontPaths,
            playheadUs = playbackController.playhead.timeUs,
            resolvedViewModel = resolvedViewModel,
            previewRenderer = previewRenderer,
            onWakeFrameDriver = { frameDriver.wake() },
            onCloseTextEditor = {
                isTextSheetVisible = false
                editingTextOverlayId = null
                isNewTextOverlay = false
            },
            onCloseStickerPicker = { isStickerSheetVisible = false },
            onPickStickerImage = {
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
            onCloseTextColor = {
                isTextColorSheetVisible = false
                activeTextOverlayId = null
            },
            onCloseTextFont = {
                isTextFontSheetVisible = false
                activeTextOverlayId = null
            },
            onPickCustomFont = {
                fontPickerLauncher.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "*/*"))
            },
            onCloseTextAnimation = {
                isTextAnimationSheetVisible = false
                activeTextOverlayId = null
            },
            onCloseTextAdjust = {
                isTextAdjustSheetVisible = false
                activeTextOverlayId = null
            }
        )

        // Phase 8 Sheets: Overlay Adjust (Opacity/Brightness/Contrast/Saturation) & Auto-Captions
        Phase8SheetsHost(
            isOverlayAdjustVisible = isOverlayAdjustSheetVisible,
            isAutoCaptionsVisible = isAutoCaptionsSheetVisible,
            selectedOverlayId = vmUiState?.selectedOverlayId,
            resolvedViewModel = resolvedViewModel,
            previewRenderer = previewRenderer,
            onWakeFrameDriver = { frameDriver.wake() },
            onDismissOverlayAdjust = { isOverlayAdjustSheetVisible = false },
            onDismissAutoCaptions = { isAutoCaptionsSheetVisible = false }
        )

        // Overlapping Layer Conflict Picker Dialog
        OverlappingLayerConflictDialog(
            conflictItemIds = overlappingConflictItemIds,
            allLayers = resolvedViewModel?.layerItems ?: emptyList(),
            selectedOverlayId = vmUiState?.selectedOverlayId,
            onSelectOverlay = { id ->
                resolvedViewModel?.selectOverlay(id)
                overlappingConflictItemIds = null
            },
            onDismiss = { overlappingConflictItemIds = null }
        )

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

        AudioAdjustmentSheetsHost(
            isFadeVisible = isAudioFadeSheetVisible,
            isVolumeVisible = isAudioVolumeSheetVisible,
            isSpeedVisible = isAudioSpeedSheetVisible,
            currentAudioItem = currentAudioItem,
            resolvedViewModel = resolvedViewModel,
            previewRenderer = previewRenderer,
            frameDriver = frameDriver,
            onDismissFade = { isAudioFadeSheetVisible = false },
            onDismissVolume = { isAudioVolumeSheetVisible = false },
            onDismissSpeed = { isAudioSpeedSheetVisible = false }
        )

        if (showCoverDialog) {
            CoverSelectionDialogHost(
                context = context,
                playbackController = playbackController,
                resolvedViewModel = resolvedViewModel,
                galleryCoverPicker = galleryCoverPicker,
                onDismiss = { showCoverDialog = false }
            )
        }
    }
}

@Composable
private fun EditorTimelineCanvasHost(
    snapshotHolder: com.flowcut.app.core.timeline.SnapshotHolder,
    playbackController: PlaybackController,
    zoomState: TimelineZoomState,
    frameTick: MutableLongState,
    selection: TimelineSelection,
    currentFocus: TimelineFocusState,
    layerItems: List<com.flowcut.app.engine.OverlayItem>,
    isLoaderActive: Boolean = false,
    customCoverBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    selectedClipIndex: Int?,
    timelineTransitions: List<com.flowcut.app.model.Transition>,
    resolvedViewModel: EditorViewModel?,
    previewRenderer: PreviewRenderer,
    frameDriver: com.flowcut.app.engine.FrameDriver,
    isMainTrackMuted: Boolean,
    onPositionTimeline: (androidx.compose.ui.geometry.Rect) -> Unit,
    onScrollingStateChanged: (Boolean) -> Unit,
    onThumbnailGeneratingChanged: (Boolean) -> Unit = {},
    onWaveformGeneratingChanged: (Boolean) -> Unit = {},
    onSelectClip: (Int) -> Unit,
    onSelectJunction: (Int) -> Unit,
    onDeselectAll: () -> Unit,
    onApplyTransition: (TransitionType) -> Unit,
    onAddClipsClick: () -> Unit,
    onCommitTrim: (Int, Long, Long, Long) -> Unit,
    onOpenCoverDialog: () -> Unit,
    onSetOverlappingConflictItemIds: (List<String>?) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors
    val timelineHeight = 224.dp

    var showOverlayTrimMenu by remember { mutableStateOf(false) }
    var trimMenuTargetOverlayId by remember { mutableStateOf<String?>(null) }
    var trimMenuIsRightHandle by remember { mutableStateOf(true) }
    var trimMenuTapOffset by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(selection) {
        showOverlayTrimMenu = false
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(timelineHeight)
            .background(colors.surface1, RoundedCornerShape(8.dp))
            .onGloballyPositioned { coords ->
                onPositionTimeline(coords.boundsInRoot())
            }
    ) {
        TimelineCanvas(
            holder = snapshotHolder,
            playhead = playbackController.playhead,
            controller = playbackController,
            zoomState = zoomState,
            frameTick = frameTick,
            selection = selection,
            focusMode = currentFocus,
            overlayItems = layerItems,
            isLoaderActive = isLoaderActive,
            customCoverBitmap = customCoverBitmap,
            onScrollingStateChanged = { scrolling ->
                if (scrolling) showOverlayTrimMenu = false
                onScrollingStateChanged(scrolling)
            },
            onThumbnailGeneratingChanged = onThumbnailGeneratingChanged,
            onWaveformGeneratingChanged = onWaveformGeneratingChanged,
            onOverlayTrimHandleTap = { id, isRight, offset ->
                trimMenuTargetOverlayId = id
                trimMenuIsRightHandle = isRight
                trimMenuTapOffset = offset
                showOverlayTrimMenu = true
            },
            onSelect = { sel ->
                when (sel) {
                    is TimelineSelection.ClipSelected -> {
                        onSelectClip(sel.index)
                    }
                    is TimelineSelection.JunctionSelected -> onSelectJunction(sel.index)
                    is TimelineSelection.TransitionClicked -> onSelectJunction(sel.junctionIndex)
                    is TimelineSelection.OverlaySelected -> {
                        onSetOverlappingConflictItemIds(null)
                        resolvedViewModel?.selectOverlay(sel.itemId)
                    }
                    is TimelineSelection.OverlappingPinsClicked -> {
                        onSetOverlappingConflictItemIds(sel.itemIds)
                    }
                    TimelineSelection.PipGroupClicked -> {
                        resolvedViewModel?.setFocusMode(TimelineFocusState.S1_PIP_GROUP)
                    }
                    TimelineSelection.CoverClicked -> {
                        onOpenCoverDialog()
                    }
                    TimelineSelection.AddClipClicked -> {
                        onAddClipsClick()
                    }
                    TimelineSelection.AudioGroupClicked -> {
                        resolvedViewModel?.setFocusMode(TimelineFocusState.S5_AUDIO_GROUP)
                    }
                    TimelineSelection.None -> {
                        onSetOverlappingConflictItemIds(null)
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
                        val currentT = timelineTransitions.getOrNull(j)
                        if (currentT != null && currentT.type != TransitionType.NONE) {
                            onApplyTransition(currentT.type)
                        }
                    }
                    else -> {}
                }
            },
            onCommitTrim = { k, inUs, outUs, durMs ->
                previewRenderer.trimPreviewTimeUs = -1L
                resolvedViewModel?.commitClipTrim(k, inUs, outUs, durMs)
                if (USE_NEW_CORE) {
                    resolvedViewModel?.let { vm ->
                        snapshotHolder.current = vm.getSnapshot()
                    }
                }
                onCommitTrim(k, inUs, outUs, durMs)
                previewRenderer.requestAccurateSeek()
                frameDriver.wake()
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
                val prev = previewRenderer.trimPreviewTimeUs
                previewRenderer.trimPreviewTimeUs = previewUs
                if (previewUs >= 0L) {
                    if (previewUs != prev) {
                        previewRenderer.requestRender()
                    }
                } else if (prev >= 0L) {
                    previewRenderer.requestAccurateSeek()
                }
                frameDriver.wake()
            },
            getClipNaturalDurationUs = { k ->
                resolveClipNaturalDurationUs(snapshotHolder.current.project.clips.getOrNull(k), resolvedViewModel, k)
            },
            onWakeFrameDriver = { frameDriver.wake() },
            isMainTrackMuted = isMainTrackMuted,
            onToggleTrackMute = { resolvedViewModel?.toggleTrackMute() },
            onToast = { resolvedViewModel?.showToast(it) },
            modifier = Modifier.fillMaxSize()
        )

        if (showOverlayTrimMenu && trimMenuTargetOverlayId != null && trimMenuTapOffset != null) {
            val targetId = trimMenuTargetOverlayId!!
            val anchor = trimMenuTapOffset!!
            val localDensity = LocalDensity.current
            val menuW = 148.dp
            val menuH = 88.dp
            val menuWPx = with(localDensity) { menuW.toPx() }
            val menuHPx = with(localDensity) { menuH.toPx() }
            val marginPx = with(localDensity) { 8.dp.toPx() }
            val gapPx = with(localDensity) { 8.dp.toPx() }

            val rawX = anchor.x - (menuWPx / 2f)
            val clampedX = rawX.coerceAtLeast(marginPx)
            val rawY = anchor.y - menuHPx - gapPx
            val clampedY = if (rawY < -menuHPx / 2f) {
                anchor.y + with(localDensity) { 24.dp.toPx() }
            } else {
                rawY
            }

            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(clampedX.roundToInt(), clampedY.roundToInt()),
                onDismissRequest = { showOverlayTrimMenu = false },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true, dismissOnBackPress = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xF018181D),
                    border = BorderStroke(1.dp, Color(0xFF383842)),
                    shadowElevation = 8.dp,
                    modifier = Modifier.width(menuW)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        val triggerAlignment = { alignment: OverlayEndAlignment ->
                            val success = resolvedViewModel?.alignOverlayEdge(
                                itemId = targetId,
                                isRightHandle = trimMenuIsRightHandle,
                                alignment = alignment
                            ) ?: false
                            if (success) {
                                if (USE_NEW_CORE) {
                                    resolvedViewModel?.let { vm ->
                                        snapshotHolder.current = vm.getSnapshot()
                                    }
                                }
                                previewRenderer.requestRender()
                                frameDriver.wake()
                            }
                            showOverlayTrimMenu = false
                        }

                        if (!trimMenuIsRightHandle) {
                            // Left Handle (<): Clip Start & Video Start
                            // 1. Clip Start
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { triggerAlignment(OverlayEndAlignment.CLIP_START) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Clip Start",
                                    tint = Color(0xFFFF2D55),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Clip Start",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                thickness = 0.5.dp,
                                color = Color(0xFF2E2E38)
                            )

                            // 2. Video Start
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { triggerAlignment(OverlayEndAlignment.VIDEO_START) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FirstPage,
                                    contentDescription = "Video Start",
                                    tint = Color(0xFFFF2D55),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Video Start",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            // Right Handle (>): Clip End & Video End
                            // 1. Clip End
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { triggerAlignment(OverlayEndAlignment.CLIP_END) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Clip End",
                                    tint = Color(0xFFFF2D55),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Clip End",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                thickness = 0.5.dp,
                                color = Color(0xFF2E2E38)
                            )

                            // 2. Video End
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { triggerAlignment(OverlayEndAlignment.VIDEO_END) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.LastPage,
                                    contentDescription = "Video End",
                                    tint = Color(0xFFFF2D55),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Video End",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveClipNaturalDurationUs(
    clip: com.flowcut.app.core.model.Clip?,
    resolvedViewModel: EditorViewModel?,
    k: Int
): Long {
    return resolvedViewModel?.getClipNaturalDurationUs(k)
        ?: run {
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
}

private fun Modifier.editorTouchInterceptor(
    isActivelyScrolling: () -> Boolean,
    isPlaying: () -> Boolean,
    timelineBounds: () -> androidx.compose.ui.geometry.Rect,
    playPauseBounds: () -> androidx.compose.ui.geometry.Rect,
    onStopScroll: () -> Unit,
    onPausePlayback: () -> Unit
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
        if (isActivelyScrolling()) {
            if (!timelineBounds().contains(down.position)) {
                onStopScroll()
                down.consume()
                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        } else if (isPlaying()) {
            if (!playPauseBounds().contains(down.position)) {
                onPausePlayback()
            }
        }
    }
}

@Composable
private fun AudioAdjustmentSheetsHost(
    isFadeVisible: Boolean,
    isVolumeVisible: Boolean,
    isSpeedVisible: Boolean,
    currentAudioItem: com.flowcut.app.engine.OverlayItem?,
    resolvedViewModel: EditorViewModel?,
    previewRenderer: PreviewRenderer,
    frameDriver: com.flowcut.app.engine.FrameDriver,
    onDismissFade: () -> Unit,
    onDismissVolume: () -> Unit,
    onDismissSpeed: () -> Unit
) {
    if (currentAudioItem == null) return
    if (isFadeVisible) {
        AudioFadeSheet(
            item = currentAudioItem,
            onUpdateFade = { inUs, outUs ->
                resolvedViewModel?.updateAudioFade(currentAudioItem.id, inUs, outUs)
                previewRenderer.requestRender()
                frameDriver.wake()
            },
            onDismiss = onDismissFade
        )
    }
    if (isVolumeVisible) {
        AudioVolumeSheet(
            item = currentAudioItem,
            onUpdateVolume = { vol ->
                resolvedViewModel?.updateAudioVolume(currentAudioItem.id, vol)
                previewRenderer.requestRender()
                frameDriver.wake()
            },
            onDismiss = onDismissVolume
        )
    }
    if (isSpeedVisible) {
        AudioSpeedSheet(
            item = currentAudioItem,
            onUpdateSpeed = { spd ->
                resolvedViewModel?.updateAudioSpeed(currentAudioItem.id, spd)
                previewRenderer.requestRender()
                frameDriver.wake()
            },
            onDismiss = onDismissSpeed
        )
    }
}

@Composable
private fun CoverSelectionDialogHost(
    context: android.content.Context,
    playbackController: com.flowcut.app.core.playback.PlaybackController,
    resolvedViewModel: EditorViewModel?,
    galleryCoverPicker: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>,
    onDismiss: () -> Unit
) {
    CoverSelectionSheet(
        onSelectCurrentFrame = {
            val playheadUs = playbackController.playhead.timeUs
            resolvedViewModel?.setCoverFromTimelineFrame(playheadUs)
            resolvedViewModel?.showToast("Cover set from current frame")
            onDismiss()
        },
        onChooseGallery = {
            try {
                galleryCoverPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "Cannot open image picker", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onDismiss = onDismiss,
        currentPlayheadTimeFormatted = formatTimeMs(com.flowcut.app.core.time.usToMs(playbackController.playhead.timeUs))
    )
}

@Composable
private fun ExportConfigSheetHost(
    exportSheetVisible: Boolean,
    resolvedViewModel: EditorViewModel?,
    timelineTotal: Long,
    onStartExport: () -> Unit,
    onCloseExportSheet: () -> Unit
) {
    if (!exportSheetVisible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onCloseExportSheet()
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Consume clicks inside sheet so scrim is not triggered */ }
        ) {
            val res = resolvedViewModel?.uiState?.value?.exportResolution ?: 1080
            val fps = resolvedViewModel?.uiState?.value?.exportFps ?: 30
            val bitrateBps = resolvedViewModel?.uiState?.value?.exportBitrateBps ?: 14_000_000
            val ratioMode = resolvedViewModel?.uiState?.value?.aspectRatioMode ?: AspectRatioMode.RATIO_9_16
            val exportAspect = if (ratioMode == AspectRatioMode.ORIGINAL) {
                val clip0 = resolvedViewModel?.uiState?.value?.project?.clips?.firstOrNull()
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
            ExportConfigSheet(
                exportAspect = exportAspect,
                initialResolution = res,
                initialFps = fps,
                initialBitrateBps = bitrateBps,
                totalDurationMs = timelineTotal,
                onStartExport = { selectedRes, selectedFps, selectedBitrate ->
                    if (resolvedViewModel != null) {
                        resolvedViewModel.startExportWithConfig(selectedRes, selectedFps, selectedBitrate)
                    } else {
                        onStartExport()
                    }
                },
                onDismiss = onCloseExportSheet
            )
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
            onScrubStart = {
                if (USE_NEW_CORE) {
                    playbackController.beginScrub()
                    frameDriver.wake()
                }
            },
            onSeekScrub = { targetMs ->
                onSeekScrub(targetMs)
                if (USE_NEW_CORE) {
                    playbackController.seekTo(targetMs * 1_000L)
                    frameDriver.wake()
                }
            },
            onScrubEnd = {
                if (USE_NEW_CORE) {
                    playbackController.endScrub(0.0)
                    previewRenderer.isWaitingForFrame = true
                    previewRenderer.requestAccurateSeek()
                    frameDriver.wake()
                }
            }
        )
    }
}

/**
 * Dedicated Host for PIP Advanced GPU Tools BottomSheets (Phase 7: Mask, Crop, Cutout / Chroma Key).
 * Isolated into a separate composable method to avoid JVM 64KB MethodTooLargeException in EditorScreen.
 */
@Composable
private fun PipAdvancedSheetsHost(
    isMaskVisible: Boolean,
    isCropVisible: Boolean,
    isCutoutVisible: Boolean,
    selectedOverlayId: String?,
    resolvedViewModel: com.flowcut.app.editor.EditorViewModel?,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit,
    onDismissMask: () -> Unit,
    onDismissCrop: () -> Unit,
    onDismissCutout: () -> Unit
) {
    if (selectedOverlayId == null) return
    val overlayItem = resolvedViewModel?.getOverlayItem(selectedOverlayId) ?: return

    if (isMaskVisible) {
        val origMaskType = remember(selectedOverlayId) { overlayItem.maskType }
        val origMaskFeather = remember(selectedOverlayId) { overlayItem.maskFeather }
        val origMaskInvert = remember(selectedOverlayId) { overlayItem.maskInvert }
        PipMaskSheet(
            initialMaskType = overlayItem.maskType,
            initialMaskFeather = overlayItem.maskFeather,
            initialMaskInvert = overlayItem.maskInvert,
            onLiveChange = { maskType, feather, invert ->
                resolvedViewModel?.liveUpdatePipMask(selectedOverlayId, maskType, feather, invert)
                previewRenderer.requestRender()
                onWakeFrameDriver()
            },
            onDone = { maskType, feather, invert ->
                resolvedViewModel?.updatePipMask(selectedOverlayId, maskType, feather, invert)
                previewRenderer.requestRender()
                onWakeFrameDriver()
                onDismissMask()
            },
            onCancel = {
                resolvedViewModel?.liveUpdatePipMask(selectedOverlayId, origMaskType, origMaskFeather, origMaskInvert)
                previewRenderer.requestRender()
                onWakeFrameDriver()
                onDismissMask()
            }
        )
    }

    if (isCropVisible) {
        val origLeft = remember(selectedOverlayId) { overlayItem.cropLeft }
        val origTop = remember(selectedOverlayId) { overlayItem.cropTop }
        val origRight = remember(selectedOverlayId) { overlayItem.cropRight }
        val origBottom = remember(selectedOverlayId) { overlayItem.cropBottom }
        PipCropSheet(
            initialCropLeft = overlayItem.cropLeft,
            initialCropTop = overlayItem.cropTop,
            initialCropRight = overlayItem.cropRight,
            initialCropBottom = overlayItem.cropBottom,
            intrinsicWidth = overlayItem.intrinsicWidth,
            intrinsicHeight = overlayItem.intrinsicHeight,
            rotation = (overlayItem.transform.rotationDeg.toInt() % 360 + 360) % 360,
            onLiveChange = { left, top, right, bottom ->
                resolvedViewModel?.liveUpdatePipCrop(selectedOverlayId, left, top, right, bottom)
                previewRenderer.requestRender()
                onWakeFrameDriver()
            },
            onDone = { left, top, right, bottom ->
                resolvedViewModel?.updatePipCrop(selectedOverlayId, left, top, right, bottom)
                previewRenderer.requestRender()
                onWakeFrameDriver()
                onDismissCrop()
            },
            onCancel = {
                resolvedViewModel?.liveUpdatePipCrop(selectedOverlayId, origLeft, origTop, origRight, origBottom)
                previewRenderer.requestRender()
                onWakeFrameDriver()
                onDismissCrop()
            }
        )
    }

    if (isCutoutVisible) {
        val origEnabled = remember(selectedOverlayId) { overlayItem.chromaEnabled }
        val origColor = remember(selectedOverlayId) { overlayItem.chromaColorArgb }
        val origThreshold = remember(selectedOverlayId) { overlayItem.chromaThreshold }
        val origBlend = remember(selectedOverlayId) { overlayItem.chromaBlend }
        PipCutoutSheet(
            initialChromaEnabled = overlayItem.chromaEnabled,
            initialChromaColorArgb = overlayItem.chromaColorArgb,
            initialChromaThreshold = overlayItem.chromaThreshold,
            initialChromaBlend = overlayItem.chromaBlend,
            onLiveChange = { enabled, colorArgb, threshold, blend ->
                resolvedViewModel?.liveUpdatePipCutout(selectedOverlayId, enabled, colorArgb, threshold, blend)
                previewRenderer.requestRender()
                onWakeFrameDriver()
            },
            onDone = { enabled, colorArgb, threshold, blend ->
                resolvedViewModel?.updatePipCutout(selectedOverlayId, enabled, colorArgb, threshold, blend)
                previewRenderer.requestRender()
                onWakeFrameDriver()
                onDismissCutout()
            },
            onCancel = {
                resolvedViewModel?.liveUpdatePipCutout(selectedOverlayId, origEnabled, origColor, origThreshold, origBlend)
                previewRenderer.requestRender()
                onWakeFrameDriver()
                onDismissCutout()
            }
        )
    }
}

/**
 * Dedicated Host for Overlay Opacity BottomSheet.
 * Isolated into a separate composable method to avoid JVM 64KB MethodTooLargeException in EditorScreen.
 */
@Composable
private fun OverlayOpacitySheetHost(
    isOpacitySheetVisible: Boolean,
    selectedClipIndex: Int?,
    selectedOverlayId: String?,
    resolvedViewModel: com.flowcut.app.editor.EditorViewModel?,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpacitySheetVisible || selectedClipIndex != null || selectedOverlayId == null) return
    val activeOverlayId = selectedOverlayId
    val activeOverlay = resolvedViewModel?.getOverlayItem(activeOverlayId) ?: return

    val colors = FlowCutTheme.colors
    val origOpacity = remember(activeOverlayId) { activeOverlay.transform.opacity }
    var sliderOpacity by remember(activeOverlayId) { mutableFloatStateOf(origOpacity) }
    var isCommitted by remember { mutableStateOf(false) }

    BackHandler {
        if (!isCommitted) {
            resolvedViewModel?.setOverlayOpacityLive(activeOverlayId, origOpacity)
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
        onDismiss()
    }

    DisposableEffect(activeOverlayId) {
        onDispose {
            if (!isCommitted) {
                resolvedViewModel?.setOverlayOpacityLive(activeOverlayId, origOpacity)
                previewRenderer.requestRender()
                onWakeFrameDriver()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        onWakeFrameDriver()
                        onDismiss()
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
                            onWakeFrameDriver()
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
                            onWakeFrameDriver()
                            onDismiss()
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
                        onWakeFrameDriver()
                    },
                    onValueChangeFinished = {
                        resolvedViewModel?.commitOverlayOpacity(activeOverlayId, sliderOpacity)
                        previewRenderer.requestRender()
                        onWakeFrameDriver()
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

/**
 * Dedicated Host for Text & Sticker BottomSheets (Phase 3 Typography Suite).
 * Isolated into a separate composable method to avoid JVM 64KB MethodTooLargeException in EditorScreen.
 */
@Composable
private fun TextAndStickerSheetsHost(
    isTextSheetVisible: Boolean,
    editingTextOverlayId: String?,
    isNewTextOverlay: Boolean,
    isStickerSheetVisible: Boolean,
    isStickerReplaceMode: Boolean = false,
    isTextColorSheetVisible: Boolean,
    isTextFontSheetVisible: Boolean,
    isTextAnimationSheetVisible: Boolean,
    isTextAdjustSheetVisible: Boolean,
    activeTextOverlayId: String?,
    customFontPaths: List<String>,
    playheadUs: Long,
    resolvedViewModel: com.flowcut.app.editor.EditorViewModel?,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit,
    onCloseTextEditor: () -> Unit,
    onCloseStickerPicker: () -> Unit,
    onPickStickerImage: () -> Unit,
    onCloseTextColor: () -> Unit,
    onCloseTextFont: () -> Unit,
    onPickCustomFont: () -> Unit,
    onCloseTextAnimation: () -> Unit,
    onCloseTextAdjust: () -> Unit
) {
    // Text Editor BottomSheet (Phase 3 Overlays)
    if (isTextSheetVisible && editingTextOverlayId != null) {
        val targetId = editingTextOverlayId
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
                    onWakeFrameDriver()
                },
                onDone = { txt, col, sty ->
                    resolvedViewModel?.updateTextOverlay(targetId, txt, col, sty)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onCloseTextEditor()
                },
                onCancel = {
                    if (isNewTextOverlay) {
                        resolvedViewModel?.removeLayerItem(targetId)
                    } else {
                        resolvedViewModel?.liveUpdateTextOverlay(targetId, origText, origColor, origStyle)
                    }
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onCloseTextEditor()
                }
            )
        } else {
            onCloseTextEditor()
        }
    }

    // Sticker Picker BottomSheet (Phase 3 Overlays)
    if (isStickerSheetVisible) {
        StickerPickerSheet(
            onSelectSticker = { glyph ->
                if (isStickerReplaceMode) {
                    resolvedViewModel?.replaceSelectedSticker(newTextOrEmoji = glyph, newMediaPath = "")
                } else {
                    resolvedViewModel?.addStickerOverlay(glyph, playheadUs, 3_000_000L)
                }
                previewRenderer.requestRender()
                onWakeFrameDriver()
                onCloseStickerPicker()
            },
            onAddImageClick = {
                onCloseStickerPicker()
                onPickStickerImage()
            },
            onDismiss = onCloseStickerPicker,
            isReplaceMode = isStickerReplaceMode
        )
    }

    // Text Color BottomSheet (Phase 3 Typography Suite)
    if (isTextColorSheetVisible && activeTextOverlayId != null) {
        val targetId = activeTextOverlayId
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
                    onWakeFrameDriver()
                },
                onDone = { tc, ta, lc, la, bc, ba, sc, sa ->
                    resolvedViewModel?.updateTextColorAndAlpha(targetId, tc, ta, lc, la, bc, ba, sc, sa)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onCloseTextColor()
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
                    onWakeFrameDriver()
                    onCloseTextColor()
                }
            )
        } else {
            onCloseTextColor()
        }
    }

    // Text Font BottomSheet (Phase 3 Typography Suite)
    if (isTextFontSheetVisible && activeTextOverlayId != null) {
        val targetId = activeTextOverlayId
        val textItem = resolvedViewModel?.getOverlayItem(targetId)
        if (textItem != null) {
            val origFontName = remember(targetId) { textItem.fontName }
            val origCustomPath = remember(targetId) { textItem.customFontPath }

            TextFontSheet(
                initialFontName = textItem.fontName,
                initialCustomFontPath = textItem.customFontPath,
                customFonts = customFontPaths,
                onPickCustomFont = onPickCustomFont,
                onLiveChange = { fontName, customFontPath ->
                    resolvedViewModel?.updateTextFont(targetId, fontName, customFontPath)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                },
                onDone = { fontName, customFontPath ->
                    resolvedViewModel?.updateTextFont(targetId, fontName, customFontPath)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onCloseTextFont()
                },
                onCancel = {
                    resolvedViewModel?.updateTextFont(targetId, origFontName, origCustomPath)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onCloseTextFont()
                }
            )
        } else {
            onCloseTextFont()
        }
    }

    // Text Animation BottomSheet (Phase 3 Typography Suite)
    if (isTextAnimationSheetVisible && activeTextOverlayId != null) {
        val targetId = activeTextOverlayId
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
                    onWakeFrameDriver()
                },
                onDone = { inType, inDur, outType, outDur, loopType ->
                    resolvedViewModel?.updateTextAnimation(targetId, inType, inDur, outType, outDur, loopType)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onCloseTextAnimation()
                },
                onCancel = {
                    resolvedViewModel?.liveUpdateTextAnimation(targetId, origInType, origInDur, origOutType, origOutDur, origLoopType)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onCloseTextAnimation()
                }
            )
        } else {
            onCloseTextAnimation()
        }
    }

    // Text Adjust BottomSheet (Phase 3 Typography Suite)
    if (isTextAdjustSheetVisible && activeTextOverlayId != null) {
        val targetId = activeTextOverlayId
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
                    onWakeFrameDriver()
                },
                onDone = { align, letter, line, alpha, bold, italic, underline, uppercase ->
                    resolvedViewModel?.updateTextAdjust(targetId, align, letter, line, alpha, bold, italic, underline, uppercase)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onCloseTextAdjust()
                },
                onCancel = {
                    resolvedViewModel?.liveUpdateTextAdjust(
                        targetId,
                        origAlign, origLetter, origLine, origAlpha,
                        origBold, origItalic, origUnderline, origUppercase
                    )
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onCloseTextAdjust()
                }
            )
        } else {
            onCloseTextAdjust()
        }
    }
}

/**
 * Dedicated Host for Phase 8 Sheets: Overlay Adjust (Opacity/Brightness/Contrast/Saturation) & Auto-Captions.
 * Isolated into a separate composable method to avoid JVM 64KB MethodTooLargeException in EditorScreen.
 */
@Composable
private fun Phase8SheetsHost(
    isOverlayAdjustVisible: Boolean,
    isAutoCaptionsVisible: Boolean,
    selectedOverlayId: String?,
    resolvedViewModel: com.flowcut.app.editor.EditorViewModel?,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit,
    onDismissOverlayAdjust: () -> Unit,
    onDismissAutoCaptions: () -> Unit
) {
    if (isOverlayAdjustVisible && selectedOverlayId != null) {
        val overlayItem = resolvedViewModel?.getOverlayItem(selectedOverlayId)
        if (overlayItem != null) {
            val origOpacity = remember(selectedOverlayId) { overlayItem.transform.opacity }
            val origBrightness = remember(selectedOverlayId) { overlayItem.adjustBrightness }
            val origContrast = remember(selectedOverlayId) { overlayItem.adjustContrast }
            val origSaturation = remember(selectedOverlayId) { overlayItem.adjustSaturation }

            OverlayAdjustSheet(
                initialOpacity = overlayItem.transform.opacity,
                initialBrightness = overlayItem.adjustBrightness,
                initialContrast = overlayItem.adjustContrast,
                initialSaturation = overlayItem.adjustSaturation,
                onLiveChange = { op, br, co, sa ->
                    resolvedViewModel.liveUpdateOverlayAdjust(selectedOverlayId, op, br, co, sa)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                },
                onDone = { op, br, co, sa ->
                    resolvedViewModel.updateOverlayAdjust(selectedOverlayId, op, br, co, sa)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onDismissOverlayAdjust()
                },
                onCancel = {
                    resolvedViewModel.liveUpdateOverlayAdjust(selectedOverlayId, origOpacity, origBrightness, origContrast, origSaturation)
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                    onDismissOverlayAdjust()
                }
            )
        } else {
            onDismissOverlayAdjust()
        }
    }

    if (isAutoCaptionsVisible) {
        val selectedItem = selectedOverlayId?.let { resolvedViewModel?.getOverlayItem(it) }
        val audioItem = if (selectedItem?.type == com.flowcut.app.engine.LayerType.AUDIO) selectedItem else null
        AutoCaptionsSheet(
            onGenerateCaptions = { phrases ->
                val startUs = audioItem?.startUs ?: 0L
                val endUs = audioItem?.endUs ?: 0L
                val added = resolvedViewModel?.generateCaptions(phrases, startUs, endUs) ?: 0
                if (added > 0) {
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                }
            },
            onGenerateFromText = { rawText ->
                val startUs = audioItem?.startUs ?: 0L
                val endUs = audioItem?.endUs ?: 0L
                val added = resolvedViewModel?.generateCaptionsFromText(rawText, startUs, endUs) ?: 0
                if (added > 0) {
                    previewRenderer.requestRender()
                    onWakeFrameDriver()
                }
            },
            onDismiss = onDismissAutoCaptions
        )
    }
}

@Composable
private fun OverlappingLayerConflictDialog(
    conflictItemIds: List<String>?,
    allLayers: List<com.flowcut.app.engine.OverlayItem>,
    selectedOverlayId: String?,
    onSelectOverlay: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (conflictItemIds == null) return
    val conflictItems = remember(conflictItemIds, allLayers) {
        conflictItemIds.mapNotNull { id -> allLayers.firstOrNull { it.id == id } }
    }

    if (conflictItems.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
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
                                val isSelected = item.id == selectedOverlayId
                                val isText = item.type == com.flowcut.app.engine.LayerType.TEXT
                                val isFilter = item.type == com.flowcut.app.engine.LayerType.FILTER
                                val isEffect = item.type == com.flowcut.app.engine.LayerType.EFFECT
                                val badgeBg = when (item.type) {
                                    com.flowcut.app.engine.LayerType.TEXT -> Color(0xFFF59E0B)
                                    com.flowcut.app.engine.LayerType.FILTER -> Color(0xFF06B6D4)
                                    com.flowcut.app.engine.LayerType.EFFECT -> Color(0xFFA855F7)
                                    com.flowcut.app.engine.LayerType.MOSAIC -> Color(0xFFD97706)
                                    else -> Color(0xFF68C95B)
                                }

                                Surface(
                                    modifier = Modifier
                                        .size(width = 48.dp, height = 34.dp)
                                        .clickable {
                                            onSelectOverlay(item.id)
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

@Composable
private fun BoxScope.FirstClipThumbnailOverlay(
    isFirstFrameLatched: Boolean,
    clips: List<com.flowcut.app.model.Clip>,
    targetCanvasAspect: Float = 9f / 16f
) {
    if (!USE_NEW_CORE || isFirstFrameLatched || clips.isEmpty()) return
    val firstClipPath = clips.firstOrNull()?.path ?: return
    var thumbBmp by remember(firstClipPath) {
        mutableStateOf(FirstClipThumbnailCache.get(firstClipPath))
    }
    LaunchedEffect(firstClipPath) {
        if (thumbBmp == null) {
            withContext(Dispatchers.IO) {
                val bmp = FirstClipThumbnailCache.get(firstClipPath)
                withContext(Dispatchers.Main) {
                    thumbBmp = bmp
                }
            }
        }
    }
    thumbBmp?.let { bmp ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val containerAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f
            val thumbMod = if (targetCanvasAspect > containerAspect) {
                Modifier.fillMaxWidth().aspectRatio(targetCanvasAspect)
            } else {
                Modifier.fillMaxHeight().aspectRatio(targetCanvasAspect)
            }
            Box(
                modifier = thumbMod
                    .clip(RectangleShape)
            ) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
        }
    }
}

private object FirstClipThumbnailCache {
    private val cache = android.util.LruCache<String, android.graphics.Bitmap>(4)

    fun get(path: String): android.graphics.Bitmap? {
        if (path.isEmpty()) return null
        val cached = cache.get(path)
        if (cached != null) return cached
        return try {
            val file = java.io.File(path)
            if (!file.exists()) return null
            val bmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(540, 960), null)
            } else {
                @Suppress("DEPRECATION")
                android.media.ThumbnailUtils.createVideoThumbnail(path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
            }
            if (bmp != null) {
                cache.put(path, bmp)
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}

@Composable
private fun TransitionSheetHost(
    selectedJunctionIndex: Int?,
    resolvedViewModel: EditorViewModel?,
    snapshotHolder: SnapshotHolder,
    playbackController: PlaybackController,
    frameDriver: FrameDriver,
    previewRenderer: PreviewRenderer,
    coroutineScope: CoroutineScope,
    onDeselectAll: () -> Unit
) {
    if (selectedJunctionIndex == null) return
    val j = selectedJunctionIndex
    val initialMode = resolvedViewModel?.getJunctionTransitionMode(j) ?: 0
    val maxDur = resolvedViewModel?.getMaxTransitionDuration(j) ?: 1000L
    val defaultDur = (maxDur / 2L).coerceAtLeast(100L)
    val existingDur = resolvedViewModel?.uiState?.value?.project?.transitions?.getOrNull(j)?.durationMs
    val initialDur = if (initialMode <= 0 || existingDur == null || existingDur <= 0L) {
        defaultDur
    } else {
        existingDur.coerceIn(100L, maxDur)
    }

    var previewJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(j) {
        onDispose {
            previewJob?.cancel()
            previewJob = null
        }
    }

    fun triggerAutoPreview() {
        previewJob?.cancel()
        val s = snapshotHolder.current
        if (j in 0 until s.main.n - 1) {
            val transStartUs = msToUs(s.main.startMs[j + 1])
            val transEndUs = msToUs(s.main.endMs[j])
            val previewStartUs = (transStartUs - 1_000_000L).coerceAtLeast(0L)
            playbackController.pause()
            playbackController.seekTo(previewStartUs)
            previewRenderer.requestRender()
            frameDriver.wake()
            previewJob = coroutineScope.launch {
                delay(80L)
                playbackController.play()
                frameDriver.wake()
                while (isActive) {
                    withFrameNanos { }
                    if (playbackController.playhead.mode != PlayMode.PLAYING) {
                        break
                    }
                    if (playbackController.playhead.timeUs >= transEndUs) {
                        playbackController.pause()
                        frameDriver.wake()
                        break
                    }
                }
            }
        }
    }

    TransitionSheet(
        activeJunctionIndex = j,
        initialMode = initialMode,
        initialDurationMs = initialDur,
        maxDurationMs = maxDur,
        onTransitionChanged = { mode, durMs ->
            resolvedViewModel?.setTransitionLive(j, mode, durMs)
            resolvedViewModel?.let { vm ->
                snapshotHolder.current = vm.getSnapshot()
            }
            previewRenderer.requestRender()
            frameDriver.wake()
            if (mode > 0) {
                triggerAutoPreview()
            }
        },
        onApplyToAll = { mode, durMs ->
            previewJob?.cancel()
            previewJob = null
            resolvedViewModel?.commitApplyTransitionToAll(mode, durMs)
            resolvedViewModel?.let { vm ->
                snapshotHolder.current = vm.getSnapshot()
            }
            previewRenderer.requestRender()
            frameDriver.wake()
            onDeselectAll()
        },
        onDone = { mode, durMs ->
            previewJob?.cancel()
            previewJob = null
            resolvedViewModel?.setTransitionLive(j, mode, durMs)
            resolvedViewModel?.commitTransition(j, mode, durMs)
            resolvedViewModel?.let { vm ->
                snapshotHolder.current = vm.getSnapshot()
            }
            previewRenderer.requestRender()
            frameDriver.wake()
            onDeselectAll()
        },
        onCancel = {
            previewJob?.cancel()
            resolvedViewModel?.cancelTransition()
            resolvedViewModel?.let { vm ->
                snapshotHolder.current = vm.getSnapshot()
            }
            previewRenderer.requestRender()
            frameDriver.wake()
            onDeselectAll()
        },
        onDismiss = {
            previewJob?.cancel()
            resolvedViewModel?.cancelTransition()
            resolvedViewModel?.let { vm ->
                snapshotHolder.current = vm.getSnapshot()
            }
            previewRenderer.requestRender()
            frameDriver.wake()
            onDeselectAll()
        }
    )
}

/**
 * Transparent touch-consuming barrier that intercepts all pointer events
 * when [blocked] is true. Emits nothing when [blocked] is false.
 */
@Composable
private fun TouchBlocker(
    blocked: Boolean,
    modifier: Modifier = Modifier
) {
    if (blocked) {
        Box(
            modifier = modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        )
    }
}

/**
 * Top App Bar with back button, project title, aspect ratio badge, export button,
 * and touch-blocking overlay when loader is active.
 */
@Composable
private fun EditorTopBar(
    projectTitle: String = "Project 1",
    hasClips: Boolean,
    isLoaderActive: Boolean = false,
    colors: FlowCutColors,
    aspectRatioLabel: String = "9:16",
    onRatioClick: () -> Unit = {},
    onRenameProject: (String) -> Unit = {},
    onBackClick: () -> Unit,
    onExportButtonClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDraftText by remember(projectTitle) { mutableStateOf(projectTitle) }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = colors.surface1,
            title = {
                Text(
                    text = "Rename Project",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            },
            text = {
                OutlinedTextField(
                    value = renameDraftText,
                    onValueChange = { if (it.length <= 40) renameDraftText = it },
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = colors.surface2,
                        unfocusedContainerColor = colors.surface2,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedIndicatorColor = colors.accent,
                        unfocusedIndicatorColor = colors.border,
                        cursorColor = colors.accent
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameDraftText.trim()
                        if (trimmed.isNotEmpty()) onRenameProject(trimmed)
                        showRenameDialog = false
                    }
                ) {
                    Text("Save", color = colors.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
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
                text = projectTitle,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        renameDraftText = projectTitle
                        showRenameDialog = true
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .background(Color(0xFF24242A), RoundedCornerShape(14.dp))
                    .clickable { onRatioClick() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = aspectRatioLabel,
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
        TouchBlocker(blocked = isLoaderActive, modifier = Modifier.matchParentSize())
    }
}

/**
 * On-Preview Free Transform Gestures overlay when a clip is selected.
 */
@Composable
private fun PreviewClipTransformOverlay(
    activeClipId: String,
    activeClip: com.flowcut.app.model.Clip?,
    selectedClipIndex: Int,
    resolvedViewModel: EditorViewModel?,
    previewRenderer: PreviewRenderer,
    frameDriver: FrameDriver,
    snapshotHolder: com.flowcut.app.core.timeline.SnapshotHolder,
    triggerApplyToAllPill: () -> Unit,
    targetCanvasAspect: Float = 9f / 16f,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    var isTransforming by remember { mutableStateOf(false) }
    var guideSnapCenterX by remember { mutableStateOf(false) }
    var guideSnapCenterY by remember { mutableStateOf(false) }
    var guideSnapEdgeLeft by remember { mutableStateOf(false) }
    var guideSnapEdgeRight by remember { mutableStateOf(false) }
    var guideSnapEdgeTop by remember { mutableStateOf(false) }
    var guideSnapEdgeBottom by remember { mutableStateOf(false) }
    var guideBadgeText by remember { mutableStateOf<String?>(null) }

    val currentCanvasAspect by rememberUpdatedState(targetCanvasAspect)
    val currentViewModel by rememberUpdatedState(resolvedViewModel)
    val currentClip by rememberUpdatedState(activeClip)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(selectedClipIndex, activeClipId) {
                var lastTapTimeMs = 0L
                var lastTapPosition = Offset.Zero

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    val downPos = down.position
                    var isDragOrTransform = false

                    val initialTransform = currentViewModel?.getClipTransform(activeClipId)
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
                        val containerAspect = if (outerH > 0f) outerW / outerH else 1f
                        val aScreen = currentCanvasAspect
                        val previewW: Float
                        val previewH: Float
                        if (aScreen > containerAspect) {
                            previewW = outerW
                            previewH = (outerW / aScreen).coerceAtMost(outerH).coerceAtLeast(1f)
                        } else {
                            previewH = outerH
                            previewW = (outerH * aScreen).coerceAtMost(outerW).coerceAtLeast(1f)
                        }

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

                            // 2. Scale Snap (smooth magnetic snap within tight 0.03f threshold)
                            val diffScale = kotlin.math.abs(rawScale - 1.0f)
                            val snapScale100 = diffScale < 0.03f
                            val snappedScale = if (snapScale100) 1.0f else rawScale

                            // 3. Effective Dimensions for Edge Snapping (True FIT-mode matching PreviewRenderer)
                            val dispW = currentClip?.displayWidth ?: 1080
                            val dispH = currentClip?.displayHeight ?: 1920
                            val aClip = (dispW.toFloat() / dispH.toFloat()).coerceAtLeast(0.01f)

                            val cardinalAngle = ((Math.round(snappedRotationDeg / 90f) * 90) % 360 + 360) % 360
                            val isRot90or270 = (cardinalAngle == 90 || cardinalAngle == 270) && kotlin.math.abs(snappedRotationDeg - cardinalAngle) < 15f
                            val effAspect = if (isRot90or270) (1.0f / aClip) else aClip

                            // True FIT-mode: when effAspect >= aScreen, width fits canvas (hw = 0.5f), height letterboxed (hh = 0.5f * (aScreen / effAspect))
                            val baseHw = if (effAspect >= aScreen) 0.5f else 0.5f * (effAspect / aScreen)
                            val baseHh = if (effAspect >= aScreen) 0.5f * (aScreen / effAspect) else 0.5f
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
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
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
                        currentViewModel?.commitClipTransform(selectedClipIndex, currentTransform)
                        if (USE_NEW_CORE) {
                            currentViewModel?.let { vm ->
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
                            currentViewModel?.commitClipTransform(selectedClipIndex, resetTransform)
                            if (USE_NEW_CORE) {
                                currentViewModel?.let { vm ->
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
        // Aspect-locked Guide Overlay Canvas & Badge
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val containerAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f
            val guideMod = if (targetCanvasAspect > containerAspect) {
                Modifier.fillMaxWidth().aspectRatio(targetCanvasAspect)
            } else {
                Modifier.fillMaxHeight().aspectRatio(targetCanvasAspect)
            }
            Box(
                modifier = guideMod
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
