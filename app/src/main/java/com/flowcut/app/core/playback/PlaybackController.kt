package com.flowcut.app.core.playback

import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.timeline.SnapshotHolder
import kotlin.math.abs
import kotlin.math.exp

/**
 * PlaybackController: Sole writer to Playhead timeUs (Section 6 & Rule R1).
 * All UI requests (play, pause, seek, scrub, fling) post desires.
 * Playhead timeUs is mutated ONLY within tick(frameTimeNanos) once per vsync frame.
 */
class PlaybackController(
    private val clock: Clock = MasterClock(),
    private val holder: SnapshotHolder = SnapshotHolder()
) {
    companion object {
        const val FLING_DECAY = 1.8
        const val MIN_FLING_VELOCITY_PX_PER_SEC = 4.0
        private const val MAX_DT_SECONDS = 0.05
    }

    interface SeekEventListener {
        fun onSeekEvent(source: String, targetUs: Long, playheadUs: Long, coalesced: Boolean)
    }

    var seekEventListener: SeekEventListener? = null

    private class MutablePlayhead : PlayheadReader {
        @Volatile override var timeUs: Long = 0L
        @Volatile override var mode: PlayMode = PlayMode.PAUSED
        @Volatile override var generation: Int = 0
    }

    private val state = MutablePlayhead()
    val playhead: PlayheadReader = state

    private var pendingSeekUs: Long? = null
    private var accumulatedScrubDeltaUs: Long = 0L

    private var flingVelocityUsPerSec: Double = 0.0
    private var flingPxPerUs: Double = 1.0
    private var lastFlingFrameNanos: Long = 0L
    private var flingRemainderUs: Double = 0.0

    val totalUs: Long get() = msToUs(holder.current.totalMs)

    fun play() {
        if (state.mode == PlayMode.PLAYING) return
        val currentTotal = totalUs
        val startUs = if (state.timeUs >= currentTotal) 0L else state.timeUs
        state.timeUs = startUs
        clock.start(startUs)
        state.mode = PlayMode.PLAYING
        state.generation++
    }

    fun pause() {
        if (state.mode != PlayMode.PAUSED) {
            clock.stop()
            state.mode = PlayMode.PAUSED
            state.generation++
            flingVelocityUsPerSec = 0.0
            lastFlingFrameNanos = 0L
            flingRemainderUs = 0.0
        }
    }

    fun seekTo(us: Long) {
        val prevPending = pendingSeekUs
        if (prevPending != null) {
            seekEventListener?.onSeekEvent("seekTo_coalesced", prevPending, state.timeUs, true)
        }
        pendingSeekUs = us
        seekEventListener?.onSeekEvent("seekTo", us, state.timeUs, false)
        if (state.mode == PlayMode.FLINGING) {
            state.mode = PlayMode.PAUSED
            state.generation++
            flingVelocityUsPerSec = 0.0
            lastFlingFrameNanos = 0L
            flingRemainderUs = 0.0
        }
    }

    fun beginScrub() {
        if (state.mode == PlayMode.PLAYING) {
            clock.stop()
        }
        state.mode = PlayMode.SCRUBBING
        state.generation++
        pendingSeekUs = null
        accumulatedScrubDeltaUs = 0L
        flingVelocityUsPerSec = 0.0
        lastFlingFrameNanos = 0L
        flingRemainderUs = 0.0
        seekEventListener?.onSeekEvent("beginScrub", state.timeUs, state.timeUs, false)
    }

    fun scrubBy(deltaUs: Long) {
        accumulatedScrubDeltaUs += deltaUs
    }

    fun endScrub(velocityUsPerSec: Double, pxPerUs: Double = 1.0) {
        seekEventListener?.onSeekEvent("endScrub", state.timeUs, state.timeUs, false)
        if (abs(velocityUsPerSec) * pxPerUs >= MIN_FLING_VELOCITY_PX_PER_SEC) {
            flingVelocityUsPerSec = velocityUsPerSec
            flingPxPerUs = pxPerUs
            lastFlingFrameNanos = 0L
            flingRemainderUs = 0.0
            state.mode = PlayMode.FLINGING
            state.generation++
        } else {
            state.mode = PlayMode.PAUSED
            state.generation++
            flingVelocityUsPerSec = 0.0
            lastFlingFrameNanos = 0L
            flingRemainderUs = 0.0
        }
    }

    fun needsFrames(): Boolean {
        return state.mode != PlayMode.PAUSED || pendingSeekUs != null
    }

    /**
     * Called once per vsync by FrameDriver.
     * Enforces Rule R10: Zero heap allocations in this method.
     */
    fun tick(frameTimeNanos: Long) {
        val currentTotal = totalUs

        // 1. Pending seek has priority
        val seek = pendingSeekUs
        if (seek != null) {
            pendingSeekUs = null
            val clampedSeek = seek.coerceIn(0L, currentTotal)
            seekEventListener?.onSeekEvent("tickCommit", clampedSeek, state.timeUs, false)
            state.timeUs = clampedSeek
            state.generation++
            if (state.mode == PlayMode.PLAYING) {
                clock.start(clampedSeek)
            } else {
                state.mode = PlayMode.PAUSED
                flingVelocityUsPerSec = 0.0
                lastFlingFrameNanos = 0L
                flingRemainderUs = 0.0
            }
        }

        // 2. Mode updates
        when (state.mode) {
            PlayMode.PAUSED -> {
                lastFlingFrameNanos = 0L
                flingRemainderUs = 0.0
            }

            PlayMode.PLAYING -> {
                lastFlingFrameNanos = 0L
                flingRemainderUs = 0.0
                val newTimeUs = clock.timeUsAt(frameTimeNanos).coerceIn(0L, currentTotal)
                state.timeUs = newTimeUs
                if (newTimeUs >= currentTotal) {
                    clock.stop()
                    state.mode = PlayMode.PAUSED
                    state.generation++
                }
            }

            PlayMode.SCRUBBING -> {
                lastFlingFrameNanos = 0L
                flingRemainderUs = 0.0
                val delta = accumulatedScrubDeltaUs
                if (delta != 0L) {
                    accumulatedScrubDeltaUs = 0L
                    state.timeUs = (state.timeUs + delta).coerceIn(0L, currentTotal)
                }
            }

            PlayMode.FLINGING -> {
                // Bug #1 fix: First frame uses standard 1/60s dt, subsequent frames clamp dt to [0.001..0.050]
                val dtSeconds: Double = if (lastFlingFrameNanos == 0L) {
                    lastFlingFrameNanos = frameTimeNanos
                    1.0 / 60.0
                } else {
                    val dt = (frameTimeNanos - lastFlingFrameNanos).toDouble() / 1_000_000_000.0
                    lastFlingFrameNanos = frameTimeNanos
                    dt.coerceIn(0.001, MAX_DT_SECONDS)
                }

                val decay = exp(-FLING_DECAY * dtSeconds)
                flingVelocityUsPerSec *= decay

                val rawDeltaUs = (flingVelocityUsPerSec * dtSeconds) + flingRemainderUs
                val deltaUs = Math.round(rawDeltaUs)
                flingRemainderUs = rawDeltaUs - deltaUs
                val nextTimeUs = state.timeUs + deltaUs

                if (nextTimeUs <= 0L) {
                    state.timeUs = 0L
                    state.mode = PlayMode.PAUSED
                    state.generation++
                    flingVelocityUsPerSec = 0.0
                    lastFlingFrameNanos = 0L
                    flingRemainderUs = 0.0
                } else if (nextTimeUs >= currentTotal) {
                    state.timeUs = currentTotal
                    state.mode = PlayMode.PAUSED
                    state.generation++
                    flingVelocityUsPerSec = 0.0
                    lastFlingFrameNanos = 0L
                    flingRemainderUs = 0.0
                } else {
                    state.timeUs = nextTimeUs
                    if (abs(flingVelocityUsPerSec) * flingPxPerUs < MIN_FLING_VELOCITY_PX_PER_SEC) {
                        state.mode = PlayMode.PAUSED
                        state.generation++
                        flingVelocityUsPerSec = 0.0
                        lastFlingFrameNanos = 0L
                        flingRemainderUs = 0.0
                    }
                }
            }
        }
    }
}
