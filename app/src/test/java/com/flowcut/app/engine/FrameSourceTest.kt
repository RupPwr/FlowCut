package com.flowcut.app.engine

import com.flowcut.app.core.playback.PlayMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSourceTest {

    @Test
    fun `shouldReusePreviousFrame returns false when lastRenderedPtsUs is negative`() {
        assertFalse(FrameSource.shouldReusePreviousFrame(-1L, 0L, 16_666L, PlayMode.PLAYING))
        assertFalse(FrameSource.shouldReusePreviousFrame(-1L, 0L, 16_666L, PlayMode.PAUSED))
        assertFalse(FrameSource.shouldReusePreviousFrame(-1L, 0L, 16_666L, PlayMode.SCRUBBING))
        assertFalse(FrameSource.shouldReusePreviousFrame(-1L, 0L, 16_666L, PlayMode.FLINGING))
    }

    @Test
    fun `60 fps clip frame reuse decisions during playback`() {
        val duration60fpsUs = 16_666L
        val lastPtsUs = 0L

        // Exact frame start and mid-frame (prior to next frame deadline)
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 0L, duration60fpsUs, PlayMode.PLAYING))
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 5_000L, duration60fpsUs, PlayMode.PLAYING))
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 8_000L, duration60fpsUs, PlayMode.PLAYING))

        // Slight jitter backwards within tolerance (tolerance = 16666 / 4 = 4166us)
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, -2_000L, duration60fpsUs, PlayMode.PLAYING))
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, -4_000L, duration60fpsUs, PlayMode.PLAYING))

        // Large backward jump beyond tolerance
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, -10_000L, duration60fpsUs, PlayMode.PLAYING))

        // Next frame due (vsync tick at 16.6ms) - must NOT reuse previous frame
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, 16_666L, duration60fpsUs, PlayMode.PLAYING))
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, 20_000L, duration60fpsUs, PlayMode.PLAYING))
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, 33_333L, duration60fpsUs, PlayMode.PLAYING))
    }

    @Test
    fun `30 fps clip on 60 Hz display frame reuse cadence`() {
        val duration30fpsUs = 33_333L
        val lastPtsUs = 0L

        // Tick 1 (t = 0us): current frame
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 0L, duration30fpsUs, PlayMode.PLAYING))

        // Tick 2 (t = 16.666ms): on 60 Hz screen, next 30fps frame (at 33.3ms) is not due yet.
        // The first frame must be reused for the 2nd display tick!
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 16_666L, duration30fpsUs, PlayMode.PLAYING))

        // Tick 3 (t = 33.333ms): next frame is due! Must NOT reuse first frame.
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, 33_333L, duration30fpsUs, PlayMode.PLAYING))

        // Subsequent ticks ahead
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, 50_000L, duration30fpsUs, PlayMode.PLAYING))
    }

    @Test
    fun `24 fps clip on 60 Hz display frame reuse cadence`() {
        val duration24fpsUs = 41_666L
        val lastPtsUs = 0L

        // Tick 1 (t = 0us): current frame
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 0L, duration24fpsUs, PlayMode.PLAYING))

        // Tick 2 (t = 16.666ms): 24fps frame 1 (at 41.6ms) not due yet -> reuse frame 0
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 16_666L, duration24fpsUs, PlayMode.PLAYING))

        // Tick 3 (t = 33.333ms): deadline (41666 - 10416 = 31250us) reached -> do not reuse, fetch frame 1
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, 33_333L, duration24fpsUs, PlayMode.PLAYING))
    }

    @Test
    fun `non-PLAYING mode preserves legacy window`() {
        val lastPtsUs = 100_000L
        val durationUs = 16_666L // 60 fps

        // Legacy window is (localUs - 25_000L)..(localUs + 45_000L)
        // Equivalently: localUs in (lastPtsUs - 45_000L)..(lastPtsUs + 25_000L) -> 55_000L..125_000L

        // PAUSED
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 100_000L, durationUs, PlayMode.PAUSED))
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 110_000L, durationUs, PlayMode.PAUSED))
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 70_000L, durationUs, PlayMode.PAUSED))
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, 130_000L, durationUs, PlayMode.PAUSED))
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, 50_000L, durationUs, PlayMode.PAUSED))

        // SCRUBBING
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 110_000L, durationUs, PlayMode.SCRUBBING))
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, 130_000L, durationUs, PlayMode.SCRUBBING))

        // FLINGING
        assertTrue(FrameSource.shouldReusePreviousFrame(lastPtsUs, 110_000L, durationUs, PlayMode.FLINGING))
        assertFalse(FrameSource.shouldReusePreviousFrame(lastPtsUs, 130_000L, durationUs, PlayMode.FLINGING))
    }
}
