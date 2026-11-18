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
    clip: Clip,
    private val surfaceTexture: SurfaceTexture
) {
    companion object {
        private val releaseExecutor = Executors.newCachedThreadPool()
    }

    var clip: Clip = clip
        set(value) {
            val oldPath = field.path
            val oldDurationMs = field.durationMs   // save BEFORE field = value
            field = value
            val clipDurUs = value.durationMs * 1000L
            if (oldPath != value.path) {
                // New clip file: reset all stale state so the replaced clip starts fresh
                mediaDurationUs = -1L
                cachedKeyframes = null
                lastRenderedPtsUs = -1L
                lastRenderedLocalUs = -1L
                lastKeyframePtsUs = -1L
                lastSeekTargetUs = -1L
                isAccuratePausedFrameLatched = false
                outputEos = false
                inputEos = false
            } else {
                // Same file — duration may have changed (handle drag / trim extend)
                if (clipDurUs > mediaDurationUs) {
                    mediaDurationUs = clipDurUs
                }
                if (value.durationMs > oldDurationMs) {
                    // Duration increased (handle drag / trim extend).
                    // Clear EOS flags so the decoder is not stuck at the old clip boundary.
                    // Do NOT reset lastRenderedPtsUs here — that would cause isInitial=true on
                    // every GL frame tick and trigger an infinite seekTo/dec.flush() loop.
                    // Instead, eosDrained in frameAt() uses a forward-past-EOS check to
                    // issue exactly one re-seek when the user scrubs into the new region.
                    if (outputEos || inputEos) {
                        outputEos = false
                        inputEos = false
                    }
                    isAccuratePausedFrameLatched = false
                    lastRenderedLocalUs = -1L
                }
            }
        }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var isPrepared = false
    private var inputEos = false
    private var outputEos = false
    @Volatile var currentPtsUs: Long = -1L
        private set
    @Volatile var lastRenderedPtsUs: Long = -1L
        private set
    @Volatile private var fastTargetUs: Long = -1L
    @Volatile private var fastRun = false
    private var fastThread: Thread? = null
    @Volatile private var fastStampNs: Long = 0L
    private var lastHopKfUs: Long = -1L
    val lastDecodedPtsUs: Long get() = if (lastRenderedPtsUs != -1L) lastRenderedPtsUs else currentPtsUs
    val currentDecoderPtsUs: Long get() = currentPtsUs
    @Volatile var playbackSpeed: Float = 1.0f
    private val effSpeed: Float get() = playbackSpeed.coerceAtLeast(1.0f)
    private var currentMime: String = ""
    private var lastSeekTargetUs: Long = -1L
    private var lastKeyframePtsUs: Long = -1L
    private var lastRenderedLocalUs: Long = -1L
    var isAccuratePausedFrameLatched: Boolean = false
        internal set
    private var catchUpStartMs: Long = 0L
    private var _isCatchingUpFromSeek: Boolean = false
    private var mediaDurationUs: Long = -1L

    private val effectiveMediaDurationUs: Long
        get() {
            var dur = mediaDurationUs
            if (dur <= 0L && videoFormat?.containsKey(MediaFormat.KEY_DURATION) == true) {
                try {
                    val d = videoFormat!!.getLong(MediaFormat.KEY_DURATION)
                    if (d > 0L) {
                        mediaDurationUs = d
                        dur = d
                    }
                } catch (_: Exception) {}
            }
            if (dur <= 0L) {
                dur = clip.durationMs * 1000L
            }
            return maxOf(dur, clip.durationMs * 1000L)
        }

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
        if (_isCatchingUpFromSeek && catchUpStartMs > 0L && (SystemClock.elapsedRealtime() - catchUpStartMs > 300L)) {
            _isCatchingUpFromSeek = false
            prewarmStage = 3
            isAccuratePausedFrameLatched = true
            catchUpStartMs = 0L
        }
    }

    fun hasValidFrameNear(targetUs: Long): Boolean {
        checkCatchUpTimeout()
        if (effSpeed > 1.5f && lastRenderedPtsUs != -1L &&
            kotlin.math.abs(targetUs - lastRenderedPtsUs) <= (60_000L * effSpeed).toLong().coerceAtLeast(400_000L)) {
            return true
        }
        val durationUs = effectiveMediaDurationUs
        // Wider EOS proximity: last 500ms of clip counts as "near EOS" to avoid
        // spurious catch-up states when user scrubs in the last half-second of a clip.
        val isNearEos = durationUs > 0L && targetUs >= durationUs - 500_000L
        if (isNearEos && lastRenderedPtsUs != -1L && (outputEos || lastRenderedPtsUs >= durationUs - 85_000L)) {
            return true
        }
        if (lastRenderedPtsUs != -1L && lastRenderedPtsUs >= targetUs - 25_000L && lastRenderedPtsUs <= targetUs + 45_000L) {
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

    private fun getKeyframes(): LongArray? {
        if (cachedKeyframes != null) return cachedKeyframes
        val idxPath = clip.path.replace(".mp4", ".idx")
        cachedKeyframes = com.flowcut.app.storage.KeyframeIndex.load(idxPath)
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
            mediaDurationUs = -1L
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                try {
                    val d = format.getLong(MediaFormat.KEY_DURATION)
                    if (d > 0L) mediaDurationUs = d
                } catch (_: Exception) {}
            }
            if (mediaDurationUs <= 0L) {
                for (i in 0 until ext.trackCount) {
                    try {
                        val f = ext.getTrackFormat(i)
                        if (f.containsKey(MediaFormat.KEY_DURATION)) {
                            val d = f.getLong(MediaFormat.KEY_DURATION)
                            if (d > 0L) {
                                mediaDurationUs = d
                                break
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            if (mediaDurationUs <= 0L) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(clip.path)
                    val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    retriever.release()
                    val durMs = durStr?.toLongOrNull() ?: 0L
                    if (durMs > 0L) {
                        mediaDurationUs = durMs * 1000L
                    }
                } catch (_: Exception) {}
            }
            val clipDurUs = clip.durationMs * 1000L
            if (clipDurUs > mediaDurationUs) {
                mediaDurationUs = clipDurUs
            }
            prewarmStage = 1
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun startCodecAndSeek(targetUs: Long): Boolean {
        stopFast()
        lastHopKfUs = -1L
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
            try {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            } catch (_: Exception) {}
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

        var pendingIdx = -1
        var pendingPts = -1L
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
                            dec.queueInputBuffer(inIndex, 0, 0, 0L, 0)
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
            val clipDurationUs = effectiveMediaDurationUs
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
                        (clipDurationUs > 0L && pts >= clipDurationUs - 85_000L)

                    if (pts >= targetUs - 20_000L || atClipEnd) {
                        if (pendingIdx >= 0) {
                            dec.releaseOutputBuffer(pendingIdx, false)
                            pendingIdx = -1
                        }
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastRenderedPtsUs = pts
                        lastRenderedLocalUs = targetUs
                        isAccuratePausedFrameLatched = true
                        isCatchingUpFromSeek = false
                        prewarmStage = 3
                        notifyFirstFrame()
                        return true
                    } else {
                        if (pendingIdx >= 0) {
                            dec.releaseOutputBuffer(pendingIdx, false)
                        }
                        pendingIdx = outIndex
                        pendingPts = pts
                        currentPtsUs = pts
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    outputEos = true
                    break
                } else {
                    break
                }
            }

            if (pendingIdx >= 0 && outputEos) {
                dec.releaseOutputBuffer(pendingIdx, true)
                currentPtsUs = pendingPts
                lastRenderedPtsUs = pendingPts
                lastRenderedLocalUs = targetUs
                isAccuratePausedFrameLatched = true
                isCatchingUpFromSeek = false
                prewarmStage = 3
                pendingIdx = -1
                notifyFirstFrame()
                return true
            }
        } catch (_: Exception) {
            return false
        } finally {
            if (pendingIdx >= 0) {
                try { dec.releaseOutputBuffer(pendingIdx, false) } catch (_: Exception) {}
            }
        }
        return prewarmStage >= 3
    }

    fun ensureDecodedAt(targetUs: Long): Boolean {
        checkCatchUpTimeout()
        if (prewarmStage >= 3 && codec != null && !isCatchingUpFromSeek && hasValidFrameNear(targetUs)) return true
        val clipDurationUs = effectiveMediaDurationUs
        val isNearClipEnd = clipDurationUs > 0L && targetUs >= clipDurationUs - 85_000L
        if (isPrepared && lastRenderedPtsUs != -1L && (kotlin.math.abs(targetUs - lastRenderedPtsUs) <= 45_000L || (isNearClipEnd && lastRenderedPtsUs >= clipDurationUs - 85_000L))) {
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
            val didRender = seekTo(targetUs, fastKeyframeOnly = false)
            if (didRender) {
                prewarmStage = 3
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = true
                return true
            }
        }
        for (i in 0 until 5) {
            if (stepPrewarmTo(targetUs)) {
                prewarmStage = 3
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = true
                return true
            }
        }
        if ((outputEos && isNearClipEnd && lastRenderedPtsUs != -1L) || (clipDurationUs > 0L && isNearClipEnd && lastRenderedPtsUs >= clipDurationUs - 85_000L)) {
            prewarmStage = 3
            isCatchingUpFromSeek = false
            isAccuratePausedFrameLatched = true
        }
        return prewarmStage >= 3 && hasValidFrameNear(targetUs)
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

    private var isRecoveringCodec = false

    /**
     * Recreates the codec if it crashes or flushes with an unrecoverable error during rapid seeks.
     */
    fun recoverCodec(targetUs: Long): Boolean {
        if (isRecoveringCodec) return false
        isRecoveringCodec = true
        try {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
            currentPtsUs = -1L
            lastRenderedPtsUs = -1L
            inputEos = false
            outputEos = false
            prewarmStage = 1
            return startCodecAndSeek(targetUs)
        } finally {
            isRecoveringCodec = false
        }
    }

    /**
     * Check if decoder can be reused for another clip with same mime & dimensions (Section 19.7).
     */
    fun canReuse(other: Clip): Boolean {
        return isPrepared && codec != null && clip.path == other.path && clip.width == other.width && clip.height == other.height
    }

    val currentPath: String get() = clip.path

    fun seekTo(localUs: Long, fastKeyframeOnly: Boolean = false, isExport: Boolean = false): Boolean {
        checkCatchUpTimeout()
        stopFast()
        lastHopKfUs = -1L
        if (fastKeyframeOnly || (lastRenderedLocalUs != -1L && kotlin.math.abs(localUs - lastRenderedLocalUs) > 15_000L)) {
            isAccuratePausedFrameLatched = false
        }
        val clipDurationUs = effectiveMediaDurationUs
        val isNearClipEnd = clipDurationUs > 0L && localUs >= clipDurationUs - 85_000L
        if (isPrepared && lastRenderedPtsUs != -1L && !fastKeyframeOnly) {
            val inTargetWindow = lastRenderedPtsUs >= localUs - 25_000L && lastRenderedPtsUs <= localUs + 45_000L
            val nearEnd = (outputEos || lastRenderedPtsUs >= clipDurationUs - 85_000L) &&
                localUs >= clipDurationUs - 500_000L
            if (inTargetWindow || nearEnd) {
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = true
                prewarmStage = 3
                return true
            }
        }
        val ext = extractor ?: return false
        val dec = codec ?: return false
        var pendingIndex = -1
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
                // Only skip the seek if the frame was accurately latched at this keyframe.
                // After a clip replace, isAccuratePausedFrameLatched is reset to false,
                // which prevents stale state from freezing the preview on the wrong frame.
                if (isAccuratePausedFrameLatched && lastKeyframePtsUs != -1L && seekTimeUs == lastKeyframePtsUs && lastRenderedPtsUs == lastKeyframePtsUs) {
                    return true
                }
            }

            ext.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            try {
                dec.flush()
            } catch (_: Exception) {
                return recoverCodec(seekTimeUs)
            }
            inputEos = false
            outputEos = false
            currentPtsUs = -1L

            val bufferInfo = MediaCodec.BufferInfo()
            var rendered = false
            var attempts = 0
            val maxAttempts = if (isExport) 150 else 100
            val deadlineNs = System.nanoTime() + (if (isExport) 60_000_000L else 28_000_000L) // 28ms max per tick
            var pendingPts = -1L

            while (!rendered && attempts < maxAttempts && !outputEos) {
                attempts++

                // Burst-feed up to 8 input buffers (non-blocking 0L)
                var fed = 0
                while (!inputEos && fed < 8) {
                    val inputIndex = dec.dequeueInputBuffer(0L)
                    if (inputIndex >= 0) {
                        val inputBuffer = dec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val sampleSize = ext.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                if (isExport) {
                                    dec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                } else {
                                    dec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                                }
                                inputEos = true
                            } else {
                                dec.queueInputBuffer(inputIndex, 0, sampleSize, ext.sampleTime, 0)
                                ext.advance()
                            }
                        }
                        fed++
                    } else {
                        break
                    }
                }

                val timeoutUs = if (attempts == 1 && fed > 0) 2_000L else 500L
                val outIndex = dec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val pts = bufferInfo.presentationTimeUs
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEos) {
                        outputEos = true
                    }
                    if (isEos && bufferInfo.size == 0) {
                        dec.releaseOutputBuffer(outIndex, false)
                        break
                    }

                    val atClipEnd = outputEos ||
                        (clipDurationUs > 0L && pts >= clipDurationUs - 85_000L)

                    if (fastKeyframeOnly) {
                        if (pendingIndex >= 0) {
                            dec.releaseOutputBuffer(pendingIndex, false)
                            pendingIndex = -1
                        }
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastRenderedPtsUs = pts
                        lastKeyframePtsUs = pts
                        rendered = true
                        lastRenderedLocalUs = localUs
                        isAccuratePausedFrameLatched = false
                        isCatchingUpFromSeek = false
                        prewarmStage = 3
                        notifyFirstFrame()
                        break
                    } else {
                        // Exact Silent Preroll: silently discard all frames before localUs - 25_000L
                        if (pts >= localUs - 25_000L || atClipEnd) {
                            if (pendingIndex >= 0) {
                                dec.releaseOutputBuffer(pendingIndex, false)
                                pendingIndex = -1
                            }
                            dec.releaseOutputBuffer(outIndex, true)
                            currentPtsUs = pts
                            lastRenderedPtsUs = pts
                            lastKeyframePtsUs = pts
                            rendered = true
                            lastRenderedLocalUs = localUs
                            isAccuratePausedFrameLatched = true
                            isCatchingUpFromSeek = false
                            prewarmStage = 3
                            notifyFirstFrame()
                            break
                        } else {
                            if (pendingIndex >= 0) {
                                dec.releaseOutputBuffer(pendingIndex, false)
                            }
                            pendingIndex = outIndex
                            pendingPts = pts
                            currentPtsUs = pts
                        }
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    outputEos = true
                    break
                }

                if (!isExport && System.nanoTime() >= deadlineNs) {
                    break
                }
            }

            if (!rendered && pendingIndex >= 0) {
                dec.releaseOutputBuffer(pendingIndex, true)
                currentPtsUs = pendingPts
                lastRenderedPtsUs = pendingPts
                lastKeyframePtsUs = pendingPts
                rendered = true
                lastRenderedLocalUs = localUs
                isAccuratePausedFrameLatched = !fastKeyframeOnly
                isCatchingUpFromSeek = false
                prewarmStage = 3
                pendingIndex = -1
                notifyFirstFrame()
            }

            if (!rendered && fastKeyframeOnly && !outputEos) {
                var fallbackAttempts = 0
                while (!rendered && fallbackAttempts < 15 && !outputEos) {
                    fallbackAttempts++
                    if (!inputEos) {
                        val inIdx = dec.dequeueInputBuffer(1_000L)
                        if (inIdx >= 0) {
                            val inBuf = dec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                val sz = ext.readSampleData(inBuf, 0)
                                if (sz < 0) {
                                    dec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                                    inputEos = true
                                } else {
                                    dec.queueInputBuffer(inIdx, 0, sz, ext.sampleTime, 0)
                                    ext.advance()
                                }
                            }
                        }
                    }
                    val outIndex = dec.dequeueOutputBuffer(bufferInfo, 2_000L)
                    if (outIndex >= 0) {
                        val pts = bufferInfo.presentationTimeUs
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (isEos && bufferInfo.size == 0) {
                            dec.releaseOutputBuffer(outIndex, false)
                            break
                        }
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastRenderedPtsUs = pts
                        lastKeyframePtsUs = pts
                        rendered = true
                        lastRenderedLocalUs = localUs
                        isAccuratePausedFrameLatched = false
                        isCatchingUpFromSeek = false
                        prewarmStage = 3
                        notifyFirstFrame()
                        break
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                        outputEos = true
                        break
                    }
                }
            }

            if (!rendered && fastKeyframeOnly && outputEos && clipDurationUs > 0L) {
                try {
                    ext.seekTo((clipDurationUs - 100_000L).coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    dec.flush()
                    inputEos = false
                    outputEos = false
                    val inIdx = dec.dequeueInputBuffer(2_000L)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val sz = ext.readSampleData(inBuf, 0)
                            if (sz > 0) {
                                dec.queueInputBuffer(inIdx, 0, sz, ext.sampleTime, 0)
                                ext.advance()
                            }
                        }
                    }
                    val outIndex = dec.dequeueOutputBuffer(bufferInfo, 5_000L)
                    if (outIndex >= 0) {
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = bufferInfo.presentationTimeUs
                        lastRenderedPtsUs = bufferInfo.presentationTimeUs
                        lastKeyframePtsUs = bufferInfo.presentationTimeUs
                        rendered = true
                        lastRenderedLocalUs = localUs
                        isAccuratePausedFrameLatched = false
                        isCatchingUpFromSeek = false
                        prewarmStage = 3
                        notifyFirstFrame()
                    }
                } catch (_: Exception) {}
            }

            if (rendered) {
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = !fastKeyframeOnly
                prewarmStage = 3
                lastRenderedLocalUs = localUs
            } else {
                if (outputEos) {
                    isCatchingUpFromSeek = false
                    isAccuratePausedFrameLatched = !fastKeyframeOnly
                    prewarmStage = 3
                    lastRenderedLocalUs = localUs
                } else {
                    isCatchingUpFromSeek = true
                    isAccuratePausedFrameLatched = false
                }
            }
            return rendered
        } catch (_: Exception) {
            recoverCodec(localUs)
            isCatchingUpFromSeek = false
            isAccuratePausedFrameLatched = false
            return false
        } finally {
            if (pendingIndex >= 0) {
                try { dec.releaseOutputBuffer(pendingIndex, false) } catch (_: Exception) {}
            }
        }
    }

    private fun startFast() {
        // Disabled: Deterministic vsync-paced Case A drain handles 0.2x..10.0x without rogue background thread
        return
    }

    private fun stopFast() {
        fastRun = false
        fastTargetUs = -1L
        val th = fastThread
        fastThread = null
        if (th != null && th !== Thread.currentThread()) {
            try {
                th.interrupt()
                th.join(200)
            } catch (_: Exception) {}
        }
    }

    private fun hopToKeyframe(kfUs: Long): Boolean {
        val ext = extractor ?: return false
        val dec = codec ?: return false
        return try {
            ext.seekTo(kfUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            dec.flush()
            inputEos = false
            outputEos = false
            currentPtsUs = kfUs
            lastSeekTargetUs = kfUs
            isAccuratePausedFrameLatched = false
            isCatchingUpFromSeek = false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun feedInput(ext: MediaExtractor, dec: MediaCodec, max: Int) {
        var fed = 0
        while (!inputEos && fed < max) {
            val inIdx = dec.dequeueInputBuffer(0L)
            if (inIdx < 0) break
            val inBuf = dec.getInputBuffer(inIdx)
            if (inBuf != null) {
                inBuf.clear()
                val size = ext.readSampleData(inBuf, 0)
                if (size < 0) {
                    dec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                    inputEos = true
                } else {
                    dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                    ext.advance()
                }
            }
            fed++
        }
    }

    private fun decodeForwardFast(
        localUs: Long,
        ext: MediaExtractor,
        dec: MediaCodec,
        clipDurationUs: Long,
        isScrubbing: Boolean = false
    ): Boolean {
        val info = MediaCodec.BufferInfo()
        val deadlineNs = System.nanoTime() + 20_000_000L
        var pendingIdx = -1
        var pendingPts = -1L
        var reached = false

        try {
            while (!reached && !outputEos) {
                // feed: queue bhari rakho
                var fed = 0
                while (!inputEos && fed < 32) {
                    val inIdx = dec.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    val inBuf = dec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val size = ext.readSampleData(inBuf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                            inputEos = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                    fed++
                }

                // drain: beech ke frames drop, sirf latest hold
                var first = true
                while (true) {
                    val outIdx = dec.dequeueOutputBuffer(info, if (first) 1_500L else 0L)
                    first = false
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
                    if (outIdx < 0) {
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                            outputEos = true
                        }
                        break
                    }

                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEos) outputEos = true
                    if (isEos && info.size == 0) {
                        dec.releaseOutputBuffer(outIdx, false)
                        reached = true
                        break
                    }
                    if (pendingIdx >= 0) dec.releaseOutputBuffer(pendingIdx, false)
                    pendingIdx = outIdx
                    pendingPts = info.presentationTimeUs
                    if (isEos || pendingPts >= localUs - 15_000L) {
                        reached = true
                        break
                    }
                }
                if (!reached && System.nanoTime() >= deadlineNs) break
            }

            if (pendingIdx >= 0) {
                dec.releaseOutputBuffer(pendingIdx, true)   // har tick par max 1 frame render
                currentPtsUs = pendingPts
                lastRenderedPtsUs = pendingPts
                lastRenderedLocalUs = localUs
                val atEnd = outputEos || (clipDurationUs > 0L && pendingPts >= clipDurationUs - 85_000L)
                if (reached || atEnd) isAccuratePausedFrameLatched = !isScrubbing
                if (currentPtsUs >= lastSeekTargetUs - 50_000L || atEnd) isCatchingUpFromSeek = false
                if (atEnd) prewarmStage = 3
                feedInput(ext, dec, 40)   // decoder agle tick tak kaam karta rahe
                notifyFirstFrame()
                pendingIdx = -1
                return true
            }
            if (pendingIdx < 0) feedInput(ext, dec, 40)
            if (outputEos) {
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = !isScrubbing
                prewarmStage = 3
                lastRenderedLocalUs = localUs
            }
            return false
        } finally {
            if (pendingIdx >= 0) {
                try { dec.releaseOutputBuffer(pendingIdx, false) } catch (_: Exception) {}
            }
        }
    }

    fun frameAt(localUs: Long, isScrubbing: Boolean = false, isPaused: Boolean = false): Boolean {
        checkCatchUpTimeout()
        if (isScrubbing || (lastRenderedLocalUs != -1L && kotlin.math.abs(localUs - lastRenderedLocalUs) > 15_000L)) {
            isAccuratePausedFrameLatched = false
        }
        val ext = extractor ?: return false
        val dec = codec ?: return false
        val clipDurationUs = effectiveMediaDurationUs
        val isNearClipEnd = clipDurationUs > 0L && localUs >= clipDurationUs - 85_000L

        try {
            // 1. If already at or very near target frame, return success (do zero redundant work)
            val inTargetWindow = lastRenderedPtsUs != -1L && lastRenderedPtsUs >= localUs - 25_000L && lastRenderedPtsUs <= localUs + 45_000L
            // Wider clip-end window: if outputEos fired OR lastRendered is near end,
            // treat ANY position within last 500ms of clip as "at clip end" to avoid
            // spurious codec-flush/re-seek cycles when scrubbing near clip end.
            val atClipEnd = isNearClipEnd && lastRenderedPtsUs != -1L &&
                (outputEos || lastRenderedPtsUs >= clipDurationUs - 85_000L) &&
                localUs >= clipDurationUs - 500_000L
            if (!isScrubbing && (inTargetWindow || atClipEnd) && (isAccuratePausedFrameLatched || isPaused)) {
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = true
                prewarmStage = 3
                lastRenderedLocalUs = localUs
                return true
            }

            // 2. If already catching up from a seek and localUs is forward of currentPtsUs:
            // Continue fast forward decode without flushing or restarting
            if (!outputEos && isCatchingUpFromSeek && currentPtsUs != -1L && localUs >= currentPtsUs && (isScrubbing || localUs <= lastSeekTargetUs + 400_000L)) {
                return decodeForwardFast(localUs, ext, dec, clipDurationUs, isScrubbing = isScrubbing)
            }

            // 3. Determine if we need an extractor seek vs streaming forward
            val isInitial = lastRenderedPtsUs == -1L
            val isBackwardJump = currentPtsUs != -1L && localUs < currentPtsUs - 25_000L
            val forwardJumpThresholdUs = if (isScrubbing) 150_000L else maxOf(400_000L, (350_000L * effSpeed).toLong())
            val isForwardJump = currentPtsUs != -1L && (localUs > currentPtsUs + forwardJumpThresholdUs)
            val eosDrained = (outputEos || inputEos) && !atClipEnd &&
                // Re-seek if EOS happened AND:
                //   (a) localUs jumped significantly BACKWARD from last rendered (original case), OR
                //   (b) localUs jumped significantly FORWARD past last rendered — this handles
                //       duration-extend: EOS flags were cleared in clip setter, but if they're
                //       still true (race), we still recover by seeking into the new region.
                run {
                    val lastKnownUs = if (lastRenderedPtsUs != -1L) lastRenderedPtsUs else currentPtsUs
                    lastKnownUs != -1L && (
                        localUs < lastKnownUs - 100_000L ||          // backward from EOS frame
                        localUs > lastKnownUs + 250_000L             // forward past EOS (duration extended)
                    )
                }
            val needsSeek = isInitial || isBackwardJump || isForwardJump || eosDrained

            if (needsSeek) {
                stopFast()
                lastForwardKeyframeHopUs = -1L
                val didRender = seekTo(localUs, fastKeyframeOnly = isScrubbing)
                if (didRender) {
                    lastRenderedLocalUs = localUs
                    // During scrubbing, mark as keyframe-only (not accurate) so accurate seek
                    // will be triggered after scrubbing ends
                    isAccuratePausedFrameLatched = !isScrubbing
                }
                return didRender
            }

            // 4. Smooth forward decode (playback or forward scrub)
            return decodeForwardFast(localUs, ext, dec, clipDurationUs, isScrubbing = isScrubbing)
        } catch (_: Exception) {
            recoverCodec(localUs)
            isCatchingUpFromSeek = false
            return false
        }
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
     * Set synchronous = true when tearing down decoders to be immediately recreated on the same SurfaceTexture.
     */
    fun release(synchronous: Boolean = false) {
        stopFast()
        lastHopKfUs = -1L
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
        lastRenderedPtsUs = -1L
        lastSeekTargetUs = -1L
        lastKeyframePtsUs = -1L
        lastRenderedLocalUs = -1L
        isAccuratePausedFrameLatched = false
        isCatchingUpFromSeek = false
        catchUpStartMs = 0L
        lastForwardKeyframeHopUs = -1L
        prewarmStage = 0
        mediaDurationUs = -1L

        val task = Runnable {
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

        if (synchronous) {
            task.run()
        } else {
            releaseExecutor.execute(task)
        }
    }
}
