package com.flowcut.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.features.filter.AdjustSheet as FeatureAdjustSheet
import com.flowcut.app.features.filter.AdjustTool as FeatureAdjustTool

/**
 * Backward compatibility alias for AdjustSheet and AdjustTool delegating directly
 * to features/filter/Index.kt.
 * Adheres to Section 2 (public API rule) of ARCHITECTURE_RULES.md.
 */
typealias AdjustTool = FeatureAdjustTool

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
    FeatureAdjustSheet(
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
