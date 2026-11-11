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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BottomToolbar(
    isClipSelected: Boolean = false,
    isOverlaySelected: Boolean = false,
    isPipOverlaySelected: Boolean = false,
    isPipActive: Boolean = false,
    isPipItemSelected: Boolean = false,
    canSplitPip: Boolean = true,
    isAudioActive: Boolean = false,
    isAudioItemSelected: Boolean = false,
    canSplitAudio: Boolean = true,
    isTextOverlaySelected: Boolean = false,
    isTextToolbarActive: Boolean = false,
    onAddOverlayClick: () -> Unit = {},
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
    onAudioClick: () -> Unit = {},
    onBackToAudioGroup: () -> Unit = {},
    onAudioSoundsClick: () -> Unit = {},
    onAudioLocalClick: () -> Unit = {},
    onAudioEffectsClick: () -> Unit = {},
    onAudioExtractClick: () -> Unit = {},
    onAudioRecordClick: () -> Unit = {},
    onAudioFadeClick: () -> Unit = {},
    onDeleteAudioClick: () -> Unit = {},
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
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Split Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onSplitClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Trim Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onTrimClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Volume Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onVolumeClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Speed Action (0.2x to 10.0x)
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onSpeedClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Adjust Action (Color Adjust: Brightness, Contrast, Saturation, etc.)
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onAdjustClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Background Action (Blur / Solid Color Canvas Background)
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onBackgroundClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Rotate Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onRotateClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Flip Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onFlipClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Opacity Action (0% to 100%)
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onOpacityClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Copy (Duplicate) Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onCopyClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Replace Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onReplaceClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Freeze Action (3.0s Still Frame)
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onFreezeClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Delete Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onDeleteClipClick?.invoke() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
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
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onAddTextClick() }
                            .padding(horizontal = 2.dp),
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
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 2. Edit
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onEditTextOverlayClick() }
                            .padding(horizontal = 2.dp),
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
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 3. Font
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onFontClick() }
                            .padding(horizontal = 2.dp),
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
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 4. Color
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onColorClick() }
                            .padding(horizontal = 2.dp),
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
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 5. Animation
                    Column(
                        modifier = Modifier
                            .widthIn(min = 56.dp)
                            .fillMaxHeight()
                            .clickable { onAnimationClick() }
                            .padding(horizontal = 2.dp),
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
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 6. Split
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onSplitClick() }
                            .padding(horizontal = 2.dp),
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
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 7. Delete
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onDeleteClipClick?.invoke() }
                            .padding(horizontal = 2.dp),
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
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 8. Adjust
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onOpacityClick() }
                            .padding(horizontal = 2.dp),
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
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 9. Copy
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onCopyClick() }
                            .padding(horizontal = 2.dp),
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
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else if (isPipActive) {
            // Dedicated PIP Toolbar matching image_21c07a.jpg
            val itemToolsEnabled = isPipItemSelected
            val splitEnabled = isPipItemSelected && canSplitPip

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Left ⌄ collapse chevron (returns to S0_NONE)
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .fillMaxHeight()
                        .clickable { onClearSelection() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse PIP",
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

                // Actions: ADD, ANIMATION, OPACITY, SPLIT, DELETE, COPY, ROTATE, FLIP
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. ADD (ALWAYS enabled in both S1 and S2)
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onAddOverlayClick() }
                            .padding(horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "ADD",
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ADD",
                            color = colors.accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 2. ANIMATION
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .alpha(if (itemToolsEnabled) 1.0f else 0.35f)
                            .then(if (itemToolsEnabled) Modifier.clickable { onAnimationClick() } else Modifier)
                            .padding(horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Animation,
                            contentDescription = "ANIMATION",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ANIMATION",
                            color = colors.textPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 3. OPACITY
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .alpha(if (itemToolsEnabled) 1.0f else 0.35f)
                            .then(if (itemToolsEnabled) Modifier.clickable { onOpacityClick() } else Modifier)
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 4. SPLIT
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .alpha(if (splitEnabled) 1.0f else 0.35f)
                            .then(if (splitEnabled) Modifier.clickable { onSplitClick() } else Modifier)
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 5. DELETE
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .alpha(if (itemToolsEnabled) 1.0f else 0.35f)
                            .then(if (itemToolsEnabled) Modifier.clickable { onDeleteClipClick?.invoke() } else Modifier)
                            .padding(horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "DELETE",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "DELETE",
                            color = colors.textPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 6. COPY
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .alpha(if (itemToolsEnabled) 1.0f else 0.35f)
                            .then(if (itemToolsEnabled) Modifier.clickable { onCopyClick() } else Modifier)
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 7. ROTATE
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .alpha(if (itemToolsEnabled) 1.0f else 0.35f)
                            .then(if (itemToolsEnabled) Modifier.clickable { onRotateClick() } else Modifier)
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 8. FLIP
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .alpha(if (itemToolsEnabled) 1.0f else 0.35f)
                            .then(if (itemToolsEnabled) Modifier.clickable { onFlipClick() } else Modifier)
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else if (isAudioActive) {
            if (isAudioItemSelected) {
                // S6 Selected Audio Item Toolbar: VOLUME, FADE, SPEED, SPLIT, COPY, DELETE
                val splitEnabled = canSplitAudio
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left ⌄ chevron (steps back to S5_AUDIO_GROUP)
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onBackToAudioGroup() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Back to Audio",
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

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 0. ADD (switches to S5_AUDIO_GROUP)
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onBackToAudioGroup() }
                                .padding(horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "ADD",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "ADD",
                                color = colors.textPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 1. VOLUME
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onVolumeClick() }
                                .padding(horizontal = 2.dp),
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
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 2. FADE
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onAudioFadeClick() }
                                .padding(horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinearScale,
                                contentDescription = "FADE",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "FADE",
                                color = colors.textPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 3. SPEED
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onSpeedClick() }
                                .padding(horizontal = 2.dp),
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
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 4. SPLIT
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .alpha(if (splitEnabled) 1.0f else 0.35f)
                                .then(if (splitEnabled) Modifier.clickable { onSplitClick() } else Modifier)
                                .padding(horizontal = 2.dp),
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
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 5. COPY
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onCopyClick() }
                                .padding(horizontal = 2.dp),
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
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 6. DELETE
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onDeleteAudioClick() }
                                .padding(horizontal = 2.dp),
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
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // S5 Audio Group Toolbar: SOUNDS, LOCAL, EFFECTS, EXTRACT, RECORD
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left ⌄ dismiss chevron (returns to S0_NONE)
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .clickable { onClearSelection() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Collapse Audio",
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

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. SOUNDS
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onAudioSoundsClick() }
                                .padding(horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LibraryMusic,
                                contentDescription = "SOUNDS",
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "SOUNDS",
                                color = colors.accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 2. LOCAL
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onAudioLocalClick() }
                                .padding(horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "LOCAL",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "LOCAL",
                                color = colors.textPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 3. EFFECTS
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onAudioEffectsClick() }
                                .padding(horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "EFFECTS",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "EFFECTS",
                                color = colors.textPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 4. EXTRACT
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onAudioExtractClick() }
                                .padding(horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VideoFile,
                                contentDescription = "EXTRACT",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "EXTRACT",
                                color = colors.textPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 5. RECORD
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onAudioRecordClick() }
                                .padding(horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "RECORD",
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "RECORD",
                                color = colors.accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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

                // Actions: ADD (for PIP), EDIT (for text), SPLIT, COPY, OPACITY, ROTATE, FLIP, DELETE
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Add Action (for PIP overlays)
                    if (isPipOverlaySelected || !isTextOverlaySelected) {
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onAddOverlayClick() }
                                .padding(horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "ADD",
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "ADD",
                                color = colors.accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Edit Action (when Text overlay is selected)
                    if (isTextOverlaySelected) {
                        Column(
                            modifier = Modifier
                                .widthIn(min = 52.dp)
                                .fillMaxHeight()
                                .clickable { onEditTextOverlayClick() }
                                .padding(horizontal = 2.dp),
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
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Split Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onSplitClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Copy Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onCopyClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Opacity Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onOpacityClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Rotate Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onRotateClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Flip Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onFlipClick() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Delete Action
                    Column(
                        modifier = Modifier
                            .widthIn(min = 52.dp)
                            .fillMaxHeight()
                            .clickable { onDeleteClipClick?.invoke() }
                            .padding(horizontal = 2.dp),
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
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
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Trim (Active: selects clip under playhead and opens TrimSheet)
                Column(
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .clickable { onTrimClick() }
                        .padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCut,
                        contentDescription = "Trim",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Trim",
                        color = colors.textPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 2. Text (Active: opens TextEditorSheet)
                Column(
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .clickable { onTextClick() }
                        .padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.TextFields,
                        contentDescription = "Text",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Text",
                        color = colors.textPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 3. Sticker (Active: opens StickerPickerSheet)
                Column(
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .clickable { onStickerClick() }
                        .padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEmotions,
                        contentDescription = "Sticker",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Sticker",
                        color = colors.textPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 4. Overlay (Active: launches multi-select media picker up to 6 items)
                Column(
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .clickable { onOverlayClick() }
                        .padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Overlay",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Overlay",
                        color = colors.textPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 5. Transit. (Active)
                Column(
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Transit.",
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Transit.",
                        color = colors.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 6. Audio
                Column(
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .clickable { onAudioClick() }
                        .padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Audio",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Audio",
                        color = colors.textPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 7. AI (Locked)
                Column(
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .clickable { onLockedItemClick() }
                        .padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "AI",
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
