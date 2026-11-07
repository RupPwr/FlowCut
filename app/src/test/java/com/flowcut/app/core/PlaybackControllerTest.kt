package com.flowcut.app.core

import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.model.Transition
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.playback.MasterClock
import com.flowcut.app.core.playback.PlaybackController
import com.flowcut.app.core.playback.PlayMode
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackControllerTest {

    private lateinit var holder: SnapshotHolder
    private lateinit var clock: MasterClock
    private lateinit var controller: PlaybackController

    // Total duration: 5000 + 4000 + 6000 - 1000 - 800 = 13200 ms = 13,200,000 us
    private val totalUs: Long = msToUs(13200L)

    @Before
    fun setUp() {
        val clips = listOf(
            Clip("c0", "/path/0.mp4", 5000L, 1080, 1920, 0),
            Clip("c1", "/path/1.mp4", 4000L, 1080, 1920, 0),
            Clip("c2", "/path/2.mp4", 6000L, 1080, 1920, 0)
        )
        val transitions = listOf(
            Transition(TransitionType.SLIDE, 1000L),
            Transition(TransitionType.FADE, 800L)
        )
        val project = Project(clips = clips, transitions = transitions)
        holder = SnapshotHolder()
        holder.current = TimelineSnapshot(1L, project)
        clock = MasterClock()
        controller = PlaybackController(clock = clock, holder = holder)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Verify timeUs only changes on tick(), even after seekTo() or scrubBy()
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testTimeUsOnlyChangesOnTick() {
        assertEquals(0L, controller.playhead.timeUs)

        // Request seek to 5,000,000 us
        controller.seekTo(5_000_000L)
        assertEquals("timeUs must NOT change before tick()", 0L, controller.playhead.timeUs)
        assertTrue("needsFrames must be true when seek is pending", controller.needsFrames())

        // Tick to apply pending seek
        controller.tick(1_000_000_000L)
        assertEquals("timeUs must update on tick()", 5_000_000L, controller.playhead.timeUs)

        // Scrub without tick
        controller.beginScrub()
        controller.scrubBy(500_000L)
        assertEquals("timeUs must NOT change immediately on scrubBy", 5_000_000L, controller.playhead.timeUs)

        // Tick to apply scrub
        controller.tick(1_016_666_666L)
        assertEquals("timeUs must update after tick()", 5_500_000L, controller.playhead.timeUs)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Verify multiple scrubBy() calls within a single frame accumulate and update timeUs once on tick()
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testMultipleScrubByAccumulatesOncePerFrame() {
        controller.beginScrub()
        assertEquals(PlayMode.SCRUBBING, controller.playhead.mode)

        // Multiple touch drag deltas in a single vsync window
        controller.scrubBy(200_000L)
        controller.scrubBy(300_000L)
        controller.scrubBy(-100_000L)
        assertEquals("timeUs must stay 0 before tick", 0L, controller.playhead.timeUs)

        // One tick applies net delta (+400,000 us)
        controller.tick(1_000_000_000L)
        assertEquals(400_000L, controller.playhead.timeUs)

        // Another tick without further scrubs must NOT change timeUs
        controller.tick(1_016_666_666L)
        assertEquals(400_000L, controller.playhead.timeUs)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Verify PLAYING advances with MasterClock and auto-pauses at totalUs
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testPlayingAdvancesAndAutoPausesAtTotalUs() {
        controller.play()
        assertEquals(PlayMode.PLAYING, controller.playhead.mode)
        assertTrue(controller.needsFrames())

        // Frame 1: Anchor at 1.0s
        controller.tick(1_000_000_000L)
        assertEquals(0L, controller.playhead.timeUs)

        // Frame 2: 50 ms later (50,000,000 ns = 50,000 us)
        controller.tick(1_050_000_000L)
        assertEquals(50_000L, controller.playhead.timeUs)
        assertEquals(PlayMode.PLAYING, controller.playhead.mode)

        // Frame 3: Advance past totalUs (13,200,000 us = 13.2 s)
        // 1.0s + 14.0s = 15.0s
        controller.tick(15_000_000_000L)
        assertEquals(totalUs, controller.playhead.timeUs)
        assertEquals(PlayMode.PAUSED, controller.playhead.mode)
        assertFalse("needsFrames must be false after reaching end and auto-pausing", controller.needsFrames())
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Verify FLINGING decays velocity exponentially, stops at boundaries (0 or totalUs),
    //    and stops when speed drops below threshold
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testFlingingDecayAndBoundaryStops() {
        // --- Part A: Forward fling advances with decay ---
        controller.seekTo(5_000_000L)
        controller.tick(1_000_000_000L)
        controller.beginScrub()
        controller.endScrub(velocityUsPerSec = 1_000_000.0, pxPerUs = 1.0)
        assertEquals(PlayMode.FLINGING, controller.playhead.mode)
        assertTrue(controller.needsFrames())

        // Anchor frame for fling
        controller.tick(1_000_000_000L)
        // Frame 2: 20 ms later
        controller.tick(1_020_000_000L)
        assertTrue("timeUs must have advanced forward", controller.playhead.timeUs > 5_000_000L)
        assertEquals(PlayMode.FLINGING, controller.playhead.mode)

        // --- Part B: Forward fling hitting totalUs boundary ---
        controller.seekTo(totalUs - 50_000L)
        controller.tick(2_000_000_000L)
        controller.beginScrub()
        controller.endScrub(velocityUsPerSec = 5_000_000.0, pxPerUs = 1.0)
        assertEquals(PlayMode.FLINGING, controller.playhead.mode)

        controller.tick(2_000_000_000L)
        controller.tick(2_050_000_000L) // 50ms at 5M us/s exceeds 50,000 us margin
        assertEquals("timeUs must clamp to totalUs", totalUs, controller.playhead.timeUs)
        assertEquals("Fling hitting end boundary must transition to PAUSED", PlayMode.PAUSED, controller.playhead.mode)

        // --- Part C: Backward fling hitting 0 boundary ---
        controller.seekTo(50_000L)
        controller.tick(3_000_000_000L)
        controller.beginScrub()
        controller.endScrub(velocityUsPerSec = -5_000_000.0, pxPerUs = 1.0)
        assertEquals(PlayMode.FLINGING, controller.playhead.mode)

        controller.tick(3_000_000_000L)
        controller.tick(3_050_000_000L)
        assertEquals("timeUs must clamp to 0", 0L, controller.playhead.timeUs)
        assertEquals("Fling hitting 0 boundary must transition to PAUSED", PlayMode.PAUSED, controller.playhead.mode)

        // --- Part D: Velocity below threshold transitions directly to PAUSED ---
        controller.beginScrub()
        // 10 px/s is below threshold 20 px/s
        controller.endScrub(velocityUsPerSec = 10.0, pxPerUs = 1.0)
        assertEquals("Low velocity must not fling; transitions to PAUSED", PlayMode.PAUSED, controller.playhead.mode)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Verify generation increments on mode transitions and seeks
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testGenerationIncrementsOnModeTransitionsAndSeeks() {
        val g0 = controller.playhead.generation

        // Seek increments generation on tick
        controller.seekTo(2_000_000L)
        assertEquals(g0, controller.playhead.generation)
        controller.tick(1_000_000_000L)
        val g1 = controller.playhead.generation
        assertTrue("Generation must increment after seek applied", g1 > g0)

        // Play increments generation
        controller.play()
        val g2 = controller.playhead.generation
        assertTrue("Generation must increment on play", g2 > g1)

        // Pause increments generation
        controller.pause()
        val g3 = controller.playhead.generation
        assertTrue("Generation must increment on pause", g3 > g2)

        // Begin scrub increments generation
        controller.beginScrub()
        val g4 = controller.playhead.generation
        assertTrue("Generation must increment on beginScrub", g4 > g3)

        // End scrub increments generation
        controller.endScrub(velocityUsPerSec = 0.0)
        val g5 = controller.playhead.generation
        assertTrue("Generation must increment on endScrub", g5 > g4)
    }
}

