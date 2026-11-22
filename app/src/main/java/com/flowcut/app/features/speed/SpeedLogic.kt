/**
 * Feature:     speed
 * File role:   logic
 * Responsible: Pure speed calculations, piecewise slider mapping, and TimeMap transformations
 * Depends on:  core/time, shared
 * Used by:     SpeedCommands, SpeedUi
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.speed

import com.flowcut.app.core.time.TimeMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure logic and calculations for playback speed.
 */
object SpeedLogic {

    val PRESET_SPEEDS = listOf(0.2f, 0.5f, 1.0f, 1.5f, 2.0f, 4.0f)

    /**
     * Maps speed (0.2x..4.0x) to a non-linear slider position (0.0..1.0)
     * giving higher precision to slow-mo ranges (0.2x..1.0x).
     */
    fun speedToSlider(speed: Float): Float {
        val s = speed.coerceIn(0.2f, 4.0f)
        return if (s <= 1.0f) {
            ((s - 0.2f) / 0.8f) * 0.35f
        } else {
            0.35f + ((s - 1.0f) / 3.0f) * 0.65f
        }
    }

    /**
     * Maps slider position (0.0..1.0) back to speed with magnetic snapping around 1.0x.
     */
    fun sliderToSpeed(slider: Float, maxSpeed: Float = 4.0f): Float {
        val p = slider.coerceIn(0.0f, 1.0f)
        val rawSpeed = if (p <= 0.35f) {
            val t = p / 0.35f
            0.2f + t * 0.8f
        } else {
            val t = (p - 0.35f) / 0.65f
            1.0f + t * 3.0f
        }
        val snapped = if (abs(rawSpeed - 1.0f) <= 0.05f) {
            1.0f
        } else if (rawSpeed <= 1.0f) {
            ((rawSpeed * 20f).roundToInt() / 20f)
        } else {
            ((rawSpeed * 10f).roundToInt() / 10f)
        }
        return snapped.coerceIn(0.2f, maxSpeed)
    }

    /**
     * Converts a decimal speed into a rational fraction (speedNum / speedDen).
     */
    fun speedToFraction(speed: Float): Pair<Int, Int> {
        val roundedHundredths = (speed * 100f).roundToInt()
        return Pair(roundedHundredths, 100)
    }

    /**
     * Creates a new [TimeMap] for the target speed preserving existing trim inUs / outUs.
     */
    fun createSpeedTimeMap(
        currentMap: TimeMap?,
        clipDurationMs: Long,
        speed: Float
    ): TimeMap {
        val (num, den) = speedToFraction(speed)
        val inUs = currentMap?.inUs ?: 0L
        val outUs = currentMap?.outUs ?: (clipDurationMs * 1000L)
        return TimeMap(
            inUs = inUs,
            outUs = outUs,
            speedNum = num,
            speedDen = den
        )
    }
}
