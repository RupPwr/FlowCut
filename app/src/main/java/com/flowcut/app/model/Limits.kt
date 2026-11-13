package com.flowcut.app.model

const val MAX_CLIPS = 1000
const val MIN_CLIP_MS = 1_000L
const val MAX_CLIP_MS = 900_000L
const val MAX_TOTAL_MS = 900_000L
const val MAX_1080P_SHORT_SIDE = 1080
const val MAX_1080P_LONG_SIDE = 2560
const val MAX_SIDE_PX = 2560

/**
 * Resolution check for 1080p class videos.
 * Supports both vertical and horizontal videos up to 1080p (e.g., 1080x2400, 1080x1920, 1920x1080).
 * Blocks 1440p (2K), 4K, 8K videos where the shorter dimension exceeds 1080.
 */
fun isResolutionSupported1080p(width: Int, height: Int, rotation: Int = 0): Boolean {
    val effW = if (rotation == 90 || rotation == 270) height else width
    val effH = if (rotation == 90 || rotation == 270) width else height
    val shortSide = minOf(effW, effH)
    val longSide = maxOf(effW, effH)
    return shortSide in 1..MAX_1080P_SHORT_SIDE && longSide in 1..MAX_1080P_LONG_SIDE
}
const val DP_PER_SECOND = 48
const val TRANSITION_MIN_MS = 300L
const val TRANSITION_MAX_MS = 2_000L
const val TRANSITION_STEP_MS = 100L
const val TRANSITION_DEFAULT_MS = 800L
const val MIN_SOLO_MS = 200L
const val UNDO_LIMIT = 30
const val PREVIEW_W = 540
const val PREVIEW_H = 960
const val EXPORT_W = 1080
const val EXPORT_H = 1920
const val EXPORT_FPS = 30
const val EXPORT_BITRATE = 10_000_000
const val EXPORT_BITRATE_30 = 10_000_000
const val EXPORT_BITRATE_60 = 16_000_000
const val PREFETCH_MS = 2_000L
const val AUTO_PLAY_MARGIN_MS = 700L
