package com.flowcut.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.editor.EditorUiState
import com.flowcut.app.editor.EditorViewModel
import com.flowcut.app.engine.AspectRatioMode
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
import com.flowcut.app.shared.ExportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ExportConfigSheet(
    exportAspect: Aspect = Aspect.R9_16,
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
    val initialQualityIndex = remember(exportAspect, initialBitrateBps, initialResolution, initialFps) {
        if (initialBitrateBps != null && initialBitrateBps > 0) {
            val diffs = qualityOptions.mapIndexed { idx, q ->
                val spec = OutputSpec(exportAspect, initialResolution, initialFps, q.first, OutCodec.AVC)
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

    val candidateSpec = remember(exportAspect, currentResolution, currentFps, currentQuality) {
        OutputSpec(exportAspect, currentResolution, currentFps, currentQuality, OutCodec.AVC)
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
                        val testSpec = OutputSpec(exportAspect, pair.first, currentFps, currentQuality, OutCodec.AVC)
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
                        val testSpec = OutputSpec(exportAspect, currentResolution, fpsVal, currentQuality, OutCodec.AVC)
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
                        val testSpec = OutputSpec(exportAspect, currentResolution, currentFps, qPair.first, OutCodec.AVC)
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
 * Dedicated Full-Screen ExportScreen (AppScreen.EXPORT)
 * Studio Dark Theme (#0E0F13), Center Visual Card with Cover Thumbnail & Animated Progress Ring,
 * Dynamic State Handling (Exporting, Done, Failed), and BackHandler.
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
