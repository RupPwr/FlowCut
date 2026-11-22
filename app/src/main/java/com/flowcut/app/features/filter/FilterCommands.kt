/**
 * Feature:     filter
 * File role:   commands
 * Responsible: Pure timeline edit commands for video filters and color grading
 * Depends on:  core/edit, core/model, engine/ClipColorAdjust
 * Used by:     FilterUi, Index
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.filter

import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.model.Project
import com.flowcut.app.engine.ClipColorAdjust

/**
 * Command builder for filter and color adjustment operations.
 */
object FilterCommands {

    fun createSetAdjustCommand(
        clipIndex: Int,
        clipId: String,
        adjust: ClipColorAdjust,
        onCommit: (clipIndex: Int, adjust: ClipColorAdjust) -> Unit
    ): Command = object : Command {
        override val label: String = "Set Color Adjust"
        override val pausesPlayback: Boolean = false
        override val coalesceKey: String = "adjust_$clipId"

        override fun apply(p: Project): Project {
            onCommit(clipIndex, adjust)
            return p
        }
    }

    fun createApplyAllAdjustCommand(
        adjust: ClipColorAdjust,
        onCommitAll: (adjust: ClipColorAdjust) -> Unit
    ): Command = object : Command {
        override val label: String = "Apply Color Adjust to All Clips"
        override val pausesPlayback: Boolean = false
        override val coalesceKey: String = "adjust_all"

        override fun apply(p: Project): Project {
            onCommitAll(adjust)
            return p
        }
    }
}
