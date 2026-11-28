package com.flowcut.app.engine.proxy

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.flowcut.app.engine.export.FrameGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * PipProxyGenerator handles background transcode of PIP videos into max-side-640,
 * all-intra, video-only MP4 proxies.
 *
 * Audio is explicitly excluded from the proxy (remains referenced from original file
 * by ClipAudioContributor). In-flight proxy generation jobs are deduplicated so identical
 * source videos never trigger concurrent duplicate transcodes.
 */
class PipProxyGenerator(
    private val context: Context,
    val proxyStore: ProxyStore = ProxyStore(context),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val proxyScope = CoroutineScope(scope.coroutineContext + job)
    private val transcodeSemaphore = Semaphore(1)
    private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

    /**
     * Checks if a ready proxy exists without triggering transcode.
     */
    fun getReadyProxyPath(sourcePath: String): String? = proxyStore.getReadyProxyPath(sourcePath)

    /**
     * Retrieves an existing proxy or enqueues a background transcode task.
     * Deduplicates in-flight jobs using source cache key and serializes hardware codec usage.
     */
    fun getOrGenerateProxy(sourcePath: String): Deferred<String?> {
        val ready = proxyStore.getReadyProxyPath(sourcePath)
        if (ready != null) {
            return CompletableDeferred(ready)
        }
        if (!job.isActive) {
            return CompletableDeferred(null)
        }

        val key = proxyStore.computeKeyForSource(sourcePath)
        return inFlight.computeIfAbsent(key) {
            proxyScope.async(Dispatchers.IO) {
                try {
                    transcodeSemaphore.withPermit {
                        val existing = proxyStore.getReadyProxyPath(sourcePath)
                        if (existing != null) return@withPermit existing

                        val tempFile = proxyStore.getTempProxyFile(key)
                        val checkCancelled = { !isActive || Thread.currentThread().isInterrupted }
                        val ok = transcodeWithFallback(sourcePath, tempFile, checkCancelled)
                        if (ok && !checkCancelled() && tempFile.exists() && tempFile.length() > 0L) {
                            val committed = proxyStore.commitProxy(key)
                            committed?.absolutePath
                        } else {
                            try { tempFile.delete() } catch (_: Throwable) {}
                            null
                        }
                    }
                } catch (_: Throwable) {
                    try { proxyStore.getTempProxyFile(key).delete() } catch (_: Throwable) {}
                    null
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }

    /**
     * Suspending shorthand for proxy generation.
     */
    suspend fun generateProxy(sourcePath: String): String? =
        getOrGenerateProxy(sourcePath).await()

    /**
     * Transcodes source video to tempFile with all-intra verification and fallback.
     */
    fun transcodeWithFallback(
        sourcePath: String,
        tempFile: File,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): Boolean {
        if (isCancelled()) return false
        // Attempt 1: All-intra via KEY_I_FRAME_INTERVAL = 0
        var success = transcodeInternal(sourcePath, tempFile, forcePerFrameSync = false, isCancelled = isCancelled)
        if (isCancelled()) {
            try { tempFile.delete() } catch (_: Throwable) {}
            return false
        }
        if (success && tempFile.exists() && tempFile.length() > 0L) {
            val allSync = verifyAllSamplesSync(tempFile)
            if (allSync) {
                return true
            }
            // If encoder ignored interval=0, delete temp and retry with per-frame sync request
            try { tempFile.delete() } catch (_: Throwable) {}
        }

        if (isCancelled()) return false
        // Attempt 2: Fallback with per-frame PARAMETER_KEY_REQUEST_SYNC_FRAME
        success = transcodeInternal(sourcePath, tempFile, forcePerFrameSync = true, isCancelled = isCancelled)
        if (isCancelled()) {
            try { tempFile.delete() } catch (_: Throwable) {}
            return false
        }
        return success && tempFile.exists() && tempFile.length() > 0L
    }

    /**
     * Verifies that all samples in the video track have SAMPLE_FLAG_SYNC set.
     */
    fun verifyAllSamplesSync(proxyFile: File): Boolean {
        if (!proxyFile.exists() || proxyFile.length() <= 0L) return false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(proxyFile.absolutePath)
            var videoTrackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIdx = i
                    break
                }
            }
            if (videoTrackIdx < 0) return false
            extractor.selectTrack(videoTrackIdx)

            var sampleCount = 0
            while (true) {
                val flags = extractor.sampleFlags
                if (flags < 0) break
                if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                    return false
                }
                sampleCount++
                if (!extractor.advance()) break
            }
            return sampleCount > 0
        } catch (_: Throwable) {
            return false
        } finally {
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun transcodeInternal(
        sourcePath: String,
        tempFile: File,
        forcePerFrameSync: Boolean,
        isCancelled: () -> Boolean
    ): Boolean {
        if (isCancelled() || sourcePath.isEmpty()) return false
        val srcFile = File(sourcePath)
        if (!srcFile.exists() || srcFile.length() <= 0L) return false

        var srcW = 0
        var srcH = 0
        var rotation = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(sourcePath)
            srcW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            srcH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            return false
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
        if (srcW <= 0 || srcH <= 0) return false

        val (targetW, targetH) = ProxyStore.computeProxyDimensions(srcW, srcH, rotation, maxSide = 640)

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var encoderInputSurface: Surface? = null
        var frameCallbackThread: HandlerThread? = null
        var muxer: MediaMuxer? = null
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var renderer: TextureRenderer? = null

        var eglDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext? = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        val texIds = IntArray(1)

        var muxerStarted = false
        var videoTrackIndexInMuxer = -1

        try {
            extractor.setDataSource(sourcePath)
            var videoTrackIdx = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIdx = i
                    videoFormat = format
                    break
                }
            }
            if (videoTrackIdx < 0 || videoFormat == null) return false
            extractor.selectTrack(videoTrackIdx)

            // 1. Encoder setup (video-only, AVC, max-side 640, 4 Mbps, 30 fps, I-frame interval 0)
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }
            val vEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = vEnc
            vEnc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = vEnc.createInputSurface()
            encoderInputSurface = inputSurface
            vEnc.start()

            // 2. EGL setup for encoder inputSurface
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            eglDisplay = display
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val configCandidates = arrayOf(
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
                ),
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
                )
            )
            var config: EGLConfig? = null
            for (attribs in configCandidates) {
                configs[0] = null
                numConfigs[0] = 0
                if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0 && configs[0] != null
                ) {
                    config = configs[0]
                    break
                }
            }
            if (config == null) return false

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) return false
            eglContext = ctx

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            val surf = EGL14.eglCreateWindowSurface(display, config, inputSurface, surfAttribs, 0)
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) return false
            eglSurface = surf

            if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) return false

            // 3. OES Texture & TextureRenderer setup
            val rnd = TextureRenderer()
            renderer = rnd
            rnd.init()

            GLES20.glGenTextures(1, texIds, 0)
            val oesTexId = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val frameGate = FrameGate()
            val cbThread = HandlerThread("FlowCut-PipProxyCb").apply { start() }
            frameCallbackThread = cbThread
            val cbHandler = Handler(cbThread.looper)
            val st = SurfaceTexture(oesTexId).apply {
                setDefaultBufferSize(targetW, targetH)
                setOnFrameAvailableListener({
                    frameGate.signal()
                }, cbHandler)
            }
            surfaceTexture = st
            val decSurf = Surface(st)
            decoderSurface = decSurf

            // 4. Decoder setup
            val decMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val vDec = MediaCodec.createDecoderByType(decMime)
            decoder = vDec
            vDec.configure(videoFormat, decSurf, null, 0)
            vDec.start()

            // 5. Muxer setup (video-only)
            if (tempFile.exists()) tempFile.delete()
            val mux = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            // 6. Transcoding loop
            var inputEos = false
            var decoderEos = false
            var encoderEos = false
            var encoderSignaledEos = false
            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            val stMatrix = FloatArray(16)
            val syncBundle = if (forcePerFrameSync) {
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            } else null
            var eosRetries = 0
            var lastEncodedPtsUs = -1L
            var lastMuxedPtsUs = -1L

            while (!encoderEos) {
                if (isCancelled()) return false
                // A. Feed Extractor -> Decoder
                if (!inputEos) {
                    val inIdx = vDec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = vDec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                vDec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                val timeUs = extractor.sampleTime.coerceAtLeast(0L)
                                val codecFlags = mapExtractorFlagsToCodecFlags(extractor.sampleFlags)
                                vDec.queueInputBuffer(inIdx, 0, sampleSize, timeUs, codecFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                // B. Drain Decoder -> Render to EGL input surface
                if (!decoderEos) {
                    val decStatus = vDec.dequeueOutputBuffer(decBufferInfo, 10_000L)
                    if (decStatus >= 0) {
                        val doRender = (decBufferInfo.size != 0)
                        if (doRender) {
                            frameGate.arm()
                            vDec.releaseOutputBuffer(decStatus, true)
                            if (frameGate.await(500L)) {
                                try {
                                    st.updateTexImage()
                                    st.getTransformMatrix(stMatrix)

                                    if (syncBundle != null) {
                                        try {
                                            vEnc.setParameters(syncBundle)
                                        } catch (_: Throwable) {}
                                    }

                                    GLES20.glViewport(0, 0, targetW, targetH)
                                    rnd.draw(stMatrix, oesTexId)
                                    val rawPtsUs = decBufferInfo.presentationTimeUs.coerceAtLeast(0L)
                                    val monoPtsUs = if (rawPtsUs <= lastEncodedPtsUs) lastEncodedPtsUs + 100L else rawPtsUs
                                    lastEncodedPtsUs = monoPtsUs
                                    EGLExt.eglPresentationTimeANDROID(display, surf, monoPtsUs * 1000L)
                                    EGL14.eglSwapBuffers(display, surf)
                                } catch (_: Throwable) {}
                            }
                        } else {
                            vDec.releaseOutputBuffer(decStatus, false)
                        }

                        if ((decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderEos = true
                            if (!encoderSignaledEos) {
                                vEnc.signalEndOfInputStream()
                                encoderSignaledEos = true
                            }
                        }
                    }
                }

                // C. Drain Encoder -> Muxer
                while (true) {
                    val encStatus = vEnc.dequeueOutputBuffer(encBufferInfo, 5_000L)
                    if (encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            val newFormat = vEnc.outputFormat
                            videoTrackIndexInMuxer = mux.addTrack(newFormat)
                            mux.start()
                            muxerStarted = true
                        }
                    } else if (encStatus >= 0) {
                        val outBuf = vEnc.getOutputBuffer(encStatus)
                        if (outBuf != null) {
                            if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encBufferInfo.size = 0
                            }
                            if (encBufferInfo.size > 0 && muxerStarted) {
                                if (encBufferInfo.presentationTimeUs <= lastMuxedPtsUs) {
                                    encBufferInfo.presentationTimeUs = lastMuxedPtsUs + 100L
                                }
                                if (encBufferInfo.presentationTimeUs < 0L) {
                                    encBufferInfo.presentationTimeUs = 0L
                                }
                                lastMuxedPtsUs = encBufferInfo.presentationTimeUs
                                outBuf.position(encBufferInfo.offset)
                                outBuf.limit(encBufferInfo.offset + encBufferInfo.size)
                                mux.writeSampleData(videoTrackIndexInMuxer, outBuf, encBufferInfo)
                            }
                        }
                        vEnc.releaseOutputBuffer(encStatus, false)
                        if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderEos = true
                            break
                        }
                    }
                }

                if (decoderEos && !encoderEos) {
                    eosRetries++
                    if (eosRetries > 200) {
                        break
                    }
                }
            }

            return true
        } catch (_: Throwable) {
            return false
        } finally {
            try { extractor.release() } catch (_: Throwable) {}
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { decoderSurface?.release() } catch (_: Throwable) {}
            try { surfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
            try { surfaceTexture?.release() } catch (_: Throwable) {}
            try { frameCallbackThread?.quitSafely() } catch (_: Throwable) {}
            try { renderer?.release() } catch (_: Throwable) {}
            if (texIds[0] != 0) {
                try { GLES20.glDeleteTextures(1, texIds, 0) } catch (_: Throwable) {}
            }
            if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                } catch (_: Throwable) {}
                if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                    try { EGL14.eglDestroySurface(eglDisplay, eglSurface) } catch (_: Throwable) {}
                }
                if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                    try { EGL14.eglDestroyContext(eglDisplay, eglContext) } catch (_: Throwable) {}
                }
                // Never call EGL14.eglTerminate(EGL_DEFAULT_DISPLAY): it is process-shared with Preview/HWUI.
                try { EGL14.eglReleaseThread() } catch (_: Throwable) {}
            }
            try { encoderInputSurface?.release() } catch (_: Throwable) {}
            try { encoder?.stop() } catch (_: Throwable) {}
            try { encoder?.release() } catch (_: Throwable) {}
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Throwable) {}
            }
            try { muxer?.release() } catch (_: Throwable) {}
        }
    }

    fun cancelAll() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    fun release() {
        cancelAll()
        try {
            job.cancel()
        } catch (_: Throwable) {}
    }

    private class TextureRenderer {
        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """.trimIndent()

        private var program = 0
        private var uSTMatrixHandle = 0
        private var aPositionHandle = 0
        private var aTextureHandle = 0

        private val triangleVerticesData = floatArrayOf(
            -1.0f, -1.0f, 0f, 0f, 0f,
             1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f,  1.0f, 0f, 0f, 1f,
             1.0f,  1.0f, 0f, 1f, 1f
        )
        private val triangleVertices = ByteBuffer.allocateDirect(triangleVerticesData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(triangleVerticesData)
                position(0)
            }

        fun init() {
            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vShader)
            GLES20.glAttachShader(program, fShader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vShader)
            GLES20.glDeleteShader(fShader)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                program = 0
                throw IllegalStateException("PipProxy shader link failed: $log")
            }
            aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        }

        fun draw(stMatrix: FloatArray, textureId: Int) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            triangleVertices.position(0)
            GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, triangleVertices)
            GLES20.glEnableVertexAttribArray(aPositionHandle)

            triangleVertices.position(3)
            GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, triangleVertices)
            GLES20.glEnableVertexAttribArray(aTextureHandle)

            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("PipProxy shader compile failed: $log")
            }
            return shader
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        internal fun mapExtractorFlagsToCodecFlags(extractorFlags: Int): Int {
            var codecFlags = 0
            if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }
            return codecFlags
        }
    }
}
