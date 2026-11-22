/**
 * Feature:     audio
 * File role:   index
 * Responsible: Public API of the audio feature module
 * Depends on:  core/edit, core/model, features/audio/AudioUi, features/audio/AudioLogic
 * Used by:     app, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.audio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.timeline.SnapshotHolder

/**
 * Public entry point for the Audio feature.
 * External modules must ONLY import from this file.
 */
object AudioFeatureModule {
    const val ID = "audio"

    /**
     * Converts volume gain to percentage integer.
     */
    fun volumeToPercent(volume: Float): Int = AudioLogic.volumeToPercentage(volume)

    /**
     * Converts percentage integer to volume gain.
     */
    fun percentToVolume(percent: Int): Float = AudioLogic.percentageToVolume(percent)

    /**
     * Creates a command to change clip volume.
     */
    fun setVolume(
        clipIndex: Int,
        clipId: String,
        volume: Float,
        onCommit: (clipIndex: Int, volume: Float) -> Unit
    ): Command = AudioCommands.createSetVolumeCommand(clipIndex, clipId, volume, onCommit)
}

/**
 * Public Composable for the Volume bottom sheet.
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
    VolumeSheetUi(
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
