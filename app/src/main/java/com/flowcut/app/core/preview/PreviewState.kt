package com.flowcut.app.core.preview

import androidx.compose.runtime.Immutable

/**
 * Preview playback and buffering lifecycle states (ARCHITECTURE_RULES.md Section 3.4).
 * Centralized state machine: IDLE -> LOADING -> READY -> PLAYING -> SEEKING -> ERROR.
 */
enum class PreviewStatus {
    IDLE,
    LOADING,
    READY,
    PLAYING,
    SEEKING,
    ERROR
}

/**
 * Immutable preview state representing playback status and anti-flicker loader display.
 */
@Immutable
data class PreviewState(
    val status: PreviewStatus = PreviewStatus.IDLE,
    val isLoaderVisible: Boolean = false,
    val errorMessage: String? = null
)
