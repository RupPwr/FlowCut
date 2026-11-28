package com.flowcut.app.core.playback

import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerFlingTest {

    private fun createController(durationMs: Long = 120_000L): PlaybackController {
        val clip = Clip(
            id = "clip-long",
            path = "/storage/long.mp4",
            durationMs = durationMs,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            timeMap = TimeMap(inUs = 0L, outUs = durationMs * 1_000L)
        )
        val holder = SnapshotHolder()
        holder.current = TimelineSnapshot(1L, Project(clips = listOf(clip)))
        return PlaybackController(holder = holder)
    }

    @Test
    fun slowFingerLiftDoesNotEnterFling() {
        val controller = createController()
        val pxPerUs = 0.000225 // 225 px/s (75 dp/s @ 3x density)
        controller.beginScrub()
        // 30 px/s < MIN_FLING_VELOCITY_PX_PER_SEC (55 px/s)
        controller.endScrub(velocityUsPerSec = 30.0 / pxPerUs, pxPerUs = pxPerUs)
        assertEquals(PlayMode.PAUSED, controller.playhead.mode)
    }

    @Test
    fun mediumFlickSettlesNaturallyUnderOneSecond() {
        val controller = createController()
        val pxPerUs = 0.000225 // 225 px/s
        controller.seekTo(10_000_000L)
        var frameNs = 1_000_000_000L
        controller.tick(frameNs)

        controller.beginScrub()
        // 2500 px/s medium thumb flick
        controller.endScrub(velocityUsPerSec = 2500.0 / pxPerUs, pxPerUs = pxPerUs)
        assertEquals(PlayMode.FLINGING, controller.playhead.mode)

        val stepNs = 16_666_667L // 60fps
        var frames = 0
        val startUs = controller.playhead.timeUs
        while (controller.playhead.mode == PlayMode.FLINGING && frames < 300) {
            frameNs += stepNs
            controller.tick(frameNs)
            frames++
        }

        assertEquals(PlayMode.PAUSED, controller.playhead.mode)
        val elapsedSec = frames / 60.0
        assertTrue("Expected medium flick to settle in 0.45..1.10s, got ${elapsedSec}s", elapsedSec in 0.45..1.10)
        assertTrue("Playhead should advance during forward fling", controller.playhead.timeUs > startUs + 1_000_000L)
    }

    @Test
    fun hardFlickAndSpikesClampAndSettleCleanly() {
        val controller = createController()
        val pxPerUs = 0.000225
        controller.seekTo(5_000_000L)
        var frameNs = 1_000_000_000L
        controller.tick(frameNs)

        controller.beginScrub()
        // Erratic 50,000 px/s touch spike should be clamped to MAX_FLING_VELOCITY_PX_PER_SEC
        controller.endScrub(velocityUsPerSec = 50_000.0 / pxPerUs, pxPerUs = pxPerUs)
        assertEquals(PlayMode.FLINGING, controller.playhead.mode)

        val stepNs = 16_666_667L
        var frames = 0
        while (controller.playhead.mode == PlayMode.FLINGING && frames < 300) {
            frameNs += stepNs
            controller.tick(frameNs)
            frames++
        }

        assertEquals(PlayMode.PAUSED, controller.playhead.mode)
        val elapsedSec = frames / 60.0
        assertTrue("Expected clamped hard flick to settle in <= 1.45s, got ${elapsedSec}s", elapsedSec <= 1.45)
    }

    @Test
    fun backwardFlingClampsAtZeroAndStops() {
        val controller = createController()
        val pxPerUs = 0.000225
        controller.seekTo(500_000L)
        var frameNs = 1_000_000_000L
        controller.tick(frameNs)

        controller.beginScrub()
        controller.endScrub(velocityUsPerSec = -4000.0 / pxPerUs, pxPerUs = pxPerUs)
        assertEquals(PlayMode.FLINGING, controller.playhead.mode)

        val stepNs = 16_666_667L
        var frames = 0
        while (controller.playhead.mode == PlayMode.FLINGING && frames < 120) {
            frameNs += stepNs
            controller.tick(frameNs)
            frames++
        }

        assertEquals(PlayMode.PAUSED, controller.playhead.mode)
        assertEquals(0L, controller.playhead.timeUs)
    }
}
