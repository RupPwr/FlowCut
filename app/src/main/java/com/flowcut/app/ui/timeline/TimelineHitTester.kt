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
    data class TrimHandleLeft(val index: Int) : TimelineSelection
    data class TrimHandleRight(val index: Int) : TimelineSelection
}

/**
 * Pure O(log n) hit-tester for TimelineCanvas (Section 8).
 * Evaluates touch hits with strict priority:
 * 0. Trim handles of currently selected clip (~28 dp touch target)
 * 1. Junction / transition nodes (48 dp touch target wins)
 * 2. Main track clips
 * 3. Feature lane items
 */
object TimelineHitTester {

    const val JUNCTION_TOUCH_TARGET_DP = 48f
    const val RULER_HEIGHT_DP = 22f
    const val MAIN_TRACK_HEIGHT_DP = 64f
    const val LANE_HEIGHT_DP = 40f
    const val LANES_PADDING_TOP_DP = 8f
    const val TRIM_HANDLE_WIDTH_DP = 15f
    const val TRIM_HANDLE_HEIGHT_DP = 38f
    const val TRIM_HANDLE_TOUCH_TARGET_DP = 28f

    enum class TrimHandleType {
        NONE,
        LEFT,
        RIGHT
    }

    /**
     * Hit-tests Left or Right trim handle of a currently selected clip with a generous touch target (~28.dp).
     */
    fun hitTestTrimHandle(
        tapX: Float,
        tapY: Float,
        selectedClipIndex: Int,
        playheadUs: Long,
        halfWidthPx: Float,
        density: Float,
        snapshot: TimelineSnapshot,
        zoomState: TimelineZoomState,
        rulerHeightDp: Float = RULER_HEIGHT_DP,
        mainTrackHeightDp: Float = MAIN_TRACK_HEIGHT_DP,
        touchTargetDp: Float = TRIM_HANDLE_TOUCH_TARGET_DP,
        handleWidthDp: Float = TRIM_HANDLE_WIDTH_DP
    ): TrimHandleType {
        val n = snapshot.main.n
        if (selectedClipIndex !in 0 until n) return TrimHandleType.NONE

        val rulerHeightPx = rulerHeightDp * density
        val mainTrackHeightPx = mainTrackHeightDp * density
        val mainTrackBottomPx = rulerHeightPx + mainTrackHeightPx

        // Generous vertical touch tolerance around the main track
        val vertMarginPx = 6f * density
        if (tapY < rulerHeightPx - vertMarginPx || tapY > mainTrackBottomPx + vertMarginPx) {
            return TrimHandleType.NONE
        }

        val clipStartUs = msToUs(snapshot.main.startMs[selectedClipIndex])
        val clipEndUs = msToUs(snapshot.main.endMs[selectedClipIndex])
        val leftX = zoomState.timeUsToX(clipStartUs, playheadUs, halfWidthPx, density)
        val rightX = zoomState.timeUsToX(clipEndUs, playheadUs, halfWidthPx, density)

        val handleWidthPx = handleWidthDp * density
        val halfTouchTargetPx = (touchTargetDp / 2f) * density

        // Left handle tab center is attached to leftX, protruding outward to the left: [leftX - handleWidthPx, leftX]
        val leftHandleCenterX = leftX - (handleWidthPx / 2f)
        // Right handle tab center is attached to rightX, protruding outward to the right: [rightX, rightX + handleWidthPx]
        val rightHandleCenterX = rightX + (handleWidthPx / 2f)

        val leftDist = abs(tapX - leftHandleCenterX)
        val rightDist = abs(tapX - rightHandleCenterX)

        val hitLeft = leftDist <= halfTouchTargetPx
        val hitRight = rightDist <= halfTouchTargetPx

        return when {
            hitLeft && hitRight -> if (leftDist <= rightDist) TrimHandleType.LEFT else TrimHandleType.RIGHT
            hitLeft -> TrimHandleType.LEFT
            hitRight -> TrimHandleType.RIGHT
            else -> TrimHandleType.NONE
        }
    }

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
        laneHeightDp: Float = LANE_HEIGHT_DP,
        selectedClipIndex: Int = -1
    ): TimelineSelection {
        val n = snapshot.main.n
        if (n == 0 && snapshot.lanes.isEmpty()) return TimelineSelection.None

        // -----------------------------------------------------------------------------------------
        // PRIORITY 0: Trim Handles of Currently Selected Clip (~28 dp touch target)
        // -----------------------------------------------------------------------------------------
        if (selectedClipIndex in 0 until n) {
            val handleHit = hitTestTrimHandle(
                tapX = tapX,
                tapY = tapY,
                selectedClipIndex = selectedClipIndex,
                playheadUs = playheadUs,
                halfWidthPx = halfWidthPx,
                density = density,
                snapshot = snapshot,
                zoomState = zoomState,
                rulerHeightDp = rulerHeightDp,
                mainTrackHeightDp = mainTrackHeightDp
            )
            when (handleHit) {
                TrimHandleType.LEFT -> return TimelineSelection.TrimHandleLeft(selectedClipIndex)
                TrimHandleType.RIGHT -> return TimelineSelection.TrimHandleRight(selectedClipIndex)
                TrimHandleType.NONE -> { /* fall through to normal gestures */ }
            }
        }

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

