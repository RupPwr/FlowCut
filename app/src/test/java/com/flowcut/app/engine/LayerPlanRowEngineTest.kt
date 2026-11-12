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
        assertEquals(36f, TimelineHitTester.ROW_A_PINS_HEIGHT_DP, 0.01f)
        assertEquals(24f, TimelineHitTester.ROW_B_BAR_HEIGHT_DP, 0.01f)
        assertEquals(2f, TimelineHitTester.ROW_GAP_DP, 0.01f)
        assertEquals(42f, TimelineHitTester.MAIN_TRACK_HEIGHT_DP, 0.01f)
        assertEquals(24f, TimelineHitTester.LOWER_ROW_HEIGHT_DP, 0.01f)
        assertEquals(4f, TimelineHitTester.LOWER_ROW_GAP_DP, 0.01f)
        assertEquals(28f, TimelineHitTester.LOWER_ROW_STEP_DP, 0.01f)
        assertEquals(112f, TimelineHitTester.LOWER_4_ROWS_TOTAL_HEIGHT_DP, 0.01f)

        // When Row B is closed: State A Row C sits at 58..100dp, Row D at 106..218dp <= 224dp
        assertEquals(58f, TimelineHitTester.ROW_C_TOP_STATE_A_DP, 0.01f)
        assertEquals(100f, TimelineHitTester.ROW_C_BOTTOM_STATE_A_DP, 0.01f)
        assertEquals(106f, TimelineHitTester.ROW_D_TOP_STATE_A_DP, 0.01f)
        assertTrue(TimelineHitTester.ROW_D_TOP_STATE_A_DP + TimelineHitTester.LOWER_4_ROWS_TOTAL_HEIGHT_DP <= TimelineHitTester.TIMELINE_TOTAL_HEIGHT_DP)

        // When Row B is open: State B Row B sits at 60..84dp, Row C at 90..132dp, Row D at 138..164dp
        assertEquals(60f, TimelineHitTester.ROW_B_TOP_DP, 0.01f)
        assertEquals(84f, TimelineHitTester.ROW_B_BOTTOM_DP, 0.01f)
        assertEquals(90f, TimelineHitTester.ROW_C_TOP_STATE_B_DP, 0.01f)
        assertEquals(132f, TimelineHitTester.ROW_C_BOTTOM_STATE_B_DP, 0.01f)
        assertEquals(138f, TimelineHitTester.ROW_D_TOP_STATE_B_DP, 0.01f)
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
    fun testCanAddLayer_enforcesExactFinalLayerCaps() {
        assertEquals(40, getLayerCap(LayerType.TEXT))
        assertEquals(40, getLayerCap(LayerType.MOSAIC))
        assertEquals(40, getLayerCap(LayerType.STICKER))
        assertEquals(40, getLayerCap(LayerType.IMAGE))
        assertEquals(40, getLayerCap(LayerType.VIDEO))
        assertEquals(40, getLayerCap(LayerType.AUDIO))
        assertEquals(40, getLayerCap(LayerType.FILTER))
        assertEquals(40, getLayerCap(LayerType.EFFECT))

        // PIP: combined VIDEO + IMAGE cap is 40
        val pipItems39 = (0 until 39).map { i ->
            OverlayItem(
                id = "pip_$i",
                type = if (i % 2 == 0) LayerType.VIDEO else LayerType.IMAGE,
                laneIndex = i % 4,
                zIndex = i,
                startUs = i * 1_000_000L,
                endUs = (i + 1) * 1_000_000L
            )
        }
        assertTrue(canAddLayer(pipItems39, LayerType.VIDEO))
        assertTrue(canAddLayer(pipItems39, LayerType.IMAGE))
        assertTrue(canAddLayerItem(pipItems39, LayerType.VIDEO))
        assertTrue(canAddLayerItem(pipItems39, LayerType.IMAGE))

        val pipItems40 = pipItems39 + OverlayItem(
            id = "pip_39",
            type = LayerType.VIDEO,
            laneIndex = 3,
            zIndex = 39,
            startUs = 39 * 1_000_000L,
            endUs = 40 * 1_000_000L
        )
        org.junit.Assert.assertFalse(canAddLayer(pipItems40, LayerType.VIDEO))
        org.junit.Assert.assertFalse(canAddLayer(pipItems40, LayerType.IMAGE))
        org.junit.Assert.assertFalse(canAddLayerItem(pipItems40, LayerType.VIDEO))
        org.junit.Assert.assertFalse(canAddLayerItem(pipItems40, LayerType.IMAGE))

        // AUDIO: cap is 40
        val audioItems39 = (0 until 39).map { i ->
            OverlayItem(
                id = "audio_$i",
                type = LayerType.AUDIO,
                laneIndex = i % 4,
                zIndex = i,
                startUs = i * 1_000_000L,
                endUs = (i + 1) * 1_000_000L
            )
        }
        assertTrue(canAddLayerItem(audioItems39, LayerType.AUDIO))
        val audioItems40 = audioItems39 + OverlayItem(
            id = "audio_39",
            type = LayerType.AUDIO,
            laneIndex = 3,
            zIndex = 39,
            startUs = 39 * 1_000_000L,
            endUs = 40 * 1_000_000L
        )
        org.junit.Assert.assertFalse(canAddLayerItem(audioItems40, LayerType.AUDIO))

        // TEXT, MOSAIC, STICKER: cap is 40 each
        val textItems40 = (0 until 40).map { i ->
            OverlayItem(id = "text_$i", type = LayerType.TEXT, laneIndex = 0, zIndex = i, startUs = i * 1000L, endUs = (i + 1) * 1000L)
        }
        org.junit.Assert.assertFalse(canAddLayerItem(textItems40, LayerType.TEXT))
        assertTrue(canAddLayerItem(textItems40, LayerType.MOSAIC))
        assertTrue(canAddLayerItem(textItems40, LayerType.STICKER))

        val mosaicItems40 = (0 until 40).map { i ->
            OverlayItem(id = "m_$i", type = LayerType.MOSAIC, laneIndex = 0, zIndex = i, startUs = i * 1000L, endUs = (i + 1) * 1000L)
        }
        org.junit.Assert.assertFalse(canAddLayerItem(mosaicItems40, LayerType.MOSAIC))

        val stickerItems40 = (0 until 40).map { i ->
            OverlayItem(id = "s_$i", type = LayerType.STICKER, laneIndex = 0, zIndex = i, startUs = i * 1000L, endUs = (i + 1) * 1000L)
        }
        org.junit.Assert.assertFalse(canAddLayerItem(stickerItems40, LayerType.STICKER))

        // FILTER: cap is 40
        val filterItems39 = (0 until 39).map { i ->
            OverlayItem(id = "f_$i", type = LayerType.FILTER, laneIndex = 0, zIndex = i, startUs = i * 1000L, endUs = (i + 1) * 1000L)
        }
        assertTrue(canAddLayerItem(filterItems39, LayerType.FILTER))
        val filterItems40 = filterItems39 + OverlayItem(id = "f_39", type = LayerType.FILTER, laneIndex = 0, zIndex = 39, startUs = 39000L, endUs = 40000L)
        org.junit.Assert.assertFalse(canAddLayerItem(filterItems40, LayerType.FILTER))

        // EFFECT: cap is 40
        val effectItems39 = (0 until 39).map { i ->
            OverlayItem(id = "e_$i", type = LayerType.EFFECT, laneIndex = 0, zIndex = i, startUs = i * 1000L, endUs = (i + 1) * 1000L)
        }
        assertTrue(canAddLayerItem(effectItems39, LayerType.EFFECT))
        val effectItems40 = effectItems39 + OverlayItem(id = "e_39", type = LayerType.EFFECT, laneIndex = 0, zIndex = 39, startUs = 39000L, endUs = 40000L)
        org.junit.Assert.assertFalse(canAddLayerItem(effectItems40, LayerType.EFFECT))
    }

    @Test
    fun testClampItemTrimBoundsOnRow_preventsOverlapForFilterAndEffect() {
        // FILTER: two filters on the timeline
        val filter1 = OverlayItem(
            id = "f1",
            type = LayerType.FILTER,
            laneIndex = 0,
            zIndex = 1,
            startUs = 1_000_000L,
            endUs = 3_000_000L
        )
        val filter2 = OverlayItem(
            id = "f2",
            type = LayerType.FILTER,
            laneIndex = 0,
            zIndex = 2,
            startUs = 4_000_000L,
            endUs = 6_000_000L
        )
        val filterList = listOf(filter1, filter2)

        // Trimming filter2's left handle back to 2.0s should clamp at filter1.endUs (3.0s)
        val (f2Start, f2End) = clampItemTrimBoundsOnRow(
            items = filterList,
            itemId = filter2.id,
            candidateStartUs = 2_000_000L,
            candidateEndUs = 6_000_000L,
            totalProjectDurationUs = 10_000_000L
        )
        assertEquals(3_000_000L, f2Start)
        assertEquals(6_000_000L, f2End)

        // Trimming filter1's right handle forward to 5.0s should clamp at filter2.startUs (4.0s)
        val (f1Start, f1End) = clampItemTrimBoundsOnRow(
            items = filterList,
            itemId = filter1.id,
            candidateStartUs = 1_000_000L,
            candidateEndUs = 5_000_000L,
            totalProjectDurationUs = 10_000_000L
        )
        assertEquals(1_000_000L, f1Start)
        assertEquals(4_000_000L, f1End)

        // EFFECT: two effects on the timeline
        val effect1 = OverlayItem(
            id = "e1",
            type = LayerType.EFFECT,
            laneIndex = 0,
            zIndex = 1,
            startUs = 500_000L,
            endUs = 2_500_000L
        )
        val effect2 = OverlayItem(
            id = "e2",
            type = LayerType.EFFECT,
            laneIndex = 0,
            zIndex = 2,
            startUs = 3_500_000L,
            endUs = 5_500_000L
        )
        val effectList = listOf(effect1, effect2)

        // Trimming effect2's left handle back to 1.5s should clamp at effect1.endUs (2.5s)
        val (e2Start, e2End) = clampItemTrimBoundsOnRow(
            items = effectList,
            itemId = effect2.id,
            candidateStartUs = 1_500_000L,
            candidateEndUs = 5_500_000L,
            totalProjectDurationUs = 10_000_000L
        )
        assertEquals(2_500_000L, e2Start)
        assertEquals(5_500_000L, e2End)

        // Trimming effect1's right handle forward to 4.5s should clamp at effect2.startUs (3.5s)
        val (e1Start, e1End) = clampItemTrimBoundsOnRow(
            items = effectList,
            itemId = effect1.id,
            candidateStartUs = 500_000L,
            candidateEndUs = 4_500_000L,
            totalProjectDurationUs = 10_000_000L
        )
        assertEquals(500_000L, e1Start)
        assertEquals(3_500_000L, e1End)
    }
}

