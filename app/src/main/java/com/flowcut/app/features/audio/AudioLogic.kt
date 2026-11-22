/**
 * Feature:     audio
 * File role:   logic
 * Responsible: Pure volume scaling, decibel conversions, and audio gain limits
 * Depends on:  shared
 * Used by:     AudioCommands, AudioUi
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.audio

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure functions for audio gain and volume calculations.
 */
object AudioLogic {

    const val MIN_VOLUME = 0.0f
    const val MAX_VOLUME = 2.0f
    const val DEFAULT_VOLUME = 1.0f

    /**
     * Converts a linear volume gain (0.0..2.0) to integer percentage (0..200).
     */
    fun volumeToPercentage(volume: Float): Int {
        return (volume.coerceIn(MIN_VOLUME, MAX_VOLUME) * 100f).roundToInt()
    }

    /**
     * Converts integer percentage (0..200) to linear volume gain (0.0..2.0).
     */
    fun percentageToVolume(percent: Int): Float {
        return (percent.coerceIn(0, 200) / 100f)
    }

    /**
     * Converts linear gain to decibels (dB). Gain of 1.0 = 0 dB.
     */
    fun gainToDecibels(gain: Float): Float {
        if (gain <= 0.0001f) return -60f
        return (20f * log10(gain)).coerceIn(-60f, 6f)
    }

    /**
     * Converts decibels (dB) to linear gain.
     */
    fun decibelsToGain(db: Float): Float {
        if (db <= -59f) return 0f
        return (10f.pow(db / 20f)).coerceIn(MIN_VOLUME, MAX_VOLUME)
    }
}
