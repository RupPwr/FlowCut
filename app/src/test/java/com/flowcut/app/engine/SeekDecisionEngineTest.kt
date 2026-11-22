package com.flowcut.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekDecisionEngineTest {

    private val frameDurationUs = 33_333L // ~30 fps

    @Test
    fun testNoProgressGuardReturnsAcceptCurrent() {
        val state = SeekState(
            lastTargetUs = 1_746_048L,
            consecutiveNoProgressAttempts = 3
        )
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 1_746_048L,
            currentPtsUs = 240_355L,
            lastRenderedPtsUs = 240_355L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = false,
            isPaused = true,
            isCatchingUpFromSeek = true
        )
        assertEquals(SeekAction.ACCEPT_CURRENT, decision.action)
        assertEquals("no_progress_guard_tripped", decision.reason)
    }

    @Test
    fun testFastScrubRendersNearestSync() {
        val state = SeekState()
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 1_500_000L,
            currentPtsUs = 0L,
            lastRenderedPtsUs = 0L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = true,
            isPaused = false,
            isCatchingUpFromSeek = false,
            syncFramePtsUs = 1_000_000L
        )
        assertEquals(SeekAction.RENDER_NEAREST_SYNC, decision.action)
        assertEquals("fast_scrub_sync", decision.reason)
    }

    @Test
    fun testFastScrubWithinSameSyncAcceptsCurrent() {
        val state = SeekState(lastRenderedPtsUs = 1_000_000L)
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 1_050_000L,
            currentPtsUs = 1_000_000L,
            lastRenderedPtsUs = 1_000_000L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = true,
            isPaused = false,
            isCatchingUpFromSeek = false,
            syncFramePtsUs = 1_000_000L
        )
        assertEquals(SeekAction.ACCEPT_CURRENT, decision.action)
        assertEquals("scrub_sync_already_rendered", decision.reason)
    }

    @Test
    fun testIdempotentAccurateSeekAcceptsCurrent() {
        val state = SeekState(
            lastTargetUs = 1_746_048L,
            lastFast = false,
            outcome = SeekOutcome.REACHED,
            lastRenderedPtsUs = 1_746_048L
        )
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 1_746_050L, // 2 us difference (within half frame)
            currentPtsUs = 1_746_048L,
            lastRenderedPtsUs = 1_746_048L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = false,
            isPaused = true,
            isCatchingUpFromSeek = false
        )
        assertEquals(SeekAction.ACCEPT_CURRENT, decision.action)
        assertEquals("idempotent_duplicate", decision.reason)
    }

    @Test
    fun testTargetAlreadyRenderedAcceptsCurrent() {
        val state = SeekState()
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 500_005L,
            currentPtsUs = 500_000L,
            lastRenderedPtsUs = 500_000L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = false,
            isPaused = true,
            isCatchingUpFromSeek = false
        )
        assertEquals(SeekAction.ACCEPT_CURRENT, decision.action)
        assertEquals("already_at_target", decision.reason)
    }

    @Test
    fun testResumableCatchUpForwardInSameGop() {
        val state = SeekState(
            lastTargetUs = 1_746_048L,
            outcome = SeekOutcome.IN_PROGRESS
        )
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 1_746_048L,
            currentPtsUs = 240_355L,
            lastRenderedPtsUs = 240_355L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = false,
            isPaused = true,
            isCatchingUpFromSeek = true,
            syncFramePtsUs = 0L
        )
        assertEquals(SeekAction.RESUME_CATCHUP, decision.action)
        assertEquals("resume_catchup_forward", decision.reason)
    }

    @Test
    fun testSeekCoalescingAheadInCatchup() {
        val state = SeekState(
            lastTargetUs = 1_746_048L,
            outcome = SeekOutcome.IN_PROGRESS
        )
        // New target arrives ahead of currentPtsUs while catching up
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 1_900_000L,
            currentPtsUs = 400_000L,
            lastRenderedPtsUs = 240_355L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = false,
            isPaused = true,
            isCatchingUpFromSeek = true,
            syncFramePtsUs = 0L
        )
        assertEquals(SeekAction.RESUME_CATCHUP, decision.action)
        assertEquals("resume_catchup_forward", decision.reason)
    }

    @Test
    fun testBackwardJumpFlushesAndSeeks() {
        val state = SeekState()
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 200_000L,
            currentPtsUs = 1_500_000L,
            lastRenderedPtsUs = 1_500_000L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = false,
            isPaused = true,
            isCatchingUpFromSeek = false
        )
        assertEquals(SeekAction.FLUSH_AND_SEEK, decision.action)
        assertEquals("backward_jump", decision.reason)
    }

    @Test
    fun testInitialSeekFlushesAndSeeks() {
        val state = SeekState()
        val decision = SeekDecisionEngine.evaluate(
            targetUs = 1_000_000L,
            currentPtsUs = -1L,
            lastRenderedPtsUs = -1L,
            state = state,
            frameDurationUs = frameDurationUs,
            isScrubbing = false,
            isPaused = true,
            isCatchingUpFromSeek = false
        )
        assertEquals(SeekAction.FLUSH_AND_SEEK, decision.action)
        assertEquals("initial_seek", decision.reason)
    }
}
