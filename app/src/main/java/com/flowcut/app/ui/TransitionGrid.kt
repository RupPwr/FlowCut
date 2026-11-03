package com.flowcut.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.model.TransitionType

data class TransitionGridItem(
    val type: TransitionType,
    val icon: ImageVector
)

@Composable
fun TransitionGrid(
    activeTransitionType: TransitionType?,
    onTransitionClick: (TransitionType) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors
    val items = listOf(
        TransitionGridItem(TransitionType.SLIDE, Icons.AutoMirrored.Filled.ArrowForward),
        TransitionGridItem(TransitionType.CIRCLE, Icons.Default.RadioButtonUnchecked),
        TransitionGridItem(TransitionType.ZOOM, Icons.Default.ZoomIn),
        TransitionGridItem(TransitionType.FADE, Icons.Default.BlurOn)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { item ->
            val isActive = activeTransitionType == item.type
            val borderModifier = if (isActive) {
                Modifier.border(1.5.dp, colors.accent, RoundedCornerShape(8.dp))
            } else {
                Modifier.border(0.5.dp, colors.border, RoundedCornerShape(8.dp))
            }
            val bg = if (isActive) colors.bgAccent else colors.surface0
            val contentColor = if (isActive) colors.accent else colors.textSecondary

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .then(borderModifier)
                    .clickable { onTransitionClick(item.type) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.type.displayName,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = item.type.displayName,
                    color = contentColor,
                    fontSize = 11.sp
                )
            }
        }
    }
}
