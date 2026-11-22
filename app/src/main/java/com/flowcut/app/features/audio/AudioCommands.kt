/**
 * Feature:     audio
 * File role:   commands
 * Responsible: Pure timeline edit commands for audio levels and mute state
 * Depends on:  core/edit, core/model, features/audio/AudioLogic
 * Used by:     AudioUi, Index
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.audio

import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.model.Project

/**
 * Command builder for audio operations.
 */
object AudioCommands {

    /**
     * Creates a command to set volume on a specific clip.
     */
    fun createSetVolumeCommand(
        clipIndex: Int,
        clipId: String,
        volume: Float,
        onCommit: (clipIndex: Int, volume: Float) -> Unit
    ): Command = object : Command {
        override val label: String = "Set Clip Volume"
        override val pausesPlayback: Boolean = false
        override val coalesceKey: String = "volume_$clipId"

        override fun apply(p: Project): Project {
            onCommit(clipIndex, volume.coerceIn(AudioLogic.MIN_VOLUME, AudioLogic.MAX_VOLUME))
            return p
        }
    }

    /**
     * Creates a command to apply volume across all clips.
     */
    fun createApplyAllVolumeCommand(
        volume: Float,
        onCommitAll: (volume: Float) -> Unit
    ): Command = object : Command {
        override val label: String = "Apply Volume to All Clips"
        override val pausesPlayback: Boolean = false
        override val coalesceKey: String = "volume_all"

        override fun apply(p: Project): Project {
            onCommitAll(volume.coerceIn(AudioLogic.MIN_VOLUME, AudioLogic.MAX_VOLUME))
            return p
        }
    }
}
