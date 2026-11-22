/**
 * Feature:     text
 * File role:   commands
 * Responsible: Pure command builders for text overlay operations
 * Depends on:  core/edit, core/model, features/text/TextLogic
 * Used by:     TextUi, Index
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.text

import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.model.Project

/**
 * Command builder for text overlay operations.
 */
object TextCommands {

    fun createUpdateTextCommand(
        overlayId: String,
        newText: String,
        onCommit: (overlayId: String, text: String) -> Unit
    ): Command = object : Command {
        override val label: String = "Update Text"
        override val pausesPlayback: Boolean = false
        override val coalesceKey: String = "text_$overlayId"

        override fun apply(p: Project): Project {
            onCommit(overlayId, newText)
            return p
        }
    }

    fun createUpdateTextStyleCommand(
        overlayId: String,
        style: TextStyleSpec,
        onCommit: (overlayId: String, style: TextStyleSpec) -> Unit
    ): Command = object : Command {
        override val label: String = "Update Text Style"
        override val pausesPlayback: Boolean = false
        override val coalesceKey: String = "text_style_$overlayId"

        override fun apply(p: Project): Project {
            onCommit(overlayId, style)
            return p
        }
    }
}
