package com.flowcut.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.model.Project
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

val PRESET_SPEEDS = listOf(0.2f, 0.5f, 1.0f, 1.5f, 2.0f, 4.0f, 8.0f, 10.0f)

/**
 * Dedicated Speed BottomSheet (0.2x to 10.0x, default 1.0x) for the selected clip.
 * Anchored strictly at the bottom of the screen with a dismissible scrim so the preview
 * remains fully unobstructed.
 *
 * Supports 8 Quick Preset buttons, smooth continuous slider with piecewise slow-mo precision
 * and magnetic snap at 1.0x, live duration readout, Reset to 1.0x, and Apply to All.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    clipIndex: Int,
    snapshotHolder: SnapshotHolder,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    getClipSpeed: (clipId: String) -> Float,
    getMaxSpeedForClip: (clipIndex: Int) -> Float,
    onSetLiveSpeed: (clipIndex: Int, speed: Float) -> Unit,
    onCommitSingleSpeed: (clipIndex: Int, speed: Float, initialProject: Project, initialTimeMaps: Map<String, TimeMap>) -> Unit,
    onCommitApplyAllSpeed: (speed: Float, initialProject: Project, initialTimeMaps: Map<String, TimeMap>) -> Unit,
    onRevertSpeed: (initialProject: Project, initialTimeMaps: Map<String, TimeMap>) -> Unit,
    initialProject: Project,
    initialTimeMaps: Map<String, TimeMap>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors
    val hapticFeedback = LocalHapticFeedback.current

    val currentSnapshot = snapshotHolder.current
    val clip = currentSnapshot.project.clips.getOrNull(clipIndex) ?: run {
        onDismiss()
        return
    }

    val originalSpeed = remember(clip.id) { getClipSpeed(clip.id) }
    val maxSpeed = remember(clip.id) { getMaxSpeedForClip(clipIndex) }

    var currentSpeed by remember(clip.id) { mutableFloatStateOf(originalSpeed) }
    var isApplyToAll by remember { mutableStateOf(false) }
    var isCommitted by remember { mutableStateOf(false) }

    fun speedToSlider(speed: Float): Float {
        val s = speed.coerceIn(0.2f, 10.0f)
        return if (s <= 1.0f) {
            // 0.2f..1.0f maps to 0.0f..0.35f
            ((s - 0.2f) / 0.8f) * 0.35f
        } else {
            // 1.0f..10.0f maps to 0.35f..1.0f
            0.35f + ((s - 1.0f) / 9.0f) * 0.65f
        }
    }

    fun sliderToSpeed(slider: Float): Float {
        val p = slider.coerceIn(0.0f, 1.0f)
        val rawSpeed = if (p <= 0.35f) {
            val t = p / 0.35f
            0.2f + t * 0.8f
        } else {
            val t = (p - 0.35f) / 0.65f
            1.0f + t * 9.0f
        }
        val snapped = if (abs(rawSpeed - 1.0f) <= 0.05f) {
            1.0f
        } else if (rawSpeed <= 1.0f) {
            ((rawSpeed * 20f).roundToInt() / 20f)
        } else {
            ((rawSpeed * 10f).roundToInt() / 10f)
        }
        return snapped.coerceIn(0.2f, maxSpeed)
    }

    fun formatSpeed(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}.0"
        } else {
            String.format(Locale.US, "%.2f", speed).trimEnd('0').let {
                if (it.endsWith(".")) "${it}0" else it
            }
        }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSec = durationMs / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        val tenths = (durationMs % 1000L) / 100L
        return String.format(Locale.US, "%d:%02d.%d", min, sec, tenths)
    }

    val liveClip = snapshotHolder.current.project.clips.getOrNull(clipIndex)
    val resultingDurationMs = liveClip?.durationMs ?: clip.durationMs

    val handleClose = {
        if (!isCommitted) {
            onRevertSpeed(initialProject, initialTimeMaps)
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        if (isApplyToAll) {
            onCommitApplyAllSpeed(currentSpeed, initialProject, initialTimeMaps)
        } else {
            onCommitSingleSpeed(clipIndex, currentSpeed, initialProject, initialTimeMaps)
        }
        previewRenderer.requestRender()
        onWakeFrameDriver()
        onDismiss()
    }

    BackHandler(enabled = true) {
        handleClose()
    }

    DisposableEffect(clip.id) {
        onDispose {
            if (!isCommitted) {
                onRevertSpeed(initialProject, initialTimeMaps)
                previewRenderer.requestRender()
                onWakeFrameDriver()
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Scrim above sheet: dismisses without modifying project on outside tap
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { handleClose() }
        )

        // Bottom Sheet Panel anchored at the bottom
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface1,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 12.dp)
            ) {
                // Header Row: Close ✕ | "Speed" + Badge + Duration | Reset | Apply to All | Done ✓
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = handleClose,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Center: Speed Title, Current Speed Badge, and Resulting Duration
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Speed",
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colors.accent.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "${formatSpeed(currentSpeed)}x",
                                color = colors.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "(${formatDuration(resultingDurationMs)})",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }

                    // Right Actions: Reset, Apply to All, Done
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Reset button
                        IconButton(
                            onClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                val resetSpeed = 1.0f.coerceAtMost(maxSpeed)
                                currentSpeed = resetSpeed
                                onSetLiveSpeed(clipIndex, resetSpeed)
                                previewRenderer.requestRender()
                                onWakeFrameDriver()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset to 1.0x",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Apply to All toggle pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isApplyToAll) colors.accent.copy(alpha = 0.2f)
                                    else colors.surface2
                                )
                                .clickable {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isApplyToAll = !isApplyToAll
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Apply to All",
                                    tint = if (isApplyToAll) colors.accent else colors.textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "All",
                                    color = if (isApplyToAll) colors.accent else colors.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Done checkmark
                        IconButton(
                            onClick = handleDone,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Quick Preset Pills (0.2x, 0.5x, 1.0x, 1.5x, 2.0x, 4.0x, 8.0x, 10.0x)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PRESET_SPEEDS.forEach { preset ->
                        val isSelected = abs(currentSpeed - preset) < 0.03f
                        val isEnabled = preset <= maxSpeed + 0.05f
                        val label = if (preset == preset.toInt().toFloat()) "${preset.toInt()}x" else "${preset}x"

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isSelected -> colors.accent
                                        isEnabled -> colors.surface2
                                        else -> colors.surface2.copy(alpha = 0.4f)
                                    }
                                )
                                .clickable(enabled = isEnabled) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val targetSpeed = preset.coerceAtMost(maxSpeed)
                                    currentSpeed = targetSpeed
                                    onSetLiveSpeed(clipIndex, targetSpeed)
                                    previewRenderer.requestRender()
                                    onWakeFrameDriver()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = when {
                                    isSelected -> Color.Black
                                    isEnabled -> colors.textPrimary
                                    else -> colors.textSecondary.copy(alpha = 0.5f)
                                },
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Continuous Smooth Speed Slider (0.2x to 10.0x)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Slider(
                        value = speedToSlider(currentSpeed),
                        onValueChange = { sliderPos ->
                            val newSpeed = sliderToSpeed(sliderPos)
                            if (abs(newSpeed - currentSpeed) >= 0.03f) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentSpeed = newSpeed
                                onSetLiveSpeed(clipIndex, newSpeed)
                                previewRenderer.requestRender()
                                onWakeFrameDriver()
                            }
                        },
                        valueRange = 0.0f..1.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent,
                            inactiveTrackColor = colors.surface2
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Tick labels below slider: 0.2x, 1x, 2x, 5x, 10x
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("0.2x", "1x", "2x", "5x", "10x").forEach { label ->
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                color = colors.textSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
