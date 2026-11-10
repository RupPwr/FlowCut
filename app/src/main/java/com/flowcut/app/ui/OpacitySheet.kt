package com.flowcut.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.core.timeline.SnapshotHolder
import kotlin.math.roundToInt

/**
 * Dedicated Opacity BottomSheet (0% to 100%) for the selected clip.
 * Anchored at Alignment.BottomCenter, matching VolumeSheet / BackgroundSheet.
 *
 * Provides real-time 60fps opacity adjustments, reset to 100%, and Apply-to-All toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpacitySheet(
    clipIndex: Int,
    snapshotHolder: SnapshotHolder,
    getClipOpacity: (clipId: String) -> Float,
    onSetLiveOpacity: (clipId: String, opacity: Float) -> Unit,
    onCommitSingleOpacity: (clipIndex: Int, opacity: Float) -> Unit,
    onCommitApplyAllOpacity: (opacity: Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors

    val currentSnapshot = snapshotHolder.current
    val clip = currentSnapshot.project.clips.getOrNull(clipIndex) ?: run {
        onDismiss()
        return
    }

    // Capture initial opacities for clean cancellation rollback
    val originalClipOpacity = remember(clip.id) { getClipOpacity(clip.id) }
    val originalAllOpacities = remember {
        currentSnapshot.project.clips.associate { it.id to getClipOpacity(it.id) }
    }

    var sliderOpacity by remember(clip.id) { mutableFloatStateOf(originalClipOpacity) }
    var isApplyToAll by remember { mutableStateOf(false) }
    var isCommitted by remember { mutableStateOf(false) }

    fun applyLive(opacity: Float, toAll: Boolean) {
        if (toAll) {
            for (c in snapshotHolder.current.project.clips) {
                onSetLiveOpacity(c.id, opacity)
            }
        } else {
            for ((id, origOp) in originalAllOpacities) {
                if (id == clip.id) {
                    onSetLiveOpacity(id, opacity)
                } else {
                    onSetLiveOpacity(id, origOp)
                }
            }
        }
    }

    val handleClose = {
        if (!isCommitted) {
            for ((id, origOp) in originalAllOpacities) {
                onSetLiveOpacity(id, origOp)
            }
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        if (isApplyToAll) {
            onCommitApplyAllOpacity(sliderOpacity)
        } else {
            onCommitSingleOpacity(clipIndex, sliderOpacity)
        }
        onDismiss()
    }

    BackHandler {
        handleClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            if (!isCommitted) {
                for ((id, origOp) in originalAllOpacities) {
                    onSetLiveOpacity(id, origOp)
                }
            }
        }
    }

    val percentageInt = (sliderOpacity * 100f).roundToInt().coerceIn(0, 100)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Scrim above sheet
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { handleClose() }
        )

        // Bottom Sheet Surface container
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* Consume clicks inside sheet */ },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 20.dp, start = 16.dp, end = 16.dp)
            ) {
                // 1. Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left Close (✕) button
                    IconButton(onClick = handleClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Center Title + live percentage
                    Text(
                        text = "Opacity $percentageInt%",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Right Actions (Reset, Apply to All, Done ✓)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Reset to 100%
                        TextButton(
                            onClick = {
                                sliderOpacity = 1.0f
                                applyLive(1.0f, isApplyToAll)
                            }
                        ) {
                            Text(
                                text = "Reset",
                                color = if (sliderOpacity < 0.999f) colors.accent else colors.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Apply to All toggle pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isApplyToAll) colors.accent.copy(alpha = 0.2f) else colors.surface2,
                            border = BorderStroke(1.dp, if (isApplyToAll) colors.accent else Color.Transparent),
                            modifier = Modifier
                                .clickable {
                                    isApplyToAll = !isApplyToAll
                                    applyLive(sliderOpacity, isApplyToAll)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Apply to All",
                                    tint = if (isApplyToAll) colors.accent else colors.textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "All",
                                    fontSize = 11.sp,
                                    color = if (isApplyToAll) colors.accent else colors.textSecondary,
                                    fontWeight = if (isApplyToAll) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        // Done (✓) button
                        IconButton(onClick = handleDone) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = colors.accent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Continuous Slider (0% to 100%)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "0%",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Slider(
                        value = sliderOpacity,
                        onValueChange = { newVal ->
                            sliderOpacity = newVal
                            applyLive(newVal, isApplyToAll)
                        },
                        valueRange = 0.0f..1.0f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent,
                            inactiveTrackColor = colors.surface2
                        )
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = "100%",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

