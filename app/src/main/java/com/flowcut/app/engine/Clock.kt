package com.flowcut.app.engine

/**
 * Clock interface (Section 19.6):
 * Master clock for playback synchronization with Choreographer vsync timestamps.
 */
interface Clock {
    fun timeUsAt(frameTimeNanos: Long): Long
}

class MasterClock : Clock {
    private var baseTimeUs: Long = 0L
    private var baseNanos: Long = 0L
    private var isRunning: Boolean = false

    fun start(startTimeUs: Long, startNanos: Long) {
        baseTimeUs = startTimeUs
        baseNanos = startNanos
        isRunning = true
    }

    fun stop(finalTimeUs: Long) {
        baseTimeUs = finalTimeUs
        isRunning = false
    }

    override fun timeUsAt(frameTimeNanos: Long): Long {
        if (!isRunning) return baseTimeUs
        val elapsedNanos = frameTimeNanos - baseNanos
        return baseTimeUs + (elapsedNanos / 1000L)
    }
}

