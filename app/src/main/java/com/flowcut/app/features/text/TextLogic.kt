/**
 * Feature:     text
 * File role:   logic
 * Responsible: Pure calculation of text layout bounds, font styling, and text overlay properties
 * Depends on:  shared
 * Used by:     TextCommands, TextUi
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

data class TextStyleSpec(
    val color: Color = Color.White,
    val fontSizeSp: Float = 24f,
    val fontWeight: FontWeight = FontWeight.Normal,
    val isItalic: Boolean = false,
    val fontName: String = "Default",
    val customFontPath: String? = null
)

object TextLogic {

    val PRESET_COLORS = listOf(
        Color.White,
        Color.Black,
        Color(0xFFFFD54F), // Amber
        Color(0xFF00E5FF), // Cyan
        Color(0xFFFF4081), // Pink
        Color(0xFF69F0AE), // Light Green
        Color(0xFFE040FB), // Purple
        Color(0xFFFF5252)  // Red
    )

    /**
     * Estimates texture aspect ratio from text line counts and maximum line length.
     */
    fun calculateTextAspect(text: String): Float {
        val lines = text.split("\n")
        val maxLen = lines.maxOfOrNull { it.length } ?: 6
        val lineCount = lines.size.coerceAtLeast(1)
        return (maxLen.toFloat() * 0.45f / lineCount.toFloat()).coerceIn(0.8f, 4.0f)
    }
}
