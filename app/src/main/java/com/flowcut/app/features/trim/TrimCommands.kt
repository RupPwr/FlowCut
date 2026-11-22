/**
 * Feature:     trim
 * File role:   commands
 * Responsible: Translates trim user intent into pure timeline commands
 * Depends on:  core/edit, core/model, core/time, features/trim/TrimLogic
 * Used by:     TrimUi, Index
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.trim

import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.edit.SetTimeMapCommand
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.map

/**
 * Command builder for clip trim operations.
 */
object TrimCommands {

    /**
     * Creates a [SetTimeMapCommand] to apply the trim to the specified clip.
     */
    fun createTrimCommand(
        clipIndex: Int,
        clip: Clip,
        newInUs: Long,
        newOutUs: Long
    ): Command {
        val newMap = TrimLogic.createTrimmedTimeMap(
            currentMap = clip.map(),
            clipDurationMs = clip.durationMs,
            newInUs = newInUs,
            newOutUs = newOutUs
        )
        return SetTimeMapCommand(
            clipIndex = clipIndex,
            timeMap = newMap,
            label = "Trim Clip",
            coalesceKey = "trim_clip_$clipIndex"
        )
    }
}
