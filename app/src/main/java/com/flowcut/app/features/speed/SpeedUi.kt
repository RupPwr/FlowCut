/**
 * Feature:     speed
 * File role:   ui
 * Responsible: Bottom sheet UI for speed adjustments, presets, and continuous slider
 * Depends on:  core/timeline, core/model, engine/preview, shared, features/speed/SpeedLogic
 * Used by:     Index, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.speed

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
import com.flowcut.app.shared.TimeFormatters
import com.flowcut.app.ui.FlowCutTheme
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheetUi(
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

    BackHandler(enabled = true) { handleClose() }

    DisposableEffect(clip.id) {
        onDispose {
            if (!isCommitted) {
                onRevertSpeed(initialProject, initialTimeMaps)
                previewRenderer.requestRender()
                onWakeFrameDriver()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { handleClose() }
        )

        Surface(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface1,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = handleClose, modifier = Modifier.size(36.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "Speed", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Surface(shape = RoundedCornerShape(6.dp), color = colors.accent.copy(alpha = 0.15f)) {
                            Text(
                                text = "${TimeFormatters.formatSpeed(currentSpeed)}x",
                                color = colors.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "(${TimeFormatters.formatDurationMs(resultingDurationMs)})",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            Icon(imageVector = Icons.Default.RestartAlt, contentDescription = "Reset to 1.0x", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isApplyToAll) colors.accent.copy(alpha = 0.2f) else colors.surface2)
                                .clickable {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isApplyToAll = !isApplyToAll
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(imageVector = Icons.Default.DoneAll, contentDescription = "Apply to All", tint = if (isApplyToAll) colors.accent else colors.textSecondary, modifier = Modifier.size(14.dp))
                                Text(text = "All", color = if (isApplyToAll) colors.accent else colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        IconButton(onClick = handleDone, modifier = Modifier.size(36.dp)) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "Done", tint = colors.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpeedLogic.PRESET_SPEEDS.forEach { preset ->
                        val isSelected = abs(currentSpeed - preset) < 0.03f
                        val isEnabled = preset <= maxSpeed + 0.05f
                        val label = if (preset == preset.toInt().toFloat()) "${preset.toInt()}x" else "${preset}x"

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(when {
                                    isSelected -> colors.accent
                                    isEnabled -> colors.surface2
                                    else -> colors.surface2.copy(alpha = 0.4f)
                                })
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

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Slider(
                        value = SpeedLogic.speedToSlider(currentSpeed),
                        onValueChange = { sliderPos ->
                            val newSpeed = SpeedLogic.sliderToSpeed(sliderPos, maxSpeed)
                            if (abs(newSpeed - currentSpeed) >= 0.03f) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentSpeed = newSpeed
                                onSetLiveSpeed(clipIndex, newSpeed)
                                previewRenderer.requestRender()
                                onWakeFrameDriver()
                            }
                        },
                        valueRange = 0.0f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.surface2),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("0.2x", "1x", "2x", "3x", "4x").forEach { label ->
                            Text(text = label, fontSize = 10.sp, color = colors.textSecondary, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
