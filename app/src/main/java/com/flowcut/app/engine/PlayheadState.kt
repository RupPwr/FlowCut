package com.flowcut.app.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Playhead Driver (Section 19.5):
 * Single source of truth for timeline position.
 */
enum class Driver {
    IDLE,
    PLAYBACK,
    USER_SCRUB,
    PROGRAMMATIC_SEEK
}

class PlayheadState {
    @Volatile
    var timeUs: Long = 0L

    @Volatile
    var driver: Driver = Driver.IDLE

    val generation = AtomicInteger(0)

    fun updateTime(newTimeUs: Long, newDriver: Driver) {
        timeUs = newTimeUs
        if (driver != newDriver) {
            driver = newDriver
            generation.incrementAndGet()
        }
    }

    fun nextGeneration(): Int = generation.incrementAndGet()
}

