package com.flowcut.app.engine.perf

enum class Level { FULL, REDUCED, MINIMAL }

/**
 * Pure Kotlin performance state machine governing runtime quality levels.
 * Zero android.* dependencies to ensure 100% JVM unit testability.
 *
 * Rules:
 * - Downgrade is INSTANT (e.g. from FULL directly to MINIMAL on severe thermal/memory pressure).
 * - Upgrade is CAUTIOUS: steps up one level at a time only after [upgradeAfterMs] (30 seconds) of sustained calm.
 * - Float.NaN for thermal headroom safely evaluates to false, falling back to frame timing and OS status.
 */
class QualityGovernor(private val upgradeAfterMs: Long = 30_000L) {

    @Volatile
    var currentLevel: Level = Level.FULL
        private set

    private var calmSince: Long = -1L

    /**
     * Updates quality level based on current telemetry signals.
     *
     * @param nowMs Current monotonic millisecond timestamp (e.g. SystemClock.uptimeMillis).
     * @param moderate True if PowerManager thermal status is >= MODERATE.
     * @param severe True if PowerManager thermal status is >= SEVERE.
     * @param headroom Forecast thermal headroom (1.0 = throttling threshold, NaN = unsupported).
     * @param lateFramePct Percentage of rendered frames exceeding deadline over recent sampling window.
     * @param memLow True if system reported low memory (e.g. TRIM_MEMORY_RUNNING_CRITICAL).
     */
    fun update(
        nowMs: Long,
        moderate: Boolean,
        severe: Boolean,
        headroom: Float,
        lateFramePct: Float,
        memLow: Boolean
    ): Level {
        val target = when {
            severe || headroom >= 1.0f || memLow -> Level.MINIMAL
            moderate || headroom >= 0.95f || lateFramePct > 8f -> Level.REDUCED
            else -> Level.FULL
        }

        if (target.ordinal > currentLevel.ordinal) {
            // Drop immediately
            currentLevel = target
            calmSince = -1L
        } else if (target.ordinal < currentLevel.ordinal) {
            // Step up cautiously after sustained calm
            if (calmSince < 0L) {
                calmSince = nowMs
            } else if (nowMs - calmSince >= upgradeAfterMs) {
                currentLevel = Level.values()[currentLevel.ordinal - 1]
                calmSince = -1L
            }
        } else {
            calmSince = -1L
        }

        return currentLevel
    }

    fun reset() {
        currentLevel = Level.FULL
        calmSince = -1L
    }
}
