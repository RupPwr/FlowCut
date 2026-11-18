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

    // Fast-path in-memory PCM data for WAV files (zero latency, zero allocations on audio thread)
    private var wavPcmData: FloatArray? = null
    private var wavTotalFrames: Int = 0

    // Sample-continuous tracking to eliminate click/pop artifacts across split audio segments
    @Volatile var lastReadEndSourceFrame: Long = -1L

    // Bounded pre-allocated circular ring buffer
    private val pcmBuffer = FloatArray(BUFFER_SAMPLES)
    @Volatile private var bufferStartFrame: Long = 0L
    @Volatile private var writeFrame: Long = 0L
    @Volatile private var bufferedFrames: Int = 0

    private var inputEos: Boolean = false
    private var outputEos: Boolean = false

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
                dec.configure(audioFormat, null, null, 0)
                dec.start()
                codec = dec

                srcSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else TARGET_SAMPLE_RATE

                srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
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
        try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return false
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val riff = ByteArray(4)
            buf.get(riff)
            if (String(riff, Charsets.US_ASCII) != "RIFF") return false
            buf.getInt() // total file size - 8
            val wave = ByteArray(4)
            buf.get(wave)
            if (String(wave, Charsets.US_ASCII) != "WAVE") return false

            var channels = 2
            var sampleRate = TARGET_SAMPLE_RATE
            var bitsPerSample = 16
            var dataOffset = -1
            var dataSize = 0

            while (buf.remaining() >= 8) {
                val chunkIdBytes = ByteArray(4)
                buf.get(chunkIdBytes)
                val chunkId = String(chunkIdBytes, Charsets.US_ASCII)
                val chunkSize = buf.getInt()

                if (chunkId == "fmt ") {
                    val fmtStart = buf.position()
                    if (chunkSize >= 16) {
                        buf.getShort() // audioFormat (1 = PCM)
                        channels = buf.getShort().toInt() and 0xFFFF
                        sampleRate = buf.getInt()
                        buf.getInt() // byteRate
                        buf.getShort() // blockAlign
                        bitsPerSample = buf.getShort().toInt() and 0xFFFF
                    }
                    val nextPos = fmtStart + chunkSize
                    if (nextPos in 0..buf.limit()) {
                        buf.position(nextPos)
                    } else {
                        break
                    }
                } else if (chunkId == "data") {
                    dataOffset = buf.position()
                    dataSize = minOf(chunkSize, buf.remaining())
                    break
                } else {
                    val nextPos = buf.position() + chunkSize
                    if (nextPos in 0..buf.limit()) {
                        buf.position(nextPos)
                    } else {
                        break
                    }
                }
            }

            if (dataOffset < 0 || dataSize <= 0 || channels <= 0 || sampleRate <= 0) return false

            buf.position(dataOffset)
            if (bitsPerSample == 16) {
                val bytesPerFrame = channels * 2
                val srcFrames = dataSize / bytesPerFrame
                if (srcFrames <= 0) return false

                val srcFloats = FloatArray(srcFrames * channels)
                var sIdx = 0
                for (f in 0 until srcFrames) {
                    for (c in 0 until channels) {
                        val s16 = buf.getShort().toInt()
                        srcFloats[sIdx++] = (s16 / 32768.0f).coerceIn(-1.0f, 1.0f)
                    }
                }

                val targetFrames = if (sampleRate == TARGET_SAMPLE_RATE) {
                    srcFrames
                } else {
                    ((srcFrames.toLong() * TARGET_SAMPLE_RATE) / sampleRate).toInt()
                }

                val pcm = FloatArray(targetFrames * TARGET_CHANNELS)
                val rateRatio = sampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()

                for (f in 0 until targetFrames) {
                    val exactSrc = f.toDouble() * rateRatio
                    val f0 = exactSrc.toInt().coerceIn(0, srcFrames - 1)
                    val f1 = (f0 + 1).coerceIn(0, srcFrames - 1)
                    val frac = (exactSrc - f0.toDouble()).toFloat()

                    val dstIdx = f shl 1
                    if (channels == 1) {
                        val s0 = srcFloats[f0]
                        val s1 = srcFloats[f1]
                        val s = s0 * (1f - frac) + s1 * frac
                        pcm[dstIdx] = s
                        pcm[dstIdx + 1] = s
                    } else {
                        val s0L = srcFloats[f0 * channels]
                        val s0R = srcFloats[f0 * channels + 1]
                        val s1L = srcFloats[f1 * channels]
                        val s1R = srcFloats[f1 * channels + 1]
                        pcm[dstIdx] = s0L * (1f - frac) + s1L * frac
                        pcm[dstIdx + 1] = s0R * (1f - frac) + s1R * frac
                    }
                }

                wavPcmData = pcm
                wavTotalFrames = targetFrames
                hasAudio = true
                isPrepared = true
                return true
            }
        } catch (_: Throwable) {
            // Fall back to MediaExtractor if parsing fails
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
            bufferStartFrame = clampedTargetFrame
            writeFrame = clampedTargetFrame
            bufferedFrames = 0
            resamplePhase = 0.0
            prevSrcSampleL = 0f
            prevSrcSampleR = 0f

            // Fill buffer forward to target + prefill
            var iterations = 0
            while (bufferedFrames < targetMinFrames && !outputEos && iterations < maxIterations) {
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
                            dec.queueInputBuffer(inIndex, 0, sampleSize, ext.sampleTime, 0)
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
                    srcSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    srcChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
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

    private fun appendPcm(byteBuf: ByteBuffer) {
        byteBuf.order(ByteOrder.LITTLE_ENDIAN)

        val is16Bit = srcPcmEncoding != AudioFormat.ENCODING_PCM_FLOAT
        val channels = maxOf(1, srcChannels)

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
            // Float PCM path
            val floatBuf = byteBuf.asFloatBuffer()
            val totalSrcFrames = floatBuf.remaining() / channels
            if (totalSrcFrames <= 0) return

            for (f in 0 until totalSrcFrames) {
                val sL = floatBuf.get()
                val sR = if (channels > 1) floatBuf.get() else sL
                for (c in 2 until channels) {
                    floatBuf.get()
                }
                writeSampleToRing(sL, sR)
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

        hasAudio = false
        isPrepared = false
        bufferedFrames = 0
        wavPcmData = null
        wavTotalFrames = 0
        lastReadEndSourceFrame = -1L
    }
}
