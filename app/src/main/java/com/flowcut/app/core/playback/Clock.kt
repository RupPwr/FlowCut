package com.flowcut.app.core.playback

import java.util.concurrent.TimeUnit

/**
 * Clock abstraction driving timeline playback (Section 6).
 * MasterClock is the pure Kotlin fallback; AudioClock replaces it in Phase 2.
 */
interface Clock {
    fun start(fromUs: Long)
    fun stop()
    fun timeUsAt(frameTimeNanos: Long): Long
    val isReady: Boolean
}

/**
 * Monotonic frame-time clock converting nanosecond deltas into microseconds.
 */
class MasterClock : Clock {
    private var anchorUs: Long = 0L
    private var anchorNanos: Long = -1L
    private var isRunning: Boolean = false

    override val isReady: Boolean get() = true

    override fun start(fromUs: Long) {
        anchorUs = fromUs
        anchorNanos = -1L
        isRunning = true
    }

    override fun stop() {
        isRunning = false
        anchorNanos = -1L
    }

    override fun timeUsAt(frameTimeNanos: Long): Long {
        if (!isRunning) return anchorUs
        if (anchorNanos < 0L) {
            anchorNanos = frameTimeNanos
            return anchorUs
        }
        val deltaNanos = frameTimeNanos - anchorNanos
        val deltaUs = TimeUnit.NANOSECONDS.toMicros(deltaNanos)
        return anchorUs + deltaUs
    }
}

