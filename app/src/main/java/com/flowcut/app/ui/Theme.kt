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
    surface1 = Color(0xFFF5F4EF),
    surface2 = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF141413),
    textSecondary = Color(0xFF5F5E5A),
    textMuted = Color(0xFF888780),
    border = Color(0x26141413),
    borderStrong = Color(0x40141413),
    accent = Color(0xFF185FA5),
    onAccent = Color(0xFFFFFFFF),
    bgAccent = Color(0xFFE6F1FB),
    danger = Color(0xFFA32D2D)
)

val DarkColors = FlowCutColors(
    surface0 = Color(0xFF141413),
    surface1 = Color(0xFF262624),
    surface2 = Color(0xFF30302E),
    textPrimary = Color(0xFFF5F4EF),
    textSecondary = Color(0xFFB4B2A9),
    textMuted = Color(0xFF888780),
    border = Color(0x26FFFFFF),
    borderStrong = Color(0x40FFFFFF),
    accent = Color(0xFF85B7EB),
    onAccent = Color(0xFF042C53),
    bgAccent = Color(0xFF0C447C),
    danger = Color(0xFFF09595)
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

val LocalFlowCutColors = staticCompositionLocalOf { LightColors }

object FlowCutTheme {
    val colors: FlowCutColors
        @Composable
        get() = LocalFlowCutColors.current
}

@Composable
fun FlowCutTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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

