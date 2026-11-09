package com.flowcut.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.core.timeline.SnapshotHolder
import kotlin.math.roundToInt

/**
 * Dedicated Volume BottomSheet (0% to 200%) for the selected clip.
 *
 * Provides real-time volume adjustment (0% to 200%), single-clip Mute/Unmute toggle,
 * and Apply-to-All clips toggle with full Undo/Redo commit support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeSheet(
    clipIndex: Int,
    snapshotHolder: SnapshotHolder,
    getClipVolume: (clipId: String) -> Float,
    getPreMuteVolume: (clipId: String) -> Float = { 1.0f },
    onSetLiveVolume: (clipId: String, volume: Float) -> Unit,
    onCommitSingleVolume: (clipIndex: Int, volume: Float) -> Unit,
    onCommitApplyAllVolume: (volume: Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors

    val currentSnapshot = snapshotHolder.current
    val clip = currentSnapshot.project.clips.getOrNull(clipIndex) ?: run {
        onDismiss()
        return
    }

    // Capture original volume settings at moment of sheet open
    val originalClipVolume = remember(clip.id) { getClipVolume(clip.id) }
    val originalAllVolumes = remember {
        currentSnapshot.project.clips.associate { it.id to getClipVolume(it.id) }
    }
    val initialPreMuteVolume = remember(clip.id) {
        if (originalClipVolume > 0.0001f) originalClipVolume else getPreMuteVolume(clip.id).takeIf { it > 0.0001f } ?: 1.0f
    }

    var sliderVolume by remember(clip.id) { mutableFloatStateOf(originalClipVolume) }
    var lastNonZeroVolume by remember(clip.id) { mutableFloatStateOf(initialPreMuteVolume) }
    var isApplyToAll by remember { mutableStateOf(false) }
    var isCommitted by remember { mutableStateOf(false) }

    // Helper to push live volume changes to audio playback immediately
    fun applyLive(vol: Float, toAll: Boolean) {
        if (toAll) {
            for (c in snapshotHolder.current.project.clips) {
                onSetLiveVolume(c.id, vol)
            }
            com.flowcut.app.audio.ClipAudioContributor.isMainTrackMuted = (vol <= 0.0001f)
        } else {
            // Apply current clip volume, keep other clips at original volume
            for ((id, origVol) in originalAllVolumes) {
                if (id == clip.id) {
                    onSetLiveVolume(id, vol)
                } else {
                    onSetLiveVolume(id, origVol)
                }
            }
            val anyUnmuted = vol > 0.0001f || originalAllVolumes.any { it.key != clip.id && it.value > 0.0001f }
            com.flowcut.app.audio.ClipAudioContributor.isMainTrackMuted = !anyUnmuted
        }
    }

    val handleClose = {
        if (!isCommitted) {
            // Discard temporary volume changes, restore all original volumes in audio contributor
            for ((id, origVol) in originalAllVolumes) {
                onSetLiveVolume(id, origVol)
            }
            val origAllMuted = originalAllVolumes.isNotEmpty() && originalAllVolumes.values.all { it <= 0.0001f }
            com.flowcut.app.audio.ClipAudioContributor.isMainTrackMuted = origAllMuted
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        if (isApplyToAll) {
            onCommitApplyAllVolume(sliderVolume)
        } else {
            onCommitSingleVolume(clipIndex, sliderVolume)
        }
        onDismiss()
    }

    // Handle system back gesture
    BackHandler {
        handleClose()
    }

    // Revert on disposal if unmounted unexpectedly without commit
    DisposableEffect(Unit) {
        onDispose {
            if (!isCommitted) {
                for ((id, origVol) in originalAllVolumes) {
                    onSetLiveVolume(id, origVol)
                }
                val origAllMuted = originalAllVolumes.isNotEmpty() && originalAllVolumes.values.all { it <= 0.0001f }
                com.flowcut.app.audio.ClipAudioContributor.isMainTrackMuted = origAllMuted
            }
        }
    }

    val percentageInt = (sliderVolume * 100f).roundToInt()
    val isClipMuted = sliderVolume <= 0.0001f

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrim above sheet: dismisses without modifying project on outside tap
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
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* Consume clicks inside sheet */ },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = colors.surface0,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
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
                            contentDescription = "Discard volume and close",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Center Title + live percentage readout
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Volume",
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$percentageInt%",
                            color = colors.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Right Done (✓) button
                    IconButton(onClick = handleDone) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Commit volume",
                            tint = colors.accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Volume Slider (0% to 200%, default 100%)
                Slider(
                    value = sliderVolume,
                    onValueChange = { newVal ->
                        sliderVolume = newVal
                        if (newVal > 0.0001f) {
                            lastNonZeroVolume = newVal
                        }
                        applyLive(newVal, isApplyToAll)
                    },
                    valueRange = 0f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.borderStrong
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Slider range labels (0%, 100%, 200%)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "0%",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "100%",
                        color = if (percentageInt == 100) colors.accent else colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (percentageInt == 100) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = "200%",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 3. Action Buttons Row (Mute / Unmute & Apply to All)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Mute / Unmute Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isClipMuted) colors.surface2 else colors.surface1)
                            .border(
                                width = 1.dp,
                                color = if (isClipMuted) colors.danger else colors.border,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                if (isClipMuted) {
                                    val restored = if (lastNonZeroVolume > 0.0001f) lastNonZeroVolume else 1.0f
                                    sliderVolume = restored
                                    applyLive(restored, isApplyToAll)
                                } else {
                                    lastNonZeroVolume = sliderVolume
                                    sliderVolume = 0f
                                    applyLive(0f, isApplyToAll)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isClipMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isClipMuted) "Unmute" else "Mute",
                                tint = if (isClipMuted) colors.danger else colors.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isClipMuted) "Unmute" else "Mute",
                                color = if (isClipMuted) colors.danger else colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Apply to All Button
                    val applyAllActive = isApplyToAll
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (applyAllActive) colors.accent.copy(alpha = 0.15f) else colors.surface1)
                            .border(
                                width = 1.dp,
                                color = if (applyAllActive) colors.accent else colors.border,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                isApplyToAll = !isApplyToAll
                                applyLive(sliderVolume, isApplyToAll)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Apply to All",
                                tint = if (applyAllActive) colors.accent else colors.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Apply to All",
                                color = if (applyAllActive) colors.accent else colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (applyAllActive) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
