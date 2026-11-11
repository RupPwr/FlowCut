package com.flowcut.app.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.core.edit.SetTimeMapCommand
import com.flowcut.app.core.feature.LanePainter
import com.flowcut.app.core.feature.PainterCtx
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.model.map
import com.flowcut.app.core.playback.PlayMode
import com.flowcut.app.core.playback.PlaybackController
import com.flowcut.app.core.playback.PlayheadReader
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * LRU cache for formatted ruler time strings to ensure zero allocations inside onDraw (Rule R10).
 */
private class TimeStringCache(private val maxEntries: Int = 200) {
    private val map = LinkedHashMap<Long, String>(maxEntries, 0.75f, true)

    fun get(second: Long): String {
        val cached = map[second]
        if (cached != null) return cached
        val m = second / 60
        val s = second % 60
        val str = "%d:%02d".format(m, s)
        if (map.size >= maxEntries) {
            val oldest = map.keys.firstOrNull()
            if (oldest != null) map.remove(oldest)
        }
        map[second] = str
        return str
    }
}

/**
 * Pre-allocated second offset cache ("+1s", "+2s"...) for zero allocations inside onDraw (Rule R10).
 */
private class SecondOffsetCache(private val maxSec: Int = 600) {
    private val array = Array(maxSec + 1) { "+${it}s" }
    fun get(sec: Long): String {
        val s = sec.toInt()
        return if (s in 0..maxSec) array[s] else "+${sec}s"
    }
}

/**
 * Pre-allocated clip title cache ("Clip 1", "Clip 2"...) for zero allocations inside onDraw (Rule R10).
 */
private class ClipTitleCache(private val maxClips: Int = 100) {
    private val array = Array(maxClips) { "Clip ${it + 1}" }
    fun get(index: Int): String {
        return if (index in 0 until maxClips) array[index] else "Clip ${index + 1}"
    }
}

/**
 * Pre-allocated duration badge cache ("0:04.0", "0:22.1"...) for zero allocations inside onDraw (Rule R10).
 */
private class DurationBadgeCache(private val maxEntries: Int = 300) {
    private val map = LinkedHashMap<Long, String>(maxEntries, 0.75f, true)

    fun get(durationUs: Long): String {
        val tenths = (durationUs / 100_000L).coerceAtLeast(0L)
        val cached = map[tenths]
        if (cached != null) return cached
        val totalSec = tenths / 10L
        val d = tenths % 10L
        val m = totalSec / 60L
        val s = totalSec % 60L
        val str = "%d:%02d.%d".format(m, s, d)
        if (map.size >= maxEntries) {
            val oldest = map.keys.firstOrNull()
            if (oldest != null) map.remove(oldest)
        }
        map[tenths] = str
        return str
    }
}

/**
 * Constructs a rounded/flat rectangle path in-place for contiguous clip rendering (Section 8 & Rule R10).
 */
private fun buildClipCornerPath(
    path: Path,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    roundLeft: Boolean,
    roundRight: Boolean,
    radius: Float
) {
    path.reset()
    val width = right - left
    if (width <= 0f) return
    val r = if (radius * 2f > width) width / 2f else radius
    val startX = if (roundLeft) left + r else left
    path.moveTo(startX, top)
    val endTopX = if (roundRight) right - r else right
    path.lineTo(endTopX, top)
    if (roundRight) {
        path.quadraticTo(right, top, right, top + r)
    }
    val endRightY = if (roundRight) bottom - r else bottom
    path.lineTo(right, endRightY)
    if (roundRight) {
        path.quadraticTo(right, bottom, right - r, bottom)
    }
    val endBottomX = if (roundLeft) left + r else left
    path.lineTo(endBottomX, bottom)
    if (roundLeft) {
        path.quadraticTo(left, bottom, left, bottom - r)
    }
    val endLeftY = if (roundLeft) top + r else top
    path.lineTo(left, endLeftY)
    if (roundLeft) {
        path.quadraticTo(left, top, left + r, top)
    }
    path.close()
}

/**
 * Builds a pentagonal/shield pin marker in-place pointing downward (Rule R10).
 */
private fun buildPinPath(
    path: Path,
    anchorX: Float,
    top: Float,
    width: Float,
    height: Float,
    cornerRadius: Float
) {
    path.reset()
    val halfW = width / 2f
    val left = anchorX - halfW
    val right = anchorX + halfW
    val bodyBottom = top + height * 0.68f
    val tipY = top + height

    path.moveTo(left + cornerRadius, top)
    path.lineTo(right - cornerRadius, top)
    path.quadraticTo(right, top, right, top + cornerRadius)
    path.lineTo(right, bodyBottom)
    path.lineTo(anchorX, tipY) // downward pointer tip
    path.lineTo(left, bodyBottom)
    path.lineTo(left, top + cornerRadius)
    path.quadraticTo(left, top, left + cornerRadius, top)
    path.close()
}

/**
 * In-memory CPU thumbnail cache for 64x64 PIP overlays (zero allocations in onDraw, Rule R10).
 */
private object PipThumbnailCache {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(32) {}

    fun get(path: String): ImageBitmap? {
        if (path.isEmpty()) return null
        val cached = cache.get(path)
        if (cached != null) return cached
        try {
            val file = java.io.File(path)
            if (!file.exists()) return null
            val isVideo = path.endsWith(".mp4", ignoreCase = true) ||
                    path.endsWith(".mkv", ignoreCase = true) ||
                    path.endsWith(".mov", ignoreCase = true) ||
                    path.contains("overlay_vid_")
            val bmp: android.graphics.Bitmap? = if (isVideo) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        android.media.ThumbnailUtils.createVideoThumbnail(
                            file,
                            android.util.Size(64, 64),
                            null
                        )
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    @Suppress("DEPRECATION")
                    android.media.ThumbnailUtils.createVideoThumbnail(
                        path,
                        android.provider.MediaStore.Video.Thumbnails.MICRO_KIND
                    )
                }
            } else {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(path, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= 64 && opts.outHeight / (sample * 2) >= 64) {
                    sample *= 2
                }
                opts.inJustDecodeBounds = false
                opts.inSampleSize = sample
                android.graphics.BitmapFactory.decodeFile(path, opts)
            }
            if (bmp != null) {
                val imageBmp = bmp.asImageBitmap()
                cache.put(path, imageBmp)
                return imageBmp
            }
        } catch (_: Exception) {
            // Ignore decoding failure
        }
        return null
    }
}

/**
 * Safely renders text on Canvas with softWrap = false and maxLines = 1 to prevent right-edge wrapping (Bug 5).
 */
private fun DrawScope.drawTextSafe(
    textMeasurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle
) {
    if (size.width <= 4f || size.height <= 4f) return
    if (x >= size.width - 4f || y >= size.height - 4f) return
    if (x < -200f || y < -50f) return

    val textLayoutResult = textMeasurer.measure(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = 1000),
        softWrap = false,
        maxLines = 1
    )
    if (x < 0f || y < 0f) {
        val shiftX = if (x < 0f) x else 0f
        val shiftY = if (y < 0f) y else 0f
        val posX = if (x < 0f) 0f else x
        val posY = if (y < 0f) 0f else y
        if (posX >= size.width - 4f || posY >= size.height - 4f) return
        translate(left = shiftX, top = shiftY) {
            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(posX, posY)
            )
        }
    } else {
        drawText(
            textLayoutResult = textLayoutResult,
            topLeft = Offset(x, y)
        )
    }
}

/**
 * TimelineCanvas Composable — VideoGuru / CapCut Reference Architecture:
 * - Row A: Top Pins Row for Text & Stickers
 * - Row B: Dynamic Selected Text / Sticker Bar
 * - Row C: Main Video Track with Left Cover Box & Right Pink "+" Button
 * - Row D: 4-Lane PIP Area (Thin Lines in S0/S3/S4 vs Expanded 4 Thumbnail Lanes in S1/S2)
 * - Row E: Sound collection bar
 *
 * Strictly Enforces Rule R10: Zero heap allocations inside onDraw.
 */
@Composable
fun TimelineCanvas(
    holder: SnapshotHolder,
    playhead: PlayheadReader,
    controller: PlaybackController,
    zoomState: TimelineZoomState,
    frameTick: MutableLongState,
    selection: TimelineSelection = TimelineSelection.None,
    focusMode: TimelineFocusState = TimelineFocusState.S0_NONE,
    overlayItems: List<OverlayItem> = emptyList(),
    lanePainters: Map<String, LanePainter> = emptyMap(),
    onSelect: (TimelineSelection) -> Unit = {},
    onLongPress: (TimelineSelection) -> Unit = {},
    onWakeFrameDriver: () -> Unit = {},
    onCommitTrim: (clipIndex: Int, newInUs: Long, newOutUs: Long, newDurationMs: Long) -> Unit = { _, _, _, _ -> },
    onCommitOverlayTrim: (itemId: String, newStartUs: Long, newEndUs: Long) -> Unit = { _, _, _ -> },
    onLiveOverlayTrim: (itemId: String, newStartUs: Long, newEndUs: Long) -> Unit = { _, _, _ -> },
    onTrimPreview: (trimPreviewTimeUs: Long) -> Unit = {},
    getClipNaturalDurationUs: (clipIndex: Int) -> Long = { index ->
        val c = holder.current.project.clips.getOrNull(index)
        c?.timeMap?.outUs ?: c?.durationMs?.let { msToUs(it) } ?: 0L
    },
    isMainTrackMuted: Boolean = false,
    onToggleTrackMute: () -> Unit = {},
    onToast: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer(cacheSize = 200)
    val coroutineScope = rememberCoroutineScope()

    val currentSelection by rememberUpdatedState(selection)
    val currentFocusMode by rememberUpdatedState(focusMode)
    val currentOverlayItems by rememberUpdatedState(overlayItems)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnCommitTrim by rememberUpdatedState(onCommitTrim)
    val currentOnCommitOverlayTrim by rememberUpdatedState(onCommitOverlayTrim)
    val currentOnLiveOverlayTrim by rememberUpdatedState(onLiveOverlayTrim)
    val currentOnTrimPreview by rememberUpdatedState(onTrimPreview)
    val currentGetClipNaturalDurationUs by rememberUpdatedState(getClipNaturalDurationUs)
    val currentOnWakeFrameDriver by rememberUpdatedState(onWakeFrameDriver)
    val currentIsMainTrackMuted by rememberUpdatedState(isMainTrackMuted)
    val currentOnToggleTrackMute by rememberUpdatedState(onToggleTrackMute)
    val currentOnToast by rememberUpdatedState(onToast)

    val clipNaturalDurationCache = remember { mutableMapOf<String, Long>() }

    // Pre-allocated styling constants (Rule R10)
    val rulerHeightPx = remember(density) { TimelineHitTester.RULER_HEIGHT_DP * density }
    val rowAPinsHeightPx = remember(density) { TimelineHitTester.ROW_A_PINS_HEIGHT_DP * density }
    val rowBBarHeightPx = remember(density) { TimelineHitTester.ROW_B_BAR_HEIGHT_DP * density }
    val mainTrackHeightPx = remember(density) { TimelineHitTester.MAIN_TRACK_HEIGHT_DP * density }
    val pipCollapsedLineHeightPx = remember(density) { TimelineHitTester.ROW_D_PIP_COLLAPSED_LINE_HEIGHT_DP * density }
    val pipExpandedLaneHeightPx = remember(density) { TimelineHitTester.ROW_D_PIP_EXPANDED_LANE_HEIGHT_DP * density }
    val pipExpandedLaneStepPx = remember(density) { TimelineHitTester.ROW_D_PIP_EXPANDED_LANE_STEP_DP * density }
    val pipExpandedTotalHeightPx = remember(density) { TimelineHitTester.ROW_D_PIP_EXPANDED_TOTAL_HEIGHT_DP * density }
    val soundBarHeightPx = remember(density) { TimelineHitTester.ROW_E_SOUND_BAR_HEIGHT_DP * density }

    val pinWidthPx = remember(density) { TimelineHitTester.PIN_WIDTH_DP * density }
    val pinHeightPx = remember(density) { TimelineHitTester.PIN_HEIGHT_DP * density }
    val pinCornerRadiusPx = remember(density) { 3.5f * density }
    val pinUnderlineStrokePx = remember(density) { 2.5f * density }

    val cornerRadiusPx = remember(density) { 6.dp.value * density }
    val selectionStroke = remember(density) { Stroke(width = 2.5f * density) }
    val unselectedStroke = remember(density) { Stroke(width = 1f * density) }
    val playheadStrokeWidth = remember(density) { 2.5f * density }

    val junctionBoxSizePx = remember(density) { TimelineHitTester.JUNCTION_BOX_SIZE_DP * density }
    val junctionHalfBoxPx = remember(density) { junctionBoxSizePx / 2f }
    val junctionCornerRadius = remember(density) { CornerRadius(4.5f * density, 4.5f * density) }
    val junctionBoxSize = remember(density) { Size(junctionBoxSizePx, junctionBoxSizePx) }
    val junctionBoxBorderStroke = remember(density) { Stroke(width = 1f * density) }
    val junctionSelectedBorderStroke = remember(density) { Stroke(width = 2f * density) }
    val slashStrokeWidthPx = remember(density) { 2f * density }
    val slashInsetPx = remember(density) { 5.5f * density }

    val whiteSelectionStroke = remember(density) { Stroke(width = 2f * density) }
    val chevronStroke = remember(density) { Stroke(width = 2f * density, cap = StrokeCap.Round) }
    val handleCornerRadiusPx = remember(density) { 4f * density }
    val handleWidthPx = remember(density) { 15f * density }
    val handleHeightPx = remember(density) { 38f * density }
    val chevronHalfWPx = remember(density) { 2.5f * density }
    val chevronHalfHPx = remember(density) { 5.5f * density }

    // Pre-allocated colors
    val bgColor = remember { Color(0xFF141414) }
    val rulerMajorTickColor = remember { Color(0xFF8E8E93) }
    val rulerMidTickColor = remember { Color(0xFF555558) }
    val rulerMinorTickColor = remember { Color(0xFF38383A) }
    val clipBgColor = remember { Color(0xFF2C3E50) }
    val clipAltCellColor = remember { Color(0x18FFFFFF) }
    val clipCellDividerColor = remember { Color(0x2EFFFFFF) }
    val clipSprocketColor = remember { Color(0x55FFFFFF) }
    val clipBorderColor = remember { Color(0xFF34495E) }
    val junctionBgColor = remember { Color(0xFFE67E22) }
    val junctionNoneColor = remember { Color(0xFF3E4A56) }
    val junctionSelectedColor = remember { Color(0xFFFF5722) }
    val playheadColor = remember { Color(0xFFFF2D55) }
    val whiteColor = remember { Color.White }
    val chevronColor = remember { Color(0xFF141414) }
    val junctionBoxBorderColor = remember { Color(0x33000000) }
    val slashColor = remember { Color(0xFF141414) }

    // VideoGuru Pin Colors
    val colorTextWarmOrange = remember { Color(0xFFF59E0B) }
    val colorStickerTeal = remember { Color(0xFF2DD4BF) }
    val colorStickerGreen = remember { Color(0xFF4ADE80) }
    val colorPipAmberGold = remember { Color(0xFFC8B84A) }
    val colorPipExpandedBg = remember { Color(0xFF1E232A) }
    val colorYellowHandle = remember { Color(0xFFEAB308) }
    val pipSelectionStroke = remember(density) { Stroke(width = 1.5f * density) }
    val pipHandleWidthPx = remember(density) { 10f * density }
    val pipTileWidthPx = pipExpandedLaneHeightPx
    val pipTileDstSize = remember(pipExpandedLaneHeightPx) { IntSize(pipExpandedLaneHeightPx.toInt(), pipExpandedLaneHeightPx.toInt()) }
    val pipGripStroke = remember(density) { Stroke(width = 1.5f * density, cap = StrokeCap.Round) }
    val pipGripColor = remember { Color(0xFF141414) }
    val colorPinkAddBtn = remember { Color(0xFFFF2D55) }
    val colorCoverCardBg = remember { Color(0xFF1E1E24) }
    val colorSoundBarBg = remember { Color(0xFF1F1F24) }

    val timeTextStyle = remember { TextStyle(fontSize = 9.sp, color = Color(0xFF8E8E93)) }
    val clipLabelStyle = remember { TextStyle(fontSize = 9.sp, color = Color(0x99FFFFFF)) }
    val durationBadgeStyle = remember { TextStyle(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold) }
    val rowBTextStyle = remember { TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
    val coverLabelStyle = remember { TextStyle(fontSize = 8.5.sp, color = Color(0xCCFFFFFF), fontWeight = FontWeight.Medium) }
    val soundLabelStyle = remember { TextStyle(fontSize = 11.sp, color = Color(0xAAFFFFFF), fontWeight = FontWeight.Medium) }
    val pipVolStyle = remember { TextStyle(fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }

    val timeStringCache = remember { TimeStringCache(maxEntries = 200) }
    val secondOffsetCache = remember { SecondOffsetCache(maxSec = 600) }
    val clipTitleCache = remember { ClipTitleCache(maxClips = 100) }
    val durationBadgeCache = remember { DurationBadgeCache(maxEntries = 300) }

    // Track Mute/Unmute constants
    val muteBtnBorderStroke = remember(density) { Stroke(width = 1f * density) }
    val speakerWaveStroke = remember(density) { Stroke(width = 1.5f * density, cap = StrokeCap.Round) }
    val muteCrossColor = remember { Color(0xFFFF453A) }
    val speakerMutedColor = remember { Color(0xFF8E8E93) }
    val muteBtnCardBg = remember { Color(0xFF1E1E24) }
    val muteBtnBorderColor = remember { Color(0x33FFFFFF) }

    // Reusable paths (Rule R10: zero allocation in onDraw)
    val clipPath = remember { Path() }
    val selectionPath = remember { Path() }
    val leftHandlePath = remember { Path() }
    val rightHandlePath = remember { Path() }
    val leftChevronPath = remember { Path() }
    val rightChevronPath = remember { Path() }
    val muteBtnCardPath = remember { Path() }
    val speakerConePath = remember { Path() }
    val speakerWave1Path = remember { Path() }
    val speakerWave2Path = remember { Path() }

    val pinPath = remember { Path() }
    val pinOutlinePath = remember { Path() }
    val stackedPinPath = remember { Path() }
    val stackedPinStroke = remember(density) { Stroke(width = 1.2f * density) }
    val connectorTickPath = remember { Path() }
    val pinSmilePath = remember { Path() }
    val pinTPath = remember { Path() }

    val rowBBarPath = remember { Path() }
    val rowBSelectionPath = remember { Path() }
    val rowBLeftHandlePath = remember { Path() }
    val rowBRightHandlePath = remember { Path() }
    val rowBBadgePath = remember { Path() }

    val coverCardPath = remember { Path() }
    val coverPencilPath = remember { Path() }
    val addBtnPath = remember { Path() }
    val addPlusPath = remember { Path() }

    val pipThinLinePath = remember { Path() }
    val pipLaneCardPath = remember { Path() }
    val pipSelectionPath = remember { Path() }
    val pipLeftHandlePath = remember { Path() }
    val pipRightHandlePath = remember { Path() }
    val pipBadgePath = remember { Path() }

    // Audio Row Resources
    val colorAudioCardBg = remember { Color(0xFF2563EB) }
    val colorAudioCardSelectedBg = remember { Color(0xFF1D4ED8) }
    val colorAudioHandle = remember { Color(0xFF93C5FD) }
    val colorAudioTrackBg = remember { Color(0x14FFFFFF) }
    val audioLabelStyle = remember { TextStyle(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
    val audioCardPath = remember { Path() }
    val audioLeftHandlePath = remember { Path() }
    val audioRightHandlePath = remember { Path() }

    val soundBarPath = remember { Path() }

    val activeTrimPlayheadUs = remember { mutableLongStateOf(Long.MIN_VALUE) }

    // Asynchronous Filmstrip Thumbnail Cache for PIP Rows (Requirement 1 & Rule R10)
    val pipMediaPaths = remember(overlayItems) {
        overlayItems.filter { it.type == LayerType.IMAGE || it.type == LayerType.VIDEO }
            .map { it.mediaPath }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val pipThumbnails = remember { mutableStateMapOf<String, ImageBitmap>() }

    LaunchedEffect(pipMediaPaths) {
        withContext(Dispatchers.IO) {
            for (path in pipMediaPaths) {
                if (!pipThumbnails.containsKey(path)) {
                    try {
                        val file = java.io.File(path)
                        if (file.exists()) {
                            val isVideo = path.endsWith(".mp4", ignoreCase = true) ||
                                    path.endsWith(".mkv", ignoreCase = true) ||
                                    path.endsWith(".mov", ignoreCase = true) ||
                                    path.contains("overlay_vid_")
                            val bmp: android.graphics.Bitmap? = if (isVideo) {
                                val retriever = android.media.MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(path)
                                    val raw = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    if (raw != null) {
                                        val scaled = android.graphics.Bitmap.createScaledBitmap(raw, 56, 56, true)
                                        if (scaled != raw) raw.recycle()
                                        scaled
                                    } else null
                                } catch (_: Exception) {
                                    null
                                } finally {
                                    try { retriever.release() } catch (_: Exception) {}
                                }
                            } else {
                                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeFile(path, opts)
                                var sample = 1
                                while (opts.outWidth / (sample * 2) >= 56 && opts.outHeight / (sample * 2) >= 56) {
                                    sample *= 2
                                }
                                opts.inJustDecodeBounds = false
                                opts.inSampleSize = sample
                                val raw = android.graphics.BitmapFactory.decodeFile(path, opts)
                                if (raw != null) {
                                    val scaled = android.graphics.Bitmap.createScaledBitmap(raw, 56, 56, true)
                                    if (scaled != raw) raw.recycle()
                                    scaled
                                } else null
                            }
                            if (bmp != null) {
                                pipThumbnails[path] = bmp.asImageBitmap()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    var velocityTracker = VelocityTracker()
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    velocityTracker.addPointerInputChange(down)
                    var activePointerId = down.id
                    var lastUptimeMillis = down.uptimeMillis

                    val wasMovingOnDown = playhead.mode == PlayMode.FLINGING || playhead.mode == PlayMode.PLAYING
                    val currentSel = currentSelection
                    val focus = currentFocusMode
                    val selectedClipIndex = (currentSel as? TimelineSelection.ClipSelected)?.index ?: -1
                    val selectedOverlayId = (currentSel as? TimelineSelection.OverlaySelected)?.itemId ?: focus.currentSelectedItemId
                    val snapshotOnDown = holder.current
                    val halfWidthPxOnDown = size.width / 2f

                    val isRowBOpenOnDown = focus is TimelineFocusState.S3_STICKER_ITEM || focus is TimelineFocusState.S4_TEXT_ITEM
                    val rowBHeightDpOnDown = if (isRowBOpenOnDown) TimelineHitTester.ROW_B_BAR_HEIGHT_DP else 0f
                    val rowBTopDpOnDown = TimelineHitTester.RULER_HEIGHT_DP + TimelineHitTester.ROW_A_PINS_HEIGHT_DP + TimelineHitTester.ROW_GAP_DP
                    val rowBBottomDpOnDown = rowBTopDpOnDown + rowBHeightDpOnDown
                    val rowCTopDpOnDown = if (isRowBOpenOnDown) rowBBottomDpOnDown + TimelineHitTester.ROW_GAP_DP else rowBTopDpOnDown

                    val trimHandleHit = if (selectedClipIndex in 0 until snapshotOnDown.main.n) {
                        TimelineHitTester.hitTestTrimHandle(
                            tapX = down.position.x,
                            tapY = down.position.y,
                            selectedClipIndex = selectedClipIndex,
                            playheadUs = playhead.timeUs,
                            halfWidthPx = halfWidthPxOnDown,
                            density = density,
                            snapshot = snapshotOnDown,
                            zoomState = zoomState,
                            rowCTopDp = rowCTopDpOnDown,
                            mainTrackHeightDp = TimelineHitTester.MAIN_TRACK_HEIGHT_DP
                        )
                    } else {
                        TimelineHitTester.TrimHandleType.NONE
                    }

                    val isTrimCandidate = trimHandleHit != TimelineHitTester.TrimHandleType.NONE
                    val isLeftHandle = trimHandleHit == TimelineHitTester.TrimHandleType.LEFT

                    val overlayTrimHit = if (!isTrimCandidate && selectedOverlayId != null) {
                        TimelineHitTester.hitTestOverlayTrimHandle(
                            tapX = down.position.x,
                            tapY = down.position.y,
                            selectedOverlayId = selectedOverlayId,
                            overlayItems = currentOverlayItems,
                            playheadUs = playhead.timeUs,
                            halfWidthPx = halfWidthPxOnDown,
                            density = density,
                            zoomState = zoomState,
                            focusMode = focus
                        )
                    } else {
                        TimelineHitTester.OverlayTrimEdge.NONE
                    }

                    val isOverlayTrimCandidate = overlayTrimHit != TimelineHitTester.OverlayTrimEdge.NONE
                    val isOverlayLeftHandle = overlayTrimHit == TimelineHitTester.OverlayTrimEdge.LEFT

                    if (wasMovingOnDown) {
                        if (isTrimCandidate || isOverlayTrimCandidate) {
                            controller.pause()
                        } else {
                            controller.beginScrub()
                        }
                        currentOnWakeFrameDriver()
                    }

                    var isDragging = false
                    var isZooming = false
                    var isTrimming = false
                    var wasLongPressed = false
                    val startTouchX = down.position.x
                    var startPos = down.position
                    val dragSlop = 4.dp.value * density
                    var lastDragX = down.position.x
                    var remainderUs = 0.0

                    val initialPlayheadUs = playhead.timeUs
                    val curClip = if (isTrimCandidate) snapshotOnDown.project.clips.getOrNull(selectedClipIndex) else null
                    val curOverlayItem = if (isOverlayTrimCandidate && selectedOverlayId != null) {
                        currentOverlayItems.firstOrNull { it.id == selectedOverlayId }
                    } else null
                    val initialMap = curClip?.map() ?: TimeMap(0L, 0L)
                    val initialInUs = initialMap.inUs
                    val initialOutUs = initialMap.outUs
                    val pxPerUsAtStart = zoomState.pxPerUs(density)

                    val initialOverlayStartUs = curOverlayItem?.startUs ?: 0L
                    val initialOverlayEndUs = curOverlayItem?.endUs ?: 0L
                    var currentOverlayStartUs = initialOverlayStartUs
                    var currentOverlayEndUs = initialOverlayEndUs

                    val untrimmedDurationUs = if (isTrimCandidate && curClip != null) {
                        clipNaturalDurationCache.getOrPut(curClip.id) {
                            val reported = currentGetClipNaturalDurationUs(selectedClipIndex)
                            maxOf(reported, initialOutUs, msToUs(curClip.durationMs))
                        }
                    } else 0L
                    val maxSourceUs = maxOf(initialOutUs, untrimmedDurationUs, curClip?.let { msToUs(it.durationMs) } ?: 0L)

                    var currentInUs = initialInUs
                    var currentOutUs = initialOutUs
                    var currentFingerX = down.position.x
                    var accumulatedAutoScrollUs = 0L
                    var autoScrollJob: Job? = null
                    val maxScrollSpeedPxPerSec = 300f * density
                    val edgeZonePx = 48f * density
                    var scrollRemainderUs = 0.0
                    var hasShownDurationToast = false

                    fun updateTrim(fingerX: Float) {
                        if (pxPerUsAtStart <= 0.0) return
                        val totalDxPx = fingerX - startTouchX
                        val dragDeltaUs = (totalDxPx / pxPerUsAtStart).roundToLong()
                        val effectiveDeltaUs = dragDeltaUs + accumulatedAutoScrollUs

                        val newInUs: Long
                        val newOutUs: Long
                        if (isLeftHandle) {
                            newInUs = (initialInUs + effectiveDeltaUs).coerceIn(0L, initialOutUs - 200_000L)
                            newOutUs = initialOutUs
                        } else {
                            newInUs = initialInUs
                            newOutUs = (initialOutUs + effectiveDeltaUs).coerceIn(initialInUs + 200_000L, maxSourceUs)
                        }

                        if (newInUs != currentInUs || newOutUs != currentOutUs) {
                            currentInUs = newInUs
                            currentOutUs = newOutUs

                            val speedNum = initialMap.speedNum
                            val speedDen = initialMap.speedDen
                            val newMap = TimeMap(
                                inUs = newInUs,
                                outUs = newOutUs,
                                speedNum = speedNum,
                                speedDen = speedDen
                            )
                            val newProject = SetTimeMapCommand(
                                clipIndex = selectedClipIndex,
                                timeMap = newMap
                            ).apply(holder.current.project)
                            holder.current = TimelineSnapshot(System.nanoTime(), newProject)
                        }

                        val virtualPlayheadUs = if (isLeftHandle) {
                            initialPlayheadUs - (currentInUs - initialInUs) + accumulatedAutoScrollUs
                        } else {
                            initialPlayheadUs + accumulatedAutoScrollUs
                        }
                        activeTrimPlayheadUs.longValue = virtualPlayheadUs

                        val totalTimelineUs = msToUs(holder.current.totalMs)
                        val clampedPlayheadUs = virtualPlayheadUs.coerceIn(0L, maxOf(0L, totalTimelineUs))

                        if (isLeftHandle || accumulatedAutoScrollUs != 0L) {
                            controller.seekTo(clampedPlayheadUs)
                        }

                        val trimmedEdgeUs = if (isLeftHandle) {
                            msToUs(holder.current.main.startMs[selectedClipIndex])
                        } else {
                            val endMs = holder.current.main.endMs[selectedClipIndex]
                            val startMs = holder.current.main.startMs[selectedClipIndex]
                            maxOf(msToUs(startMs), msToUs(endMs) - 1_000L)
                        }
                        currentOnTrimPreview(trimmedEdgeUs)
                        currentOnWakeFrameDriver()
                    }

                    fun updateOverlayTrim(fingerX: Float) {
                        if (pxPerUsAtStart <= 0.0 || curOverlayItem == null) return
                        val totalDxPx = fingerX - startTouchX
                        val dragDeltaUs = (totalDxPx / pxPerUsAtStart).roundToLong()
                        val effectiveDeltaUs = dragDeltaUs + accumulatedAutoScrollUs
                        val totalTimelineUs = msToUs(holder.current.totalMs)

                        val rawStartUs: Long
                        val rawEndUs: Long
                        if (isOverlayLeftHandle) {
                            rawStartUs = initialOverlayStartUs + effectiveDeltaUs
                            rawEndUs = initialOverlayEndUs
                        } else {
                            rawStartUs = initialOverlayStartUs
                            rawEndUs = initialOverlayEndUs + effectiveDeltaUs
                            if (rawEndUs > totalTimelineUs && !hasShownDurationToast) {
                                hasShownDurationToast = true
                                currentOnToast("Cannot exceed video duration")
                            }
                        }

                        val (newStartUs, newEndUs) = com.flowcut.app.engine.clampItemTrimBoundsOnRow(
                            items = currentOverlayItems,
                            itemId = curOverlayItem.id,
                            candidateStartUs = rawStartUs,
                            candidateEndUs = rawEndUs,
                            totalProjectDurationUs = totalTimelineUs
                        )

                        if (newStartUs != currentOverlayStartUs || newEndUs != currentOverlayEndUs) {
                            currentOverlayStartUs = newStartUs
                            currentOverlayEndUs = newEndUs
                            currentOnLiveOverlayTrim(curOverlayItem.id, newStartUs, newEndUs)
                        }

                        val seekUs = if (isOverlayLeftHandle) newStartUs else newEndUs
                        controller.seekTo(seekUs)
                        currentOnWakeFrameDriver()
                    }

                    val longPressJob = if (!wasMovingOnDown && !isTrimCandidate && !isOverlayTrimCandidate) {
                        coroutineScope.launch {
                            delay(viewConfiguration.longPressTimeoutMillis)
                            if (!isDragging && !isZooming && !wasMovingOnDown) {
                                wasLongPressed = true
                                val halfWidthPx = size.width / 2f
                                val hit = TimelineHitTester.hitTest(
                                    tapX = startPos.x,
                                    tapY = startPos.y,
                                    playheadUs = playhead.timeUs,
                                    halfWidthPx = halfWidthPx,
                                    density = density,
                                    snapshot = holder.current,
                                    zoomState = zoomState,
                                    selectedClipIndex = selectedClipIndex,
                                    selectedOverlayId = selectedOverlayId,
                                    overlayItems = currentOverlayItems,
                                    focusMode = focus
                                )
                                currentOnLongPress(hit)
                            }
                        }
                    } else null

                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)

                            var pressedCount = 0
                            var activeChange: PointerInputChange? = null
                            for (i in event.changes.indices) {
                                val ch = event.changes[i]
                                if (ch.pressed) {
                                    pressedCount++
                                    if (ch.id == activePointerId) {
                                        activeChange = ch
                                    }
                                }
                            }
                            if (activeChange == null && pressedCount > 0) {
                                for (i in event.changes.indices) {
                                    val ch = event.changes[i]
                                    if (ch.pressed) {
                                        activeChange = ch
                                        activePointerId = ch.id
                                        lastDragX = ch.position.x
                                        lastUptimeMillis = ch.uptimeMillis
                                        break
                                    }
                                }
                            }

                            if (pressedCount >= 2) {
                                longPressJob?.cancel()
                                autoScrollJob?.cancel()
                                autoScrollJob = null
                                activeTrimPlayheadUs.longValue = Long.MIN_VALUE
                                isZooming = true
                                val zoomFactor = event.calculateZoom()
                                if (zoomFactor != 1f) {
                                    zoomState.updateZoom(zoomFactor)
                                    currentOnWakeFrameDriver()
                                }
                                event.changes.forEach { it.consume() }
                            } else if (pressedCount == 1) {
                                val change = activeChange ?: event.changes.first { it.pressed }
                                if (isZooming) {
                                    isZooming = false
                                    lastDragX = change.position.x
                                    startPos = change.position
                                    activePointerId = change.id
                                    remainderUs = 0.0
                                    velocityTracker = VelocityTracker()
                                    velocityTracker.addPointerInputChange(change)
                                    lastUptimeMillis = change.uptimeMillis
                                    change.consume()
                                } else if (isTrimCandidate) {
                                    val totalDxPx = change.position.x - startTouchX
                                    val dragDist = abs(totalDxPx)
                                    if (!isTrimming && dragDist > dragSlop) {
                                        isTrimming = true
                                    }
                                    if (isTrimming) {
                                        currentFingerX = change.position.x
                                        updateTrim(currentFingerX)

                                        val canvasW = size.width.toFloat()
                                        val inLeftZone = currentFingerX < edgeZonePx
                                        val inRightZone = currentFingerX > canvasW - edgeZonePx

                                        if ((inLeftZone || inRightZone) && autoScrollJob == null) {
                                            autoScrollJob = coroutineScope.launch {
                                                var lastNanos = System.nanoTime()
                                                while (isActive) {
                                                    delay(16L)
                                                    val nowNanos = System.nanoTime()
                                                    val dtSec = ((nowNanos - lastNanos) / 1_000_000_000.0).coerceIn(0.001, 0.050)
                                                    lastNanos = nowNanos

                                                    val w = size.width.toFloat()
                                                    val fX = currentFingerX
                                                    val speedFactor = when {
                                                        fX < edgeZonePx -> -((edgeZonePx - fX) / edgeZonePx).coerceIn(0f, 1f)
                                                        fX > w - edgeZonePx -> ((fX - (w - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1f)
                                                        else -> 0f
                                                    }

                                                    if (speedFactor == 0f) break

                                                    val deltaPx = speedFactor * maxScrollSpeedPxPerSec * dtSec
                                                    if (pxPerUsAtStart > 0.0) {
                                                        val rawDeltaUs = (deltaPx / pxPerUsAtStart) + scrollRemainderUs
                                                        val deltaUs = rawDeltaUs.roundToLong()
                                                        scrollRemainderUs = rawDeltaUs - deltaUs

                                                        if (deltaUs != 0L) {
                                                            val canScroll = if (isLeftHandle) {
                                                                if (deltaUs < 0) currentInUs > 0L else currentInUs < initialOutUs - 200_000L
                                                            } else {
                                                                if (deltaUs > 0) currentOutUs < maxSourceUs else currentOutUs > initialInUs + 200_000L
                                                            }

                                                            if (canScroll) {
                                                                accumulatedAutoScrollUs += deltaUs
                                                                updateTrim(currentFingerX)
                                                            }
                                                        }
                                                    }
                                                }
                                                autoScrollJob = null
                                            }
                                        } else if (!inLeftZone && !inRightZone) {
                                            autoScrollJob?.cancel()
                                            autoScrollJob = null
                                        }
                                    }
                                    change.consume()
                                } else if (isOverlayTrimCandidate) {
                                    val totalDxPx = change.position.x - startTouchX
                                    val dragDist = abs(totalDxPx)
                                    if (!isTrimming && dragDist > dragSlop) {
                                        isTrimming = true
                                    }
                                    if (isTrimming) {
                                        currentFingerX = change.position.x
                                        updateOverlayTrim(currentFingerX)

                                        val canvasW = size.width.toFloat()
                                        val inLeftZone = currentFingerX < edgeZonePx
                                        val inRightZone = currentFingerX > canvasW - edgeZonePx

                                        if ((inLeftZone || inRightZone) && autoScrollJob == null) {
                                            autoScrollJob = coroutineScope.launch {
                                                var lastNanos = System.nanoTime()
                                                while (isActive) {
                                                    delay(16L)
                                                    val nowNanos = System.nanoTime()
                                                    val dtSec = ((nowNanos - lastNanos) / 1_000_000_000.0).coerceIn(0.001, 0.050)
                                                    lastNanos = nowNanos

                                                    val w = size.width.toFloat()
                                                    val fX = currentFingerX
                                                    val speedFactor = when {
                                                        fX < edgeZonePx -> -((edgeZonePx - fX) / edgeZonePx).coerceIn(0f, 1f)
                                                        fX > w - edgeZonePx -> ((fX - (w - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1f)
                                                        else -> 0f
                                                    }

                                                    if (speedFactor == 0f) break

                                                    val deltaPx = speedFactor * maxScrollSpeedPxPerSec * dtSec
                                                    if (pxPerUsAtStart > 0.0) {
                                                        val rawDeltaUs = (deltaPx / pxPerUsAtStart) + scrollRemainderUs
                                                        val deltaUs = rawDeltaUs.roundToLong()
                                                        scrollRemainderUs = rawDeltaUs - deltaUs

                                                        if (deltaUs != 0L) {
                                                            accumulatedAutoScrollUs += deltaUs
                                                            updateOverlayTrim(currentFingerX)
                                                        }
                                                    }
                                                }
                                                autoScrollJob = null
                                            }
                                        } else if (!inLeftZone && !inRightZone) {
                                            autoScrollJob?.cancel()
                                            autoScrollJob = null
                                        }
                                    }
                                    change.consume()
                                } else {
                                    val totalDx = abs(change.position.x - startPos.x)
                                    val totalDy = abs(change.position.y - startPos.y)

                                    if (!isDragging && totalDx > dragSlop && totalDx > totalDy) {
                                        isDragging = true
                                        longPressJob?.cancel()
                                        controller.beginScrub()
                                        currentOnWakeFrameDriver()
                                    }

                                    if (isDragging) {
                                        val dx = change.position.x - lastDragX
                                        lastDragX = change.position.x
                                        lastUptimeMillis = change.uptimeMillis

                                        val currentPxPerUs = zoomState.pxPerUs(density)
                                        if (currentPxPerUs > 0.0) {
                                            val exactDeltaUs = (-dx / currentPxPerUs) + remainderUs
                                            val deltaUsLong = exactDeltaUs.roundToLong()
                                            remainderUs = exactDeltaUs - deltaUsLong

                                            if (deltaUsLong != 0L) {
                                                controller.scrubBy(deltaUsLong)
                                                currentOnWakeFrameDriver()
                                            }
                                        }
                                        velocityTracker.addPointerInputChange(change)
                                        change.consume()
                                    }
                                }
                            } else {
                                break
                            }
                        }
                    } finally {
                        longPressJob?.cancel()
                        autoScrollJob?.cancel()
                        activeTrimPlayheadUs.longValue = Long.MIN_VALUE
                    }

                    if (isTrimCandidate && isTrimming) {
                        val finalDurMs = usToMs(currentOutUs - currentInUs)
                        currentOnCommitTrim(selectedClipIndex, currentInUs, currentOutUs, finalDurMs)
                        currentOnWakeFrameDriver()
                    } else if (isOverlayTrimCandidate && isTrimming && curOverlayItem != null) {
                        currentOnCommitOverlayTrim(curOverlayItem.id, currentOverlayStartUs, currentOverlayEndUs)
                        currentOnWakeFrameDriver()
                    } else if (isDragging) {
                        val velocityPxPerSec = velocityTracker.calculateVelocity().x
                        val pxPerUsAtEnd = zoomState.pxPerUs(density)
                        val velocityUsPerSec = if (pxPerUsAtEnd > 0.0) -velocityPxPerSec / pxPerUsAtEnd else 0.0
                        controller.endScrub(velocityUsPerSec, pxPerUsAtEnd)
                        currentOnWakeFrameDriver()
                    } else if (!wasLongPressed && !isZooming && !isTrimming) {
                        val halfWidthPx = size.width / 2f
                        val hit = TimelineHitTester.hitTest(
                            tapX = startPos.x,
                            tapY = startPos.y,
                            playheadUs = playhead.timeUs,
                            halfWidthPx = halfWidthPx,
                            density = density,
                            snapshot = holder.current,
                            zoomState = zoomState,
                            selectedClipIndex = selectedClipIndex,
                            selectedOverlayId = selectedOverlayId,
                            overlayItems = currentOverlayItems,
                            focusMode = focus
                        )
                        currentOnSelect(hit)
                        currentOnWakeFrameDriver()
                    }
                }
            }
    ) {
        // Enforce Rule R3: Read frameTick, playhead.timeUs, holder.current inside onDraw ONLY
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTick.longValue
        val snapshot = holder.current

        val activeTrimUs = activeTrimPlayheadUs.longValue
        val tUs = if (activeTrimUs != Long.MIN_VALUE) activeTrimUs else playhead.timeUs

        val halfWidthPx = size.width / 2f
        val halfWidthInt = halfWidthPx.toInt()
        val pxPerUs = zoomState.pxPerUs(density)
        val offsetPx = (tUs * pxPerUs).roundToLong()
        val pxPerSec = pxPerUs * 1_000_000.0

        val spanUs = if (pxPerUs > 0.0) ((halfWidthPx + 100f) / pxPerUs).toLong() else 10_000_000L
        val leftUs = maxOf(0L, tUs - spanUs)
        val rightUs = tUs + spanUs
        val leftMs = usToMs(leftUs)
        val rightMs = usToMs(rightUs)

        val focus = currentFocusMode
        val isRowBOpen = focus is TimelineFocusState.S3_STICKER_ITEM || focus is TimelineFocusState.S4_TEXT_ITEM
        val isPipExpanded = focus is TimelineFocusState.S1_PIP_GROUP || focus is TimelineFocusState.S2_PIP_ITEM
        val isAudioExpanded = focus is TimelineFocusState.S5_AUDIO_GROUP || focus is TimelineFocusState.S6_AUDIO_ITEM

        val pipItems = overlayItems.filter { it.type == LayerType.VIDEO || it.type == LayerType.IMAGE }
        val audioItems = overlayItems.filter { it.type == LayerType.AUDIO }
        val occupiedPipLanes = pipItems.map { it.laneIndex.coerceIn(0, 3) }.toSet()
        val activePipLaneCount = if (occupiedPipLanes.isEmpty()) 0 else (occupiedPipLanes.max() + 1).coerceIn(1, 4)

        // Constant vertical layout coordinates
        val rowATop = rulerHeightPx
        val rowABottom = rowATop + rowAPinsHeightPx

        val rowBTop = rowABottom + (TimelineHitTester.ROW_GAP_DP * density)
        val rowBHeight = if (isRowBOpen) rowBBarHeightPx else 0f
        val rowBBottom = rowBTop + rowBHeight

        val rowCTop = if (isRowBOpen) rowBBottom + (TimelineHitTester.ROW_GAP_DP * density) else rowBTop
        val rowCBottom = rowCTop + mainTrackHeightPx

        val rowDTop = rowCBottom + (TimelineHitTester.ROW_D_TOP_MARGIN_DP * density)
        val lowerRowHeightPx = TimelineHitTester.LOWER_ROW_HEIGHT_DP * density
        val lowerRowStepPx = TimelineHitTester.LOWER_ROW_STEP_DP * density

        val thinLinesBottomPx = if (activePipLaneCount > 0) {
            rowDTop + (((activePipLaneCount - 1) * 7f + 3.5f) * density)
        } else {
            rowDTop
        }

        val soundBarTop = if (activePipLaneCount > 0) {
            thinLinesBottomPx + (8f * density)
        } else {
            rowDTop + (4f * density)
        }
        val soundBarBottom = soundBarTop + soundBarHeightPx

        val selectedOverlayId = (currentSelection as? TimelineSelection.OverlaySelected)?.itemId ?: focus.currentSelectedItemId

        // 1. Draw Background
        drawRect(color = bgColor, topLeft = Offset.Zero, size = size)

        // 2. Draw Time Ruler (Section 8)
        val labelStepSec = when {
            pxPerSec >= 100.0 -> 1L
            pxPerSec >= 30.0 -> 2L
            pxPerSec >= 15.0 -> 5L
            else -> 10L
        }

        val subDivisions = when {
            pxPerSec >= 100.0 -> 10
            pxPerSec >= 30.0 -> 5
            else -> 1
        }
        val subStepUs = 1_000_000L / subDivisions

        val leftSec = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(leftMs)
        val rightSec = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(rightMs) + 1L

        val dotY = rulerHeightPx - 3f * density
        val dotRadius = 1.5f * density
        val subDotRadius = 1.0f * density

        for (sec in leftSec..rightSec) {
            val secUs = java.util.concurrent.TimeUnit.SECONDS.toMicros(sec)
            val secX = zoomState.timeUsToX(secUs, offsetPx, halfWidthInt, pxPerUs)

            if (secX in -50f..size.width + 50f) {
                val isLabeled = (sec >= 0L && sec % labelStepSec == 0L)
                if (isLabeled) {
                    val label = timeStringCache.get(sec)
                    val textX = secX + 3f * density
                    val textY = 2f * density
                    drawTextSafe(
                        textMeasurer = textMeasurer,
                        text = label,
                        x = textX,
                        y = textY,
                        style = timeTextStyle
                    )
                    drawCircle(
                        color = rulerMajorTickColor,
                        radius = dotRadius,
                        center = Offset(secX, dotY)
                    )
                } else if (sec >= 0L) {
                    drawCircle(
                        color = rulerMinorTickColor,
                        radius = subDotRadius,
                        center = Offset(secX, dotY)
                    )
                }
            }

            if (subDivisions > 1 && sec >= 0L) {
                for (sub in 1 until subDivisions) {
                    val subUs = secUs + sub * subStepUs
                    val subX = zoomState.timeUsToX(subUs, offsetPx, halfWidthInt, pxPerUs)
                    if (subX in 0f..size.width) {
                        val isMid = (subDivisions == 10 && sub == 5)
                        val r = if (isMid) dotRadius * 0.8f else subDotRadius * 0.75f
                        val color = if (isMid) rulerMidTickColor else rulerMinorTickColor
                        drawCircle(
                            color = color,
                            radius = r,
                            center = Offset(subX, dotY)
                        )
                    }
                }
            }
        }

        // =========================================================================================
        // ROW A: Top Pins Row for Text & Stickers
        // =========================================================================================
        val conflictDistPx = 0.30f * pinWidthPx

        for (idx in overlayItems.indices) {
            val item = overlayItems[idx]
            if (item.type != LayerType.TEXT && item.type != LayerType.STICKER) continue

            val isSelectedPin = (item.id == selectedOverlayId)
            val startX = zoomState.timeUsToX(item.startUs, offsetPx, halfWidthInt, pxPerUs)
            val endX = zoomState.timeUsToX(item.endUs, offsetPx, halfWidthInt, pxPerUs)

            val pinColor = when {
                item.type == LayerType.TEXT -> colorTextWarmOrange
                item.mediaPath.isNotEmpty() -> colorStickerGreen
                else -> colorStickerTeal
            }

            // Duration underline along bottom of Row A
            if (endX > startX && endX >= -50f && startX <= size.width + 50f) {
                drawLine(
                    color = pinColor,
                    start = Offset(startX, rowABottom - (pinUnderlineStrokePx / 2f)),
                    end = Offset(endX, rowABottom - (pinUnderlineStrokePx / 2f)),
                    strokeWidth = pinUnderlineStrokePx,
                    cap = StrokeCap.Round
                )
            }

            // Pin Marker: 24.dp wide x 26.dp tall with downward pointer tip anchored at startX
            if (startX in -50f..size.width + 50f) {
                val elevationY = if (isSelectedPin) -4f * density else 0f
                val pinTopY = rowATop + (2f * density) + elevationY

                // Check if this pin is in a >= 70% overlap conflict with any other pin
                var hasOverlapConflict = false
                for (otherIdx in overlayItems.indices) {
                    val other = overlayItems[otherIdx]
                    if (other.id != item.id && (other.type == LayerType.TEXT || other.type == LayerType.STICKER)) {
                        val otherStartX = zoomState.timeUsToX(other.startUs, offsetPx, halfWidthInt, pxPerUs)
                        if (abs(startX - otherStartX) <= conflictDistPx) {
                            hasOverlapConflict = true
                            break
                        }
                    }
                }

                // If overlapping >= 70%, draw subtle multi-layer stacked outline behind the pin
                if (hasOverlapConflict) {
                    buildPinPath(
                        path = stackedPinPath,
                        anchorX = startX + (3.5f * density),
                        top = pinTopY - (2.5f * density),
                        width = pinWidthPx,
                        height = pinHeightPx,
                        cornerRadius = pinCornerRadiusPx
                    )
                    drawPath(path = stackedPinPath, color = Color(0x991E1E24))
                    drawPath(path = stackedPinPath, color = Color(0xCCFFFFFF), style = stackedPinStroke)
                }

                buildPinPath(
                    path = pinPath,
                    anchorX = startX,
                    top = pinTopY,
                    width = pinWidthPx,
                    height = pinHeightPx,
                    cornerRadius = pinCornerRadiusPx
                )

                // Fill pin body
                drawPath(path = pinPath, color = pinColor)

                // Pin inner icon: crisp white "T" for TEXT, smiley for STICKER
                if (item.type == LayerType.TEXT) {
                    val tTop = pinTopY + (5f * density)
                    val tStemBottom = pinTopY + (14f * density)
                    val tHalfBar = 4.5f * density
                    pinTPath.reset()
                    pinTPath.moveTo(startX - tHalfBar, tTop)
                    pinTPath.lineTo(startX + tHalfBar, tTop)
                    pinTPath.moveTo(startX, tTop)
                    pinTPath.lineTo(startX, tStemBottom)
                    drawPath(path = pinTPath, color = whiteColor, style = Stroke(width = 1.8f * density, cap = StrokeCap.Round))
                } else {
                    // Smiley or custom picture icon
                    if (item.mediaPath.isNotEmpty()) {
                        // Picture icon: small rounded square
                        drawRect(
                            color = whiteColor,
                            topLeft = Offset(startX - (4.5f * density), pinTopY + (5f * density)),
                            size = Size(9f * density, 8f * density),
                            style = Stroke(width = 1.4f * density)
                        )
                    } else {
                        // Smiley eyes and curved smile
                        val eyeY = pinTopY + (7f * density)
                        drawCircle(color = whiteColor, radius = 1.1f * density, center = Offset(startX - (3.2f * density), eyeY))
                        drawCircle(color = whiteColor, radius = 1.1f * density, center = Offset(startX + (3.2f * density), eyeY))

                        pinSmilePath.reset()
                        pinSmilePath.moveTo(startX - (4f * density), pinTopY + (11f * density))
                        pinSmilePath.quadraticTo(startX, pinTopY + (13.5f * density), startX + (4f * density), pinTopY + (11f * density))
                        drawPath(path = pinSmilePath, color = whiteColor, style = Stroke(width = 1.4f * density, cap = StrokeCap.Round))
                    }
                }

                // If selected: 2.dp crisp white outline and short vertical connector tick down to Row B
                if (isSelectedPin) {
                    buildPinPath(
                        path = pinOutlinePath,
                        anchorX = startX,
                        top = pinTopY,
                        width = pinWidthPx,
                        height = pinHeightPx,
                        cornerRadius = pinCornerRadiusPx
                    )
                    drawPath(path = pinOutlinePath, color = whiteColor, style = whiteSelectionStroke)

                    // Vertical white connector tick pointing down into Row B
                    val pinTipY = pinTopY + pinHeightPx
                    connectorTickPath.reset()
                    connectorTickPath.moveTo(startX, pinTipY)
                    connectorTickPath.lineTo(startX, rowBTop + (2f * density))
                    drawPath(path = connectorTickPath, color = whiteColor, style = whiteSelectionStroke)
                }
            }
        }

        // =========================================================================================
        // ROW B: Dynamic Selected Text / Sticker Bar Above Main Track (Only in S3 or S4)
        // =========================================================================================
        if (isRowBOpen && selectedOverlayId != null) {
            val selItem = overlayItems.firstOrNull { it.id == selectedOverlayId }
            if (selItem != null) {
                val startX = zoomState.timeUsToX(selItem.startUs, offsetPx, halfWidthInt, pxPerUs)
                val endX = zoomState.timeUsToX(selItem.endUs, offsetPx, halfWidthInt, pxPerUs)
                val barW = endX - startX

                if (barW > 0f && endX >= -handleWidthPx && startX <= size.width + handleWidthPx) {
                    val barTop = rowBTop + (2f * density)
                    val barBottom = rowBBottom - (2f * density)
                    val barH = barBottom - barTop
                    val barR = 5f * density

                    val fillColor = if (focus is TimelineFocusState.S4_TEXT_ITEM) {
                        colorTextWarmOrange
                    } else if (selItem.mediaPath.isNotEmpty()) {
                        colorStickerGreen
                    } else {
                        colorStickerTeal
                    }

                    buildClipCornerPath(
                        path = rowBBarPath,
                        left = startX,
                        top = barTop,
                        right = endX,
                        bottom = barBottom,
                        roundLeft = true,
                        roundRight = true,
                        radius = barR
                    )
                    drawPath(path = rowBBarPath, color = fillColor)

                    // Crisp white selection border around Row B bar
                    drawPath(path = rowBBarPath, color = whiteColor, style = whiteSelectionStroke)

                    // Text snippet / icon on the left
                    val previewSnippet = if (selItem.text.isNotBlank()) selItem.text else if (focus is TimelineFocusState.S4_TEXT_ITEM) "Your Text" else "Sticker"
                    drawTextSafe(
                        textMeasurer = textMeasurer,
                        text = previewSnippet,
                        x = startX + (8f * density),
                        y = barTop + (6f * density),
                        style = rowBTextStyle
                    )

                    // Duration badge in top-right: e.g. "0:04.0"
                    val durStr = durationBadgeCache.get(selItem.durationUs)
                    val badgeW = 44f * density
                    val badgeH = 16f * density
                    val badgeLeft = endX - badgeW - (6f * density)
                    val badgeTop = barTop + (barH - badgeH) / 2f
                    if (badgeLeft > startX + (50f * density)) {
                        buildClipCornerPath(
                            path = rowBBadgePath,
                            left = badgeLeft,
                            top = badgeTop,
                            right = badgeLeft + badgeW,
                            bottom = badgeTop + badgeH,
                            roundLeft = true,
                            roundRight = true,
                            radius = 3f * density
                        )
                        drawPath(path = rowBBadgePath, color = Color(0x99000000))
                        drawTextSafe(
                            textMeasurer = textMeasurer,
                            text = durStr,
                            x = badgeLeft + (4f * density),
                            y = badgeTop + (1.5f * density),
                            style = durationBadgeStyle
                        )
                    }

                    // External < and > Chevron Trim Handles situated strictly OUTSIDE startX .. endX
                    val r = minOf(handleCornerRadiusPx, handleWidthPx / 2f, barH / 2f)

                    // Left Handle Tab (<)
                    val leftHandleLeft = startX - handleWidthPx
                    val leftHandleRight = startX
                    rowBLeftHandlePath.reset()
                    rowBLeftHandlePath.moveTo(leftHandleRight, barTop)
                    rowBLeftHandlePath.lineTo(leftHandleLeft + r, barTop)
                    rowBLeftHandlePath.quadraticTo(leftHandleLeft, barTop, leftHandleLeft, barTop + r)
                    rowBLeftHandlePath.lineTo(leftHandleLeft, barBottom - r)
                    rowBLeftHandlePath.quadraticTo(leftHandleLeft, barBottom, leftHandleLeft + r, barBottom)
                    rowBLeftHandlePath.lineTo(leftHandleRight, barBottom)
                    rowBLeftHandlePath.close()
                    drawPath(path = rowBLeftHandlePath, color = whiteColor)

                    // Right Handle Tab (>)
                    val rightHandleLeft = endX
                    val rightHandleRight = endX + handleWidthPx
                    rowBRightHandlePath.reset()
                    rowBRightHandlePath.moveTo(rightHandleLeft, barTop)
                    rowBRightHandlePath.lineTo(rightHandleRight - r, barTop)
                    rowBRightHandlePath.quadraticTo(rightHandleRight, barTop, rightHandleRight, barTop + r)
                    rowBRightHandlePath.lineTo(rightHandleRight, barBottom - r)
                    rowBRightHandlePath.quadraticTo(rightHandleRight, barBottom, rightHandleRight - r, barBottom)
                    rowBRightHandlePath.lineTo(rightHandleLeft, barBottom)
                    rowBRightHandlePath.close()
                    drawPath(path = rowBRightHandlePath, color = whiteColor)

                    // Dark < and > chevron icons
                    val barCenterY = (barTop + barBottom) / 2f
                    val leftCenterX = startX - (handleWidthPx / 2f)
                    leftChevronPath.reset()
                    leftChevronPath.moveTo(leftCenterX + chevronHalfWPx, barCenterY - chevronHalfHPx)
                    leftChevronPath.lineTo(leftCenterX - chevronHalfWPx, barCenterY)
                    leftChevronPath.lineTo(leftCenterX + chevronHalfWPx, barCenterY + chevronHalfHPx)
                    drawPath(path = leftChevronPath, color = chevronColor, style = chevronStroke)

                    val rightCenterX = endX + (handleWidthPx / 2f)
                    rightChevronPath.reset()
                    rightChevronPath.moveTo(rightCenterX - chevronHalfWPx, barCenterY - chevronHalfHPx)
                    rightChevronPath.lineTo(rightCenterX + chevronHalfWPx, barCenterY)
                    rightChevronPath.lineTo(rightCenterX - chevronHalfWPx, barCenterY + chevronHalfHPx)
                    drawPath(path = rightChevronPath, color = chevronColor, style = chevronStroke)
                }
            }
        }

        // =========================================================================================
        // ROW C: Main Video Track with Left "Cover" Box & Right Pink "+" Button
        // =========================================================================================
        val mainClipTop = rowCTop + (3f * density)
        val mainClipBottom = rowCBottom - (3f * density)
        val mainClipHeight = mainClipBottom - mainClipTop
        val n = snapshot.main.n

        // Left of 0:00: Cover Box and Track Audio Mute
        val clip0LeftX = zoomState.timeUsToX(0L, offsetPx, halfWidthInt, pxPerUs)
        val coverW = TimelineHitTester.COVER_BOX_WIDTH_DP * density
        val coverH = TimelineHitTester.COVER_BOX_HEIGHT_DP * density
        val coverRight = clip0LeftX - (10f * density)
        val coverLeft = coverRight - coverW
        val coverTop = rowCTop + (mainTrackHeightPx - coverH) / 2f
        val coverBottom = coverTop + coverH

        if (coverRight >= -50f && coverLeft <= size.width + 50f) {
            // Draw Cover card
            buildClipCornerPath(
                path = coverCardPath,
                left = coverLeft,
                top = coverTop,
                right = coverRight,
                bottom = coverBottom,
                roundLeft = true,
                roundRight = true,
                radius = 5f * density
            )
            drawPath(path = coverCardPath, color = colorCoverCardBg)
            drawPath(path = coverCardPath, color = Color(0x33FFFFFF), style = unselectedStroke)

            // Pencil/image icon at top of cover card
            val pencilCenterX = (coverLeft + coverRight) / 2f
            val pencilTopY = coverTop + (8f * density)
            coverPencilPath.reset()
            coverPencilPath.moveTo(pencilCenterX - (4f * density), pencilTopY + (8f * density))
            coverPencilPath.lineTo(pencilCenterX + (4f * density), pencilTopY)
            coverPencilPath.lineTo(pencilCenterX + (6f * density), pencilTopY + (2f * density))
            coverPencilPath.lineTo(pencilCenterX - (2f * density), pencilTopY + (10f * density))
            coverPencilPath.close()
            drawPath(path = coverPencilPath, color = Color(0xAAFFFFFF), style = unselectedStroke)

            // "Cover" text label
            drawTextSafe(
                textMeasurer = textMeasurer,
                text = "Cover",
                x = coverLeft + (5f * density),
                y = coverBottom - (15f * density),
                style = coverLabelStyle
            )

            // Track Audio Mute Button (placed to the left of Cover card)
            val muteBtnCenterX = coverLeft - (20f * density)
            val muteBtnCenterY = rowCTop + (mainTrackHeightPx / 2f)
            val muteBtnHalfW = 14f * density
            val muteBtnHalfH = 17f * density
            val isMuted = currentIsMainTrackMuted

            buildClipCornerPath(
                path = muteBtnCardPath,
                left = muteBtnCenterX - muteBtnHalfW,
                top = muteBtnCenterY - muteBtnHalfH,
                right = muteBtnCenterX + muteBtnHalfW,
                bottom = muteBtnCenterY + muteBtnHalfH,
                roundLeft = true,
                roundRight = true,
                radius = 5f * density
            )
            drawPath(path = muteBtnCardPath, color = muteBtnCardBg)
            drawPath(
                path = muteBtnCardPath,
                color = if (isMuted) Color(0x55FF453A) else muteBtnBorderColor,
                style = muteBtnBorderStroke
            )

            // Speaker cone
            speakerConePath.reset()
            speakerConePath.moveTo(muteBtnCenterX - (7.5f * density), muteBtnCenterY - (3f * density))
            speakerConePath.lineTo(muteBtnCenterX - (4.5f * density), muteBtnCenterY - (3f * density))
            speakerConePath.lineTo(muteBtnCenterX - (1.5f * density), muteBtnCenterY - (6f * density))
            speakerConePath.lineTo(muteBtnCenterX - (1.5f * density), muteBtnCenterY + (6f * density))
            speakerConePath.lineTo(muteBtnCenterX - (4.5f * density), muteBtnCenterY + (3f * density))
            speakerConePath.lineTo(muteBtnCenterX - (7.5f * density), muteBtnCenterY + (3f * density))
            speakerConePath.close()
            drawPath(speakerConePath, color = if (isMuted) speakerMutedColor else whiteColor)

            if (!isMuted) {
                speakerWave1Path.reset()
                speakerWave1Path.moveTo(muteBtnCenterX + (2f * density), muteBtnCenterY - (3.5f * density))
                speakerWave1Path.quadraticTo(
                    muteBtnCenterX + (5f * density),
                    muteBtnCenterY,
                    muteBtnCenterX + (2f * density),
                    muteBtnCenterY + (3.5f * density)
                )
                drawPath(speakerWave1Path, color = whiteColor, style = speakerWaveStroke)
            } else {
                drawLine(
                    color = muteCrossColor,
                    start = Offset(muteBtnCenterX + (2.5f * density), muteBtnCenterY - (4f * density)),
                    end = Offset(muteBtnCenterX + (7.5f * density), muteBtnCenterY + (4f * density)),
                    strokeWidth = 1.8f * density,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = muteCrossColor,
                    start = Offset(muteBtnCenterX + (7.5f * density), muteBtnCenterY - (4f * density)),
                    end = Offset(muteBtnCenterX + (2.5f * density), muteBtnCenterY + (4f * density)),
                    strokeWidth = 1.8f * density,
                    cap = StrokeCap.Round
                )
            }
        }

        // Draw Main Clips
        if (n > 0) {
            val k0 = maxOf(0, snapshot.main.lastStartAtOrBefore(leftMs) - 1)
            val k1 = minOf(n - 1, snapshot.main.lastStartAtOrBefore(rightMs) + 1)

            val cellStepUs = when {
                pxPerSec >= 120.0 -> 500_000L
                pxPerSec >= 40.0 -> 1_000_000L
                pxPerSec >= 15.0 -> 2_000_000L
                else -> 5_000_000L
            }

            for (k in k0..k1) {
                val clipStartMs = snapshot.main.startMs[k]
                val clipEndMs = snapshot.main.endMs[k]
                val clipStartUs = msToUs(clipStartMs)
                val clipEndUs = msToUs(clipEndMs)
                val leftX = zoomState.timeUsToX(clipStartUs, offsetPx, halfWidthInt, pxPerUs)
                val rightX = zoomState.timeUsToX(clipEndUs, offsetPx, halfWidthInt, pxPerUs)
                val clipWidth = rightX - leftX
                if (clipWidth <= 0f) continue

                val roundLeft = (k == 0) || (snapshot.main.types.getOrNull(k - 1) != null && snapshot.main.types[k - 1] != TransitionType.NONE)
                val roundRight = (k == n - 1) || (snapshot.main.types.getOrNull(k) != null && snapshot.main.types[k] != TransitionType.NONE)

                buildClipCornerPath(
                    path = clipPath,
                    left = leftX,
                    top = mainClipTop,
                    right = rightX,
                    bottom = mainClipBottom,
                    roundLeft = roundLeft,
                    roundRight = roundRight,
                    radius = cornerRadiusPx
                )

                drawPath(path = clipPath, color = clipBgColor)

                // Internal Filmstrip motion cues
                val visibleStartUs = maxOf(clipStartUs, tUs - spanUs)
                val visibleEndUs = minOf(clipEndUs, tUs + spanUs)
                val cell0 = maxOf(0L, (visibleStartUs - clipStartUs) / cellStepUs)
                val cell1 = minOf((clipEndUs - clipStartUs) / cellStepUs + 1L, ((visibleEndUs - clipStartUs) / cellStepUs) + 1L)

                for (cellIdx in cell0..cell1) {
                    val cellTimeUs = clipStartUs + cellIdx * cellStepUs
                    if (cellTimeUs >= clipEndUs) break

                    val cellX = zoomState.timeUsToX(cellTimeUs, offsetPx, halfWidthInt, pxPerUs)
                    val nextCellTimeUs = minOf(clipEndUs, cellTimeUs + cellStepUs)
                    val nextCellX = zoomState.timeUsToX(nextCellTimeUs, offsetPx, halfWidthInt, pxPerUs)

                    if (cellIdx % 2L == 1L) {
                        val stripLeft = maxOf(leftX, cellX)
                        val stripRight = minOf(rightX, nextCellX)
                        if (stripRight > stripLeft) {
                            drawRect(
                                color = clipAltCellColor,
                                topLeft = Offset(stripLeft, mainClipTop),
                                size = Size(stripRight - stripLeft, mainClipHeight)
                            )
                        }
                    }

                    if (cellX > leftX + 2f && cellX < rightX - 2f) {
                        drawLine(
                            color = clipCellDividerColor,
                            start = Offset(cellX, mainClipTop),
                            end = Offset(cellX, mainClipBottom),
                            strokeWidth = 1f * density
                        )
                    }

                    if (cellIdx == 0L) {
                        val textLeft = leftX + 8f * density
                        val textTop = mainClipTop + 4f * density
                        if (textLeft < rightX - 16f * density) {
                            val title = clipTitleCache.get(k)
                            drawTextSafe(
                                textMeasurer = textMeasurer,
                                text = title,
                                x = textLeft,
                                y = textTop,
                                style = clipLabelStyle
                            )
                        }
                    } else {
                        val textLeft = cellX + 4f * density
                        val textTop = mainClipTop + 4f * density
                        if (textLeft < rightX - 12f * density) {
                            val secOffset = (cellTimeUs - clipStartUs) / 1_000_000L
                            val marker = secondOffsetCache.get(secOffset)
                            drawTextSafe(
                                textMeasurer = textMeasurer,
                                text = marker,
                                x = textLeft,
                                y = textTop,
                                style = clipLabelStyle
                            )
                        }
                    }
                }

                if (selection != TimelineSelection.ClipSelected(k)) {
                    drawPath(
                        path = clipPath,
                        color = clipBorderColor,
                        style = unselectedStroke
                    )
                }
            }

            // Transition Junction Icons
            val junctionCenterY = rowCTop + (mainTrackHeightPx / 2f)
            val selectedK = (selection as? TimelineSelection.ClipSelected)?.index ?: -1
            for (j in k0 until minOf(k1 + 1, n - 1)) {
                if (selectedK >= 0 && (j == selectedK - 1 || j == selectedK)) continue
                val centerMs = (snapshot.main.startMs[j + 1] + snapshot.main.endMs[j]) / 2L
                val jx = zoomState.timeUsToX(msToUs(centerMs), offsetPx, halfWidthInt, pxPerUs)
                if (jx in -50f..size.width + 50f) {
                    val jType = snapshot.main.types.getOrNull(j) ?: TransitionType.NONE
                    val isJunctionSelected = selection == TimelineSelection.JunctionSelected(j)
                    val jBg = when {
                        isJunctionSelected -> junctionSelectedColor
                        jType != TransitionType.NONE -> junctionBgColor
                        else -> junctionNoneColor
                    }

                    val boxTopLeft = Offset(jx - junctionHalfBoxPx, junctionCenterY - junctionHalfBoxPx)
                    drawRoundRect(
                        color = jBg,
                        topLeft = boxTopLeft,
                        size = junctionBoxSize,
                        cornerRadius = junctionCornerRadius
                    )
                    drawRoundRect(
                        color = if (isJunctionSelected) whiteColor else junctionBoxBorderColor,
                        topLeft = boxTopLeft,
                        size = junctionBoxSize,
                        cornerRadius = junctionCornerRadius,
                        style = if (isJunctionSelected) junctionSelectedBorderStroke else junctionBoxBorderStroke
                    )

                    val slashColorActual = if (isJunctionSelected) whiteColor else slashColor
                    drawLine(
                        color = slashColorActual,
                        start = Offset(jx + junctionHalfBoxPx - slashInsetPx, junctionCenterY - junctionHalfBoxPx + slashInsetPx),
                        end = Offset(jx - junctionHalfBoxPx + slashInsetPx, junctionCenterY + junctionHalfBoxPx - slashInsetPx),
                        strokeWidth = slashStrokeWidthPx,
                        cap = StrokeCap.Round
                    )
                }
            }

            // Dedicated Pass: Selected Clip Crisp White Border & External Trim Handles
            if (selectedK in 0 until n) {
                val selClipStartUs = msToUs(snapshot.main.startMs[selectedK])
                val selClipEndUs = msToUs(snapshot.main.endMs[selectedK])
                val sLeftX = zoomState.timeUsToX(selClipStartUs, offsetPx, halfWidthInt, pxPerUs)
                val sRightX = zoomState.timeUsToX(selClipEndUs, offsetPx, halfWidthInt, pxPerUs)
                val sClipWidth = sRightX - sLeftX

                if (sClipWidth > 0f && sRightX >= -handleWidthPx && sLeftX <= size.width + handleWidthPx) {
                    val halfStroke = whiteSelectionStroke.width / 2f
                    buildClipCornerPath(
                        path = selectionPath,
                        left = sLeftX + halfStroke,
                        top = mainClipTop + halfStroke,
                        right = maxOf(sLeftX + halfStroke, sRightX - halfStroke),
                        bottom = mainClipBottom - halfStroke,
                        roundLeft = true,
                        roundRight = true,
                        radius = maxOf(0f, cornerRadiusPx - halfStroke)
                    )
                    drawPath(path = selectionPath, color = whiteColor, style = whiteSelectionStroke)

                    val clipCenterY = (mainClipTop + mainClipBottom) / 2f
                    val handleTop = clipCenterY - handleHeightPx / 2f
                    val handleBottom = clipCenterY + handleHeightPx / 2f
                    val r = minOf(handleCornerRadiusPx, handleWidthPx / 2f, (handleBottom - handleTop) / 2f)

                    // Left Handle Tab (<)
                    val leftHandleLeft = sLeftX - handleWidthPx
                    val leftHandleRight = sLeftX + 1f
                    leftHandlePath.reset()
                    leftHandlePath.moveTo(leftHandleRight, handleTop)
                    leftHandlePath.lineTo(leftHandleLeft + r, handleTop)
                    leftHandlePath.quadraticTo(leftHandleLeft, handleTop, leftHandleLeft, handleTop + r)
                    leftHandlePath.lineTo(leftHandleLeft, handleBottom - r)
                    leftHandlePath.quadraticTo(leftHandleLeft, handleBottom, leftHandleLeft + r, handleBottom)
                    leftHandlePath.lineTo(leftHandleRight, handleBottom)
                    leftHandlePath.close()
                    drawPath(path = leftHandlePath, color = whiteColor)

                    // Right Handle Tab (>)
                    val rightHandleLeft = sRightX - 1f
                    val rightHandleRight = sRightX + handleWidthPx
                    rightHandlePath.reset()
                    rightHandlePath.moveTo(rightHandleLeft, handleTop)
                    rightHandlePath.lineTo(rightHandleRight - r, handleTop)
                    rightHandlePath.quadraticTo(rightHandleRight, handleTop, rightHandleRight, handleTop + r)
                    rightHandlePath.lineTo(rightHandleRight, handleBottom - r)
                    rightHandlePath.quadraticTo(rightHandleRight, handleBottom, rightHandleRight - r, handleBottom)
                    rightHandlePath.lineTo(rightHandleLeft, handleBottom)
                    rightHandlePath.close()
                    drawPath(path = rightHandlePath, color = whiteColor)

                    val leftCenterX = sLeftX - (handleWidthPx / 2f)
                    leftChevronPath.reset()
                    leftChevronPath.moveTo(leftCenterX + chevronHalfWPx, clipCenterY - chevronHalfHPx)
                    leftChevronPath.lineTo(leftCenterX - chevronHalfWPx, clipCenterY)
                    leftChevronPath.lineTo(leftCenterX + chevronHalfWPx, clipCenterY + chevronHalfHPx)
                    drawPath(path = leftChevronPath, color = chevronColor, style = chevronStroke)

                    val rightCenterX = sRightX + (handleWidthPx / 2f)
                    rightChevronPath.reset()
                    rightChevronPath.moveTo(rightCenterX - chevronHalfWPx, clipCenterY - chevronHalfHPx)
                    rightChevronPath.lineTo(rightCenterX + chevronHalfWPx, clipCenterY)
                    rightChevronPath.lineTo(rightCenterX - chevronHalfWPx, clipCenterY + chevronHalfHPx)
                    drawPath(path = rightChevronPath, color = chevronColor, style = chevronStroke)
                }
            }

            // Right end of Main Track: Pink/Red "+" Button anchored to append media clips
            val totalUs = msToUs(snapshot.totalMs)
            val lastClipRightX = zoomState.timeUsToX(totalUs, offsetPx, halfWidthInt, pxPerUs)
            val addBtnLeft = lastClipRightX + (10f * density)
            val addBtnW = TimelineHitTester.ADD_BTN_WIDTH_DP * density
            val addBtnH = TimelineHitTester.ADD_BTN_HEIGHT_DP * density
            val addBtnTop = rowCTop + (mainTrackHeightPx - addBtnH) / 2f
            val addBtnBottom = addBtnTop + addBtnH

            if (addBtnLeft + addBtnW >= -50f && addBtnLeft <= size.width + 50f) {
                buildClipCornerPath(
                    path = addBtnPath,
                    left = addBtnLeft,
                    top = addBtnTop,
                    right = addBtnLeft + addBtnW,
                    bottom = addBtnBottom,
                    roundLeft = true,
                    roundRight = true,
                    radius = 6f * density
                )
                drawPath(path = addBtnPath, color = colorPinkAddBtn)

                val plusCenterX = addBtnLeft + (addBtnW / 2f)
                val plusCenterY = (addBtnTop + addBtnBottom) / 2f
                val plusHalfSize = 6f * density
                addPlusPath.reset()
                addPlusPath.moveTo(plusCenterX - plusHalfSize, plusCenterY)
                addPlusPath.lineTo(plusCenterX + plusHalfSize, plusCenterY)
                addPlusPath.moveTo(plusCenterX, plusCenterY - plusHalfSize)
                addPlusPath.lineTo(plusCenterX, plusCenterY + plusHalfSize)
                drawPath(path = addPlusPath, color = whiteColor, style = Stroke(width = 2f * density, cap = StrokeCap.Round))
            }
        }

        // =========================================================================================
        // ROW D: Fixed 4-Row Area Below Main Track (PIP / Audio / Collapsed)
        // =========================================================================================
        if (isPipExpanded) {
            // Expanded 4 PIP Rows (28.dp tall each, 4.dp gap, row step = 32.dp)
            for (lane in 0..3) {
                val laneTop = rowDTop + (lane * lowerRowStepPx)
                val laneBottom = laneTop + lowerRowHeightPx
                val lanePipItems = pipItems.filter { it.laneIndex.coerceIn(0, 3) == lane }

                for (item in lanePipItems) {
                    val sX = zoomState.timeUsToX(item.startUs, offsetPx, halfWidthInt, pxPerUs)
                    val eX = zoomState.timeUsToX(item.endUs, offsetPx, halfWidthInt, pxPerUs)
                    val itemW = eX - sX
                    if (itemW <= 0f || eX < -handleWidthPx || sX > size.width + handleWidthPx) continue

                    val isSelectedPip = (focus is TimelineFocusState.S2_PIP_ITEM && focus.itemId == item.id)

                    buildClipCornerPath(
                        path = pipLaneCardPath,
                        left = sX,
                        top = laneTop,
                        right = eX,
                        bottom = laneBottom,
                        roundLeft = true,
                        roundRight = true,
                        radius = 4f * density
                    )

                    clipPath(path = pipLaneCardPath) {
                        drawPath(path = pipLaneCardPath, color = colorPipExpandedBg)

                        val thumb = pipThumbnails[item.mediaPath] ?: if (item.mediaPath.isNotEmpty()) PipThumbnailCache.get(item.mediaPath) else null
                        val cellWidthPx = pipTileWidthPx
                        if (thumb != null) {
                            var tileX = sX
                            while (tileX < eX) {
                                drawImage(
                                    image = thumb,
                                    dstOffset = IntOffset(tileX.toInt(), laneTop.toInt()),
                                    dstSize = pipTileDstSize
                                )
                                drawLine(
                                    color = Color(0x44000000),
                                    start = Offset(tileX + cellWidthPx, laneTop),
                                    end = Offset(tileX + cellWidthPx, laneBottom),
                                    strokeWidth = 1f * density
                                )
                                tileX += cellWidthPx
                            }
                        } else {
                            var tileX = sX
                            var alt = false
                            while (tileX < eX) {
                                if (alt) {
                                    drawRect(
                                        color = Color(0x22FFFFFF),
                                        topLeft = Offset(tileX, laneTop),
                                        size = Size(minOf(cellWidthPx, eX - tileX), lowerRowHeightPx)
                                    )
                                }
                                drawLine(
                                    color = clipCellDividerColor,
                                    start = Offset(tileX + cellWidthPx, laneTop),
                                    end = Offset(tileX + cellWidthPx, laneBottom),
                                    strokeWidth = 1f * density
                                )
                                tileX += cellWidthPx
                                alt = !alt
                            }
                        }

                        // On S2_PIP_ITEM: volume badge "🔊 100%"
                        if (isSelectedPip && eX - sX > 56f * density) {
                            val volW = 54f * density
                            val volH = 15f * density
                            val volLeft = sX + (6f * density)
                            val volTop = laneTop + (lowerRowHeightPx - volH) / 2f
                            drawRoundRect(
                                color = Color(0x99000000),
                                topLeft = Offset(volLeft, volTop),
                                size = Size(volW, volH),
                                cornerRadius = CornerRadius(3f * density, 3f * density)
                            )
                            drawTextSafe(
                                textMeasurer = textMeasurer,
                                text = "🔊 100%",
                                x = volLeft + (3f * density),
                                y = volTop + (1.5f * density),
                                style = pipVolStyle
                            )
                        }

                        // On S2_PIP_ITEM: duration badge on right
                        if (isSelectedPip) {
                            val durBadgeStr = durationBadgeCache.get(item.durationUs)
                            val badgeW = 42f * density
                            val badgeH = 15f * density
                            val badgeLeft = eX - badgeW - (6f * density)
                            val badgeTop = laneTop + (lowerRowHeightPx - badgeH) / 2f
                            if (badgeLeft > sX + (66f * density)) {
                                drawRoundRect(
                                    color = Color(0x99000000),
                                    topLeft = Offset(badgeLeft, badgeTop),
                                    size = Size(badgeW, badgeH),
                                    cornerRadius = CornerRadius(3f * density, 3f * density)
                                )
                                drawTextSafe(
                                    textMeasurer = textMeasurer,
                                    text = durBadgeStr,
                                    x = badgeLeft + (4f * density),
                                    y = badgeTop + (1.5f * density),
                                    style = durationBadgeStyle
                                )
                            }
                        }
                    }

                    // In S2_PIP_ITEM: Yellow Left Handle, selection border, and trim handles
                    if (isSelectedPip) {
                        drawPath(path = pipLaneCardPath, color = colorYellowHandle, style = pipSelectionStroke)

                        val r = minOf(4f * density, pipHandleWidthPx / 2f, lowerRowHeightPx / 2f)
                        val leftHandleLeft = sX - pipHandleWidthPx
                        val leftHandleRight = sX
                        pipLeftHandlePath.reset()
                        pipLeftHandlePath.moveTo(leftHandleRight, laneTop)
                        pipLeftHandlePath.lineTo(leftHandleLeft + r, laneTop)
                        pipLeftHandlePath.quadraticTo(leftHandleLeft, laneTop, leftHandleLeft, laneTop + r)
                        pipLeftHandlePath.lineTo(leftHandleLeft, laneBottom - r)
                        pipLeftHandlePath.quadraticTo(leftHandleLeft, laneBottom, leftHandleLeft + r, laneBottom)
                        pipLeftHandlePath.lineTo(leftHandleRight, laneBottom)
                        pipLeftHandlePath.close()
                        drawPath(path = pipLeftHandlePath, color = colorYellowHandle)

                        // Vertical dark grip line inside left handle
                        val leftGripX = sX - (pipHandleWidthPx / 2f)
                        drawLine(
                            color = pipGripColor,
                            start = Offset(leftGripX, laneTop + 5f * density),
                            end = Offset(leftGripX, laneBottom - 5f * density),
                            strokeWidth = 1.5f * density,
                            cap = StrokeCap.Round
                        )

                        val rightHandleLeft = eX
                        val rightHandleRight = eX + pipHandleWidthPx
                        pipRightHandlePath.reset()
                        pipRightHandlePath.moveTo(rightHandleLeft, laneTop)
                        pipRightHandlePath.lineTo(rightHandleRight - r, laneTop)
                        pipRightHandlePath.quadraticTo(rightHandleRight, laneTop, rightHandleRight, laneTop + r)
                        pipRightHandlePath.lineTo(rightHandleRight, laneBottom - r)
                        pipRightHandlePath.quadraticTo(rightHandleRight, laneBottom, rightHandleRight - r, laneBottom)
                        pipRightHandlePath.lineTo(rightHandleLeft, laneBottom)
                        pipRightHandlePath.close()
                        drawPath(path = pipRightHandlePath, color = colorYellowHandle)

                        // Vertical dark grip line inside right handle
                        val rightGripX = eX + (pipHandleWidthPx / 2f)
                        drawLine(
                            color = pipGripColor,
                            start = Offset(rightGripX, laneTop + 5f * density),
                            end = Offset(rightGripX, laneBottom - 5f * density),
                            strokeWidth = 1.5f * density,
                            cap = StrokeCap.Round
                        )
                    } else {
                        drawPath(path = pipLaneCardPath, color = Color(0x33FFFFFF), style = unselectedStroke)
                    }
                }
            }
        } else if (isAudioExpanded) {
            // 1. Keep the 4 PIP thin indicator lines visible above the expanded audio row(s)
            for (lane in 0 until activePipLaneCount) {
                val lineY = rowDTop + (lane * 7f * density)
                val lanePipItems = pipItems.filter { it.laneIndex.coerceIn(0, 3) == lane }
                for (item in lanePipItems) {
                    val sX = zoomState.timeUsToX(item.startUs, offsetPx, halfWidthInt, pxPerUs)
                    val eX = zoomState.timeUsToX(item.endUs, offsetPx, halfWidthInt, pxPerUs)
                    if (eX > sX && eX >= -50f && sX <= size.width + 50f) {
                        buildClipCornerPath(
                            path = pipThinLinePath,
                            left = sX,
                            top = lineY,
                            right = eX,
                            bottom = lineY + (3.5f * density),
                            roundLeft = true,
                            roundRight = true,
                            radius = 1.75f * density
                        )
                        drawPath(path = pipThinLinePath, color = colorPipAmberGold)
                    }
                }
            }

            // 2. Expanded 4 Audio Rows (placed right below the thin PIP lines)
            val audioRowsTop = if (activePipLaneCount > 0) thinLinesBottomPx + (4f * density) else rowDTop
            for (lane in 0..3) {
                val laneTop = audioRowsTop + (lane * lowerRowStepPx)
                val laneBottom = laneTop + lowerRowHeightPx
                val laneAudioItems = audioItems.filter { it.laneIndex.coerceIn(0, 3) == lane }

                for (item in laneAudioItems) {
                    val sX = zoomState.timeUsToX(item.startUs, offsetPx, halfWidthInt, pxPerUs)
                    val eX = zoomState.timeUsToX(item.endUs, offsetPx, halfWidthInt, pxPerUs)
                    val itemW = eX - sX
                    if (itemW <= 0f || eX < -handleWidthPx || sX > size.width + handleWidthPx) continue

                    val isSelectedAudio = (focus is TimelineFocusState.S6_AUDIO_ITEM && focus.itemId == item.id)

                    buildClipCornerPath(
                        path = audioCardPath,
                        left = sX,
                        top = laneTop,
                        right = eX,
                        bottom = laneBottom,
                        roundLeft = true,
                        roundRight = true,
                        radius = 4f * density
                    )

                    val bgColor = if (isSelectedAudio) colorAudioCardSelectedBg else colorAudioCardBg
                    drawPath(path = audioCardPath, color = bgColor)

                    // Sound title label
                    val title = if (item.text.isNotEmpty()) item.text else "Sound"
                    drawTextSafe(
                        textMeasurer = textMeasurer,
                        text = "♪ $title",
                        x = sX + (8f * density),
                        y = laneTop + (6f * density),
                        style = audioLabelStyle
                    )

                    if (isSelectedAudio) {
                        drawPath(path = audioCardPath, color = colorAudioHandle, style = whiteSelectionStroke)

                        val r = minOf(handleCornerRadiusPx, handleWidthPx / 2f, lowerRowHeightPx / 2f)
                        val leftHandleLeft = sX - handleWidthPx
                        val leftHandleRight = sX
                        audioLeftHandlePath.reset()
                        audioLeftHandlePath.moveTo(leftHandleRight, laneTop)
                        audioLeftHandlePath.lineTo(leftHandleLeft + r, laneTop)
                        audioLeftHandlePath.quadraticTo(leftHandleLeft, laneTop, leftHandleLeft, laneTop + r)
                        audioLeftHandlePath.lineTo(leftHandleLeft, laneBottom - r)
                        audioLeftHandlePath.quadraticTo(leftHandleLeft, laneBottom, leftHandleLeft + r, laneBottom)
                        audioLeftHandlePath.lineTo(leftHandleRight, laneBottom)
                        audioLeftHandlePath.close()
                        drawPath(path = audioLeftHandlePath, color = colorAudioHandle)

                        val rightHandleLeft = eX
                        val rightHandleRight = eX + handleWidthPx
                        audioRightHandlePath.reset()
                        audioRightHandlePath.moveTo(rightHandleLeft, laneTop)
                        audioRightHandlePath.lineTo(rightHandleRight - r, laneTop)
                        audioRightHandlePath.quadraticTo(rightHandleRight, laneTop, rightHandleRight, laneTop + r)
                        audioRightHandlePath.lineTo(rightHandleRight, laneBottom - r)
                        audioRightHandlePath.quadraticTo(rightHandleRight, laneBottom, rightHandleRight - r, laneBottom)
                        audioRightHandlePath.lineTo(rightHandleLeft, laneBottom)
                        audioRightHandlePath.close()
                        drawPath(path = audioRightHandlePath, color = colorAudioHandle)

                        val centerY = (laneTop + laneBottom) / 2f
                        val leftCenterX = sX - (handleWidthPx / 2f)
                        leftChevronPath.reset()
                        leftChevronPath.moveTo(leftCenterX + chevronHalfWPx, centerY - chevronHalfHPx)
                        leftChevronPath.lineTo(leftCenterX - chevronHalfWPx, centerY)
                        leftChevronPath.lineTo(leftCenterX + chevronHalfWPx, centerY + chevronHalfHPx)
                        drawPath(path = leftChevronPath, color = chevronColor, style = chevronStroke)

                        val rightCenterX = eX + (handleWidthPx / 2f)
                        rightChevronPath.reset()
                        rightChevronPath.moveTo(rightCenterX - chevronHalfWPx, centerY - chevronHalfHPx)
                        rightChevronPath.lineTo(rightCenterX + chevronHalfWPx, centerY)
                        rightChevronPath.lineTo(rightCenterX - chevronHalfWPx, centerY + chevronHalfHPx)
                        drawPath(path = rightChevronPath, color = chevronColor, style = chevronStroke)
                    } else {
                        drawPath(path = audioCardPath, color = Color(0x33FFFFFF), style = unselectedStroke)
                    }
                }
            }
        } else {
            // Collapsed states (S0, S3, S4):
            // 1. Thin yellow/gold PIP indicator lines (3.5.dp thick, 7.dp step)
            for (lane in 0 until activePipLaneCount) {
                val lineY = rowDTop + (lane * 7f * density)
                val lanePipItems = pipItems.filter { it.laneIndex.coerceIn(0, 3) == lane }
                for (item in lanePipItems) {
                    val sX = zoomState.timeUsToX(item.startUs, offsetPx, halfWidthInt, pxPerUs)
                    val eX = zoomState.timeUsToX(item.endUs, offsetPx, halfWidthInt, pxPerUs)
                    if (eX > sX && eX >= -50f && sX <= size.width + 50f) {
                        buildClipCornerPath(
                            path = pipThinLinePath,
                            left = sX,
                            top = lineY,
                            right = eX,
                            bottom = lineY + (3.5f * density),
                            roundLeft = true,
                            roundRight = true,
                            radius = 1.75f * density
                        )
                        drawPath(path = pipThinLinePath, color = colorPipAmberGold)
                    }
                }
            }

            // 2. "♪ Sound collection" Bar anchored strictly to [0L..totalUsForSound]
            val totalUsForSound = msToUs(snapshot.totalMs)
            val soundBarLeft = zoomState.timeUsToX(0L, offsetPx, halfWidthInt, pxPerUs)
            val soundBarRight = if (totalUsForSound > 0L) {
                zoomState.timeUsToX(totalUsForSound, offsetPx, halfWidthInt, pxPerUs)
            } else {
                soundBarLeft + (120f * density)
            }
            val soundBarW = soundBarRight - soundBarLeft

            if (soundBarW > 0f && soundBarRight >= -50f && soundBarLeft <= size.width + 50f) {
                buildClipCornerPath(
                    path = soundBarPath,
                    left = soundBarLeft,
                    top = soundBarTop,
                    right = soundBarRight,
                    bottom = soundBarBottom,
                    roundLeft = true,
                    roundRight = true,
                    radius = 5f * density
                )
                clipPath(path = soundBarPath) {
                    drawPath(path = soundBarPath, color = colorSoundBarBg)
                    drawPath(path = soundBarPath, color = Color(0x26FFFFFF), style = unselectedStroke)

                    // Keep "♪ Sound collection" label pinned inside visible portion of [soundBarLeft, soundBarRight]
                    val visibleTextX = maxOf(soundBarLeft + (10f * density), 16f * density)
                    if (visibleTextX < soundBarRight - (40f * density)) {
                        drawTextSafe(
                            textMeasurer = textMeasurer,
                            text = "♪ Sound collection",
                            x = visibleTextX,
                            y = soundBarTop + (4.5f * density),
                            style = soundLabelStyle
                        )
                    }
                }
            }
        }

        // Feature Lanes (shifted below Row E)
        val lanesTop = soundBarBottom + (8f * density)
        if (snapshot.lanes.isNotEmpty()) {
            for (laneIdx in snapshot.lanes.indices) {
                val lane = snapshot.lanes[laneIdx]
                val kind = snapshot.project.lanes.getOrNull(laneIdx)?.kind ?: continue
                val painter = lanePainters[kind] ?: continue

                val from = lane.firstEndingAfter(leftMs)
                val to = lane.lastStartingBefore(rightMs)
                if (from >= 0 && to >= from) {
                    val ctx = object : PainterCtx {
                        override val snapshot: TimelineSnapshot get() = snapshot
                        override val halfWidthPx: Float get() = halfWidthPx
                        override val offsetPx: Long get() = offsetPx
                        override val pxPerUs: Double get() = pxPerUs
                        override val leftMs: Long get() = leftMs
                        override val rightMs: Long get() = rightMs
                        override val density: Float get() = density

                        override fun timeMsToX(timeMs: Long): Float =
                            zoomState.timeUsToX(msToUs(timeMs), offsetPx, halfWidthInt, pxPerUs)

                        override fun timeUsToX(timeUs: Long): Float =
                            zoomState.timeUsToX(timeUs, offsetPx, halfWidthInt, pxPerUs)

                        override fun drawRect(x: Float, y: Float, width: Float, height: Float, colorArgb: Int) {
                            this@Canvas.drawRect(
                                color = Color(colorArgb),
                                topLeft = Offset(x, lanesTop + y),
                                size = Size(width, height)
                            )
                        }

                        override fun drawText(text: String, x: Float, y: Float, colorArgb: Int, fontSizeSp: Float) {
                            val textY = lanesTop + y
                            this@Canvas.drawTextSafe(
                                textMeasurer = textMeasurer,
                                text = text,
                                x = x,
                                y = textY,
                                style = TextStyle(fontSize = fontSizeSp.sp, color = Color(colorArgb))
                            )
                        }
                    }
                    painter.draw(ctx, laneIdx, from, to)
                }
            }
        }

        // Center Fixed Playhead Line (Rule R2: Always at size.width / 2)
        drawLine(
            color = playheadColor,
            start = Offset(halfWidthPx, rulerHeightPx),
            end = Offset(halfWidthPx, size.height - 4f * density),
            strokeWidth = playheadStrokeWidth,
            cap = StrokeCap.Round
        )
    }
}
