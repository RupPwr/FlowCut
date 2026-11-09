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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
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
    onSplitClick: () -> Unit = {},
    onTrimClick: () -> Unit = {},
    onVolumeClick: () -> Unit = {},
    onRotateClick: () -> Unit = {},
    onFlipClick: () -> Unit = {},
    onDeleteClipClick: (() -> Unit)? = null,
    onClearSelection: () -> Unit = {},
    onLockedItemClick: () -> Unit = {},
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
            // Main Bottom Toolbar (Normal state when NO clip is selected)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
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

                // 2. Transit. (Active)
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

                // 3. Audio (Locked)
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

                // 4. Text (Locked)
                Column(
                    modifier = Modifier
                        .width(48.dp)
                        .clickable { onLockedItemClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.TextFields,
                        contentDescription = "Text",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Text",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }

                // 5. AI (Locked)
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
