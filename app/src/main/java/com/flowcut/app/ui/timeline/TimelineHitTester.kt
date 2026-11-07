package com.flowcut.app.ui.timeline

import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.TimelineSnapshot
import kotlin.math.abs

/**
 * Result of a timeline hit-test (Section 8).
 */
sealed interface TimelineSelection {
    object None : TimelineSelection
    data class ClipSelected(val index: Int) : TimelineSelection
    data class JunctionSelected(val index: Int) : TimelineSelection
    data class LaneItemSelected(val laneIndex: Int, val itemIndex: Int) : TimelineSelection
}

/**
 * Pure O(log n) hit-tester for TimelineCanvas (Section 8).
 * Evaluates touch hits with strict priority:
 * 1. Junction / transition nodes (48 dp touch target wins)
 * 2. Main track clips
 * 3. Feature lane items
 */
object TimelineHitTester {

    const val JUNCTION_TOUCH_TARGET_DP = 48f
    const val RULER_HEIGHT_DP = 32f
    const val MAIN_TRACK_HEIGHT_DP = 64f
    const val LANE_HEIGHT_DP = 40f
    const val LANES_PADDING_TOP_DP = 8f

    fun hitTest(
        tapX: Float,
        tapY: Float,
        playheadUs: Long,
        halfWidthPx: Float,
        density: Float,
        snapshot: TimelineSnapshot,
        zoomState: TimelineZoomState,
        rulerHeightDp: Float = RULER_HEIGHT_DP,
        mainTrackHeightDp: Float = MAIN_TRACK_HEIGHT_DP,
        laneHeightDp: Float = LANE_HEIGHT_DP
    ): TimelineSelection {
        val n = snapshot.main.n
        if (n == 0 && snapshot.lanes.isEmpty()) return TimelineSelection.None

        val rulerHeightPx = rulerHeightDp * density
        val mainTrackHeightPx = mainTrackHeightDp * density
        val mainTrackBottomPx = rulerHeightPx + mainTrackHeightPx
        val junctionCenterY = rulerHeightPx + mainTrackHeightPx / 2f
        val junctionTouchRadiusPx = (JUNCTION_TOUCH_TARGET_DP / 2f) * density
        val tapTimeUs = zoomState.xToTimeUs(tapX, playheadUs, halfWidthPx, density)
        val tapTimeMs = usToMs(tapTimeUs)

        // -----------------------------------------------------------------------------------------
        // PRIORITY 1: Junction / Transition nodes (48 dp touch target)
        // -----------------------------------------------------------------------------------------
        if (n > 1) {
            val k = snapshot.main.lastStartAtOrBefore(tapTimeMs)
            val minJ = maxOf(0, k - 1)
            val maxJ = minOf(n - 2, k + 1)
            for (j in minJ..maxJ) {
                val centerMs = (snapshot.main.startMs[j + 1] + snapshot.main.endMs[j]) / 2L
                val junctionCenterPx = zoomState.timeUsToX(msToUs(centerMs), playheadUs, halfWidthPx, density)
                val dx = abs(tapX - junctionCenterPx)
                val dy = abs(tapY - junctionCenterY)

                if (dx <= junctionTouchRadiusPx && dy <= junctionTouchRadiusPx) {
                    return TimelineSelection.JunctionSelected(j)
                }
            }
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY 2: Main Track Clips
        // -----------------------------------------------------------------------------------------
        if (n > 0 && tapY in rulerHeightPx..mainTrackBottomPx) {
            val k = snapshot.main.lastStartAtOrBefore(tapTimeMs)
            if (k in 0 until n) {
                // If tap is in overlap with next clip, pick the one whose center is closer
                if (k + 1 < n && tapTimeMs >= snapshot.main.startMs[k + 1] && tapTimeMs <= snapshot.main.endMs[k]) {
                    val centerK = (snapshot.main.startMs[k] + snapshot.main.endMs[k]) / 2L
                    val centerNext = (snapshot.main.startMs[k + 1] + snapshot.main.endMs[k + 1]) / 2L
                    return if (abs(tapTimeMs - centerNext) < abs(tapTimeMs - centerK)) {
                        TimelineSelection.ClipSelected(k + 1)
                    } else {
                        TimelineSelection.ClipSelected(k)
                    }
                }
                if (tapTimeMs in snapshot.main.startMs[k]..snapshot.main.endMs[k]) {
                    return TimelineSelection.ClipSelected(k)
                }
            }
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY 3: Lane Items
        // -----------------------------------------------------------------------------------------
        val lanesTopPx = mainTrackBottomPx + (LANES_PADDING_TOP_DP * density)
        val laneHeightPx = laneHeightDp * density
        if (tapY >= lanesTopPx && snapshot.lanes.isNotEmpty()) {
            val relativeY = tapY - lanesTopPx
            val laneIndex = (relativeY / laneHeightPx).toInt()
            if (laneIndex in snapshot.lanes.indices) {
                val itemIndex = snapshot.lanes[laneIndex].indexAt(tapTimeMs)
                if (itemIndex >= 0) {
                    return TimelineSelection.LaneItemSelected(laneIndex, itemIndex)
                }
            }
        }

        return TimelineSelection.None
    }
}

