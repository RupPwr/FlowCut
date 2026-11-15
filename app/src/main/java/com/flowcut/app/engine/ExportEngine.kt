package com.flowcut.app.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.flowcut.app.BuildConfig
import com.flowcut.app.audio.ClipAudioContributor
import com.flowcut.app.engine.proxy.ProxyStore
import com.flowcut.app.engine.export.Aspect
import com.flowcut.app.engine.export.BitrateTable
import com.flowcut.app.engine.export.ExportCapabilities
import com.flowcut.app.engine.export.ExportEstimator
import com.flowcut.app.engine.export.ExportFrameTimeoutException
import com.flowcut.app.engine.export.FrameGate
import com.flowcut.app.engine.export.OutCodec
import com.flowcut.app.engine.export.OutputSpec
import com.flowcut.app.engine.export.Quality
import com.flowcut.app.core.model.map
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.model.EXPORT_BITRATE
import com.flowcut.app.model.EXPORT_FPS
import com.flowcut.app.model.EXPORT_H
import com.flowcut.app.model.EXPORT_W
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.TransitionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

private const val EGL_RECORDABLE_ANDROID = 0x3142

/**
 * Resolved frame information for export frame-by-frame loop.
 */
data class ExportFrameResolution(
    val frameIndex: Int,
    val tUs: Long,
    val clipAIndex: Int,
    val sourceUsA: Long,
    val isTransition: Boolean,
    val clipBIndex: Int,
    val sourceUsB: Long,
    val transitionType: TransitionType,
    val linearP: Float,
    val easedP: Float
)

data class ExportStats(
    val totalFrames: Int = 0,
    val timeoutsA: Int = 0,
    val timeoutsB: Int = 0,
    val duplicatesA: Int = 0,
    val duplicatesB: Int = 0,
    val elapsedMs: Long = 0L,
    val avgFps: Double = 0.0
)

class ExportEngine(private val context: Context) {

    private val isCancelled = AtomicBoolean(false)
    var lastStats: ExportStats? = null

    fun cancel() {
        isCancelled.set(true)
    }

    /**
     * Helper to resolve frame state during export using TimelineSnapshot and TimeMap (Rule R11).
     */
    fun resolveExportFrame(
        snapshot: TimelineSnapshot,
        frameIndex: Int,
        fps: Int = EXPORT_FPS,
        activeSet: ActiveSet = ActiveSet(snapshot.lanes.size)
    ): ExportFrameResolution = Companion.resolveExportFrame(snapshot, frameIndex, fps, activeSet)

    companion object {
        val DEFAULT_OUTPUT_SPEC = OutputSpec(Aspect.R9_16, 1080, 30, Quality.HIGH, OutCodec.AVC)

        fun resolveExportFrame(
            snapshot: TimelineSnapshot,
            frameIndex: Int,
            fps: Int = EXPORT_FPS,
            activeSet: ActiveSet = ActiveSet(snapshot.lanes.size)
        ): ExportFrameResolution {
            val tUs = (frameIndex * 1_000_000L) / fps
            snapshot.resolve(tUs, activeSet)

            val clipA = if (activeSet.aIndex in snapshot.project.clips.indices) {
                snapshot.project.clips[activeSet.aIndex]
            } else null
            val sourceUsA = clipA?.map()?.sourceUs(activeSet.aLocalUs) ?: 0L

            val isTransition = activeSet.bIndex >= 0 && activeSet.bIndex in snapshot.project.clips.indices
            val clipB = if (isTransition) snapshot.project.clips[activeSet.bIndex] else null
            val sourceUsB = clipB?.map()?.sourceUs(activeSet.bLocalUs) ?: 0L

            val modelTransType = when (activeSet.type) {
                com.flowcut.app.core.model.TransitionType.NONE -> TransitionType.NONE
                com.flowcut.app.core.model.TransitionType.SLIDE -> TransitionType.SLIDE
                com.flowcut.app.core.model.TransitionType.CIRCLE -> TransitionType.CIRCLE
                com.flowcut.app.core.model.TransitionType.ZOOM -> TransitionType.ZOOM
                com.flowcut.app.core.model.TransitionType.FADE -> TransitionType.FADE
            }
            val easedP = ProgramTimeline.ease(activeSet.linearP)

            return ExportFrameResolution(
                frameIndex = frameIndex,
                tUs = tUs,
                clipAIndex = activeSet.aIndex,
                sourceUsA = sourceUsA,
                isTransition = isTransition,
                clipBIndex = if (isTransition) activeSet.bIndex else -1,
                sourceUsB = sourceUsB,
                transitionType = modelTransType,
                linearP = activeSet.linearP,
                easedP = easedP
            )
        }
    }

    suspend fun export(
        timeline: ProgramTimeline,
        width: Int = EXPORT_W,
        height: Int = EXPORT_H,
        onProgress: (Float) -> Unit
    ): Uri {
        val coreClips = timeline.project.clips.map {
            com.flowcut.app.core.model.Clip(
                id = it.id,
                path = it.path,
                durationMs = it.durationMs,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                frameRate = it.frameRate
            )
        }
        val coreTransitions = timeline.project.transitions.map {
            com.flowcut.app.core.model.Transition(
                type = when (it.type) {
                    TransitionType.NONE -> com.flowcut.app.core.model.TransitionType.NONE
                    TransitionType.SLIDE -> com.flowcut.app.core.model.TransitionType.SLIDE
                    TransitionType.CIRCLE -> com.flowcut.app.core.model.TransitionType.CIRCLE
                    TransitionType.ZOOM -> com.flowcut.app.core.model.TransitionType.ZOOM
                    TransitionType.FADE -> com.flowcut.app.core.model.TransitionType.FADE
                },
                durationMs = it.durationMs
            )
        }
        val snapshot = TimelineSnapshot(1L, com.flowcut.app.core.model.Project(coreClips, coreTransitions))
        return export(snapshot, width, height, onProgress = onProgress)
    }

    suspend fun export(
        timeline: ProgramTimeline,
        onProgress: (Float) -> Unit
    ): Uri = export(timeline, EXPORT_W, EXPORT_H, onProgress)

    private data class PendingSample(
        val isVideo: Boolean,
        val data: ByteArray,
        val flags: Int,
        val presentationTimeUs: Long
    )

    /**
     * Snapshot-based frame-by-frame export loop with full video + AAC audio multiplexing.
     * Muxes into a local cache file, finalizes the MP4 cleanly, and then writes to MediaStore.
     */
    suspend fun export(
        snapshot: TimelineSnapshot,
        spec: OutputSpec = DEFAULT_OUTPUT_SPEC,
        onProgress: (Float) -> Unit
    ): Uri = export(
        snapshot = snapshot,
        spec = spec,
        explicitBitrateBps = null,
        onProgress = onProgress
    )

    suspend fun export(
        snapshot: TimelineSnapshot,
        width: Int = EXPORT_W,
        height: Int = EXPORT_H,
        fps: Int = EXPORT_FPS,
        bitrateBps: Int = EXPORT_BITRATE,
        onProgress: (Float) -> Unit
    ): Uri {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val wSafe = width.coerceAtLeast(1)
        val hSafe = height.coerceAtLeast(1)
        val g = gcd(wSafe, hSafe)
        val aspect = Aspect(wSafe / g, hSafe / g)
        val shortSide = minOf(wSafe, hSafe)
        val spec = OutputSpec(aspect, shortSide, fps, Quality.HIGH, OutCodec.AVC)
        return export(snapshot = snapshot, spec = spec, explicitBitrateBps = bitrateBps, onProgress = onProgress)
    }

    suspend fun export(
        snapshot: TimelineSnapshot,
        spec: OutputSpec,
        explicitBitrateBps: Int?,
        onProgress: (Float) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        isCancelled.set(false)

        if (snapshot.project.clips.isEmpty()) {
            error("No clips to export")
        }

        val capabilities = ExportCapabilities()
        val choice = capabilities.find(spec, allowSoftware = true)
            ?: throw UnsupportedOperationException("No supported encoder for ${spec.key()}")

        val width = choice.width
        val height = choice.height
        val fps = spec.fps
        val calculatedBps = explicitBitrateBps ?: BitrateTable.videoBps(width, height, fps, spec.quality, spec.codec)
        val bitrateBps = ExportCapabilities.clampBitrate(calculatedBps, choice.minBps, choice.maxBps)

        val totalDurationMs = snapshot.totalMs
        val totalFrames = ExportEstimator.totalFrames(totalDurationMs, fps).toInt().coerceAtLeast(1)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "FlowCut_$timestamp.mp4"

        // Step 1: Always mux into a local file first
        val tempFile = File(context.cacheDir, "flowcut_export_${System.currentTimeMillis()}_$timestamp.mp4")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        var muxer: MediaMuxer? = null
        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var eglDisplay: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        var renderer: TransitionRenderer? = null
        var overlayTextureCache: OverlayTextureCache? = null
        var surfaceTextureA: SurfaceTexture? = null
        var surfaceTextureB: SurfaceTexture? = null
        var frameSourceA: FrameSource? = null
        var frameSourceB: FrameSource? = null
        val snapshotHolder = SnapshotHolder(snapshot)
        val audioContributor = ClipAudioContributor(snapshotHolder)
        val activeLayerSet = ActiveLayerSet()

        val plan = PreviewRenderer.currentLayerPlan
        val audioOverlays = plan.items.filter { it.type == LayerType.AUDIO || (it.type == LayerType.VIDEO && it.volume > 0.001f) }
        if (audioOverlays.isNotEmpty()) {
            ClipAudioContributor.syncAudioOverlays(audioOverlays)
            for (item in audioOverlays) {
                if (item.mediaPath.isNotEmpty()) {
                    ClipAudioContributor.prewarmAudioPath(item.mediaPath)
                }
            }
        }

        var muxerStarted = false
        var muxerReleased = false

        val prevCanvasAspect = PreviewRenderer.canvasAspectRatio
        try {
            val mux = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            // Step 2: Configure video encoder using probed capability
            val videoFormat = ExportCapabilities.buildVideoFormat(spec, choice, bitrateBps)
            val vEnc = MediaCodec.createByCodecName(choice.codecName)
            videoEncoder = vEnc
            vEnc.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = vEnc.createInputSurface()
            vEnc.start()

            if (BuildConfig.DEBUG) {
                try {
                    val isHw = choice.hardware
                    val mime = if (spec.codec == OutCodec.AVC) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
                    Log.d(
                        "CODEC",
                        "Video encoder started: name=${choice.codecName}, isHardwareAccelerated=$isHw, mime=$mime, size=${width}x${height}, fps=$fps, bitrate=$bitrateBps"
                    )
                } catch (_: Throwable) {}
            }

            // Step 3: Configure AAC audio encoder (48kHz, stereo, 192kbps)
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder = aEnc
            aEnc.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aEnc.start()

            if (BuildConfig.DEBUG) {
                try {
                    val isHw = aEnc.codecInfo.isHardwareAccelerated
                    Log.d(
                        "CODEC",
                        "Audio encoder started: name=${aEnc.name}, isHardwareAccelerated=$isHw, sampleRate=48000, channels=2, bitrate=192000"
                    )
                } catch (_: Throwable) {}
            }

            // Step 4: Setup EGL for rendering onto encoder's input surface
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            eglDisplay = display
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: error("EGL config not found")

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            val eglCtx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            eglContext = eglCtx

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val surf = EGL14.eglCreateWindowSurface(display, config, inputSurface, surfaceAttribs, 0)
            eglSurface = surf

            EGL14.eglMakeCurrent(display, surf, surf, eglCtx)
            val exportAspect = (width.toFloat() / height.toFloat()).coerceAtLeast(0.01f)
            PreviewRenderer.setCanvasAspectRatio(exportAspect)
            TransitionRenderer.canvasAspect = exportAspect
            GLES30.glViewport(0, 0, width, height)

            // Step 5: Setup TransitionRenderer and SurfaceTextures
            val rnd = TransitionRenderer()
            renderer = rnd
            rnd.init()
            val otc = OverlayTextureCache(proxyStore = OverlayTextureCache.defaultProxyStore ?: ProxyStore(context)).apply {
                bakeScale = (minOf(width, height).toFloat() / 1080f).coerceIn(1.0f, 2.0f)
            }
            overlayTextureCache = otc
            val animEvaluator = AnimatedTransformEvaluator()

            val gateA = FrameGate()
            val gateB = FrameGate()

            val stA = SurfaceTexture(rnd.textureIdA()).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener {
                    gateA.signal()
                }
            }
            val stB = SurfaceTexture(rnd.textureIdB()).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener {
                    gateB.signal()
                }
            }
            surfaceTextureA = stA
            surfaceTextureB = stB

            val matA = FloatArray(16)
            val matB = FloatArray(16)
            val rawMatA = FloatArray(16)
            val rawMatB = FloatArray(16)
            val cropA = FloatArray(2) { 1f }
            val cropB = FloatArray(2) { 1f }
            val scissorRect = IntArray(4)
            val activeSet = ActiveSet(snapshot.lanes.size)

            val adjA0 = FloatArray(4)
            val adjA1 = FloatArray(4)
            val adjA2 = FloatArray(4)
            val adjB0 = FloatArray(4)
            val adjB1 = FloatArray(4)
            val adjB2 = FloatArray(4)
            val texelSize = floatArrayOf(1.0f / width.toFloat(), 1.0f / height.toFloat())

            val fgNormMatA = FloatArray(16)
            val fgNormMatB = FloatArray(16)
            val bgNormMat = FloatArray(16)
            val bgMatA = FloatArray(16)
            val bgMatB = FloatArray(16)
            val bgParamsA = FloatArray(4)
            val bgParamsB = FloatArray(4)

            val drawSpec = DrawSpec()

            var currentAIndex = -1
            var currentBIndex = -1

            var timeoutsA = 0
            var timeoutsB = 0
            var duplicatesA = 0
            var duplicatesB = 0
            val exportStartTimeMs = SystemClock.uptimeMillis()

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            val pendingSamples = mutableListOf<PendingSample>()
            var lastVideoPtsUs = -1L
            var lastAudioPtsUs = -1L

            fun checkStartMuxer() {
                if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                    mux.start()
                    muxerStarted = true
                    val sampleBuf = ByteBuffer.allocateDirect(1024 * 1024)
                    val sampleInfo = MediaCodec.BufferInfo()
                    for (pending in pendingSamples) {
                        sampleBuf.clear()
                        sampleBuf.put(pending.data)
                        sampleBuf.flip()
                        sampleInfo.set(0, pending.data.size, pending.presentationTimeUs, pending.flags)
                        val trackIdx = if (pending.isVideo) videoTrackIndex else audioTrackIndex
                        mux.writeSampleData(trackIdx, sampleBuf, sampleInfo)
                    }
                    pendingSamples.clear()
                }
            }

            fun writeOutputSample(isVideo: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    return
                }
                if (info.size <= 0) {
                    return
                }

                if (isVideo) {
                    if (info.presentationTimeUs <= lastVideoPtsUs) {
                        info.presentationTimeUs = lastVideoPtsUs + 100L
                    }
                    if (info.presentationTimeUs < 0L) {
                        info.presentationTimeUs = 0L
                    }
                    lastVideoPtsUs = info.presentationTimeUs
                } else {
                    if (info.presentationTimeUs <= lastAudioPtsUs) {
                        info.presentationTimeUs = lastAudioPtsUs + 100L
                    }
                    if (info.presentationTimeUs < 0L) {
                        info.presentationTimeUs = 0L
                    }
                    lastAudioPtsUs = info.presentationTimeUs
                }

                if (muxerStarted) {
                    val trackIdx = if (isVideo) videoTrackIndex else audioTrackIndex
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    mux.writeSampleData(trackIdx, buffer, info)
                } else {
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data)
                    pendingSamples.add(
                        PendingSample(
                            isVideo = isVideo,
                            data = data,
                            flags = info.flags,
                            presentationTimeUs = info.presentationTimeUs
                        )
                    )
                }
            }

            fun drainVideo(endOfStream: Boolean) {
                val info = MediaCodec.BufferInfo()
                var triesWithoutOutput = 0
                while (true) {
                    val status = vEnc.dequeueOutputBuffer(info, 10_000L)
                    if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                        triesWithoutOutput++
                        if (triesWithoutOutput > 50) break
                    } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackIndex = mux.addTrack(vEnc.outputFormat)
                        checkStartMuxer()
                    } else if (status >= 0) {
                        triesWithoutOutput = 0
                        val outBuf = vEnc.getOutputBuffer(status)
                        if (outBuf != null) {
                            writeOutputSample(isVideo = true, buffer = outBuf, info = info)
                        }
                        vEnc.releaseOutputBuffer(status, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }

            fun drainAudio(endOfStream: Boolean) {
                val info = MediaCodec.BufferInfo()
                var triesWithoutOutput = 0
                while (true) {
                    val status = aEnc.dequeueOutputBuffer(info, 10_000L)
                    if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                        triesWithoutOutput++
                        if (triesWithoutOutput > 50) break
                    } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        audioTrackIndex = mux.addTrack(aEnc.outputFormat)
                        checkStartMuxer()
                    } else if (status >= 0) {
                        triesWithoutOutput = 0
                        val outBuf = aEnc.getOutputBuffer(status)
                        if (outBuf != null) {
                            writeOutputSample(isVideo = false, buffer = outBuf, info = info)
                        }
                        aEnc.releaseOutputBuffer(status, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }

            fun feedAudioInput(data: ByteArray, isEos: Boolean, ptsUs: Long) {
                var offset = 0
                var remaining = data.size
                while (remaining > 0 || isEos) {
                    val inIndex = aEnc.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inBuf = aEnc.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            inBuf.clear()
                            val toWrite = minOf(remaining, inBuf.capacity())
                            if (toWrite > 0) {
                                inBuf.put(data, offset, toWrite)
                                offset += toWrite
                                remaining -= toWrite
                            }
                            val flags = if (isEos && remaining == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            aEnc.queueInputBuffer(inIndex, 0, toWrite, ptsUs, flags)
                        }
                        if (remaining == 0) break
                    }
                }
            }

            // Step 6: Render frame-by-frame loop with lockstep audio blocks
            for (i in 0 until totalFrames) {
                if (isCancelled.get()) {
                    throw CancellationException("Export cancelled by user")
                }

                // 6a. Render corresponding synchronous audio block
                val startAudioFrame = (i * 48000L) / fps
                val endAudioFrame = ((i + 1) * 48000L) / fps
                val audioFramesCount = (endAudioFrame - startAudioFrame).toInt()
                val audioPtsUs = (startAudioFrame * 1_000_000L) / 48000L

                if (audioFramesCount > 0) {
                    val pcmFloats = FloatArray(audioFramesCount * 2)
                    audioContributor.renderSynchronous(startAudioFrame, audioFramesCount, pcmFloats)

                    val pcmBytes = ByteArray(audioFramesCount * 4)
                    var bIdx = 0
                    for (f in 0 until audioFramesCount * 2) {
                        val sampleInt = (pcmFloats[f].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt()
                        pcmBytes[bIdx++] = (sampleInt and 0xFF).toByte()
                        pcmBytes[bIdx++] = ((sampleInt shr 8) and 0xFF).toByte()
                    }

                    feedAudioInput(pcmBytes, isEos = false, ptsUs = audioPtsUs)
                    drainAudio(false)
                }

                // 6b. Render video frame
                val res = resolveExportFrame(snapshot, i, fps, activeSet)
                val clipA = snapshot.project.clips[res.clipAIndex]
                val legacyClipA = PreviewRenderer.toLegacyClip(clipA)

                gateA.arm()
                val renderedA: Boolean
                if (currentAIndex != res.clipAIndex || frameSourceA == null) {
                    frameSourceA?.release()
                    frameSourceA = FrameSource(legacyClipA, stA).apply {
                        prepare()
                    }
                    currentAIndex = res.clipAIndex
                    renderedA = frameSourceA!!.seekTo(res.sourceUsA, fastKeyframeOnly = false, isExport = true)
                } else {
                    renderedA = frameSourceA!!.frameAtExport(res.sourceUsA)
                }

                if (renderedA) {
                    var ok = gateA.await(1500L)
                    if (!ok) {
                        timeoutsA++
                        ok = gateA.await(1500L)
                    }
                    if (!ok) {
                        throw ExportFrameTimeoutException(i)
                    }
                    try {
                        stA.updateTexImage()
                        stA.getTransformMatrix(rawMatA)
                    } catch (e: Throwable) {
                        throw IllegalStateException("Failed to updateTexImage for Slot A at export frame $i: ${e.message}", e)
                    }
                } else {
                    if (frameSourceA?.currentPtsUs == -1L) {
                        throw IllegalStateException("FrameSource A has no latched frame for export at frame $i (targetUs=${res.sourceUsA})")
                    }
                    duplicatesA++
                }

                val aScreen = (width.toFloat() / height.toFloat()).coerceAtLeast(0.01f)
                val transformA = PreviewRenderer.getClipTransform(clipA.id)
                PreviewRenderer.computeFgNormMatrix(fgNormMatA, transformA, clipA.displayWidth, clipA.displayHeight, aScreen)
                System.arraycopy(rawMatA, 0, matA, 0, 16)
                PreviewRenderer.calculateClipCrop(clipA.displayWidth, clipA.displayHeight, transformA, cropA)
                PreviewRenderer.computeBgNormMatrix(bgNormMat, transformA, clipA.displayWidth, clipA.displayHeight, aScreen)
                Matrix.multiplyMM(bgMatA, 0, rawMatA, 0, bgNormMat, 0)
                val bgA = PreviewRenderer.getClipBackground(clipA.id)
                var bgModeA = PreviewRenderer.packBackground(bgA, bgParamsA)
                val videoAspectA = PreviewRenderer.computeVideoAspect(clipA.displayWidth, clipA.displayHeight, transformA)
                if (bgModeA == 0 && (kotlin.math.abs(videoAspectA - aScreen) > 0.01f || transformA.scale < 0.999f)) {
                    bgModeA = 2
                    bgParamsA[0] = 0f
                    bgParamsA[1] = 0f
                    bgParamsA[2] = 0f
                    bgParamsA[3] = if (bgA.blurLevel > 0.01f) bgA.blurLevel.coerceIn(0.01f, 1.0f) else 0.5f
                }
                if (bgModeA != 0 && PreviewRenderer.isForegroundCoveringCanvas(transformA, clipA.displayWidth, clipA.displayHeight, aScreen)) {
                    bgModeA = 0
                }

                val isTransition = res.isTransition && res.clipBIndex >= 0
                var bgB = ClipBackground.DEFAULT
                var bgModeB = 0
                var opacityB = 1.0f

                if (isTransition) {
                    val clipB = snapshot.project.clips[res.clipBIndex]
                    val legacyClipB = PreviewRenderer.toLegacyClip(clipB)
                    gateB.arm()
                    val renderedB: Boolean
                    if (currentBIndex != res.clipBIndex || frameSourceB == null) {
                        frameSourceB?.release()
                        frameSourceB = FrameSource(legacyClipB, stB).apply {
                            prepare()
                        }
                        currentBIndex = res.clipBIndex
                        renderedB = frameSourceB!!.seekTo(res.sourceUsB, fastKeyframeOnly = false, isExport = true)
                    } else {
                        renderedB = frameSourceB!!.frameAtExport(res.sourceUsB)
                    }

                    if (renderedB) {
                        var ok = gateB.await(1500L)
                        if (!ok) {
                            timeoutsB++
                            ok = gateB.await(1500L)
                        }
                        if (!ok) {
                            throw ExportFrameTimeoutException(i)
                        }
                        try {
                            stB.updateTexImage()
                            stB.getTransformMatrix(rawMatB)
                        } catch (e: Throwable) {
                            throw IllegalStateException("Failed to updateTexImage for Slot B at export frame $i: ${e.message}", e)
                        }
                    } else {
                        if (frameSourceB?.currentPtsUs == -1L) {
                            throw IllegalStateException("FrameSource B has no latched frame for export at frame $i (targetUs=${res.sourceUsB})")
                        }
                        duplicatesB++
                    }

                    val transformB = PreviewRenderer.getClipTransform(clipB.id)
                    opacityB = transformB.opacity
                    PreviewRenderer.computeFgNormMatrix(fgNormMatB, transformB, clipB.displayWidth, clipB.displayHeight, aScreen)
                    System.arraycopy(rawMatB, 0, matB, 0, 16)
                    PreviewRenderer.calculateClipCrop(clipB.displayWidth, clipB.displayHeight, transformB, cropB)
                    PreviewRenderer.computeBgNormMatrix(bgNormMat, transformB, clipB.displayWidth, clipB.displayHeight, aScreen)
                    Matrix.multiplyMM(bgMatB, 0, rawMatB, 0, bgNormMat, 0)
                    bgB = PreviewRenderer.getClipBackground(clipB.id)
                    bgModeB = PreviewRenderer.packBackground(bgB, bgParamsB)
                    val videoAspectB = PreviewRenderer.computeVideoAspect(clipB.displayWidth, clipB.displayHeight, transformB)
                    if (bgModeB == 0 && (kotlin.math.abs(videoAspectB - aScreen) > 0.01f || transformB.scale < 0.999f)) {
                        bgModeB = 2
                        bgParamsB[0] = 0f
                        bgParamsB[1] = 0f
                        bgParamsB[2] = 0f
                        bgParamsB[3] = if (bgB.blurLevel > 0.01f) bgB.blurLevel.coerceIn(0.01f, 1.0f) else 0.5f
                    }
                    if (bgModeB != 0 && PreviewRenderer.isForegroundCoveringCanvas(transformB, clipB.displayWidth, clipB.displayHeight, aScreen)) {
                        bgModeB = 0
                    }
                } else {
                    if (frameSourceB != null) {
                        frameSourceB?.release()
                        frameSourceB = null
                        currentBIndex = -1
                    }
                }

                GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

                val adjustA = PreviewRenderer.getClipColorAdjust(clipA.id)
                PreviewRenderer.packAdjust(adjustA, adjA0, adjA1, adjA2)

                if (isTransition) {
                    val clipB = snapshot.project.clips[res.clipBIndex]
                    val adjustB = PreviewRenderer.getClipColorAdjust(clipB.id)
                    PreviewRenderer.packAdjust(adjustB, adjB0, adjB1, adjB2)
                } else {
                    PreviewRenderer.packAdjust(ClipColorAdjust.DEFAULT, adjB0, adjB1, adjB2)
                }

                val jt = if (isTransition) PreviewRenderer.getJunctionTransitionType(res.clipAIndex) else 0
                val glTransType = if (jt > 0) jt else when (res.transitionType) {
                    TransitionType.SLIDE -> 4
                    TransitionType.CIRCLE -> 8
                    TransitionType.ZOOM -> 6
                    TransitionType.FADE -> 1
                    else -> 0
                }
                drawSpec.type = res.transitionType
                drawSpec.swapTextures = false
                drawSpec.transitionType = glTransType
                drawSpec.easedP = res.easedP
                drawSpec.matA = matA
                drawSpec.matB = matB
                drawSpec.cropA = cropA
                drawSpec.cropB = cropB
                drawSpec.opacityA = transformA.opacity
                drawSpec.opacityB = opacityB
                drawSpec.adjA0 = adjA0
                drawSpec.adjA1 = adjA1
                drawSpec.adjA2 = adjA2
                drawSpec.adjB0 = adjB0
                drawSpec.adjB1 = adjB1
                drawSpec.adjB2 = adjB2
                drawSpec.texelSize = texelSize
                drawSpec.fgNormMatA = fgNormMatA
                drawSpec.fgNormMatB = if (isTransition) fgNormMatB else null
                drawSpec.bgMatA = bgMatA
                drawSpec.bgMatB = if (isTransition) bgMatB else null
                drawSpec.bgModeA = bgModeA
                drawSpec.bgModeB = bgModeB
                drawSpec.bgParamsA = bgParamsA
                drawSpec.bgParamsB = if (isTransition) bgParamsB else null
                drawSpec.aspectRatio = (width.toFloat() / height.toFloat()).coerceAtLeast(0.01f)

                val timeSec = (res.tUs.toDouble() / 1_000_000.0).toFloat()
                val st = PreviewRenderer.getClipFilterState(clipA.id)
                var filterId = st.filterPresetId
                var filterInt = st.filterIntensity
                var effectId = st.effectPresetId
                var effectInt = st.effectIntensity

                val plan = PreviewRenderer.currentLayerPlan
                if (plan.segments.isNotEmpty()) {
                    val found = plan.resolveAt(res.tUs, activeLayerSet)
                    if (found && activeLayerSet.filterCount > 0) {
                        for (f in 0 until activeLayerSet.filterCount) {
                            val idx = activeLayerSet.activeFilterIndices[f]
                            val item = plan.items.getOrNull(idx) ?: continue
                            if (item.type == LayerType.FILTER && item.filterPresetId > 0) {
                                filterId = item.filterPresetId
                                filterInt = item.filterIntensity
                            }
                            if (item.type == LayerType.EFFECT && item.effectPresetId > 0) {
                                effectId = item.effectPresetId
                                effectInt = item.effectIntensity
                            }
                        }
                    }
                }

                drawSpec.filterPresetId = filterId
                drawSpec.filterIntensity = filterInt
                drawSpec.effectPresetId = effectId
                drawSpec.effectIntensity = effectInt
                drawSpec.timeSec = timeSec

                val needsScissor = if (isTransition) {
                    (bgA.type == BackgroundType.NONE && bgB.type == BackgroundType.NONE) &&
                        PreviewRenderer.computeScissorRect(width, height, transformA, clipA.displayWidth, clipA.displayHeight, scissorRect)
                } else {
                    (bgA.type == BackgroundType.NONE) &&
                        PreviewRenderer.computeScissorRect(width, height, transformA, clipA.displayWidth, clipA.displayHeight, scissorRect)
                }
                if (needsScissor) {
                    GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                    GLES30.glScissor(scissorRect[0], scissorRect[1], scissorRect[2], scissorRect[3])
                }
                try {
                    rnd.draw(drawSpec)
                } finally {
                    if (needsScissor) {
                        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                    }
                }
                Matrix.multiplyMM(matA, 0, rawMatA, 0, fgNormMatA, 0)

                // Overlay compositor hook: render active overlay layers for this timestamp
                if (plan.segments.isNotEmpty()) {
                    val found = plan.resolveAt(res.tUs, activeLayerSet)
                    if (found && activeLayerSet.visualCount > 0) {
                        for (v in 0 until activeLayerSet.visualCount) {
                            val itemIndex = activeLayerSet.activeVisualIndices[v]
                            val item = plan.items.getOrNull(itemIndex) ?: continue
                            val effTransform = animEvaluator.evaluate(item, res.tUs)
                            if (effTransform.opacity <= 0.001f) continue

                            if (item.type == LayerType.MOSAIC) {
                                rnd.drawMosaicRegion(
                                    videoTexId = rnd.textureIdA(),
                                    videoMat = matA,
                                    crop = cropA,
                                    transform = effTransform,
                                    mosaicStyle = item.mosaicStyle,
                                    mosaicIntensity = item.mosaicIntensity,
                                    mosaicFeather = item.mosaicFeather,
                                    opacity = effTransform.opacity,
                                    displayWidth = width,
                                    displayHeight = height
                                )
                                continue
                            }

                            val cached = if (item.type == LayerType.VIDEO) {
                                otc.getOrUpdateVideoTexture(item, res.tUs, isSynchronous = true)
                            } else {
                                otc.getOrBakeTexture(item)
                            } ?: continue
                            if (cached.texId > 0) {
                                rnd.drawOverlayQuad(
                                    texId = cached.texId,
                                    texWidth = cached.width,
                                    texHeight = cached.height,
                                    transform = effTransform,
                                    opacity = effTransform.opacity,
                                    displayWidth = width,
                                    displayHeight = height,
                                    cropLeft = item.cropLeft,
                                    cropTop = item.cropTop,
                                    cropRight = item.cropRight,
                                    cropBottom = item.cropBottom,
                                    maskType = item.maskType,
                                    maskFeather = item.maskFeather,
                                    maskInvert = item.maskInvert,
                                    chromaEnabled = item.chromaEnabled,
                                    chromaColorArgb = item.chromaColorArgb,
                                    chromaThreshold = item.chromaThreshold,
                                    chromaBlend = item.chromaBlend,
                                    adjustBrightness = item.adjustBrightness,
                                    adjustContrast = item.adjustContrast,
                                    adjustSaturation = item.adjustSaturation
                                )
                            }
                        }
                    }
                }

                // Non-zero monotonic EGL timestamp: offset by +1ms (1,000,000 ns) to avoid driver 0L pitfalls
                val presentationTimeNs = (i * 1_000_000_000L / fps) + 1_000_000L
                EGLExt.eglPresentationTimeANDROID(display, surf, presentationTimeNs)
                EGL14.eglSwapBuffers(display, surf)

                drainVideo(false)
                onProgress((i + 1).toFloat() / totalFrames.toFloat())
            }

            // Step 7: Signal EOS on both video and audio encoders and drain
            val finalAudioPtsUs = (totalFrames * 48000L / fps * 1_000_000L) / 48000L
            feedAudioInput(ByteArray(0), isEos = true, ptsUs = finalAudioPtsUs)
            drainAudio(true)

            vEnc.signalEndOfInputStream()
            drainVideo(true)

            // Step 8: Finalize MediaMuxer BEFORE copying to MediaStore
            if (muxerStarted) {
                mux.stop()
            }
            mux.release()
            muxerReleased = true

            val elapsedMs = SystemClock.uptimeMillis() - exportStartTimeMs
            val avgFps = if (elapsedMs > 0) (totalFrames.toDouble() * 1000.0) / elapsedMs else 0.0
            val stats = ExportStats(
                totalFrames = totalFrames,
                timeoutsA = timeoutsA,
                timeoutsB = timeoutsB,
                duplicatesA = duplicatesA,
                duplicatesB = duplicatesB,
                elapsedMs = elapsedMs,
                avgFps = avgFps
            )
            lastStats = stats

            if (BuildConfig.DEBUG) {
                try {
                    Log.d(
                        "EXPORTSTAT",
                        "Export completed: totalFrames=$totalFrames, timeoutsA=$timeoutsA, timeoutsB=$timeoutsB, duplicatesA=$duplicatesA, duplicatesB=$duplicatesB, elapsedMs=${elapsedMs}ms, avgFps=${String.format(Locale.US, "%.2f", avgFps)}"
                    )
                } catch (_: Throwable) {}
            }

            if (!tempFile.exists() || tempFile.length() < 1024L) {
                error("Export failed: output file is missing or corrupted (${tempFile.length()} bytes)")
            }

            // Step 9: Insert into MediaStore and copy finalized bytes
            val contentResolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FlowCut")
                put(MediaStore.Video.Media.IS_PENDING, 1)
                put(MediaStore.Video.Media.DURATION, totalDurationMs)
                put(MediaStore.Video.Media.WIDTH, width)
                put(MediaStore.Video.Media.HEIGHT, height)
            }

            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Failed to create MediaStore entry")

            try {
                contentResolver.openOutputStream(uri, "w")?.use { out ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                    out.flush()
                } ?: error("Failed to open output stream to MediaStore URI")

                // Step 10: Mark IS_PENDING = 0 with DURATION populated
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                values.put(MediaStore.Video.Media.DURATION, totalDurationMs)
                contentResolver.update(uri, values, null, null)

                // Step 11: Trigger system MediaScanner
                try {
                    val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    val targetFile = File(moviesDir, "FlowCut/$fileName")
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        arrayOf("video/mp4"),
                        null
                    )
                } catch (_: Exception) {}

                return@withContext uri

            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }

        } finally {
            try {
                PreviewRenderer.setCanvasAspectRatio(prevCanvasAspect)
                TransitionRenderer.canvasAspect = prevCanvasAspect
            } catch (_: Exception) {}
            audioContributor.release()
            frameSourceA?.release()
            frameSourceB?.release()
            surfaceTextureA?.release()
            surfaceTextureB?.release()
            overlayTextureCache?.release()
            renderer?.release()

            if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }

            try {
                videoEncoder?.stop()
            } catch (_: Exception) {}
            try {
                videoEncoder?.release()
            } catch (_: Exception) {}

            try {
                audioEncoder?.stop()
            } catch (_: Exception) {}
            try {
                audioEncoder?.release()
            } catch (_: Exception) {}

            if (!muxerReleased) {
                try {
                    if (muxerStarted) muxer?.stop()
                } catch (_: Exception) {}
                try {
                    muxer?.release()
                } catch (_: Exception) {}
            }

            try {
                tempFile.delete()
            } catch (_: Exception) {}
        }
    }
}
