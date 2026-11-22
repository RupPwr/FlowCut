package com.flowcut.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.engine.FrameDriver
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.features.trim.TrimSheet as FeatureTrimSheet

/**
 * Backward compatibility alias for TrimSheet delegating directly to features/trim/Index.kt.
 * Adheres to Section 2 (public API rule) of ARCHITECTURE_RULES.md.
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
    FeatureTrimSheet(
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
