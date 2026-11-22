/**
 * Feature:     filter
 * File role:   index
 * Responsible: Public API of the filter feature module
 * Depends on:  features/filter/FilterLogic, features/filter/FilterUi
 * Used by:     app, ui
 * Never touches: Player directly, other features
 */
package com.flowcut.app.features.filter

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.PreviewRenderer

/**
 * Public entry point for the Filter feature.
 * External modules must ONLY import from this file.
 */
object FilterFeature {
    const val ID = "filter"

    val DEFAULT_ADJUST: ClipColorAdjust = ClipColorAdjust.DEFAULT

    fun getToolValue(adjust: ClipColorAdjust, tool: AdjustTool): Float =
        FilterLogic.getToolValue(adjust, tool)

    fun withToolValue(adjust: ClipColorAdjust, tool: AdjustTool, normalized: Float): ClipColorAdjust =
        FilterLogic.withToolValue(adjust, tool, normalized)
}

/**
 * Public Composable for the Adjust bottom sheet.
 */
@Composable
fun AdjustSheet(
    clipIndex: Int,
    snapshotHolder: SnapshotHolder,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    getClipColorAdjust: (clipId: String) -> ClipColorAdjust,
    onCommitSingleAdjust: (clipIndex: Int, adjust: ClipColorAdjust) -> Unit,
    onCommitApplyAllAdjust: (adjust: ClipColorAdjust) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AdjustSheetUi(
        clipIndex = clipIndex,
        snapshotHolder = snapshotHolder,
        previewRenderer = previewRenderer,
        onWakeFrameDriver = onWakeFrameDriver,
        getClipColorAdjust = getClipColorAdjust,
        onCommitSingleAdjust = onCommitSingleAdjust,
        onCommitApplyAllAdjust = onCommitApplyAllAdjust,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
