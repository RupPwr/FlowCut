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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flowcut.app.model.AUTO_PLAY_MARGIN_MS
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.Project
import com.flowcut.app.model.Transition
import com.flowcut.app.model.TransitionType

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = FlowCutTheme.colors

    val hasClips = timeline.n > 0
    val selectedTransitionName = selectedJunctionIndex?.let { j ->
        val t = timeline.project.transitions.getOrNull(j)?.type
        if (t != null && t != TransitionType.NONE) t.displayName else null
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
                    glSurfaceView = glSurfaceView,
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
                    currentMs = currentMs,
                    totalMs = timeline.total,
                    isPlaying = isPlaying,
                    onPlayPauseClick = onPlayPauseClick,
                    onSeekScrub = onSeekScrub
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 4. Timeline block: 96 dp
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

                // Preserves exact screen position, height and spacing of Timeline (128 dp)
                Spacer(modifier = Modifier.height(128.dp))
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

