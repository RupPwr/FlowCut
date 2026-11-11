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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class OverlayTextColor(val name: String, val argb: Int)

val CURATED_OVERLAY_COLORS = listOf(
    OverlayTextColor("White", 0xFFFFFFFF.toInt()),
    OverlayTextColor("Black", 0xFF000000.toInt()),
    OverlayTextColor("Yellow", 0xFFFFEB3B.toInt()),
    OverlayTextColor("Cyan", 0xFF00E5FF.toInt()),
    OverlayTextColor("Red", 0xFFFF1744.toInt()),
    OverlayTextColor("Neon Green", 0xFF00E676.toInt()),
    OverlayTextColor("Pink", 0xFFFF4081.toInt()),
    OverlayTextColor("Orange", 0xFFFF9100.toInt()),
    OverlayTextColor("Royal Blue", 0xFF2979FF.toInt()),
    OverlayTextColor("Violet", 0xFF7C4DFF.toInt()),
    OverlayTextColor("Gold", 0xFFFFD700.toInt()),
    OverlayTextColor("Coral", 0xFFFF6E40.toInt()),
    OverlayTextColor("Lime", 0xFFAEEA00.toInt()),
    OverlayTextColor("Magenta", 0xFFE040FB.toInt())
)

val EXPRESSIVE_STICKERS = listOf(
    "🔥", "❤️", "😂", "😍", "✨", "💯", "👍", "🎉",
    "🚀", "⚡", "💥", "🎵", "⭐", "👑", "😎", "🙌",
    "💡", "🎯", "🏆", "💎", "💬", "❗", "❓", "➡️",
    "✅", "❌", "👀", "🎬", "📸", "🌟", "🌈", "🎁"
)

/**
 * Dedicated Text Editor BottomSheet with auto-focus, real-time live preview updates,
 * 3 background styles (Outline / Dark Pill / Solid Pill), and a palette of 14 curated colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorSheet(
    initialText: String = "",
    initialColorArgb: Int = 0xFFFFFFFF.toInt(),
    initialBgStyle: Int = 0,
    onLiveChange: (text: String, colorArgb: Int, bgStyle: Int) -> Unit = { _, _, _ -> },
    onDone: (text: String, colorArgb: Int, bgStyle: Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors

    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(initialText.length)
            )
        )
    }
    var selectedColorArgb by remember { mutableIntStateOf(initialColorArgb) }
    var selectedBgStyle by remember { mutableIntStateOf(initialBgStyle) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun notifyChange(newText: String, newColor: Int, newStyle: Int) {
        onLiveChange(newText, newColor, newStyle)
    }

    BackHandler {
        onCancel()
    }

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
                ) { onCancel() }
        )

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
                // 1. Header Row (Cancel ✕, Title, Done ✓)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Text(
                        text = "Edit Text",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(
                        onClick = {
                            val finalTxt = textFieldValue.text
                            onDone(finalTxt, selectedColorArgb, selectedBgStyle)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Done",
                            tint = colors.accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Text Input Field with autofocus
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        notifyChange(newValue.text, selectedColorArgb, selectedBgStyle)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text("Enter text overlay...", color = colors.textSecondary)
                    },
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 3,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surface1,
                        unfocusedContainerColor = colors.surface1,
                        focusedIndicatorColor = colors.accent,
                        unfocusedIndicatorColor = colors.border,
                        cursorColor = colors.accent
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Style Toggle Selector (Outline, Dark Pill, Solid Pill)
                Text(
                    text = "Style",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val styles = listOf(
                        0 to "Outline",
                        1 to "Dark Pill",
                        2 to "Solid Pill"
                    )
                    styles.forEach { (styleId, label) ->
                        val isSelected = selectedBgStyle == styleId
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clickable {
                                    selectedBgStyle = styleId
                                    notifyChange(textFieldValue.text, selectedColorArgb, styleId)
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) colors.accent.copy(alpha = 0.2f) else colors.surface2,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) colors.accent else colors.border
                            )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    color = if (isSelected) colors.accent else colors.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 4. Curated 14 Text Colors Palette
                Text(
                    text = "Text Color",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CURATED_OVERLAY_COLORS.forEach { colorItem ->
                        val isSelected = selectedColorArgb == colorItem.argb
                        val swatchColor = Color(colorItem.argb)
                        val isWhiteOrVeryLight = colorItem.argb == 0xFFFFFFFF.toInt()

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                                .border(
                                    BorderStroke(
                                        if (isSelected) 2.5.dp else 1.dp,
                                        if (isSelected) colors.accent else if (isWhiteOrVeryLight) colors.border else Color.Transparent
                                    ),
                                    CircleShape
                                )
                                .clickable {
                                    selectedColorArgb = colorItem.argb
                                    notifyChange(textFieldValue.text, colorItem.argb, selectedBgStyle)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = if (colorItem.argb == 0xFF000000.toInt()) Color.White else Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean Sticker Picker BottomSheet featuring a grid of 32 expressive high-res stickers
 * and an "Add Image / Logo" import button at the top.
 */
@Composable
fun StickerPickerSheet(
    onSelectSticker: (stickerGlyph: String) -> Unit,
    onAddImageClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors

    BackHandler {
        onDismiss()
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* Consume */ },
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
                // Header (Close ✕, Title)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Text(
                        text = "Stickers & Overlays",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.size(48.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // "Add Image / Logo" top import action
                OutlinedButton(
                    onClick = onAddImageClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.accent),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.accent.copy(alpha = 0.12f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Add Image",
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Add Image / Logo (PNG, JPG)",
                        color = colors.accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 32 Expressive Stickers Grid: 4 rows x 8 columns
                val rows = EXPRESSIVE_STICKERS.chunked(8)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rows.forEach { rowStickers ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            rowStickers.forEach { glyph ->
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surface1)
                                        .clickable { onSelectSticker(glyph) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = glyph,
                                        fontSize = 22.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

