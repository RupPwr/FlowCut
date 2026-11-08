package com.flowcut.app.ui

import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flowcut.app.ALL_FEATURES
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
    onCommitTrim: (clipIndex: Int, newInUs: Long, newOutUs: Long, newDurationMs: Long) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val colors = FlowCutTheme.colors
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
                PreviewBox(
                    hasClips = hasClips,
                    selectedTransitionName = selectedTransitionName,
                    onAddVideoClick = onAddClipsClick,
                    glSurfaceView = if (USE_NEW_CORE && previewRenderer.glSurfaceView != null) previewRenderer.glSurfaceView else glSurfaceView,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 220.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Playback row: 40 dp
                PlaybackRow(
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onUndoClick = onUndoClick,
                    onRedoClick = onRedoClick,
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

            // 7. Bottom toolbar: 64 dp + navigation bar inset
            BottomToolbar(
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
    }
}
