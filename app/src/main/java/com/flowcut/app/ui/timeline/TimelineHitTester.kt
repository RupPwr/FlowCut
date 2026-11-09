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
    data object TrackMuteClicked : TimelineSelection
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

    const val JUNCTION_BOX_SIZE_DP = 20f
    const val JUNCTION_TOUCH_TARGET_DP = 20f
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
     * Hit-tests Left or Right trim handle of a currently selected clip with a generous touch target.
     * Left hit zone: [leftX - handleWidthPx - 16.dp .. leftX + 12.dp]
     * Right hit zone: [rightX - 12.dp .. rightX + handleWidthPx + 16.dp]
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
        handleWidthDp: Float = TRIM_HANDLE_WIDTH_DP
    ): TrimHandleType {
        val n = snapshot.main.n
        if (selectedClipIndex !in 0 until n) return TrimHandleType.NONE

        val rulerHeightPx = rulerHeightDp * density
        val mainTrackHeightPx = mainTrackHeightDp * density
        val mainTrackBottomPx = rulerHeightPx + mainTrackHeightPx

        // Generous vertical touch tolerance around the main track
        val vertMarginPx = 8f * density
        if (tapY < rulerHeightPx - vertMarginPx || tapY > mainTrackBottomPx + vertMarginPx) {
            return TrimHandleType.NONE
        }

        val clipStartUs = msToUs(snapshot.main.startMs[selectedClipIndex])
        val clipEndUs = msToUs(snapshot.main.endMs[selectedClipIndex])
        val leftX = zoomState.timeUsToX(clipStartUs, playheadUs, halfWidthPx, density)
        val rightX = zoomState.timeUsToX(clipEndUs, playheadUs, halfWidthPx, density)

        val handleWidthPx = handleWidthDp * density

        // Exact touch hit zones per Bug #2:
        val leftHitMin = leftX - handleWidthPx - (16f * density)
        val leftHitMax = leftX + (12f * density)
        val rightHitMin = rightX - (12f * density)
        val rightHitMax = rightX + handleWidthPx + (16f * density)

        val hitLeft = tapX in leftHitMin..leftHitMax
        val hitRight = tapX in rightHitMin..rightHitMax

        return when {
            hitLeft && hitRight -> {
                val leftHandleCenterX = leftX - (handleWidthPx / 2f)
                val rightHandleCenterX = rightX + (handleWidthPx / 2f)
                if (abs(tapX - leftHandleCenterX) <= abs(tapX - rightHandleCenterX)) {
                    TrimHandleType.LEFT
                } else {
                    TrimHandleType.RIGHT
                }
            }
            hitLeft -> TrimHandleType.LEFT
            hitRight -> TrimHandleType.RIGHT
            else -> TrimHandleType.NONE
        }
    }

    fun hitTestTrackMuteButton(
        tapX: Float,
        tapY: Float,
        playheadUs: Long,
        halfWidthPx: Float,
        density: Float,
        zoomState: TimelineZoomState,
        rulerHeightDp: Float = RULER_HEIGHT_DP,
        mainTrackHeightDp: Float = MAIN_TRACK_HEIGHT_DP
    ): Boolean {
        val clip0LeftX = zoomState.timeUsToX(0L, playheadUs, halfWidthPx, density)
        val muteBtnCenterX = clip0LeftX - (32f * density)
        val rulerHeightPx = rulerHeightDp * density
        val mainTrackHeightPx = mainTrackHeightDp * density
        val muteBtnCenterY = rulerHeightPx + mainTrackHeightPx / 2f

        val touchRadiusPx = 24f * density // 48dp touch target
        val dx = abs(tapX - muteBtnCenterX)
        val dy = abs(tapY - muteBtnCenterY)
        return dx <= touchRadiusPx && dy <= touchRadiusPx
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

        // -----------------------------------------------------------------------------------------
        // PRIORITY -1: Track Audio Mute/Unmute Button (Anchored left of clip 0)
        // -----------------------------------------------------------------------------------------
        if (hitTestTrackMuteButton(tapX, tapY, playheadUs, halfWidthPx, density, zoomState, rulerHeightDp, mainTrackHeightDp)) {
            return TimelineSelection.TrackMuteClicked
        }

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
        val junctionTouchHalfSizePx = (JUNCTION_BOX_SIZE_DP / 2f) * density
        val tapTimeUs = zoomState.xToTimeUs(tapX, playheadUs, halfWidthPx, density)
        val tapTimeMs = usToMs(tapTimeUs)

        // -----------------------------------------------------------------------------------------
        // PRIORITY 1: Junction / Transition nodes (strictly inside the 20 dp icon bounds)
        // -----------------------------------------------------------------------------------------
        if (n > 1) {
            val k = snapshot.main.lastStartAtOrBefore(tapTimeMs)
            val minJ = maxOf(0, k - 1)
            val maxJ = minOf(n - 2, k + 1)
            for (j in minJ..maxJ) {
                // Auto-hide: ignore junctions flanking the currently selected clip
                if (selectedClipIndex >= 0 && (j == selectedClipIndex - 1 || j == selectedClipIndex)) {
                    continue
                }
                val centerMs = (snapshot.main.startMs[j + 1] + snapshot.main.endMs[j]) / 2L
                val junctionCenterPx = zoomState.timeUsToX(msToUs(centerMs), playheadUs, halfWidthPx, density)
                val dx = abs(tapX - junctionCenterPx)
                val dy = abs(tapY - junctionCenterY)

                if (dx <= junctionTouchHalfSizePx && dy <= junctionTouchHalfSizePx) {
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

