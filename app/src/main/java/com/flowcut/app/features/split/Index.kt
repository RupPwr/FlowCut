/**
 * Feature:     split
 * File role:   index
 * Responsible: Public API of the split feature module
 * Depends on:  core/edit, core/model, core/time, features/split/SplitCommands, features/split/SplitLogic
 * Used by:     app, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.split

import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.model.Clip

/**
 * Public entry point for the Split feature.
 * External modules must ONLY import from this file.
 */
object SplitFeature {
    const val ID = "split"

    /**
     * Evaluates whether a clip can be split at the current playhead position.
     */
    fun canSplit(
        clipIndex: Int,
        clip: Clip,
        clipStartMs: Long,
        playheadUs: Long
    ): Boolean {
        return SplitLogic.calculateSplit(clipIndex, clip, clipStartMs, playheadUs) is SplitValidationResult.Valid
    }

    /**
     * Creates a command to split the target clip if valid, or null with error if invalid.
     */
    fun splitClip(
        clipIndex: Int,
        clip: Clip,
        clipStartMs: Long,
        playheadUs: Long
    ): Pair<Command?, String?> {
        return when (val res = SplitLogic.calculateSplit(clipIndex, clip, clipStartMs, playheadUs)) {
            is SplitValidationResult.Valid -> {
                val cmd = SplitClipCommand(
                    clipIndex = clipIndex,
                    leftClip = res.leftClip,
                    rightClip = res.rightClip
                )
                Pair(cmd, null)
            }
            is SplitValidationResult.Invalid -> Pair(null, res.reason)
        }
    }
}
