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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import com.flowcut.app.editor.EditorUiState
import com.flowcut.app.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import com.flowcut.app.engine.ClipColorAdjust
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PanoramaFishEye
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.style.TextOverflow
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.engine.AspectRatioMode
import com.flowcut.app.engine.BackgroundType
import com.flowcut.app.engine.ClipBackground
import com.flowcut.app.engine.ClipFilterState
import com.flowcut.app.engine.PreviewRenderer
import kotlin.math.roundToInt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowcut.app.engine.export.Aspect
import com.flowcut.app.engine.export.BitrateTable
import com.flowcut.app.engine.export.ExportBenchmark
import com.flowcut.app.engine.export.ExportCapabilities
import com.flowcut.app.engine.export.ExportCoordinator
import com.flowcut.app.engine.export.ExportEstimator
import com.flowcut.app.engine.export.OutCodec
import com.flowcut.app.engine.export.OutputSpec
import com.flowcut.app.engine.export.Quality
import com.flowcut.app.engine.perf.SessionGuard
import java.io.File
import java.util.Locale

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

    val startText = if (initialText == "Your Text" || initialText.isBlank()) "" else initialText
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = startText,
                selection = TextRange(startText.length)
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

    Dialog(
        onDismissRequest = onCancel,
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
                        Text("Your Text", color = colors.textSecondary)
                    },
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surface1,
                        unfocusedContainerColor = colors.surface1,
                        focusedIndicatorColor = colors.accent,
                        unfocusedIndicatorColor = colors.border,
                        cursorColor = colors.accent
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
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
    isReplaceMode: Boolean = false,
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
                        text = if (isReplaceMode) "Replace Sticker" else "Stickers & Overlays",
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
                        text = if (isReplaceMode) "Replace with Image / Logo (PNG, JPG)" else "Add Image / Logo (PNG, JPG)",
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

// ---------------------------------------------------------------------------
// Design Constants for Text Tool Suite Sheets (Matches CapCut / Reference UI)
// ---------------------------------------------------------------------------
val PINK_RED_ACCENT = Color(0xFFFF2D55)
val DARK_SHEET_BG = Color(0xFF1C1C1E)
val DARK_CARD_BG = Color(0xFF2B2B2E)
val DARK_SURFACE_BORDER = Color(0xFF38383A)

val TEXT_COLOR_PALETTE = listOf(
    0xFFFFFFFF.toInt(), // White
    0xFF000000.toInt(), // Black
    0xFF8E8E93.toInt(), // Gray
    0xFFD1D1D6.toInt(), // Light Gray
    0xFFFFEB3B.toInt(), // Yellow
    0xFFFFC107.toInt(), // Amber
    0xFFFF9800.toInt(), // Orange
    0xFFFF5722.toInt(), // Coral
    0xFFFF1744.toInt(), // Red
    0xFFFF4081.toInt(), // Pink
    0xFF9C27B0.toInt(), // Purple
    0xFF673AB7.toInt(), // Deep Purple
    0xFF2196F3.toInt(), // Blue
    0xFF00E5FF.toInt(), // Cyan
    0xFF009688.toInt(), // Teal
    0xFF4CAF50.toInt()  // Green
)

val RICH_CUSTOM_PALETTE_40 = listOf(
    // Row 1: Grayscale & Neutrals
    0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt(), 0xFFBDBDBD.toInt(), 0xFF9E9E9E.toInt(), 
    0xFF757575.toInt(), 0xFF616161.toInt(), 0xFF424242.toInt(), 0xFF000000.toInt(),
    // Row 2: Warm Reds & Oranges
    0xFFFF1744.toInt(), 0xFFFF5252.toInt(), 0xFFFF6E40.toInt(), 0xFFFF9100.toInt(), 
    0xFFFFAB00.toInt(), 0xFFFFD700.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFF176.toInt(),
    // Row 3: Limes, Greens & Teals
    0xFFAEEA00.toInt(), 0xFF76FF03.toInt(), 0xFF00E676.toInt(), 0xFF1DE9B6.toInt(), 
    0xFF00BFA5.toInt(), 0xFF009688.toInt(), 0xFF4CAF50.toInt(), 0xFF2E7D32.toInt(),
    // Row 4: Cyans, Blues & Indigos
    0xFF00E5FF.toInt(), 0xFF00B0FF.toInt(), 0xFF2979FF.toInt(), 0xFF3D5AFE.toInt(), 
    0xFF536DFE.toInt(), 0xFF1976D2.toInt(), 0xFF0D47A1.toInt(), 0xFF303F9F.toInt(),
    // Row 5: Purples, Magentas & Pinks
    0xFF651FFF.toInt(), 0xFF7C4DFF.toInt(), 0xFFD500F9.toInt(), 0xFFE040FB.toInt(), 
    0xFFFF4081.toInt(), 0xFFFF80AB.toInt(), 0xFFF50057.toInt(), 0xFF880E4F.toInt()
)

data class FontPreset(
    val id: String,
    val displayName: String,
    val previewText: String = "FlowCut Video Editor",
    val typeface: android.graphics.Typeface
)

data class AnimPreset(
    val id: Int,
    val name: String
)

/**
 * 1. TextColorSheet (Matches Screenshot 1):
 * - Top horizontal scroll: Rainbow spectrum wheel + 16 circular swatches (38.dp) with pink active ring.
 * - Middle row: Opacity label + percentage + pink slider controlling active tab's alpha.
 * - Bottom row: 4 tabs (TEXT | LABEL | BORDER | SHADOW), auto-bumping alpha from 0 to 1.0f on color pick.
 */
@Composable
fun TextColorSheet(
    initialTextColor: Int = 0xFFFFFFFF.toInt(),
    initialTextAlpha: Float = 1.0f,
    initialLabelColor: Int = 0x00000000,
    initialLabelAlpha: Float = 0.0f,
    initialBorderColor: Int = 0xFF000000.toInt(),
    initialBorderAlpha: Float = 0.0f,
    initialShadowColor: Int = 0xFF000000.toInt(),
    initialShadowAlpha: Float = 0.6f,
    onLiveChange: (
        textColor: Int, textAlpha: Float,
        labelColor: Int, labelAlpha: Float,
        borderColor: Int, borderAlpha: Float,
        shadowColor: Int, shadowAlpha: Float
    ) -> Unit,
    onDone: (
        textColor: Int, textAlpha: Float,
        labelColor: Int, labelAlpha: Float,
        borderColor: Int, borderAlpha: Float,
        shadowColor: Int, shadowAlpha: Float
    ) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var textColor by remember { mutableIntStateOf(initialTextColor) }
    var textAlpha by remember { mutableFloatStateOf(initialTextAlpha) }
    var labelColor by remember { mutableIntStateOf(initialLabelColor) }
    var labelAlpha by remember { mutableFloatStateOf(initialLabelAlpha) }
    var borderColor by remember { mutableIntStateOf(initialBorderColor) }
    var borderAlpha by remember { mutableFloatStateOf(initialBorderAlpha) }
    var shadowColor by remember { mutableIntStateOf(initialShadowColor) }
    var shadowAlpha by remember { mutableFloatStateOf(initialShadowAlpha) }

    var activeTab by remember { mutableIntStateOf(0) } // 0: TEXT, 1: LABEL, 2: BORDER, 3: SHADOW
    var isCustomPaletteOpen by remember { mutableStateOf(false) }
    var customHue by remember { mutableFloatStateOf(0f) }

    fun notifyChange() {
        onLiveChange(textColor, textAlpha, labelColor, labelAlpha, borderColor, borderAlpha, shadowColor, shadowAlpha)
    }

    fun pickColor(colorArgb: Int) {
        when (activeTab) {
            0 -> {
                textColor = colorArgb
                if (textAlpha <= 0.01f) textAlpha = 1.0f
            }
            1 -> {
                labelColor = colorArgb
                if (labelAlpha <= 0.01f) labelAlpha = 1.0f
            }
            2 -> {
                borderColor = colorArgb
                if (borderAlpha <= 0.01f) borderAlpha = 1.0f
            }
            3 -> {
                shadowColor = colorArgb
                if (shadowAlpha <= 0.01f) shadowAlpha = 0.8f
            }
        }
        notifyChange()
    }

    fun changeAlpha(newAlpha: Float) {
        when (activeTab) {
            0 -> textAlpha = newAlpha
            1 -> labelAlpha = newAlpha
            2 -> borderAlpha = newAlpha
            3 -> shadowAlpha = newAlpha
        }
        notifyChange()
    }

    val currentSelectedColor = when (activeTab) {
        0 -> textColor
        1 -> labelColor
        2 -> borderColor
        else -> shadowColor
    }
    val currentSelectedAlpha = when (activeTab) {
        0 -> textAlpha
        1 -> labelAlpha
        2 -> borderAlpha
        else -> shadowAlpha
    }

    BackHandler { onCancel() }

    Dialog(
        onDismissRequest = onCancel,
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
                    ) { onCancel() }
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
                        .verticalScroll(rememberScrollState())
                        .padding(top = 10.dp, bottom = 18.dp, start = 16.dp, end = 16.dp)
                ) {
                    // Header Row (Close ✕, Title, Done ✓)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onCancel) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Text(
                            text = "Color",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        IconButton(
                            onClick = {
                                onDone(textColor, textAlpha, labelColor, labelAlpha, borderColor, borderAlpha, shadowColor, shadowAlpha)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = PINK_RED_ACCENT,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Row 1: Rainbow Spectrum Wheel + 16 Circular Color Swatches (Horizontal Scroll)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Rainbow Spectrum Wheel (Toggles interactive Custom Color Palette)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.sweepGradient(
                                        listOf(
                                            Color.Red,
                                            Color.Yellow,
                                            Color.Green,
                                            Color.Cyan,
                                            Color.Blue,
                                            Color.Magenta,
                                            Color.Red
                                        )
                                    )
                                )
                                .border(
                                    BorderStroke(
                                        if (isCustomPaletteOpen) 2.5.dp else 1.dp,
                                        if (isCustomPaletteOpen) PINK_RED_ACCENT else Color.White.copy(alpha = 0.4f)
                                    ),
                                    CircleShape
                                )
                                .clickable {
                                    isCustomPaletteOpen = !isCustomPaletteOpen
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.9f))
                            )
                        }

                        // 16 Curated Swatches
                        TEXT_COLOR_PALETTE.forEach { colorArgb ->
                            val isSelected = currentSelectedColor == colorArgb
                            val isWhiteOrLight = colorArgb == 0xFFFFFFFF.toInt() || colorArgb == 0xFFD1D1D6.toInt()

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorArgb))
                                    .border(
                                        BorderStroke(
                                            if (isSelected) 2.5.dp else 1.dp,
                                            if (isSelected) PINK_RED_ACCENT else if (isWhiteOrLight) Color(0x33FFFFFF) else Color.Transparent
                                        ),
                                        CircleShape
                                    )
                                    .clickable {
                                        pickColor(colorArgb)
                                        isCustomPaletteOpen = false
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = if (colorArgb == 0xFF000000.toInt()) Color.White else Color.Black,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Interactive Custom Color Palette (40-Color Spectrum Grid + Continuous Rainbow Hue Bar)
                    if (isCustomPaletteOpen) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = DARK_CARD_BG,
                            border = BorderStroke(1.dp, DARK_SURFACE_BORDER)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Swatch & Hex preview row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(Color(currentSelectedColor))
                                                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                                        )
                                        val hexStr = String.format(Locale.US, "#%06X", 0xFFFFFF and currentSelectedColor)
                                        Text(
                                            text = hexStr,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .height(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(PINK_RED_ACCENT.copy(alpha = 0.15f))
                                            .clickable { isCustomPaletteOpen = false }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Close", color = PINK_RED_ACCENT, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                // Continuous Rainbow Hue Slider
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(28.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(
                                                        Color.Red, Color.Yellow, Color.Green,
                                                        Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                                                    )
                                                )
                                            )
                                    )
                                    Slider(
                                        value = customHue,
                                        onValueChange = { h ->
                                            customHue = h
                                            val hsv = floatArrayOf(h, 1.0f, 1.0f)
                                            val rgb = android.graphics.Color.HSVToColor(hsv)
                                            pickColor(rgb)
                                        },
                                        valueRange = 0f..360f,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = Color.Transparent,
                                            inactiveTrackColor = Color.Transparent
                                        )
                                    )
                                }

                                // 40-Color Full Spectrum Grid
                                val gridRows = RICH_CUSTOM_PALETTE_40.chunked(8)
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    gridRows.forEach { rowColors ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            rowColors.forEach { cArgb ->
                                                val isPicked = currentSelectedColor == cArgb
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(cArgb))
                                                        .border(
                                                            BorderStroke(
                                                                if (isPicked) 2.dp else 0.5.dp,
                                                                if (isPicked) PINK_RED_ACCENT else Color.White.copy(alpha = 0.2f)
                                                            ),
                                                            CircleShape
                                                        )
                                                        .clickable { pickColor(cArgb) },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isPicked) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(6.dp)
                                                                .clip(CircleShape)
                                                                .background(if (cArgb == 0xFF000000.toInt()) Color.White else Color.Black)
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

                    Spacer(modifier = Modifier.height(18.dp))

                    // Row 2: Opacity Slider (Pink)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Opacity",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${(currentSelectedAlpha * 100).toInt()}%",
                            color = PINK_RED_ACCENT,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = currentSelectedAlpha,
                        onValueChange = { changeAlpha(it) },
                        valueRange = 0.0f..1.0f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = PINK_RED_ACCENT,
                            activeTrackColor = PINK_RED_ACCENT,
                            inactiveTrackColor = Color(0xFF3A3A3C)
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Row 3: 4 Tabs (TEXT | LABEL | BORDER | SHADOW)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val tabs = listOf("TEXT", "LABEL", "BORDER", "SHADOW")
                        tabs.forEachIndexed { index, title ->
                            val isTabSelected = activeTab == index
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clickable {
                                        activeTab = index
                                        isCustomPaletteOpen = false
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isTabSelected) PINK_RED_ACCENT.copy(alpha = 0.18f) else DARK_CARD_BG,
                                border = BorderStroke(
                                    1.dp,
                                    if (isTabSelected) PINK_RED_ACCENT else Color.Transparent
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = title,
                                        color = if (isTabSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium
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

/**
 * 2. TextFontSheet (Matches Screenshot 2):
 * - Vertical list of fonts rendered in actual Typeface at 20sp.
 * - Bottom bar: RECENT and FONTS tabs, plus pink (+) FAB for .ttf/.otf document picker.
 */
@Composable
fun TextFontSheet(
    initialFontName: String = "Roboto-Medium",
    initialCustomFontPath: String = "",
    customFonts: List<String> = emptyList(),
    onPickCustomFont: () -> Unit,
    onLiveChange: (fontName: String, customFontPath: String) -> Unit,
    onDone: (fontName: String, customFontPath: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFontName by remember { mutableStateOf(initialFontName) }
    var selectedCustomPath by remember { mutableStateOf(initialCustomFontPath) }
    var selectedTab by remember { mutableIntStateOf(1) } // 0: RECENT, 1: FONTS

    val builtInPresets = remember {
        listOf(
            FontPreset("Roboto-Medium", "Roboto Medium", "The quick brown fox jumps", android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)),
            FontPreset("PermanentMarker", "Permanent Marker", "FlowCut Creative Video", android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL)),
            FontPreset("BEBAS", "Bebas Bold", "HEADLINE BLOCK TITLE", android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)),
            FontPreset("Aileron", "Aileron Light", "Clean Modern Aesthetic", android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)),
            FontPreset("Roboto-Thin", "Roboto Thin", "Minimal Elegance", android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)),
            FontPreset("Serif-Bold", "Serif Bold", "Classic Editorial Type", android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)),
            FontPreset("Monospace", "Monospace Code", "0123456789 ABCDEF", android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL))
        )
    }

    fun selectBuiltin(preset: FontPreset) {
        selectedFontName = preset.id
        selectedCustomPath = ""
        onLiveChange(preset.id, "")
    }

    fun selectCustom(path: String) {
        selectedFontName = "Custom"
        selectedCustomPath = path
        onLiveChange("Custom", path)
    }

    BackHandler { onCancel() }

    Dialog(
        onDismissRequest = onCancel,
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
                    ) { onCancel() }
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
                        .padding(top = 10.dp, bottom = 18.dp, start = 16.dp, end = 16.dp)
                ) {
                    // Header Row (Close ✕, Title, Done ✓)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onCancel) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Text(
                            text = "Font",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        IconButton(
                            onClick = {
                                onDone(selectedFontName, selectedCustomPath)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = PINK_RED_ACCENT,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Scrollable vertical list of fonts (Height-constrained, e.g. 260.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Custom fonts (if any)
                        customFonts.forEach { customPath ->
                            val file = File(customPath)
                            if (file.exists()) {
                                val fontName = file.nameWithoutExtension
                                val isSelected = selectedCustomPath == customPath
                                val customTf = remember(customPath) {
                                    try {
                                        android.graphics.Typeface.createFromFile(file)
                                    } catch (_: Exception) {
                                        android.graphics.Typeface.DEFAULT
                                    }
                                }

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectCustom(customPath) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) PINK_RED_ACCENT.copy(alpha = 0.15f) else DARK_CARD_BG,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) PINK_RED_ACCENT else Color.Transparent
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = fontName,
                                                color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Custom Imported Font",
                                                color = Color.White.copy(alpha = 0.85f),
                                                fontSize = 20.sp,
                                                fontFamily = FontFamily(customTf)
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = PINK_RED_ACCENT,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Built-in presets
                        builtInPresets.forEach { preset ->
                            val isSelected = selectedFontName == preset.id && selectedCustomPath.isEmpty()
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectBuiltin(preset) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) PINK_RED_ACCENT.copy(alpha = 0.15f) else DARK_CARD_BG,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) PINK_RED_ACCENT else Color.Transparent
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = preset.displayName,
                                            color = if (isSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.6f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = preset.previewText,
                                            color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                            fontSize = 20.sp,
                                            fontFamily = FontFamily(preset.typeface)
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = PINK_RED_ACCENT,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Bottom Row: [RECENT | FONTS] Tabs + Pink (+) FAB for custom .ttf/.otf
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("RECENT", "FONTS").forEachIndexed { idx, tabTitle ->
                                val isTabSelected = selectedTab == idx
                                Surface(
                                    modifier = Modifier
                                        .height(36.dp)
                                        .clickable { selectedTab = idx },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isTabSelected) PINK_RED_ACCENT.copy(alpha = 0.18f) else DARK_CARD_BG,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isTabSelected) PINK_RED_ACCENT else Color.Transparent
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = tabTitle,
                                            color = if (isTabSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.6f),
                                            fontSize = 12.sp,
                                            fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        // Pink (+) FAB
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(PINK_RED_ACCENT)
                                .clickable { onPickCustomFont() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Import Font (.ttf, .otf)",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 3. TextAnimationSheet (Matches Screenshot 3):
 * - Horizontal cards (72.dp x 72.dp, 0xFF2B2B2E) with "ABC" badge, IN/OUT/LOOP presets.
 * - Pink Color(0xFFFF2D55) active selection and live evaluation on GL canvas.
 * - Duration slider for IN and OUT tabs.
 */
@Composable
fun TextAnimationSheet(
    initialAnimInType: Int = 0,
    initialAnimInDurationUs: Long = 500_000L,
    initialAnimOutType: Int = 0,
    initialAnimOutDurationUs: Long = 500_000L,
    initialAnimLoopType: Int = 0,
    onLiveChange: (inType: Int, inDurationUs: Long, outType: Int, outDurationUs: Long, loopType: Int) -> Unit,
    onDone: (inType: Int, inDurationUs: Long, outType: Int, outDurationUs: Long, loopType: Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var animInType by remember { mutableIntStateOf(initialAnimInType) }
    var animInDurationUs by remember { mutableLongStateOf(initialAnimInDurationUs) }
    var animOutType by remember { mutableIntStateOf(initialAnimOutType) }
    var animOutDurationUs by remember { mutableLongStateOf(initialAnimOutDurationUs) }
    var animLoopType by remember { mutableIntStateOf(initialAnimLoopType) }
    var activeTab by remember { mutableIntStateOf(0) } // 0: IN, 1: OUT, 2: LOOP

    val inPresets = remember {
        listOf(
            AnimPreset(0, "None"),
            AnimPreset(1, "Fade In"),
            AnimPreset(2, "Zoom In"),
            AnimPreset(3, "Slide Up"),
            AnimPreset(4, "Slide Down"),
            AnimPreset(5, "Slide Left"),
            AnimPreset(6, "Slide Right"),
            AnimPreset(7, "Bounce In"),
            AnimPreset(8, "Spin In"),
            AnimPreset(9, "Pop In")
        )
    }

    val outPresets = remember {
        listOf(
            AnimPreset(0, "None"),
            AnimPreset(1, "Fade Out"),
            AnimPreset(2, "Zoom Out"),
            AnimPreset(3, "Slide Up"),
            AnimPreset(4, "Slide Down"),
            AnimPreset(5, "Slide Left"),
            AnimPreset(6, "Slide Right"),
            AnimPreset(7, "Spin Out")
        )
    }

    val loopPresets = remember {
        listOf(
            AnimPreset(0, "None"),
            AnimPreset(1, "Pulse"),
            AnimPreset(2, "Shake"),
            AnimPreset(3, "Float"),
            AnimPreset(4, "Spin"),
            AnimPreset(5, "Flash"),
            AnimPreset(6, "Rainbow Glow")
        )
    }

    fun notifyChange() {
        onLiveChange(animInType, animInDurationUs, animOutType, animOutDurationUs, animLoopType)
    }

    BackHandler { onCancel() }

    Dialog(
        onDismissRequest = onCancel,
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
                    ) { onCancel() }
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
                        .padding(top = 10.dp, bottom = 18.dp, start = 16.dp, end = 16.dp)
                ) {
                    // Header Row (Close ✕, Title, Done ✓)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onCancel) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Text(
                            text = "Animation",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        IconButton(
                            onClick = {
                                onDone(animInType, animInDurationUs, animOutType, animOutDurationUs, animLoopType)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = PINK_RED_ACCENT,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Horizontal cards row (72.dp x 72.dp, 0xFF2B2B2E)
                    val currentPresets = when (activeTab) {
                        0 -> inPresets
                        1 -> outPresets
                        else -> loopPresets
                    }
                    val currentSelectedPresetId = when (activeTab) {
                        0 -> animInType
                        1 -> animOutType
                        else -> animLoopType
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        currentPresets.forEach { preset ->
                            val isSelected = currentSelectedPresetId == preset.id
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DARK_CARD_BG)
                                    .border(
                                        BorderStroke(
                                            if (isSelected) 2.dp else 1.dp,
                                            if (isSelected) PINK_RED_ACCENT else Color.Transparent
                                        ),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        when (activeTab) {
                                            0 -> animInType = preset.id
                                            1 -> animOutType = preset.id
                                            2 -> animLoopType = preset.id
                                        }
                                        notifyChange()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(4.dp)
                                ) {
                                    if (preset.id == 0) {
                                        Icon(
                                            imageVector = Icons.Default.Block,
                                            contentDescription = "None",
                                            tint = if (isSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.6f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isSelected) PINK_RED_ACCENT.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.12f)
                                        ) {
                                            Text(
                                                text = "ABC",
                                                color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = preset.name,
                                        color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Optional Duration Slider for IN or OUT
                    val showDurationSlider = (activeTab == 0 && animInType > 0) || (activeTab == 1 && animOutType > 0)
                    if (showDurationSlider) {
                        val currentDurUs = if (activeTab == 0) animInDurationUs else animOutDurationUs
                        val currentDurSec = currentDurUs / 1_000_000.0f

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Duration",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = String.format(Locale.US, "%.1fs", currentDurSec),
                                color = PINK_RED_ACCENT,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Slider(
                            value = currentDurSec,
                            onValueChange = { newSec ->
                                val newUs = (newSec * 1_000_000L).toLong()
                                if (activeTab == 0) {
                                    animInDurationUs = newUs
                                } else {
                                    animOutDurationUs = newUs
                                }
                                notifyChange()
                            },
                            valueRange = 0.1f..2.0f,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = PINK_RED_ACCENT,
                                activeTrackColor = PINK_RED_ACCENT,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            )
                        )
                    } else {
                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bottom Row: 3 Tabs (IN | OUT | LOOP)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val tabs = listOf("IN", "OUT", "LOOP")
                        tabs.forEachIndexed { idx, tabTitle ->
                            val isTabSelected = activeTab == idx
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clickable { activeTab = idx },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isTabSelected) PINK_RED_ACCENT.copy(alpha = 0.18f) else DARK_CARD_BG,
                                border = BorderStroke(
                                    1.dp,
                                    if (isTabSelected) PINK_RED_ACCENT else Color.Transparent
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = tabTitle,
                                        color = if (isTabSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium
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

/**
 * 4. TextAdjustSheet (Matches Screenshot 4):
 * - Top bar: Alignment icons (Left, Center, Right) with pink active tint.
 * - 3 Pink sliders: Character (-0.05f..0.5f), Line (0.7f..2.0f), Opacity (0.0f..1.0f).
 * - 4 Format toggle buttons: B, I, U, AA.
 */
@Composable
fun TextAdjustSheet(
    initialTextAlign: Int = 1,
    initialLetterSpacing: Float = 0.0f,
    initialLineSpacing: Float = 1.0f,
    initialTextAlpha: Float = 1.0f,
    initialIsBold: Boolean = false,
    initialIsItalic: Boolean = false,
    initialIsUnderline: Boolean = false,
    initialIsUppercase: Boolean = false,
    onLiveChange: (
        align: Int, letterSpacing: Float, lineSpacing: Float, textAlpha: Float,
        isBold: Boolean, isItalic: Boolean, isUnderline: Boolean, isUppercase: Boolean
    ) -> Unit,
    onDone: (
        align: Int, letterSpacing: Float, lineSpacing: Float, textAlpha: Float,
        isBold: Boolean, isItalic: Boolean, isUnderline: Boolean, isUppercase: Boolean
    ) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var textAlign by remember { mutableIntStateOf(initialTextAlign) }
    var letterSpacing by remember { mutableFloatStateOf(initialLetterSpacing) }
    var lineSpacing by remember { mutableFloatStateOf(initialLineSpacing) }
    var textAlpha by remember { mutableFloatStateOf(initialTextAlpha) }
    var isBold by remember { mutableStateOf(initialIsBold) }
    var isItalic by remember { mutableStateOf(initialIsItalic) }
    var isUnderline by remember { mutableStateOf(initialIsUnderline) }
    var isUppercase by remember { mutableStateOf(initialIsUppercase) }

    fun notifyChange() {
        onLiveChange(textAlign, letterSpacing, lineSpacing, textAlpha, isBold, isItalic, isUnderline, isUppercase)
    }

    BackHandler { onCancel() }

    Dialog(
        onDismissRequest = onCancel,
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
                    ) { onCancel() }
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
                        .padding(top = 10.dp, bottom = 18.dp, start = 16.dp, end = 16.dp)
                ) {
                    // Header Row (Close ✕, Title, Done ✓)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onCancel) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Text(
                            text = "Adjust",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        IconButton(
                            onClick = {
                                onDone(textAlign, letterSpacing, lineSpacing, textAlpha, isBold, isItalic, isUnderline, isUppercase)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = PINK_RED_ACCENT,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Top Row: Alignment Icons (Left, Center, Right)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val aligns = listOf(
                            0 to Icons.AutoMirrored.Filled.FormatAlignLeft,
                            1 to Icons.Default.FormatAlignCenter,
                            2 to Icons.AutoMirrored.Filled.FormatAlignRight
                        )
                        aligns.forEach { (alignVal, icon) ->
                            val isSelected = textAlign == alignVal
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clickable {
                                        textAlign = alignVal
                                        notifyChange()
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) PINK_RED_ACCENT.copy(alpha = 0.18f) else DARK_CARD_BG,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) PINK_RED_ACCENT else Color.Transparent
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = "Align",
                                        tint = if (isSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Slider 1: Character Spacing (-0.05f..0.5f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Character",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${(letterSpacing * 100).toInt()}%",
                            color = PINK_RED_ACCENT,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = letterSpacing,
                        onValueChange = {
                            letterSpacing = it
                            notifyChange()
                        },
                        valueRange = -0.08f..1.40f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = PINK_RED_ACCENT,
                            activeTrackColor = PINK_RED_ACCENT,
                            inactiveTrackColor = Color(0xFF3A3A3C)
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Slider 2: Line Spacing (0.55f..2.80f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Line",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format(Locale.US, "%.1fx", lineSpacing),
                            color = PINK_RED_ACCENT,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = lineSpacing,
                        onValueChange = {
                            lineSpacing = it
                            notifyChange()
                        },
                        valueRange = 0.55f..2.80f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = PINK_RED_ACCENT,
                            activeTrackColor = PINK_RED_ACCENT,
                            inactiveTrackColor = Color(0xFF3A3A3C)
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Slider 3: Opacity (0.0f..1.0f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Opacity",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${(textAlpha * 100).toInt()}%",
                            color = PINK_RED_ACCENT,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = textAlpha,
                        onValueChange = {
                            textAlpha = it
                            notifyChange()
                        },
                        valueRange = 0.0f..1.0f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = PINK_RED_ACCENT,
                            activeTrackColor = PINK_RED_ACCENT,
                            inactiveTrackColor = Color(0xFF3A3A3C)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Format Toggle Buttons: B, I, U, AA
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val toggles = listOf(
                            "B" to isBold,
                            "I" to isItalic,
                            "U" to isUnderline,
                            "AA" to isUppercase
                        )
                        toggles.forEachIndexed { idx, (label, active) ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clickable {
                                        when (idx) {
                                            0 -> isBold = !isBold
                                            1 -> isItalic = !isItalic
                                            2 -> isUnderline = !isUnderline
                                            3 -> isUppercase = !isUppercase
                                        }
                                        notifyChange()
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = if (active) PINK_RED_ACCENT.copy(alpha = 0.18f) else DARK_CARD_BG,
                                border = BorderStroke(
                                    1.dp,
                                    if (active) PINK_RED_ACCENT else Color.Transparent
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        color = if (active) PINK_RED_ACCENT else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (active || idx == 0) FontWeight.Bold else FontWeight.Medium,
                                        fontStyle = if (idx == 1) FontStyle.Italic else FontStyle.Normal,
                                        textDecoration = if (idx == 2) TextDecoration.Underline else TextDecoration.None
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

// ---------------------------------------------------------------------------
// Phase 4: Pro Color Filters & GPU Visual Effects Suite
// ---------------------------------------------------------------------------

data class FilterPresetInfo(
    val id: Int,
    val name: String,
    val gradient: Brush
)

val FILTER_PRESETS = listOf(
    FilterPresetInfo(0, "None", Brush.verticalGradient(listOf(Color(0xFF2C2C2E), Color(0xFF1C1C1E)))),
    FilterPresetInfo(1, "Vivid", Brush.verticalGradient(listOf(Color(0xFFFF334B), Color(0xFF007AFF)))),
    FilterPresetInfo(2, "Warm", Brush.verticalGradient(listOf(Color(0xFFFF9500), Color(0xFFFFCC00)))),
    FilterPresetInfo(3, "Cool", Brush.verticalGradient(listOf(Color(0xFF00C6FF), Color(0xFF0072FF)))),
    FilterPresetInfo(4, "Teal & Orange", Brush.horizontalGradient(listOf(Color(0xFF008080), Color(0xFFFF8C00)))),
    FilterPresetInfo(5, "Vintage", Brush.verticalGradient(listOf(Color(0xFF8B5A2B), Color(0xFFD2B48C)))),
    FilterPresetInfo(6, "Noir B&W", Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFFFFFFFF)))),
    FilterPresetInfo(7, "Matte Fade", Brush.verticalGradient(listOf(Color(0xFF4A5568), Color(0xFFCBD5E0)))),
    FilterPresetInfo(8, "Cyberpunk", Brush.horizontalGradient(listOf(Color(0xFFFF007F), Color(0xFF00F0FF)))),
    FilterPresetInfo(9, "Golden Hour", Brush.verticalGradient(listOf(Color(0xFFFF7E00), Color(0xFFFFD700)))),
    FilterPresetInfo(10, "Dramatic", Brush.verticalGradient(listOf(Color(0xFF14171A), Color(0xFF657786)))),
    FilterPresetInfo(11, "Pastel", Brush.horizontalGradient(listOf(Color(0xFFFBCFE8), Color(0xFFA7F3D0))))
)

data class EffectPresetInfo(
    val id: Int,
    val name: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val gradient: Brush,
    val iconTint: Color = Color.White
)

val EFFECT_PRESETS = listOf(
    EffectPresetInfo(0, "None", Icons.Default.Block, Brush.verticalGradient(listOf(Color(0xFF2C2C2E), Color(0xFF1C1C1E))), Color(0xFF8E8E93)),
    EffectPresetInfo(1, "RGB Split", Icons.Default.InvertColors, Brush.horizontalGradient(listOf(Color(0xFFFF0055), Color(0xFF00EEFF)))),
    EffectPresetInfo(2, "Vignette", Icons.Default.PanoramaFishEye, Brush.radialGradient(listOf(Color(0xFF444444), Color(0xFF000000)))),
    EffectPresetInfo(3, "VHS", Icons.Default.GraphicEq, Brush.verticalGradient(listOf(Color(0xFF5E2BFF), Color(0xFF00F5D4)))),
    EffectPresetInfo(4, "Pulse", Icons.Default.Favorite, Brush.verticalGradient(listOf(Color(0xFFFF0055), Color(0xFFFF5252)))),
    EffectPresetInfo(5, "Shake", Icons.Default.Vibration, Brush.verticalGradient(listOf(Color(0xFFFF9100), Color(0xFFFFD600)))),
    EffectPresetInfo(6, "Flash", Icons.Default.FlashOn, Brush.verticalGradient(listOf(Color(0xFFFFEA00), Color(0xFFFFFFFF))), Color.Black),
    EffectPresetInfo(7, "Glow", Icons.Default.Lightbulb, Brush.verticalGradient(listOf(Color(0xFF9D4EDD), Color(0xFFFF70A6)))),
    EffectPresetInfo(8, "Pixelate", Icons.Default.GridOn, Brush.verticalGradient(listOf(Color(0xFF06D6A0), Color(0xFF118AB2))))
)

/**
 * Dedicated FilterSheet anchored cleanly at Alignment.BottomCenter matching our dark + #FF2D55 theme.
 * Features a top row with "Intensity" label + pink slider (0% to 100%, default 80%), "Apply to All" button,
 * Cancel (✕), and Done (✓), plus a scrollable row of 12 Filter Cards (68.dp x 84.dp) with color-graded preview swatches.
 */
@Composable
fun FilterSheet(
    targetClipIndex: Int,
    snapshotHolder: SnapshotHolder,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    getClipFilterState: (clipId: String) -> ClipFilterState,
    onCommitSingleFilter: (clipIndex: Int, presetId: Int, intensity: Float) -> Unit,
    onCommitApplyAllFilter: (presetId: Int, intensity: Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSnapshot = snapshotHolder.current
    val clip = currentSnapshot.project.clips.getOrNull(targetClipIndex) ?: run {
        onDismiss()
        return
    }

    val originalClipFilter = remember(clip.id) { getClipFilterState(clip.id) }
    val originalAllStates = remember {
        currentSnapshot.project.clips.associate { it.id to getClipFilterState(it.id) }
    }

    var selectedPresetId by remember(clip.id) { mutableIntStateOf(originalClipFilter.filterPresetId) }
    var currentIntensity by remember(clip.id) {
        mutableFloatStateOf(if (originalClipFilter.filterIntensity in 0.01f..1.0f) originalClipFilter.filterIntensity else 0.80f)
    }
    var isApplyToAll by remember { mutableStateOf(false) }
    var isCommitted by remember { mutableStateOf(false) }

    fun applyLive(presetId: Int, intensity: Float, toAll: Boolean) {
        if (toAll) {
            for (c in snapshotHolder.current.project.clips) {
                PreviewRenderer.setClipFilter(c.id, presetId, intensity)
            }
        } else {
            for ((id, orig) in originalAllStates) {
                if (id == clip.id) {
                    PreviewRenderer.setClipFilter(id, presetId, intensity)
                } else {
                    PreviewRenderer.setClipFilter(id, orig.filterPresetId, orig.filterIntensity)
                }
            }
        }
        previewRenderer.requestRender()
        onWakeFrameDriver()
    }

    val handleClose = {
        if (!isCommitted) {
            for ((id, orig) in originalAllStates) {
                PreviewRenderer.setClipFilter(id, orig.filterPresetId, orig.filterIntensity)
            }
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        if (isApplyToAll) {
            onCommitApplyAllFilter(selectedPresetId, currentIntensity)
        } else {
            onCommitSingleFilter(targetClipIndex, selectedPresetId, currentIntensity)
        }
        onDismiss()
    }

    BackHandler { handleClose() }

    DisposableEffect(Unit) {
        onDispose {
            if (!isCommitted) {
                for ((id, orig) in originalAllStates) {
                    PreviewRenderer.setClipFilter(id, orig.filterPresetId, orig.filterIntensity)
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
                                    isApplyToAll = !isApplyToAll
                                    applyLive(selectedPresetId, currentIntensity, isApplyToAll)
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isApplyToAll) PINK_RED_ACCENT else DARK_CARD_BG,
                                border = BorderStroke(
                                    0.5.dp,
                                    if (isApplyToAll) PINK_RED_ACCENT else DARK_SURFACE_BORDER
                                ),
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
                                applyLive(selectedPresetId, newIntensity, isApplyToAll)
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
                                        applyLive(preset.id, currentIntensity, isApplyToAll)
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

/**
 * Dedicated EffectSheet anchored cleanly at Alignment.BottomCenter matching our dark + #FF2D55 theme.
 * Features a top row with "Intensity" label + pink slider (0% to 100%, default 70%), Cancel (✕), and Done (✓),
 * plus a scrollable row of 8 Visual Effect Cards (None + 8 effects = 9 cards, 68.dp x 84.dp) with expressive icons and colored preview badges.
 */
@Composable
fun EffectSheet(
    targetClipIndex: Int,
    snapshotHolder: SnapshotHolder,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    getClipFilterState: (clipId: String) -> ClipFilterState,
    onCommitSingleEffect: (clipIndex: Int, presetId: Int, intensity: Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSnapshot = snapshotHolder.current
    val clip = currentSnapshot.project.clips.getOrNull(targetClipIndex) ?: run {
        onDismiss()
        return
    }

    val originalClipFilter = remember(clip.id) { getClipFilterState(clip.id) }
    var selectedPresetId by remember(clip.id) { mutableIntStateOf(originalClipFilter.effectPresetId) }
    var currentIntensity by remember(clip.id) {
        mutableFloatStateOf(if (originalClipFilter.effectIntensity in 0.01f..1.0f) originalClipFilter.effectIntensity else 0.70f)
    }
    var isCommitted by remember { mutableStateOf(false) }

    fun applyLive(presetId: Int, intensity: Float) {
        PreviewRenderer.setClipEffect(clip.id, presetId, intensity)
        previewRenderer.requestRender()
        onWakeFrameDriver()
    }

    val handleClose = {
        if (!isCommitted) {
            PreviewRenderer.setClipEffect(clip.id, originalClipFilter.effectPresetId, originalClipFilter.effectIntensity)
            previewRenderer.requestRender()
            onWakeFrameDriver()
        }
        onDismiss()
    }

    val handleDone = {
        isCommitted = true
        onCommitSingleEffect(targetClipIndex, selectedPresetId, currentIntensity)
        onDismiss()
    }

    BackHandler { handleClose() }

    DisposableEffect(Unit) {
        onDispose {
            if (!isCommitted) {
                PreviewRenderer.setClipEffect(clip.id, originalClipFilter.effectPresetId, originalClipFilter.effectIntensity)
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

                        Text(
                            text = "Visual Effects",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

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

                    // Scrollable row of 8 Visual Effect Cards (68.dp x 84.dp)
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

/**
 * Dedicated PIP Mask BottomSheet with live preview and zero heap allocations on render loop.
 * Supported Shapes:
 * 0: None, 1: Circle, 2: Rect (Rounded Rect), 3: Heart, 4: Star, 5: Linear Split, 6: Mirror Band
 */
@Composable
fun PipMaskSheet(
    initialMaskType: Int,
    initialMaskFeather: Float,
    initialMaskInvert: Boolean,
    onLiveChange: (maskType: Int, feather: Float, invert: Boolean) -> Unit,
    onDone: (maskType: Int, feather: Float, invert: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedMaskType by remember { mutableIntStateOf(initialMaskType) }
    var feather by remember { mutableFloatStateOf(initialMaskFeather.coerceIn(0.0f, 0.5f)) }
    var invert by remember { mutableStateOf(initialMaskInvert) }

    fun notifyChange() {
        onLiveChange(selectedMaskType, feather, invert)
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(DARK_SHEET_BG)
                    .border(BorderStroke(0.5.dp, DARK_SURFACE_BORDER), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // Header Row: Cancel (✕), "Mask", Invert chip, Done (✓)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                        }
                        Text(
                            text = "Mask",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        // Invert toggle chip
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (invert) PINK_RED_ACCENT else DARK_CARD_BG)
                                .border(BorderStroke(1.dp, if (invert) PINK_RED_ACCENT else DARK_SURFACE_BORDER), RoundedCornerShape(12.dp))
                                .clickable {
                                    invert = !invert
                                    notifyChange()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Invert",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(
                            onClick = { onDone(selectedMaskType, feather, invert) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Done", tint = PINK_RED_ACCENT)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Feather Slider Row
                    if (selectedMaskType > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Feather",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(60.dp)
                            )
                            Slider(
                                value = feather / 0.5f,
                                onValueChange = { norm ->
                                    feather = norm * 0.5f
                                    notifyChange()
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
                                text = "${(feather / 0.5f * 100f).roundToInt()}%",
                                color = PINK_RED_ACCENT,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(42.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 7 Shape Cards Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val maskShapes = listOf(
                            Triple(0, "None", Icons.Default.Block),
                            Triple(1, "Circle", Icons.Default.Circle),
                            Triple(2, "Rect", Icons.Default.CropSquare),
                            Triple(3, "Heart", Icons.Default.Favorite),
                            Triple(4, "Star", Icons.Default.Star),
                            Triple(5, "Linear", Icons.Default.Splitscreen),
                            Triple(6, "Mirror", Icons.Default.ViewAgenda)
                        )
                        for ((typeId, label, icon) in maskShapes) {
                            val isSelected = selectedMaskType == typeId
                            Column(
                                modifier = Modifier
                                    .size(width = 64.dp, height = 76.dp)
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
                                        selectedMaskType = typeId
                                        notifyChange()
                                    }
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) PINK_RED_ACCENT.copy(alpha = 0.2f) else Color(0xFF202022)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = label,
                                        tint = if (isSelected) PINK_RED_ACCENT else Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Text(
                                    text = label,
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

/**
 * Dedicated PIP Crop BottomSheet with aspect ratio presets and 4 normalized edge trim sliders.
 * UV bounds: [cropLeft..cropRight, cropTop..cropBottom]
 */
@Composable
fun PipCropSheet(
    initialCropLeft: Float,
    initialCropTop: Float,
    initialCropRight: Float,
    initialCropBottom: Float,
    intrinsicWidth: Int = 1080,
    intrinsicHeight: Int = 1920,
    onLiveChange: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onDone: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var cropLeft by remember { mutableFloatStateOf(initialCropLeft.coerceIn(0f, 0.45f)) }
    var cropTop by remember { mutableFloatStateOf(initialCropTop.coerceIn(0f, 0.45f)) }
    var cropRight by remember { mutableFloatStateOf(initialCropRight.coerceIn(0.55f, 1f)) }
    var cropBottom by remember { mutableFloatStateOf(initialCropBottom.coerceIn(0.55f, 1f)) }

    fun notifyChange() {
        onLiveChange(cropLeft, cropTop, cropRight, cropBottom)
    }

    fun applyPreset(targetAspect: Float?) {
        if (targetAspect == null) {
            // Reset to full
            cropLeft = 0.0f
            cropTop = 0.0f
            cropRight = 1.0f
            cropBottom = 1.0f
            notifyChange()
            return
        }
        val mediaW = if (intrinsicWidth > 0) intrinsicWidth.toFloat() else 1080f
        val mediaH = if (intrinsicHeight > 0) intrinsicHeight.toFloat() else 1920f
        val mediaAspect = mediaW / mediaH

        if (mediaAspect > targetAspect) {
            // Media is wider than target aspect: crop left and right
            val effectiveW = targetAspect / mediaAspect
            val margin = ((1.0f - effectiveW) / 2.0f).coerceIn(0f, 0.45f)
            cropLeft = margin
            cropRight = 1.0f - margin
            cropTop = 0.0f
            cropBottom = 1.0f
        } else {
            // Media is taller than target aspect: crop top and bottom
            val effectiveH = mediaAspect / targetAspect
            val margin = ((1.0f - effectiveH) / 2.0f).coerceIn(0f, 0.45f)
            cropLeft = 0.0f
            cropRight = 1.0f
            cropTop = margin
            cropBottom = 1.0f - margin
        }
        notifyChange()
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(DARK_SHEET_BG)
                    .border(BorderStroke(0.5.dp, DARK_SURFACE_BORDER), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // Header Row: Cancel (✕), "Crop", Reset chip, Done (✓)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                        }
                        Text(
                            text = "Crop",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(DARK_CARD_BG)
                                .border(BorderStroke(1.dp, DARK_SURFACE_BORDER), RoundedCornerShape(12.dp))
                                .clickable { applyPreset(null) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Reset",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(
                            onClick = { onDone(cropLeft, cropTop, cropRight, cropBottom) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Done", tint = PINK_RED_ACCENT)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Preset Buttons Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val presets = listOf(
                            "Free" to null,
                            "1:1" to 1.0f,
                            "9:16" to (9f / 16f),
                            "16:9" to (16f / 9f),
                            "4:5" to (4f / 5f),
                            "3:4" to (3f / 4f)
                        )
                        for ((label, aspectVal) in presets) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DARK_CARD_BG)
                                    .border(BorderStroke(1.dp, DARK_SURFACE_BORDER), RoundedCornerShape(8.dp))
                                    .clickable { applyPreset(aspectVal) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4 Sliders (Left, Top, Right, Bottom)
                    // Left Trim
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Left", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.width(48.dp))
                        Slider(
                            value = cropLeft,
                            onValueChange = { v ->
                                cropLeft = v.coerceIn(0f, (cropRight - 0.1f).coerceAtLeast(0f))
                                notifyChange()
                            },
                            valueRange = 0f..0.45f,
                            colors = SliderDefaults.colors(thumbColor = PINK_RED_ACCENT, activeTrackColor = PINK_RED_ACCENT, inactiveTrackColor = Color(0xFF3A3A3C)),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(cropLeft * 100f).roundToInt()}%", color = PINK_RED_ACCENT, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                    }

                    // Top Trim
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Top", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.width(48.dp))
                        Slider(
                            value = cropTop,
                            onValueChange = { v ->
                                cropTop = v.coerceIn(0f, (cropBottom - 0.1f).coerceAtLeast(0f))
                                notifyChange()
                            },
                            valueRange = 0f..0.45f,
                            colors = SliderDefaults.colors(thumbColor = PINK_RED_ACCENT, activeTrackColor = PINK_RED_ACCENT, inactiveTrackColor = Color(0xFF3A3A3C)),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(cropTop * 100f).roundToInt()}%", color = PINK_RED_ACCENT, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                    }

                    // Right Trim
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Right", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.width(48.dp))
                        Slider(
                            value = cropRight,
                            onValueChange = { v ->
                                cropRight = v.coerceIn((cropLeft + 0.1f).coerceAtMost(1f), 1f)
                                notifyChange()
                            },
                            valueRange = 0.55f..1f,
                            colors = SliderDefaults.colors(thumbColor = PINK_RED_ACCENT, activeTrackColor = PINK_RED_ACCENT, inactiveTrackColor = Color(0xFF3A3A3C)),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(cropRight * 100f).roundToInt()}%", color = PINK_RED_ACCENT, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                    }

                    // Bottom Trim
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bottom", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.width(48.dp))
                        Slider(
                            value = cropBottom,
                            onValueChange = { v ->
                                cropBottom = v.coerceIn((cropTop + 0.1f).coerceAtMost(1f), 1f)
                                notifyChange()
                            },
                            valueRange = 0.55f..1f,
                            colors = SliderDefaults.colors(thumbColor = PINK_RED_ACCENT, activeTrackColor = PINK_RED_ACCENT, inactiveTrackColor = Color(0xFF3A3A3C)),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(cropBottom * 100f).roundToInt()}%", color = PINK_RED_ACCENT, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                    }
                }
            }
        }
    }
}

/**
 * Dedicated PIP Cutout / Chroma Key BottomSheet.
 * Supports color selection swatches (Green, Blue, Black, White, Red, Yellow, Cyan, Magenta),
 * threshold intensity, and edge smoothing blend.
 */
@Composable
fun PipCutoutSheet(
    initialChromaEnabled: Boolean,
    initialChromaColorArgb: Int,
    initialChromaThreshold: Float,
    initialChromaBlend: Float,
    onLiveChange: (enabled: Boolean, colorArgb: Int, threshold: Float, blend: Float) -> Unit,
    onDone: (enabled: Boolean, colorArgb: Int, threshold: Float, blend: Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var chromaEnabled by remember { mutableStateOf(initialChromaEnabled) }
    var selectedColorArgb by remember { mutableIntStateOf(initialChromaColorArgb) }
    var threshold by remember { mutableFloatStateOf(initialChromaThreshold.coerceIn(0.0f, 0.8f)) }
    var blend by remember { mutableFloatStateOf(initialChromaBlend.coerceIn(0.01f, 0.4f)) }

    fun notifyChange() {
        onLiveChange(chromaEnabled, selectedColorArgb, threshold, blend)
    }

    val swatches = listOf(
        "Green" to 0xFF00FF00.toInt(),
        "Blue" to 0xFF0000FF.toInt(),
        "Black" to 0xFF000000.toInt(),
        "White" to 0xFFFFFFFF.toInt(),
        "Red" to 0xFFFF0000.toInt(),
        "Yellow" to 0xFFFFFF00.toInt(),
        "Cyan" to 0xFF00FFFF.toInt(),
        "Magenta" to 0xFFFF00FF.toInt()
    )

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(DARK_SHEET_BG)
                    .border(BorderStroke(0.5.dp, DARK_SURFACE_BORDER), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // Header Row: Cancel (✕), "Cutout / Chroma", Switch chip, Done (✓)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                        }
                        Text(
                            text = "Cutout / Chroma",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (chromaEnabled) PINK_RED_ACCENT else DARK_CARD_BG)
                                .border(BorderStroke(1.dp, if (chromaEnabled) PINK_RED_ACCENT else DARK_SURFACE_BORDER), RoundedCornerShape(12.dp))
                                .clickable {
                                    chromaEnabled = !chromaEnabled
                                    notifyChange()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (chromaEnabled) "Active" else "Disabled",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(
                            onClick = { onDone(chromaEnabled, selectedColorArgb, threshold, blend) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Done", tint = PINK_RED_ACCENT)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Color Swatches Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for ((name, colorInt) in swatches) {
                            val isSelected = selectedColorArgb == colorInt
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorInt))
                                    .border(
                                        BorderStroke(
                                            if (isSelected) 3.dp else 1.dp,
                                            if (isSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.3f)
                                        ),
                                        CircleShape
                                    )
                                    .clickable {
                                        selectedColorArgb = colorInt
                                        if (!chromaEnabled) chromaEnabled = true
                                        notifyChange()
                                    }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Intensity Slider (Threshold: 0.0f..0.8f)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Intensity",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(68.dp)
                        )
                        Slider(
                            value = threshold / 0.8f,
                            onValueChange = { norm ->
                                threshold = norm * 0.8f
                                notifyChange()
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
                            text = "${(threshold / 0.8f * 100f).roundToInt()}%",
                            color = PINK_RED_ACCENT,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(42.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Edge Feather Slider (Blend: 0.01f..0.4f)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Feather",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(68.dp)
                        )
                        Slider(
                            value = (blend - 0.01f) / 0.39f,
                            onValueChange = { norm ->
                                blend = 0.01f + norm * 0.39f
                                notifyChange()
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
                            text = "${((blend - 0.01f) / 0.39f * 100f).roundToInt()}%",
                            color = PINK_RED_ACCENT,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(42.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact Overlay Adjust Sheet with 4 pink/red sliders (Opacity, Brightness, Contrast, Saturation)
 * and a Reset button, matching Phase 8 VideoGuru Spec.
 */
@Composable
fun OverlayAdjustSheet(
    initialOpacity: Float = 1.0f,
    initialBrightness: Float = 0.0f,
    initialContrast: Float = 1.0f,
    initialSaturation: Float = 1.0f,
    onLiveChange: (opacity: Float, brightness: Float, contrast: Float, saturation: Float) -> Unit,
    onDone: (opacity: Float, brightness: Float, contrast: Float, saturation: Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var opacity by remember { mutableFloatStateOf(initialOpacity.coerceIn(0.0f, 1.0f)) }
    var brightness by remember { mutableFloatStateOf(initialBrightness.coerceIn(-0.5f, 0.5f)) }
    var contrast by remember { mutableFloatStateOf(initialContrast.coerceIn(0.5f, 1.8f)) }
    var saturation by remember { mutableFloatStateOf(initialSaturation.coerceIn(0.0f, 2.0f)) }

    fun notifyChange() {
        onLiveChange(opacity, brightness, contrast, saturation)
    }

    fun resetAll() {
        opacity = 1.0f
        brightness = 0.0f
        contrast = 1.0f
        saturation = 1.0f
        notifyChange()
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(DARK_SHEET_BG)
                    .border(BorderStroke(0.5.dp, DARK_SURFACE_BORDER), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // Header Row: Cancel (✕), "Adjust Overlay", Reset Pill, Done (✓)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                        }

                        Text(
                            text = "Adjust Overlay",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DARK_CARD_BG)
                                    .clickable { resetAll() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "Reset",
                                    color = PINK_RED_ACCENT,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { onDone(opacity, brightness, contrast, saturation) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Done", tint = PINK_RED_ACCENT)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 1. Opacity Slider (0.0f..1.0f)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Opacity",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(76.dp)
                        )
                        Slider(
                            value = opacity,
                            onValueChange = {
                                opacity = it
                                notifyChange()
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
                            text = "${(opacity * 100f).roundToInt()}%",
                            color = PINK_RED_ACCENT,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(44.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 2. Brightness Slider (-0.5f..0.5f, norm 0f..1f)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Brightness",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(76.dp)
                        )
                        Slider(
                            value = (brightness + 0.5f),
                            onValueChange = { norm ->
                                brightness = norm - 0.5f
                                notifyChange()
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = PINK_RED_ACCENT,
                                activeTrackColor = PINK_RED_ACCENT,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        val bDisplay = (brightness * 100f).roundToInt()
                        Text(
                            text = if (bDisplay > 0) "+$bDisplay%" else "$bDisplay%",
                            color = PINK_RED_ACCENT,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(44.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 3. Contrast Slider (0.5f..1.8f, norm 0f..1f)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Contrast",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(76.dp)
                        )
                        Slider(
                            value = (contrast - 0.5f) / 1.3f,
                            onValueChange = { norm ->
                                contrast = 0.5f + norm * 1.3f
                                notifyChange()
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = PINK_RED_ACCENT,
                                activeTrackColor = PINK_RED_ACCENT,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        val cDisplay = ((contrast - 1.0f) * 100f).roundToInt()
                        Text(
                            text = if (cDisplay > 0) "+$cDisplay%" else "$cDisplay%",
                            color = PINK_RED_ACCENT,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(44.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 4. Saturation Slider (0.0f..2.0f, norm 0f..1f)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Saturation",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(76.dp)
                        )
                        Slider(
                            value = saturation / 2.0f,
                            onValueChange = { norm ->
                                saturation = norm * 2.0f
                                notifyChange()
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = PINK_RED_ACCENT,
                                activeTrackColor = PINK_RED_ACCENT,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        val sDisplay = ((saturation - 1.0f) * 100f).roundToInt()
                        Text(
                            text = if (sDisplay > 0) "+$sDisplay%" else "$sDisplay%",
                            color = PINK_RED_ACCENT,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(44.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Auto Captions Sheet allowing multi-line lyrics/script input or auto-generation from timeline beats/speech.
 * Slices sequential non-overlapping LayerType.TEXT items.
 */
@Composable
fun AutoCaptionsSheet(
    onGenerateCaptions: (phrases: List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors
    var scriptText by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(DARK_SHEET_BG)
                    .border(BorderStroke(0.5.dp, DARK_SURFACE_BORDER), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // Header Row: Close (✕), Title "Auto-Captions"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }

                        Text(
                            text = "Auto-Captions",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.size(36.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Paste or type script / lyrics (one phrase per line) or use auto-generate:",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Script Input TextField
                    OutlinedTextField(
                        value = scriptText,
                        onValueChange = { scriptText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = {
                            Text(
                                text = "Enter lyrics or speech text here...\nLine 1: Welcome to the video\nLine 2: Enjoy the moment",
                                color = Color.White.copy(alpha = 0.35f),
                                fontSize = 13.sp
                            )
                        },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DARK_CARD_BG,
                            unfocusedContainerColor = DARK_CARD_BG,
                            focusedIndicatorColor = PINK_RED_ACCENT,
                            unfocusedIndicatorColor = DARK_SURFACE_BORDER,
                            cursorColor = PINK_RED_ACCENT
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Auto-Generate from Timeline Beats button
                    OutlinedButton(
                        onClick = {
                            scriptText = listOf(
                                "Welcome to the video",
                                "Watch this amazing moment",
                                "Don't forget to like and follow",
                                "Thanks for watching!"
                            ).joinToString("\n")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, PINK_RED_ACCENT.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = PINK_RED_ACCENT.copy(alpha = 0.12f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Auto",
                            tint = PINK_RED_ACCENT,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Auto-Generate from Timeline Beats / Speech",
                            color = PINK_RED_ACCENT,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Generate Captions Primary Button
                    val phrases = scriptText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val canGenerate = phrases.isNotEmpty()

                    Button(
                        onClick = {
                            if (canGenerate) {
                                onGenerateCaptions(phrases)
                                onDismiss()
                            }
                        },
                        enabled = canGenerate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PINK_RED_ACCENT,
                            disabledContainerColor = Color(0xFF3A3A3C)
                        )
                    ) {
                        Text(
                            text = if (phrases.isEmpty()) "Generate Captions" else "Generate ${phrases.size} Captions",
                            color = if (canGenerate) Color.White else Color.White.copy(alpha = 0.4f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Interactive GPU Mosaic / Privacy Blur BottomSheet (Phase 9).
 * Features 4 style cards (Pixelate, Blur, Dot Matrix, Blackout) with #FF2D55 active border,
 * Strength slider (10%..100% -> mosaicIntensity), and Feather slider (0%..100% -> mosaicFeather)
 * with live 60fps preview updates and Cancel (✕) / Done (✓) controls.
 */
@Composable
fun MosaicSheet(
    initialStyle: Int = 0,
    initialIntensity: Float = 0.5f,
    initialFeather: Float = 0.05f,
    onLiveUpdate: (style: Int, intensity: Float, feather: Float) -> Unit,
    onCommit: (style: Int, intensity: Float, feather: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var style by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(initialStyle) }
    var intensity by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(initialIntensity) }
    var feather by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(initialFeather) }

    fun notifyChange(newStyle: Int, newIntensity: Float, newFeather: Float) {
        style = newStyle
        intensity = newIntensity
        feather = newFeather
        onLiveUpdate(newStyle, newIntensity, newFeather)
    }

    val styleCards = listOf(
        Triple(0, "Pixelate", Icons.Default.GridOn),
        Triple(1, "Blur", Icons.Default.BlurOn),
        Triple(2, "Dot Matrix", Icons.Default.Grain),
        Triple(3, "Blackout", Icons.Default.Shield)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                indication = null
            ) {
                onLiveUpdate(initialStyle, initialIntensity, initialFeather)
                onDismiss()
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    color = DARK_SHEET_BG,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                    indication = null
                ) { /* Consume */ }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp)
        ) {
            // Header: [✕]  MOSAIC / PRIVACY BLUR  [✓]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        onLiveUpdate(initialStyle, initialIntensity, initialFeather)
                        onDismiss()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.White
                    )
                }

                Text(
                    text = "MOSAIC / PRIVACY BLUR",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                IconButton(
                    onClick = {
                        onCommit(style, intensity, feather)
                        onDismiss()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = PINK_RED_ACCENT
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(DARK_SURFACE_BORDER))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // 4 Style Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    styleCards.forEach { (sId, sName, sIcon) ->
                        val isSelected = style == sId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(72.dp)
                                .background(DARK_CARD_BG, RoundedCornerShape(10.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    notifyChange(sId, intensity, feather)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = sIcon,
                                    contentDescription = sName,
                                    tint = if (isSelected) PINK_RED_ACCENT else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = sName,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Strength Slider (10%..100% -> mosaicIntensity)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Strength",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${(intensity * 100).roundToInt()}%",
                        color = PINK_RED_ACCENT,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Slider(
                    value = intensity,
                    onValueChange = { notifyChange(style, it, feather) },
                    valueRange = 0.10f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = PINK_RED_ACCENT,
                        activeTrackColor = PINK_RED_ACCENT,
                        inactiveTrackColor = DARK_SURFACE_BORDER
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Feather Slider (0%..100% -> mosaicFeather in 0.0f..0.3f)
                val featherDisplay = ((feather / 0.3f) * 100f).coerceIn(0f, 100f).roundToInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Feather",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$featherDisplay%",
                        color = PINK_RED_ACCENT,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Slider(
                    value = feather,
                    onValueChange = { notifyChange(style, intensity, it) },
                    valueRange = 0.0f..0.3f,
                    colors = SliderDefaults.colors(
                        thumbColor = PINK_RED_ACCENT,
                        activeTrackColor = PINK_RED_ACCENT,
                        inactiveTrackColor = DARK_SURFACE_BORDER
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Dedicated Aspect Ratio BottomSheet.
 * Displays all 13 supported aspect ratios (Original, 9:16, 16:9, 1:1, 4:5, 4:3, 3:4, 5:4, 3:2, 2:3, 2:1, 1:2, 21:9)
 * with visual proportional frame icons, #FF2D55 active highlight, live preview updates on card tap,
 * and Cancel (✕) / Done (✓) controls.
 */
@Composable
fun AspectRatioSheet(
    activeRatio: AspectRatioMode,
    onRatioChanged: (AspectRatioMode) -> Unit,
    onDone: (AspectRatioMode) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedRatio by androidx.compose.runtime.remember(activeRatio) { androidx.compose.runtime.mutableStateOf(activeRatio) }
    val ratios = AspectRatioMode.entries

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                indication = null
            ) {
                onCancel()
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    color = DARK_SHEET_BG,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                    indication = null
                ) { /* Consume */ }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
        ) {
            // Header: [✕]  ASPECT RATIO  [✓]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.White
                    )
                }

                Text(
                    text = "ASPECT RATIO",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                IconButton(
                    onClick = { onDone(selectedRatio) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = PINK_RED_ACCENT
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(DARK_SURFACE_BORDER))

            Spacer(modifier = Modifier.height(16.dp))

            // Presets row: 13 cards (Original through 21:9)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ratios.forEach { r ->
                    val isSelected = selectedRatio == r
                    Column(
                        modifier = Modifier
                            .width(76.dp)
                            .height(86.dp)
                            .background(DARK_CARD_BG, RoundedCornerShape(10.dp))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                selectedRatio = r
                                onRatioChanged(r)
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Visual proportional frame icon
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val frameAspect = when (r) {
                                AspectRatioMode.ORIGINAL -> 9f / 16f
                                else -> if (r.aspect > 0f) r.aspect else (r.ratioWidth.toFloat() / r.ratioHeight.toFloat())
                            }
                            val maxDim = 20.dp
                            val (mW, mH) = if (frameAspect >= 1f) {
                                maxDim to (maxDim / frameAspect).coerceAtLeast(6.dp)
                            } else {
                                (maxDim * frameAspect).coerceAtLeast(6.dp) to maxDim
                            }
                            Box(
                                modifier = Modifier
                                    .size(mW, mH)
                                    .border(
                                        width = 1.5.dp,
                                        color = if (isSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                                    .background(if (isSelected) PINK_RED_ACCENT.copy(alpha = 0.2f) else Color.Transparent)
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = r.label,
                                color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when (r) {
                                    AspectRatioMode.ORIGINAL -> "Source"
                                    AspectRatioMode.RATIO_9_16 -> "Reels/Shorts"
                                    AspectRatioMode.RATIO_16_9 -> "YouTube"
                                    AspectRatioMode.RATIO_1_1 -> "Square"
                                    AspectRatioMode.RATIO_4_5 -> "Portrait"
                                    AspectRatioMode.RATIO_4_3 -> "Classic"
                                    AspectRatioMode.RATIO_3_4 -> "Photo"
                                    AspectRatioMode.RATIO_5_4 -> "Monitor"
                                    AspectRatioMode.RATIO_3_2 -> "Standard"
                                    AspectRatioMode.RATIO_2_3 -> "Vertical"
                                    AspectRatioMode.RATIO_2_1 -> "Cinema"
                                    AspectRatioMode.RATIO_1_2 -> "Tall"
                                    AspectRatioMode.RATIO_21_9 -> "Ultrawide"
                                },
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 9.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Dedicated Aspect Ratio & Canvas Background BottomSheet (Phase 9).
 * - 6 Pro Aspect Ratios: 9:16, 16:9, 1:1, 4:5, 4:3, 21:9
 * - Canvas Background Fill for letterbox/pillarbox areas:
 *   1. Blur (4 levels)
 *   2. Color (Black, White, Dark Gray, Pastel/Vibrant solid colors)
 *   3. Gradient (6 smooth 2-color vertical gradients)
 * - "Apply to All" button
 * - Cancel (✕) / Done (✓)
 */
@Composable
fun CanvasRatioSheet(
    activeRatio: AspectRatioMode,
    activeBackground: ClipBackground,
    onRatioChanged: (AspectRatioMode) -> Unit,
    onBackgroundChanged: (ClipBackground) -> Unit,
    onApplyBackgroundToAll: (ClipBackground) -> Unit,
    onDone: ((AspectRatioMode, ClipBackground) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var selectedRatio by androidx.compose.runtime.remember(activeRatio) { androidx.compose.runtime.mutableStateOf(activeRatio) }
    var selectedBg by androidx.compose.runtime.remember(activeBackground) { androidx.compose.runtime.mutableStateOf(activeBackground) }
    var bgTab by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            if (activeBackground.gradientIndex in 0..5) {
                "Gradient"
            } else when (activeBackground.type) {
                BackgroundType.BLUR -> "Blur"
                BackgroundType.COLOR -> "Color"
                BackgroundType.NONE -> "Blur"
            }
        )
    }

    val ratios = AspectRatioMode.entries

    val solidColors = listOf(
        0xFF000000.toInt() to "Black",
        0xFFFFFFFF.toInt() to "White",
        0xFF24242A.toInt() to "Dark Gray",
        0xFFFF2D55.toInt() to "Pink Red",
        0xFF2196F3.toInt() to "Blue",
        0xFF9C27B0.toInt() to "Purple",
        0xFF009688.toInt() to "Teal",
        0xFFFF9800.toInt() to "Orange",
        0xFF4CAF50.toInt() to "Green"
    )

    val gradientPresets = listOf(
        Pair(Color(0xFF1E0D40), Color(0xFF8C1A73)), // 0: Deep Purple to Magenta
        Pair(Color(0xFF0D1A40), Color(0xFF0080A6)), // 1: Midnight Blue to Cyan
        Pair(Color(0xFFA6331A), Color(0xFF400D26)), // 2: Sunset to Deep Red
        Pair(Color(0xFF0D3326), Color(0xFF1A7366)), // 3: Emerald to Teal
        Pair(Color(0xFF1A1F47), Color(0xFF994059)), // 4: Twilight Navy to Rose
        Pair(Color(0xFF333338), Color(0xFF0A0A0D))  // 5: Charcoal to Pitch Black
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                indication = null
            ) {
                onCancel?.invoke() ?: onDismiss()
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    color = DARK_SHEET_BG,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                    indication = null
                ) { /* Consume */ }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp)
        ) {
            // Header: [✕]  ASPECT RATIO & CANVAS  [✓]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { onCancel?.invoke() ?: onDismiss() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                Text(
                    text = "CANVAS & ASPECT RATIO",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                IconButton(
                    onClick = { onDone?.invoke(selectedRatio, selectedBg) ?: onDismiss() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = PINK_RED_ACCENT
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(DARK_SURFACE_BORDER))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Section 1: Aspect Ratio Presets
                Text(
                    text = "Aspect Ratio",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ratios.forEach { r ->
                        val isSelected = selectedRatio == r
                        Column(
                            modifier = Modifier
                                .width(76.dp)
                                .height(86.dp)
                                .background(DARK_CARD_BG, RoundedCornerShape(10.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    selectedRatio = r
                                    onRatioChanged(r)
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val frameAspect = if (r.aspect > 0f) r.aspect else 9f / 16f
                                val maxDim = 20.dp
                                val (mW, mH) = if (frameAspect >= 1f) {
                                    maxDim to (maxDim / frameAspect).coerceAtLeast(6.dp)
                                } else {
                                    (maxDim * frameAspect).coerceAtLeast(6.dp) to maxDim
                                }
                                Box(
                                    modifier = Modifier
                                        .size(mW, mH)
                                        .border(
                                            width = 1.5.dp,
                                            color = if (isSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.7f),
                                            shape = RoundedCornerShape(2.dp)
                                        )
                                        .background(if (isSelected) PINK_RED_ACCENT.copy(alpha = 0.2f) else Color.Transparent)
                                )
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = r.label,
                                    color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when (r) {
                                        AspectRatioMode.ORIGINAL -> "Source"
                                        AspectRatioMode.RATIO_9_16 -> "Reels/Shorts"
                                        AspectRatioMode.RATIO_16_9 -> "YouTube"
                                        AspectRatioMode.RATIO_1_1 -> "Square"
                                        AspectRatioMode.RATIO_4_5 -> "Portrait"
                                        AspectRatioMode.RATIO_4_3 -> "Classic"
                                        AspectRatioMode.RATIO_3_4 -> "Photo"
                                        AspectRatioMode.RATIO_5_4 -> "Monitor"
                                        AspectRatioMode.RATIO_3_2 -> "Standard"
                                        AspectRatioMode.RATIO_2_3 -> "Vertical"
                                        AspectRatioMode.RATIO_2_1 -> "Cinema"
                                        AspectRatioMode.RATIO_1_2 -> "Tall"
                                        AspectRatioMode.RATIO_21_9 -> "Ultrawide"
                                    },
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 9.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Section 2: Canvas Background Fill (Letterbox / Pillarbox)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Canvas Background",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // 3 Tabs: Blur, Color, Gradient
                    Row(
                        modifier = Modifier
                            .background(DARK_CARD_BG, RoundedCornerShape(16.dp))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Blur", "Color", "Gradient").forEach { tab ->
                            val isTabSelected = bgTab == tab
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isTabSelected) PINK_RED_ACCENT else Color.Transparent,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { bgTab = tab }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = tab,
                                    color = if (isTabSelected) Color.White else Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp,
                                    fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                when (bgTab) {
                    "Blur" -> {
                        // 4 Blur Levels: 25%, 50%, 75%, 100%
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val blurLevels = listOf(0.25f, 0.50f, 0.75f, 1.0f)
                            blurLevels.forEachIndexed { idx, lvl ->
                                val isSelected = selectedBg.gradientIndex < 0 && selectedBg.type == BackgroundType.BLUR && kotlin.math.abs(selectedBg.blurLevel - lvl) < 0.05f
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(54.dp)
                                        .background(DARK_CARD_BG, RoundedCornerShape(10.dp))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            val newBg = ClipBackground(type = BackgroundType.BLUR, blurLevel = lvl, gradientIndex = -1)
                                            selectedBg = newBg
                                            onBackgroundChanged(newBg)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Level ${idx + 1}",
                                            color = if (isSelected) PINK_RED_ACCENT else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${(lvl * 100).toInt()}%",
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "Color" -> {
                        // Solid Colors
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            solidColors.forEach { (colorArgb, colorName) ->
                                val isSelected = selectedBg.gradientIndex < 0 && selectedBg.type == BackgroundType.COLOR && selectedBg.colorArgb == colorArgb
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        val newBg = ClipBackground(type = BackgroundType.COLOR, colorArgb = colorArgb, gradientIndex = -1)
                                        selectedBg = newBg
                                        onBackgroundChanged(newBg)
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(Color(colorArgb), CircleShape)
                                            .border(
                                                width = if (isSelected) 2.5.dp else 1.dp,
                                                color = if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER,
                                                shape = CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = colorName,
                                        color = if (isSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.7f),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                    "Gradient" -> {
                        // 6 Vertical Gradients
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            gradientPresets.forEachIndexed { gIdx, (topCol, botCol) ->
                                val isSelected = selectedBg.gradientIndex == gIdx
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        val newBg = ClipBackground(type = BackgroundType.COLOR, gradientIndex = gIdx)
                                        selectedBg = newBg
                                        onBackgroundChanged(newBg)
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 46.dp, height = 54.dp)
                                            .background(
                                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                                    listOf(topCol, botCol)
                                                ),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                width = if (isSelected) 2.5.dp else 1.dp,
                                                color = if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "G${gIdx + 1}",
                                        color = if (isSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Apply to All Button
                OutlinedButton(
                    onClick = {
                        onApplyBackgroundToAll(selectedBg)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, PINK_RED_ACCENT.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = PINK_RED_ACCENT.copy(alpha = 0.12f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Apply to All",
                        tint = PINK_RED_ACCENT,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Apply Canvas Background to All Clips",
                        color = PINK_RED_ACCENT,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Phase 10A: TransitionSheet
 * Interactive Clip-to-Clip transitions on Row C junctions.
 */
@Composable
fun TransitionSheet(
    activeJunctionIndex: Int,
    initialMode: Int = 0,
    initialDurationMs: Long = 600L,
    maxDurationMs: Long = 1000L,
    onTransitionChanged: (mode: Int, durationMs: Long) -> Unit,
    onApplyToAll: (mode: Int, durationMs: Long) -> Unit,
    onDone: (mode: Int, durationMs: Long) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by androidx.compose.runtime.remember(activeJunctionIndex) {
        androidx.compose.runtime.mutableIntStateOf(initialMode)
    }
    val defaultDurMs = (maxDurationMs / 2L).coerceAtLeast(100L)
    var currentDurationMs by androidx.compose.runtime.remember(activeJunctionIndex) {
        val startDur = if (initialMode <= 0 || initialDurationMs <= 0L) defaultDurMs else initialDurationMs.coerceIn(100L, maxDurationMs)
        androidx.compose.runtime.mutableLongStateOf(startDur)
    }

    val maxSec = (maxDurationMs / 1000f).coerceAtLeast(0.2f)
    val durSec = (currentDurationMs / 1000f).coerceIn(0.1f, maxSec)

    val transitionItems = listOf(
        Triple(0, "None", Icons.Default.Block),
        Triple(1, "Dissolve", Icons.Default.Grain),
        Triple(2, "Black", Icons.Default.Circle),
        Triple(3, "White", Icons.Default.FlashOn),
        Triple(4, "Slide L", Icons.Default.ViewAgenda),
        Triple(5, "Slide R", Icons.Default.Splitscreen),
        Triple(6, "Zoom In", Icons.Default.Add),
        Triple(7, "Zoom Out", Icons.Default.Remove),
        Triple(8, "Wipe", Icons.Default.CropSquare)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                indication = null
            ) {
                onCancel()
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    color = DARK_SHEET_BG,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                    indication = null
                ) { /* Consume */ }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp)
        ) {
            // Header: [✕]  TRANSITION  [✓]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { onCancel() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                Text(
                    text = "TRANSITION",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                IconButton(
                    onClick = { onDone(selectedMode, currentDurationMs) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = PINK_RED_ACCENT
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(DARK_SURFACE_BORDER))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Top Row: Duration Slider & Apply to All
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Duration: ${String.format(java.util.Locale.US, "%.1fs", durSec)} / ${String.format(java.util.Locale.US, "%.1fs", maxSec)} max",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedButton(
                        onClick = { onApplyToAll(selectedMode, currentDurationMs) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        border = BorderStroke(1.dp, PINK_RED_ACCENT.copy(alpha = 0.8f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = PINK_RED_ACCENT.copy(alpha = 0.12f)
                        ),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = "Apply to All",
                            color = PINK_RED_ACCENT,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Slider(
                    value = durSec,
                    onValueChange = { newSec ->
                        val newMs = (newSec * 1000f).toLong().coerceIn(100L, maxDurationMs)
                        currentDurationMs = newMs
                    },
                    valueRange = 0.1f..maxSec,
                    enabled = selectedMode > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = PINK_RED_ACCENT,
                        activeTrackColor = PINK_RED_ACCENT,
                        inactiveTrackColor = DARK_SURFACE_BORDER
                    ),
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 9 Transition Cards Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    transitionItems.forEach { (mode, name, icon) ->
                        val isSelected = selectedMode == mode
                        Column(
                            modifier = Modifier
                                .width(74.dp)
                                .height(82.dp)
                                .background(DARK_CARD_BG, RoundedCornerShape(10.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    val prevMode = selectedMode
                                    selectedMode = mode
                                    if (mode > 0 && prevMode == 0) {
                                        currentDurationMs = defaultDurMs
                                    }
                                    onTransitionChanged(mode, currentDurationMs)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        if (isSelected) PINK_RED_ACCENT.copy(alpha = 0.18f) else Color.Transparent,
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = name,
                                    tint = if (isSelected) PINK_RED_ACCENT else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Text(
                                text = name,
                                color = if (isSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
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

/**
 * Phase 10A: ClipAdjustSheet
 * Main Video Clip Pro Color Adjustments (Brightness, Contrast, Saturation, Warmth, Vignette)
 */
@Composable
fun ClipAdjustSheet(
    initialAdjust: ClipColorAdjust,
    onAdjustChanged: (ClipColorAdjust) -> Unit,
    onApplyToAll: (ClipColorAdjust) -> Unit,
    onReset: () -> Unit,
    onDone: (ClipColorAdjust) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var adjust by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(initialAdjust)
    }

    // 5 Selectable Parameter Tabs
    var selectedParamIndex by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }

    val params = listOf(
        Triple(0, "Brightness", Icons.Default.BrightnessMedium),
        Triple(1, "Contrast", Icons.Default.Contrast),
        Triple(2, "Saturation", Icons.Default.ColorLens),
        Triple(3, "Warmth", Icons.Default.Thermostat),
        Triple(4, "Vignette", Icons.Default.PanoramaFishEye)
    )

    // Current parameter percentage for slider
    val currentPercent: Float = when (selectedParamIndex) {
        0 -> (adjust.brightness / 0.5f) * 50f
        1 -> (adjust.contrast / 0.5f) * 50f
        2 -> (adjust.saturation / 0.5f) * 50f
        3 -> (adjust.warmth / 0.5f) * 50f
        4 -> adjust.vignette * 100f
        else -> 0f
    }

    val paramRange = if (selectedParamIndex == 4) 0f..100f else -50f..50f
    val paramName = params.getOrNull(selectedParamIndex)?.second ?: "Adjust"
    val displayValue = if (selectedParamIndex == 4) {
        "${currentPercent.toInt()}%"
    } else {
        val v = currentPercent.toInt()
        if (v > 0) "+$v%" else "$v%"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                indication = null
            ) {
                onCancel()
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    color = DARK_SHEET_BG,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                    indication = null
                ) { /* Consume */ }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp)
        ) {
            // Header: [✕]  PRO COLOR ADJUST  [✓]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { onCancel() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                Text(
                    text = "ADJUST",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                IconButton(
                    onClick = { onDone(adjust) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = PINK_RED_ACCENT
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(DARK_SURFACE_BORDER))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Parameter Name and Live Value
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = paramName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = displayValue,
                        color = PINK_RED_ACCENT,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Smooth Pink Slider
                Slider(
                    value = currentPercent,
                    onValueChange = { newVal ->
                        val updated = when (selectedParamIndex) {
                            0 -> adjust.copy(brightness = (newVal / 50f) * 0.5f)
                            1 -> adjust.copy(contrast = (newVal / 50f) * 0.5f)
                            2 -> adjust.copy(saturation = (newVal / 50f) * 0.5f)
                            3 -> adjust.copy(warmth = (newVal / 50f) * 0.5f)
                            4 -> adjust.copy(vignette = (newVal / 100f).coerceIn(0f, 1f))
                            else -> adjust
                        }
                        adjust = updated
                        onAdjustChanged(updated)
                    },
                    valueRange = paramRange,
                    colors = SliderDefaults.colors(
                        thumbColor = PINK_RED_ACCENT,
                        activeTrackColor = PINK_RED_ACCENT,
                        inactiveTrackColor = DARK_SURFACE_BORDER
                    ),
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 5 Selectable Parameter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    params.forEach { (idx, name, icon) ->
                        val isSelected = selectedParamIndex == idx
                        Column(
                            modifier = Modifier
                                .width(70.dp)
                                .height(68.dp)
                                .background(DARK_CARD_BG, RoundedCornerShape(10.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) PINK_RED_ACCENT else DARK_SURFACE_BORDER,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    selectedParamIndex = idx
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = name,
                                tint = if (isSelected) PINK_RED_ACCENT else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = name,
                                color = if (isSelected) PINK_RED_ACCENT else Color.White.copy(alpha = 0.75f),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Bottom Buttons: Reset & Apply to All
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val resetAdjust = when (selectedParamIndex) {
                                0 -> adjust.copy(brightness = 0f)
                                1 -> adjust.copy(contrast = 0f)
                                2 -> adjust.copy(saturation = 0f)
                                3 -> adjust.copy(warmth = 0f)
                                4 -> adjust.copy(vignette = 0f)
                                else -> ClipColorAdjust.DEFAULT
                            }
                            adjust = resetAdjust
                            onAdjustChanged(resetAdjust)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, DARK_SURFACE_BORDER),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = DARK_CARD_BG
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Reset",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            onApplyToAll(adjust)
                        },
                        modifier = Modifier
                            .weight(1.3f)
                            .height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PINK_RED_ACCENT
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Apply to All",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Apply to All Clips",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Phase 10B: Step 1 - ExportConfigSheet BottomSheet on EditorScreen
 * Quality (Resolution), Frame Rate (FPS), Bitrate (Code Rate), and Live Approximate File Size Banner
 * with 3 smooth #FF2D55 interactive sliders matching VideoGuru / CapCut export sheets.
 */
@Composable
fun ExportConfigSheet(
    initialResolution: Int = 1080,
    initialFps: Int = 30,
    initialBitrateBps: Int? = null,
    totalDurationMs: Long = 0L,
    onStartExport: (resolution: Int, fps: Int, bitrateBps: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val capabilities = remember { ExportCapabilities() }
    val sessionGuard = remember(context) { SessionGuard(context) }

    val resolutionOptions = remember {
        listOf(
            480 to "480p",
            720 to "720p (HD)",
            1080 to "1080p (FHD)",
            1440 to "1440p (2K)",
            2160 to "2160p (4K)"
        )
    }
    val initialResIndex = remember(initialResolution) {
        val idx = resolutionOptions.indexOfFirst { it.first == initialResolution }
        if (idx >= 0) idx else 2 // default 1080p
    }
    var resolutionIndex by remember { mutableFloatStateOf(initialResIndex.toFloat()) }
    val currentResPair = resolutionOptions[resolutionIndex.roundToInt().coerceIn(0, 4)]
    val currentResolution = currentResPair.first
    val currentResolutionLabel = currentResPair.second

    val fpsOptions = remember { listOf(24, 25, 30, 50, 60) }
    val initialFpsIndex = remember(initialFps) {
        val idx = fpsOptions.indexOf(initialFps)
        if (idx >= 0) idx else 2 // default 30 FPS
    }
    var fpsIndex by remember { mutableFloatStateOf(initialFpsIndex.toFloat()) }
    val currentFps = fpsOptions[fpsIndex.roundToInt().coerceIn(0, 4)]

    val qualityOptions = remember {
        listOf(
            Quality.LOW to "Low",
            Quality.MEDIUM to "Medium",
            Quality.HIGH to "High",
            Quality.ULTRA to "Ultra",
            Quality.EXTREME to "Extreme"
        )
    }
    val initialQualityIndex = remember(initialBitrateBps, initialResolution, initialFps) {
        if (initialBitrateBps != null && initialBitrateBps > 0) {
            val diffs = qualityOptions.mapIndexed { idx, q ->
                val spec = OutputSpec(Aspect.R9_16, initialResolution, initialFps, q.first, OutCodec.AVC)
                val s = spec.size()
                val bps = BitrateTable.videoBps(s.w, s.h, spec.fps, spec.quality, spec.codec)
                idx to kotlin.math.abs(bps - initialBitrateBps)
            }
            diffs.minByOrNull { it.second }?.first ?: 2
        } else {
            2 // default High
        }
    }
    var qualityIndex by remember { mutableFloatStateOf(initialQualityIndex.toFloat()) }
    val currentQualityPair = qualityOptions[qualityIndex.roundToInt().coerceIn(0, 4)]
    val currentQuality = currentQualityPair.first
    val currentQualityLabel = currentQualityPair.second

    val candidateSpec = remember(currentResolution, currentFps, currentQuality) {
        OutputSpec(Aspect.R9_16, currentResolution, currentFps, currentQuality, OutCodec.AVC)
    }
    val targetBitrateBps = remember(candidateSpec) {
        val s = candidateSpec.size()
        BitrateTable.videoBps(s.w, s.h, candidateSpec.fps, candidateSpec.quality, candidateSpec.codec)
    }
    val bitrateMbps = targetBitrateBps / 1_000_000f

    val isCrashExcluded = remember(candidateSpec) {
        sessionGuard.isExcluded(candidateSpec.key())
    }
    val choice = remember(candidateSpec) {
        capabilities.find(candidateSpec, allowSoftware = true)
    }
    val isSupported = choice != null && !isCrashExcluded
    val unsupportedReason = when {
        isCrashExcluded -> "Excluded due to crash"
        choice == null -> if (ExportCapabilities.isHardwareRequired(candidateSpec)) "Hardware unsupported" else "Encoder unsupported"
        else -> null
    }

    val estimatedSizeBytes = remember(targetBitrateBps, totalDurationMs) {
        ExportEstimator.sizeBytes(targetBitrateBps, totalDurationMs)
    }
    val approxSizeMb = estimatedSizeBytes.toDouble() / (1024.0 * 1024.0)
    val formattedDuration = remember(totalDurationMs) {
        String.format(Locale.US, "%02d:%02d", totalDurationMs / 60000L, (totalDurationMs % 60000L) / 1000L)
    }

    val measuredFps = remember(candidateSpec, choice) {
        ExportBenchmark.getCachedFps(candidateSpec)
            ?: ExportBenchmark.fallbackFps(candidateSpec, choice?.hardware ?: true)
    }
    val totalFrames = remember(totalDurationMs, currentFps) {
        ExportEstimator.totalFrames(totalDurationMs, currentFps)
    }
    val etaMs = remember(totalFrames, measuredFps) {
        ExportEstimator.etaMs(totalFrames, measuredFps)
    }
    val formattedEta = remember(etaMs) {
        if (etaMs > 0L) {
            val etaSec = (etaMs + 999L) / 1000L
            if (etaSec >= 60L) "${etaSec / 60L}m ${etaSec % 60L}s" else "${etaSec}s"
        } else {
            "--"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF14151C), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .border(
                BorderStroke(1.dp, Color(0xFF262833)),
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Top Header: Cancel (✕), Title ("Export Settings"), and Done / "Export" button in #FF2D55
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = "Export Settings",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = {
                        if (isSupported) {
                            onStartExport(currentResolution, currentFps, targetBitrateBps)
                        }
                    },
                    enabled = isSupported,
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(17.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PINK_RED_ACCENT,
                        disabledContainerColor = PINK_RED_ACCENT.copy(alpha = 0.38f),
                        disabledContentColor = Color.White.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Export",
                        tint = if (isSupported) Color.White else Color.White.copy(alpha = 0.38f),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Export",
                        color = if (isSupported) Color.White else Color.White.copy(alpha = 0.38f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Slider 1 — Resolution (Quality)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Resolution",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = currentResolutionLabel,
                        color = PINK_RED_ACCENT,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = resolutionIndex,
                    onValueChange = { newVal ->
                        resolutionIndex = newVal
                    },
                    valueRange = 0f..4f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = PINK_RED_ACCENT,
                        activeTrackColor = PINK_RED_ACCENT,
                        inactiveTrackColor = Color(0xFF262833)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    resolutionOptions.forEachIndexed { idx, pair ->
                        val isSelected = idx == resolutionIndex.roundToInt().coerceIn(0, 4)
                        val testSpec = OutputSpec(Aspect.R9_16, pair.first, currentFps, currentQuality, OutCodec.AVC)
                        val isOptExcluded = sessionGuard.isExcluded(testSpec.key())
                        val optChoice = capabilities.find(testSpec, allowSoftware = true)
                        val isOptSupported = optChoice != null && !isOptExcluded

                        Text(
                            text = pair.second,
                            color = when {
                                isSelected -> Color.White
                                !isOptSupported -> Color.White.copy(alpha = 0.38f)
                                else -> Color.White.copy(alpha = 0.6f)
                            },
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Slider 2 — Frame Rate (FPS)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Frame Rate",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${currentFps} FPS",
                        color = PINK_RED_ACCENT,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = fpsIndex,
                    onValueChange = { newVal ->
                        fpsIndex = newVal
                    },
                    valueRange = 0f..4f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = PINK_RED_ACCENT,
                        activeTrackColor = PINK_RED_ACCENT,
                        inactiveTrackColor = Color(0xFF262833)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    fpsOptions.forEachIndexed { idx, fpsVal ->
                        val isSelected = idx == fpsIndex.roundToInt().coerceIn(0, 4)
                        val testSpec = OutputSpec(Aspect.R9_16, currentResolution, fpsVal, currentQuality, OutCodec.AVC)
                        val isOptExcluded = sessionGuard.isExcluded(testSpec.key())
                        val optChoice = capabilities.find(testSpec, allowSoftware = true)
                        val isOptSupported = optChoice != null && !isOptExcluded

                        Text(
                            text = "$fpsVal",
                            color = when {
                                isSelected -> Color.White
                                !isOptSupported -> Color.White.copy(alpha = 0.38f)
                                else -> Color.White.copy(alpha = 0.6f)
                            },
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Slider 3 — Quality / Bitrate
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quality",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$currentQualityLabel • ${String.format(Locale.US, "%.1f", bitrateMbps)} Mbps",
                        color = PINK_RED_ACCENT,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = qualityIndex,
                    onValueChange = { newVal ->
                        qualityIndex = newVal
                    },
                    valueRange = 0f..4f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = PINK_RED_ACCENT,
                        activeTrackColor = PINK_RED_ACCENT,
                        inactiveTrackColor = Color(0xFF262833)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    qualityOptions.forEachIndexed { idx, qPair ->
                        val isSelected = idx == qualityIndex.roundToInt().coerceIn(0, 4)
                        val testSpec = OutputSpec(Aspect.R9_16, currentResolution, currentFps, qPair.first, OutCodec.AVC)
                        val isOptExcluded = sessionGuard.isExcluded(testSpec.key())
                        val optChoice = capabilities.find(testSpec, allowSoftware = true)
                        val isOptSupported = optChoice != null && !isOptExcluded

                        Text(
                            text = qPair.second,
                            color = when {
                                isSelected -> Color.White
                                !isOptSupported -> Color.White.copy(alpha = 0.38f)
                                else -> Color.White.copy(alpha = 0.6f)
                            },
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Unsupported or Crash-Excluded Warning Banner
            if (!isSupported && unsupportedReason != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33FF453A), RoundedCornerShape(10.dp))
                        .border(BorderStroke(1.dp, Color(0x66FF453A)), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = Color(0xFFFF453A),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "$unsupportedReason for ${currentResolution}p @ ${currentFps}fps",
                            color = Color(0xFFFF8B8B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Live Approximate File Size and ETA Banner below sliders
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1D27), RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.dp, Color(0xFF2B2E3D)), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(PINK_RED_ACCENT.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "File Size Info",
                            tint = PINK_RED_ACCENT,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Estimated Size: ~${String.format(Locale.US, "%.1f", approxSizeMb)} MB  •  Duration: $formattedDuration  •  Est. Time: $formattedEta",
                        color = Color.White.copy(alpha = 0.95f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Phase 10B: Step 2 - Dedicated Full-Screen ExportScreen (AppScreen.EXPORT)
 * Studio Dark Theme (#0E0F13), Center Visual Card with Cover Thumbnail & Animated Progress Ring,
 * Dynamic State Handling (Exporting, Done, Failed), Lifecycle ON_STOP auto-cancel, and BackHandler.
 */
@Composable
fun ExportScreen(
    uiState: EditorUiState,
    aspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_9_16,
    onBackClick: () -> Unit,
    onCancelExport: (reason: String) -> Unit,
    onRetryExport: () -> Unit,
    onShareExport: () -> Unit
) {
    var showCancelConfirmDialog by remember { mutableStateOf(false) }

    fun handleBackOrCancel() {
        if (uiState.exportState is ExportState.Exporting) {
            showCancelConfirmDialog = true
        } else {
            onBackClick()
        }
    }


    BackHandler(enabled = true) {
        handleBackOrCancel()
    }

    // Cover Thumbnail from first video clip
    var coverBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uiState.project.clips) {
        val firstClipPath = uiState.project.clips.firstOrNull()?.path
        if (firstClipPath != null) {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(firstClipPath)
                    val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(540, 960), null)
                    } else {
                        @Suppress("DEPRECATION")
                        android.media.ThumbnailUtils.createVideoThumbnail(firstClipPath, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                    }
                    coverBitmap = bmp
                } catch (_: Exception) {}
            }
        }
    }

    val state = uiState.exportState
    val progress = if (state is ExportState.Exporting) state.progress.coerceIn(0f, 1f) else 0f
    val titleText = when (state) {
        is ExportState.Exporting -> "Exporting Video"
        is ExportState.Done -> "Export Complete"
        is ExportState.Failed -> if (uiState.exportErrorMessage?.contains("background", ignoreCase = true) == true) "Export Cancelled" else "Export Failed"
        else -> "Export"
    }

    val targetBitrate = if (uiState.exportBitrateBps > 0) {
        uiState.exportBitrateBps
    } else {
        EditorViewModel.calculateTargetBitrateBps(
            uiState.exportResolution,
            uiState.exportFps,
            uiState.exportBitratePreset
        )
    }
    val bitrateMbps = targetBitrate / 1_000_000f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0F13))
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { handleBackOrCancel() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = titleText,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 2. Center Visual Section: Thumbnail Card + Progress Indicator + Settings Badge
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                val frameAspect = if (aspectRatioMode.aspect > 0f) aspectRatioMode.aspect else (9f / 16f)
                val cardHeight = 260.dp
                val cardWidth = (cardHeight * frameAspect).coerceIn(150.dp, 300.dp)

                Box(
                    modifier = Modifier
                        .size(width = cardWidth, height = cardHeight)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF161822))
                        .border(
                            BorderStroke(1.5.dp, if (state is ExportState.Exporting) PINK_RED_ACCENT else Color(0xFF262833)),
                            RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap!!.asImageBitmap(),
                            contentDescription = "Project Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Overlay based on state
                    when (state) {
                        is ExportState.Exporting -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.size(88.dp),
                                    color = PINK_RED_ACCENT,
                                    trackColor = PINK_RED_ACCENT.copy(alpha = 0.25f),
                                    strokeWidth = 6.dp
                                )
                                Text(
                                    text = "${(progress * 100f).toInt()}%",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        is ExportState.Done -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .background(Color(0xFF34C759).copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Export Complete",
                                        tint = Color(0xFF34C759),
                                        modifier = Modifier.size(52.dp)
                                    )
                                }
                            }
                        }
                        is ExportState.Failed -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.60f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .background(Color(0xFFFF453A).copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Export Failed",
                                        tint = Color(0xFFFF453A),
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            }
                        }
                        else -> {}
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Settings badge
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1B1D27), RoundedCornerShape(14.dp))
                        .border(BorderStroke(1.dp, Color(0xFF2B2E3D)), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${uiState.exportResolution}p  •  ${uiState.exportFps} FPS  •  ${String.format(Locale.US, "%.1f", bitrateMbps)} Mbps",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                val fallbackNotice = uiState.exportFallbackNotice ?: ExportCoordinator.fallbackNotice
                if (fallbackNotice != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0x33FFB300), RoundedCornerShape(8.dp))
                            .border(BorderStroke(1.dp, Color(0x66FFB300)), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = fallbackNotice,
                            color = Color(0xFFFFD54F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 3. Bottom Action & Status Panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (state) {
                    is ExportState.Exporting -> {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = PINK_RED_ACCENT,
                            trackColor = Color(0xFF262833)
                        )

                        Text(
                            text = "Please don't lock the screen or switch apps",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedButton(
                            onClick = { handleBackOrCancel() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF383C4D))
                        ) {
                            Text(
                                text = "Cancel Export",
                                color = Color(0xFFFF453A),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    is ExportState.Done -> {
                        Text(
                            text = "Video Saved to Gallery!",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Saved to Movies/FlowCut",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = onShareExport,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PINK_RED_ACCENT
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Share Video",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF383C4D))
                        ) {
                            Text(
                                text = "Back to Editor",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    is ExportState.Failed -> {
                        Text(
                            text = if (uiState.exportErrorMessage?.contains("background", ignoreCase = true) == true) "Export Cancelled" else "Export Failed",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = uiState.exportErrorMessage ?: "Export was stopped. Try again.",
                            color = Color(0xFFFF6961),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = onRetryExport,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PINK_RED_ACCENT
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Retry Export",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF383C4D))
                        ) {
                            Text(
                                text = "Back to Editor",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    else -> {
                        OutlinedButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF383C4D))
                        ) {
                            Text(
                                text = "Back to Editor",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        if (showCancelConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showCancelConfirmDialog = false },
                title = {
                    Text(
                        text = "Cancel Export?",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Your video is still exporting. Do you want to stop exporting and return to the editor?",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp
                    )
                },
                containerColor = Color(0xFF1C1E28),
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCancelConfirmDialog = false
                            onCancelExport("Export cancelled by user")
                            onBackClick()
                        }
                    ) {
                        Text(
                            text = "Cancel Export",
                            color = PINK_RED_ACCENT,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCancelConfirmDialog = false
                        }
                    ) {
                        Text(
                            text = "Continue",
                            color = Color.White.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            )
        }
    }
}

