package com.flowcut.app.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.flowcut.app.model.EXPORT_BITRATE
import com.flowcut.app.model.EXPORT_FPS
import com.flowcut.app.model.EXPORT_H
import com.flowcut.app.model.EXPORT_W
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.TransitionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class ExportEngine(private val context: Context) {

    private val isCancelled = AtomicBoolean(false)

    fun cancel() {
        isCancelled.set(true)
    }

    suspend fun export(
        timeline: ProgramTimeline,
        onProgress: (Float) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        isCancelled.set(false)

        if (timeline.n == 0) {
            error("No clips to export")
        }

        val totalFrames = ceil(timeline.total * EXPORT_FPS / 1000.0).toInt().coerceAtLeast(1)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "FlowCut_$timestamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FlowCut")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val contentResolver = context.contentResolver
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore entry")

        var pfd: ParcelFileDescriptor? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var eglDisplay: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        var renderer: TransitionRenderer? = null
        var surfaceTextureA: SurfaceTexture? = null
        var surfaceTextureB: SurfaceTexture? = null
        var frameSourceA: FrameSource? = null
        var frameSourceB: FrameSource? = null

        try {
            pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: error("Failed to open file descriptor")

            // 1. Setup encoder
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EXPORT_W, EXPORT_H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, EXPORT_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, EXPORT_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }

            try {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            } catch (_: Exception) {}

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = enc.createInputSurface()
            enc.start()

            // 2. Setup muxer
            val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            // 3. Setup EGL for rendering onto encoder's input surface
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
            val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            eglContext = ctx

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val surf = EGL14.eglCreateWindowSurface(display, config, inputSurface, surfaceAttribs, 0)
            eglSurface = surf

            EGL14.eglMakeCurrent(display, surf, surf, ctx)

            // 4. Init renderer and SurfaceTextures in EGL context
            val rnd = TransitionRenderer()
            renderer = rnd
            rnd.init()

            val stA = SurfaceTexture(rnd.textureIdA()).apply {
                setDefaultBufferSize(EXPORT_W, EXPORT_H)
            }
            val stB = SurfaceTexture(rnd.textureIdB()).apply {
                setDefaultBufferSize(EXPORT_W, EXPORT_H)
            }
            surfaceTextureA = stA
            surfaceTextureB = stB

            GLES30.glViewport(0, 0, EXPORT_W, EXPORT_H)

            var currentAIndex = -1
            var currentBIndex = -1

            val matA = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
            val matB = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

            val bufferInfo = MediaCodec.BufferInfo()
            var muxerTrackIndex = -1
            var isMuxerStarted = false

            fun drain(endOfStream: Boolean) {
                if (endOfStream) {
                    enc.signalEndOfInputStream()
                }
                while (true) {
                    val status = enc.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                    } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (isMuxerStarted) error("Format changed twice")
                        muxerTrackIndex = mux.addTrack(enc.outputFormat)
                        mux.start()
                        isMuxerStarted = true
                    } else if (status >= 0) {
                        val outputBuffer = enc.getOutputBuffer(status)
                        if (outputBuffer != null && isMuxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)) {
                            if (bufferInfo.size != 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                mux.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                            }
                        }
                        enc.releaseOutputBuffer(status, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            }

            // 5. Render frames loop
            for (i in 0 until totalFrames) {
                if (isCancelled.get()) {
                    error("Export cancelled by user")
                }

                val tMs = (i * 1000.0 / EXPORT_FPS).toLong()
                val spec = timeline.resolve(tMs)
                val clipA = timeline.project.clips[spec.aIndex]

                if (currentAIndex != spec.aIndex || frameSourceA == null) {
                    frameSourceA?.release()
                    frameSourceA = FrameSource(clipA, stA).apply {
                        prepare()
                        seekTo(spec.aLocalMs * 1000L)
                    }
                    currentAIndex = spec.aIndex
                } else {
                    frameSourceA?.frameAt(spec.aLocalMs * 1000L)
                }

                try {
                    stA.updateTexImage()
                    stA.getTransformMatrix(matA)
                } catch (_: Exception) {}

                val cropA = TransitionRenderer.calculateCrop(clipA)
                var cropB = floatArrayOf(1f, 1f)

                if (spec.isTransition && spec.bIndex != null) {
                    val clipB = timeline.project.clips[spec.bIndex]
                    if (currentBIndex != spec.bIndex || frameSourceB == null) {
                        frameSourceB?.release()
                        frameSourceB = FrameSource(clipB, stB).apply {
                            prepare()
                            seekTo(spec.bLocalMs!! * 1000L)
                        }
                        currentBIndex = spec.bIndex
                    } else {
                        frameSourceB?.frameAt(spec.bLocalMs!! * 1000L)
                    }

                    try {
                        stB.updateTexImage()
                        stB.getTransformMatrix(matB)
                    } catch (_: Exception) {}

                    cropB = TransitionRenderer.calculateCrop(clipB)
                } else {
                    if (frameSourceB != null) {
                        frameSourceB?.release()
                        frameSourceB = null
                        currentBIndex = -1
                    }
                }

                val easedP = ProgramTimeline.ease(spec.linearP)
                rnd.draw(
                    DrawSpec(
                        type = spec.type,
                        easedP = easedP,
                        matA = matA,
                        matB = matB,
                        cropA = cropA,
                        cropB = cropB
                    )
                )

                // Set presentation time in nanoseconds
                val presentationTimeNs = i * 1_000_000_000L / EXPORT_FPS
                EGLExt.eglPresentationTimeANDROID(display, surf, presentationTimeNs)
                EGL14.eglSwapBuffers(display, surf)

                drain(false)
                onProgress((i + 1).toFloat() / totalFrames.toFloat())
            }

            // 6. Signal EOS and drain remaining
            drain(true)

            // Complete export
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)

            return@withContext uri

        } catch (e: Exception) {
            // Clean up MediaStore entry on failure/cancel so no partial file remains
            contentResolver.delete(uri, null, null)
            throw e
        } finally {
            frameSourceA?.release()
            frameSourceB?.release()
            surfaceTextureA?.release()
            surfaceTextureB?.release()
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
                encoder?.stop()
            } catch (_: Exception) {}
            try {
                encoder?.release()
            } catch (_: Exception) {}

            try {
                muxer?.stop()
            } catch (_: Exception) {}
            try {
                muxer?.release()
            } catch (_: Exception) {}

            try {
                pfd?.close()
            } catch (_: Exception) {}
        }
    }
}
