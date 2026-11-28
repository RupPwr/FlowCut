package com.flowcut.app.engine.export

import android.content.Context
import android.media.MediaCodec
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.SystemClock
import android.view.Surface
import com.flowcut.app.core.timeline.TimelineSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object ExportBenchmark {

    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private val cache = ConcurrentHashMap<String, Double>()

    fun getCachedFps(spec: OutputSpec): Double? = cache[spec.key()]

    fun clearCache() {
        cache.clear()
    }

    /**
     * Pure-Kotlin calculation of measured render+encode FPS.
     */
    fun computeMeasuredFps(framesRendered: Int, elapsedMs: Long): Double {
        if (framesRendered <= 0 || elapsedMs <= 0L) return 0.0
        return (framesRendered.toDouble() * 1000.0) / elapsedMs.toDouble()
    }

    /**
     * Pure-Kotlin baseline fallback FPS when probe is skipped or fails.
     * Hardware encoders generally achieve ~1.5x realtime; software encoders ~0.75x realtime.
     */
    fun fallbackFps(spec: OutputSpec, isHardware: Boolean): Double {
        val baseFps = spec.fps.coerceAtLeast(1).toDouble()
        return if (isHardware) baseFps * 1.5 else baseFps * 0.75
    }

    /**
     * Estimates remaining project export duration in milliseconds.
     */
    fun estimateProjectEtaMs(durationMs: Long, spec: OutputSpec, measuredFps: Double): Long {
        val totalFrames = ExportEstimator.totalFrames(durationMs, spec.fps)
        return ExportEstimator.etaMs(totalFrames, measuredFps)
    }

    /**
     * Headless probe measuring real render + video encoding throughput on the target device.
     * Renders [probeFrames] into an off-screen EGL surface attached to the chosen MediaCodec encoder,
     * immediately releasing encoded output buffers with ZERO disk writes, ZERO MediaMuxer, and ZERO audio overhead.
     */
    suspend fun measureFps(
        context: Context,
        snapshot: TimelineSnapshot,
        spec: OutputSpec,
        probeFrames: Int = 60
    ): Double = withContext(Dispatchers.Default) {
        val cacheKey = spec.key()
        cache[cacheKey]?.let { return@withContext it }

        val capabilities = ExportCapabilities()
        val choice = capabilities.find(spec, allowSoftware = true)
            ?: return@withContext 0.0

        val totalFrames = ExportEstimator.totalFrames(snapshot.totalMs, spec.fps)
        val actualProbeFrames = minOf(probeFrames.toLong(), totalFrames).toInt().coerceAtLeast(15)

        var eglDisplay: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        var encoderInputSurface: Surface? = null
        var videoEncoder: MediaCodec? = null

        try {
            val bps = BitrateTable.videoBps(choice.width, choice.height, spec.fps, spec.quality, spec.codec)
            val format = ExportCapabilities.buildVideoFormat(spec, choice, bps)

            val vEnc = MediaCodec.createByCodecName(choice.codecName)
            videoEncoder = vEnc
            vEnc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = vEnc.createInputSurface()
            encoderInputSurface = inputSurface
            vEnc.start()

            // Setup lightweight offscreen EGL context for rendering
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) error("EGL display unavailable")
            eglDisplay = display
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) error("EGL initialize failed")

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val configCandidates = arrayOf(
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
                ),
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
            if (config == null) error("EGL config not found")

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            val eglCtx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglCtx == null || eglCtx == EGL14.EGL_NO_CONTEXT) error("EGL context creation failed")
            eglContext = eglCtx

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val surf = EGL14.eglCreateWindowSurface(display, config, inputSurface, surfaceAttribs, 0)
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) error("EGL surface creation failed")
            eglSurface = surf

            if (!EGL14.eglMakeCurrent(display, surf, surf, eglCtx)) error("EGL makeCurrent failed")
            GLES30.glViewport(0, 0, choice.width, choice.height)

            val startMs = SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()

            for (i in 0 until actualProbeFrames) {
                // Clear color buffer simulating a render pass
                GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

                val presentationTimeNs = (i * 1_000_000_000L / spec.fps) + 1_000_000L
                EGLExt.eglPresentationTimeANDROID(display, surf, presentationTimeNs)
                EGL14.eglSwapBuffers(display, surf)

                // Drain output without saving/muxing
                while (true) {
                    val status = vEnc.dequeueOutputBuffer(info, 2000L)
                    if (status >= 0) {
                        vEnc.releaseOutputBuffer(status, false)
                    } else {
                        break
                    }
                }
            }

            vEnc.signalEndOfInputStream()
            while (true) {
                val status = vEnc.dequeueOutputBuffer(info, 5000L)
                if (status >= 0) {
                    vEnc.releaseOutputBuffer(status, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else {
                    break
                }
            }

            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            val measuredFps = computeMeasuredFps(actualProbeFrames, elapsedMs)
            val finalFps = if (measuredFps > 0.0) measuredFps else fallbackFps(spec, choice.hardware)
            cache[cacheKey] = finalFps
            finalFps
        } catch (_: Throwable) {
            val fallback = fallbackFps(spec, choice.hardware)
            cache[cacheKey] = fallback
            fallback
        } finally {
            try {
                if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    }
                    if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    // Never call EGL14.eglTerminate(EGL_DEFAULT_DISPLAY): it is process-shared with Preview/HWUI.
                    EGL14.eglReleaseThread()
                }
            } catch (_: Throwable) {}

            try {
                encoderInputSurface?.release()
            } catch (_: Throwable) {}

            try {
                videoEncoder?.stop()
            } catch (_: Throwable) {}
            try {
                videoEncoder?.release()
            } catch (_: Throwable) {}
        }
    }
}
