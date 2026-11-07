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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.core.feature.LanePainter
import com.flowcut.app.core.feature.PainterCtx
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.playback.PlaybackController
import com.flowcut.app.core.playback.PlayheadReader
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
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
        val str = "%02d:%02d".format(m, s)
        if (map.size >= maxEntries) {
            val oldest = map.keys.firstOrNull()
            if (oldest != null) map.remove(oldest)
        }
        map[second] = str
        return str
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

    // Pre-allocated styling constants (Rule R10)
    val rulerHeightPx = remember(density) { 32.dp.value * density }
    val mainTrackHeightPx = remember(density) { 64.dp.value * density }
    val junctionRadiusPx = remember(density) { 12.dp.value * density }
    val junctionDiameterPx = remember(density) { 24.dp.value * density }
    val cornerRadius = remember(density) { CornerRadius(6.dp.value * density) }
    val selectionStroke = remember(density) { Stroke(width = 2.5f * density) }
    val playheadStroke = remember(density) { Stroke(width = 2f * density) }

    // Pre-allocated colors
    val bgColor = remember { Color(0xFF141414) }
    val rulerBgColor = remember { Color(0xFF1E1E1E) }
    val rulerTickColor = remember { Color(0xFF555555) }
    val rulerTextColor = remember { Color(0xFF999999) }
    val clipBgColor = remember { Color(0xFF2C3E50) }
    val clipBorderColor = remember { Color(0xFF34495E) }
    val clipSelectedBorderColor = remember { Color(0xFFFFD700) }
    val junctionBgColor = remember { Color(0xFFE67E22) }
    val junctionNoneColor = remember { Color(0xFF3E4A56) }
    val junctionSelectedColor = remember { Color(0xFFFF5722) }
    val playheadColor = remember { Color(0xFFE74C3C) }
    val timeTextStyle = remember { TextStyle(fontSize = 10.sp, color = Color(0xFF888888)) }
    val timeStringCache = remember { TimeStringCache(maxEntries = 200) }

    // Reusable playhead head path
    val playheadTriangle = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val velocityTracker = VelocityTracker()
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    var isDragging = false
                    var isZooming = false
                    val startPos = down.position
                    val touchSlop = viewConfiguration.touchSlop
                    var lastDragPos = down.position
                    var downTimeMillis = System.currentTimeMillis()

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Main)
                        val pointerCount = event.changes.size

                        if (pointerCount >= 2) {
                            // Two-finger pinch: zoom
                            isZooming = true
                            val zoomFactor = event.calculateZoom()
                            if (zoomFactor != 1f) {
                                zoomState.updateZoom(zoomFactor)
                                onWakeFrameDriver()
                            }
                            event.changes.forEach { it.consume() }
                        } else if (pointerCount == 1 && !isZooming) {
                            val change = event.changes[0]
                            if (change.pressed) {
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val dragDistance = abs(change.position.x - startPos.x)

                                if (!isDragging && dragDistance > touchSlop) {
                                    isDragging = true
                                    controller.beginScrub()
                                    onWakeFrameDriver()
                                }

                                if (isDragging) {
                                    val dx = change.position.x - lastDragPos.x
                                    val pxPerUs = zoomState.pxPerUs(density)
                                    if (pxPerUs > 0.0) {
                                        // Drag left moves playhead forward
                                        val deltaUs = (-dx / pxPerUs).roundToLong()
                                        controller.scrubBy(deltaUs)
                                        onWakeFrameDriver()
                                    }
                                    lastDragPos = change.position
                                    change.consume()
                                }
                            } else {
                                // Pointer up
                                if (isDragging) {
                                    val pxPerUs = zoomState.pxPerUs(density)
                                    val velocityPx = velocityTracker.calculateVelocity().x
                                    val velocityUsPerSec = if (pxPerUs > 0.0) (-velocityPx / pxPerUs) else 0.0
                                    controller.endScrub(velocityUsPerSec, pxPerUs)
                                    onWakeFrameDriver()
                                } else if (!isZooming) {
                                    // Single tap or long press detection
                                    val duration = System.currentTimeMillis() - downTimeMillis
                                    val halfWidthPx = size.width / 2f
                                    val hit = TimelineHitTester.hitTest(
                                        tapX = change.position.x,
                                        tapY = change.position.y,
                                        playheadUs = playhead.timeUs,
                                        halfWidthPx = halfWidthPx,
                                        density = density,
                                        snapshot = holder.current,
                                        zoomState = zoomState
                                    )
                                    if (duration > viewConfiguration.longPressTimeoutMillis) {
                                        onLongPress(hit)
                                    } else {
                                        onSelect(hit)
                                    }
                                }
                                break
                            }
                        } else {
                            break
                        }
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
        val halfWidthPx = size.width / 2f
        val offsetPx = (tUs * pxPerUs).roundToLong()

        // 1. Draw Background
        drawRect(color = bgColor)

        // 2. Visible Range Culling (Rule R7)
        val spanUs = if (pxPerUs > 0.0) (halfWidthPx / pxPerUs).roundToLong() else 0L
        val leftMs = usToMs(maxOf(0L, tUs - spanUs))
        val rightMs = usToMs(tUs + spanUs)

        // 3. Draw Ruler
        drawRect(
            color = rulerBgColor,
            topLeft = Offset.Zero,
            size = Size(size.width, rulerHeightPx)
        )

        val leftSec = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(leftMs)
        val rightSec = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(rightMs) + 1L
        for (sec in leftSec..rightSec) {
            val secUs = java.util.concurrent.TimeUnit.SECONDS.toMicros(sec)
            val secX = zoomState.timeUsToX(secUs, tUs, halfWidthPx, density)
            if (secX in -50f..size.width + 50f) {
                val isMajor = sec % 5L == 0L
                val tickHeight = if (isMajor) rulerHeightPx * 0.4f else rulerHeightPx * 0.2f
                drawLine(
                    color = rulerTickColor,
                    start = Offset(secX, rulerHeightPx - tickHeight),
                    end = Offset(secX, rulerHeightPx),
                    strokeWidth = 1f * density
                )
                if (isMajor) {
                    val label = timeStringCache.get(sec)
                    val textX = secX + 4f * density
                    val textY = 4f * density
                    if (textX > -60f && textX < size.width && textY < size.height) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = label,
                            topLeft = Offset(textX, textY),
                            style = timeTextStyle
                        )
                    }
                }
            }
        }

        // 4. Draw Main Track Clips & Junctions
        val mainTop = rulerHeightPx
        val n = snapshot.main.n
        if (n > 0) {
            val k0 = maxOf(0, snapshot.main.lastStartAtOrBefore(leftMs) - 1)
            val k1 = minOf(n - 1, snapshot.main.lastStartAtOrBefore(rightMs) + 1)

            for (k in k0..k1) {
                val clipStartMs = snapshot.main.startMs[k]
                val clipEndMs = snapshot.main.endMs[k]
                val leftX = zoomState.timeUsToX(msToUs(clipStartMs), tUs, halfWidthPx, density)
                val rightX = zoomState.timeUsToX(msToUs(clipEndMs), tUs, halfWidthPx, density)
                val clipWidth = maxOf(2f, rightX - leftX)

                // Draw Clip Body
                drawRoundRect(
                    color = clipBgColor,
                    topLeft = Offset(leftX, mainTop + 4f * density),
                    size = Size(clipWidth, mainTrackHeightPx - 8f * density),
                    cornerRadius = cornerRadius
                )
                drawRoundRect(
                    color = if (selection == TimelineSelection.ClipSelected(k)) clipSelectedBorderColor else clipBorderColor,
                    topLeft = Offset(leftX, mainTop + 4f * density),
                    size = Size(clipWidth, mainTrackHeightPx - 8f * density),
                    cornerRadius = cornerRadius,
                    style = selectionStroke
                )
            }

            // Draw Junction Badges
            val junctionCenterY = mainTop + mainTrackHeightPx / 2f
            for (j in k0 until minOf(k1 + 1, n - 1)) {
                val centerMs = (snapshot.main.startMs[j + 1] + snapshot.main.endMs[j]) / 2L
                val jx = zoomState.timeUsToX(msToUs(centerMs), tUs, halfWidthPx, density)
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
                            zoomState.timeUsToX(msToUs(timeMs), tUs, halfWidthPx, density)

                        override fun timeUsToX(timeUs: Long): Float =
                            zoomState.timeUsToX(timeUs, tUs, halfWidthPx, density)

                        override fun drawRect(x: Float, y: Float, width: Float, height: Float, colorArgb: Int) {
                            this@Canvas.drawRect(
                                color = Color(colorArgb),
                                topLeft = Offset(x, lanesTop + y),
                                size = Size(width, height)
                            )
                        }

                        override fun drawText(text: String, x: Float, y: Float, colorArgb: Int, fontSizeSp: Float) {
                            val textY = lanesTop + y
                            if (x > -60f && x < size.width && textY < size.height) {
                                this@Canvas.drawText(
                                    textMeasurer = textMeasurer,
                                    text = text,
                                    topLeft = Offset(x, textY),
                                    style = TextStyle(fontSize = fontSizeSp.sp, color = Color(colorArgb))
                                )
                            }
                        }
                    }
                    painter.draw(ctx, laneIdx, from, to)
                }
            }
        }

        // 6. Center Fixed Playhead Line (Rule R2: Always at size.width / 2)
        drawLine(
            color = playheadColor,
            start = Offset(halfWidthPx, 0f),
            end = Offset(halfWidthPx, size.height),
            strokeWidth = 2f * density
        )

        // Playhead Top Indicator
        playheadTriangle.reset()
        playheadTriangle.moveTo(halfWidthPx - 6f * density, 0f)
        playheadTriangle.lineTo(halfWidthPx + 6f * density, 0f)
        playheadTriangle.lineTo(halfWidthPx, 10f * density)
        playheadTriangle.close()
        drawPath(path = playheadTriangle, color = playheadColor)
    }
}
