package com.flowcut.app.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.core.feature.LanePainter
import com.flowcut.app.core.feature.PainterCtx
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.playback.PlayMode
import com.flowcut.app.core.playback.PlaybackController
import com.flowcut.app.core.playback.PlayheadReader
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Constructs a rounded/flat rectangle path in-place for contiguous clip rendering (Section 8 & Rule R10).
 * Prevents gaps and sub-pixel flutter between adjacent clips.
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
 * Safely renders text on Canvas while guaranteeing constraints maxWidth >= minWidth >= 0.
 * In Jetpack Compose, DrawScope.drawText dynamically derives maxWidth as ceil(size.width - topLeft.x).
 * If topLeft.x >= size.width or topLeft.y >= size.height, maxWidth or maxHeight becomes negative,
 * throwing IllegalArgumentException: maxWidth(-12) must be >= than minWidth(0).
 * Furthermore, negative topLeft coordinates can corrupt internal constraints; translate() safely
 * shifts the coordinate matrix without violating layout constraints.
 */
private fun DrawScope.drawTextSafe(
    textMeasurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle
) {
    if (size.width <= 4f || size.height <= 4f) return
    // Guard right and bottom edges to prevent negative constraints (maxWidth / maxHeight < 0)
    if (x >= size.width - 4f || y >= size.height - 4f) return
    // Guard far off-screen to avoid unnecessary work
    if (x < -200f || y < -50f) return

    if (x < 0f || y < 0f) {
        val shiftX = if (x < 0f) x else 0f
        val shiftY = if (y < 0f) y else 0f
        val posX = if (x < 0f) 0f else x
        val posY = if (y < 0f) 0f else y
        if (posX >= size.width - 4f || posY >= size.height - 4f) return
        translate(left = shiftX, top = shiftY) {
            drawText(
                textMeasurer = textMeasurer,
                text = text,
                topLeft = Offset(posX, posY),
                style = style
            )
        }
    } else {
        drawText(
            textMeasurer = textMeasurer,
            text = text,
            topLeft = Offset(x, y),
            style = style
        )
    }
}

/**
 * TimelineCanvas Composable (Section 8).
 * Renders the ruler, main track clips, junction nodes, and feature lanes on a single Canvas.
 *
 * Strict Rules Enforced:
 * - Rule R2: ZERO scroll state. Playhead is anchored at size.width / 2.
 * - Rule R3: Reads frameTick.longValue, playhead.timeUs, holder.current inside onDraw ONLY.
 * - Rule R7: Binary search visible range culling (k0 - 1 .. k1 + 1).
 * - Rule R10: Zero-allocation hot path inside onDraw.
 */
@Composable
fun TimelineCanvas(
    holder: SnapshotHolder,
    playhead: PlayheadReader,
    controller: PlaybackController,
    zoomState: TimelineZoomState,
    frameTick: MutableLongState,
    selection: TimelineSelection = TimelineSelection.None,
    lanePainters: Map<String, LanePainter> = emptyMap(),
    onSelect: (TimelineSelection) -> Unit = {},
    onLongPress: (TimelineSelection) -> Unit = {},
    onWakeFrameDriver: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer(cacheSize = 200)
    val coroutineScope = rememberCoroutineScope()

    // Pre-allocated styling constants (Rule R10)
    val rulerHeightPx = remember(density) { TimelineHitTester.RULER_HEIGHT_DP * density }
    val mainTrackHeightPx = remember(density) { TimelineHitTester.MAIN_TRACK_HEIGHT_DP * density }
    val junctionRadiusPx = remember(density) { 12.dp.value * density }
    val junctionDiameterPx = remember(density) { 24.dp.value * density }
    val cornerRadiusPx = remember(density) { 6.dp.value * density }
    val selectionStroke = remember(density) { Stroke(width = 2.5f * density) }
    val unselectedStroke = remember(density) { Stroke(width = 1f * density) }
    val playheadStrokeWidth = remember(density) { 2.5f * density }

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
    val clipSelectedBorderColor = remember { Color(0xFFFFD700) }
    val junctionBgColor = remember { Color(0xFFE67E22) }
    val junctionNoneColor = remember { Color(0xFF3E4A56) }
    val junctionSelectedColor = remember { Color(0xFFFF5722) }
    val playheadColor = remember { Color(0xFFFF2D55) }
    val timeTextStyle = remember { TextStyle(fontSize = 9.sp, color = Color(0xFF8E8E93)) }
    val clipLabelStyle = remember { TextStyle(fontSize = 9.sp, color = Color(0x99FFFFFF)) }
    val timeStringCache = remember { TimeStringCache(maxEntries = 200) }
    val secondOffsetCache = remember { SecondOffsetCache(maxSec = 600) }
    val clipTitleCache = remember { ClipTitleCache(maxClips = 100) }

    // Reusable paths (Rule R10: zero allocation in onDraw)
    val clipPath = remember { Path() }
    val selectionPath = remember { Path() }

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
                    var lastSignificantDx = 0f

                    val wasMovingOnDown = playhead.mode == PlayMode.FLINGING || playhead.mode == PlayMode.PLAYING
                    if (wasMovingOnDown) {
                        controller.beginScrub()
                        onWakeFrameDriver()
                    }

                    var isDragging = false
                    var isZooming = false
                    var wasLongPressed = false
                    var startPos = down.position
                    val dragSlop = 4.dp.value * density // Immediate 1:1 scrub response!
                    var lastDragX = down.position.x
                    var remainderUs = 0.0

                    // Asynchronous long-press timer: NEVER delays or blocks awaitPointerEvent!
                    val longPressJob = if (!wasMovingOnDown) {
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
                                    zoomState = zoomState
                                )
                                onLongPress(hit)
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
                                isZooming = true
                                val zoomFactor = event.calculateZoom()
                                if (zoomFactor != 1f) {
                                    zoomState.updateZoom(zoomFactor)
                                    onWakeFrameDriver()
                                }
                                event.changes.forEach { it.consume() }
                            } else if (pressedCount == 1) {
                                val change = activeChange ?: event.changes.first { it.pressed }
                                if (isZooming) {
                                    isZooming = false
                                    lastDragX = change.position.x
                                    startPos = change.position
                                    activePointerId = change.id
                                    lastSignificantDx = 0f
                                    remainderUs = 0.0
                                    velocityTracker = VelocityTracker()
                                    velocityTracker.addPointerInputChange(change)
                                    lastUptimeMillis = change.uptimeMillis
                                    change.consume()
                                } else {
                                    if (change.uptimeMillis > lastUptimeMillis) {
                                        velocityTracker.addPointerInputChange(change)
                                        lastUptimeMillis = change.uptimeMillis
                                    }
                                    val dragDistance = abs(change.position.x - startPos.x)

                                    if (!isDragging && dragDistance > dragSlop) {
                                        isDragging = true
                                        longPressJob?.cancel()
                                        controller.beginScrub()
                                        onWakeFrameDriver()
                                        lastDragX = change.position.x
                                        lastSignificantDx = 0f
                                        remainderUs = 0.0
                                    }

                                    if (isDragging) {
                                        val dx = change.position.x - lastDragX
                                        if (abs(dx) > 1.5f) {
                                            lastSignificantDx = dx
                                        }
                                        val pxPerUs = zoomState.pxPerUs(density)
                                        if (pxPerUs > 0.0) {
                                            val exactDeltaUs = (-dx / pxPerUs) + remainderUs
                                            val deltaUsLong = exactDeltaUs.roundToLong()
                                            remainderUs = exactDeltaUs - deltaUsLong
                                            if (deltaUsLong != 0L) {
                                                controller.scrubBy(deltaUsLong)
                                                onWakeFrameDriver()
                                            }
                                        }
                                        lastDragX = change.position.x
                                        change.consume()
                                    }
                                }
                            } else {
                                // Pointer up (pressedCount == 0)
                                longPressJob?.cancel()
                                if (isDragging) {
                                    val pxPerUs = zoomState.pxPerUs(density)
                                    val maxVelocityPx = 8000f * density
                                    var velocityPx = velocityTracker.calculateVelocity(Velocity(maxVelocityPx, maxVelocityPx)).x

                                    // Guard against reverse thumb-lift twitch:
                                    // If user was dragging consistently in one direction (lastSignificantDx != 0),
                                    // do not let a tiny 1-2px release roll/twitch invert the fling velocity sign!
                                    if (lastSignificantDx != 0f && (velocityPx * lastSignificantDx) < 0f) {
                                        velocityPx = -velocityPx
                                    }

                                    val velocityUsPerSec = if (pxPerUs > 0.0) (-velocityPx / pxPerUs) else 0.0
                                    controller.endScrub(velocityUsPerSec, pxPerUs)
                                    onWakeFrameDriver()
                                } else if (!isZooming && !wasMovingOnDown && !wasLongPressed) {
                                    val halfWidthPx = size.width / 2f
                                    if (down.position.y < rulerHeightPx) {
                                        // Ruler tap-to-seek
                                        val tappedTimeUs = zoomState.xToTimeUs(
                                            x = down.position.x,
                                            playheadUs = playhead.timeUs,
                                            halfWidthPx = halfWidthPx,
                                            density = density
                                        )
                                        controller.seekTo(tappedTimeUs)
                                        onWakeFrameDriver()
                                    } else {
                                        val hit = TimelineHitTester.hitTest(
                                            tapX = down.position.x,
                                            tapY = down.position.y,
                                            playheadUs = playhead.timeUs,
                                            halfWidthPx = halfWidthPx,
                                            density = density,
                                            snapshot = holder.current,
                                            zoomState = zoomState
                                        )
                                        onSelect(hit)
                                    }
                                }
                                break
                            }
                        }
                    } finally {
                        longPressJob?.cancel()
                    }
                }
            }
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        // -----------------------------------------------------------------------------------------
        // RULE R3: Read dynamic states inside draw lambda ONLY
        // -----------------------------------------------------------------------------------------
        val tick = frameTick.longValue
        val tUs = playhead.timeUs
        val snapshot = holder.current
        val pxPerUs = zoomState.pxPerUs(density)
        val halfWidthInt = (size.width / 2f).toInt()
        val halfWidthPx = size.width / 2f
        val offsetPx = Math.round(tUs * pxPerUs)
        val pxPerSec = zoomState.pxPerSecond(density)

        // 1. Draw Background
        drawRect(color = bgColor)

        // 2. Visible Range Culling (Rule R7)
        val spanUs = if (pxPerUs > 0.0) (halfWidthPx / pxPerUs).roundToLong() else 0L
        val leftMs = usToMs(maxOf(0L, tUs - spanUs))
        val rightMs = usToMs(tUs + spanUs)

        // 3. Draw Minimal Time Ruler (Compact & Micro Dots/Ticks)
        val labelStepSec = when {
            pxPerSec >= 60.0 -> 1L
            pxPerSec >= 25.0 -> 2L
            pxPerSec >= 10.0 -> 5L
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

            // Draw Minor Sub-Ticks between seconds
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

        // 4. Draw Main Track Clips & Filmstrip Cues
        val mainTop = rulerHeightPx
        val mainClipTop = mainTop + 4f * density
        val mainClipHeight = mainTrackHeightPx - 8f * density
        val mainClipBottom = mainClipTop + mainClipHeight
        val n = snapshot.main.n

        if (n > 0) {
            val k0 = maxOf(0, snapshot.main.lastStartAtOrBefore(leftMs) - 1)
            val k1 = minOf(n - 1, snapshot.main.lastStartAtOrBefore(rightMs) + 1)

            // Frame cell step duration for internal filmstrip cues
            val cellStepUs = when {
                pxPerSec >= 120.0 -> 500_000L   // 0.5s per cell (60–200dp wide)
                pxPerSec >= 40.0 -> 1_000_000L  // 1.0s per cell (40–120dp wide)
                pxPerSec >= 15.0 -> 2_000_000L  // 2.0s per cell (30–80dp wide)
                else -> 5_000_000L              // 5.0s per cell
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

                // Fill Clip Body
                drawPath(
                    path = clipPath,
                    color = clipBgColor
                )

                // ---------------------------------------------------------------------------------
                // FILMSTRIP MOTION CUES: Frame cells, alternating shading, sprockets & second labels
                // ---------------------------------------------------------------------------------
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

                    // 1. Alternating subtle shade strip for visual motion tracking
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

                    // 2. Vertical frame-box divider line
                    if (cellX > leftX + 2f && cellX < rightX - 2f) {
                        drawLine(
                            color = clipCellDividerColor,
                            start = Offset(cellX, mainClipTop),
                            end = Offset(cellX, mainClipBottom),
                            strokeWidth = 1f * density
                        )
                    }

                    // 3. Filmstrip top & bottom sprocket notch ticks
                    if (cellX in leftX..rightX) {
                        drawLine(
                            color = clipSprocketColor,
                            start = Offset(cellX, mainClipTop),
                            end = Offset(cellX, mainClipTop + 4f * density),
                            strokeWidth = 1.5f * density
                        )
                        drawLine(
                            color = clipSprocketColor,
                            start = Offset(cellX, mainClipBottom - 4f * density),
                            end = Offset(cellX, mainClipBottom),
                            strokeWidth = 1.5f * density
                        )
                    }

                    val midX = (cellX + nextCellX) / 2f
                    if (midX > leftX + 4f && midX < rightX - 4f) {
                        drawLine(
                            color = clipSprocketColor,
                            start = Offset(midX, mainClipTop),
                            end = Offset(midX, mainClipTop + 2.5f * density),
                            strokeWidth = 1f * density
                        )
                        drawLine(
                            color = clipSprocketColor,
                            start = Offset(midX, mainClipBottom - 2.5f * density),
                            end = Offset(midX, mainClipBottom),
                            strokeWidth = 1f * density
                        )
                    }

                    // 4. Clip title and local second markers (+1s, +2s, +3s...)
                    if (cellIdx == 0L) {
                        val textLeft = leftX + 8f * density
                        val textTop = mainClipTop + 6f * density
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
                        val textTop = mainClipTop + 6f * density
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

                // Draw Border or Inset Selection Outline
                if (selection == TimelineSelection.ClipSelected(k)) {
                    val halfStroke = selectionStroke.width / 2f
                    val sLeft = leftX + halfStroke
                    val sRight = maxOf(sLeft, rightX - halfStroke)
                    val sTop = mainClipTop + halfStroke
                    val sBottom = mainClipBottom - halfStroke
                    buildClipCornerPath(
                        path = selectionPath,
                        left = sLeft,
                        top = sTop,
                        right = sRight,
                        bottom = sBottom,
                        roundLeft = roundLeft,
                        roundRight = roundRight,
                        radius = maxOf(0f, cornerRadiusPx - halfStroke)
                    )
                    drawPath(
                        path = selectionPath,
                        color = clipSelectedBorderColor,
                        style = selectionStroke
                    )
                } else {
                    drawPath(
                        path = clipPath,
                        color = clipBorderColor,
                        style = unselectedStroke
                    )
                }

                // If followed by another clip with a cut, draw 1px vertical junction line
                if (k < n - 1 && snapshot.main.types[k] == TransitionType.NONE) {
                    drawLine(
                        color = clipBorderColor,
                        start = Offset(rightX, mainClipTop),
                        end = Offset(rightX, mainClipBottom),
                        strokeWidth = 1f * density
                    )
                }
            }

            // Draw Junction Badges
            val junctionCenterY = mainTop + mainTrackHeightPx / 2f
            for (j in k0 until minOf(k1 + 1, n - 1)) {
                val centerMs = (snapshot.main.startMs[j + 1] + snapshot.main.endMs[j]) / 2L
                val jx = zoomState.timeUsToX(msToUs(centerMs), offsetPx, halfWidthInt, pxPerUs)
                if (jx in -50f..size.width + 50f) {
                    val isTransition = snapshot.main.types[j] != TransitionType.NONE
                    val jColor = if (selection == TimelineSelection.JunctionSelected(j)) {
                        junctionSelectedColor
                    } else if (isTransition) {
                        junctionBgColor
                    } else {
                        junctionNoneColor
                    }

                    drawCircle(
                        color = jColor,
                        radius = junctionRadiusPx,
                        center = Offset(jx, junctionCenterY)
                    )
                    if (selection == TimelineSelection.JunctionSelected(j)) {
                        drawCircle(
                            color = Color.White,
                            radius = junctionRadiusPx,
                            center = Offset(jx, junctionCenterY),
                            style = selectionStroke
                        )
                    }
                }
            }
        }

        // 5. Draw Feature Lanes
        val lanesTop = mainTop + mainTrackHeightPx + (8.dp.value * density)
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

        // 6. Center Fixed Playhead Line (Rule R2: Always at size.width / 2)
        drawLine(
            color = playheadColor,
            start = Offset(halfWidthPx, rulerHeightPx),
            end = Offset(halfWidthPx, size.height - 8f * density),
            strokeWidth = playheadStrokeWidth,
            cap = StrokeCap.Round
        )
    }
}
