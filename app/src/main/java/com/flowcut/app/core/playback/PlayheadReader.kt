package com.flowcut.app.core.playback

/**
 * Playback modes supported by the timeline player (Section 6).
 */
enum class PlayMode {
    PAUSED,
    PLAYING,
    SCRUBBING,
    FLINGING
}

/**
 * Read-only interface for playhead consumers (Section 6).
 * Enforces Rule R1: external consumers can only read state.
 * Only PlaybackController can mutate playhead timeUs inside tick().
 */
interface PlayheadReader {
    val timeUs: Long
    val mode: PlayMode

    /**
     * Increments on seek, play, pause, scrub, fling, or boundary hit.
     * Consumers use this to discard stale async frame decode results.
     */
    val generation: Int
}

