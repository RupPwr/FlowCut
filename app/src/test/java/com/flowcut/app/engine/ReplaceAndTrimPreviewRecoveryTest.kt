package com.flowcut.app.engine

import com.flowcut.app.core.edit.SetTimeMapCommand
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.playback.PlaybackController
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReplaceAndTrimPreviewRecoveryTest {

    private val frameDurationUs = 33_333L

    @Test
    fun testSeekDecisionEngineReSeeksWhenDecoderAtEosInsteadOfResumingCatchUp() {
        val state = SeekState(
            lastTargetUs = 6_000_000L,
            outcome = SeekOutcome.IN_PROGRESS
        )
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 6_000_000L,
            currentPtsUs = 3_000_000L,
            lastRenderedPtsUs = 3_000_000L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = false,
            isPaused = true,
            isCatchingUpFromSeek = true,
            syncFramePtsUs = 2_000_000L,
            isEos = true
        )
        assertEquals(SeekAction.FLUSH_AND_SEEK, decision.action)
        assertEquals("eos_reseek", decision.reason)
    }

    @Test
    fun testSeekDecisionEngineDoesNotAcceptStaleSyncFrameWhenAtEosDuringScrub() {
        val state = SeekState(lastRenderedPtsUs = 3_000_000L)
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 3_020_000L,
            currentPtsUs = 3_000_000L,
            lastRenderedPtsUs = 3_000_000L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = true,
            isPaused = false,
            isCatchingUpFromSeek = false,
            syncFramePtsUs = 3_000_000L,
            isEos = true
        )
        assertNotEquals("scrub_sync_already_rendered", decision.reason)
        assertEquals(SeekAction.RENDER_NEAREST_SYNC, decision.action)
    }

    @Test
    fun testSeekDecisionEngineDoesNotFreezeScrubWhenNoFrameHasRenderedYet() {
        val state = SeekState(
            lastTargetUs = 5_000_000L,
            consecutiveNoProgressAttempts = 5
        )
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 5_000_000L,
            currentPtsUs = -1L,
            lastRenderedPtsUs = -1L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = true,
            isPaused = false,
            isCatchingUpFromSeek = false,
            syncFramePtsUs = 4_000_000L,
            isEos = false
        )
        assertEquals(SeekAction.RENDER_NEAREST_SYNC, decision.action)
        assertEquals("fast_scrub_sync", decision.reason)
    }

    @Test
    fun testForwardTrimScrubWithinLongGopUsesSmoothForwardDecodeInsteadOfFreezingOnKeyframe() {
        val state = SeekState(lastRenderedPtsUs = 0L)
        // 5-second GOP: keyframe at 0s, user dragging trim handle forward past 3.2s
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 3_200_000L,
            currentPtsUs = 2_800_000L,
            lastRenderedPtsUs = 2_800_000L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = true,
            isPaused = false,
            isCatchingUpFromSeek = false,
            syncFramePtsUs = 0L,
            isEos = false,
            isForwardScrub = true
        )
        assertEquals(SeekAction.RESUME_CATCHUP, decision.action)
        assertEquals("scrub_forward_advance", decision.reason)
    }

    @Test
    fun testForwardTrimScrubCrossingNewGopHopsToNewSyncFrame() {
        val state = SeekState(lastRenderedPtsUs = 2_800_000L)
        // Crossed into a new keyframe at 5.0s
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 5_400_000L,
            currentPtsUs = 2_800_000L,
            lastRenderedPtsUs = 2_800_000L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = true,
            isPaused = false,
            isCatchingUpFromSeek = false,
            syncFramePtsUs = 5_000_000L,
            isEos = false,
            isForwardScrub = true
        )
        assertEquals(SeekAction.RENDER_NEAREST_SYNC, decision.action)
        assertEquals("fast_scrub_sync", decision.reason)
    }

    @Test
    fun testForwardTrimScrubWithoutKeyframeIndexDecodesForwardWithout60HzFlushStorm() {
        val state = SeekState(lastRenderedPtsUs = 3_000_000L)
        // Immediately after Replace before async .idx build finishes (syncFramePtsUs == null)
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 3_350_000L,
            currentPtsUs = 3_000_000L,
            lastRenderedPtsUs = 3_000_000L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = true,
            isPaused = false,
            isCatchingUpFromSeek = false,
            syncFramePtsUs = null,
            isEos = false,
            isForwardScrub = true
        )
        assertEquals(SeekAction.RESUME_CATCHUP, decision.action)
        assertEquals("scrub_forward_advance", decision.reason)
    }

    @Test
    fun testForwardTrimScrubAtEosForcesSyncSeekInsteadOfCatchUp() {
        val state = SeekState(lastRenderedPtsUs = 3_000_000L)
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 3_500_000L,
            currentPtsUs = 3_000_000L,
            lastRenderedPtsUs = 3_000_000L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = true,
            isPaused = false,
            isCatchingUpFromSeek = false,
            syncFramePtsUs = 0L,
            isEos = true,
            isForwardScrub = true
        )
        assertEquals(SeekAction.RENDER_NEAREST_SYNC, decision.action)
        assertEquals("fast_scrub_sync", decision.reason)
    }

    @Test
    fun testBackwardTrimScrubUsesNearestSyncSeek() {
        val state = SeekState(lastRenderedPtsUs = 6_000_000L)
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 4_200_000L,
            currentPtsUs = 6_000_000L,
            lastRenderedPtsUs = 6_000_000L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = true,
            isPaused = false,
            isCatchingUpFromSeek = false,
            syncFramePtsUs = 4_000_000L,
            isEos = false,
            isForwardScrub = false
        )
        assertEquals(SeekAction.RENDER_NEAREST_SYNC, decision.action)
        assertEquals("fast_scrub_sync", decision.reason)
    }

    @Test
    fun testReplaceShorterWithLongerClipThenExtendTrimAndReleaseNeverFreezesPreview() {
        // 1. Start with a shorter 3-second clip on the timeline
        val shortClip = Clip(
            id = "clip-short",
            path = "/storage/short_3s.mp4",
            durationMs = 3_000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            timeMap = TimeMap(inUs = 0L, outUs = 3_000_000L)
        )
        val holder = SnapshotHolder()
        holder.current = TimelineSnapshot(1L, Project(clips = listOf(shortClip)))

        val controller = PlaybackController(holder = holder)
        var frameNs = 1_000_000_000L
        controller.seekTo(1_000_000L)
        controller.tick(frameNs)

        val renderer = PreviewRenderer(
            holder = holder,
            playhead = controller.playhead,
            context = null
        )

        val initialFrame = renderer.renderFrame()
        assertEquals("clip-short", initialFrame.clipAId)
        assertEquals("/storage/short_3s.mp4", initialFrame.clipAPath)
        assertEquals(1_000_000L, initialFrame.clipASourceUs)

        // 2. Replace with a longer 15-second video (initially clamped to old 3s slot duration)
        val replacedLongClip = Clip(
            id = "clip-long",
            path = "/storage/long_15s.mp4",
            durationMs = 3_000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            timeMap = TimeMap(inUs = 0L, outUs = 3_000_000L)
        )
        holder.current = TimelineSnapshot(2L, Project(clips = listOf(replacedLongClip)))
        renderer.invalidateDecoders()

        assertEquals(-1L, renderer.trimPreviewTimeUs)
        val afterReplace = renderer.renderFrame()
        assertEquals("clip-long", afterReplace.clipAId)
        assertEquals("/storage/long_15s.mp4", afterReplace.clipAPath)
        assertEquals(1_000_000L, afterReplace.clipASourceUs)

        // 3. Drag Right Trim Handle forward across 6s -> 10s -> 14s while playhead stays paused at 1s
        val trimTargetsMs = listOf(6_000L, 10_000L, 14_000L, 8_500L)
        var version = 3L
        for (targetMs in trimTargetsMs) {
            val targetUs = msToUs(targetMs)
            val updatedProject = SetTimeMapCommand(
                clipIndex = 0,
                timeMap = TimeMap(inUs = 0L, outUs = targetUs)
            ).apply(holder.current.project)
            holder.current = TimelineSnapshot(version++, updatedProject)

            val trimmedEdgeUs = msToUs(holder.current.main.endMs[0]) - 1_000L
            renderer.trimPreviewTimeUs = trimmedEdgeUs

            val trimFrame = renderer.renderFrame()
            assertEquals("clip-long", trimFrame.clipAId)
            assertEquals("/storage/long_15s.mp4", trimFrame.clipAPath)
            assertEquals(trimmedEdgeUs, trimFrame.tUs)
            assertEquals(trimmedEdgeUs, trimFrame.clipASourceUs)
        }

        // 4. Release trim handle and verify Play -> Pause -> Play cycles advance smoothly across extended duration
        renderer.trimPreviewTimeUs = -1L
        controller.play()
        var lastSourceUs = -1L
        repeat(60) {
            frameNs += 16_666_667L
            controller.tick(frameNs)
            val frame = renderer.renderFrame()
            assertEquals("clip-long", frame.clipAId)
            if (lastSourceUs != -1L) {
                assertEquals(true, frame.clipASourceUs >= lastSourceUs)
            }
            lastSourceUs = frame.clipASourceUs
        }

        controller.pause()
        frameNs += 16_666_667L
        controller.tick(frameNs)
        val pausedSourceUs = renderer.renderFrame().clipASourceUs

        controller.play()
        repeat(60) {
            frameNs += 16_666_667L
            controller.tick(frameNs)
            val frame = renderer.renderFrame()
            assertEquals("clip-long", frame.clipAId)
            assertEquals(true, frame.clipASourceUs >= pausedSourceUs)
        }
    }
}
