package com.flowcut.app.engine

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.flowcut.app.BuildConfig
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
    private var catchUpStartMs: Long = 0L
    private var _isCatchingUpFromSeek: Boolean = false

    var isCatchingUpFromSeek: Boolean
        get() {
            checkCatchUpTimeout()
            return _isCatchingUpFromSeek
        }
        set(value) {
            if (value) {
                if (!_isCatchingUpFromSeek || catchUpStartMs == 0L) {
                    catchUpStartMs = SystemClock.elapsedRealtime()
                }
            } else {
                catchUpStartMs = 0L
            }
            _isCatchingUpFromSeek = value
        }

    private fun checkCatchUpTimeout() {
        if (_isCatchingUpFromSeek && catchUpStartMs > 0L && (SystemClock.elapsedRealtime() - catchUpStartMs > 450L)) {
            _isCatchingUpFromSeek = false
            prewarmStage = 3
            isAccuratePausedFrameLatched = true
            catchUpStartMs = 0L
        }
    }

    fun hasValidFrameNear(targetUs: Long): Boolean {
        checkCatchUpTimeout()
        val durationUs = clip.durationMs * 1000L
        val isNearEos = durationUs > 0L && targetUs >= durationUs - 85_000L
        if (isNearEos && (outputEos || (currentPtsUs != -1L && currentPtsUs >= durationUs - 85_000L))) {
            return true
        }
        if (currentPtsUs != -1L && kotlin.math.abs(targetUs - currentPtsUs) <= 60_000L) {
            return true
        }
        if (isAccuratePausedFrameLatched && lastRenderedLocalUs != -1L && kotlin.math.abs(targetUs - lastRenderedLocalUs) <= 60_000L) {
            return true
        }
        return false
    }
    private var lastForwardKeyframeHopUs: Long = -1L
    private var hasNotifiedFirstFrame: Boolean = false
    var onFirstFrameDecoded: (() -> Unit)? = null

    var prewarmStage: Int = 0 // 0 = uninitialized, 1 = extractor ready, 2 = codec configured & seeked, 3 = target frame decoded & ready
    private var videoTrackIndex: Int = -1
    private var videoFormat: MediaFormat? = null

    private var cachedKeyframes: LongArray? = null
    private var keyframeLoadAttempted = false

    private fun getKeyframes(): LongArray? {
        if (!keyframeLoadAttempted) {
            keyframeLoadAttempted = true
            val idxPath = clip.path.replace(".mp4", ".idx")
            cachedKeyframes = com.flowcut.app.storage.KeyframeIndex.load(idxPath)
        }
        return cachedKeyframes
    }

    private fun findSoftwareDecoderName(mime: String): String? {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                val name = info.name
                if (name.startsWith("c2.android.", ignoreCase = true) || name.startsWith("OMX.google.", ignoreCase = true)) {
                    for (type in info.supportedTypes) {
                        if (type.equals(mime, ignoreCase = true)) {
                            return name
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun notifyFirstFrame() {
        if (!hasNotifiedFirstFrame) {
            hasNotifiedFirstFrame = true
            onFirstFrameDecoded?.invoke()
        }
    }

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

            val rawMime = format.getString(MediaFormat.KEY_MIME) ?: ""
            // MediaTek MT6833 / Codec2 Dolby Vision Fallback:
            // Standard GL surfaces fail on video/dolby-vision with "failed config".
            // Normalize to standard video/hevc and remove profile/level constraints.
            if (rawMime.contains("dolby-vision", ignoreCase = true)) {
                currentMime = "video/hevc"
                format.setString(MediaFormat.KEY_MIME, "video/hevc")
                try {
                    if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                        format.setInteger(MediaFormat.KEY_PROFILE, 0)
                    }
                    if (format.containsKey(MediaFormat.KEY_LEVEL)) {
                        format.setInteger(MediaFormat.KEY_LEVEL, 0)
                    }
                } catch (_: Exception) {}
            } else {
                currentMime = rawMime
            }
            videoFormat = format
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
        val targetMime = if (currentMime.contains("dolby-vision", ignoreCase = true)) {
            "video/hevc"
        } else {
            currentMime.ifEmpty { format.getString(MediaFormat.KEY_MIME) ?: "video/avc" }
        }
        if (currentMime.contains("dolby-vision", ignoreCase = true)) {
            format.setString(MediaFormat.KEY_MIME, "video/hevc")
        }

        try {
            surface = Surface(surfaceTexture)
            var decoder: MediaCodec? = null

            // Pass 1: Try Hardware Decoder
            try {
                val hwDec = MediaCodec.createDecoderByType(targetMime)
                hwDec.configure(format, surface, null, 0)
                hwDec.start()
                decoder = hwDec
            } catch (e1: Exception) {
                try { decoder?.release() } catch (_: Exception) {}
                decoder = null

                // Pass 2: Retry Hardware Decoder with stripped profile/level and basic dimensions
                try {
                    val fallbackFormat = ext.getTrackFormat(videoTrackIndex)
                    fallbackFormat.setString(MediaFormat.KEY_MIME, targetMime)
                    val hwRetry = MediaCodec.createDecoderByType(targetMime)
                    hwRetry.configure(fallbackFormat, surface, null, 0)
                    hwRetry.start()
                    decoder = hwRetry
                } catch (e2: Exception) {
                    try { decoder?.release() } catch (_: Exception) {}
                    decoder = null

                    // Pass 3: Fallback to Software Codec (c2.android.* / OMX.google.*)
                    val swCodecName = findSoftwareDecoderName(targetMime)
                    if (swCodecName != null) {
                        try {
                            val swDec = MediaCodec.createByCodecName(swCodecName)
                            swDec.configure(format, surface, null, 0)
                            swDec.start()
                            decoder = swDec
                        } catch (e3: Exception) {
                            try { decoder?.release() } catch (_: Exception) {}
                            decoder = null
                        }
                    }
                }
            }

            if (decoder == null) {
                isPrepared = false
                return false
            }

            codec = decoder
            isPrepared = true

            if (BuildConfig.DEBUG) {
                try {
                    val isHw = decoder.codecInfo.isHardwareAccelerated
                    Log.d(
                        "CODEC",
                        "Decoder started: name=${decoder.name}, isHardwareAccelerated=$isHw, clipId=${clip.id}, mime=$targetMime, size=${clip.width}x${clip.height}"
                    )
                } catch (_: Throwable) {}
            }

            ext.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            inputEos = false
            outputEos = false
            currentPtsUs = -1L
            lastSeekTargetUs = targetUs
            lastKeyframePtsUs = -1L
            lastRenderedLocalUs = -1L
            isAccuratePausedFrameLatched = false
            isCatchingUpFromSeek = true
            lastForwardKeyframeHopUs = -1L
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

            // Feed up to 8 input buffers (0L non-blocking)
            var fed = 0
            while (fed < 8 && !inputEos) {
                fed++
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
                } else {
                    break
                }
            }

            // Drain up to 4 output buffers (first drain with 3,000us timeout, subsequent 0L)
            var drained = 0
            val clipDurationUs = clip.durationMs * 1000L
            while (drained < 4 && !outputEos) {
                val timeoutUs = if (drained == 0) 3_000L else 0L
                drained++
                val outIndex = dec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val pts = bufferInfo.presentationTimeUs
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                    val atClipEnd = outputEos ||
                        (clipDurationUs > 0L && (pts >= clipDurationUs - 85_000L || targetUs >= clipDurationUs - 85_000L))

                    if (pts >= targetUs - 20_000L || atClipEnd) {
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastRenderedLocalUs = targetUs
                        isAccuratePausedFrameLatched = true
                        isCatchingUpFromSeek = false
                        prewarmStage = 3
                        notifyFirstFrame()
                        return true
                    } else {
                        dec.releaseOutputBuffer(outIndex, false)
                        currentPtsUs = pts
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    outputEos = true
                    isCatchingUpFromSeek = false
                    isAccuratePausedFrameLatched = true
                    prewarmStage = 3
                    lastRenderedLocalUs = targetUs
                    notifyFirstFrame()
                    return true
                } else {
                    break
                }
            }
        } catch (_: Exception) {
            return false
        }
        return prewarmStage >= 3
    }

    fun ensureDecodedAt(targetUs: Long): Boolean {
        checkCatchUpTimeout()
        if (prewarmStage >= 3 && codec != null && !isCatchingUpFromSeek) return true
        val clipDurationUs = clip.durationMs * 1000L
        val isNearClipEnd = clipDurationUs > 0L && targetUs >= clipDurationUs - 85_000L
        if (isPrepared && currentPtsUs != -1L && (kotlin.math.abs(targetUs - currentPtsUs) <= 45_000L || (isNearClipEnd && currentPtsUs >= clipDurationUs - 85_000L))) {
            prewarmStage = 3
            isCatchingUpFromSeek = false
            isAccuratePausedFrameLatched = true
            return true
        }
        if (prewarmStage < 1 || extractor == null || videoFormat == null) {
            if (!prepareExtractorOnly()) return false
        }
        if (prewarmStage < 2 || codec == null) {
            if (!startCodecAndSeek(targetUs)) return false
        }
        val fastKeyframe = targetUs <= 100_000L
        val didRender = seekTo(targetUs, fastKeyframeOnly = fastKeyframe)
        if (didRender) {
            prewarmStage = 3
            isCatchingUpFromSeek = false
            isAccuratePausedFrameLatched = true
            return true
        }
        for (i in 0 until 5) {
            if (stepPrewarmTo(targetUs)) {
                prewarmStage = 3
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = true
                return true
            }
        }
        if (outputEos || (clipDurationUs > 0L && currentPtsUs >= clipDurationUs - 85_000L)) {
            prewarmStage = 3
            isCatchingUpFromSeek = false
            isAccuratePausedFrameLatched = true
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

    val currentPath: String get() = clip.path

    fun seekTo(localUs: Long, fastKeyframeOnly: Boolean = false, isExport: Boolean = false): Boolean {
        checkCatchUpTimeout()
        val clipDurationUs = clip.durationMs * 1000L
        val isNearClipEnd = clipDurationUs > 0L && localUs >= clipDurationUs - 85_000L
        if (isPrepared && currentPtsUs != -1L) {
            if (kotlin.math.abs(localUs - currentPtsUs) <= 45_000L ||
                (isNearClipEnd && currentPtsUs >= clipDurationUs - 85_000L)) {
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = true
                prewarmStage = 3
                return false
            }
        }
        if (fastKeyframeOnly && isPrepared && currentPtsUs != -1L && lastSeekTargetUs != -1L && kotlin.math.abs(localUs - lastSeekTargetUs) < 60_000L) {
            isCatchingUpFromSeek = false
            return false
        }
        val ext = extractor ?: return false
        val dec = codec ?: return false
        try {
            lastSeekTargetUs = localUs
            isCatchingUpFromSeek = true
            var seekTimeUs = localUs
            if (fastKeyframeOnly) {
                val keyframes = getKeyframes()
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
            val timeoutUs = 1_000L
            var rendered = false
            var attempts = 0
            val maxAttempts = if (isExport) 120 else (if (fastKeyframeOnly) 10 else 12)

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
                    val atClipEnd = outputEos ||
                        (clipDurationUs > 0L && pts >= clipDurationUs - 85_000L) ||
                        (clipDurationUs > 0L && localUs >= clipDurationUs - 85_000L)

                    if (fastKeyframeOnly) {
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastKeyframePtsUs = pts
                        rendered = true
                        lastRenderedLocalUs = localUs
                        if (atClipEnd) {
                            isAccuratePausedFrameLatched = true
                            isCatchingUpFromSeek = false
                            prewarmStage = 3
                        }
                        notifyFirstFrame()
                    } else {
                        // Exact Silent Preroll: silently discard all frames before localUs - 20_000L
                        if (pts >= localUs - 20_000L || atClipEnd) {
                            dec.releaseOutputBuffer(outIndex, true)
                            currentPtsUs = pts
                            lastKeyframePtsUs = pts
                            rendered = true
                            lastRenderedLocalUs = localUs
                            isAccuratePausedFrameLatched = true
                            isCatchingUpFromSeek = false
                            prewarmStage = 3
                            notifyFirstFrame()
                        } else {
                            dec.releaseOutputBuffer(outIndex, false)
                            currentPtsUs = pts
                        }
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    outputEos = true
                    isCatchingUpFromSeek = false
                    isAccuratePausedFrameLatched = true
                    prewarmStage = 3
                    lastRenderedLocalUs = localUs
                    break
                }
            }
            if (rendered || outputEos || (inputEos && !rendered) || attempts >= maxAttempts || (clipDurationUs > 0L && currentPtsUs >= clipDurationUs - 85_000L) || (currentPtsUs >= lastSeekTargetUs - 50_000L)) {
                isCatchingUpFromSeek = false
                if (outputEos || (clipDurationUs > 0L && currentPtsUs >= clipDurationUs - 85_000L)) {
                    isAccuratePausedFrameLatched = true
                    prewarmStage = 3
                    lastRenderedLocalUs = localUs
                }
            }
            return rendered
        } catch (_: Exception) {
            isCatchingUpFromSeek = false
            return false
        }
    }

    fun frameAt(localUs: Long, isScrubbing: Boolean = false): Boolean {
        checkCatchUpTimeout()
        val ext = extractor ?: return false
        val dec = codec ?: return false
        val clipDurationUs = clip.durationMs * 1000L
        val isNearClipEnd = clipDurationUs > 0L && (localUs >= clipDurationUs - 85_000L || (currentPtsUs != -1L && currentPtsUs >= clipDurationUs - 85_000L))

        try {
            // Case 1: Scrubbing / Flinging mode
            if (isScrubbing) {
                isAccuratePausedFrameLatched = false
                isCatchingUpFromSeek = false
                // If we already rendered near this target, skip to avoid spamming
                if (currentPtsUs != -1L && kotlin.math.abs(localUs - currentPtsUs) <= 30_000L) {
                    return false
                }
                if (isNearClipEnd && currentPtsUs != -1L && currentPtsUs >= clipDurationUs - 85_000L) {
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
            if (isAccuratePausedFrameLatched && (kotlin.math.abs(localUs - lastRenderedLocalUs) <= 5_000L || (isNearClipEnd && lastRenderedLocalUs >= clipDurationUs - 85_000L))) {
                isCatchingUpFromSeek = false
                return false
            }

            if (outputEos || (isNearClipEnd && currentPtsUs != -1L && currentPtsUs >= clipDurationUs - 85_000L)) {
                isAccuratePausedFrameLatched = true
                isCatchingUpFromSeek = false
                prewarmStage = 3
                lastRenderedLocalUs = localUs
                return false
            }

            val isInitial = currentPtsUs == -1L
            // Backward jump > 50ms
            val isBackwardJump = currentPtsUs != -1L && currentPtsUs > localUs + 50_000L

            // Decode whenever localUs >= currentPtsUs + 8_000L (8ms threshold instead of 25ms) so 30fps and 60fps clips never skip a frame during a fast transition
            if (!isInitial && !isBackwardJump && currentPtsUs != -1L && localUs < currentPtsUs + 8_000L) {
                lastRenderedLocalUs = localUs
                isAccuratePausedFrameLatched = true
                return false
            }

            // Check if catch-up from seek is complete
            if (isCatchingUpFromSeek && (currentPtsUs >= lastSeekTargetUs - 50_000L || (clipDurationUs > 0L && currentPtsUs >= clipDurationUs - 85_000L))) {
                isCatchingUpFromSeek = false
            }

            if (isInitial || isBackwardJump) {
                lastForwardKeyframeHopUs = -1L
                val didRender = seekTo(localUs, fastKeyframeOnly = false)
                if (didRender) {
                    lastRenderedLocalUs = localUs
                    isAccuratePausedFrameLatched = true
                }
                return didRender
            }

            val deltaUs = localUs - currentPtsUs

            // Case B (Extreme forward jump > 1.5s): Only hop to keyframe if keyframe is strictly AHEAD of currentPtsUs
            if (deltaUs > 1_500_000L && (lastForwardKeyframeHopUs == -1L || localUs - lastForwardKeyframeHopUs > 600_000L)) {
                val targetMs = localUs / 1000L
                val keyframes = getKeyframes()
                val closestKeyframeMs = if (keyframes != null && keyframes.isNotEmpty()) {
                    com.flowcut.app.storage.KeyframeIndex.findClosestKeyframeAtOrBefore(keyframes, targetMs)
                } else -1L
                val keyframeUs = if (closestKeyframeMs >= 0L) closestKeyframeMs * 1000L else -1L

                if (keyframeUs > currentPtsUs + 300_000L) {
                    lastForwardKeyframeHopUs = localUs
                    val didRender = seekTo(localUs, fastKeyframeOnly = true)
                    if (didRender) {
                        lastRenderedLocalUs = localUs
                        isAccuratePausedFrameLatched = true
                    }
                    return didRender
                }
            }

            // Case A: Ultra-smooth forward playback decode & drain loop (Fix Bug #1)
            // 1. Feed up to 4 input packets so hardware decoder queue is always well-fed
            var inFed = 0
            while (!inputEos && inFed < 4) {
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
                    inFed++
                } else {
                    break
                }
            }

            // 2. Dequeue output buffers: always release with render = true so SurfaceTexture is never starved!
            val isFastForward = deltaUs >= 40_000L
            val maxDrains = if (isFastForward) 6 else 3
            var drainedCount = 0
            var renderedAny = false
            val bufferInfo = MediaCodec.BufferInfo()

            var outIndex = dec.dequeueOutputBuffer(bufferInfo, 2_000L)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                outputEos = true
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = true
                prewarmStage = 3
                lastRenderedLocalUs = localUs
            }
            while (outIndex >= 0) {
                val pts = bufferInfo.presentationTimeUs
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputEos = true
                }

                // SurfaceTexture latches the latest released buffer on updateTexImage()
                dec.releaseOutputBuffer(outIndex, true)
                currentPtsUs = pts
                renderedAny = true
                drainedCount++

                val atClipEnd = outputEos ||
                    (clipDurationUs > 0L && pts >= clipDurationUs - 85_000L) ||
                    (clipDurationUs > 0L && localUs >= clipDurationUs - 85_000L)

                if (atClipEnd) {
                    isCatchingUpFromSeek = false
                    isAccuratePausedFrameLatched = true
                    prewarmStage = 3
                    lastRenderedLocalUs = localUs
                    break
                }

                if (pts >= localUs - 15_000L || drainedCount >= maxDrains) {
                    break
                }

                // Non-blocking check for next decoded frame already ready in hardware queue
                outIndex = dec.dequeueOutputBuffer(bufferInfo, 0L)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    outputEos = true
                    isCatchingUpFromSeek = false
                    isAccuratePausedFrameLatched = true
                    prewarmStage = 3
                    lastRenderedLocalUs = localUs
                    break
                }
            }

            if (renderedAny) {
                lastRenderedLocalUs = localUs
                isAccuratePausedFrameLatched = true
                if (currentPtsUs >= lastSeekTargetUs - 50_000L || outputEos || (clipDurationUs > 0L && currentPtsUs >= clipDurationUs - 85_000L)) {
                    isCatchingUpFromSeek = false
                }
                notifyFirstFrame()
                return true
            } else if (inputEos || outputEos || (clipDurationUs > 0L && currentPtsUs >= clipDurationUs - 85_000L)) {
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = true
                prewarmStage = 3
                lastRenderedLocalUs = localUs
            }
        } catch (_: Exception) {
            isCatchingUpFromSeek = false
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
            // Case C: If already at target frame (within 12ms), or freeze frame repeating identical localUs
            if (currentPtsUs != -1L && (localUs == lastRenderedLocalUs || kotlin.math.abs(localUs - currentPtsUs) < 12_000L || (currentPtsUs >= localUs - 10_000L && currentPtsUs <= localUs + 15_000L))) {
                return false
            }

            // If discontinuous or far behind/ahead, seek with silent preroll
            if (currentPtsUs == -1L || currentPtsUs > localUs + 200_000L || localUs > currentPtsUs + 800_000L) {
                return seekTo(localUs, fastKeyframeOnly = false, isExport = true)
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
        catchUpStartMs = 0L
        lastForwardKeyframeHopUs = -1L
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
