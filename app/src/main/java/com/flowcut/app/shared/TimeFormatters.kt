package com.flowcut.app.shared

import java.util.Locale

/**
 * Shared time formatting utilities across UI and features.
 * Pure formatting functions, zero UI allocations.
 */
object TimeFormatters {

    /**
     * Formats microseconds to "M:SS.T" (e.g. "0:02.4" or "0:10.0").
     */
    fun formatDurationUs(timeUs: Long): String {
        val totalMs = (timeUs / 1000L).coerceAtLeast(0L)
        val tenths = (totalMs % 1000L) / 100L
        val totalSec = totalMs / 1000L
        val sec = totalSec % 60L
        val min = totalSec / 60L
        return String.format(Locale.US, "%d:%02d.%d", min, sec, tenths)
    }

    /**
     * Formats milliseconds to "M:SS.T" (e.g. "0:05.0").
     */
    fun formatDurationMs(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        val tenths = ((durationMs.coerceAtLeast(0L)) % 1000L) / 100L
        return String.format(Locale.US, "%d:%02d.%d", min, sec, tenths)
    }

    /**
     * Formats speed multiplier to string (e.g. "1.0", "1.5", "2.0").
     */
    fun formatSpeed(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}.0"
        } else {
            String.format(Locale.US, "%.2f", speed).trimEnd('0').let {
                if (it.endsWith(".")) "${it}0" else it
            }
        }
    }
}
