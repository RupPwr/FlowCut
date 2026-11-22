/**
 * Feature:     export
 * File role:   logic
 * Responsible: Pure calculation of estimated export file sizes, bitrates, and progress scaling
 * Depends on:  shared
 * Used by:     ExportUi, Index
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.export

/**
 * Pure helper functions for video export calculations.
 */
object ExportLogic {

    /**
     * Estimates output file size in megabytes (MB) given duration in ms and target bitrate in bps.
     */
    fun estimateFileSizeMb(durationMs: Long, bitrateBps: Long = 12_000_000L): Float {
        val durationSeconds = durationMs / 1000f
        val totalBits = durationSeconds * bitrateBps
        val totalBytes = totalBits / 8f
        return (totalBytes / (1024f * 1024f)).coerceAtLeast(0.1f)
    }

    /**
     * Formats resolution and aspect ratio string.
     */
    fun formatResolution(width: Int, height: Int): String {
        return "$width x $height (${if (width < height) "9:16" else "16:9"})"
    }

    /**
     * Formats export progress as percentage integer (0..100).
     */
    fun progressToPercent(progress: Float): Int {
        return (progress * 100f).toInt().coerceIn(0, 100)
    }
}
