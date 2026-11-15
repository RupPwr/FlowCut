package com.flowcut.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WaveformServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSilenceProducesZeroPeaks() {
        val sampleRate = 48000
        val channels = 2
        // 1 second of stereo silence (96,000 samples)
        val pcm = ShortArray(sampleRate * channels) { 0 }

        val peaks = computePeaksFromPcm16(pcm, pcm.size, sampleRate, channels, targetSamplesPerSec = 50)
        assertEquals(50, peaks.size)
        for (peak in peaks) {
            assertEquals(0.0f, peak, 0.0001f)
        }
    }

    @Test
    fun testFullScaleSineProducesNormalizedPeaks() {
        val sampleRate = 48000
        val channels = 1
        // 0.5 seconds of mono audio with full-scale amplitude 32767
        val pcm = ShortArray(sampleRate / 2) { 32767 }

        val peaks = computePeaksFromPcm16(pcm, pcm.size, sampleRate, channels, targetSamplesPerSec = 50)
        assertEquals(25, peaks.size)
        for (peak in peaks) {
            assertEquals(1.0f, peak, 0.0001f)
        }
    }

    @Test
    fun testStereoChannelPeakCapture() {
        val sampleRate = 48000
        val channels = 2
        val totalFrames = sampleRate // 1 second
        val pcm = ShortArray(totalFrames * channels)

        // Put peak audio only on right channel (odd indices), left channel is silent
        for (i in 0 until totalFrames) {
            pcm[i * 2] = 0 // Left
            pcm[i * 2 + 1] = 16384 // Right (~0.5 amplitude)
        }

        val peaks = computePeaksFromPcm16(pcm, pcm.size, sampleRate, channels, targetSamplesPerSec = 50)
        assertEquals(50, peaks.size)
        for (peak in peaks) {
            assertTrue("Peak should capture right channel amplitude ~0.5", peak in 0.49f..0.51f)
        }
    }

    @Test
    fun testEmptyInputHandling() {
        val peaksEmpty = computePeaksFromPcm16(ShortArray(0), 0, 48000, 2)
        assertEquals(0, peaksEmpty.size)

        val peaksZeroCount = computePeaksFromPcm16(ShortArray(100), 0, 48000, 2)
        assertEquals(0, peaksZeroCount.size)
    }

    @Test
    fun testBinaryPersistenceRoundTrip() {
        val file = File(tempFolder.root, "test_wave.wave")
        val originalPeaks = floatArrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
        val data = WaveformData(samplesPerSec = 50, peaks = originalPeaks)

        WaveformService.writeToDisk(file, data)
        assertTrue(file.exists())
        assertEquals(12 + 5, file.length().toInt()) // 4 magic + 4 sps + 4 count + 5 bytes

        val restored = WaveformService.readFromDisk(file)
        assertNotNull(restored)
        assertEquals(50, restored!!.samplesPerSec)
        assertEquals(5, restored.peaks.size)

        // Byte quantization accuracy: 1/255 ~= 0.004
        for (i in originalPeaks.indices) {
            assertEquals(originalPeaks[i], restored.peaks[i], 0.005f)
        }
    }
}
