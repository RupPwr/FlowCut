/**
 * Feature:     trim
 * File role:   ui
 * Responsible: Bottom sheet UI container, header actions, and lifecycle for trim
 * Depends on:  core/timeline, core/time, engine/preview, shared, features/trim/TrimTrackCanvas
 * Used by:     Index, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.trim

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.core.model.map
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.FrameDriver
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.shared.TimeFormatters
import com.flowcut.app.ui.FlowCutTheme

@Composable
fun TrimSheetUi(
    clipIndex: Int = -1,
    snapshotHolder: SnapshotHolder,
    previewRenderer: PreviewRenderer,
    frameDriver: FrameDriver,
    getClipNaturalDurationUs: (Int) -> Long = { 0L },
    onCommitTrim: (clipIndex: Int, newInUs: Long, newOutUs: Long) -> Unit = { _, _, _ -> },
    onCommitGlobalTrim: (globalStartUs: Long, globalEndUs: Long) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors
    val originalSnapshot = remember { snapshotHolder.current }
    val isGlobal = clipIndex < 0

    val clip = if (!isGlobal) originalSnapshot.project.clips.getOrNull(clipIndex) else null
    if (!isGlobal && clip == null) {
        onDismiss()
        return
    }

    val initialMap = remember(clip) { clip?.map() }
    val maxSourceUs = remember(clipIndex, clip, initialMap, isGlobal, originalSnapshot) {
        if (isGlobal) {
            maxOf(msToUs(originalSnapshot.main.totalMs), 200_000L)
        } else {
            val reported = getClipNaturalDurationUs(clipIndex)
            maxOf(initialMap?.outUs ?: 200_000L, reported, msToUs(clip?.durationMs ?: 200L), 200_000L)
        }
    }

    var tempInUs by remember {
        mutableLongStateOf(
            if (isGlobal) 0L else (initialMap?.inUs?.coerceIn(0L, maxOf(0L, (initialMap.outUs) - 200_000L)) ?: 0L)
        )
    }
    var tempOutUs by remember {
        mutableLongStateOf(
            if (isGlobal) maxSourceUs else (initialMap?.outUs?.coerceIn(tempInUs + 200_000L, maxSourceUs) ?: maxSourceUs)
        )
    }

    LaunchedEffect(clipIndex) {
        previewRenderer.trimPreviewTimeUs = if (isGlobal) 0L else msToUs(snapshotHolder.current.main.startMs.getOrElse(clipIndex) { 0L })
        frameDriver.wake()
    }

    DisposableEffect(Unit) {
        onDispose {
            previewRenderer.trimPreviewTimeUs = -1L
            frameDriver.wake()
        }
    }

    val handleClose = {
        previewRenderer.trimPreviewTimeUs = -1L
        snapshotHolder.current = originalSnapshot
        frameDriver.wake()
        onDismiss()
    }

    val handleDone = {
        previewRenderer.trimPreviewTimeUs = -1L
        if (isGlobal) {
            onCommitGlobalTrim(tempInUs, tempOutUs)
        } else {
            onCommitTrim(clipIndex, tempInUs, tempOutUs)
        }
        frameDriver.wake()
        onDismiss()
    }

    BackHandler { handleClose() }

    val junctionTicksUs = remember(originalSnapshot, isGlobal) {
        if (isGlobal && originalSnapshot.main.n > 1) {
            (0 until originalSnapshot.main.n - 1).map { msToUs(originalSnapshot.main.endMs[it]) }
        } else emptyList()
    }

    val updateSnapshotAndPreview: (Long, Long, Boolean) -> Unit = { inUs, outUs, isLeft ->
        if (isGlobal) {
            previewRenderer.trimPreviewTimeUs = if (isLeft) inUs else maxOf(0L, outUs - 1_000L)
            frameDriver.wake()
        } else if (clip != null) {
            val cmd = TrimCommands.createTrimCommand(clipIndex, clip, inUs, outUs)
            val newProject = cmd.apply(snapshotHolder.current.project)
            snapshotHolder.current = TimelineSnapshot(System.nanoTime(), newProject)

            val trimmedEdgeUs = if (isLeft) {
                msToUs(snapshotHolder.current.main.startMs.getOrElse(clipIndex) { 0L })
            } else {
                val endMs = snapshotHolder.current.main.endMs.getOrElse(clipIndex) { 0L }
                val startMs = snapshotHolder.current.main.startMs.getOrElse(clipIndex) { 0L }
                maxOf(msToUs(startMs), msToUs(endMs) - 1_000L)
            }
            previewRenderer.trimPreviewTimeUs = trimmedEdgeUs
            frameDriver.wake()
        }
    }

    val trackMarginDp = 28.dp
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { handleClose() }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 10.dp, bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = handleClose) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Discard trim and close", tint = colors.textPrimary, modifier = Modifier.size(24.dp))
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(text = if (isGlobal) "Trim Timeline" else "Trim Clip", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${TimeFormatters.formatDurationUs(tempOutUs - tempInUs)} / ${TimeFormatters.formatDurationUs(maxSourceUs)}",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }

                    IconButton(onClick = handleDone) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = "Commit trim", tint = colors.accent, modifier = Modifier.size(24.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TrimTrackCanvas(
                    tempInUs = tempInUs,
                    tempOutUs = tempOutUs,
                    maxSourceUs = maxSourceUs,
                    isGlobal = isGlobal,
                    junctionTicksUs = junctionTicksUs,
                    onTrimChange = { newIn, newOut, isLeft ->
                        tempInUs = newIn
                        tempOutUs = newOut
                        updateSnapshotAndPreview(newIn, newOut, isLeft)
                    },
                    trackMarginDp = trackMarginDp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = trackMarginDp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "0:00.0", color = colors.textSecondary, fontSize = 11.sp)
                    Text(text = TimeFormatters.formatDurationUs(maxSourceUs), color = colors.textSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}
