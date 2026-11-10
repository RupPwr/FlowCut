package com.flowcut.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.NotInterested
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
import com.flowcut.app.engine.BackgroundType
import com.flowcut.app.engine.ClipBackground
import com.flowcut.app.engine.PreviewRenderer
import kotlin.math.roundToInt

data class CuratedColor(val name: String, val argb: Int)

val CURATED_COLORS = listOf(
    CuratedColor("Black", 0xFF000000.toInt()),
    CuratedColor("White", 0xFFFFFFFF.toInt()),
    CuratedColor("Dark Gray", 0xFF262626.toInt()),
    CuratedColor("Warm Cream", 0xFFF5EBE0.toInt()),
    CuratedColor("Crimson", 0xFFD90429.toInt()),
    CuratedColor("Coral", 0xFFFF6B6B.toInt()),
    CuratedColor("Amber", 0xFFFFAA00.toInt()),
    CuratedColor("Emerald", 0xFF10B981.toInt()),
    CuratedColor("Teal", 0xFF14B8A6.toInt()),
    CuratedColor("Cyan", 0xFF06B6D4.toInt()),
    CuratedColor("Royal Blue", 0xFF2563EB.toInt()),
    CuratedColor("Indigo", 0xFF6366F1.toInt()),
    CuratedColor("Violet", 0xFF8B5CF6.toInt()),
    CuratedColor("Magenta", 0xFFD946EF.toInt()),
    CuratedColor("Rose", 0xFFF43F5E.toInt()),
    CuratedColor("Espresso", 0xFF3E2723.toInt()),
    CuratedColor("Olive", 0xFF556B2F.toInt()),
    CuratedColor("Slate", 0xFF475569.toInt())
)

enum class BackgroundTab(val title: String) {
    BLUR("Blur"),
    COLOR("Color"),
    NONE("None")
}

/**
 * Dedicated Canvas Background BottomSheet (CapCut/VN style) for the selected clip.
 * Anchored strictly at the bottom of the screen with a dismissible scrim so the preview
 * remains fully unobstructed.
 *
 * Supports smooth Blur Slider (0% to 100%), 18 Curated Solid Colors, or None (pure black).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSheet(
    clipIndex: Int,
    snapshotHolder: SnapshotHolder,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    getClipBackground: (clipId: String) -> ClipBackground,
    onCommitSingleBackground: (clipIndex: Int, background: ClipBackground) -> Unit,
    onCommitApplyAllBackground: (background: ClipBackground) -> Unit,
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

    val originalClipBackground = remember(clip.id) { getClipBackground(clip.id) }
    val originalAllBackgrounds = remember {
        currentSnapshot.project.clips.associate { it.id to getClipBackground(it.id) }
    }

    var currentBackground by remember(clip.id) { mutableStateOf(originalClipBackground) }
    var selectedTab by remember(clip.id) {
        mutableStateOf(
            when (originalClipBackground.type) {
                BackgroundType.BLUR -> BackgroundTab.BLUR
                BackgroundType.COLOR -> BackgroundTab.COLOR
                BackgroundType.NONE -> BackgroundTab.BLUR
            }
        )
    }
    var isApplyToAll by remember { mutableStateOf(false) }
    var isCommitted by remember { mutableStateOf(false) }

    fun applyLive(background: ClipBackground, toAll: Boolean) {
        if (toAll) {
            for (c in snapshotHolder.current.project.clips) {
                PreviewRenderer.setClipBackground(c.id, background)
            }
        } else {
            for ((id, origBg) in originalAllBackgrounds) {
                if (id == clip.id) {
                    PreviewRenderer.setClipBackground(id, background)
                } else {
                    PreviewRenderer.setClipBackground(id, origBg)
                }
            }
        }
        previewRenderer.requestRender()
        onWakeFrameDriver()
    }

    val handleClose = {
        if (!isCommitted) {
            for ((id, origBg) in originalAllBackgrounds) {
                PreviewRenderer.setClipBackground(id, origBg)
            }
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        if (isApplyToAll) {
            onCommitApplyAllBackground(currentBackground)
        } else {
            onCommitSingleBackground(clipIndex, currentBackground)
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
                for ((id, origBg) in originalAllBackgrounds) {
                    PreviewRenderer.setClipBackground(id, origBg)
                }
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
                // Header Row: Close ✕ | "Background" | Apply to All | Done ✓
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

                    Text(
                        text = "Background",
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Apply to All pill toggle
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isApplyToAll) colors.accent else colors.surface2)
                                .clickable {
                                    isApplyToAll = !isApplyToAll
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    applyLive(currentBackground, isApplyToAll)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Apply to All",
                                tint = if (isApplyToAll) Color.Black else colors.textSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "All",
                                color = if (isApplyToAll) Color.Black else colors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        IconButton(
                            onClick = handleDone,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = colors.accent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Mode Tabs: Blur | Color | None
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BackgroundTab.values().forEach { tab ->
                        val isSelected = selectedTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) colors.surface2 else Color.Transparent)
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (isSelected) colors.accent else colors.border
                                    ),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    selectedTab = tab
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    when (tab) {
                                        BackgroundTab.BLUR -> {
                                            val level = if (currentBackground.blurLevel > 0.05f) {
                                                currentBackground.blurLevel
                                            } else {
                                                0.5f
                                            }
                                            val newBg = currentBackground.copy(
                                                type = BackgroundType.BLUR,
                                                blurLevel = level
                                            )
                                            currentBackground = newBg
                                            applyLive(newBg, isApplyToAll)
                                        }
                                        BackgroundTab.COLOR -> {
                                            val newBg = currentBackground.copy(type = BackgroundType.COLOR)
                                            currentBackground = newBg
                                            applyLive(newBg, isApplyToAll)
                                        }
                                        BackgroundTab.NONE -> {
                                            val newBg = currentBackground.copy(type = BackgroundType.NONE, blurLevel = 0.0f)
                                            currentBackground = newBg
                                            applyLive(newBg, isApplyToAll)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.title,
                                color = if (isSelected) colors.accent else colors.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tab Content
                when (selectedTab) {
                    BackgroundTab.BLUR -> {
                        // Smooth Blur Slider (0% to 100%) with live percentage readout
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            val percentInt = if (currentBackground.type == BackgroundType.NONE) {
                                0
                            } else {
                                (currentBackground.blurLevel * 100f).roundToInt().coerceIn(0, 100)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Blur",
                                    color = colors.textSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "$percentInt%",
                                    color = colors.accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            val sliderVal = if (currentBackground.type == BackgroundType.NONE) {
                                0f
                            } else {
                                currentBackground.blurLevel.coerceIn(0f, 1f)
                            }

                            Slider(
                                value = sliderVal,
                                onValueChange = { newVal ->
                                    val newBg = if (newVal <= 0.005f) {
                                        currentBackground.copy(type = BackgroundType.NONE, blurLevel = 0f)
                                    } else {
                                        currentBackground.copy(
                                            type = BackgroundType.BLUR,
                                            blurLevel = newVal.coerceIn(0.01f, 1.0f)
                                        )
                                    }
                                    currentBackground = newBg
                                    applyLive(newBg, isApplyToAll)
                                },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = colors.accent,
                                    activeTrackColor = colors.accent,
                                    inactiveTrackColor = colors.borderStrong
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    BackgroundTab.COLOR -> {
                        // Color Palette Header
                        val selectedColorName = CURATED_COLORS.firstOrNull { it.argb == currentBackground.colorArgb }?.name ?: "Custom"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Solid Colors",
                                    color = colors.textSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = selectedColorName,
                                    color = colors.accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 18 Curated Colors Palette Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CURATED_COLORS.forEach { curated ->
                                    val isSelected = currentBackground.type == BackgroundType.COLOR &&
                                        currentBackground.colorArgb == curated.argb
                                    val composeColor = Color(curated.argb)

                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(composeColor)
                                            .border(
                                                BorderStroke(
                                                    if (isSelected) 3.dp else 1.dp,
                                                    if (isSelected) colors.accent else if (curated.name == "Black") colors.borderStrong else Color.Transparent
                                                ),
                                                CircleShape
                                            )
                                            .clickable {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val newBg = currentBackground.copy(
                                                    type = BackgroundType.COLOR,
                                                    colorArgb = curated.argb
                                                )
                                                currentBackground = newBg
                                                applyLive(newBg, isApplyToAll)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = if (curated.name == "White" || curated.name == "Warm Cream") Color.Black else Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    BackgroundTab.NONE -> {
                        // Informational None view
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotInterested,
                                contentDescription = "None",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Canvas padding remains pure black.",
                                color = colors.textSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
