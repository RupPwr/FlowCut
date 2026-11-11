package com.flowcut.app.audio

import com.flowcut.app.core.model.Project
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MultiTrackAudioMixingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Helper to write a valid 16-bit PCM WAV file for unit tests.
     */
    private fun createTestWav(
        sampleRate: Int = 48000,
        channels: Int = 2,
        durationSeconds: Float = 1.0f,
        frequencyHz: Double = 440.0
    ): File {
        val file = tempFolder.newFile("test_${sampleRate}_${channels}ch.wav")
        val numFrames = (sampleRate * durationSeconds).toInt()
        val dataSize = numFrames * channels * 2

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        // fmt chunk
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1.toShort()) // PCM format
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * 2) // ByteRate
        buffer.putShort((channels * 2).toShort()) // BlockAlign
        buffer.putShort(16.toShort()) // BitsPerSample
        // data chunk
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)

        for (i in 0 until numFrames) {
            val t = i.toDouble() / sampleRate.toDouble()
            val sampleVal = kotlin.math.sin(2.0 * Math.PI * frequencyHz * t) * 0.7
            val s16 = (sampleVal * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            for (c in 0 until channels) {
                buffer.putShort(s16)
            }
        }

        file.writeBytes(buffer.array())
        return file
    }

    @Test
    fun testWavFastParsingAndReading() {
        val wavFile = createTestWav(sampleRate = 48000, channels = 2, durationSeconds = 0.5f, frequencyHz = 440.0)
        val decoder = ClipAudioDecoder(wavFile.absolutePath)

        assertTrue("WAV decoder should be prepared", decoder.isPrepared)
        assertTrue("WAV decoder should have audio", decoder.hasAudio)

        val out = FloatArray(480 * 2) // 10ms block
        decoder.read(sourceFrame = 0L, count = 480, out = out, outOffset = 0, gain = 1.0f)

        var hasNonZero = false
        for (sample in out) {
            if (kotlin.math.abs(sample) > 0.01f) {
                hasNonZero = true
                break
            }
        }
        assertTrue("Output should contain decoded sine wave samples", hasNonZero)
    }

    @Test
    fun testWavResamplingFrom44100To48000() {
        val wavFile = createTestWav(sampleRate = 44100, channels = 1, durationSeconds = 0.5f, frequencyHz = 880.0)
        val decoder = ClipAudioDecoder(wavFile.absolutePath)

        assertTrue(decoder.isPrepared)
        assertTrue(decoder.hasAudio)

        val out = FloatArray(480 * 2)
        decoder.readResampled(sourceStartFrame = 0L, count = 480, speed = 1.5f, out = out, outOffset = 0, gain = 1.0f)

        var maxSample = 0f
        for (sample in out) {
            maxSample = maxOf(maxSample, kotlin.math.abs(sample))
        }
        assertTrue("Resampled audio should contain non-zero samples", maxSample > 0.05f)
    }

    @Test
    fun testEffectiveGainAndSoftLimit() {
        // Gain mapping tests
        assertEquals(0.0f, ClipAudioContributor.computeEffectiveGain(0.0f), 0.001f)
        assertEquals(0.25f, ClipAudioContributor.computeEffectiveGain(0.5f), 0.001f)
        assertEquals(1.0f, ClipAudioContributor.computeEffectiveGain(1.0f), 0.001f)
        assertEquals(4.0f, ClipAudioContributor.computeEffectiveGain(2.0f), 0.001f)

        // Soft limit tests
        assertEquals(0.0f, ClipAudioContributor.softLimit(0.0f), 0.001f)
        assertEquals(0.5f, ClipAudioContributor.softLimit(0.5f), 0.001f)
        assertEquals(-0.5f, ClipAudioContributor.softLimit(-0.5f), 0.001f)
        assertEquals(0.6f, ClipAudioContributor.softLimit(0.6f), 0.001f)

        val limitedHigh = ClipAudioContributor.softLimit(2.0f)
        assertTrue("Soft limited high sample should be < 1.0f", limitedHigh < 1.0f)
        assertTrue("Soft limited high sample should be > 0.6f", limitedHigh > 0.6f)

        val limitedNegative = ClipAudioContributor.softLimit(-2.0f)
        assertTrue("Soft limited negative sample should be > -1.0f", limitedNegative > -1.0f)
        assertTrue("Soft limited negative sample should be < -0.6f", limitedNegative < -0.6f)
    }

    @Test
    fun testMultiTrackOverlayMixingWithMainTrackMuted() {
        val wavFile = createTestWav(sampleRate = 48000, channels = 2, durationSeconds = 2.0f, frequencyHz = 500.0)

        val emptyProject = Project()
        val snapshot = TimelineSnapshot(
            version = 1L,
            project = emptyProject
        )
        val snapshotHolder = SnapshotHolder(snapshot)
        val contributor = ClipAudioContributor(snapshotHolder)

        // Create an audio overlay item
        val audioItem = OverlayItem(
            id = "test_audio_1",
            type = LayerType.AUDIO,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L,
            mediaPath = wavFile.absolutePath,
            volume = 1.0f,
            fadeInUs = 100_000L,
            fadeOutUs = 100_000L,
            speed = 1.0f,
            sourceInUs = 0L
        )

        ClipAudioContributor.syncAudioOverlays(listOf(audioItem))

        // Ensure main track is MUTED
        ClipAudioContributor.isMainTrackMuted = true

        val out = FloatArray(480 * 2) // 10ms at frame 0 (time 0us)
        contributor.render(startFrame = 0L, frames = 480, out = out)

        // Even though main track is muted, the audio overlay must be rendered into out!
        var hasOverlayAudio = false
        for (sample in out) {
            if (kotlin.math.abs(sample) > 0.0001f) {
                hasOverlayAudio = true
                break
            }
        }
        assertTrue("Overlay audio MUST play even when isMainTrackMuted is true", hasOverlayAudio)

        // Check synchronous rendering for offline export
        val syncOut = FloatArray(480 * 2)
        contributor.renderSynchronous(startFrame = 0L, frames = 480, syncOut)

        var hasSyncAudio = false
        for (sample in syncOut) {
            if (kotlin.math.abs(sample) > 0.0001f) {
                hasSyncAudio = true
                break
            }
        }
        assertTrue("renderSynchronous MUST mix overlay audio for offline export", hasSyncAudio)

        // Clean up
        ClipAudioContributor.isMainTrackMuted = false
        ClipAudioContributor.syncAudioOverlays(emptyList())
        contributor.release()
    }

    @Test
    fun testFadeInGainEnvelopeAttenuation() {
        val wavFile = createTestWav(sampleRate = 48000, channels = 2, durationSeconds = 3.0f, frequencyHz = 400.0)

        val emptyProject = Project()
        val snapshot = TimelineSnapshot(
            version = 1L,
            project = emptyProject
        )
        val snapshotHolder = SnapshotHolder(snapshot)
        val contributor = ClipAudioContributor(snapshotHolder)

        val fadeInDurationUs = 1_000_000L // 1 second fade-in
        val audioItem = OverlayItem(
            id = "fade_test_audio",
            type = LayerType.AUDIO,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L,
            mediaPath = wavFile.absolutePath,
            volume = 1.0f,
            fadeInUs = fadeInDurationUs,
            fadeOutUs = 0L,
            speed = 1.0f,
            sourceInUs = 0L
        )

        ClipAudioContributor.syncAudioOverlays(listOf(audioItem))

        // Block at t = 10ms (10,000us) -> fadeInGain is approx 10,000 / 1,000,000 = 0.01 (very quiet)
        val earlyOut = FloatArray(480 * 2)
        contributor.render(startFrame = 0L, frames = 480, out = earlyOut)
        var earlyMax = 0f
        for (s in earlyOut) earlyMax = maxOf(earlyMax, kotlin.math.abs(s))

        // Block at t = 1.5s (after fade-in completes) -> fadeInGain is 1.0f (full volume)
        val lateFrame = 48000L + 24000L // 1.5s
        val lateOut = FloatArray(480 * 2)
        contributor.render(startFrame = lateFrame, frames = 480, out = lateOut)
        var lateMax = 0f
        for (s in lateOut) lateMax = maxOf(lateMax, kotlin.math.abs(s))

        assertTrue("Early in fade-in envelope should be significantly quieter than full volume", earlyMax < lateMax * 0.1f)

        // Clean up
        ClipAudioContributor.syncAudioOverlays(emptyList())
        contributor.release()
    }
}
