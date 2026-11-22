/**
 * Feature:     trim
 * File role:   index
 * Responsible: Public API of the trim feature module
 * Depends on:  core/edit, core/model, core/time, features/trim/TrimUi
 * Used by:     app, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.trim

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.engine.FrameDriver
import com.flowcut.app.engine.PreviewRenderer

/**
 * Public entry point for the Trim feature.
 * External modules must ONLY import from this file.
 */
object TrimFeature {
    const val ID = "trim"

    /**
     * Creates a command to trim the given clip.
     */
    fun trimClip(clipIndex: Int, clip: Clip, newInUs: Long, newOutUs: Long): Command {
        return TrimCommands.createTrimCommand(clipIndex, clip, newInUs, newOutUs)
    }

    /**
     * Pure validation / boundary calculation for a trim range.
     */
    fun calculateTrimRange(inUs: Long, outUs: Long, maxSourceUs: Long): Pair<Long, Long> {
        return TrimLogic.clampTrimRange(inUs, outUs, maxSourceUs)
    }

    /**
     * Pure TimeMap generator.
     */
    fun createTimeMap(currentMap: TimeMap?, clipDurationMs: Long, inUs: Long, outUs: Long): TimeMap {
        return TrimLogic.createTrimmedTimeMap(currentMap, clipDurationMs, inUs, outUs)
    }
}

/**
 * Public Composable for the Trim bottom sheet.
 */
@Composable
fun TrimSheet(
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
    TrimSheetUi(
        clipIndex = clipIndex,
        snapshotHolder = snapshotHolder,
        previewRenderer = previewRenderer,
        frameDriver = frameDriver,
        getClipNaturalDurationUs = getClipNaturalDurationUs,
        onCommitTrim = onCommitTrim,
        onCommitGlobalTrim = onCommitGlobalTrim,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
