/**
 * Feature:     speed
 * File role:   commands
 * Responsible: Translates speed changes into pure timeline edit commands
 * Depends on:  core/edit, core/model, core/time, features/speed/SpeedLogic
 * Used by:     SpeedUi, Index
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.speed

import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.edit.SetTimeMapCommand
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.model.map

/**
 * Command builder for clip speed operations.
 */
object SpeedCommands {

    /**
     * Creates a command to change speed on a single clip.
     */
    fun createSpeedCommand(
        clipIndex: Int,
        clip: Clip,
        speed: Float
    ): Command {
        val newMap = SpeedLogic.createSpeedTimeMap(
            currentMap = clip.map(),
            clipDurationMs = clip.durationMs,
            speed = speed
        )
        return SetTimeMapCommand(
            clipIndex = clipIndex,
            timeMap = newMap,
            label = "Change Clip Speed",
            coalesceKey = "speed_clip_$clipIndex"
        )
    }

    /**
     * Creates a command to apply the same speed across all clips in the project.
     */
    fun createApplyAllSpeedCommand(
        speed: Float
    ): Command = object : Command {
        override val label: String = "Apply Speed to All Clips"
        override val pausesPlayback: Boolean = true
        override val coalesceKey: String = "speed_apply_all"

        override fun apply(p: Project): Project {
            var updated = p
            for (i in p.clips.indices) {
                val clip = updated.clips[i]
                val cmd = createSpeedCommand(i, clip, speed)
                updated = cmd.apply(updated)
            }
            return updated
        }
    }
}
