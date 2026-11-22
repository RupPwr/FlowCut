package com.flowcut.app.shared

/**
 * Shared constants for video editing timing, bounds, and UI ergonomics.
 */
object SharedConstants {
    const val MIN_CLIP_DURATION_MS: Long = 1000L
    const val MIN_TRIM_DURATION_US: Long = 200_000L // 200ms
    const val MAX_PROJECT_DURATION_MS: Long = 15 * 60 * 1000L // 15 minutes
}
