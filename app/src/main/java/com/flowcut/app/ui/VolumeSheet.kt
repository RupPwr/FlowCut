package com.flowcut.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.features.audio.VolumeSheet as FeatureVolumeSheet

/**
 * Backward compatibility alias for VolumeSheet delegating directly to features/audio/Index.kt.
 * Adheres to Section 2 (public API rule) of ARCHITECTURE_RULES.md.
 */
@Composable
fun VolumeSheet(
    clipIndex: Int,
    snapshotHolder: SnapshotHolder,
    getClipVolume: (clipId: String) -> Float,
    getPreMuteVolume: (clipId: String) -> Float = { 1.0f },
    onSetLiveVolume: (clipId: String, volume: Float) -> Unit,
    onCommitSingleVolume: (clipIndex: Int, volume: Float) -> Unit,
    onCommitApplyAllVolume: (volume: Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    FeatureVolumeSheet(
        clipIndex = clipIndex,
        snapshotHolder = snapshotHolder,
        getClipVolume = getClipVolume,
        getPreMuteVolume = getPreMuteVolume,
        onSetLiveVolume = onSetLiveVolume,
        onCommitSingleVolume = onCommitSingleVolume,
        onCommitApplyAllVolume = onCommitApplyAllVolume,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
