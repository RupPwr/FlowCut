package com.flowcut.app.engine

import kotlin.math.abs

/**
 * Pure-Kotlin adaptive decoding policy for Picture-in-Picture (PiP) video overlays.
 *
 * Balances smoothness on low-end Android devices with frame-accurate export:
 * - Synchronous offline export ALWAYS uses exact frame decoding (`useKeyframeFastPath = false`).
 * - When a 640px all-intra proxy is ready, exact frame decoding is already fast (< 8ms),
 *   so exact decoding is always used.
 * - During live preview on unproxied long-GOP media (e.g., 1080p/4K H.264/HEVC),
 *   automatically switches to keyframe-sync (`OPTION_CLOSEST_SYNC`) while actively scrubbing
 *   (bucket jump > 2) or when recent decode latency exceeds [SLOW_DECODE_THRESHOLD_MS],
 *   preventing thread starvation and UI stalls.
 */
object PipDecodePolicy {

    const val SLOW_DECODE_THRESHOLD_MS: Long = 55L
    const val SCRUB_JUMP_BUCKETS: Long = 2L
    const val FAILURE_RETRY_COOLDOWN_MS: Long = 500L

    /**
     * Returns true when `MediaMetadataRetriever.OPTION_CLOSEST_SYNC` should be preferred
     * over `OPTION_CLOSEST` for responsiveness.
     */
    fun shouldUseKeyframeFastPath(
        isSynchronousExport: Boolean,
        hasReadyProxy: Boolean,
        lastBucket: Long,
        targetBucket: Long,
        lastDecodeDurationMs: Long
    ): Boolean {
        if (isSynchronousExport) return false
        if (hasReadyProxy) return false
        if (lastBucket >= 0L && abs(targetBucket - lastBucket) > SCRUB_JUMP_BUCKETS) {
            return true
        }
        return lastDecodeDurationMs >= SLOW_DECODE_THRESHOLD_MS
    }

    /**
     * Guards against infinite 60fps retry loops when a PiP video file is missing or corrupt.
     */
    fun shouldSkipFailedBucket(
        failedBucket: Long,
        targetBucket: Long,
        lastFailedAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (failedBucket != targetBucket) return false
        return (nowMs - lastFailedAtMs) in 0L until FAILURE_RETRY_COOLDOWN_MS
    }
}
