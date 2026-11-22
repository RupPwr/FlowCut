package com.flowcut.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.features.speed.SpeedSheet as FeatureSpeedSheet
import com.flowcut.app.model.Project

/**
 * Backward compatibility alias for SpeedSheet delegating directly to features/speed/Index.kt.
 * Adheres to Section 2 (public API rule) of ARCHITECTURE_RULES.md.
 */
@Composable
fun SpeedSheet(
    clipIndex: Int,
    snapshotHolder: SnapshotHolder,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    getClipSpeed: (clipId: String) -> Float,
    getMaxSpeedForClip: (clipIndex: Int) -> Float,
    onSetLiveSpeed: (clipIndex: Int, speed: Float) -> Unit,
    onCommitSingleSpeed: (clipIndex: Int, speed: Float, initialProject: Project, initialTimeMaps: Map<String, TimeMap>) -> Unit,
    onCommitApplyAllSpeed: (speed: Float, initialProject: Project, initialTimeMaps: Map<String, TimeMap>) -> Unit,
    onRevertSpeed: (initialProject: Project, initialTimeMaps: Map<String, TimeMap>) -> Unit,
    initialProject: Project,
    initialTimeMaps: Map<String, TimeMap>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    FeatureSpeedSheet(
        clipIndex = clipIndex,
        snapshotHolder = snapshotHolder,
        previewRenderer = previewRenderer,
        onWakeFrameDriver = onWakeFrameDriver,
        getClipSpeed = getClipSpeed,
        getMaxSpeedForClip = getMaxSpeedForClip,
        onSetLiveSpeed = onSetLiveSpeed,
        onCommitSingleSpeed = onCommitSingleSpeed,
        onCommitApplyAllSpeed = onCommitApplyAllSpeed,
        onRevertSpeed = onRevertSpeed,
        initialProject = initialProject,
        initialTimeMaps = initialTimeMaps,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
