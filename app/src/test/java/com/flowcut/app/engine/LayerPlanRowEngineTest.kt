package com.flowcut.app.engine

import com.flowcut.app.ui.timeline.TimelineHitTester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerPlanRowEngineTest {

    @Test
    fun testConstantTimelineLayoutHeights() {
        assertEquals(224f, TimelineHitTester.TIMELINE_TOTAL_HEIGHT_DP, 0.01f)
        assertEquals(20f, TimelineHitTester.RULER_HEIGHT_DP, 0.01f)
        assertEquals(24f, TimelineHitTester.ROW_A_PINS_HEIGHT_DP, 0.01f)
        assertEquals(26f, TimelineHitTester.ROW_B_BAR_HEIGHT_DP, 0.01f)
        assertEquals(2f, TimelineHitTester.ROW_GAP_DP, 0.01f)
        assertEquals(42f, TimelineHitTester.MAIN_TRACK_HEIGHT_DP, 0.01f)
        assertEquals(4f, TimelineHitTester.ROW_D_TOP_MARGIN_DP, 0.01f)
        assertEquals(24f, TimelineHitTester.LOWER_ROW_HEIGHT_DP, 0.01f)
        assertEquals(4f, TimelineHitTester.LOWER_ROW_GAP_DP, 0.01f)
        assertEquals(28f, TimelineHitTester.LOWER_ROW_STEP_DP, 0.01f)
        assertEquals(112f, TimelineHitTester.LOWER_4_ROWS_TOTAL_HEIGHT_DP, 0.01f)

        // When Row B is closed: Row C sits at 46..88dp, Row D at 92dp, 4 expanded rows occupy 92..204dp <= 224dp
        val rowCTopClosed = TimelineHitTester.RULER_HEIGHT_DP + TimelineHitTester.ROW_A_PINS_HEIGHT_DP + TimelineHitTester.ROW_GAP_DP
        val rowDTopClosed = rowCTopClosed + TimelineHitTester.MAIN_TRACK_HEIGHT_DP + TimelineHitTester.ROW_D_TOP_MARGIN_DP
        val lowerAreaBottomClosed = rowDTopClosed + (4 * TimelineHitTester.LOWER_ROW_STEP_DP)
        assertEquals(46f, rowCTopClosed, 0.01f)
        assertEquals(92f, rowDTopClosed, 0.01f)
        assertEquals(204f, lowerAreaBottomClosed, 0.01f)
        assertTrue(lowerAreaBottomClosed <= TimelineHitTester.TIMELINE_TOTAL_HEIGHT_DP)

        // When Row B is open: Row B sits at 46..72dp, Row C at 74..116dp, Row D at 120dp
        val rowCTopOpen = rowCTopClosed + TimelineHitTester.ROW_B_BAR_HEIGHT_DP + TimelineHitTester.ROW_GAP_DP
        val rowDTopOpen = rowCTopOpen + TimelineHitTester.MAIN_TRACK_HEIGHT_DP + TimelineHitTester.ROW_D_TOP_MARGIN_DP
        assertEquals(74f, rowCTopOpen, 0.01f)
        assertEquals(120f, rowDTopOpen, 0.01f)
        assertTrue(rowDTopOpen + TimelineHitTester.ROW_E_SOUND_BAR_HEIGHT_DP + 16f <= TimelineHitTester.TIMELINE_TOTAL_HEIGHT_DP)
    }

    @Test
    fun testFindAvailableRowOrClamp_allocatesRow0WhenEmpty() {
        val items = emptyList<OverlayItem>()
        val result = findAvailableRowOrClamp(
            items = items,
            group = LayerTrackGroup.PIP,
            desiredStartUs = 0L,
            desiredDurationUs = 3_000_000L,
            totalProjectDurationUs = 10_000_000L
        )

        assertNotNull(result)
        assertEquals(0, result!!.laneIndex)
        assertEquals(0L, result.startUs)
        assertEquals(3_000_000L, result.endUs)
    }

    @Test
    fun testFindAvailableRowOrClamp_sequentialNonOverlappingItemsStayOnRow0() {
        val item1 = OverlayItem(
            id = "pip1",
            type = LayerType.VIDEO,
            laneIndex = 0,
            zIndex = 1,
            startUs = 0L,
            endUs = 3_000_000L
        )
        val items = listOf(item1)

        // Item 2 starts right after item 1 at 3.0s -> should fit on Row 0!
        val result = findAvailableRowOrClamp(
            items = items,
            group = LayerTrackGroup.PIP,
            desiredStartUs = 3_000_000L,
            desiredDurationUs = 2_000_000L,
            totalProjectDurationUs = 10_000_000L
        )

        assertNotNull(result)
        assertEquals(0, result!!.laneIndex)
        assertEquals(3_000_000L, result.startUs)
        assertEquals(5_000_000L, result.endUs)
    }

    @Test
    fun testFindAvailableRowOrClamp_simultaneousOverlappingPacksToNextRow() {
        val item1 = OverlayItem(
            id = "pip1",
            type = LayerType.VIDEO,
            laneIndex = 0,
            zIndex = 1,
            startUs = 1_000_000L,
            endUs = 4_000_000L
        )
        val items = listOf(item1)

        // Item 2 overlaps with item 1 at 2.0s -> cannot fit on Row 0, should go to Row 1!
        val result = findAvailableRowOrClamp(
            items = items,
            group = LayerTrackGroup.PIP,
            desiredStartUs = 2_000_000L,
            desiredDurationUs = 3_000_000L,
            totalProjectDurationUs = 10_000_000L
        )

        assertNotNull(result)
        assertEquals(1, result!!.laneIndex)
        assertEquals(2_000_000L, result.startUs)
        assertEquals(5_000_000L, result.endUs)
    }

    @Test
    fun testFindAvailableRowOrClamp_clampsEndUsWhenNextItemStartsLater() {
        // Row 0 has an item at 2.0s .. 5.0s
        // Rows 1, 2, 3 are occupied throughout 0..5s
        val itemRow0 = OverlayItem(
            id = "pip0",
            type = LayerType.IMAGE,
            laneIndex = 0,
            zIndex = 1,
            startUs = 2_000_000L,
            endUs = 5_000_000L
        )
        val itemRow1 = OverlayItem(
            id = "pip1",
            type = LayerType.IMAGE,
            laneIndex = 1,
            zIndex = 2,
            startUs = 0L,
            endUs = 5_000_000L
        )
        val itemRow2 = OverlayItem(
            id = "pip2",
            type = LayerType.IMAGE,
            laneIndex = 2,
            zIndex = 3,
            startUs = 0L,
            endUs = 5_000_000L
        )
        val itemRow3 = OverlayItem(
            id = "pip3",
            type = LayerType.IMAGE,
            laneIndex = 3,
            zIndex = 4,
            startUs = 0L,
            endUs = 5_000_000L
        )
        val items = listOf(itemRow0, itemRow1, itemRow2, itemRow3)

        // Adding a 3-second item at 0.0s. Row 0 is free for 0..2.0s (>= 200ms space).
        // It should clamp endUs = 2_000_000L!
        val result = findAvailableRowOrClamp(
            items = items,
            group = LayerTrackGroup.PIP,
            desiredStartUs = 0L,
            desiredDurationUs = 3_000_000L,
            totalProjectDurationUs = 10_000_000L
        )

        assertNotNull(result)
        assertEquals(0, result!!.laneIndex)
        assertEquals(0L, result.startUs)
        assertEquals(2_000_000L, result.endUs)
    }

    @Test
    fun testFindAvailableRowOrClamp_returnsNullWhenAll4RowsOccupied() {
        // All 4 rows (0, 1, 2, 3) are occupied at 1.0s .. 4.0s
        val items = (0..3).map { row ->
            OverlayItem(
                id = "pip_$row",
                type = LayerType.VIDEO,
                laneIndex = row,
                zIndex = row + 1,
                startUs = 1_000_000L,
                endUs = 4_000_000L
            )
        }

        // Tapping Overlay at 2.0s -> all 4 tracks are occupied at 2.0s
        val result = findAvailableRowOrClamp(
            items = items,
            group = LayerTrackGroup.PIP,
            desiredStartUs = 2_000_000L,
            desiredDurationUs = 3_000_000L,
            totalProjectDurationUs = 10_000_000L
        )

        assertNull(result)
    }

    @Test
    fun testClampItemTrimBoundsOnRow_clampsLeftHandleAgainstPreviousItem() {
        val prevItem = OverlayItem(
            id = "pip_prev",
            type = LayerType.VIDEO,
            laneIndex = 0,
            zIndex = 1,
            startUs = 0L,
            endUs = 2_000_000L
        )
        val targetItem = OverlayItem(
            id = "pip_target",
            type = LayerType.VIDEO,
            laneIndex = 0,
            zIndex = 2,
            startUs = 3_000_000L,
            endUs = 5_000_000L
        )
        val items = listOf(prevItem, targetItem)

        // Dragging left handle back to 1.0s (overlaps previous item ending at 2.0s)
        val (clampedStart, clampedEnd) = clampItemTrimBoundsOnRow(
            items = items,
            itemId = targetItem.id,
            candidateStartUs = 1_000_000L,
            candidateEndUs = 5_000_000L,
            totalProjectDurationUs = 10_000_000L
        )

        assertEquals(2_000_000L, clampedStart) // Clamped at prevItem.endUs!
        assertEquals(5_000_000L, clampedEnd)
    }

    @Test
    fun testClampItemTrimBoundsOnRow_clampsRightHandleAgainstNextItem() {
        val targetItem = OverlayItem(
            id = "pip_target",
            type = LayerType.VIDEO,
            laneIndex = 1,
            zIndex = 1,
            startUs = 1_000_000L,
            endUs = 3_000_000L
        )
        val nextItem = OverlayItem(
            id = "pip_next",
            type = LayerType.VIDEO,
            laneIndex = 1,
            zIndex = 2,
            startUs = 4_000_000L,
            endUs = 6_000_000L
        )
        val items = listOf(targetItem, nextItem)

        // Dragging right handle forward to 5.0s (overlaps next item starting at 4.0s)
        val (clampedStart, clampedEnd) = clampItemTrimBoundsOnRow(
            items = items,
            itemId = targetItem.id,
            candidateStartUs = 1_000_000L,
            candidateEndUs = 5_000_000L,
            totalProjectDurationUs = 10_000_000L
        )

        assertEquals(1_000_000L, clampedStart)
        assertEquals(4_000_000L, clampedEnd) // Clamped at nextItem.startUs!
    }

    @Test
    fun testCanAddLayer_returnsTrueForPipAndAudio() {
        val items = (0..50).map { i ->
            OverlayItem(
                id = "item_$i",
                type = LayerType.VIDEO,
                laneIndex = i % 4,
                zIndex = i,
                startUs = i * 1_000_000L,
                endUs = (i + 1) * 1_000_000L
            )
        }

        // Unlimited sequential items permitted for VIDEO, IMAGE, and AUDIO
        assertTrue(canAddLayer(items, LayerType.VIDEO))
        assertTrue(canAddLayer(items, LayerType.IMAGE))
        assertTrue(canAddLayer(items, LayerType.AUDIO))
    }
}

