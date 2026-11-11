package com.flowcut.app.ui.timeline

import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import kotlin.math.abs

/**
 * Unified Focus & Selection State Machine (VideoGuru / CapCut Reference Architecture).
 * Single source of truth driving vertical layout, toolbar, and preview gizmo.
 */
sealed interface TimelineFocusState {
    data object S0_NONE : TimelineFocusState
    data object S1_PIP_GROUP : TimelineFocusState
    data class S2_PIP_ITEM(val itemId: String) : TimelineFocusState
    data class S3_STICKER_ITEM(val itemId: String) : TimelineFocusState
    data class S4_TEXT_ITEM(val itemId: String) : TimelineFocusState
    data object S5_AUDIO_GROUP : TimelineFocusState
    data class S6_AUDIO_ITEM(val itemId: String) : TimelineFocusState

    val currentSelectedItemId: String?
        get() = when (this) {
            is S2_PIP_ITEM -> itemId
            is S3_STICKER_ITEM -> itemId
            is S4_TEXT_ITEM -> itemId
            is S6_AUDIO_ITEM -> itemId
            else -> null
        }
}

/**
 * Result of a timeline hit-test (Section 8).
 */
sealed interface TimelineSelection {
    data object None : TimelineSelection
    data class ClipSelected(val index: Int) : TimelineSelection
    data class JunctionSelected(val index: Int) : TimelineSelection
    data class LaneItemSelected(val laneIndex: Int, val itemIndex: Int) : TimelineSelection
    data class TrimHandleLeft(val index: Int) : TimelineSelection
    data class TrimHandleRight(val index: Int) : TimelineSelection
    data object TrackMuteClicked : TimelineSelection
    data object CoverClicked : TimelineSelection
    data object AddClipClicked : TimelineSelection
    data object PipGroupClicked : TimelineSelection
    data class OverlaySelected(val itemId: String) : TimelineSelection
    data object AudioGroupClicked : TimelineSelection
}

/**
 * Pure O(log n) hit-tester for TimelineCanvas (Section 8 & VideoGuru Timeline System).
 */
object TimelineHitTester {

    const val JUNCTION_BOX_SIZE_DP = 20f
    const val JUNCTION_TOUCH_TARGET_DP = 20f
    const val RULER_HEIGHT_DP = 22f
    const val ROW_A_PINS_HEIGHT_DP = 30f
    const val ROW_B_BAR_HEIGHT_DP = 30f
    const val MAIN_TRACK_HEIGHT_DP = 48f
    const val ROW_D_PIP_COLLAPSED_HEIGHT_DP = 32f
    const val ROW_D_PIP_EXPANDED_LANE_HEIGHT_DP = 36f
    const val ROW_D_PIP_EXPANDED_LANE_STEP_DP = 42f
    const val ROW_D_PIP_EXPANDED_TOTAL_HEIGHT_DP = 168f
    const val ROW_E_SOUND_BAR_HEIGHT_DP = 26f
    const val COVER_BOX_WIDTH_DP = 36f
    const val COVER_BOX_HEIGHT_DP = 42f
    const val ADD_BTN_WIDTH_DP = 28f
    const val ADD_BTN_HEIGHT_DP = 36f
    const val PIN_WIDTH_DP = 24f
    const val PIN_HEIGHT_DP = 26f
    const val TRIM_HANDLE_WIDTH_DP = 15f
    const val TRIM_HANDLE_HEIGHT_DP = 38f
    const val TRIM_HANDLE_TOUCH_TARGET_DP = 28f
    const val LANE_HEIGHT_DP = 40f
    const val LANES_PADDING_TOP_DP = 8f

    enum class TrimHandleType {
        NONE,
        LEFT,
        RIGHT
    }

    enum class OverlayTrimEdge {
        NONE,
        LEFT,
        RIGHT
    }

    /**
     * Hit-tests Left or Right trim handle of a currently selected clip in Row C.
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
        rowCTopDp: Float = RULER_HEIGHT_DP + ROW_A_PINS_HEIGHT_DP,
        mainTrackHeightDp: Float = MAIN_TRACK_HEIGHT_DP,
        handleWidthDp: Float = TRIM_HANDLE_WIDTH_DP
    ): TrimHandleType {
        val n = snapshot.main.n
        if (selectedClipIndex !in 0 until n) return TrimHandleType.NONE

        val rowCTopPx = rowCTopDp * density
        val mainTrackHeightPx = mainTrackHeightDp * density
        val rowCBottomPx = rowCTopPx + mainTrackHeightPx

        val vertMarginPx = 8f * density
        if (tapY < rowCTopPx - vertMarginPx || tapY > rowCBottomPx + vertMarginPx) {
            return TrimHandleType.NONE
        }

        val clipStartUs = msToUs(snapshot.main.startMs[selectedClipIndex])
        val clipEndUs = msToUs(snapshot.main.endMs[selectedClipIndex])
        val leftX = zoomState.timeUsToX(clipStartUs, playheadUs, halfWidthPx, density)
        val rightX = zoomState.timeUsToX(clipEndUs, playheadUs, halfWidthPx, density)

        val handleWidthPx = handleWidthDp * density

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

    /**
     * Hit-tests Left or Right chevron trim handle of a currently selected OverlayItem
     * in Row B (for Text/Sticker) or Row D (for PIP Video/Image).
     */
    fun hitTestOverlayTrimHandle(
        tapX: Float,
        tapY: Float,
        selectedOverlayId: String?,
        overlayItems: List<OverlayItem>,
        playheadUs: Long,
        halfWidthPx: Float,
        density: Float,
        zoomState: TimelineZoomState,
        focusMode: TimelineFocusState = TimelineFocusState.S0_NONE,
        handleWidthDp: Float = TRIM_HANDLE_WIDTH_DP
    ): OverlayTrimEdge {
        if (selectedOverlayId == null) return OverlayTrimEdge.NONE
        val item = overlayItems.firstOrNull { it.id == selectedOverlayId } ?: return OverlayTrimEdge.NONE

        val isTextOrSticker = item.type == LayerType.TEXT || item.type == LayerType.STICKER
        val itemTopPx: Float
        val itemBottomPx: Float

        if (isTextOrSticker) {
            // Row B bounds: immediately below Row A
            val rowBTopPx = (RULER_HEIGHT_DP + ROW_A_PINS_HEIGHT_DP) * density
            val rowBBottomPx = rowBTopPx + (ROW_B_BAR_HEIGHT_DP * density)
            itemTopPx = rowBTopPx
            itemBottomPx = rowBBottomPx
        } else {
            // Row D PIP lane bounds
            val isRowBOpen = focusMode is TimelineFocusState.S3_STICKER_ITEM || focusMode is TimelineFocusState.S4_TEXT_ITEM
            val rowBHeightDp = if (isRowBOpen) ROW_B_BAR_HEIGHT_DP else 0f
            val rowCTopDp = RULER_HEIGHT_DP + ROW_A_PINS_HEIGHT_DP + rowBHeightDp
            val rowDTopDp = rowCTopDp + MAIN_TRACK_HEIGHT_DP + 4f
            val lane = item.laneIndex.coerceIn(0, 3)
            val pipLaneTopDp = rowDTopDp + 3f + (lane * ROW_D_PIP_EXPANDED_LANE_STEP_DP)
            itemTopPx = pipLaneTopDp * density
            itemBottomPx = itemTopPx + (ROW_D_PIP_EXPANDED_LANE_HEIGHT_DP * density)
        }

        val vertMarginPx = 8f * density
        if (tapY < itemTopPx - vertMarginPx || tapY > itemBottomPx + vertMarginPx) {
            return OverlayTrimEdge.NONE
        }

        val leftX = zoomState.timeUsToX(item.startUs, playheadUs, halfWidthPx, density)
        val rightX = zoomState.timeUsToX(item.endUs, playheadUs, halfWidthPx, density)
        val handleWidthPx = handleWidthDp * density

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
                    OverlayTrimEdge.LEFT
                } else {
                    OverlayTrimEdge.RIGHT
                }
            }
            hitLeft -> OverlayTrimEdge.LEFT
            hitRight -> OverlayTrimEdge.RIGHT
            else -> OverlayTrimEdge.NONE
        }
    }

    fun hitTestTrackMuteButton(
        tapX: Float,
        tapY: Float,
        playheadUs: Long,
        halfWidthPx: Float,
        density: Float,
        zoomState: TimelineZoomState,
        rowCTopPx: Float,
        mainTrackHeightPx: Float
    ): Boolean {
        val clip0LeftX = zoomState.timeUsToX(0L, playheadUs, halfWidthPx, density)
        val coverRight = clip0LeftX - (10f * density)
        val coverLeft = coverRight - (COVER_BOX_WIDTH_DP * density)
        val muteBtnCenterX = coverLeft - (20f * density)
        val muteBtnCenterY = rowCTopPx + mainTrackHeightPx / 2f

        val touchRadiusPx = 24f * density
        val dx = abs(tapX - muteBtnCenterX)
        val dy = abs(tapY - muteBtnCenterY)
        return dx <= touchRadiusPx && dy <= touchRadiusPx
    }

    fun hitTestCoverButton(
        tapX: Float,
        tapY: Float,
        playheadUs: Long,
        halfWidthPx: Float,
        density: Float,
        zoomState: TimelineZoomState,
        rowCTopPx: Float,
        mainTrackHeightPx: Float
    ): Boolean {
        val clip0LeftX = zoomState.timeUsToX(0L, playheadUs, halfWidthPx, density)
        val coverRight = clip0LeftX - (10f * density)
        val coverLeft = coverRight - (COVER_BOX_WIDTH_DP * density)
        val coverTop = rowCTopPx + (mainTrackHeightPx - (COVER_BOX_HEIGHT_DP * density)) / 2f
        val coverBottom = coverTop + (COVER_BOX_HEIGHT_DP * density)
        val margin = 6f * density

        return tapX in (coverLeft - margin)..(coverRight + margin) &&
                tapY in (coverTop - margin)..(coverBottom + margin)
    }

    fun hitTestAddClipButton(
        tapX: Float,
        tapY: Float,
        totalUs: Long,
        playheadUs: Long,
        halfWidthPx: Float,
        density: Float,
        zoomState: TimelineZoomState,
        rowCTopPx: Float,
        mainTrackHeightPx: Float
    ): Boolean {
        val lastClipRightX = zoomState.timeUsToX(totalUs, playheadUs, halfWidthPx, density)
        val addBtnLeft = lastClipRightX + (10f * density)
        val addBtnRight = addBtnLeft + (ADD_BTN_WIDTH_DP * density)
        val addBtnTop = rowCTopPx + (mainTrackHeightPx - (ADD_BTN_HEIGHT_DP * density)) / 2f
        val addBtnBottom = addBtnTop + (ADD_BTN_HEIGHT_DP * density)
        val margin = 8f * density

        return tapX in (addBtnLeft - margin)..(addBtnRight + margin) &&
                tapY in (addBtnTop - margin)..(addBtnBottom + margin)
    }

    /**
     * Hit-tests Row A (Pins Row): Pin markers (24.dp x 26.dp) and duration underlines.
     */
    fun hitTestRowAPins(
        tapX: Float,
        tapY: Float,
        overlayItems: List<OverlayItem>,
        playheadUs: Long,
        halfWidthPx: Float,
        density: Float,
        zoomState: TimelineZoomState,
        rowATopPx: Float,
        rowABottomPx: Float
    ): OverlayItem? {
        val margin = 4f * density
        if (tapY < rowATopPx - margin || tapY > rowABottomPx + margin) return null

        val pinTouchRadiusPx = 18f * density
        var bestItem: OverlayItem? = null
        var minDistance = Float.MAX_VALUE

        // 1. First priority: Direct pin marker hit (near startX)
        for (idx in overlayItems.indices) {
            val item = overlayItems[idx]
            if (item.type != LayerType.TEXT && item.type != LayerType.STICKER) continue
            val startX = zoomState.timeUsToX(item.startUs, playheadUs, halfWidthPx, density)
            val dx = abs(tapX - startX)
            if (dx <= pinTouchRadiusPx && dx < minDistance) {
                minDistance = dx
                bestItem = item
            }
        }
        if (bestItem != null) return bestItem

        // 2. Second priority: Duration underline hit (startX .. endX)
        for (idx in overlayItems.indices.reversed()) {
            val item = overlayItems[idx]
            if (item.type != LayerType.TEXT && item.type != LayerType.STICKER) continue
            val startX = zoomState.timeUsToX(item.startUs, playheadUs, halfWidthPx, density)
            val endX = zoomState.timeUsToX(item.endUs, playheadUs, halfWidthPx, density)
            if (tapX in (startX - 8f * density)..(endX + 8f * density)) {
                return item
            }
        }
        return null
    }

    /**
     * Master hit test evaluating priorities across all rows:
     * - Row A: Pins Row
     * - Row B: Dynamic Selected Text/Sticker Bar
     * - Row C: Main Track (Track Mute, Cover, Clips, Junctions, Add "+")
     * - Row D: PIP Area (Thin Lines in S0/S3/S4 vs Expanded 4 lanes in S1/S2)
     * - Row E: Sound collection bar
     */
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
        selectedClipIndex: Int = -1,
        selectedOverlayId: String? = null,
        overlayItems: List<OverlayItem> = emptyList(),
        focusMode: TimelineFocusState = TimelineFocusState.S0_NONE
    ): TimelineSelection {
        val n = snapshot.main.n
        val totalUs = msToUs(snapshot.totalMs)

        // Calculate dynamic vertical row coordinates
        val rulerHeightPx = rulerHeightDp * density
        val rowATopPx = rulerHeightPx
        val rowAPinsHeightPx = ROW_A_PINS_HEIGHT_DP * density
        val rowABottomPx = rowATopPx + rowAPinsHeightPx

        val isRowBOpen = focusMode is TimelineFocusState.S3_STICKER_ITEM || focusMode is TimelineFocusState.S4_TEXT_ITEM
        val rowBTopPx = rowABottomPx
        val rowBHeightPx = if (isRowBOpen) ROW_B_BAR_HEIGHT_DP * density else 0f
        val rowBBottomPx = rowBTopPx + rowBHeightPx

        val rowCTopPx = rowBBottomPx + (2f * density)
        val mainTrackHeightPx = mainTrackHeightDp * density
        val rowCBottomPx = rowCTopPx + mainTrackHeightPx

        val isPipExpanded = focusMode is TimelineFocusState.S1_PIP_GROUP || focusMode is TimelineFocusState.S2_PIP_ITEM
        val rowDTopPx = rowCBottomPx + (4f * density)
        val rowDHeightPx = if (isPipExpanded) {
            ROW_D_PIP_EXPANDED_TOTAL_HEIGHT_DP * density
        } else {
            ROW_D_PIP_COLLAPSED_HEIGHT_DP * density
        }
        val rowDBottomPx = rowDTopPx + rowDHeightPx

        val rowETopPx = rowDBottomPx + (4f * density)
        val rowEHeightPx = ROW_E_SOUND_BAR_HEIGHT_DP * density
        val rowEBottomPx = rowETopPx + rowEHeightPx

        // -----------------------------------------------------------------------------------------
        // PRIORITY -1: Track Audio Mute/Unmute Button (Anchored left of clip 0 in Row C)
        // -----------------------------------------------------------------------------------------
        if (hitTestTrackMuteButton(tapX, tapY, playheadUs, halfWidthPx, density, zoomState, rowCTopPx, mainTrackHeightPx)) {
            return TimelineSelection.TrackMuteClicked
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY -0.5: Cover Box (Anchored left of clip 0 in Row C)
        // -----------------------------------------------------------------------------------------
        if (hitTestCoverButton(tapX, tapY, playheadUs, halfWidthPx, density, zoomState, rowCTopPx, mainTrackHeightPx)) {
            return TimelineSelection.CoverClicked
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY -0.2: Append Media Pink "+" Button (Anchored right of total duration in Row C)
        // -----------------------------------------------------------------------------------------
        if (hitTestAddClipButton(tapX, tapY, totalUs, playheadUs, halfWidthPx, density, zoomState, rowCTopPx, mainTrackHeightPx)) {
            return TimelineSelection.AddClipClicked
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY 0: Trim Handles of Currently Selected Main Clip (~28 dp touch target)
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
                rowCTopDp = (rowCTopPx / density),
                mainTrackHeightDp = mainTrackHeightDp
            )
            when (handleHit) {
                TrimHandleType.LEFT -> return TimelineSelection.TrimHandleLeft(selectedClipIndex)
                TrimHandleType.RIGHT -> return TimelineSelection.TrimHandleRight(selectedClipIndex)
                TrimHandleType.NONE -> { /* fall through */ }
            }
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY 0.5: Trim Handles of Currently Selected Overlay Item (Row B or Row D)
        // -----------------------------------------------------------------------------------------
        if (selectedOverlayId != null) {
            val overlayTrimHit = hitTestOverlayTrimHandle(
                tapX = tapX,
                tapY = tapY,
                selectedOverlayId = selectedOverlayId,
                overlayItems = overlayItems,
                playheadUs = playheadUs,
                halfWidthPx = halfWidthPx,
                density = density,
                zoomState = zoomState,
                focusMode = focusMode
            )
            if (overlayTrimHit != OverlayTrimEdge.NONE) {
                return TimelineSelection.OverlaySelected(selectedOverlayId)
            }
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY 1: Row A — Top Pins Row for Text & Stickers
        // -----------------------------------------------------------------------------------------
        val hitPin = hitTestRowAPins(
            tapX = tapX,
            tapY = tapY,
            overlayItems = overlayItems,
            playheadUs = playheadUs,
            halfWidthPx = halfWidthPx,
            density = density,
            zoomState = zoomState,
            rowATopPx = rowATopPx,
            rowABottomPx = rowABottomPx
        )
        if (hitPin != null) {
            return TimelineSelection.OverlaySelected(hitPin.id)
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY 1.5: Row B — Dynamic Selected Text / Sticker Bar
        // -----------------------------------------------------------------------------------------
        if (isRowBOpen && selectedOverlayId != null && tapY in rowBTopPx..rowBBottomPx) {
            val selItem = overlayItems.firstOrNull { it.id == selectedOverlayId }
            if (selItem != null) {
                val leftX = zoomState.timeUsToX(selItem.startUs, playheadUs, halfWidthPx, density)
                val rightX = zoomState.timeUsToX(selItem.endUs, playheadUs, halfWidthPx, density)
                if (tapX in leftX..rightX) {
                    return TimelineSelection.OverlaySelected(selItem.id)
                }
            }
        }

        val tapTimeUs = zoomState.xToTimeUs(tapX, playheadUs, halfWidthPx, density)
        val tapTimeMs = usToMs(tapTimeUs)

        // -----------------------------------------------------------------------------------------
        // PRIORITY 2: Transition Junction Nodes in Row C
        // -----------------------------------------------------------------------------------------
        val junctionCenterY = rowCTopPx + mainTrackHeightPx / 2f
        val junctionTouchHalfSizePx = (JUNCTION_BOX_SIZE_DP / 2f) * density
        if (n > 1) {
            val k = snapshot.main.lastStartAtOrBefore(tapTimeMs)
            val minJ = maxOf(0, k - 1)
            val maxJ = minOf(n - 2, k + 1)
            for (j in minJ..maxJ) {
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
        // PRIORITY 3: Main Track Clips in Row C
        // -----------------------------------------------------------------------------------------
        if (n > 0 && tapY in rowCTopPx..rowCBottomPx) {
            val k = snapshot.main.lastStartAtOrBefore(tapTimeMs)
            if (k in 0 until n) {
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
        // PRIORITY 4: Row D — 4-Lane PIP Area (Thin Lines vs. Expanded Thumbnail Lanes)
        // -----------------------------------------------------------------------------------------
        if (tapY in rowDTopPx..rowDBottomPx) {
            val pipItems = overlayItems.filter { it.type == LayerType.VIDEO || it.type == LayerType.IMAGE }
            if (isPipExpanded) {
                // Expanded 4 thumbnail lanes: check each lane
                val relativeY = tapY - rowDTopPx - (3f * density)
                val laneIndex = (relativeY / (ROW_D_PIP_EXPANDED_LANE_STEP_DP * density)).toInt().coerceIn(0, 3)
                val candidateItem = pipItems.firstOrNull {
                    it.laneIndex.coerceIn(0, 3) == laneIndex &&
                            tapTimeUs in it.startUs..it.endUs
                }
                if (candidateItem != null) {
                    return TimelineSelection.OverlaySelected(candidateItem.id)
                }
                // Tapping expanded PIP area outside specific item keeps PIP group focused
                return TimelineSelection.PipGroupClicked
            } else {
                // Collapsed 4 thin lines: check if directly tapping a PIP bar
                for (lane in 0..3) {
                    val lineY = rowDTopPx + (3f * density) + (lane * 8f * density)
                    if (abs(tapY - lineY) <= 8f * density) {
                        val hitItem = pipItems.firstOrNull {
                            it.laneIndex.coerceIn(0, 3) == lane &&
                                    tapTimeUs in it.startUs..it.endUs
                        }
                        if (hitItem != null) {
                            return TimelineSelection.OverlaySelected(hitItem.id)
                        }
                    }
                }
                // Tapping anywhere in collapsed PIP zone expands to S1_PIP_GROUP
                return TimelineSelection.PipGroupClicked
            }
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY 5: Row E — Sound Collection Bar
        // -----------------------------------------------------------------------------------------
        if (tapY in rowETopPx..rowEBottomPx) {
            return TimelineSelection.AudioGroupClicked
        }

        // -----------------------------------------------------------------------------------------
        // PRIORITY 6: Feature Lanes (below Row E)
        // -----------------------------------------------------------------------------------------
        val lanesTopPx = rowEBottomPx + (LANES_PADDING_TOP_DP * density)
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
