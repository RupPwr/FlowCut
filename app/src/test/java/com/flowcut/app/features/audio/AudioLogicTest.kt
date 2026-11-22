package com.flowcut.app.features.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLogicTest {

    @Test
    fun `volumeToPercentage and percentageToVolume round trip`() {
        assertEquals(100, AudioLogic.volumeToPercentage(1.0f))
        assertEquals(200, AudioLogic.volumeToPercentage(2.0f))
        assertEquals(0, AudioLogic.volumeToPercentage(0.0f))

        assertEquals(1.0f, AudioLogic.percentageToVolume(100), 0.001f)
        assertEquals(0.5f, AudioLogic.percentageToVolume(50), 0.001f)
    }

    @Test
    fun `decibel conversion zero gain maps to -60dB`() {
        val db = AudioLogic.gainToDecibels(0.0f)
        assertEquals(-60f, db, 0.01f)

        val gain = AudioLogic.decibelsToGain(-60f)
        assertEquals(0f, gain, 0.01f)
    }

    @Test
    fun `gain 1_0 maps to 0dB`() {
        val db = AudioLogic.gainToDecibels(1.0f)
        assertEquals(0f, db, 0.01f)

        val gain = AudioLogic.decibelsToGain(0f)
        assertEquals(1.0f, gain, 0.01f)
    }
}
