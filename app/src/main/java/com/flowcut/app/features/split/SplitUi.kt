/**
 * Feature:     split
 * File role:   ui
 * Responsible: Split action button and interaction state
 * Depends on:  core/playback, features/split/SplitLogic
 * Used by:     Index, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.split

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Reusable Split icon button for timeline / edit toolbars.
 */
@Composable
fun SplitButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(44.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CallSplit,
            contentDescription = "Split clip at playhead",
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
    }
}
