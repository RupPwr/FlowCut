package com.flowcut.app.core

import com.flowcut.app.core.feature.FrameCtx
import com.flowcut.app.core.feature.Prefetcher
import com.flowcut.app.core.feature.RenderContributor
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Lane
import com.flowcut.app.core.model.LaneItem
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.model.Transition
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.playback.PlayMode
import com.flowcut.app.core.playback.PlayheadReader
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.DefaultPlayheadReader
import com.flowcut.app.engine.ExportEngine
import com.flowcut.app.engine.PreviewRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewResolutionTest {

    private data class DummyLaneItem(
        override val id: String,
        override val startMs: Long,
        override val durationMs: Long
    ) : LaneItem

    private class TestPlayhead(
        override var timeUs: Long = 0L,
        override var mode: PlayMode = PlayMode.PAUSED,
        override var generation: Int = 1
    ) : PlayheadReader

    private class CountingContributor(
        override val order: Int,
        override val kinds: Set<String>,
        private val throwOnCall: Boolean = false
    ) : RenderContributor {
        var callCount: Int = 0
        var lastTimeUs: Long = -1L

        override fun onFrame(f: FrameCtx) {
            callCount++
            lastTimeUs = f.tUs
            if (throwOnCall) {
                error("Simulated GL contributor explosion")
            }
        }

        override fun release() {}
    }

    private class CountingPrefetcher : Prefetcher {
        var callCount = 0
        var lastFromUs = -1L
        var lastWindowUs = -1L

        override fun prefetch(s: TimelineSnapshot, fromUs: Long, windowUs: Long) {
            callCount++
            lastFromUs = fromUs
            lastWindowUs = windowUs
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Single clip resolution, un-trimmed and normal speed
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testSingleClipResolution_Untrimmed_NormalSpeed() {
        val project = Project(
            clips = listOf(
                Clip(id = "c0", path = "/storage/v0.mp4", durationMs = 5000L, width = 1080, height = 1920, rotation = 0)
            )
        )
        val snapshot = TimelineSnapshot(1L, project)
        val holder = SnapshotHolder().apply { current = snapshot }
        val playhead = TestPlayhead(timeUs = 0L, generation = 42)

        val renderer = PreviewRenderer(holder = holder, playhead = playhead)

        // At t = 0
        val frame0 = renderer.renderFrame()
        assertEquals(0, frame0.clipAIndex)
        assertEquals("c0", frame0.clipAId)
        assertEquals(0L, frame0.clipASourceUs)
        assertFalse(frame0.isTransition)
        assertEquals(-1, frame0.clipBIndex)
        assertEquals(42, frame0.generation)

        // At t = 2000 ms (2,000,000 us)
        playhead.timeUs = 2_000_000L
        val frame1 = renderer.renderFrame()
        assertEquals(0, frame1.clipAIndex)
        assertEquals(2_000_000L, frame1.clipASourceUs)
        assertFalse(frame1.isTransition)

        // At t = 5000 ms (clamped to end of timeline)
        playhead.timeUs = 5_000_000L
        val frame2 = renderer.renderFrame()
        assertEquals(0, frame2.clipAIndex)
        assertEquals(4_999_999L, frame2.clipASourceUs)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Transition resolution: bIndex >= 0, linearP progress, and sourceUs mapping
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testTransitionResolution_ProgressAndSourceUs() {
        // Clip 0: 5000 ms, Clip 1: 4000 ms. Transition: 1000 ms SLIDE.
        // Timeline layout:
        // Clip 0: [0 .. 5000] ms
        // Clip 1 starts at 4000 ms (overlap 4000..5000 ms)
        // Total timeline = 8000 ms
        val project = Project(
            clips = listOf(
                Clip(id = "c0", path = "/v0.mp4", durationMs = 5000L, width = 1080, height = 1920, rotation = 0),
                Clip(id = "c1", path = "/v1.mp4", durationMs = 4000L, width = 1080, height = 1920, rotation = 0)
            ),
            transitions = listOf(
                Transition(type = TransitionType.SLIDE, durationMs = 1000L)
            )
        )
        val holder = SnapshotHolder().apply { current = TimelineSnapshot(1L, project) }
        val playhead = TestPlayhead()
        val renderer = PreviewRenderer(holder = holder, playhead = playhead)

        // Before transition: t = 2000 ms
        playhead.timeUs = 2_000_000L
        val fBefore = renderer.renderFrame()
        assertEquals(0, fBefore.clipAIndex)
        assertEquals(2_000_000L, fBefore.clipASourceUs)
        assertFalse(fBefore.isTransition)
        assertEquals(-1, fBefore.clipBIndex)
        assertEquals(TransitionType.NONE, fBefore.transitionType)
        assertEquals(0f, fBefore.linearP, 0.001f)

        // Inside transition: t = 4500 ms (halfway through the 1000 ms transition)
        playhead.timeUs = 4_500_000L
        val fTrans = renderer.renderFrame()
        assertTrue(fTrans.isTransition)
        assertEquals(0, fTrans.clipAIndex)
        assertEquals("c0", fTrans.clipAId)
        assertEquals(4_500_000L, fTrans.clipASourceUs)

        assertEquals(1, fTrans.clipBIndex)
        assertEquals("c1", fTrans.clipBId)
        // Clip 1 startMs is 4000 ms, so localUs in clip 1 is 4500 - 4000 = 500 ms = 500,000 us
        assertEquals(500_000L, fTrans.clipBSourceUs)
        assertEquals(TransitionType.SLIDE, fTrans.transitionType)
        assertEquals(0.5f, fTrans.linearP, 0.001f)

        // After transition: t = 6000 ms (solo on clip 1)
        playhead.timeUs = 6_000_000L
        val fAfter = renderer.renderFrame()
        assertFalse(fAfter.isTransition)
        assertEquals(1, fAfter.clipAIndex)
        assertEquals("c1", fAfter.clipAId)
        // 6000 - 4000 = 2000 ms = 2,000,000 us
        assertEquals(2_000_000L, fAfter.clipASourceUs)
        assertEquals(-1, fAfter.clipBIndex)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Trimmed and speed-adjusted clips via Rule R11 TimeMap.sourceUs
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testTrimmedAndSpeedAdjustedClips_TimeMap() {
        // Clip 0: trimmed inUs = 1s, outUs = 4s (source length 3s). Speed 1x -> durationMs = 3000
        val map0 = TimeMap(inUs = 1_000_000L, outUs = 4_000_000L, speedNum = 1, speedDen = 1)
        val clip0 = Clip(id = "c0", path = "/v0.mp4", durationMs = 3000L, width = 1080, height = 1920, rotation = 0, timeMap = map0)

        // Clip 1: 2x speed (speedNum = 2, speedDen = 1). inUs = 0, outUs = 4s. Timeline duration = 2s = 2000 ms
        val map1 = TimeMap(inUs = 0L, outUs = 4_000_000L, speedNum = 2, speedDen = 1)
        val clip1 = Clip(id = "c1", path = "/v1.mp4", durationMs = 2000L, width = 1080, height = 1920, rotation = 0, timeMap = map1)

        // Clip 2: 0.5x slow-mo (speedNum = 1, speedDen = 2). inUs = 2s, outUs = 3s. Timeline duration = 2s = 2000 ms
        val map2 = TimeMap(inUs = 2_000_000L, outUs = 3_000_000L, speedNum = 1, speedDen = 2)
        val clip2 = Clip(id = "c2", path = "/v2.mp4", durationMs = 2000L, width = 1080, height = 1920, rotation = 0, timeMap = map2)

        val project = Project(
            clips = listOf(clip0, clip1, clip2),
            transitions = listOf(Transition(), Transition())
        )
        val holder = SnapshotHolder().apply { current = TimelineSnapshot(1L, project) }
        val playhead = TestPlayhead()
        val renderer = PreviewRenderer(holder = holder, playhead = playhead)

        // Clip 0 at t = 500 ms (local 500_000 us). Source should be inUs (1_000_000) + 500_000 = 1_500_000 us
        playhead.timeUs = 500_000L
        val f0 = renderer.renderFrame()
        assertEquals(0, f0.clipAIndex)
        assertEquals(1_500_000L, f0.clipASourceUs)

        // Clip 1 at timeline t = 3500 ms (starts at 3000 ms, so localUs = 500_000 us).
        // 2x speed: sourceUs = 0 + 500_000 * 2 / 1 = 1_000_000 us
        playhead.timeUs = 3_500_000L
        val f1 = renderer.renderFrame()
        assertEquals(1, f1.clipAIndex)
        assertEquals(1_000_000L, f1.clipASourceUs)

        // Clip 2 at timeline t = 6000 ms (starts at 5000 ms, so localUs = 1_000_000 us).
        // 0.5x slow-mo: sourceUs = 2_000_000 + 1_000_000 * 1 / 2 = 2_500_000 us
        playhead.timeUs = 6_000_000L
        val f2 = renderer.renderFrame()
        assertEquals(2, f2.clipAIndex)
        assertEquals(2_500_000L, f2.clipASourceUs)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Contributor activation filtering: ONLY invoked when lane kind has active item >= 0
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testContributorActivationFiltering() {
        val lanes = listOf(
            Lane(id = "l_text", kind = "text", items = listOf(DummyLaneItem("t1", startMs = 1000L, durationMs = 2000L))),
            Lane(id = "l_sticker", kind = "sticker", items = listOf(DummyLaneItem("s1", startMs = 4000L, durationMs = 1500L)))
        )
        val project = Project(
            clips = listOf(Clip(id = "c0", path = "/v0.mp4", durationMs = 7000L, width = 1080, height = 1920, rotation = 0)),
            lanes = lanes
        )
        val holder = SnapshotHolder().apply { current = TimelineSnapshot(1L, project) }
        val playhead = TestPlayhead()
        val renderer = PreviewRenderer(holder = holder, playhead = playhead)

        val textContributor = CountingContributor(order = 10, kinds = setOf("text"))
        val stickerContributor = CountingContributor(order = 20, kinds = setOf("sticker"))

        renderer.registerContributor(textContributor)
        renderer.registerContributor(stickerContributor)

        // Frame 1: t = 500 ms -> Neither lane has an active item
        playhead.timeUs = 500_000L
        renderer.renderFrame()
        assertEquals(0, textContributor.callCount)
        assertEquals(0, stickerContributor.callCount)

        // Frame 2: t = 1500 ms -> Text lane is active [1000..3000], sticker is inactive
        playhead.timeUs = 1_500_000L
        renderer.renderFrame()
        assertEquals(1, textContributor.callCount)
        assertEquals(1_500_000L, textContributor.lastTimeUs)
        assertEquals(0, stickerContributor.callCount) // Zero cost for inactive contributor

        // Frame 3: t = 3500 ms -> Gap between items, neither active
        playhead.timeUs = 3_500_000L
        renderer.renderFrame()
        assertEquals(1, textContributor.callCount)
        assertEquals(0, stickerContributor.callCount)

        // Frame 4: t = 4500 ms -> Sticker lane is active [4000..5500], text is inactive
        playhead.timeUs = 4_500_000L
        renderer.renderFrame()
        assertEquals(1, textContributor.callCount) // Unchanged
        assertEquals(1, stickerContributor.callCount)
        assertEquals(4_500_000L, stickerContributor.lastTimeUs)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Contributor failure isolation: throwing contributor is caught and disabled
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testContributorFailureIsolation() {
        val lanes = listOf(
            Lane(id = "l_text", kind = "text", items = listOf(DummyLaneItem("t1", startMs = 0L, durationMs = 5000L)))
        )
        val project = Project(
            clips = listOf(Clip(id = "c0", path = "/v0.mp4", durationMs = 5000L, width = 1080, height = 1920, rotation = 0)),
            lanes = lanes
        )
        val holder = SnapshotHolder().apply { current = TimelineSnapshot(1L, project) }
        val playhead = TestPlayhead(timeUs = 1_000_000L)
        val renderer = PreviewRenderer(holder = holder, playhead = playhead)

        val failingContributor = CountingContributor(order = 10, kinds = setOf("text"), throwOnCall = true)
        val healthyContributor = CountingContributor(order = 20, kinds = setOf("text"), throwOnCall = false)

        renderer.registerContributor(failingContributor)
        renderer.registerContributor(healthyContributor)

        assertFalse(renderer.isContributorDisabled(failingContributor))
        assertFalse(renderer.isContributorDisabled(healthyContributor))

        // First frame: failingContributor throws, should be caught and disabled. Healthy contributor still runs!
        val frame1 = renderer.renderFrame()
        assertNotNull(frame1)
        assertEquals(0, frame1.clipAIndex)
        assertEquals(1, failingContributor.callCount)
        assertEquals(1, healthyContributor.callCount)
        assertTrue(renderer.isContributorDisabled(failingContributor))
        assertFalse(renderer.isContributorDisabled(healthyContributor))

        // Second frame: failingContributor was disabled, so it is skipped immediately (callCount remains 1).
        // Healthy contributor runs again!
        playhead.timeUs = 2_000_000L
        val frame2 = renderer.renderFrame()
        assertNotNull(frame2)
        assertEquals(1, failingContributor.callCount) // NOT called again!
        assertEquals(2, healthyContributor.callCount)
    }

    // ---------------------------------------------------------------------------------------------
    // 6. ExportEngine frame resolution helper
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testExportEngineFrameResolution() {
        val map0 = TimeMap(inUs = 500_000L, outUs = 3_500_000L, speedNum = 1, speedDen = 1)
        val project = Project(
            clips = listOf(
                Clip(id = "c0", path = "/v0.mp4", durationMs = 3000L, width = 1080, height = 1920, rotation = 0, timeMap = map0),
                Clip(id = "c1", path = "/v1.mp4", durationMs = 4000L, width = 1080, height = 1920, rotation = 0)
            ),
            transitions = listOf(
                Transition(type = TransitionType.ZOOM, durationMs = 1000L)
            )
        )
        val snapshot = TimelineSnapshot(1L, project)
        val activeSet = ActiveSet(snapshot.lanes.size)

        // Frame at t = 0 (frameIndex = 0 at 30 fps)
        val res0 = ExportEngine.resolveExportFrame(snapshot, frameIndex = 0, fps = 30, activeSet = activeSet)
        assertEquals(0, res0.clipAIndex)
        // With timeMap inUs = 500_000:
        assertEquals(500_000L, res0.sourceUsA)
        assertFalse(res0.isTransition)

        // Frame during transition: Clip 0 ends at 3000 ms, Clip 1 starts at 2000 ms (overlap 2000..3000 ms).
        // At t = 2500 ms -> frameIndex = 75 at 30 fps (75 * 1_000_000 / 30 = 2_500_000 us)
        val resTrans = ExportEngine.resolveExportFrame(snapshot, frameIndex = 75, fps = 30, activeSet = activeSet)
        assertTrue(resTrans.isTransition)
        assertEquals(0, resTrans.clipAIndex)
        // Clip 0 localUs = 2500 ms. sourceUs = 500_000 + 2_500_000 = 3_000_000 us
        assertEquals(3_000_000L, resTrans.sourceUsA)
        assertEquals(1, resTrans.clipBIndex)
        // Clip 1 starts at 2000 ms. localUs = 2500 - 2000 = 500 ms = 500_000 us
        assertEquals(500_000L, resTrans.sourceUsB)
        assertEquals(com.flowcut.app.model.TransitionType.ZOOM, resTrans.transitionType)
        assertEquals(0.5f, resTrans.linearP, 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Background prefetch coordinator using TimelineSnapshot + TimeMap
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testPrefetchCoordinator() {
        val map1 = TimeMap(inUs = 1_000_000L, outUs = 5_000_000L, speedNum = 1, speedDen = 1)
        val project = Project(
            clips = listOf(
                Clip(id = "c0", path = "/v0.mp4", durationMs = 5000L, width = 1080, height = 1920, rotation = 0),
                Clip(id = "c1", path = "/v1.mp4", durationMs = 4000L, width = 1080, height = 1920, rotation = 0, timeMap = map1)
            ),
            transitions = listOf(Transition())
        )
        val holder = SnapshotHolder().apply { current = TimelineSnapshot(1L, project) }
        val playhead = TestPlayhead(timeUs = 1_000_000L) // t = 1s

        var prefetchedClipIndex = -1
        var prefetchedSourceUs = -1L

        val prefetcher = CountingPrefetcher()
        val renderer = PreviewRenderer(
            holder = holder,
            playhead = playhead,
            onVideoDecoderPrefetch = { clipIdx, srcUs ->
                prefetchedClipIndex = clipIdx
                prefetchedSourceUs = srcUs
            }
        )
        renderer.registerPrefetcher(prefetcher)

        // At t = 1s with windowUs = 2s (reaches up to 3s). Next clip starts at 5s.
        // Should NOT trigger video decoder prefetch for clip 1 yet.
        renderer.triggerPrefetch(windowUs = 2_000_000L)
        assertEquals(1, prefetcher.callCount)
        assertEquals(1_000_000L, prefetcher.lastFromUs)
        assertEquals(-1, prefetchedClipIndex)

        // At t = 3.5s with windowUs = 2s (reaches up to 5.5s). Next clip starts at 5s.
        // Now next clip junction IS within window!
        // Should trigger video decoder prefetch for clip 1 with sourceUs = map1.sourceUs(0L) = 1_000_000 us!
        playhead.timeUs = 3_500_000L
        renderer.triggerPrefetch(windowUs = 2_000_000L)
        assertEquals(2, prefetcher.callCount)
        assertEquals(3_500_000L, prefetcher.lastFromUs)
        assertEquals(1, prefetchedClipIndex)
        assertEquals(1_000_000L, prefetchedSourceUs)
    }
}
