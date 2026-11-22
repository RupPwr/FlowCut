package com.flowcut.app.engine

enum class SeekAction {
    ACCEPT_CURRENT,
    RENDER_NEAREST_SYNC,
    RESUME_CATCHUP,
    FLUSH_AND_SEEK
}

enum class SeekOutcome {
    NONE,
    IN_PROGRESS,
    REACHED,
    BEST_EFFORT
}

data class SeekState(
    val lastTargetUs: Long = -1L,
    val lastFast: Boolean = false,
    val outcome: SeekOutcome = SeekOutcome.NONE,
    val lastRenderedPtsUs: Long = -1L,
    val consecutiveNoProgressAttempts: Int = 0
)

data class SeekDecision(
    val action: SeekAction,
    val targetUs: Long,
    val reason: String
)

object SeekDecisionEngine {
    fun evaluate(
        targetUs: Long,
        currentPtsUs: Long,
        lastRenderedPtsUs: Long,
        state: SeekState,
        frameDurationUs: Long,
        isScrubbing: Boolean,
        isPaused: Boolean,
        isCatchingUpFromSeek: Boolean,
        syncFramePtsUs: Long? = null,
        maxCatchUpDistanceUs: Long = 600_000L
    ): SeekDecision {
        val halfFrameUs = (frameDurationUs / 2L).coerceAtLeast(16_000L)

        // 1. Guard against infinite retry loops with no progress (Step B.5)
        if (state.consecutiveNoProgressAttempts >= 3) {
            return SeekDecision(
                SeekAction.ACCEPT_CURRENT,
                targetUs,
                "no_progress_guard_tripped"
            )
        }

        // 2. Fast Scrub: during active scrub, render single nearest sync frame and stop (Step B.4)
        if (isScrubbing) {
            if (syncFramePtsUs != null && lastRenderedPtsUs != -1L &&
                kotlin.math.abs(syncFramePtsUs - lastRenderedPtsUs) <= halfFrameUs
            ) {
                return SeekDecision(
                    SeekAction.ACCEPT_CURRENT,
                    targetUs,
                    "scrub_sync_already_rendered"
                )
            }
            return SeekDecision(
                SeekAction.RENDER_NEAREST_SYNC,
                targetUs,
                "fast_scrub_sync"
            )
        }

        // 3. Idempotent check: if target is within half-frame of previous completed seek with same mode (Step B.1)
        if (state.lastTargetUs != -1L &&
            state.outcome in listOf(SeekOutcome.REACHED, SeekOutcome.BEST_EFFORT) &&
            !state.lastFast && // previous was accurate seek
            kotlin.math.abs(targetUs - state.lastTargetUs) <= halfFrameUs
        ) {
            return SeekDecision(
                SeekAction.ACCEPT_CURRENT,
                targetUs,
                "idempotent_duplicate"
            )
        }

        // 4. Target already rendered on surface
        if (lastRenderedPtsUs != -1L &&
            kotlin.math.abs(targetUs - lastRenderedPtsUs) <= halfFrameUs &&
            !isCatchingUpFromSeek
        ) {
            return SeekDecision(
                SeekAction.ACCEPT_CURRENT,
                targetUs,
                "already_at_target"
            )
        }

        // 5. Resumable catch-up: forward progress within same GOP or within catch-up window (Step B.2 & B.3)
        val isForward = currentPtsUs != -1L && targetUs >= currentPtsUs - halfFrameUs
        val inSameGop = if (syncFramePtsUs != null) {
            syncFramePtsUs <= currentPtsUs
        } else {
            targetUs - currentPtsUs <= maxCatchUpDistanceUs
        }

        if (isForward && inSameGop && (isCatchingUpFromSeek || targetUs - currentPtsUs <= maxCatchUpDistanceUs)) {
            return SeekDecision(
                SeekAction.RESUME_CATCHUP,
                targetUs,
                "resume_catchup_forward"
            )
        }

        // 6. Backward jump, initial seek, discontinuous, or beyond GOP: flush and seek
        val reason = when {
            currentPtsUs == -1L -> "initial_seek"
            targetUs < currentPtsUs - halfFrameUs -> "backward_jump"
            else -> "beyond_gop"
        }
        return SeekDecision(
            SeekAction.FLUSH_AND_SEEK,
            targetUs,
            reason
        )
    }
}
