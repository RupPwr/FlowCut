package com.flowcut.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

@Composable
fun PlaybackRow(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    currentMs: Long,
    totalMs: Long,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onSeekScrub: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1. Undo icon (24 dp icon, 48 dp touch)
        IconButton(
            onClick = { if (canUndo) onUndoClick() },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                tint = if (canUndo) colors.textPrimary else colors.textPrimary.copy(alpha = 0.38f),
                modifier = Modifier.size(24.dp)
            )
        }

        // 2. Redo icon
        IconButton(
            onClick = { if (canRedo) onRedoClick() },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                tint = if (canRedo) colors.textPrimary else colors.textPrimary.copy(alpha = 0.38f),
                modifier = Modifier.size(24.dp)
            )
        }

        // 3. Seekbar (weight 1f)
        val progress = if (totalMs > 0) (currentMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .pointerInput(totalMs) {
                    detectTapGestures { offset ->
                        if (totalMs > 0 && size.width > 0) {
                            val targetProgress = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeekScrub((targetProgress * totalMs).toLong())
                        }
                    }
                }
                .pointerInput(totalMs) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        if (totalMs > 0 && size.width > 0) {
                            val targetProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                            onSeekScrub((targetProgress * totalMs).toLong())
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            ) {
                val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                // Track background
                drawRoundRect(
                    color = colors.borderStrong,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = corner
                )
                // Filled part
                if (progress > 0f) {
                    drawRoundRect(
                        color = colors.accent,
                        topLeft = Offset.Zero,
                        size = Size(size.width * progress, size.height),
                        cornerRadius = corner
                    )
                }
            }
        }

        // 4. Time text "mm:ss / mm:ss" (only recomposes when whole second changes)
        val timeString = remember(currentMs / 1000L, totalMs / 1000L) {
            "${formatTimeMs(currentMs)} / ${formatTimeMs(totalMs)}"
        }
        Text(
            text = timeString,
            color = colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        // 5. Play / Pause icon
        IconButton(
            onClick = onPlayPauseClick,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = colors.textPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
