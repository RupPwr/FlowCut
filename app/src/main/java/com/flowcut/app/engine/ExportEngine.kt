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
import android.provider.MediaStore
import com.flowcut.app.audio.ClipAudioContributor
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

class ExportEngine(private val context: Context) {

    private val isCancelled = AtomicBoolean(false)

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
        return export(snapshot, onProgress)
    }

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
        onProgress: (Float) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        isCancelled.set(false)

        if (snapshot.project.clips.isEmpty()) {
            error("No clips to export")
        }

        val totalDurationMs = snapshot.totalMs
        val totalFrames = ceil(totalDurationMs * EXPORT_FPS / 1000.0).toInt().coerceAtLeast(1)

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

        var muxerStarted = false
        var muxerReleased = false

        try {
            val mux = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            // Step 2: Configure H.264 video encoder (no B-frames)
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EXPORT_W, EXPORT_H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, EXPORT_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, EXPORT_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            }

            val vEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder = vEnc
            vEnc.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = vEnc.createInputSurface()
            vEnc.start()

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
            GLES30.glViewport(0, 0, EXPORT_W, EXPORT_H)

            // Step 5: Setup TransitionRenderer and SurfaceTextures
            val rnd = TransitionRenderer()
            renderer = rnd
            rnd.init()
            val otc = OverlayTextureCache()
            overlayTextureCache = otc

            val frameSyncObjectA = java.lang.Object()
            var frameAvailableA = false

            val frameSyncObjectB = java.lang.Object()
            var frameAvailableB = false

            val stA = SurfaceTexture(rnd.textureIdA()).apply {
                setDefaultBufferSize(EXPORT_W, EXPORT_H)
                setOnFrameAvailableListener {
                    synchronized(frameSyncObjectA) {
                        frameAvailableA = true
                        frameSyncObjectA.notifyAll()
                    }
                }
            }
            val stB = SurfaceTexture(rnd.textureIdB()).apply {
                setDefaultBufferSize(EXPORT_W, EXPORT_H)
                setOnFrameAvailableListener {
                    synchronized(frameSyncObjectB) {
                        frameAvailableB = true
                        frameSyncObjectB.notifyAll()
                    }
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
            val texelSize = floatArrayOf(1.0f / EXPORT_W.toFloat(), 1.0f / EXPORT_H.toFloat())

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
                val startAudioFrame = (i * 48000L) / EXPORT_FPS
                val endAudioFrame = ((i + 1) * 48000L) / EXPORT_FPS
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
                val res = resolveExportFrame(snapshot, i, EXPORT_FPS, activeSet)
                val clipA = snapshot.project.clips[res.clipAIndex]
                val legacyClipA = PreviewRenderer.toLegacyClip(clipA)

                val renderedA: Boolean
                if (currentAIndex != res.clipAIndex || frameSourceA == null) {
                    frameSourceA?.release()
                    frameSourceA = FrameSource(legacyClipA, stA).apply {
                        prepare()
                    }
                    currentAIndex = res.clipAIndex
                    renderedA = frameSourceA!!.seekTo(res.sourceUsA, fastKeyframeOnly = false)
                } else {
                    renderedA = frameSourceA!!.frameAtExport(res.sourceUsA)
                }

                if (renderedA) {
                    synchronized(frameSyncObjectA) {
                        var waits = 0
                        while (!frameAvailableA && waits < 10) {
                            waits++
                            try {
                                frameSyncObjectA.wait(25L)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                        frameAvailableA = false
                    }
                }

                try {
                    stA.updateTexImage()
                    stA.getTransformMatrix(rawMatA)
                } catch (_: Exception) {}

                val transformA = PreviewRenderer.getClipTransform(clipA.id)
                PreviewRenderer.computeFgNormMatrix(fgNormMatA, transformA, clipA.displayWidth, clipA.displayHeight)
                System.arraycopy(rawMatA, 0, matA, 0, 16)
                PreviewRenderer.applyTransformMatrix(matA, transformA, clipA.displayWidth, clipA.displayHeight)
                PreviewRenderer.calculateClipCrop(clipA.displayWidth, clipA.displayHeight, transformA, cropA)
                PreviewRenderer.computeBgNormMatrix(bgNormMat, transformA, clipA.displayWidth, clipA.displayHeight)
                Matrix.multiplyMM(bgMatA, 0, rawMatA, 0, bgNormMat, 0)
                val bgA = PreviewRenderer.getClipBackground(clipA.id)
                val bgModeA = PreviewRenderer.packBackground(bgA, bgParamsA)

                val isTransition = res.isTransition && res.clipBIndex >= 0
                var bgB = ClipBackground.DEFAULT
                var bgModeB = 0
                var opacityB = 1.0f

                if (isTransition) {
                    val clipB = snapshot.project.clips[res.clipBIndex]
                    val legacyClipB = PreviewRenderer.toLegacyClip(clipB)
                    val renderedB: Boolean
                    if (currentBIndex != res.clipBIndex || frameSourceB == null) {
                        frameSourceB?.release()
                        frameSourceB = FrameSource(legacyClipB, stB).apply {
                            prepare()
                        }
                        currentBIndex = res.clipBIndex
                        renderedB = frameSourceB!!.seekTo(res.sourceUsB, fastKeyframeOnly = false)
                    } else {
                        renderedB = frameSourceB!!.frameAtExport(res.sourceUsB)
                    }

                    if (renderedB) {
                        synchronized(frameSyncObjectB) {
                            var waits = 0
                            while (!frameAvailableB && waits < 10) {
                                waits++
                                try {
                                    frameSyncObjectB.wait(25L)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                            frameAvailableB = false
                        }
                    }

                    try {
                        stB.updateTexImage()
                        stB.getTransformMatrix(rawMatB)
                    } catch (_: Exception) {}

                    val transformB = PreviewRenderer.getClipTransform(clipB.id)
                    opacityB = transformB.opacity
                    PreviewRenderer.computeFgNormMatrix(fgNormMatB, transformB, clipB.displayWidth, clipB.displayHeight)
                    System.arraycopy(rawMatB, 0, matB, 0, 16)
                    PreviewRenderer.applyTransformMatrix(matB, transformB, clipB.displayWidth, clipB.displayHeight)
                    PreviewRenderer.calculateClipCrop(clipB.displayWidth, clipB.displayHeight, transformB, cropB)
                    PreviewRenderer.computeBgNormMatrix(bgNormMat, transformB, clipB.displayWidth, clipB.displayHeight)
                    Matrix.multiplyMM(bgMatB, 0, rawMatB, 0, bgNormMat, 0)
                    bgB = PreviewRenderer.getClipBackground(clipB.id)
                    bgModeB = PreviewRenderer.packBackground(bgB, bgParamsB)
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

                drawSpec.type = res.transitionType
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

                val needsScissor = if (isTransition) {
                    (bgA.type == BackgroundType.NONE && bgB.type == BackgroundType.NONE) &&
                        PreviewRenderer.computeScissorRect(EXPORT_W, EXPORT_H, transformA, clipA.displayWidth, clipA.displayHeight, scissorRect)
                } else {
                    (bgA.type == BackgroundType.NONE) &&
                        PreviewRenderer.computeScissorRect(EXPORT_W, EXPORT_H, transformA, clipA.displayWidth, clipA.displayHeight, scissorRect)
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

                // Overlay compositor hook: render active overlay layers for this timestamp
                val plan = PreviewRenderer.currentLayerPlan
                if (plan.segments.isNotEmpty()) {
                    val found = plan.resolveAt(res.tUs, activeLayerSet)
                    if (found && activeLayerSet.visualCount > 0) {
                        for (v in 0 until activeLayerSet.visualCount) {
                            val itemIndex = activeLayerSet.activeVisualIndices[v]
                            val item = plan.items.getOrNull(itemIndex) ?: continue
                            if (item.transform.opacity <= 0.001f) continue
                            val cached = otc.getOrBakeTexture(item) ?: continue
                            if (cached.texId > 0) {
                                rnd.drawOverlayQuad(
                                    texId = cached.texId,
                                    texWidth = cached.width,
                                    texHeight = cached.height,
                                    transform = item.transform,
                                    opacity = item.transform.opacity,
                                    displayWidth = EXPORT_W,
                                    displayHeight = EXPORT_H
                                )
                            }
                        }
                    }
                }

                // Non-zero monotonic EGL timestamp: offset by +1ms (1,000,000 ns) to avoid driver 0L pitfalls
                val presentationTimeNs = (i * 1_000_000_000L / EXPORT_FPS) + 1_000_000L
                EGLExt.eglPresentationTimeANDROID(display, surf, presentationTimeNs)
                EGL14.eglSwapBuffers(display, surf)

                drainVideo(false)
                onProgress((i + 1).toFloat() / totalFrames.toFloat())
            }

            // Step 7: Signal EOS on both video and audio encoders and drain
            val finalAudioPtsUs = (totalFrames * 48000L / EXPORT_FPS * 1_000_000L) / 48000L
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
                put(MediaStore.Video.Media.WIDTH, EXPORT_W)
                put(MediaStore.Video.Media.HEIGHT, EXPORT_H)
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
