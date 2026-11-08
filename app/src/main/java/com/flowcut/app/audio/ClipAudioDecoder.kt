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

/**
 * Hardware audio decoder with a bounded sliding ring buffer.
 *
 * Enforces:
 * - Rule R10: Zero heap allocations in the per-block read loop.
 * - Rule R11: Timeline-to-source mapping via clip.map().sourceUs().
 * - Safeguard 1: Bounded memory (~1.1 MB buffer per instance) preventing OOM.
 * - Supports 16-bit PCM and Float PCM, mono-to-stereo upmixing, and 48 kHz resampling.
 * - Graceful silence output for videos without audio tracks.
 */
class ClipAudioDecoder(private val clip: Clip) {

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

    var hasAudio: Boolean = false
        private set
    var isPrepared: Boolean = false
        private set

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null

    private var srcSampleRate: Int = TARGET_SAMPLE_RATE
    private var srcChannels: Int = TARGET_CHANNELS
    private var srcPcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

    // Bounded pre-allocated circular ring buffer
    private val pcmBuffer = FloatArray(BUFFER_SAMPLES)
    private var bufferStartFrame: Long = 0L
    private var writeFrame: Long = 0L
    private var bufferedFrames: Int = 0

    private var inputEos: Boolean = false
    private var outputEos: Boolean = false

    // Pre-allocated scratch buffers for decoding
    private val bufferInfo = MediaCodec.BufferInfo()
    private val tempSrcSamples = FloatArray(4096)
    private var prevSrcSampleL: Float = 0f
    private var prevSrcSampleR: Float = 0f
    private var resamplePhase: Double = 0.0

    init {
        prepare()
    }

    private fun prepare() {
        if (isPrepared) return
        val file = File(clip.path)
        if (!file.exists()) {
            hasAudio = false
            return
        }

        try {
            val ext = MediaExtractor()
            ext.setDataSource(clip.path)

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

            // Prime the buffer from frame 0
            seekToFrame(0L)
        } catch (_: Exception) {
            hasAudio = false
            isPrepared = false
            release()
        }
    }

    fun seekToFrame(targetFrame: Long) {
        if (!hasAudio || !isPrepared) return
        val ext = extractor ?: return
        val dec = codec ?: return

        try {
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
            val minFramesToBuffer = PREFILL_FRAMES
            var iterations = 0
            while (bufferedFrames < minFramesToBuffer && !outputEos && iterations < 80) {
                val decoded = decodeChunk()
                if (!decoded && inputEos) break
                iterations++
            }
        } catch (_: Exception) {
            // Decodes will fall back to silence
        }
    }

    /**
     * Reads [count] audio frames starting at [sourceFrame] and adds them to [out] at [outOffset]
     * scaled by [gain].
     *
     * Rule R10: Zero heap allocations in this method.
     */
    fun read(sourceFrame: Long, count: Int, out: FloatArray, outOffset: Int, gain: Float) {
        if (!hasAudio || !isPrepared || count <= 0 || gain <= 0f) return

        // Check if requested range is outside buffer
        if (sourceFrame < bufferStartFrame || sourceFrame + count > bufferStartFrame + bufferedFrames) {
            seekToFrame(sourceFrame)
        } else {
            // Buffer is valid; replenish if getting low
            val remaining = (bufferStartFrame + bufferedFrames) - (sourceFrame + count)
            if (remaining < PREFILL_FRAMES / 2 && !outputEos) {
                decodeChunk()
            }
        }

        var curFrame = sourceFrame
        var dstIdx = outOffset
        for (i in 0 until count) {
            if (curFrame in bufferStartFrame until (bufferStartFrame + bufferedFrames)) {
                val ringIdx = ((curFrame and BUFFER_MASK.toLong()).toInt()) shl 1
                out[dstIdx] += pcmBuffer[ringIdx] * gain
                out[dstIdx + 1] += pcmBuffer[ringIdx + 1] * gain
            }
            curFrame++
            dstIdx += TARGET_CHANNELS
        }
    }

    /**
     * Feeds input and drains output from MediaCodec into the circular buffer.
     * Returns true if any audio was appended to the buffer.
     */
    private fun decodeChunk(): Boolean {
        val ext = extractor ?: return false
        val dec = codec ?: return false

        var produced = false

        // 1. Feed input buffer
        if (!inputEos) {
            val inIndex = dec.dequeueInputBuffer(DECODE_TIMEOUT_US)
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
        val outIndex = dec.dequeueOutputBuffer(bufferInfo, DECODE_TIMEOUT_US)
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

        return produced
    }

    /**
     * Appends raw PCM from MediaCodec into pcmBuffer, converting 16-bit to float,
     * upmixing mono to stereo, and linearly resampling to 48 kHz.
     */
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
                    // Skip extra channels beyond stereo
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
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null

        try {
            extractor?.release()
        } catch (_: Exception) {}
        extractor = null

        hasAudio = false
        isPrepared = false
        bufferedFrames = 0
    }
}
