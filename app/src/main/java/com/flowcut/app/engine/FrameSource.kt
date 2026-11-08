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

    fun prepare() {
        if (isPrepared) return
        val file = File(clip.path)
        if (!file.exists()) return

        try {
            val ext = MediaExtractor()
            ext.setDataSource(clip.path)
            var videoTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    format = f
                    break
                }
            }
            if (videoTrack == -1 || format == null) {
                ext.release()
                return
            }

            ext.selectTrack(videoTrack)
            extractor = ext

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            currentMime = mime

            // Low-latency API 30 flags (Section 19.6)
            try {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                format.setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
            } catch (_: Exception) {}

            surface = Surface(surfaceTexture)
            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(format, surface, null, 0)
            } catch (cfgEx: Exception) {
                // Fallback: configure without low-latency hints
                val fallbackFormat = ext.getTrackFormat(videoTrack)
                decoder.configure(fallbackFormat, surface, null, 0)
            }
            decoder.start()
            codec = decoder
            isPrepared = true
            inputEos = false
            outputEos = false
            currentPtsUs = -1L
            lastSeekTargetUs = -1L
            lastKeyframePtsUs = -1L
            lastRenderedLocalUs = -1L
            isAccuratePausedFrameLatched = false
        } catch (e: Exception) {
            isPrepared = false
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
            val maxAttempts = if (fastKeyframeOnly) 15 else 30

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
                    if (fastKeyframeOnly || pts >= localUs - 30_000L || outputEos) {
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastKeyframePtsUs = pts
                        rendered = true
                        lastRenderedLocalUs = localUs
                        if (!fastKeyframeOnly) {
                            isAccuratePausedFrameLatched = true
                        }
                    } else {
                        dec.releaseOutputBuffer(outIndex, false)
                        currentPtsUs = pts
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    break
                }
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

            val isInitial = currentPtsUs == -1L
            val isBackwardJump = currentPtsUs != -1L && currentPtsUs > localUs + 50_000L
            // Huge forward jump (> 2.5s) outside GOP range
            val isHugeForwardJump = currentPtsUs != -1L &&
                    localUs > currentPtsUs + 2_500_000L &&
                    (lastSeekTargetUs == -1L || localUs > lastSeekTargetUs + 500_000L)

            if (isInitial || isBackwardJump || isHugeForwardJump) {
                val didRender = seekTo(localUs, fastKeyframeOnly = false)
                if (didRender) {
                    lastRenderedLocalUs = localUs
                    isAccuratePausedFrameLatched = true
                }
                return didRender
            }

            // If current frame is already within half a frame (16ms) of target, latch it and keep it
            if (currentPtsUs in (localUs - 16_000L)..(localUs + 16_000L)) {
                lastRenderedLocalUs = localUs
                isAccuratePausedFrameLatched = true
                return false
            }

            // Catch-up / normal playback decode loop:
            // If localUs is ahead (e.g. after a keyframe scrub or normal playback tick),
            // decode forward. Drop intermediate frames (releaseOutputBuffer false) until pts >= localUs - 30_000L.
            val isCatchingUp = localUs > currentPtsUs + 100_000L
            val maxAttempts = if (isCatchingUp) 30 else 10
            val timeoutUs = if (isCatchingUp) 500L else 1_500L

            val bufferInfo = MediaCodec.BufferInfo()
            var attempts = 0

            while (attempts < maxAttempts && !outputEos) {
                attempts++
                if (!inputEos) {
                    val inIndex = dec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = dec.getInputBuffer(inIndex)
                        if (inBuf != null) {
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

                val outIndex = dec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val pts = bufferInfo.presentationTimeUs
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                    if (pts >= localUs - 30_000L || outputEos) {
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastRenderedLocalUs = localUs
                        isAccuratePausedFrameLatched = true
                        return true
                    } else {
                        // Drop intermediate frame to catch up rapidly without blocking GL
                        dec.releaseOutputBuffer(outIndex, false)
                        currentPtsUs = pts
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    return false
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
        isPrepared = false
        inputEos = false
        outputEos = false
        currentPtsUs = -1L
        lastSeekTargetUs = -1L
        lastKeyframePtsUs = -1L
        lastRenderedLocalUs = -1L
        isAccuratePausedFrameLatched = false

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
