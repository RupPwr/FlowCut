/**
 * Feature:     text
 * File role:   index
 * Responsible: Public API of the text feature module
 * Depends on:  features/text/TextLogic, features/text/TextCommands, features/text/TextUi
 * Used by:     app, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.feature.Feature
import com.flowcut.app.core.feature.FeatureRegistry

/**
 * Public entry point for the Text feature.
 * External modules must ONLY import from this file.
 */
object TextFeature : Feature {
    const val ID = "text"
    override val id: String = ID

    override fun register(r: FeatureRegistry) {
        r.itemType(TextLayerRenderer.KIND_TEXT)
        r.layerRenderer(TextLayerRenderer())
    }

    fun calculateAspect(text: String): Float = TextLogic.calculateTextAspect(text)

    fun updateText(overlayId: String, newText: String, onCommit: (String, String) -> Unit): Command =
        TextCommands.createUpdateTextCommand(overlayId, newText, onCommit)

    fun updateStyle(overlayId: String, style: TextStyleSpec, onCommit: (String, TextStyleSpec) -> Unit): Command =
        TextCommands.createUpdateTextStyleCommand(overlayId, style, onCommit)
}

/**
 * Public Composable for Text Editor bottom sheet.
 */
@Composable
fun TextEditorSheet(
    initialText: String,
    initialStyle: TextStyleSpec = TextStyleSpec(),
    onCommit: (newText: String, style: TextStyleSpec) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextEditorUi(
        initialText = initialText,
        initialStyle = initialStyle,
        onCommit = onCommit,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
