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
        maxCatchUpDistanceUs: Long = 600_000L,
        isEos: Boolean = false,
        isForwardScrub: Boolean = false,
        maxForwardScrubAdvanceUs: Long = 4_000_000L
    ): SeekDecision {
        val halfFrameUs = (frameDurationUs / 2L).coerceAtLeast(16_000L)

        // 1. Guard against infinite retry loops with no progress (Step B.5)
        // Never silently accept when no frame has ever been rendered during active scrub.
        if (state.consecutiveNoProgressAttempts >= 3 && !(isScrubbing && lastRenderedPtsUs == -1L)) {
            return SeekDecision(
                SeekAction.ACCEPT_CURRENT,
                targetUs,
                "no_progress_guard_tripped"
            )
        }

        // 2. Active Scrub / Trim Preview:
        //    - When moving forward within the same GOP (or within bounded advance without .idx),
        //      decode forward incrementally with zero dec.flush() calls so long GOPs never freeze
        //      on a single keyframe.
        //    - Otherwise jump to the nearest sync frame (deduplicating when already rendered and not at EOS).
        if (isScrubbing) {
            if (!isEos && isForwardScrub) {
                if (lastRenderedPtsUs != -1L && kotlin.math.abs(targetUs - lastRenderedPtsUs) <= halfFrameUs) {
                    return SeekDecision(
                        SeekAction.ACCEPT_CURRENT,
                        targetUs,
                        "already_at_target"
                    )
                }
                if (currentPtsUs != -1L && targetUs > currentPtsUs + halfFrameUs) {
                    val scrubInSameGop = if (syncFramePtsUs != null) {
                        syncFramePtsUs <= currentPtsUs + halfFrameUs
                    } else {
                        targetUs - currentPtsUs <= maxForwardScrubAdvanceUs
                    }
                    if (scrubInSameGop) {
                        return SeekDecision(
                            SeekAction.RESUME_CATCHUP,
                            targetUs,
                            "scrub_forward_advance"
                        )
                    }
                }
            }
            if (!isEos && syncFramePtsUs != null && lastRenderedPtsUs != -1L &&
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
        // If decoder already reached EOS, RESUME_CATCHUP cannot advance and must flush & re-seek.
        val isForward = currentPtsUs != -1L && targetUs >= currentPtsUs - halfFrameUs
        val inSameGop = if (syncFramePtsUs != null) {
            syncFramePtsUs <= currentPtsUs
        } else {
            targetUs - currentPtsUs <= maxCatchUpDistanceUs
        }

        if (!isEos && isForward && inSameGop && (isCatchingUpFromSeek || targetUs - currentPtsUs <= maxCatchUpDistanceUs)) {
            return SeekDecision(
                SeekAction.RESUME_CATCHUP,
                targetUs,
                "resume_catchup_forward"
            )
        }

        // 6. Backward jump, initial seek, EOS, discontinuous, or beyond GOP: flush and seek
        val reason = when {
            isEos -> "eos_reseek"
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
