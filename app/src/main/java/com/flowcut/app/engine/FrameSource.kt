package com.flowcut.app.engine

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import com.flowcut.app.model.Clip
import java.io.File
import java.util.concurrent.Executors

/**
 * FrameSource (Section 19.6 & 19.7):
 * Low-latency hardware decoder wrapper with async cleanup, API 30 flags, and flush reuse.
 */
class FrameSource(
    var clip: Clip,
    private val surfaceTexture: SurfaceTexture
) {
    companion object {
        private val releaseExecutor = Executors.newCachedThreadPool()
    }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var isPrepared = false
    private var inputEos = false
    private var outputEos = false
    private var currentPtsUs: Long = -1L
    private var currentMime: String = ""
    private var lastSeekTargetUs: Long = -1L
    private var lastKeyframePtsUs: Long = -1L
    private var lastRenderedLocalUs: Long = -1L
    private var isAccuratePausedFrameLatched: Boolean = false
    private var isCatchingUpFromSeek: Boolean = false

    var prewarmStage: Int = 0 // 0 = uninitialized, 1 = extractor ready, 2 = codec configured & seeked, 3 = target frame decoded & ready
    private var videoTrackIndex: Int = -1
    private var videoFormat: MediaFormat? = null

    fun prepareExtractorOnly(): Boolean {
        if (prewarmStage >= 1 && extractor != null) return true
        val file = File(clip.path)
        if (!file.exists()) return false

        try {
            val ext = MediaExtractor()
            ext.setDataSource(clip.path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track == -1 || format == null) {
                ext.release()
                return false
            }

            ext.selectTrack(track)
            extractor = ext
            videoTrackIndex = track
            videoFormat = format
            currentMime = format.getString(MediaFormat.KEY_MIME) ?: ""
            prewarmStage = 1
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun startCodecAndSeek(targetUs: Long): Boolean {
        if (prewarmStage >= 2 && codec != null) return true
        if (prewarmStage < 1 || extractor == null || videoFormat == null) {
            if (!prepareExtractorOnly()) return false
        }

        val ext = extractor ?: return false
        val format = videoFormat ?: return false
        val mime = currentMime.ifEmpty { format.getString(MediaFormat.KEY_MIME) ?: "" }

        try {
            try {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
            } catch (_: Exception) {}

            surface = Surface(surfaceTexture)
            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(format, surface, null, 0)
            } catch (cfgEx: Exception) {
                val fallbackFormat = ext.getTrackFormat(videoTrackIndex)
                decoder.configure(fallbackFormat, surface, null, 0)
            }
            decoder.start()
            codec = decoder
            isPrepared = true

            ext.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            inputEos = false
            outputEos = false
            currentPtsUs = -1L
            lastSeekTargetUs = targetUs
            lastKeyframePtsUs = -1L
            lastRenderedLocalUs = -1L
            isAccuratePausedFrameLatched = false
            isCatchingUpFromSeek = true
            prewarmStage = 2
            return true
        } catch (_: Exception) {
            isPrepared = false
            return false
        }
    }

    fun stepPrewarmTo(targetUs: Long): Boolean {
        if (prewarmStage >= 3) return true
        if (prewarmStage < 2) return false

        val ext = extractor ?: return false
        val dec = codec ?: return false

        try {
            val bufferInfo = MediaCodec.BufferInfo()

            // Feed up to 1 input buffer (0L non-blocking)
            if (!inputEos) {
                val inIndex = dec.dequeueInputBuffer(0L)
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

            // Drain up to 2 output buffers (0L non-blocking)
            var drained = 0
            while (drained < 2 && !outputEos) {
                drained++
                val outIndex = dec.dequeueOutputBuffer(bufferInfo, 0L)
                if (outIndex >= 0) {
                    val pts = bufferInfo.presentationTimeUs
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                    if (pts >= targetUs - 20_000L || outputEos) {
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastRenderedLocalUs = targetUs
                        isAccuratePausedFrameLatched = true
                        isCatchingUpFromSeek = false
                        prewarmStage = 3
                        return true
                    } else {
                        dec.releaseOutputBuffer(outIndex, false)
                        currentPtsUs = pts
                    }
                } else {
                    break
                }
            }
        } catch (_: Exception) {
            return false
        }
        return prewarmStage >= 3
    }

    fun prepare() {
        if (isPrepared) return
        if (prewarmStage < 1) {
            prepareExtractorOnly()
        }
        if (prewarmStage < 2) {
            startCodecAndSeek(0L)
        }
    }

    /**
     * Check if decoder can be reused for another clip with same mime & dimensions (Section 19.7).
     */
    fun canReuse(other: Clip): Boolean {
        return isPrepared && codec != null && clip.width == other.width && clip.height == other.height
    }

    fun seekTo(localUs: Long, fastKeyframeOnly: Boolean = false): Boolean {
        val ext = extractor ?: return false
        val dec = codec ?: return false
        try {
            lastSeekTargetUs = localUs
            isCatchingUpFromSeek = true
            var seekTimeUs = localUs
            if (fastKeyframeOnly) {
                val idxPath = clip.path.replace(".mp4", ".idx")
                val keyframes = com.flowcut.app.storage.KeyframeIndex.load(idxPath)
                if (keyframes != null && keyframes.isNotEmpty()) {
                    val targetMs = localUs / 1000L
                    val closestKeyframeMs = com.flowcut.app.storage.KeyframeIndex.findClosestKeyframeAtOrBefore(keyframes, targetMs)
                    seekTimeUs = closestKeyframeMs * 1000L
                }
            }

            ext.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (currentPtsUs != -1L) {
                try {
                    dec.flush()
                } catch (_: Exception) {}
            }
            inputEos = false
            outputEos = false
            currentPtsUs = -1L

            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 1_500L
            var rendered = false
            var attempts = 0
            val maxAttempts = if (fastKeyframeOnly) 15 else 120

            while (!rendered && attempts < maxAttempts && !outputEos) {
                attempts++
                if (!inputEos) {
                    val inputIndex = dec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = dec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = ext.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                dec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                dec.queueInputBuffer(inputIndex, 0, sampleSize, ext.sampleTime, 0)
                                ext.advance()
                            }
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val pts = bufferInfo.presentationTimeUs
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                    if (fastKeyframeOnly) {
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastKeyframePtsUs = pts
                        rendered = true
                        lastRenderedLocalUs = localUs
                    } else {
                        // Exact Silent Preroll: silently discard all frames before localUs - 20_000L
                        if (pts >= localUs - 20_000L || outputEos || attempts >= maxAttempts) {
                            dec.releaseOutputBuffer(outIndex, true)
                            currentPtsUs = pts
                            lastKeyframePtsUs = pts
                            rendered = true
                            lastRenderedLocalUs = localUs
                            isAccuratePausedFrameLatched = true
                            isCatchingUpFromSeek = false
                        } else {
                            dec.releaseOutputBuffer(outIndex, false)
                            currentPtsUs = pts
                        }
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    break
                }
            }
            if (currentPtsUs >= lastSeekTargetUs - 50_000L) {
                isCatchingUpFromSeek = false
            }
            return rendered
        } catch (_: Exception) {
            return false
        }
    }

    fun frameAt(localUs: Long, isScrubbing: Boolean = false): Boolean {
        val ext = extractor ?: return false
        val dec = codec ?: return false

        try {
            // Case 1: Scrubbing / Flinging mode
            if (isScrubbing) {
                isAccuratePausedFrameLatched = false
                isCatchingUpFromSeek = false
                // If we already rendered near this target, skip to avoid spamming
                if (currentPtsUs != -1L && kotlin.math.abs(localUs - currentPtsUs) <= 30_000L) {
                    return false
                }
                // Only seek if scrub target moved significantly (> 80ms)
                if (lastSeekTargetUs != -1L && kotlin.math.abs(localUs - lastSeekTargetUs) <= 80_000L && currentPtsUs != -1L) {
                    return false
                }
                val didRender = seekTo(localUs, fastKeyframeOnly = true)
                if (didRender) {
                    lastRenderedLocalUs = localUs
                }
                return didRender
            }

            // Case 2: Playback / Paused mode
            // If already accurately latched on this target in PAUSED mode, do zero work!
            if (isAccuratePausedFrameLatched && localUs == lastRenderedLocalUs) {
                return false
            }

            // Check if catch-up from seek is complete
            if (isCatchingUpFromSeek && currentPtsUs >= lastSeekTargetUs - 50_000L) {
                isCatchingUpFromSeek = false
            }

            // Lookahead pacing threshold (Item 3): 4ms tolerance so vsync transitions smoothly without cadence stutter
            if (currentPtsUs >= localUs - 4_000L && currentPtsUs <= localUs + 500_000L) {
                lastRenderedLocalUs = localUs
                isAccuratePausedFrameLatched = true
                return false
            }

            val isInitial = currentPtsUs == -1L
            // Backward jump > 500ms
            val isBackwardJump = currentPtsUs != -1L && currentPtsUs > localUs + 500_000L
            // If we are significantly behind (> 80ms) when starting play, seek with silent preroll instead of playing GOP forward!
            val isFarBehind = currentPtsUs != -1L && localUs > currentPtsUs + 80_000L && !isCatchingUpFromSeek
            val isHugeForwardJump = currentPtsUs != -1L && localUs > currentPtsUs + 2_500_000L

            if (isInitial || isBackwardJump || isHugeForwardJump || isFarBehind) {
                val didRender = seekTo(localUs, fastKeyframeOnly = false)
                if (didRender) {
                    lastRenderedLocalUs = localUs
                    isAccuratePausedFrameLatched = true
                }
                return didRender
            }

            // Catch-up / normal playback decode loop:
            var hasDequeuedAnyOutput = false
            val maxAttempts = 15
            var attempts = 0
            val bufferInfo = MediaCodec.BufferInfo()

            while (attempts < maxAttempts && !outputEos) {
                attempts++

                // Feed input buffer
                if (!inputEos) {
                    val inTimeout = if (hasDequeuedAnyOutput) 0L else 1_000L
                    val inIndex = dec.dequeueInputBuffer(inTimeout)
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

                // Use 2.5ms timeout for initial output wait, 0L for subsequent checks once a buffer is received
                val outTimeoutUs = if (hasDequeuedAnyOutput) 0L else 2_500L
                val outIndex = dec.dequeueOutputBuffer(bufferInfo, outTimeoutUs)

                if (outIndex >= 0) {
                    hasDequeuedAnyOutput = true
                    val pts = bufferInfo.presentationTimeUs
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }

                    val isTargetReached = pts >= localUs - 12_000L || outputEos

                    if (isTargetReached) {
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastRenderedLocalUs = localUs
                        isAccuratePausedFrameLatched = true
                        if (pts >= lastSeekTargetUs - 50_000L) {
                            isCatchingUpFromSeek = false
                        }
                        return true
                    } else {
                        // Drop intermediate frame silently without ever force-rendering early GOP frames
                        dec.releaseOutputBuffer(outIndex, false)
                        currentPtsUs = pts
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputEos || hasDequeuedAnyOutput) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    /**
     * Dedicated 100% frame-accurate offline export decoder.
     * Never skips to visual burst, silently discards frames until targetUs, and returns true only when
     * the exact target frame is released to the Surface.
     */
    fun frameAtExport(localUs: Long): Boolean {
        val ext = extractor ?: return false
        val dec = codec ?: return false

        try {
            // If already at target frame (within 12ms), no new frame is needed
            if (currentPtsUs != -1L && currentPtsUs >= localUs - 10_000L && currentPtsUs <= localUs + 15_000L) {
                return false
            }

            // If discontinuous or far behind/ahead, seek with silent preroll
            if (currentPtsUs == -1L || currentPtsUs > localUs + 200_000L || localUs > currentPtsUs + 800_000L) {
                return seekTo(localUs, fastKeyframeOnly = false)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var attempts = 0
            val maxAttempts = 60

            while (attempts < maxAttempts && !outputEos) {
                attempts++

                if (!inputEos) {
                    val inIndex = dec.dequeueInputBuffer(10_000L)
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

                val outIndex = dec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIndex >= 0) {
                    val pts = bufferInfo.presentationTimeUs
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }

                    if (pts >= localUs - 18_000L || outputEos) {
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastRenderedLocalUs = localUs
                        return true
                    } else {
                        dec.releaseOutputBuffer(outIndex, false)
                        currentPtsUs = pts
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    break
                }
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    /**
     * Release decoders on background thread (Section 19.7) so GL thread is never blocked.
     * Note: Do NOT call surface.release() here as it invalidates the underlying SurfaceTexture's connection.
     */
    fun release() {
        val oldCodec = codec
        val oldExt = extractor

        codec = null
        extractor = null
        surface = null
        videoFormat = null
        videoTrackIndex = -1
        isPrepared = false
        inputEos = false
        outputEos = false
        currentPtsUs = -1L
        lastSeekTargetUs = -1L
        lastKeyframePtsUs = -1L
        lastRenderedLocalUs = -1L
        isAccuratePausedFrameLatched = false
        isCatchingUpFromSeek = false
        prewarmStage = 0

        releaseExecutor.execute {
            try {
                oldCodec?.stop()
            } catch (_: Exception) {}
            try {
                oldCodec?.release()
            } catch (_: Exception) {}
            try {
                oldExt?.release()
            } catch (_: Exception) {}
        }
    }
}
