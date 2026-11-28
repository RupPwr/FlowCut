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
import com.flowcut.app.core.playback.PlayMode
import com.flowcut.app.engine.perf.PreviewEventLog
import com.flowcut.app.engine.perf.PreviewEventType
import com.flowcut.app.engine.perf.ResourceTracker
import com.flowcut.app.model.Clip
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

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

        /**
         * Pure frame selection decision function.
         * Principle: The frame shown must be the newest decoded frame whose pts <= playhead local time
         * (with tolerance up to half a frame); a previous frame may only be reused while it is still that newest frame.
         * Never skip a newer eligible frame that is already decoded/available.
         */
        fun shouldReusePreviousFrame(
            lastRenderedPtsUs: Long,
            localUs: Long,
            frameDurationUs: Long,
            mode: PlayMode
        ): Boolean {
            if (lastRenderedPtsUs < 0L) return false
            if (mode != PlayMode.PLAYING) {
                // Non-playing modes (PAUSED, SCRUBBING, FLINGING) preserve exact legacy window
                return lastRenderedPtsUs in (localUs - 25_000L)..(localUs + 45_000L)
            }
            val safeDurationUs = if (frameDurationUs > 0L) frameDurationUs else 33_333L
            val toleranceUs = safeDurationUs / 4L
            val minReuseUs = lastRenderedPtsUs - toleranceUs
            val maxReuseUs = lastRenderedPtsUs + safeDurationUs - toleranceUs
            return localUs in minReuseUs until maxReuseUs
        }
    }

    var slot: Int = -1

    init {
        PreviewEventLog.log(PreviewEventType.FRAMESOURCE_CREATE, slot = slot, clipId = clip.id)
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
                keyframeIntervalUs = -1L
                acceptedTargetUs = -1L
                lastRenderedPtsUs = -1L
                lastFastSeekKeyframeUs = -1L
                lastVideoPtsUs = -1L
                lastRenderedLocalUs = -1L
                lastKeyframePtsUs = -1L
                lastSeekTargetUs = -1L
                isAccuratePausedFrameLatched = false
                outputEos = false
                inputEos = false
                seekState = SeekState()
                seekConsecutiveNoProgress = 0
                lastSeekAttemptPtsUs = -1L
                lastCatchUpProgressPtsUs = -1L
                lastCatchUpProgressMs = 0L
                consecutiveZeroOutputCalls = 0
                lastScrubTargetUs = -1L
                lastScrubDirection = 0
                didAttemptNoProgressRecovery = false
            } else {
                // Same file — duration may have changed (handle drag / trim extend)
                if (clipDurUs > mediaDurationUs) {
                    mediaDurationUs = clipDurUs
                }
                if (value.durationMs != oldDurationMs) {
                    // Allow new targets beyond the old clip boundary without wiping active
                    // decoder/EOS state: clearing inputEos/outputEos without dec.flush()
                    // desynchronizes Kotlin state from the native MediaCodec EOS state,
                    // and wiping lastFastSeekKeyframeUs/seekState causes a 60Hz flush storm.
                    acceptedTargetUs = -1L
                    isAccuratePausedFrameLatched = false
                    seekConsecutiveNoProgress = 0
                    didAttemptNoProgressRecovery = false
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
    private var lastFastSeekKeyframeUs: Long = -1L
    private var lastVideoPtsUs: Long = -1L
    var isWaitingForFrame: Boolean = false
    var onSeekAcceptedLastFrame: (() -> Unit)? = null
    val lastDecodedPtsUs: Long get() = if (lastRenderedPtsUs != -1L) lastRenderedPtsUs else currentPtsUs
    val currentDecoderPtsUs: Long get() = currentPtsUs
    val isAtEndOfMedia: Boolean
        get() = outputEos && lastRenderedPtsUs > 0L
    val lastAvailablePtsUs: Long
        get() = lastRenderedPtsUs
    val codecName: String? get() = try { codec?.name } catch (_: Throwable) { null }
    val currentMimeType: String get() = currentMime
    @Volatile var didReleaseOutputBuffer: Boolean = false
        internal set

    val frameDurationUs: Long
        get() {
            val vf = videoFormat
            if (vf != null && vf.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                val fps = try {
                    vf.getInteger(MediaFormat.KEY_FRAME_RATE)
                } catch (_: Exception) {
                    try { vf.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() } catch (_: Exception) { 0 }
                }
                if (fps > 0) return 1_000_000L / fps
            }
            if (clip.frameRate > 0) {
                return 1_000_000L / clip.frameRate
            }
            return 33_333L // fallback ~30 fps
        }

    @Volatile var playbackSpeed: Float = 1.0f
    private val effSpeed: Float get() = playbackSpeed.coerceAtLeast(1.0f)
    private var currentMime: String = ""
    private var lastSeekTargetUs: Long = -1L
    private var lastKeyframePtsUs: Long = -1L
    private var lastRenderedLocalUs: Long = -1L
    var isAccuratePausedFrameLatched: Boolean = false
        internal set
    var keyframeIntervalUs: Long = -1L
        private set
    private var acceptedTargetUs: Long = -1L
    var seekState: SeekState = SeekState()
        internal set
    private var seekConsecutiveNoProgress: Int = 0
    private var lastSeekAttemptPtsUs: Long = -1L
    private var catchUpStartMs: Long = 0L
    private var lastCatchUpProgressPtsUs: Long = -1L
    private var lastCatchUpProgressMs: Long = 0L
    private var consecutiveZeroOutputCalls: Int = 0
    private var lastScrubTargetUs: Long = -1L
    private var lastScrubDirection: Int = 0
    private var didAttemptNoProgressRecovery: Boolean = false
    private var _isCatchingUpFromSeek: Boolean = false
    private var mediaDurationUs: Long = -1L

    private fun markDecodeProgress(ptsUs: Long) {
        consecutiveZeroOutputCalls = 0
        didAttemptNoProgressRecovery = false
        if (ptsUs != lastCatchUpProgressPtsUs) {
            lastCatchUpProgressPtsUs = ptsUs
            lastCatchUpProgressMs = SystemClock.elapsedRealtime()
        }
    }

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
                    val now = SystemClock.elapsedRealtime()
                    catchUpStartMs = now
                    lastCatchUpProgressMs = now
                    lastCatchUpProgressPtsUs = currentPtsUs
                }
            } else {
                catchUpStartMs = 0L
                lastCatchUpProgressMs = 0L
            }
            _isCatchingUpFromSeek = value
        }

    private fun checkCatchUpTimeout() {
        if (_isCatchingUpFromSeek && catchUpStartMs > 0L) {
            val now = SystemClock.elapsedRealtime()
            if (currentPtsUs != lastCatchUpProgressPtsUs) {
                lastCatchUpProgressPtsUs = currentPtsUs
                lastCatchUpProgressMs = now
                return
            }
            val stalledMs = now - (if (lastCatchUpProgressMs > 0L) lastCatchUpProgressMs else catchUpStartMs)
            if (stalledMs > 2000L) {
                // Genuine decoder stall: no PTS progress for > 2s.
                // Recover the codec cleanly instead of leaving a wedged decoder.
                isAccuratePausedFrameLatched = false
                lastRenderedPtsUs = -1L
                lastVideoPtsUs = -1L
                catchUpStartMs = now
                lastCatchUpProgressMs = now
                val recoveryTargetUs = when {
                    lastSeekTargetUs >= 0L -> lastSeekTargetUs
                    lastRenderedLocalUs >= 0L -> lastRenderedLocalUs
                    else -> 0L
                }
                recoverCodec(recoveryTargetUs)
            }
        }
    }

    fun hasValidFrameNear(targetUs: Long): Boolean {
        checkCatchUpTimeout()
        if (isAccuratePausedFrameLatched) return true
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

        val t0 = SystemClock.elapsedRealtime()
        try {
            val ext = MediaExtractor()
            ResourceTracker.onCreated(ResourceTracker.ResourceType.MEDIA_EXTRACTOR, clip.id)
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
                ResourceTracker.onReleased(ResourceTracker.ResourceType.MEDIA_EXTRACTOR, clip.id)
                return false
            }

            ext.selectTrack(track)
            extractor = ext
            videoTrackIndex = track

            val keyframes = getKeyframes()
            if (keyframes != null && keyframes.size >= 2) {
                keyframeIntervalUs = (keyframes[1] - keyframes[0]) * 1000L
            } else {
                try {
                    ext.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val first = ext.sampleTime
                    ext.seekTo(if (first >= 0L) first + 1L else 1L, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    var second = ext.sampleTime
                    if (second <= first) {
                        var count = 0
                        while (ext.advance() && count < 300) {
                            count++
                            if ((ext.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                second = ext.sampleTime
                                break
                            }
                        }
                    }
                    if (first >= 0L && second > first) {
                        keyframeIntervalUs = second - first
                    }
                    ext.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                } catch (_: Exception) {}
            }
            Log.w("SEEKTRACE", "GOP clip=${clip.id} intervalUs=$keyframeIntervalUs")

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
                    ResourceTracker.onCreated(ResourceTracker.ResourceType.MEDIA_METADATA_RETRIEVER, clip.id)
                    retriever.setDataSource(clip.path)
                    val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    retriever.release()
                    ResourceTracker.onReleased(ResourceTracker.ResourceType.MEDIA_METADATA_RETRIEVER, clip.id)
                    val durMs = durStr?.toLongOrNull() ?: 0L
                    if (durMs > 0L) {
                        mediaDurationUs = durMs * 1000L
                    }
                } catch (e: Exception) {
                    PreviewEventLog.logSwallowedException("FrameSource.retriever_dur", e)
                }
            }
            val clipDurUs = clip.durationMs * 1000L
            if (clipDurUs > mediaDurationUs) {
                mediaDurationUs = clipDurUs
            }
            prewarmStage = 1
            val durMs = SystemClock.elapsedRealtime() - t0
            PreviewEventLog.log(PreviewEventType.FRAMESOURCE_CONFIGURE, slot = slot, clipId = clip.id, arg0 = durMs, reason = "extractor_ready")
            return true
        } catch (e: Exception) {
            PreviewEventLog.logSwallowedException("FrameSource.prepareExtractorOnly", e)
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

        val t0 = SystemClock.elapsedRealtime()
        try {
            surface = Surface(surfaceTexture)
            ResourceTracker.onCreated(ResourceTracker.ResourceType.SURFACE, clip.id)
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
                ResourceTracker.onCreated(ResourceTracker.ResourceType.MEDIA_CODEC, clip.id)
                hwDec.configure(format, surface, null, 0)
                hwDec.start()
                decoder = hwDec
            } catch (e1: Exception) {
                PreviewEventLog.logSwallowedException("FrameSource.startCodec_hwPass1", e1)
                try {
                    decoder?.release()
                    ResourceTracker.onReleased(ResourceTracker.ResourceType.MEDIA_CODEC, clip.id)
                } catch (_: Exception) {}
                decoder = null

                // Pass 2: Retry Hardware Decoder with stripped profile/level and basic dimensions
                try {
                    val fallbackFormat = ext.getTrackFormat(videoTrackIndex)
                    fallbackFormat.setString(MediaFormat.KEY_MIME, targetMime)
                    val hwRetry = MediaCodec.createDecoderByType(targetMime)
                    ResourceTracker.onCreated(ResourceTracker.ResourceType.MEDIA_CODEC, clip.id)
                    hwRetry.configure(fallbackFormat, surface, null, 0)
                    hwRetry.start()
                    decoder = hwRetry
                } catch (e2: Exception) {
                    PreviewEventLog.logSwallowedException("FrameSource.startCodec_hwPass2", e2)
                    try {
                        decoder?.release()
                        ResourceTracker.onReleased(ResourceTracker.ResourceType.MEDIA_CODEC, clip.id)
                    } catch (_: Exception) {}
                    decoder = null

                    // Pass 3: Fallback to Software Codec (c2.android.* / OMX.google.*)
                    val swCodecName = findSoftwareDecoderName(targetMime)
                    if (swCodecName != null) {
                        try {
                            val swDec = MediaCodec.createByCodecName(swCodecName)
                            ResourceTracker.onCreated(ResourceTracker.ResourceType.MEDIA_CODEC, clip.id)
                            swDec.configure(format, surface, null, 0)
                            swDec.start()
                            decoder = swDec
                        } catch (e3: Exception) {
                            PreviewEventLog.logSwallowedException("FrameSource.startCodec_swPass3", e3)
                            try {
                                decoder?.release()
                                ResourceTracker.onReleased(ResourceTracker.ResourceType.MEDIA_CODEC, clip.id)
                            } catch (_: Exception) {}
                            decoder = null
                        }
                    }
                }
            }

            if (decoder == null) {
                try {
                    surface?.release()
                    ResourceTracker.onReleased(ResourceTracker.ResourceType.SURFACE, clip.id)
                } catch (_: Exception) {}
                surface = null
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
            val syncSampleTimeUs = ext.sampleTime
            val framesToDecode = if (frameDurationUs > 0L) (targetUs - syncSampleTimeUs).coerceAtLeast(0L) / frameDurationUs else 0L
            PreviewEventLog.log(PreviewEventType.SEEK_SYNC, slot = slot, clipId = clip.id, arg0 = syncSampleTimeUs, arg1 = targetUs, reason = "frames=$framesToDecode")
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
            val durMs = SystemClock.elapsedRealtime() - t0
            PreviewEventLog.log(PreviewEventType.FRAMESOURCE_START, slot = slot, clipId = clip.id, arg0 = durMs, arg1 = targetUs, reason = "startCodecAndSeek_done")
            return true
        } catch (e: Exception) {
            PreviewEventLog.logSwallowedException("FrameSource.startCodecAndSeek", e)
            try {
                surface?.release()
                ResourceTracker.onReleased(ResourceTracker.ResourceType.SURFACE, clip.id)
            } catch (_: Exception) {}
            surface = null
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

            // Drain output buffers without prematurely declaring outputEos on a single TRY_AGAIN_LATER
            var drained = 0
            var eosTryAgain = 0
            val clipDurationUs = effectiveMediaDurationUs
            val maxDrainSteps = if (inputEos) 16 else 4
            while (drained < maxDrainSteps && !outputEos) {
                val timeoutUs = if (drained == 0 || inputEos) 3_000L else 0L
                drained++
                val outIndex = dec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    eosTryAgain = 0
                    val pts = bufferInfo.presentationTimeUs
                    markDecodeProgress(pts)
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEos) {
                        outputEos = true
                        if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                        if (pts > lastVideoPtsUs) lastVideoPtsUs = pts
                    }
                    if (isEos && bufferInfo.size == 0) {
                        dec.releaseOutputBuffer(outIndex, false)
                        break
                    }
                    val atClipEnd = outputEos ||
                        (clipDurationUs > 0L && pts >= clipDurationUs - 85_000L)

                    if (pts >= targetUs - 20_000L || atClipEnd) {
                        if (pendingIdx >= 0) {
                            dec.releaseOutputBuffer(pendingIdx, false)
                            pendingIdx = -1
                        }
                        didReleaseOutputBuffer = true
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
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                    outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    eosTryAgain++
                    if (eosTryAgain >= 15) {
                        outputEos = true
                        if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                        break
                    }
                } else {
                    break
                }
            }

            if (pendingIdx >= 0 && outputEos) {
                didReleaseOutputBuffer = true
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
            if (outputEos && prewarmStage < 3) {
                // Never wedge permanently at prewarmStage == 2 when EOS is reached without a frame.
                // Promote to stage 3 so frameAt() can flush and re-seek cleanly on subsequent targets.
                prewarmStage = 3
                isCatchingUpFromSeek = false
            }
        } catch (e: Exception) {
            PreviewEventLog.logSwallowedException("FrameSource.stepPrewarmTo", e)
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
        PreviewEventLog.log(PreviewEventType.FRAMESOURCE_RELEASE, slot = slot, clipId = clip.id, reason = "recoverCodec")
        try {
            try { codec?.stop() } catch (e: Exception) { PreviewEventLog.logSwallowedException("FrameSource.recoverCodec_stop", e) }
            try {
                if (codec != null) {
                    codec?.release()
                    ResourceTracker.onReleased(ResourceTracker.ResourceType.MEDIA_CODEC, clip.id)
                }
            } catch (e: Exception) {
                PreviewEventLog.logSwallowedException("FrameSource.recoverCodec_release", e)
            }
            try {
                if (surface != null) {
                    surface?.release()
                    ResourceTracker.onReleased(ResourceTracker.ResourceType.SURFACE, clip.id)
                }
            } catch (e: Exception) {
                PreviewEventLog.logSwallowedException("FrameSource.recoverCodec_surface", e)
            }
            surface = null
            codec = null
            currentPtsUs = -1L
            lastRenderedPtsUs = -1L
            lastFastSeekKeyframeUs = -1L
            lastVideoPtsUs = -1L
            inputEos = false
            outputEos = false
            seekState = SeekState()
            seekConsecutiveNoProgress = 0
            lastSeekAttemptPtsUs = -1L
            consecutiveZeroOutputCalls = 0
            lastCatchUpProgressPtsUs = -1L
            lastCatchUpProgressMs = 0L
            lastScrubTargetUs = -1L
            lastScrubDirection = 0
            didAttemptNoProgressRecovery = false
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
        acceptedTargetUs = -1L
        android.util.Log.w("SEEKTRACE", "ENTER target=$localUs fast=$fastKeyframeOnly lastVideoPts=$lastVideoPtsUs lastRendered=$lastRenderedPtsUs eos=$outputEos")
        checkCatchUpTimeout()
        stopFast()
        lastHopKfUs = -1L
        if (fastKeyframeOnly || (lastRenderedLocalUs != -1L && kotlin.math.abs(localUs - lastRenderedLocalUs) > 15_000L)) {
            isAccuratePausedFrameLatched = false
        }
        val clipDurationUs = effectiveMediaDurationUs
        val endTolUs = maxOf(250_000L, frameDurationUs * 15)
        val isNearClipEnd = clipDurationUs > 0L && localUs >= clipDurationUs - endTolUs
        if (isPrepared && lastRenderedPtsUs != -1L && !fastKeyframeOnly) {
            val inTargetWindow = lastRenderedPtsUs >= localUs - 25_000L && lastRenderedPtsUs <= localUs + 45_000L
            val nearEnd = (outputEos || lastRenderedPtsUs >= clipDurationUs - endTolUs) &&
                localUs >= clipDurationUs - 500_000L
            if (inTargetWindow || nearEnd) {
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = true
                prewarmStage = 3
                android.util.Log.w("SEEKTRACE", "EXIT-1 rendered=true target=$localUs")
                return true
            }
        }
        val ext = extractor ?: run {
            android.util.Log.w("SEEKTRACE", "EXIT-2 rendered=false target=$localUs")
            return false
        }
        val dec = codec ?: run {
            android.util.Log.w("SEEKTRACE", "EXIT-3 rendered=false target=$localUs")
            return false
        }
        var pendingIndex = -1
        val t0 = SystemClock.elapsedRealtime()
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
                if (!outputEos && !inputEos && lastFastSeekKeyframeUs == seekTimeUs) {
                    val isInvalid = lastRenderedPtsUs <= 0L && localUs > 1_000_000L
                    val withinTolerance = abs(lastRenderedPtsUs - localUs) <= maxOf(250_000L, frameDurationUs * 15)
                    if (!isInvalid && withinTolerance) {
                        isCatchingUpFromSeek = false
                        android.util.Log.w("SEEKTRACE", "EXIT-4 rendered=true target=$localUs")
                        return true   // this keyframe is already on screen; no flush needed
                    }
                }
                // Only skip the seek if the frame was accurately latched at this keyframe.
                // After a clip replace, isAccuratePausedFrameLatched is reset to false,
                // which prevents stale state from freezing the preview on the wrong frame.
                if (!outputEos && !inputEos && isAccuratePausedFrameLatched && lastKeyframePtsUs != -1L && seekTimeUs == lastKeyframePtsUs && lastRenderedPtsUs == lastKeyframePtsUs) {
                    isCatchingUpFromSeek = false
                    android.util.Log.w("SEEKTRACE", "EXIT-5 rendered=true target=$localUs")
                    return true
                }
            }

            ext.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val syncSampleTimeUs = ext.sampleTime
            val resolvedSyncUs = if (syncSampleTimeUs >= 0L) syncSampleTimeUs else seekTimeUs
            if (fastKeyframeOnly && !outputEos && !inputEos && syncSampleTimeUs >= 0L &&
                lastRenderedPtsUs != -1L && lastFastSeekKeyframeUs == syncSampleTimeUs &&
                abs(lastRenderedPtsUs - syncSampleTimeUs) <= maxOf(50_000L, frameDurationUs * 2)
            ) {
                // Even without an .idx file (e.g. immediately after Replace), MediaExtractor
                // resolved to the exact same sync frame already displayed. Skip dec.flush()
                // so hardware MediaCodec is never hammered at 60Hz.
                isCatchingUpFromSeek = false
                lastRenderedLocalUs = localUs
                return true
            }
            val framesToDecode = if (frameDurationUs > 0L) (localUs - syncSampleTimeUs).coerceAtLeast(0L) / frameDurationUs else 0L
            PreviewEventLog.log(PreviewEventType.SEEK_SYNC, slot = slot, clipId = clip.id, arg0 = syncSampleTimeUs, arg1 = localUs, reason = "frames=$framesToDecode")
            try {
                dec.flush()
                val flushDurMs = SystemClock.elapsedRealtime() - t0
                PreviewEventLog.log(PreviewEventType.FRAMESOURCE_FLUSH, slot = slot, clipId = clip.id, arg0 = flushDurMs, arg1 = localUs, reason = "seekTo(fast=$fastKeyframeOnly)")
                PreviewEventLog.flushCounter.recordFlush("seekTo_fast$fastKeyframeOnly")
            } catch (e: Exception) {
                PreviewEventLog.logSwallowedException("FrameSource.seekTo_flush", e)
                val rec = recoverCodec(seekTimeUs)
                android.util.Log.w("SEEKTRACE", "EXIT-6 rendered=$rec target=$localUs")
                return rec
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

            var eosTryAgain = 0
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
                                dec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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

                val timeoutUs = if ((attempts == 1 && fed > 0) || inputEos) 2_000L else 500L
                val outIndex = dec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    eosTryAgain = 0
                    val pts = bufferInfo.presentationTimeUs
                    markDecodeProgress(pts)
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEos) {
                        outputEos = true
                        if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                        if (pts > lastVideoPtsUs) {
                            lastVideoPtsUs = pts
                        }
                    }
                    if (isEos && bufferInfo.size == 0) {
                        dec.releaseOutputBuffer(outIndex, false)
                        break
                    }

                    val atClipEnd = outputEos ||
                        (clipDurationUs > 0L && pts >= clipDurationUs - endTolUs)

                    if (fastKeyframeOnly) {
                        if (pendingIndex >= 0) {
                            dec.releaseOutputBuffer(pendingIndex, false)
                            pendingIndex = -1
                        }
                        didReleaseOutputBuffer = true
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastRenderedPtsUs = pts
                        lastKeyframePtsUs = pts
                        lastFastSeekKeyframeUs = resolvedSyncUs
                        rendered = true
                        lastRenderedLocalUs = localUs
                        isAccuratePausedFrameLatched = false
                        isCatchingUpFromSeek = false
                        prewarmStage = 3
                        seekState = SeekState(
                            lastTargetUs = localUs,
                            lastFast = true,
                            outcome = SeekOutcome.REACHED,
                            lastRenderedPtsUs = bufferInfo.presentationTimeUs
                        )
                        notifyFirstFrame()
                        break
                    } else {
                        // Exact Silent Preroll: silently discard all frames before localUs - 25_000L
                        if (pts >= localUs - 25_000L || atClipEnd) {
                            if (pendingIndex >= 0) {
                                dec.releaseOutputBuffer(pendingIndex, false)
                                pendingIndex = -1
                            }
                            didReleaseOutputBuffer = true
                            dec.releaseOutputBuffer(outIndex, true)
                            currentPtsUs = pts
                            lastRenderedPtsUs = pts
                            lastKeyframePtsUs = pts
                            rendered = true
                            lastFastSeekKeyframeUs = -1L
                            lastRenderedLocalUs = localUs
                            isAccuratePausedFrameLatched = true
                            isCatchingUpFromSeek = false
                            prewarmStage = 3
                            seekState = SeekState(
                                lastTargetUs = localUs,
                                lastFast = false,
                                outcome = SeekOutcome.REACHED,
                                lastRenderedPtsUs = pts
                            )
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
                    eosTryAgain++
                    if (eosTryAgain >= 15) {
                        outputEos = true
                        if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                        break
                    }
                }

                if (!isExport && System.nanoTime() >= deadlineNs) {
                    break
                }
            }

            if (!rendered && pendingIndex >= 0) {
                if (fastKeyframeOnly || outputEos) {
                    didReleaseOutputBuffer = true
                    dec.releaseOutputBuffer(pendingIndex, true)
                    currentPtsUs = pendingPts
                    lastRenderedPtsUs = pendingPts
                    lastKeyframePtsUs = pendingPts
                    if (fastKeyframeOnly) {
                        lastFastSeekKeyframeUs = resolvedSyncUs
                    } else {
                        lastFastSeekKeyframeUs = -1L
                    }
                    rendered = true
                    lastRenderedLocalUs = localUs
                    isAccuratePausedFrameLatched = !fastKeyframeOnly
                    isCatchingUpFromSeek = false
                    prewarmStage = 3
                    seekState = SeekState(
                        lastTargetUs = localUs,
                        lastFast = fastKeyframeOnly,
                        outcome = SeekOutcome.REACHED,
                        lastRenderedPtsUs = pendingPts
                    )
                    notifyFirstFrame()
                } else {
                    // Silently drop intermediate frame before target in accurate seek:
                    // Keep isCatchingUpFromSeek = true to allow resumable forward catch-up on subsequent draws without flushing!
                    dec.releaseOutputBuffer(pendingIndex, false)
                    currentPtsUs = pendingPts
                    isAccuratePausedFrameLatched = false
                    isCatchingUpFromSeek = true
                    seekState = SeekState(
                        lastTargetUs = localUs,
                        lastFast = false,
                        outcome = SeekOutcome.IN_PROGRESS,
                        lastRenderedPtsUs = lastRenderedPtsUs
                    )
                    rendered = false
                }
                pendingIndex = -1
            }

            if (!rendered && fastKeyframeOnly && !outputEos) {
                var fallbackAttempts = 0
                var fallbackEosTryAgain = 0
                while (!rendered && fallbackAttempts < 18 && !outputEos) {
                    fallbackAttempts++
                    if (!inputEos) {
                        val inIdx = dec.dequeueInputBuffer(1_000L)
                        if (inIdx >= 0) {
                            val inBuf = dec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                val sz = ext.readSampleData(inBuf, 0)
                                if (sz < 0) {
                                    dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
                        fallbackEosTryAgain = 0
                        val pts = bufferInfo.presentationTimeUs
                        markDecodeProgress(pts)
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (isEos && bufferInfo.size == 0) {
                            outputEos = true
                            dec.releaseOutputBuffer(outIndex, false)
                            break
                        }
                        didReleaseOutputBuffer = true
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        lastRenderedPtsUs = pts
                        lastKeyframePtsUs = pts
                        lastFastSeekKeyframeUs = resolvedSyncUs
                        rendered = true
                        lastRenderedLocalUs = localUs
                        isAccuratePausedFrameLatched = false
                        isCatchingUpFromSeek = false
                        prewarmStage = 3
                        seekState = SeekState(
                            lastTargetUs = localUs,
                            lastFast = true,
                            outcome = SeekOutcome.REACHED,
                            lastRenderedPtsUs = bufferInfo.presentationTimeUs
                        )
                        notifyFirstFrame()
                        break
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                        fallbackEosTryAgain++
                        if (fallbackEosTryAgain >= 15) {
                            outputEos = true
                            if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                            break
                        }
                    }
                }
            }

            if (!rendered && fastKeyframeOnly && outputEos && clipDurationUs > 0L) {
                try {
                    val safeTailUs = ((if (lastVideoPtsUs > 0L) lastVideoPtsUs else clipDurationUs) - 100_000L).coerceAtLeast(0L)
                    ext.seekTo(safeTailUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val tailSyncUs = ext.sampleTime
                    dec.flush()
                    inputEos = false
                    outputEos = false
                    var tailAttempts = 0
                    var tailEosTryAgain = 0
                    while (!rendered && tailAttempts < 18 && !outputEos) {
                        tailAttempts++
                        var tailFed = 0
                        while (!inputEos && tailFed < 8) {
                            val inIdx = dec.dequeueInputBuffer(0L)
                            if (inIdx < 0) break
                            val inBuf = dec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                val sz = ext.readSampleData(inBuf, 0)
                                if (sz < 0) {
                                    dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEos = true
                                } else {
                                    dec.queueInputBuffer(inIdx, 0, sz, ext.sampleTime, 0)
                                    ext.advance()
                                }
                            }
                            tailFed++
                        }
                        val outIndex = dec.dequeueOutputBuffer(bufferInfo, 2_000L)
                        if (outIndex >= 0) {
                            tailEosTryAgain = 0
                            val pts = bufferInfo.presentationTimeUs
                            markDecodeProgress(pts)
                            val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (isEos && bufferInfo.size == 0) {
                                outputEos = true
                                dec.releaseOutputBuffer(outIndex, false)
                                break
                            }
                            didReleaseOutputBuffer = true
                            dec.releaseOutputBuffer(outIndex, true)
                            currentPtsUs = pts
                            lastRenderedPtsUs = pts
                            lastKeyframePtsUs = pts
                            lastFastSeekKeyframeUs = if (tailSyncUs >= 0L) tailSyncUs else resolvedSyncUs
                            rendered = true
                            lastRenderedLocalUs = localUs
                            isAccuratePausedFrameLatched = false
                            isCatchingUpFromSeek = false
                            prewarmStage = 3
                            seekState = SeekState(
                                lastTargetUs = localUs,
                                lastFast = true,
                                outcome = SeekOutcome.REACHED,
                                lastRenderedPtsUs = pts
                            )
                            notifyFirstFrame()
                            break
                        } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                            tailEosTryAgain++
                            if (tailEosTryAgain >= 15) {
                                outputEos = true
                                break
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (rendered) {
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = !fastKeyframeOnly
                prewarmStage = 3
                lastRenderedLocalUs = localUs
                if (!fastKeyframeOnly) {
                    lastFastSeekKeyframeUs = -1L
                }
            } else {
                lastFastSeekKeyframeUs = -1L
                if (outputEos) {
                    if (lastRenderedPtsUs > lastVideoPtsUs) {
                        lastVideoPtsUs = lastRenderedPtsUs
                    }
                    isCatchingUpFromSeek = false
                    isAccuratePausedFrameLatched = !fastKeyframeOnly
                    prewarmStage = 3
                    lastRenderedLocalUs = localUs
                } else {
                    isCatchingUpFromSeek = true
                    isAccuratePausedFrameLatched = false
                }
            }
            val totalDurMs = SystemClock.elapsedRealtime() - t0
            PreviewEventLog.log(PreviewEventType.SEEK_RESULT, slot = slot, clipId = clip.id, arg0 = totalDurMs, arg1 = if (rendered) 1L else 0L, reason = "seekTo_rendered=$rendered")
            android.util.Log.w("SEEKTRACE", "EXIT-7 rendered=$rendered target=$localUs")
            return rendered
        } catch (e: Exception) {
            PreviewEventLog.logSwallowedException("FrameSource.seekTo", e)
            recoverCodec(localUs)
            isCatchingUpFromSeek = false
            isAccuratePausedFrameLatched = false
            android.util.Log.w("SEEKTRACE", "EXIT-8 rendered=false target=$localUs")
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
            PreviewEventLog.log(PreviewEventType.FRAMESOURCE_FLUSH, slot = slot, clipId = clip.id, arg0 = kfUs, reason = "hopToKeyframe")
            PreviewEventLog.flushCounter.recordFlush("hopToKeyframe")
            inputEos = false
            outputEos = false
            currentPtsUs = kfUs
            lastSeekTargetUs = kfUs
            isAccuratePausedFrameLatched = false
            isCatchingUpFromSeek = false
            true
        } catch (e: Exception) {
            PreviewEventLog.logSwallowedException("FrameSource.hopToKeyframe", e)
            false
        }
    }

    private fun feedInput(ext: MediaExtractor, dec: MediaCodec, max: Int): Int {
        var fed = 0
        while (!inputEos && fed < max) {
            val inIdx = dec.dequeueInputBuffer(0L)
            if (inIdx < 0) break
            val inBuf = dec.getInputBuffer(inIdx)
            if (inBuf != null) {
                inBuf.clear()
                val size = ext.readSampleData(inBuf, 0)
                if (size < 0) {
                    dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEos = true
                } else {
                    dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                    ext.advance()
                }
            }
            fed++
        }
        return fed
    }

    private fun decodeCatchUpSlice(
        targetUs: Long,
        ext: MediaExtractor,
        dec: MediaCodec,
        clipDurationUs: Long
    ): Boolean {
        val info = MediaCodec.BufferInfo()
        val deadlineNs = System.nanoTime() + 10_000_000L // 10ms slice max per draw
        var reached = false
        var eosTryAgain = 0
        var decodedCount = 0L

        try {
            while (!reached && !outputEos && System.nanoTime() < deadlineNs) {
                // Burst feed input buffers
                var fed = 0
                while (!inputEos && fed < 16) {
                    val inIdx = dec.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    val inBuf = dec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val size = ext.readSampleData(inBuf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                    fed++
                }

                // Drain output buffers
                while (true) {
                    val outTimeoutUs = if (inputEos) 2_000L else 1_000L
                    val outIdx = dec.dequeueOutputBuffer(info, outTimeoutUs)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
                    if (outIdx < 0) {
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                            eosTryAgain++
                            if (eosTryAgain >= 15) {
                                outputEos = true
                                if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                            }
                        }
                        break
                    }

                    eosTryAgain = 0
                    decodedCount++
                    val pts = info.presentationTimeUs
                    markDecodeProgress(pts)
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEos) {
                        outputEos = true
                        if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                        if (pts > lastVideoPtsUs) lastVideoPtsUs = pts
                    }
                    if (isEos && info.size == 0) {
                        dec.releaseOutputBuffer(outIdx, false)
                        break
                    }
                    val atEnd = outputEos || (clipDurationUs > 0L && pts >= clipDurationUs - 85_000L)
                    currentPtsUs = pts

                    if (pts >= targetUs - 25_000L || atEnd) {
                        didReleaseOutputBuffer = true
                        dec.releaseOutputBuffer(outIdx, true)
                        lastRenderedPtsUs = pts
                        lastKeyframePtsUs = pts
                        lastRenderedLocalUs = targetUs
                        isAccuratePausedFrameLatched = true
                        isCatchingUpFromSeek = false
                        prewarmStage = 3
                        seekState = SeekState(
                            lastTargetUs = targetUs,
                            lastFast = false,
                            outcome = SeekOutcome.REACHED,
                            lastRenderedPtsUs = pts
                        )
                        notifyFirstFrame()
                        reached = true
                        break
                    } else {
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }

            if (!reached && !outputEos && decodedCount == 0L) {
                consecutiveZeroOutputCalls++
                if (consecutiveZeroOutputCalls >= 8) {
                    consecutiveZeroOutputCalls = 0
                    recoverCodec(targetUs)
                    return false
                }
            }

            if (!reached) {
                if (outputEos) {
                    isCatchingUpFromSeek = false
                    isAccuratePausedFrameLatched = true
                    prewarmStage = 3
                    lastRenderedLocalUs = targetUs
                } else {
                    isCatchingUpFromSeek = true
                    seekState = seekState.copy(
                        lastTargetUs = targetUs,
                        lastFast = false,
                        outcome = SeekOutcome.IN_PROGRESS
                    )
                }
            }
            return reached
        } catch (e: Exception) {
            PreviewEventLog.logSwallowedException("FrameSource.decodeCatchUpSlice", e)
            recoverCodec(targetUs)
            isCatchingUpFromSeek = false
            return false
        }
    }

    /**
     * Bounded forward decode slice used during active forward scrub / trim-handle drag.
     * Decodes smoothly through the current GOP with zero dec.flush() calls and renders
     * the latest decoded frame on every draw tick so long-GOP videos never freeze on a
     * single keyframe while dragging a trim handle forward.
     */
    private fun decodeForwardScrubSlice(
        targetUs: Long,
        ext: MediaExtractor,
        dec: MediaCodec,
        clipDurationUs: Long
    ): Boolean {
        val info = MediaCodec.BufferInfo()
        val deadlineNs = System.nanoTime() + 12_000_000L // 12ms slice keeps UI/timeline at 60fps
        var pendingIdx = -1
        var pendingPts = -1L
        var reached = false
        var eosTryAgain = 0
        var decodedCount = 0L

        try {
            while (!reached && !outputEos && System.nanoTime() < deadlineNs) {
                var fed = 0
                while (!inputEos && fed < 16) {
                    val inIdx = dec.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    val inBuf = dec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val size = ext.readSampleData(inBuf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                    fed++
                }

                var first = true
                while (true) {
                    val outTimeoutUs = when {
                        inputEos -> 2_000L
                        first -> 1_500L
                        else -> 0L
                    }
                    val outIdx = dec.dequeueOutputBuffer(info, outTimeoutUs)
                    first = false
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
                    if (outIdx < 0) {
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                            eosTryAgain++
                            if (eosTryAgain >= 15) {
                                outputEos = true
                                if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                            }
                        }
                        break
                    }

                    eosTryAgain = 0
                    decodedCount++
                    val pts = info.presentationTimeUs
                    markDecodeProgress(pts)
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEos) {
                        outputEos = true
                        if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                        if (pts > lastVideoPtsUs) lastVideoPtsUs = pts
                    }
                    if (isEos && info.size == 0) {
                        dec.releaseOutputBuffer(outIdx, false)
                        reached = true
                        break
                    }
                    if (pendingIdx >= 0) {
                        dec.releaseOutputBuffer(pendingIdx, false)
                    }
                    pendingIdx = outIdx
                    pendingPts = pts
                    currentPtsUs = pts
                    val atEnd = outputEos || (clipDurationUs > 0L && pts >= clipDurationUs - 85_000L)
                    if (pts >= targetUs - 15_000L || atEnd) {
                        reached = true
                        break
                    }
                }
            }

            if (pendingIdx >= 0) {
                val atEnd = outputEos || (clipDurationUs > 0L && pendingPts >= clipDurationUs - 85_000L)
                didReleaseOutputBuffer = true
                dec.releaseOutputBuffer(pendingIdx, true)
                currentPtsUs = pendingPts
                lastRenderedPtsUs = pendingPts
                lastRenderedLocalUs = targetUs
                isAccuratePausedFrameLatched = false
                isCatchingUpFromSeek = !reached && !atEnd
                prewarmStage = 3
                seekState = SeekState(
                    lastTargetUs = targetUs,
                    lastFast = true,
                    outcome = if (reached || atEnd) SeekOutcome.REACHED else SeekOutcome.IN_PROGRESS,
                    lastRenderedPtsUs = pendingPts
                )
                notifyFirstFrame()
                pendingIdx = -1
                return true
            }

            if (decodedCount == 0L && !outputEos) {
                consecutiveZeroOutputCalls++
                if (consecutiveZeroOutputCalls >= 8) {
                    consecutiveZeroOutputCalls = 0
                    recoverCodec(targetUs)
                    return false
                }
            }

            if (outputEos) {
                isCatchingUpFromSeek = false
                prewarmStage = 3
                lastRenderedLocalUs = targetUs
            } else {
                isCatchingUpFromSeek = true
            }
            return false
        } catch (e: Exception) {
            PreviewEventLog.logSwallowedException("FrameSource.decodeForwardScrubSlice", e)
            recoverCodec(targetUs)
            isCatchingUpFromSeek = false
            return false
        } finally {
            if (pendingIdx >= 0) {
                try { dec.releaseOutputBuffer(pendingIdx, false) } catch (_: Exception) {}
            }
        }
    }

    private fun decodeForwardFast(
        localUs: Long,
        ext: MediaExtractor,
        dec: MediaCodec,
        clipDurationUs: Long,
        isScrubbing: Boolean = false,
        mode: PlayMode = PlayMode.PLAYING
    ): Boolean {
        val info = MediaCodec.BufferInfo()
        val deadlineNs = System.nanoTime() + 20_000_000L
        var pendingIdx = -1
        var pendingPts = -1L
        var reached = false
        var droppedCount = 0L
        var queuedCount = 0L
        var tryAgainCount = 0L
        var decodedCount = 0L

        var eosTryAgain = 0
        try {
            while (!reached && !outputEos) {
                // feed: queue bhari rakho
                var fed = 0
                while (!inputEos && fed < 24) {
                    val inIdx = dec.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    val inBuf = dec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val size = ext.readSampleData(inBuf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                    fed++
                }
                queuedCount += fed

                // drain: beech ke frames drop, sirf latest hold
                var first = true
                while (true) {
                    val outTimeoutUs = when {
                        inputEos -> 2_000L
                        first -> 1_500L
                        else -> 0L
                    }
                    val outIdx = dec.dequeueOutputBuffer(info, outTimeoutUs)
                    first = false
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
                    if (outIdx < 0) {
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            tryAgainCount++
                            if (inputEos) {
                                eosTryAgain++
                                if (eosTryAgain >= 15) {
                                    outputEos = true
                                    if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                                }
                            }
                        }
                        break
                    }

                    eosTryAgain = 0
                    decodedCount++
                    val pts = info.presentationTimeUs
                    markDecodeProgress(pts)
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEos) {
                        outputEos = true
                        if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
                        if (pts > lastVideoPtsUs) lastVideoPtsUs = pts
                    }
                    if (isEos && info.size == 0) {
                        dec.releaseOutputBuffer(outIdx, false)
                        droppedCount++
                        reached = true
                        break
                    }
                    if (pendingIdx >= 0) {
                        dec.releaseOutputBuffer(pendingIdx, false)
                        droppedCount++
                    }
                    pendingIdx = outIdx
                    pendingPts = pts
                    if (isEos || pendingPts >= localUs - 15_000L) {
                        reached = true
                        break
                    }
                }
                if (!reached && System.nanoTime() >= deadlineNs) break
            }

            if (pendingIdx >= 0) {
                val atEnd = outputEos || (clipDurationUs > 0L && pendingPts >= clipDurationUs - 85_000L)
                val isSilentCatchUpFrame = !isScrubbing && isCatchingUpFromSeek && !reached && !atEnd &&
                    lastRenderedPtsUs != -1L && pendingPts < localUs - 150_000L
                if (isSilentCatchUpFrame) {
                    // Still catching up from an earlier sync frame to the current playhead target:
                    // advance decoder state silently instead of rendering intermediate frames,
                    // preventing visible 2-3s Fast Forward bursts after seek/trim.
                    dec.releaseOutputBuffer(pendingIdx, false)
                    droppedCount++
                    currentPtsUs = pendingPts
                    pendingIdx = -1
                    queuedCount += feedInput(ext, dec, 20)
                    return false
                }

                didReleaseOutputBuffer = true
                dec.releaseOutputBuffer(pendingIdx, true)   // har tick par max 1 frame render
                currentPtsUs = pendingPts
                lastRenderedPtsUs = pendingPts
                lastRenderedLocalUs = localUs
                if (reached || atEnd) isAccuratePausedFrameLatched = !isScrubbing
                if (reached || currentPtsUs >= localUs - 50_000L || atEnd) {
                    isCatchingUpFromSeek = false
                }
                if (atEnd) prewarmStage = 3
                queuedCount += feedInput(ext, dec, 20)   // keep hardware pipeline fed without premature EOS over-queuing
                notifyFirstFrame()
                pendingIdx = -1
                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && mode == PlayMode.PLAYING) {
                    PacePerfTracker.onDecoderFrameAtResult(
                        rendered = true,
                        dropped = droppedCount,
                        queued = queuedCount,
                        tryAgain = tryAgainCount,
                        decodedThisCall = decodedCount,
                        mode = mode
                    )
                }
                return true
            }
            if (pendingIdx < 0) {
                queuedCount += feedInput(ext, dec, 20)
            }
            if (decodedCount == 0L && !outputEos) {
                consecutiveZeroOutputCalls++
                if (consecutiveZeroOutputCalls >= 8) {
                    consecutiveZeroOutputCalls = 0
                    PreviewEventLog.log(
                        PreviewEventType.SEEK_RESULT,
                        slot = slot,
                        clipId = clip.id,
                        arg0 = localUs,
                        arg1 = currentPtsUs,
                        reason = "decodeForwardFast_stall_recover"
                    )
                    recoverCodec(localUs)
                    return false
                }
            }
            if (outputEos) {
                isCatchingUpFromSeek = false
                isAccuratePausedFrameLatched = !isScrubbing
                prewarmStage = 3
                lastRenderedLocalUs = localUs
            }
            return false
        } finally {
            if (pendingIdx >= 0) {
                try {
                    dec.releaseOutputBuffer(pendingIdx, false)
                    droppedCount++
                } catch (_: Exception) {}
            }
            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && mode == PlayMode.PLAYING && !didReleaseOutputBuffer) {
                PacePerfTracker.onDecoderFrameAtResult(
                    rendered = false,
                    dropped = droppedCount,
                    queued = queuedCount,
                    tryAgain = tryAgainCount,
                    decodedThisCall = decodedCount,
                    mode = mode
                )
            }
        }
    }

    fun frameAt(
        localUs: Long,
        isScrubbing: Boolean = false,
        isPaused: Boolean = false,
        mode: PlayMode = if (isPaused) PlayMode.PAUSED else if (isScrubbing) PlayMode.SCRUBBING else PlayMode.PLAYING
    ): Boolean {
        didReleaseOutputBuffer = false
        checkCatchUpTimeout()
        if (isScrubbing || (lastRenderedLocalUs != -1L && kotlin.math.abs(localUs - lastRenderedLocalUs) > 15_000L)) {
            isAccuratePausedFrameLatched = false
        }
        val ext = extractor ?: return false
        val dec = codec ?: return false
        val clipDurationUs = effectiveMediaDurationUs
        val isNearClipEnd = clipDurationUs > 0L && localUs >= clipDurationUs - 85_000L

        try {
            // Hot path for PLAYING mode: preserve 100% existing logic and PacePerf numbers
            if (mode == PlayMode.PLAYING) {
                lastScrubTargetUs = -1L
                lastScrubDirection = 0

                val inTargetWindow = shouldReusePreviousFrame(lastRenderedPtsUs, localUs, frameDurationUs, mode)
                val atClipEnd = isNearClipEnd && lastRenderedPtsUs != -1L &&
                    (outputEos || lastRenderedPtsUs >= clipDurationUs - 85_000L) &&
                    localUs >= clipDurationUs - 500_000L
                if (!isScrubbing && !isCatchingUpFromSeek && (inTargetWindow || atClipEnd)) {
                    prewarmStage = 3
                    lastRenderedLocalUs = localUs
                    PreviewEventLog.log(PreviewEventType.SEEK_RESULT, slot = slot, clipId = clip.id, arg0 = localUs, arg1 = lastRenderedPtsUs, reason = "frameAt_coalesced_skip")
                    if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                        PacePerfTracker.onDecoderFrameAtResult(
                            rendered = false,
                            dropped = 0L,
                            queued = 0L,
                            tryAgain = 0L,
                            decodedThisCall = 0L,
                            mode = mode
                        )
                    }
                    return true
                }

                if (!outputEos && isCatchingUpFromSeek && currentPtsUs != -1L && localUs >= currentPtsUs) {
                    val keyframes = getKeyframes()
                    val syncFramePtsUs = if (keyframes != null && keyframes.isNotEmpty()) {
                        com.flowcut.app.storage.KeyframeIndex.findClosestKeyframeAtOrBefore(keyframes, localUs / 1000L) * 1000L
                    } else null
                    val stillInSameGop = if (syncFramePtsUs != null) {
                        syncFramePtsUs <= currentPtsUs + 50_000L
                    } else {
                        localUs - currentPtsUs <= 6_000_000L
                    }
                    if (stillInSameGop || localUs <= lastSeekTargetUs + 400_000L) {
                        return decodeForwardFast(localUs, ext, dec, clipDurationUs, isScrubbing = false, mode = mode)
                    }
                }

                val isInitial = lastRenderedPtsUs == -1L
                val isBackwardJump = currentPtsUs != -1L && localUs < currentPtsUs - 25_000L
                val forwardJumpThresholdUs = maxOf(400_000L, (350_000L * effSpeed).toLong())
                val isForwardJump = currentPtsUs != -1L && (localUs > currentPtsUs + forwardJumpThresholdUs)
                val lastKnownUs = if (lastRenderedPtsUs != -1L) lastRenderedPtsUs else currentPtsUs
                val eosDrained = !atClipEnd && lastKnownUs != -1L && (
                    (outputEos && (localUs < lastKnownUs - 100_000L || localUs > lastKnownUs + 250_000L)) ||
                    (inputEos && localUs < lastKnownUs - 100_000L)
                )
                val needsSeek = isInitial || isBackwardJump || isForwardJump || eosDrained

                if (needsSeek) {
                    val reasonStr = when {
                        isInitial -> "isInitial"
                        isBackwardJump -> "isBackwardJump"
                        isForwardJump -> "isForwardJump"
                        eosDrained -> "eosDrained"
                        else -> "other"
                    }
                    PreviewEventLog.log(PreviewEventType.SEEK_ENTER, slot = slot, clipId = clip.id, arg0 = localUs, arg1 = currentPtsUs, reason = "frameAt_needsSeek_$reasonStr")
                    stopFast()
                    lastForwardKeyframeHopUs = -1L
                    val didRender = seekTo(localUs, fastKeyframeOnly = false)
                    if (didRender) {
                        lastRenderedLocalUs = localUs
                        isAccuratePausedFrameLatched = true
                    }
                    if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                        PacePerfTracker.onDecoderFrameAtResult(
                            rendered = didRender,
                            dropped = 0L,
                            queued = 0L,
                            tryAgain = 0L,
                            decodedThisCall = if (didRender) 1L else 0L,
                            mode = mode
                        )
                    }
                    return didRender
                }

                return decodeForwardFast(localUs, ext, dec, clipDurationUs, isScrubbing = false, mode = mode)
            }

            // PAUSED and SCRUBBING modes: evaluate through pure SeekDecisionEngine
            val isForwardScrub = if (isScrubbing) {
                if (lastScrubTargetUs != -1L) {
                    when {
                        localUs > lastScrubTargetUs + 1_000L -> lastScrubDirection = 1
                        localUs < lastScrubTargetUs - 1_000L -> lastScrubDirection = -1
                    }
                } else if (currentPtsUs != -1L && localUs > currentPtsUs + frameDurationUs) {
                    lastScrubDirection = 1
                } else {
                    lastScrubDirection = 0
                }
                lastScrubTargetUs = localUs
                lastScrubDirection > 0
            } else {
                lastScrubTargetUs = -1L
                lastScrubDirection = 0
                false
            }

            val keyframes = getKeyframes()
            val syncFramePtsUs = if (keyframes != null && keyframes.isNotEmpty()) {
                val targetMs = localUs / 1000L
                com.flowcut.app.storage.KeyframeIndex.findClosestKeyframeAtOrBefore(keyframes, targetMs) * 1000L
            } else null

            if (lastSeekTargetUs != localUs) {
                seekConsecutiveNoProgress = 0
                lastSeekTargetUs = localUs
                lastSeekAttemptPtsUs = currentPtsUs
                didAttemptNoProgressRecovery = false
            } else if (currentPtsUs != -1L && currentPtsUs == lastSeekAttemptPtsUs) {
                seekConsecutiveNoProgress++
            } else {
                seekConsecutiveNoProgress = 0
                lastSeekAttemptPtsUs = currentPtsUs
            }

            val decoderAtEosForTarget = outputEos ||
                (inputEos && (currentPtsUs == -1L || localUs < currentPtsUs - frameDurationUs))

            val decision = SeekDecisionEngine.evaluate(
                targetUs = localUs,
                currentPtsUs = currentPtsUs,
                lastRenderedPtsUs = lastRenderedPtsUs,
                state = seekState.copy(consecutiveNoProgressAttempts = seekConsecutiveNoProgress),
                frameDurationUs = frameDurationUs,
                isScrubbing = isScrubbing,
                isPaused = isPaused,
                isCatchingUpFromSeek = isCatchingUpFromSeek,
                syncFramePtsUs = syncFramePtsUs,
                isEos = decoderAtEosForTarget,
                isForwardScrub = isForwardScrub
            )

            when (decision.action) {
                SeekAction.ACCEPT_CURRENT -> {
                    if (decision.reason == "no_progress_guard_tripped") {
                        val farFromTarget = lastRenderedPtsUs != -1L && abs(localUs - lastRenderedPtsUs) > 200_000L
                        if (lastRenderedPtsUs == -1L || (!outputEos && !didAttemptNoProgressRecovery && farFromTarget)) {
                            // Recover a silently wedged decoder instead of permanently freezing on a stale frame.
                            didAttemptNoProgressRecovery = true
                            seekConsecutiveNoProgress = 0
                            lastSeekAttemptPtsUs = -1L
                            recoverCodec(localUs)
                            return false
                        }
                        if (acceptedTargetUs != localUs) {
                            acceptedTargetUs = localUs
                            android.util.Log.w("PreviewErr", "seek accepted last available frame")
                            seekState = seekState.copy(
                                lastTargetUs = localUs,
                                lastFast = isScrubbing,
                                outcome = SeekOutcome.BEST_EFFORT,
                                lastRenderedPtsUs = lastRenderedPtsUs
                            )
                            isCatchingUpFromSeek = false
                            isAccuratePausedFrameLatched = !isScrubbing
                            isWaitingForFrame = false
                            onSeekAcceptedLastFrame?.invoke()
                        }
                    } else if (isPaused && !isScrubbing) {
                        isAccuratePausedFrameLatched = true
                    }
                    prewarmStage = 3
                    lastRenderedLocalUs = localUs
                    PreviewEventLog.log(
                        PreviewEventType.SEEK_RESULT,
                        slot = slot,
                        clipId = clip.id,
                        arg0 = localUs,
                        arg1 = lastRenderedPtsUs,
                        reason = "accept_${decision.reason}"
                    )
                    return true
                }
                SeekAction.RENDER_NEAREST_SYNC -> {
                    PreviewEventLog.log(
                        PreviewEventType.SEEK_ENTER,
                        slot = slot,
                        clipId = clip.id,
                        arg0 = localUs,
                        arg1 = currentPtsUs,
                        reason = "render_sync"
                    )
                    stopFast()
                    val didRender = seekTo(localUs, fastKeyframeOnly = true)
                    if (didRender) {
                        lastRenderedLocalUs = localUs
                    }
                    return didRender
                }
                SeekAction.RESUME_CATCHUP -> {
                    PreviewEventLog.log(
                        PreviewEventType.SEEK_ENTER,
                        slot = slot,
                        clipId = clip.id,
                        arg0 = localUs,
                        arg1 = currentPtsUs,
                        reason = "resume_catchup_${decision.reason}"
                    )
                    isCatchingUpFromSeek = true
                    lastSeekTargetUs = localUs
                    return if (isScrubbing) {
                        decodeForwardScrubSlice(localUs, ext, dec, clipDurationUs)
                    } else {
                        decodeCatchUpSlice(localUs, ext, dec, clipDurationUs)
                    }
                }
                SeekAction.FLUSH_AND_SEEK -> {
                    PreviewEventLog.log(
                        PreviewEventType.SEEK_ENTER,
                        slot = slot,
                        clipId = clip.id,
                        arg0 = localUs,
                        arg1 = currentPtsUs,
                        reason = "flush_seek_${decision.reason}"
                    )
                    stopFast()
                    lastForwardKeyframeHopUs = -1L
                    seekConsecutiveNoProgress = 0
                    lastSeekAttemptPtsUs = -1L
                    val didRender = seekTo(localUs, fastKeyframeOnly = false)
                    if (didRender) {
                        lastRenderedLocalUs = localUs
                    }
                    return didRender
                }
            }
        } catch (e: Exception) {
            PreviewEventLog.logSwallowedException("FrameSource.frameAt", e)
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
                        if (lastRenderedPtsUs > lastVideoPtsUs) lastVideoPtsUs = lastRenderedPtsUs
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
        val t0 = SystemClock.elapsedRealtime()
        stopFast()
        lastHopKfUs = -1L
        val oldCodec = codec
        val oldExt = extractor
        val oldSurface = surface

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
        lastFastSeekKeyframeUs = -1L
        lastVideoPtsUs = -1L
        lastSeekTargetUs = -1L
        lastKeyframePtsUs = -1L
        lastRenderedLocalUs = -1L
        isAccuratePausedFrameLatched = false
        isCatchingUpFromSeek = false
        catchUpStartMs = 0L
        lastCatchUpProgressPtsUs = -1L
        lastCatchUpProgressMs = 0L
        consecutiveZeroOutputCalls = 0
        lastScrubTargetUs = -1L
        lastScrubDirection = 0
        didAttemptNoProgressRecovery = false
        lastForwardKeyframeHopUs = -1L
        prewarmStage = 0
        mediaDurationUs = -1L
        didReleaseOutputBuffer = false
        seekState = SeekState()
        seekConsecutiveNoProgress = 0
        lastSeekAttemptPtsUs = -1L

        val task = Runnable {
            try {
                oldCodec?.stop()
            } catch (e: Exception) {
                PreviewEventLog.logSwallowedException("FrameSource.release_stop", e)
            }
            try {
                if (oldCodec != null) {
                    oldCodec.release()
                    ResourceTracker.onReleased(ResourceTracker.ResourceType.MEDIA_CODEC, clip.id)
                }
            } catch (e: Exception) {
                PreviewEventLog.logSwallowedException("FrameSource.release_codec", e)
            }
            try {
                if (oldSurface != null) {
                    oldSurface.release()
                    ResourceTracker.onReleased(ResourceTracker.ResourceType.SURFACE, clip.id)
                }
            } catch (e: Exception) {
                PreviewEventLog.logSwallowedException("FrameSource.release_surface", e)
            }
            try {
                if (oldExt != null) {
                    oldExt.release()
                    ResourceTracker.onReleased(ResourceTracker.ResourceType.MEDIA_EXTRACTOR, clip.id)
                }
            } catch (e: Exception) {
                PreviewEventLog.logSwallowedException("FrameSource.release_ext", e)
            }
            val durMs = SystemClock.elapsedRealtime() - t0
            PreviewEventLog.log(PreviewEventType.FRAMESOURCE_RELEASE, slot = slot, clipId = clip.id, arg0 = durMs, reason = "release_done(sync=$synchronous)")
        }

        if (synchronous) {
            task.run()
        } else {
            releaseExecutor.execute(task)
        }
    }
}
