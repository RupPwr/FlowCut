package com.flowcut.app.ui.timeline

import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Zoom state and coordinate mapping for TimelineCanvas (Section 8).
 * Manages dpPerSecond clamped to [0.4, 400.0] and provides single-rounding,
 * zero-allocation coordinate conversions between time (us) and Canvas pixels (X).
 */
class TimelineZoomState(
    initialDpPerSecond: Double = DEFAULT_DP_PER_SECOND
) {
    companion object {
        const val MIN_DP_PER_SECOND = 0.4    // Fits 15 min (900s) on screen
        const val MAX_DP_PER_SECOND = 400.0  // Maximum zoom
        const val DEFAULT_DP_PER_SECOND = 50.0
        private val US_PER_SECOND = TimeUnit.SECONDS.toMicros(1L).toDouble()
    }

    @Volatile
    var dpPerSecond: Double = initialDpPerSecond.coerceIn(MIN_DP_PER_SECOND, MAX_DP_PER_SECOND)

    fun updateZoom(factor: Float) {
        dpPerSecond = (dpPerSecond * factor).coerceIn(MIN_DP_PER_SECOND, MAX_DP_PER_SECOND)
    }

    fun pxPerSecond(density: Float): Double = dpPerSecond * density

    fun pxPerUs(density: Float): Double = pxPerSecond(density) / US_PER_SECOND

    /**
     * Converts time (us) to Canvas X pixel position.
     * When tUs == playheadUs, the result is exactly halfWidthPx (the fixed center playhead line).
     */
    fun timeUsToX(tUs: Long, playheadUs: Long, halfWidthPx: Float, density: Float): Float {
        val pxUs = pxPerUs(density)
        val offsetPx = (playheadUs * pxUs).roundToLong()
        val itemPx = (tUs * pxUs).roundToLong()
        return (itemPx - offsetPx + halfWidthPx).toFloat()
    }

    /**
     * Converts a Canvas X pixel position to timeline time (us).
     */
    fun xToTimeUs(x: Float, playheadUs: Long, halfWidthPx: Float, density: Float): Long {
        val pxUs = pxPerUs(density)
        val offsetPx = (playheadUs * pxUs).roundToLong()
        val targetPx = x - halfWidthPx + offsetPx
        val targetUs = Math.round(targetPx / pxUs)
        return targetUs.coerceAtLeast(0L)
    }
}

