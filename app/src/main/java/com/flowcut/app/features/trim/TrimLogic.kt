/**
 * Feature:     trim
 * File role:   logic
 * Responsible: Pure calculation of clip in/out boundaries and time maps
 * Depends on:  core/time, shared
 * Used by:     TrimCommands, TrimUi
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.trim

import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.shared.SharedConstants

/**
 * Pure functions for trim calculations.
 */
object TrimLogic {

    /**
     * Calculates updated in/out points clamped to valid source range and minimum duration.
     */
    fun clampTrimRange(
        desiredInUs: Long,
        desiredOutUs: Long,
        maxSourceUs: Long,
        minDurationUs: Long = SharedConstants.MIN_TRIM_DURATION_US
    ): Pair<Long, Long> {
        val clampedIn = desiredInUs.coerceIn(0L, maxOf(0L, maxSourceUs - minDurationUs))
        val clampedOut = desiredOutUs.coerceIn(clampedIn + minDurationUs, maxSourceUs)
        return Pair(clampedIn, clampedOut)
    }

    /**
     * Generates a new [TimeMap] with updated trim boundaries, preserving existing playback speed.
     */
    fun createTrimmedTimeMap(
        currentMap: TimeMap?,
        clipDurationMs: Long,
        newInUs: Long,
        newOutUs: Long
    ): TimeMap {
        val speedNum = currentMap?.speedNum ?: 1
        val speedDen = currentMap?.speedDen ?: 1
        val maxUs = currentMap?.outUs ?: (clipDurationMs * 1000L)
        val (safeIn, safeOut) = clampTrimRange(newInUs, newOutUs, maxUs)
        return TimeMap(
            inUs = safeIn,
            outUs = safeOut,
            speedNum = speedNum,
            speedDen = speedDen
        )
    }
}
