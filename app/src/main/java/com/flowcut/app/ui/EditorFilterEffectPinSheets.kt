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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.editor.EditorViewModel
import com.flowcut.app.engine.PreviewRenderer
import kotlin.math.roundToInt

@Composable
internal fun FilterPinLayerSheet(
    targetOverlayId: String?,
    initialPresetId: Int,
    initialIntensity: Float,
    isNewItem: Boolean,
    playheadUs: Long,
    resolvedViewModel: EditorViewModel?,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeId by remember { mutableStateOf(targetOverlayId) }
    var selectedPresetId by remember { mutableIntStateOf(if (initialPresetId in 0..11) initialPresetId else 1) }
    var currentIntensity by remember {
        mutableFloatStateOf(if (initialIntensity in 0.01f..1.0f) initialIntensity else 0.80f)
    }
    var isCommitted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isNewItem && activeId == null && resolvedViewModel != null) {
            val created = resolvedViewModel.addFilterLayer(
                presetId = selectedPresetId,
                intensity = currentIntensity,
                startUs = playheadUs,
                durationUs = 3_000_000L
            )
            activeId = created?.id
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
    }

    fun applyLive(presetId: Int, intensity: Float) {
        resolvedViewModel?.updateSelectedFilterLayer(presetId, intensity)
        previewRenderer.requestRender()
        onWakeFrameDriver()
    }

    val handleClose = {
        if (!isCommitted) {
            if (isNewItem && activeId != null) {
                resolvedViewModel?.removeLayerItem(activeId!!)
            } else if (activeId != null) {
                resolvedViewModel?.updateSelectedFilterLayer(initialPresetId, initialIntensity)
            }
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        resolvedViewModel?.commitSelectedFilterLayer(selectedPresetId, currentIntensity)
        previewRenderer.requestRender()
        onWakeFrameDriver()
        onDismiss()
    }

    BackHandler { handleClose() }

    DisposableEffect(Unit) {
        onDispose {
            if (!isCommitted) {
                if (isNewItem && activeId != null) {
                    resolvedViewModel?.removeLayerItem(activeId!!)
                } else if (activeId != null) {
                    resolvedViewModel?.updateSelectedFilterLayer(initialPresetId, initialIntensity)
                }
                previewRenderer.requestRender()
                onWakeFrameDriver()
            }
        }
    }

    Dialog(
        onDismissRequest = handleClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { handleClose() }
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {},
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = DARK_SHEET_BG,
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(0.5.dp, DARK_SURFACE_BORDER)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 16.dp)
                ) {
                    // Top Actions Row
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
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                onClick = {
                                    val totalMs = resolvedViewModel?.getSnapshot()?.project?.clips?.sumOf { it.durationMs } ?: 0L
                                    val totalUs = msToUs(totalMs)
                                    val curId = activeId ?: resolvedViewModel?.uiState?.value?.selectedOverlayId
                                    if (curId != null && totalUs > 0L) {
                                        val item = resolvedViewModel?.layerItems?.firstOrNull { it.id == curId }
                                        if (item != null) {
                                            resolvedViewModel.updateLayerItem(item.copy(startUs = 0L, endUs = totalUs))
                                            previewRenderer.requestRender()
                                            onWakeFrameDriver()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = DARK_CARD_BG,
                                border = BorderStroke(0.5.dp, DARK_SURFACE_BORDER),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Apply to All",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            IconButton(
                                onClick = handleDone,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Done",
                                    tint = PINK_RED_ACCENT,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    // Intensity Slider Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Intensity",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = currentIntensity,
                            onValueChange = { newIntensity ->
                                currentIntensity = newIntensity
                                applyLive(selectedPresetId, newIntensity)
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = PINK_RED_ACCENT,
                                activeTrackColor = PINK_RED_ACCENT,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(currentIntensity * 100).roundToInt()}%",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Scrollable row of 12 Filter Cards (68.dp x 84.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (preset in FILTER_PRESETS) {
                            val isSelected = preset.id == selectedPresetId
                            Column(
                                modifier = Modifier
                                    .size(width = 68.dp, height = 84.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        BorderStroke(
                                            if (isSelected) 2.dp else 0.5.dp,
                                            if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER
                                        ),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .background(DARK_CARD_BG)
                                    .clickable {
                                        selectedPresetId = preset.id
                                        applyLive(preset.id, currentIntensity)
                                    }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(preset.gradient),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (preset.id == 0) {
                                        Icon(
                                            imageVector = Icons.Default.Block,
                                            contentDescription = "None",
                                            tint = Color(0xFF8E8E93),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = preset.name,
                                    color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EffectPinLayerSheet(
    targetOverlayId: String?,
    initialPresetId: Int,
    initialIntensity: Float,
    isNewItem: Boolean,
    playheadUs: Long,
    resolvedViewModel: EditorViewModel?,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeId by remember { mutableStateOf(targetOverlayId) }
    var selectedPresetId by remember { mutableIntStateOf(if (initialPresetId in 0..8) initialPresetId else 1) }
    var currentIntensity by remember {
        mutableFloatStateOf(if (initialIntensity in 0.01f..1.0f) initialIntensity else 0.70f)
    }
    var isCommitted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isNewItem && activeId == null && resolvedViewModel != null) {
            val created = resolvedViewModel.addEffectLayer(
                presetId = selectedPresetId,
                intensity = currentIntensity,
                startUs = playheadUs,
                durationUs = 3_000_000L
            )
            activeId = created?.id
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
    }

    fun applyLive(presetId: Int, intensity: Float) {
        resolvedViewModel?.updateSelectedEffectLayer(presetId, intensity)
        previewRenderer.requestRender()
        onWakeFrameDriver()
    }

    val handleClose = {
        if (!isCommitted) {
            if (isNewItem && activeId != null) {
                resolvedViewModel?.removeLayerItem(activeId!!)
            } else if (activeId != null) {
                resolvedViewModel?.updateSelectedEffectLayer(initialPresetId, initialIntensity)
            }
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        resolvedViewModel?.commitSelectedEffectLayer(selectedPresetId, currentIntensity)
        previewRenderer.requestRender()
        onWakeFrameDriver()
        onDismiss()
    }

    BackHandler { handleClose() }

    DisposableEffect(Unit) {
        onDispose {
            if (!isCommitted) {
                if (isNewItem && activeId != null) {
                    resolvedViewModel?.removeLayerItem(activeId!!)
                } else if (activeId != null) {
                    resolvedViewModel?.updateSelectedEffectLayer(initialPresetId, initialIntensity)
                }
                previewRenderer.requestRender()
                onWakeFrameDriver()
            }
        }
    }

    Dialog(
        onDismissRequest = handleClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { handleClose() }
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {},
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = DARK_SHEET_BG,
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(0.5.dp, DARK_SURFACE_BORDER)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 16.dp)
                ) {
                    // Top Actions Row
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
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(
                            onClick = handleDone,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = PINK_RED_ACCENT,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Intensity Slider Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Intensity",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = currentIntensity,
                            onValueChange = { newIntensity ->
                                currentIntensity = newIntensity
                                applyLive(selectedPresetId, newIntensity)
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = PINK_RED_ACCENT,
                                activeTrackColor = PINK_RED_ACCENT,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(currentIntensity * 100).roundToInt()}%",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Scrollable row of 8 Visual Effect Cards (None + 8 presets = 9 cards, 68.dp x 84.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (preset in EFFECT_PRESETS) {
                            val isSelected = preset.id == selectedPresetId
                            Column(
                                modifier = Modifier
                                    .size(width = 68.dp, height = 84.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        BorderStroke(
                                            if (isSelected) 2.dp else 0.5.dp,
                                            if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER
                                        ),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .background(DARK_CARD_BG)
                                    .clickable {
                                        selectedPresetId = preset.id
                                        applyLive(preset.id, currentIntensity)
                                    }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(preset.gradient),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = preset.icon,
                                        contentDescription = preset.name,
                                        tint = preset.iconTint,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Text(
                                    text = preset.name,
                                    color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
