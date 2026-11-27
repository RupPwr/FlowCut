package com.flowcut.app.audio

import com.flowcut.app.ui.timeline.BoundedTextCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class Phase3AudioAndTimelinePerfTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeWavFile(
        name: String,
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
        audioFormat: Short = 1,
        extraOddChunk: Boolean = false
    ): File {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream()
        fun writeU32(v: Int) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            out.write(b)
        }
        fun writeU16(v: Short) {
            val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
            out.write(b)
        }

        val oddChunkTotal = if (extraOddChunk) 8 + 3 + 1 else 0 // "JUNK" header + 3 bytes + 1 pad
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeU32(36 + oddChunkTotal + dataSize)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))

        if (extraOddChunk) {
            out.write("JUNK".toByteArray(Charsets.US_ASCII))
            writeU32(3) // odd size triggers RIFF even-byte padding
            out.write(byteArrayOf(1, 2, 3, 0))
        }

        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeU32(16)
        writeU16(audioFormat)
        writeU16(channels.toShort())
        writeU32(sampleRate)
        writeU32(sampleRate * channels * 2)
        writeU16((channels * 2).toShort())
        writeU16(16)

        out.write("data".toByteArray(Charsets.US_ASCII))
        writeU32(dataSize)
        val pcmBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcmBuf.putShort(s)
        out.write(pcmBuf.array())

        val f = tempFolder.newFile(name)
        f.writeBytes(out.toByteArray())
        return f
    }

    @Test
    fun wavHeaderParser_parsesStandardAndOddPaddedChunks() {
        val samples = ShortArray(480) { if (it % 2 == 0) 16384 else -16384 }
        val file = writeWavFile("odd_chunk.wav", sampleRate = 48000, channels = 2, samples = samples, extraOddChunk = true)

        RandomAccessFile(file, "r").use { raf ->
            val meta = WavHeaderParser.parse(raf)
            assertNotNull(meta)
            assertEquals(2, meta!!.channels)
            assertEquals(48000, meta.sampleRate)
            assertEquals(16, meta.bitsPerSample)
            assertEquals(240L, meta.totalSrcFrames)
            assertEquals(samples.size * 2L, meta.dataSize)
        }
    }

    @Test
    fun wavHeaderParser_rejectsNonPcmAndCorruptFiles() {
        val samples = ShortArray(64) { 1000 }
        val ieeeFloatWav = writeWavFile("float.wav", sampleRate = 48000, channels = 1, samples = samples, audioFormat = 3)
        RandomAccessFile(ieeeFloatWav, "r").use { raf ->
            assertNull(WavHeaderParser.parse(raf))
        }

        val corrupt = tempFolder.newFile("corrupt.wav").apply { writeBytes(ByteArray(20)) }
        RandomAccessFile(corrupt, "r").use { raf ->
            assertNull(WavHeaderParser.parse(raf))
        }
    }

    @Test
    fun decodeSmallWavToStereo48k_preservesSamplesAndResamples44100() {
        // 1. Direct 48kHz stereo fast path
        val stereo48 = ShortArray(200) { i -> if (i % 2 == 0) 16384 else -8192 }
        val f48 = writeWavFile("stereo48.wav", sampleRate = 48000, channels = 2, samples = stereo48)
        RandomAccessFile(f48, "r").use { raf ->
            val meta = WavHeaderParser.parse(raf)!!
            val pcm = WavHeaderParser.decodeSmallWavToStereo48k(raf, meta, 48000)
            assertNotNull(pcm)
            assertEquals(200, pcm!!.size)
            assertEquals(0.5f, pcm[0], 0.001f)
            assertEquals(-0.25f, pcm[1], 0.001f)
        }

        // 2. 44.1kHz mono -> 48kHz stereo chunked resampler
        val mono441 = ShortArray(4410) { 16384 } // 0.1s constant +0.5
        val f441 = writeWavFile("mono441.wav", sampleRate = 44100, channels = 1, samples = mono441)
        RandomAccessFile(f441, "r").use { raf ->
            val meta = WavHeaderParser.parse(raf)!!
            val pcm = WavHeaderParser.decodeSmallWavToStereo48k(raf, meta, 48000)
            assertNotNull(pcm)
            assertEquals(4800 * 2, pcm!!.size)
            // Mid-buffer sample should stay +0.5 on both stereo channels
            assertEquals(0.5f, pcm[1000], 0.01f)
            assertEquals(0.5f, pcm[1001], 0.01f)
        }
    }

    @Test
    fun equalPowerCrossfade_maintainsConstantPowerWithoutMidpointDip() {
        assertEquals(1f, ClipAudioContributor.equalPowerGainA(0f), 1e-4f)
        assertEquals(0f, ClipAudioContributor.equalPowerGainB(0f), 1e-4f)
        assertEquals(0f, ClipAudioContributor.equalPowerGainA(1f), 1e-4f)
        assertEquals(1f, ClipAudioContributor.equalPowerGainB(1f), 1e-4f)

        for (step in 0..10) {
            val p = step / 10f
            val a = ClipAudioContributor.equalPowerGainA(p)
            val b = ClipAudioContributor.equalPowerGainB(p)
            val power = a * a + b * b
            assertTrue("Expected equal power (~1.0) at p=$p, got $power", abs(power - 1f) < 1e-4f)
        }
    }

    @Test
    fun boundedTextCache_hitsWithoutRecomputingAndEnforcesLruCap() {
        val cache = BoundedTextCache<String, String>(maxEntriesPerBucket = 3, maxBuckets = 2)
        var computeCount = 0

        val v1 = cache.getOrPut("styleA", "00:01") { computeCount++; "layout_1" }
        val v1Again = cache.getOrPut("styleA", "00:01") { computeCount++; "layout_1_new" }
        assertEquals("layout_1", v1)
        assertEquals("layout_1", v1Again)
        assertEquals(1, computeCount)

        // Fill bucket past cap (3): access "00:01" stays recent, "00:02" gets evicted when 4th added
        cache.getOrPut("styleA", "00:02") { computeCount++; "layout_2" }
        cache.getOrPut("styleA", "00:03") { computeCount++; "layout_3" }
        cache.getOrPut("styleA", "00:01") { computeCount++; "should_hit" } // touch 00:01 -> MRU
        assertEquals(3, computeCount)

        cache.getOrPut("styleA", "00:04") { computeCount++; "layout_4" } // evicts 00:02
        assertEquals(3, cache.totalEntries())

        // 00:01 must still be cached (hit), 00:02 must recompute (miss)
        cache.getOrPut("styleA", "00:01") { computeCount++; "recomputed" }
        assertEquals(4, computeCount)
        cache.getOrPut("styleA", "00:02") { computeCount++; "layout_2_recomputed" }
        assertEquals(5, computeCount)
    }
}
