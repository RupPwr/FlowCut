/**
 * Feature:     filter
 * File role:   logic
 * Responsible: Pure calculation of color adjustment parameters, ranges, and normalization
 * Depends on:  engine/ClipColorAdjust, shared
 * Used by:     FilterCommands, FilterUi
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.filter

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.PanoramaFishEye
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.ui.graphics.vector.ImageVector
import com.flowcut.app.engine.ClipColorAdjust

enum class AdjustTool(
    val title: String,
    val icon: ImageVector,
    val isBipolar: Boolean = true
) {
    BRIGHTNESS("Brightness", Icons.Default.BrightnessMedium),
    CONTRAST("Contrast", Icons.Default.Contrast),
    SATURATION("Saturation", Icons.Default.ColorLens),
    EXPOSURE("Exposure", Icons.Default.Exposure),
    TEMP("Temp", Icons.Default.Thermostat),
    TINT("Tint", Icons.Default.InvertColors),
    HIGHLIGHTS("Highlights", Icons.Default.WbTwilight),
    SHADOWS("Shadows", Icons.Default.NightlightRound),
    SHARPNESS("Sharpness", Icons.Default.Details, isBipolar = false),
    VIGNETTE("Vignette", Icons.Default.PanoramaFishEye)
}

object FilterLogic {

    fun getToolValue(adjust: ClipColorAdjust, tool: AdjustTool): Float = when (tool) {
        AdjustTool.BRIGHTNESS -> adjust.brightness
        AdjustTool.CONTRAST -> adjust.contrast
        AdjustTool.SATURATION -> adjust.saturation
        AdjustTool.EXPOSURE -> adjust.exposure
        AdjustTool.TEMP -> adjust.warmth
        AdjustTool.TINT -> adjust.tint
        AdjustTool.HIGHLIGHTS -> adjust.highlights
        AdjustTool.SHADOWS -> adjust.shadows
        AdjustTool.SHARPNESS -> adjust.sharpness
        AdjustTool.VIGNETTE -> adjust.vignette
    }

    fun withToolValue(adjust: ClipColorAdjust, tool: AdjustTool, normalized: Float): ClipColorAdjust = when (tool) {
        AdjustTool.BRIGHTNESS -> adjust.copy(brightness = normalized)
        AdjustTool.CONTRAST -> adjust.copy(contrast = normalized)
        AdjustTool.SATURATION -> adjust.copy(saturation = normalized)
        AdjustTool.EXPOSURE -> adjust.copy(exposure = normalized)
        AdjustTool.TEMP -> adjust.copy(warmth = normalized)
        AdjustTool.TINT -> adjust.copy(tint = normalized)
        AdjustTool.HIGHLIGHTS -> adjust.copy(highlights = normalized)
        AdjustTool.SHADOWS -> adjust.copy(shadows = normalized)
        AdjustTool.SHARPNESS -> adjust.copy(sharpness = normalized)
        AdjustTool.VIGNETTE -> adjust.copy(vignette = normalized)
    }
}
