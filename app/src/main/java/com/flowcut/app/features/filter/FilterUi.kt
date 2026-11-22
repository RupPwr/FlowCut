/**
 * Feature:     filter
 * File role:   ui
 * Responsible: Bottom sheet UI for video color grading, sliders, parameter strip, and live preview
 * Depends on:  core/timeline, engine/ClipColorAdjust, engine/PreviewRenderer, shared, features/filter/FilterLogic
 * Used by:     Index, ui
 * Never touches: Player directly, other features
 */
package com.flowcut.app.features.filter

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
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
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.ui.FlowCutTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustSheetUi(
    clipIndex: Int,
    snapshotHolder: SnapshotHolder,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    getClipColorAdjust: (clipId: String) -> ClipColorAdjust,
    onCommitSingleAdjust: (clipIndex: Int, adjust: ClipColorAdjust) -> Unit,
    onCommitApplyAllAdjust: (adjust: ClipColorAdjust) -> Unit,
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

    val originalClipAdjust = remember(clip.id) { getClipColorAdjust(clip.id) }
    val originalAllAdjusts = remember {
        currentSnapshot.project.clips.associate { it.id to getClipColorAdjust(it.id) }
    }

    var currentAdjust by remember(clip.id) { mutableStateOf(originalClipAdjust) }
    var selectedTool by remember { mutableStateOf(AdjustTool.BRIGHTNESS) }
    var isApplyToAll by remember { mutableStateOf(false) }
    var isCommitted by remember { mutableStateOf(false) }

    fun applyLive(adjust: ClipColorAdjust, toAll: Boolean) {
        if (toAll) {
            for (c in snapshotHolder.current.project.clips) {
                PreviewRenderer.setClipColorAdjust(c.id, adjust)
            }
        } else {
            for ((id, origAdj) in originalAllAdjusts) {
                if (id == clip.id) PreviewRenderer.setClipColorAdjust(id, adjust) else PreviewRenderer.setClipColorAdjust(id, origAdj)
            }
        }
        previewRenderer.requestRender()
        onWakeFrameDriver()
    }

    val handleClose = {
        if (!isCommitted) {
            for ((id, origAdj) in originalAllAdjusts) {
                PreviewRenderer.setClipColorAdjust(id, origAdj)
            }
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        if (isApplyToAll) onCommitApplyAllAdjust(currentAdjust) else onCommitSingleAdjust(clipIndex, currentAdjust)
        onDismiss()
    }

    BackHandler { handleClose() }

    DisposableEffect(Unit) {
        onDispose {
            if (!isCommitted) {
                for ((id, origAdj) in originalAllAdjusts) {
                    PreviewRenderer.setClipColorAdjust(id, origAdj)
                }
                previewRenderer.requestRender()
                onWakeFrameDriver()
            }
        }
    }

    val currentNormalized = FilterLogic.getToolValue(currentAdjust, selectedTool)
    val currentUiValue = if (selectedTool.isBipolar) {
        (currentNormalized * 50f).roundToInt().coerceIn(-50, 50)
    } else {
        (currentNormalized * 100f).roundToInt().coerceIn(0, 100)
    }

    val headerTitle = if (selectedTool.isBipolar) {
        if (currentUiValue > 0) "${selectedTool.title} +$currentUiValue" else "${selectedTool.title} $currentUiValue"
    } else {
        "${selectedTool.title} $currentUiValue"
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { handleClose() })

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
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", tint = colors.textPrimary, modifier = Modifier.size(22.dp))
                    }

                    Text(text = headerTitle, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = {
                                val currentVal = FilterLogic.getToolValue(currentAdjust, selectedTool)
                                val newAdjust = if (kotlin.math.abs(currentVal) > 0.001f) {
                                    FilterLogic.withToolValue(currentAdjust, selectedTool, 0f)
                                } else ClipColorAdjust.DEFAULT
                                currentAdjust = newAdjust
                                applyLive(newAdjust, isApplyToAll)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surface2,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                Text(text = "Reset", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Surface(
                            onClick = {
                                isApplyToAll = !isApplyToAll
                                applyLive(currentAdjust, isApplyToAll)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isApplyToAll) colors.accent.copy(alpha = 0.2f) else colors.surface2,
                            border = BorderStroke(1.dp, if (isApplyToAll) colors.accent else Color.Transparent),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(imageVector = Icons.Default.DoneAll, contentDescription = "Apply to All", tint = if (isApplyToAll) colors.accent else colors.textSecondary, modifier = Modifier.size(14.dp))
                                Text(text = "All", color = if (isApplyToAll) colors.accent else colors.textSecondary, fontSize = 11.sp, fontWeight = if (isApplyToAll) FontWeight.Bold else FontWeight.Normal)
                            }
                        }

                        IconButton(onClick = handleDone, modifier = Modifier.size(36.dp)) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "Done", tint = colors.accent, modifier = Modifier.size(24.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    if (selectedTool.isBipolar) {
                        Box(modifier = Modifier.width(2.dp).height(14.dp).background(colors.border, RoundedCornerShape(1.dp)))
                    }

                    var lastSnappedZero by remember(selectedTool) { mutableStateOf(false) }

                    Slider(
                        value = currentUiValue.toFloat(),
                        onValueChange = { newVal ->
                            val snappedVal = if (selectedTool.isBipolar) {
                                if (kotlin.math.abs(newVal) < 2.0f) 0f else newVal
                            } else {
                                if (newVal < 2.0f) 0f else newVal
                            }

                            if (snappedVal == 0f && !lastSnappedZero) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastSnappedZero = true
                            } else if (snappedVal != 0f) {
                                lastSnappedZero = false
                            }

                            val newNormalized = if (selectedTool.isBipolar) {
                                (snappedVal / 50f).coerceIn(-1.0f, 1.0f)
                            } else {
                                (snappedVal / 100f).coerceIn(0.0f, 1.0f)
                            }

                            val newAdjust = FilterLogic.withToolValue(currentAdjust, selectedTool, newNormalized)
                            currentAdjust = newAdjust
                            applyLive(newAdjust, isApplyToAll)
                        },
                        valueRange = if (selectedTool.isBipolar) -50f..50f else 0f..100f,
                        colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.borderStrong),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (tool in AdjustTool.values()) {
                        val isSelected = tool == selectedTool
                        val toolVal = FilterLogic.getToolValue(currentAdjust, tool)
                        val isActive = kotlin.math.abs(toolVal) > 0.01f

                        Column(
                            modifier = Modifier
                                .width(62.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedTool = tool }
                                .background(if (isSelected) colors.surface2 else Color.Transparent)
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(imageVector = tool.icon, contentDescription = tool.title, tint = if (isSelected) colors.accent else if (isActive) colors.textPrimary else colors.textSecondary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = tool.title, color = if (isSelected) colors.accent else if (isActive) colors.textPrimary else colors.textSecondary, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(modifier = Modifier.size(4.dp).background(if (isActive) Color(0xFF00E5FF) else Color.Transparent, shape = CircleShape))
                        }
                    }
                }
            }
        }
    }
}
