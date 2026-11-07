package com.flowcut.app.core

import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Lane
import com.flowcut.app.core.model.LaneItem
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.model.Transition
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.ui.timeline.TimelineHitTester
import com.flowcut.app.ui.timeline.TimelineSelection
import com.flowcut.app.ui.timeline.TimelineZoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class TimelineHitAndZoomTest {

    private class TestLaneItem(
        override val id: String,
        override val startMs: Long,
        override val durationMs: Long
    ) : LaneItem

    private lateinit var snapshot: TimelineSnapshot
    private lateinit var zoomState: TimelineZoomState
    private val density = 2.0f
    private val halfWidthPx = 540f // 1080p screen width / 2

    @Before
    fun setUp() {
        // Main track: clip0 [0..5000ms], clip1 [4000..8000ms], clip2 [7200..13200ms]
        // Transitions: j0 between 0-1 (1000ms, center 4500ms), j1 between 1-2 (800ms, center 7600ms)
        val clips = listOf(
            Clip("c0", "/path/0.mp4", 5000L, 1080, 1920, 0),
            Clip("c1", "/path/1.mp4", 4000L, 1080, 1920, 0),
            Clip("c2", "/path/2.mp4", 6000L, 1080, 1920, 0)
        )
        val transitions = listOf(
            Transition(TransitionType.SLIDE, 1000L),
            Transition(TransitionType.FADE, 800L)
        )
        val laneItems = listOf(
            TestLaneItem("t0", 1000L, 2000L),  // [1000..3000ms]
            TestLaneItem("t1", 6000L, 3000L)   // [6000..9000ms]
        )
        val lanes = listOf(
            Lane("lane_text", "text", laneItems)
        )
        val project = Project(clips = clips, transitions = transitions, lanes = lanes)
        snapshot = TimelineSnapshot(1L, project)
        zoomState = TimelineZoomState(initialDpPerSecond = 100.0)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Coordinate Math & Zoom Tests
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testPlayheadAlwaysAtCenterUnderAllZooms() {
        val playheadUs = 4_500_000L // 4.5s

        // Test at default zoom
        val xDefault = zoomState.timeUsToX(playheadUs, playheadUs, halfWidthPx, density)
        assertEquals("Playhead must be at halfWidthPx", halfWidthPx, xDefault, 0.001f)

        // Test at min zoom (0.4 dp/s)
        zoomState.dpPerSecond = TimelineZoomState.MIN_DP_PER_SECOND
        val xMin = zoomState.timeUsToX(playheadUs, playheadUs, halfWidthPx, density)
        assertEquals("Playhead must remain at halfWidthPx at min zoom", halfWidthPx, xMin, 0.001f)

        // Test at max zoom (400.0 dp/s)
        zoomState.dpPerSecond = TimelineZoomState.MAX_DP_PER_SECOND
        val xMax = zoomState.timeUsToX(playheadUs, playheadUs, halfWidthPx, density)
        assertEquals("Playhead must remain at halfWidthPx at max zoom", halfWidthPx, xMax, 0.001f)
    }

    @Test
    fun testZoomBoundsAndClamping() {
        // Initial zoom clamped to bounds
        val zoom = TimelineZoomState(initialDpPerSecond = 50.0)
        assertEquals(50.0, zoom.dpPerSecond, 0.001)

        // Zoom out drastically
        zoom.updateZoom(0.0001f)
        assertEquals(TimelineZoomState.MIN_DP_PER_SECOND, zoom.dpPerSecond, 0.001)

        // Zoom in drastically
        zoom.updateZoom(1_000_000f)
        assertEquals(TimelineZoomState.MAX_DP_PER_SECOND, zoom.dpPerSecond, 0.001)
    }

    @Test
    fun testCoordinateRoundTrip() {
        val playheadUs = 3_000_000L
        val testTimesUs = listOf(0L, 500_000L, 1_000_000L, 3_000_000L, 7_500_000L, 13_200_000L)

        for (t in testTimesUs) {
            val x = zoomState.timeUsToX(t, playheadUs, halfWidthPx, density)
            val convertedBackUs = zoomState.xToTimeUs(x, playheadUs, halfWidthPx, density)
            // Accuracy within 5 microseconds due to floating-point pxPerUs rounding
            assertTrue(
                "Round-trip error for $t us -> $convertedBackUs us must be <= 5 us",
                abs(t - convertedBackUs) <= 5L
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Hit Testing: Priority 1 (Junction wins over underlying clip)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testPriority1JunctionWinsOverClip() {
        val playheadUs = 0L
        // Junction 0 center is at 4500ms
        val j0CenterUs = msToUs(4500L)
        val j0CenterPx = zoomState.timeUsToX(j0CenterUs, playheadUs, halfWidthPx, density)
        val mainTrackCenterY = (TimelineHitTester.RULER_HEIGHT_DP + TimelineHitTester.MAIN_TRACK_HEIGHT_DP / 2f) * density

        // Tap right on junction center
        val hitCenter = TimelineHitTester.hitTest(
            tapX = j0CenterPx,
            tapY = mainTrackCenterY,
            playheadUs = playheadUs,
            halfWidthPx = halfWidthPx,
            density = density,
            snapshot = snapshot,
            zoomState = zoomState
        )
        assertTrue("Tap on junction center must select JunctionSelected", hitCenter is TimelineSelection.JunctionSelected)
        assertEquals(0, (hitCenter as TimelineSelection.JunctionSelected).index)

        // Tap within 20dp of junction center (within 24dp touch radius)
        val hitNearby = TimelineHitTester.hitTest(
            tapX = j0CenterPx + (15f * density),
            tapY = mainTrackCenterY + (10f * density),
            playheadUs = playheadUs,
            halfWidthPx = halfWidthPx,
            density = density,
            snapshot = snapshot,
            zoomState = zoomState
        )
        assertTrue("Tap within 24dp radius must still select JunctionSelected", hitNearby is TimelineSelection.JunctionSelected)
        assertEquals(0, (hitNearby as TimelineSelection.JunctionSelected).index)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Hit Testing: Priority 2 (Main Track Clip)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testPriority2ClipSelection() {
        val playheadUs = 0L
        val mainTrackCenterY = (TimelineHitTester.RULER_HEIGHT_DP + TimelineHitTester.MAIN_TRACK_HEIGHT_DP / 2f) * density

        // Tap on Clip 0 safely away from junction: t = 2000ms
        val c0Px = zoomState.timeUsToX(msToUs(2000L), playheadUs, halfWidthPx, density)
        val hitC0 = TimelineHitTester.hitTest(
            tapX = c0Px,
            tapY = mainTrackCenterY,
            playheadUs = playheadUs,
            halfWidthPx = halfWidthPx,
            density = density,
            snapshot = snapshot,
            zoomState = zoomState
        )
        assertTrue(hitC0 is TimelineSelection.ClipSelected)
        assertEquals(0, (hitC0 as TimelineSelection.ClipSelected).index)

        // Tap on Clip 1: t = 6000ms (between j0 at 4500ms and j1 at 7600ms)
        val c1Px = zoomState.timeUsToX(msToUs(6000L), playheadUs, halfWidthPx, density)
        val hitC1 = TimelineHitTester.hitTest(
            tapX = c1Px,
            tapY = mainTrackCenterY,
            playheadUs = playheadUs,
            halfWidthPx = halfWidthPx,
            density = density,
            snapshot = snapshot,
            zoomState = zoomState
        )
        assertTrue(hitC1 is TimelineSelection.ClipSelected)
        assertEquals(1, (hitC1 as TimelineSelection.ClipSelected).index)

        // Tap on Clip 2: t = 10000ms
        val c2Px = zoomState.timeUsToX(msToUs(10000L), playheadUs, halfWidthPx, density)
        val hitC2 = TimelineHitTester.hitTest(
            tapX = c2Px,
            tapY = mainTrackCenterY,
            playheadUs = playheadUs,
            halfWidthPx = halfWidthPx,
            density = density,
            snapshot = snapshot,
            zoomState = zoomState
        )
        assertTrue(hitC2 is TimelineSelection.ClipSelected)
        assertEquals(2, (hitC2 as TimelineSelection.ClipSelected).index)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Hit Testing: Priority 3 (Lane Items)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testPriority3LaneItemSelection() {
        val playheadUs = 0L
        val mainTrackBottomPx = (TimelineHitTester.RULER_HEIGHT_DP + TimelineHitTester.MAIN_TRACK_HEIGHT_DP) * density
        val laneY = mainTrackBottomPx + (15f * density) // inside lane 0

        // Tap on lane item 0: [1000..3000ms], tap at 2000ms
        val item0Px = zoomState.timeUsToX(msToUs(2000L), playheadUs, halfWidthPx, density)
        val hitItem0 = TimelineHitTester.hitTest(
            tapX = item0Px,
            tapY = laneY,
            playheadUs = playheadUs,
            halfWidthPx = halfWidthPx,
            density = density,
            snapshot = snapshot,
            zoomState = zoomState
        )
        assertTrue(hitItem0 is TimelineSelection.LaneItemSelected)
        val itemSel0 = hitItem0 as TimelineSelection.LaneItemSelected
        assertEquals(0, itemSel0.laneIndex)
        assertEquals(0, itemSel0.itemIndex)

        // Tap on lane item 1: [6000..9000ms], tap at 7000ms
        val item1Px = zoomState.timeUsToX(msToUs(7000L), playheadUs, halfWidthPx, density)
        val hitItem1 = TimelineHitTester.hitTest(
            tapX = item1Px,
            tapY = laneY,
            playheadUs = playheadUs,
            halfWidthPx = halfWidthPx,
            density = density,
            snapshot = snapshot,
            zoomState = zoomState
        )
        assertTrue(hitItem1 is TimelineSelection.LaneItemSelected)
        val itemSel1 = hitItem1 as TimelineSelection.LaneItemSelected
        assertEquals(0, itemSel1.laneIndex)
        assertEquals(1, itemSel1.itemIndex)

        // Tap in lane gap: 4000ms (between 3000ms and 6000ms)
        val gapPx = zoomState.timeUsToX(msToUs(4000L), playheadUs, halfWidthPx, density)
        val hitGap = TimelineHitTester.hitTest(
            tapX = gapPx,
            tapY = laneY,
            playheadUs = playheadUs,
            halfWidthPx = halfWidthPx,
            density = density,
            snapshot = snapshot,
            zoomState = zoomState
        )
        assertEquals(TimelineSelection.None, hitGap)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Hit Testing: Ruler and Out of Bounds
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testRulerAndOutOfBoundsReturnNone() {
        val playheadUs = 0L
        val rulerY = 10f * density // Inside time ruler

        val hitRuler = TimelineHitTester.hitTest(
            tapX = 100f,
            tapY = rulerY,
            playheadUs = playheadUs,
            halfWidthPx = halfWidthPx,
            density = density,
            snapshot = snapshot,
            zoomState = zoomState
        )
        assertEquals(TimelineSelection.None, hitRuler)
    }
}

