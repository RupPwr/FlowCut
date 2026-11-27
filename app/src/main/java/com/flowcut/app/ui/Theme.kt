package com.flowcut.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class FlowCutColors(
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val border: Color,
    val borderStrong: Color,
    val accent: Color,
    val onAccent: Color,
    val bgAccent: Color,
    val danger: Color
)

@Immutable
data class ClipColorScheme(
    val bg: Color,
    val text: Color
)

val LightColors = FlowCutColors(
    surface0 = Color(0xFFFFFFFF),
    surface1 = Color(0xFFF5F4F7),
    surface2 = Color(0xFFEBEAEF),
    textPrimary = Color(0xFF141416),
    textSecondary = Color(0xFF5F5E66),
    textMuted = Color(0xFF8C8B94),
    border = Color(0x26141416),
    borderStrong = Color(0x40141416),
    accent = Color(0xFFFF2D55),
    onAccent = Color(0xFFFFFFFF),
    bgAccent = Color(0xFFFFE5EB),
    danger = Color(0xFFDC2626)
)

val DarkColors = FlowCutColors(
    surface0 = Color(0xFF0E0E10),
    surface1 = Color(0xFF18181C),
    surface2 = Color(0xFF24242A),
    textPrimary = Color(0xFFF7F7FA),
    textSecondary = Color(0xFFA1A1AA),
    textMuted = Color(0xFF71717A),
    border = Color(0x24FFFFFF),
    borderStrong = Color(0x3DFFFFFF),
    accent = Color(0xFFFF2D55),
    onAccent = Color(0xFFFFFFFF),
    bgAccent = Color(0x2EFF2D55),
    danger = Color(0xFFEF4444)
)

fun getClipColors(colorIndex: Int, isDark: Boolean): ClipColorScheme {
    return when (colorIndex % 3) {
        0 -> if (isDark) {
            ClipColorScheme(bg = Color(0xFF3C3489), text = Color(0xFFCECBF6))
        } else {
            ClipColorScheme(bg = Color(0xFFCECBF6), text = Color(0xFF3C3489))
        }
        1 -> if (isDark) {
            ClipColorScheme(bg = Color(0xFF085041), text = Color(0xFF9FE1CB))
        } else {
            ClipColorScheme(bg = Color(0xFF9FE1CB), text = Color(0xFF085041))
        }
        else -> if (isDark) {
            ClipColorScheme(bg = Color(0xFF712B13), text = Color(0xFFF5C4B3))
        } else {
            ClipColorScheme(bg = Color(0xFFF5C4B3), text = Color(0xFF712B13))
        }
    }
}

val LocalFlowCutColors = staticCompositionLocalOf { DarkColors }

object FlowCutTheme {
    val colors: FlowCutColors
        @Composable
        get() = LocalFlowCutColors.current
}

@Composable
fun FlowCutTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val flowCutColors = if (darkTheme) DarkColors else LightColors
    val materialColorScheme = if (darkTheme) {
        darkColorScheme(
            background = flowCutColors.surface0,
            surface = flowCutColors.surface1,
            primary = flowCutColors.accent,
            onPrimary = flowCutColors.onAccent
        )
    } else {
        lightColorScheme(
            background = flowCutColors.surface0,
            surface = flowCutColors.surface1,
            primary = flowCutColors.accent,
            onPrimary = flowCutColors.onAccent
        )
    }

    CompositionLocalProvider(LocalFlowCutColors provides flowCutColors) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            content = content
        )
    }
}

