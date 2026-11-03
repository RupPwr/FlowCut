package com.flowcut.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TransitionChips(
    onLockedChipClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors
    val chips = listOf(
        "Basic" to false,
        "Glitch" to true,
        "Shape" to true,
        "3D" to true,
        "Liquid" to true
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { (name, isLocked) ->
            val isActive = !isLocked
            val bgColor = if (isActive) colors.bgAccent else colors.surface0
            val textColor = if (isActive) colors.accent else colors.textMuted
            val borderColor = if (isActive) colors.accent else colors.borderStrong

            Box(
                modifier = Modifier
                    .height(36.dp) // 36 dp touch target
                    .clickable {
                        if (isLocked) {
                            onLockedChipClick()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(CircleShape)
                        .background(bgColor)
                        .border(0.5.dp, borderColor, CircleShape)
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isLocked) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = colors.textMuted,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Text(
                        text = name,
                        color = textColor,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

