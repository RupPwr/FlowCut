package com.flowcut.app.audio

import java.io.RandomAccessFile

/**
 * Metadata extracted from a standard RIFF/WAVE PCM header without loading the file into RAM.
 */
data class WavMetadata(
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long
) {
    val bytesPerSrcFrame: Int
        get() = channels * (bitsPerSample / 8)

    val totalSrcFrames: Long
        get() = if (bytesPerSrcFrame > 0) dataSize / bytesPerSrcFrame else 0L
}

/**
 * Streaming RIFF/WAVE header parser.
 *
 * Reads only the RIFF header chunks via [RandomAccessFile] (a few dozen bytes) instead of
 * calling File.readBytes(), preventing OutOfMemoryError on long WAV files on low-RAM Android devices.
 */
object WavHeaderParser {
    // Files <= 4 MB (~20s stereo 48kHz PCM) load into compact resampled RAM for zero-latency SFX/beats.
    // Larger WAV files stream directly from RandomAccessFile into ClipAudioDecoder's 1.1 MB ring buffer.
    const val MAX_IN_MEMORY_WAV_BYTES: Long = 4L * 1024L * 1024L

    private const val MAX_CHUNKS_TO_SCAN = 128

    fun parse(raf: RandomAccessFile): WavMetadata? {
        val fileLen = raf.length()
        if (fileLen < 44L) return null

        raf.seek(0L)
        val header = ByteArray(12)
        if (readFullySafe(raf, header, 0, 12) < 12) return null

        if (ascii4(header, 0) != "RIFF" || ascii4(header, 8) != "WAVE") {
            return null
        }

        var foundFmt = false
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1L
        var dataSize = 0L

        val chunkHeader = ByteArray(8)
        val fmtBuf = ByteArray(16)
        var pos = 12L
        var chunksScanned = 0

        while (pos + 8L <= fileLen && chunksScanned < MAX_CHUNKS_TO_SCAN) {
            chunksScanned++
            raf.seek(pos)
            if (readFullySafe(raf, chunkHeader, 0, 8) < 8) break

            val chunkId = ascii4(chunkHeader, 0)
            val chunkSize = readUInt32LE(chunkHeader, 4)
            val chunkDataStart = pos + 8L

            if (chunkId == "fmt ") {
                if (chunkSize < 16L || chunkDataStart + 16L > fileLen) return null
                if (readFullySafe(raf, fmtBuf, 0, 16) < 16) return null
                audioFormat = readUInt16LE(fmtBuf, 0)
                channels = readUInt16LE(fmtBuf, 2)
                sampleRate = readUInt32LE(fmtBuf, 4).toInt()
                bitsPerSample = readUInt16LE(fmtBuf, 14)
                foundFmt = true
            } else if (chunkId == "data") {
                dataOffset = chunkDataStart
                val available = (fileLen - chunkDataStart).coerceAtLeast(0L)
                dataSize = minOf(chunkSize, available)
                if (foundFmt) break
            }

            // RIFF chunks are word-aligned (even byte boundary)
            val paddedSize = chunkSize + (chunkSize and 1L)
            val nextPos = chunkDataStart + paddedSize
            if (nextPos <= pos || nextPos > fileLen) break
            pos = nextPos
        }

        if (!foundFmt || dataOffset < 0L || dataSize <= 0L) return null
        // Only handle uncompressed 16-bit PCM here; other WAV formats fall back to MediaExtractor
        if (audioFormat != 1 || bitsPerSample != 16) return null
        if (channels !in 1..8 || sampleRate !in 8000..192000) return null

        val bytesPerFrame = channels * 2
        val alignedDataSize = (dataSize / bytesPerFrame) * bytesPerFrame
        if (alignedDataSize <= 0L) return null

        return WavMetadata(
            channels = channels,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset,
            dataSize = alignedDataSize
        )
    }

    /**
     * Decodes a small 16-bit PCM WAV file into interleaved 48kHz stereo float samples using
     * only a fixed 16 KB read buffer (no File.readBytes(), no intermediate FloatArray copy).
     */
    fun decodeSmallWavToStereo48k(
        raf: RandomAccessFile,
        meta: WavMetadata,
        targetSampleRate: Int = 48000
    ): FloatArray? {
        if (meta.dataSize > MAX_IN_MEMORY_WAV_BYTES) return null
        val srcFramesLong = meta.totalSrcFrames
        if (srcFramesLong <= 0L || srcFramesLong > Int.MAX_VALUE) return null
        val srcFrames = srcFramesLong.toInt()
        val channels = meta.channels
        val bytesPerFrame = meta.bytesPerSrcFrame

        val targetFramesLong = if (meta.sampleRate == targetSampleRate) {
            srcFramesLong
        } else {
            (srcFramesLong * targetSampleRate.toLong()) / meta.sampleRate.toLong()
        }
        if (targetFramesLong <= 0L || targetFramesLong > Int.MAX_VALUE / 2) return null
        val targetFrames = targetFramesLong.toInt()
        val pcm = FloatArray(targetFrames * 2)

        raf.seek(meta.dataOffset)
        // Align chunk buffer to a whole number of source frames (~16 KB)
        val framesPerChunk = (16384 / bytesPerFrame).coerceAtLeast(1)
        val chunkBuf = ByteArray(framesPerChunk * bytesPerFrame)
        var remainingBytes = meta.dataSize

        if (meta.sampleRate == targetSampleRate) {
            // Fast 1:1 copy without resampling
            var outFrame = 0
            while (remainingBytes > 0L && outFrame < targetFrames) {
                val toRead = minOf(chunkBuf.size.toLong(), remainingBytes).toInt()
                val read = readFullySafe(raf, chunkBuf, 0, toRead)
                if (read < bytesPerFrame) break
                remainingBytes -= read
                val chunkFrames = read / bytesPerFrame
                var bIdx = 0
                for (f in 0 until chunkFrames) {
                    if (outFrame >= targetFrames) break
                    val sL = readInt16LE(chunkBuf, bIdx) / 32768.0f
                    val sR = if (channels > 1) readInt16LE(chunkBuf, bIdx + 2) / 32768.0f else sL
                    val dstIdx = outFrame shl 1
                    pcm[dstIdx] = sL.coerceIn(-1f, 1f)
                    pcm[dstIdx + 1] = sR.coerceIn(-1f, 1f)
                    outFrame++
                    bIdx += bytesPerFrame
                }
            }
            return pcm
        }

        // Streaming linear resampler directly from 16 KB chunks into output FloatArray
        val step = meta.sampleRate.toDouble() / targetSampleRate.toDouble()
        var resamplePhase = 0.0
        var prevL = 0f
        var prevR = 0f
        var hasPrev = false
        var outFrame = 0

        while (remainingBytes > 0L && outFrame < targetFrames) {
            val toRead = minOf(chunkBuf.size.toLong(), remainingBytes).toInt()
            val read = readFullySafe(raf, chunkBuf, 0, toRead)
            if (read < bytesPerFrame) break
            remainingBytes -= read
            val chunkFrames = read / bytesPerFrame
            var bIdx = 0
            for (f in 0 until chunkFrames) {
                val curL = (readInt16LE(chunkBuf, bIdx) / 32768.0f).coerceIn(-1f, 1f)
                val curR = if (channels > 1) (readInt16LE(chunkBuf, bIdx + 2) / 32768.0f).coerceIn(-1f, 1f) else curL
                bIdx += bytesPerFrame

                if (!hasPrev) {
                    prevL = curL
                    prevR = curR
                    hasPrev = true
                }

                while (resamplePhase < 1.0 && outFrame < targetFrames) {
                    val frac = resamplePhase.toFloat()
                    val dstIdx = outFrame shl 1
                    pcm[dstIdx] = prevL * (1f - frac) + curL * frac
                    pcm[dstIdx + 1] = prevR * (1f - frac) + curR * frac
                    outFrame++
                    resamplePhase += step
                }
                resamplePhase -= 1.0
                prevL = curL
                prevR = curR
            }
        }
        return pcm
    }

    private fun readInt16LE(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt() // sign-extended
        return (hi shl 8) or lo
    }

    private fun readFullySafe(raf: RandomAccessFile, dst: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = raf.read(dst, offset + total, length - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun ascii4(buf: ByteArray, offset: Int): String =
        String(buf, offset, 4, Charsets.US_ASCII)

    private fun readUInt16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32LE(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFFL) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24)
}
