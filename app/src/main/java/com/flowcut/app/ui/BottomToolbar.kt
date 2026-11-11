package com.flowcut.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BottomToolbar(
    isClipSelected: Boolean = false,
    isOverlaySelected: Boolean = false,
    isTextOverlaySelected: Boolean = false,
    isTextToolbarActive: Boolean = false,
    onSplitClick: () -> Unit = {},
    onTrimClick: () -> Unit = {},
    onVolumeClick: () -> Unit = {},
    onSpeedClick: () -> Unit = {},
    onAdjustClick: () -> Unit = {},
    onBackgroundClick: () -> Unit = {},
    onRotateClick: () -> Unit = {},
    onFlipClick: () -> Unit = {},
    onOpacityClick: () -> Unit = {},
    onCopyClick: () -> Unit = {},
    onReplaceClick: () -> Unit = {},
    onFreezeClick: () -> Unit = {},
    onDeleteClipClick: (() -> Unit)? = null,
    onClearSelection: () -> Unit = {},
    onLockedItemClick: () -> Unit = {},
    onTextClick: () -> Unit = {},
    onStickerClick: () -> Unit = {},
    onImageClick: () -> Unit = {},
    onOverlayClick: () -> Unit = onImageClick,
    onEditTextOverlayClick: () -> Unit = {},
    onAddTextClick: () -> Unit = onTextClick,
    onFontClick: () -> Unit = {},
    onColorClick: () -> Unit = {},
    onAnimationClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Top 0.5 dp divider
        HorizontalDivider(
            thickness = 0.5.dp,
            color = colors.border
        )

        if (isClipSelected) {
            // Contextual Selected Clip Toolbar (CapCut / VN style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Back / Dismiss button on the far left
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .fillMaxHeight()
                        .clickable { onClearSelection() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Dismiss",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Vertical separator
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(colors.border)
                )

                // Actions: SPLIT, TRIM, VOLUME, ROTATE, FLIP, and DELETE
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Split Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onSplitClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = "SPLIT",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SPLIT",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Trim Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onTrimClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCut,
                            contentDescription = "TRIM",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "TRIM",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Volume Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onVolumeClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "VOLUME",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "VOLUME",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Speed Action (0.2x to 10.0x)
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onSpeedClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "SPEED",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SPEED",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Adjust Action (Color Adjust: Brightness, Contrast, Saturation, etc.)
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onAdjustClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "ADJUST",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ADJUST",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Background Action (Blur / Solid Color Canvas Background)
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onBackgroundClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wallpaper,
                            contentDescription = "BG",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "BG",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Rotate Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onRotateClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.RotateRight,
                            contentDescription = "ROTATE",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ROTATE",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Flip Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onFlipClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Flip,
                            contentDescription = "FLIP",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "FLIP",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Opacity Action (0% to 100%)
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onOpacityClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Opacity,
                            contentDescription = "OPACITY",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "OPACITY",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Copy (Duplicate) Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onCopyClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "COPY",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "COPY",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Replace Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onReplaceClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "REPLACE",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "REPLACE",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Freeze Action (3.0s Still Frame)
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onFreezeClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AcUnit,
                            contentDescription = "FREEZE",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "FREEZE",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Delete Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onDeleteClipClick?.invoke() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "DELETE",
                            tint = colors.danger,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "DELETE",
                            color = colors.danger,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else if (isTextToolbarActive || (isOverlaySelected && isTextOverlaySelected)) {
            // Dedicated Text Toolbar (9 options: Add text, Edit, Font, Color, Animation, Split, Delete, Adjust, Copy)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Back / Dismiss button on the far left
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .fillMaxHeight()
                        .clickable { onClearSelection() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Dismiss",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Vertical separator
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(colors.border)
                )

                // 9 Exact Text Actions
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Add text
                    Column(
                        modifier = Modifier
                            .width(52.dp)
                            .fillMaxHeight()
                            .clickable { onAddTextClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add text",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Add text",
                            color = colors.textPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 2. Edit
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onEditTextOverlayClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Edit",
                            color = colors.accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 3. Font
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onFontClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.TextFields,
                            contentDescription = "Font",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Font",
                            color = colors.textPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 4. Color
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onColorClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Color",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Color",
                            color = colors.textPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 5. Animation
                    Column(
                        modifier = Modifier
                            .width(56.dp)
                            .fillMaxHeight()
                            .clickable { onAnimationClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Animation,
                            contentDescription = "Animation",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Animation",
                            color = colors.textPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 6. Split
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onSplitClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = "Split",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Split",
                            color = colors.textPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 7. Delete
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onDeleteClipClick?.invoke() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = colors.danger,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Delete",
                            color = colors.danger,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 8. Adjust
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onOpacityClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Adjust",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Adjust",
                            color = colors.textPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 9. Copy
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onCopyClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Copy",
                            color = colors.textPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else if (isOverlaySelected) {
            // Contextual Selected Overlay Toolbar (SPLIT, COPY, OPACITY, ROTATE, FLIP, DELETE)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Back / Dismiss button on the far left
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .fillMaxHeight()
                        .clickable { onClearSelection() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Dismiss",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Vertical separator
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(colors.border)
                )

                // Actions: EDIT (for text), SPLIT, COPY, OPACITY, ROTATE, FLIP, DELETE
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Edit Action (when Text overlay is selected)
                    if (isTextOverlaySelected) {
                        Column(
                            modifier = Modifier
                                .width(48.dp)
                                .fillMaxHeight()
                                .clickable { onEditTextOverlayClick() },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "EDIT",
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "EDIT",
                                color = colors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Split Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onSplitClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = "SPLIT",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SPLIT",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Copy Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onCopyClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "COPY",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "COPY",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Opacity Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onOpacityClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Opacity,
                            contentDescription = "OPACITY",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "OPACITY",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Rotate Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onRotateClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.RotateRight,
                            contentDescription = "ROTATE",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ROTATE",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Flip Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onFlipClick() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Flip,
                            contentDescription = "FLIP",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "FLIP",
                            color = colors.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Delete Action
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onDeleteClipClick?.invoke() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "DELETE",
                            tint = colors.danger,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "DELETE",
                            color = colors.danger,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            // Main Bottom Toolbar (Normal state when NO clip or overlay is selected)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Trim (Active: selects clip under playhead and opens TrimSheet)
                Column(
                    modifier = Modifier
                        .width(48.dp)
                        .clickable { onTrimClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCut,
                        contentDescription = "Trim",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Trim",
                        color = colors.textPrimary,
                        fontSize = 11.sp
                    )
                }

                // 2. Text (Active: opens TextEditorSheet)
                Column(
                    modifier = Modifier
                        .width(48.dp)
                        .clickable { onTextClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.TextFields,
                        contentDescription = "Text",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Text",
                        color = colors.textPrimary,
                        fontSize = 11.sp
                    )
                }

                // 3. Sticker (Active: opens StickerPickerSheet)
                Column(
                    modifier = Modifier
                        .width(48.dp)
                        .clickable { onStickerClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEmotions,
                        contentDescription = "Sticker",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Sticker",
                        color = colors.textPrimary,
                        fontSize = 11.sp
                    )
                }

                // 4. Overlay (Active: launches multi-select media picker up to 6 items)
                Column(
                    modifier = Modifier
                        .width(48.dp)
                        .clickable { onOverlayClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Overlay",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Overlay",
                        color = colors.textPrimary,
                        fontSize = 11.sp
                    )
                }

                // 5. Transit. (Active)
                Column(
                    modifier = Modifier.width(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Transit.",
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Transit.",
                        color = colors.accent,
                        fontSize = 11.sp
                    )
                }

                // 6. Audio (Locked)
                Column(
                    modifier = Modifier
                        .width(48.dp)
                        .clickable { onLockedItemClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Audio",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Audio",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }

                // 7. AI (Locked)
                Column(
                    modifier = Modifier
                        .width(48.dp)
                        .clickable { onLockedItemClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "AI",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
