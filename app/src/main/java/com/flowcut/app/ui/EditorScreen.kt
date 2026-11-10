package com.flowcut.app.ui

import android.view.View
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flowcut.app.ALL_FEATURES
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.flowcut.app.audio.AudioEngine
import com.flowcut.app.audio.AudioFeature
import com.flowcut.app.audio.ClipAudioContributor
import com.flowcut.app.core.feature.DefaultFeatureRegistry
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
import com.flowcut.app.ui.timeline.TimelineSelection
import com.flowcut.app.ui.timeline.TimelineZoomState

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

    val previewRenderer = remember {
        PreviewRenderer(
            holder = snapshotHolder,
            playhead = playbackController.playhead,
            context = context
        )
    }

    val frameDriver = remember(playbackController, previewRenderer) {
        var lastReportedSec = -1L
        var lastReportedMode = PlayMode.PAUSED

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
                previewRenderer.requestRender()
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
    var isAdjustSheetVisible by remember { mutableStateOf(false) }
    var isBackgroundSheetVisible by remember { mutableStateOf(false) }

    val handleOpenTrim: () -> Unit = {
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
            if (playbackController.playhead.mode == PlayMode.PLAYING) {
                playbackController.pause()
                frameDriver.wake()
                onPlaybackStateChange(false)
            }
            isVolumeSheetVisible = false
            isAdjustSheetVisible = false
            isBackgroundSheetVisible = false
            isTrimSheetVisible = true
        } else {
            Toast.makeText(context, "No clip selected or under playhead", Toast.LENGTH_SHORT).show()
        }
    }

    val vmUiState = resolvedViewModel?.uiState?.collectAsState()?.value
    val isMainTrackMuted = vmUiState?.isMainTrackMuted ?: false
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
                // 1. Top bar: 48 dp
                TopBar(
                    onBackClick = onBackClick,
                    onExportClick = {
                        if (!hasClips) {
                            Toast.makeText(context, "Add clips first", Toast.LENGTH_SHORT).show()
                        } else {
                            onExportButtonClick()
                        }
                    }
                )

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

                // 3. Playback row: 40 dp
                PlaybackRow(
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
                    totalMs = timeline.total,
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

                Spacer(modifier = Modifier.height(8.dp))

                // 4. Timeline block: 240 dp (New Core TimelineCanvas or legacy TimelineBlock)
                if (USE_NEW_CORE) {
                    val selection = remember(selectedClipIndex, selectedJunctionIndex) {
                        selectedClipIndex?.let { TimelineSelection.ClipSelected(it) }
                            ?: selectedJunctionIndex?.let { TimelineSelection.JunctionSelected(it) }
                            ?: TimelineSelection.None
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(colors.surface1, RoundedCornerShape(8.dp))
                    ) {
                        TimelineCanvas(
                            holder = snapshotHolder,
                            playhead = playbackController.playhead,
                            controller = playbackController,
                            zoomState = zoomState,
                            frameTick = frameTick,
                            selection = selection,
                            onSelect = { sel ->
                                when (sel) {
                                    is TimelineSelection.ClipSelected -> onSelectClip(sel.index)
                                    is TimelineSelection.JunctionSelected -> onSelectJunction(sel.index)
                                    TimelineSelection.None -> onDeselectAll()
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
                                resolvedViewModel?.commitClipTrim(k, inUs, outUs)
                                onCommitTrim(k, inUs, outUs, durMs)
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

            // 7. Bottom toolbar: 64 dp + navigation bar inset (Contextual Main vs Clip toolbar)
            BottomToolbar(
                isClipSelected = selectedClipIndex != null,
                onSplitClick = onSplitClick ?: handleSplit,
                onTrimClick = handleOpenTrim,
                onVolumeClick = {
                    isAdjustSheetVisible = false
                    isBackgroundSheetVisible = false
                    isTrimSheetVisible = false
                    isVolumeSheetVisible = true
                },
                onAdjustClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    isVolumeSheetVisible = false
                    isBackgroundSheetVisible = false
                    isTrimSheetVisible = false
                    isAdjustSheetVisible = true
                },
                onBackgroundClick = {
                    if (playbackController.playhead.mode == PlayMode.PLAYING) {
                        playbackController.pause()
                        frameDriver.wake()
                        onPlaybackStateChange(false)
                    }
                    isVolumeSheetVisible = false
                    isAdjustSheetVisible = false
                    isTrimSheetVisible = false
                    isBackgroundSheetVisible = true
                },
                onRotateClick = onRotateClip ?: {
                    resolvedViewModel?.rotateSelectedClip()
                    if (USE_NEW_CORE) {
                        resolvedViewModel?.let { vm ->
                            snapshotHolder.current = vm.getSnapshot()
                        }
                    }
                    previewRenderer.requestRender()
                    frameDriver.wake()
                    triggerApplyToAllPill()
                },
                onFlipClick = onFlipClip ?: {
                    resolvedViewModel?.flipSelectedClip()
                    if (USE_NEW_CORE) {
                        resolvedViewModel?.let { vm ->
                            snapshotHolder.current = vm.getSnapshot()
                        }
                    }
                    previewRenderer.requestRender()
                    frameDriver.wake()
                    triggerApplyToAllPill()
                },
                onDeleteClipClick = selectedClipIndex?.let { k ->
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
                onClearSelection = onDeselectAll,
                onLockedItemClick = {
                    Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
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

        // Trim BottomSheet / Panel for selected clip
        if (isTrimSheetVisible && selectedClipIndex != null) {
            TrimSheet(
                clipIndex = selectedClipIndex,
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
    }
}
