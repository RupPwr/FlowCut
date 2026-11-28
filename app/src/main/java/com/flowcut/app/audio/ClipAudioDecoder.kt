package com.flowcut.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.time.AUDIO_FRAMES_PER_MS
import com.flowcut.app.core.time.US_PER_MS
import com.flowcut.app.core.time.usToAudioFrames
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock

/**
 * Hardware audio decoder with a bounded sliding ring buffer.
 *
 * Enforces:
 * - Rule R10: Zero heap allocations in the per-block read loop.
 * - Rule R11: Timeline-to-source mapping via clip.map().sourceUs().
 * - Thread-safety: Synchronized codecLock prevents concurrent MediaCodec access between audio & prewarm threads.
 * - Failure isolation: Catches and recovers from MediaCodec IllegalStateException / errors cleanly.
 * - Bounded memory (~1.1 MB buffer per instance) preventing OOM.
 */
class ClipAudioDecoder(val filePath: String) {

    constructor(clip: Clip) : this(clip.path)

    companion object {
        const val TARGET_SAMPLE_RATE = 48000
        const val TARGET_CHANNELS = 2
        // Circular buffer holds 131,072 stereo frames (~2.73 seconds at 48kHz)
        const val BUFFER_FRAMES = 131072
        const val BUFFER_MASK = BUFFER_FRAMES - 1
        const val BUFFER_SAMPLES = BUFFER_FRAMES * TARGET_CHANNELS
        const val PREFILL_FRAMES = 48000 // 1 second ahead
        private const val DECODE_TIMEOUT_US = 0L
        private const val PCM_24BIT_PACKED = 21 // AudioFormat.ENCODING_PCM_24BIT_PACKED (API 31+)
        private const val PCM_32BIT = 22        // AudioFormat.ENCODING_PCM_32BIT (API 31+)

        fun framesToUs(frames: Long): Long = Math.floorDiv(frames * US_PER_MS, AUDIO_FRAMES_PER_MS)
    }

    private val codecLock = ReentrantLock()

    @Volatile var hasAudio: Boolean = false
        private set
    @Volatile var isPrepared: Boolean = false
        private set

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null

    private var srcSampleRate: Int = TARGET_SAMPLE_RATE
    private var srcChannels: Int = TARGET_CHANNELS
    private var srcPcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

    // Fast-path in-memory PCM data for small WAV files (<= 4 MB, zero allocations on audio thread)
    private var wavPcmData: FloatArray? = null
    private var wavTotalFrames: Int = 0

    // Bounded streaming path for large WAV files (> 4 MB) into pcmBuffer ring buffer (prevents OOM)
    private var wavStreamRaf: RandomAccessFile? = null
    private var wavMeta: WavMetadata? = null
    private var wavRemainingBytes: Long = 0L
    private var wavChunkBytes: ByteArray? = null
    private var wavChunkBuf: ByteBuffer? = null

    // Sample-continuous tracking to eliminate click/pop artifacts across split audio segments
    @Volatile var lastReadEndSourceFrame: Long = -1L

    // Bounded pre-allocated circular ring buffer
    private val pcmBuffer = FloatArray(BUFFER_SAMPLES)
    @Volatile private var bufferStartFrame: Long = 0L
    @Volatile private var writeFrame: Long = 0L
    @Volatile private var bufferedFrames: Int = 0

    private var inputEos: Boolean = false
    private var outputEos: Boolean = false
    private var pendingSeekSync: Boolean = false

    // Pre-allocated scratch buffers for decoding
    private val bufferInfo = MediaCodec.BufferInfo()
    private var prevSrcSampleL: Float = 0f
    private var prevSrcSampleR: Float = 0f
    private var resamplePhase: Double = 0.0

    init {
        prepare()
    }

    fun prepare() {
        codecLock.lock()
        try {
            if (isPrepared) return
            val file = File(filePath)
            if (!file.exists()) {
                hasAudio = false
                return
            }

            if (filePath.endsWith(".wav", ignoreCase = true)) {
                if (prepareWav(file)) {
                    return
                }
            }

            try {
                val ext = MediaExtractor()
                ext.setDataSource(filePath)

                var audioTrack = -1
                var audioFormat: MediaFormat? = null
                for (i in 0 until ext.trackCount) {
                    val f = ext.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrack = i
                        audioFormat = f
                        break
                    }
                }

                if (audioTrack == -1 || audioFormat == null) {
                    ext.release()
                    hasAudio = false
                    return
                }

                ext.selectTrack(audioTrack)
                extractor = ext

                val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
                val dec = MediaCodec.createDecoderByType(mime)
                codec = dec
                dec.configure(audioFormat, null, null, 0)
                dec.start()

                srcSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                } else TARGET_SAMPLE_RATE

                srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                } else TARGET_CHANNELS

                srcPcmEncoding = if (audioFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    audioFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else AudioFormat.ENCODING_PCM_16BIT

                hasAudio = true
                isPrepared = true

                // Prime the buffer from frame 0 with minimal iterations
                internalSeekToFrame(0L, maxIterations = 10, targetMinFrames = PREFILL_FRAMES / 2)
            } catch (_: Throwable) {
                hasAudio = false
                isPrepared = false
                internalRelease()
            }
        } finally {
            codecLock.unlock()
        }
    }

    private fun prepareWav(file: File): Boolean {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(file, "r")
            val meta = WavHeaderParser.parse(raf) ?: return false

            srcSampleRate = meta.sampleRate
            srcChannels = meta.channels
            srcPcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            if (meta.dataSize <= WavHeaderParser.MAX_IN_MEMORY_WAV_BYTES) {
                val pcm = WavHeaderParser.decodeSmallWavToStereo48k(raf, meta, TARGET_SAMPLE_RATE) ?: return false
                try { raf.close() } catch (_: Throwable) {}
                raf = null
                wavPcmData = pcm
                wavTotalFrames = pcm.size / TARGET_CHANNELS
                hasAudio = true
                isPrepared = true
                return true
            }

            // Large WAV (> 4 MB): stream via RandomAccessFile into the bounded 1.1 MB ring buffer
            val framesPerChunk = (16384 / meta.bytesPerSrcFrame).coerceAtLeast(1)
            val chunkBytes = ByteArray(framesPerChunk * meta.bytesPerSrcFrame)
            wavStreamRaf = raf
            wavMeta = meta
            wavChunkBytes = chunkBytes
            wavChunkBuf = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
            wavRemainingBytes = meta.dataSize
            raf.seek(meta.dataOffset)
            raf = null // ownership transferred to wavStreamRaf

            hasAudio = true
            isPrepared = true
            internalSeekToFrame(0L, maxIterations = 12, targetMinFrames = PREFILL_FRAMES / 2)
            return true
        } catch (_: Throwable) {
            // Fall back to MediaExtractor if parsing fails
            try { raf?.close() } catch (_: Throwable) {}
            try { wavStreamRaf?.close() } catch (_: Throwable) {}
            wavStreamRaf = null
            wavMeta = null
            wavChunkBytes = null
            wavChunkBuf = null
            wavRemainingBytes = 0L
        } finally {
            try { raf?.close() } catch (_: Throwable) {}
        }
        return false
    }

    fun seekToFrame(targetFrame: Long) {
        if (wavPcmData != null) return
        if (codecLock.tryLock()) {
            try {
                internalSeekToFrame(targetFrame, maxIterations = 20, targetMinFrames = PREFILL_FRAMES)
            } finally {
                codecLock.unlock()
            }
        }
    }

    private fun internalSeekToFrame(targetFrame: Long, maxIterations: Int = 40, targetMinFrames: Int = PREFILL_FRAMES) {
        if (!hasAudio || !isPrepared) return

        // Streaming large-WAV seek path (O(1) file seek, zero codec flush overhead)
        val raf = wavStreamRaf
        val meta = wavMeta
        if (raf != null && meta != null) {
            try {
                lastReadEndSourceFrame = -1L
                val clampedTargetFrame = maxOf(0L, targetFrame)
                val srcFrame = if (meta.sampleRate == TARGET_SAMPLE_RATE) {
                    clampedTargetFrame
                } else {
                    (clampedTargetFrame * meta.sampleRate.toLong()) / TARGET_SAMPLE_RATE.toLong()
                }.coerceIn(0L, meta.totalSrcFrames)

                val byteOffset = srcFrame * meta.bytesPerSrcFrame.toLong()
                raf.seek(meta.dataOffset + byteOffset)
                wavRemainingBytes = (meta.dataSize - byteOffset).coerceAtLeast(0L)

                inputEos = wavRemainingBytes <= 0L
                outputEos = wavRemainingBytes <= 0L
                bufferStartFrame = clampedTargetFrame
                writeFrame = clampedTargetFrame
                bufferedFrames = 0
                resamplePhase = 0.0
                prevSrcSampleL = 0f
                prevSrcSampleR = 0f

                var iterations = 0
                while (bufferedFrames < targetMinFrames && !outputEos && iterations < maxIterations) {
                    if (!internalDecodeChunk()) break
                    iterations++
                }
            } catch (_: Throwable) {
                outputEos = true
            }
            return
        }

        val ext = extractor ?: return
        val dec = codec ?: return

        try {
            lastReadEndSourceFrame = -1L
            val clampedTargetFrame = maxOf(0L, targetFrame)
            val seekTargetUs = framesToUs(clampedTargetFrame)

            ext.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            dec.flush()

            inputEos = false
            outputEos = false
            pendingSeekSync = true
            bufferStartFrame = clampedTargetFrame
            writeFrame = clampedTargetFrame
            bufferedFrames = 0
            resamplePhase = 0.0
            prevSrcSampleL = 0f
            prevSrcSampleR = 0f

            // Fill buffer forward past target + prefill (accounting for SEEK_TO_PREVIOUS_SYNC preroll)
            val desiredEndFrame = clampedTargetFrame + targetMinFrames
            var iterations = 0
            while ((writeFrame < desiredEndFrame || bufferedFrames < targetMinFrames) && !outputEos && iterations < maxIterations) {
                val decoded = internalDecodeChunk()
                if (!decoded && inputEos) break
                iterations++
            }
        } catch (_: Throwable) {
            // Decodes will fall back to silence
        }
    }

    /**
     * Reads [count] audio frames starting at [sourceFrame] and adds them to [out] at [outOffset]
     * scaled by [gain].
     *
     * Rule R10: Zero heap allocations in this method.
     * Non-blocking guarantee: Never stalls the real-time audio thread. If samples are not yet ready,
     * performs at most a non-blocking decode pass and outputs silence for unready samples.
     */
    fun read(sourceFrame: Long, count: Int, out: FloatArray, outOffset: Int, gain: Float) {
        if (!hasAudio || !isPrepared || count <= 0 || gain <= 0f) return

        // Snap to lastReadEndSourceFrame if within ±96 frames (±2ms) to eliminate split boundary clicks
        val actualSourceFrame = if (lastReadEndSourceFrame >= 0L && kotlin.math.abs(sourceFrame - lastReadEndSourceFrame) <= 96L) {
            lastReadEndSourceFrame
        } else {
            sourceFrame
        }

        val pcm = wavPcmData
        if (pcm != null) {
            var curFrame = actualSourceFrame
            var dstIdx = outOffset
            val totalFrames = wavTotalFrames
            for (i in 0 until count) {
                if (curFrame in 0 until totalFrames) {
                    val idx = (curFrame.toInt()) shl 1
                    out[dstIdx] += pcm[idx] * gain
                    out[dstIdx + 1] += pcm[idx + 1] * gain
                }
                curFrame++
                dstIdx += TARGET_CHANNELS
            }
            lastReadEndSourceFrame = actualSourceFrame + count
            return
        }

        // Non-blocking real-time audio thread: tryLock prevents blocking AudioTrack callback
        if (!codecLock.tryLock()) {
            return
        }
        try {
            val start = bufferStartFrame
            val countBuffered = bufferedFrames
            if (actualSourceFrame < start || actualSourceFrame > start + countBuffered + 24_000L) {
                internalSeekToFrame(actualSourceFrame, maxIterations = 3, targetMinFrames = count * 2)
            } else if (actualSourceFrame + count > start + countBuffered) {
                internalDecodeChunk()
            } else {
                val remaining = (start + countBuffered) - (actualSourceFrame + count)
                if (remaining < PREFILL_FRAMES / 2 && !outputEos) {
                    internalDecodeChunk()
                }
            }

            var curFrame = actualSourceFrame
            var dstIdx = outOffset
            val curStart = bufferStartFrame
            val curBuffered = bufferedFrames

            for (i in 0 until count) {
                if (curFrame in curStart until (curStart + curBuffered)) {
                    val ringIdx = ((curFrame and BUFFER_MASK.toLong()).toInt()) shl 1
                    out[dstIdx] += pcmBuffer[ringIdx] * gain
                    out[dstIdx + 1] += pcmBuffer[ringIdx + 1] * gain
                }
                curFrame++
                dstIdx += TARGET_CHANNELS
            }
            lastReadEndSourceFrame = actualSourceFrame + count
        } finally {
            codecLock.unlock()
        }
    }

    /**
     * Reads [count] timeline audio frames starting at [sourceStartFrame] playing at [speed] multiplier
     * (0.2f to 10.0f) using zero-heap-allocation linear interpolation resampling (Rule R10).
     */
    fun readResampled(sourceStartFrame: Long, count: Int, speed: Float, out: FloatArray, outOffset: Int, gain: Float) {
        if (!hasAudio || !isPrepared || count <= 0 || gain <= 0f) return
        if (kotlin.math.abs(speed - 1.0f) < 0.01f) {
            read(sourceStartFrame, count, out, outOffset, gain)
            return
        }

        // Cap supported speed range between 0.1f and 10.0f
        if (speed > 10.0f || speed <= 0f) {
            return
        }

        // Snap to lastReadEndSourceFrame if within ±96 frames (±2ms) to eliminate split boundary clicks
        val actualStartFrame = if (lastReadEndSourceFrame >= 0L && kotlin.math.abs(sourceStartFrame - lastReadEndSourceFrame) <= 96L) {
            lastReadEndSourceFrame
        } else {
            sourceStartFrame
        }

        val pcm = wavPcmData
        if (pcm != null) {
            val totalFrames = wavTotalFrames
            var dstIdx = outOffset
            for (i in 0 until count) {
                val exactSrcPos = actualStartFrame.toDouble() + i.toDouble() * speed.toDouble()
                val f0 = exactSrcPos.toLong()
                val f1 = f0 + 1L
                val frac = (exactSrcPos - f0.toDouble()).toFloat()

                if (f0 in 0 until totalFrames) {
                    val idx0 = (f0.toInt()) shl 1
                    val s0L = pcm[idx0]
                    val s0R = pcm[idx0 + 1]

                    val sL: Float
                    val sR: Float
                    if (f1 in 0 until totalFrames) {
                        val idx1 = (f1.toInt()) shl 1
                        val s1L = pcm[idx1]
                        val s1R = pcm[idx1 + 1]
                        sL = s0L * (1f - frac) + s1L * frac
                        sR = s0R * (1f - frac) + s1R * frac
                    } else {
                        sL = s0L
                        sR = s0R
                    }

                    out[dstIdx] += sL * gain
                    out[dstIdx + 1] += sR * gain
                }
                dstIdx += TARGET_CHANNELS
            }
            val consumedSrcFrames = (count.toDouble() * speed.toDouble()).toLong()
            lastReadEndSourceFrame = actualStartFrame + consumedSrcFrames
            return
        }

        // Non-blocking real-time audio thread: tryLock prevents blocking AudioTrack callback
        if (!codecLock.tryLock()) {
            return
        }
        try {
            val totalNeededSrcFrames = (count * speed).toInt() + 2
            val targetEnd = actualStartFrame + totalNeededSrcFrames
            val start = bufferStartFrame
            val countBuffered = bufferedFrames

            if (actualStartFrame < start || actualStartFrame > start + countBuffered + 24_000L) {
                val seekIterations = when {
                    speed >= 8.0f -> 12
                    speed >= 4.0f -> 8
                    else -> 4
                }
                val seekMinFrames = maxOf(totalNeededSrcFrames, if (speed > 4.0f) totalNeededSrcFrames * 2 else PREFILL_FRAMES / 2)
                internalSeekToFrame(actualStartFrame, maxIterations = seekIterations, targetMinFrames = seekMinFrames)
            } else if (targetEnd > start + countBuffered) {
                val maxCatchupChunks = when {
                    speed >= 8.0f -> 10
                    speed >= 4.0f -> 6
                    speed >= 2.0f -> 3
                    else -> 2
                }
                var decodeCount = 0
                while (bufferStartFrame + bufferedFrames < targetEnd && !outputEos && decodeCount < maxCatchupChunks) {
                    val produced = internalDecodeChunk(timeoutUs = 0L)
                    decodeCount++
                    if (!produced) break
                }
            } else {
                val remaining = (start + countBuffered) - targetEnd
                if (remaining < PREFILL_FRAMES / 2 && !outputEos) {
                    internalDecodeChunk(timeoutUs = 0L)
                }
            }

            val curStart = bufferStartFrame
            val curBuffered = bufferedFrames
            val curEnd = curStart + curBuffered

            var dstIdx = outOffset
            for (i in 0 until count) {
                val exactSrcPos = actualStartFrame.toDouble() + i.toDouble() * speed.toDouble()
                val f0 = exactSrcPos.toLong()
                val f1 = f0 + 1L
                val frac = (exactSrcPos - f0.toDouble()).toFloat()

                if (f0 in curStart until curEnd) {
                    val ringIdx0 = ((f0 and BUFFER_MASK.toLong()).toInt()) shl 1
                    val s0L = pcmBuffer[ringIdx0]
                    val s0R = pcmBuffer[ringIdx0 + 1]

                    val sL: Float
                    val sR: Float
                    if (f1 in curStart until curEnd) {
                        val ringIdx1 = ((f1 and BUFFER_MASK.toLong()).toInt()) shl 1
                        val s1L = pcmBuffer[ringIdx1]
                        val s1R = pcmBuffer[ringIdx1 + 1]
                        sL = s0L * (1f - frac) + s1L * frac
                        sR = s0R * (1f - frac) + s1R * frac
                    } else {
                        sL = s0L
                        sR = s0R
                    }

                    out[dstIdx] += sL * gain
                    out[dstIdx + 1] += sR * gain
                }
                dstIdx += TARGET_CHANNELS
            }
            val consumedSrcFrames = (count.toDouble() * speed.toDouble()).toLong()
            lastReadEndSourceFrame = actualStartFrame + consumedSrcFrames
        } finally {
            codecLock.unlock()
        }
    }

    /**
     * Synchronous accurate audio read for offline export with variable speed linear resampling.
     */
    fun readResampledSynchronous(sourceStartFrame: Long, count: Int, speed: Float, out: FloatArray, outOffset: Int, gain: Float) {
        if (!hasAudio || !isPrepared || count <= 0 || gain <= 0f) return
        if (wavPcmData != null) {
            readResampled(sourceStartFrame, count, speed, out, outOffset, gain)
            return
        }
        if (kotlin.math.abs(speed - 1.0f) < 0.01f) {
            readSynchronous(sourceStartFrame, count, out, outOffset, gain)
            return
        }
        codecLock.lock()
        try {
            val actualStartFrame = if (lastReadEndSourceFrame >= 0L && kotlin.math.abs(sourceStartFrame - lastReadEndSourceFrame) <= 96L) {
                lastReadEndSourceFrame
            } else {
                sourceStartFrame
            }
            val totalNeededSrcFrames = (count * speed).toInt() + 2
            val targetEnd = actualStartFrame + totalNeededSrcFrames
            if (actualStartFrame < bufferStartFrame || actualStartFrame >= bufferStartFrame + bufferedFrames) {
                internalSeekToFrame(actualStartFrame)
            }

            var retries = 0
            while (bufferStartFrame + bufferedFrames < targetEnd && !outputEos && retries < 60) {
                val decoded = internalDecodeChunk(timeoutUs = 5_000L)
                if (!decoded && inputEos) {
                    retries++
                } else if (!decoded) {
                    retries++
                }
            }

            val curStart = bufferStartFrame
            val curBuffered = bufferedFrames
            val curEnd = curStart + curBuffered

            var dstIdx = outOffset
            for (i in 0 until count) {
                val exactSrcPos = actualStartFrame.toDouble() + i.toDouble() * speed.toDouble()
                val f0 = exactSrcPos.toLong()
                val f1 = f0 + 1L
                val frac = (exactSrcPos - f0.toDouble()).toFloat()

                if (f0 in curStart until curEnd) {
                    val ringIdx0 = ((f0 and BUFFER_MASK.toLong()).toInt()) shl 1
                    val s0L = pcmBuffer[ringIdx0]
                    val s0R = pcmBuffer[ringIdx0 + 1]

                    val sL: Float
                    val sR: Float
                    if (f1 in curStart until curEnd) {
                        val ringIdx1 = ((f1 and BUFFER_MASK.toLong()).toInt()) shl 1
                        val s1L = pcmBuffer[ringIdx1]
                        val s1R = pcmBuffer[ringIdx1 + 1]
                        sL = s0L * (1f - frac) + s1L * frac
                        sR = s0R * (1f - frac) + s1R * frac
                    } else {
                        sL = s0L
                        sR = s0R
                    }

                    out[dstIdx] += sL * gain
                    out[dstIdx + 1] += sR * gain
                }
                dstIdx += TARGET_CHANNELS
            }
            val consumedSrcFrames = (count.toDouble() * speed.toDouble()).toLong()
            lastReadEndSourceFrame = actualStartFrame + consumedSrcFrames
        } finally {
            codecLock.unlock()
        }
    }

    /**
     * Synchronous accurate audio read for offline export.
     * Decodes source audio synchronously up to [sourceFrame] + [count] instead of dropping to silence.
     */
    fun readSynchronous(sourceFrame: Long, count: Int, out: FloatArray, outOffset: Int, gain: Float) {
        if (!hasAudio || !isPrepared || count <= 0 || gain <= 0f) return
        if (wavPcmData != null) {
            read(sourceFrame, count, out, outOffset, gain)
            return
        }
        codecLock.lock()
        try {
            val actualSourceFrame = if (lastReadEndSourceFrame >= 0L && kotlin.math.abs(sourceFrame - lastReadEndSourceFrame) <= 96L) {
                lastReadEndSourceFrame
            } else {
                sourceFrame
            }
            val targetEnd = actualSourceFrame + count
            if (actualSourceFrame < bufferStartFrame || actualSourceFrame >= bufferStartFrame + bufferedFrames) {
                internalSeekToFrame(actualSourceFrame)
            }

            var retries = 0
            while (bufferStartFrame + bufferedFrames < targetEnd && !outputEos && retries < 60) {
                val decoded = internalDecodeChunk(timeoutUs = 5_000L)
                if (!decoded && inputEos) {
                    retries++
                } else if (!decoded) {
                    retries++
                }
            }

            var curFrame = actualSourceFrame
            var dstIdx = outOffset
            val curStart = bufferStartFrame
            val curBuffered = bufferedFrames

            for (i in 0 until count) {
                if (curFrame in curStart until (curStart + curBuffered)) {
                    val ringIdx = ((curFrame and BUFFER_MASK.toLong()).toInt()) shl 1
                    out[dstIdx] += pcmBuffer[ringIdx] * gain
                    out[dstIdx + 1] += pcmBuffer[ringIdx + 1] * gain
                }
                curFrame++
                dstIdx += TARGET_CHANNELS
            }
            lastReadEndSourceFrame = actualSourceFrame + count
        } finally {
            codecLock.unlock()
        }
    }

    fun decodeChunk(): Boolean {
        codecLock.lock()
        try {
            return internalDecodeChunk()
        } finally {
            codecLock.unlock()
        }
    }

    private fun internalDecodeChunk(timeoutUs: Long = DECODE_TIMEOUT_US): Boolean {
        val raf = wavStreamRaf
        val meta = wavMeta
        val chunkBytes = wavChunkBytes
        val chunkBuf = wavChunkBuf
        if (raf != null && meta != null && chunkBytes != null && chunkBuf != null) {
            if (wavRemainingBytes <= 0L) {
                inputEos = true
                outputEos = true
                return false
            }
            return try {
                val maxRead = minOf(chunkBytes.size.toLong(), wavRemainingBytes).toInt()
                val alignedMax = (maxRead / meta.bytesPerSrcFrame) * meta.bytesPerSrcFrame
                if (alignedMax <= 0) {
                    inputEos = true
                    outputEos = true
                    return false
                }
                val read = raf.read(chunkBytes, 0, alignedMax)
                if (read <= 0) {
                    wavRemainingBytes = 0L
                    inputEos = true
                    outputEos = true
                    return false
                }
                val usable = (read / meta.bytesPerSrcFrame) * meta.bytesPerSrcFrame
                wavRemainingBytes = (wavRemainingBytes - read).coerceAtLeast(0L)
                if (wavRemainingBytes <= 0L) {
                    inputEos = true
                    outputEos = true
                }
                if (usable > 0) {
                    chunkBuf.position(0)
                    chunkBuf.limit(usable)
                    appendPcm(chunkBuf)
                    true
                } else {
                    false
                }
            } catch (_: Throwable) {
                outputEos = true
                false
            }
        }

        val ext = extractor ?: return false
        val dec = codec ?: return false

        var produced = false

        try {
            // 1. Feed input buffer
            if (!inputEos) {
                val inIndex = dec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inBuf = dec.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        inBuf.clear()
                        val sampleSize = ext.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, sampleSize, ext.sampleTime.coerceAtLeast(0L), 0)
                            ext.advance()
                        }
                    }
                }
            }

            // 2. Drain output buffer
            val outIndex = dec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outIndex >= 0) {
                val outBuf = dec.getOutputBuffer(outIndex)
                if (outBuf != null && bufferInfo.size > 0) {
                    if (pendingSeekSync) {
                        pendingSeekSync = false
                        val ptsUs = bufferInfo.presentationTimeUs
                        if (ptsUs >= 0L) {
                            val actualStartFrame = usToAudioFrames(ptsUs)
                            bufferStartFrame = actualStartFrame
                            writeFrame = actualStartFrame
                            bufferedFrames = 0
                        }
                    }
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    appendPcm(outBuf)
                    produced = true
                }
                dec.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputEos = true
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = dec.outputFormat
                if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    srcSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                }
                if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    srcChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                }
                if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    srcPcmEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
            }
        } catch (_: Throwable) {
            // Codec transient error: fail gracefully without crashing
            return false
        }

        return produced
    }

    private fun readOtherPcmSample(byteBuf: ByteBuffer, encoding: Int): Float {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> ((byteBuf.get().toInt() and 0xFF) - 128) / 128.0f
            PCM_24BIT_PACKED -> {
                val b0 = byteBuf.get().toInt() and 0xFF
                val b1 = byteBuf.get().toInt() and 0xFF
                val b2 = byteBuf.get().toInt() // sign-extended MSB
                val s24 = (b2 shl 16) or (b1 shl 8) or b0
                s24 / 8388608.0f
            }
            PCM_32BIT -> byteBuf.int / 2147483648.0f
            else -> byteBuf.short / 32768.0f
        }
    }

    private fun appendPcm(byteBuf: ByteBuffer) {
        byteBuf.order(ByteOrder.LITTLE_ENDIAN)

        val encoding = srcPcmEncoding
        val channels = maxOf(1, srcChannels)
        val safeRate = maxOf(8000, srcSampleRate)

        if (encoding == AudioFormat.ENCODING_PCM_8BIT || encoding == PCM_24BIT_PACKED || encoding == PCM_32BIT) {
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                PCM_24BIT_PACKED -> 3
                else -> 4
            }
            val bytesPerFrame = bytesPerSample * channels
            val totalSrcFrames = byteBuf.remaining() / bytesPerFrame
            if (totalSrcFrames <= 0) return

            if (safeRate == TARGET_SAMPLE_RATE) {
                for (f in 0 until totalSrcFrames) {
                    val sL = readOtherPcmSample(byteBuf, encoding)
                    val sR = if (channels > 1) readOtherPcmSample(byteBuf, encoding) else sL
                    for (c in 2 until channels) {
                        readOtherPcmSample(byteBuf, encoding)
                    }
                    writeSampleToRing(sL, sR)
                }
                return
            }

            val step = safeRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
            for (f in 0 until totalSrcFrames) {
                val sL = readOtherPcmSample(byteBuf, encoding)
                val sR = if (channels > 1) readOtherPcmSample(byteBuf, encoding) else sL
                for (c in 2 until channels) {
                    readOtherPcmSample(byteBuf, encoding)
                }
                while (resamplePhase < 1.0) {
                    val frac = resamplePhase.toFloat()
                    val outL = prevSrcSampleL * (1f - frac) + sL * frac
                    val outR = prevSrcSampleR * (1f - frac) + sR * frac
                    writeSampleToRing(outL, outR)
                    resamplePhase += step
                }
                resamplePhase -= 1.0
                prevSrcSampleL = sL
                prevSrcSampleR = sR
            }
            return
        }

        val is16Bit = encoding != AudioFormat.ENCODING_PCM_FLOAT

        if (is16Bit) {
            val shortBuf = byteBuf.asShortBuffer()
            val availableShorts = shortBuf.remaining()
            val totalSrcFrames = availableShorts / channels
            if (totalSrcFrames <= 0) return

            // Direct 48kHz stereo fast-path
            if (srcSampleRate == TARGET_SAMPLE_RATE && channels == TARGET_CHANNELS) {
                for (f in 0 until totalSrcFrames) {
                    val sL = shortBuf.get() / 32768.0f
                    val sR = shortBuf.get() / 32768.0f
                    writeSampleToRing(sL, sR)
                }
                return
            }

            // Direct 48kHz mono fast-path
            if (srcSampleRate == TARGET_SAMPLE_RATE && channels == 1) {
                for (f in 0 until totalSrcFrames) {
                    val s = shortBuf.get() / 32768.0f
                    writeSampleToRing(s, s)
                }
                return
            }

            // Resampling path (e.g. 44.1 kHz -> 48 kHz)
            val step = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
            var srcIndex = 0
            while (srcIndex < totalSrcFrames) {
                val sL: Float
                val sR: Float
                if (channels == 1) {
                    val mono = shortBuf.get() / 32768.0f
                    sL = mono
                    sR = mono
                } else {
                    sL = shortBuf.get() / 32768.0f
                    sR = shortBuf.get() / 32768.0f
                    for (c in 2 until channels) {
                        shortBuf.get()
                    }
                }

                while (resamplePhase < 1.0) {
                    val frac = resamplePhase.toFloat()
                    val outL = prevSrcSampleL * (1f - frac) + sL * frac
                    val outR = prevSrcSampleR * (1f - frac) + sR * frac
                    writeSampleToRing(outL, outR)
                    resamplePhase += step
                }
                resamplePhase -= 1.0
                prevSrcSampleL = sL
                prevSrcSampleR = sR
                srcIndex++
            }
        } else {
            // Float PCM path (with full resampling support when srcSampleRate != TARGET_SAMPLE_RATE)
            val floatBuf = byteBuf.asFloatBuffer()
            val totalSrcFrames = floatBuf.remaining() / channels
            if (totalSrcFrames <= 0) return

            if (srcSampleRate == TARGET_SAMPLE_RATE) {
                for (f in 0 until totalSrcFrames) {
                    val sL = floatBuf.get()
                    val sR = if (channels > 1) floatBuf.get() else sL
                    for (c in 2 until channels) {
                        floatBuf.get()
                    }
                    writeSampleToRing(sL, sR)
                }
                return
            }

            val step = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
            for (f in 0 until totalSrcFrames) {
                val sL = floatBuf.get()
                val sR = if (channels > 1) floatBuf.get() else sL
                for (c in 2 until channels) {
                    floatBuf.get()
                }
                while (resamplePhase < 1.0) {
                    val frac = resamplePhase.toFloat()
                    val outL = prevSrcSampleL * (1f - frac) + sL * frac
                    val outR = prevSrcSampleR * (1f - frac) + sR * frac
                    writeSampleToRing(outL, outR)
                    resamplePhase += step
                }
                resamplePhase -= 1.0
                prevSrcSampleL = sL
                prevSrcSampleR = sR
            }
        }
    }

    private fun writeSampleToRing(sampleL: Float, sampleR: Float) {
        val ringIdx = ((writeFrame and BUFFER_MASK.toLong()).toInt()) shl 1
        pcmBuffer[ringIdx] = sampleL.coerceIn(-1f, 1f)
        pcmBuffer[ringIdx + 1] = sampleR.coerceIn(-1f, 1f)

        writeFrame++
        if (bufferedFrames < BUFFER_FRAMES) {
            bufferedFrames++
        } else {
            bufferStartFrame++
        }
    }

    fun release() {
        codecLock.lock()
        try {
            internalRelease()
        } finally {
            codecLock.unlock()
        }
    }

    private fun internalRelease() {
        try {
            codec?.stop()
        } catch (_: Throwable) {}
        try {
            codec?.release()
        } catch (_: Throwable) {}
        codec = null

        try {
            extractor?.release()
        } catch (_: Throwable) {}
        extractor = null

        try {
            wavStreamRaf?.close()
        } catch (_: Throwable) {}
        wavStreamRaf = null
        wavMeta = null
        wavChunkBytes = null
        wavChunkBuf = null
        wavRemainingBytes = 0L

        hasAudio = false
        isPrepared = false
        pendingSeekSync = false
        bufferedFrames = 0
        wavPcmData = null
        wavTotalFrames = 0
        lastReadEndSourceFrame = -1L
    }
}
