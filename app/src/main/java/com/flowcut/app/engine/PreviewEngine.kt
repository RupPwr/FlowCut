package com.flowcut.app.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import com.flowcut.app.model.FrameSpecMutable
import com.flowcut.app.model.PREFETCH_MS
import com.flowcut.app.model.PREVIEW_H
import com.flowcut.app.model.PREVIEW_W
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.TransitionType
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * PreviewEngine (Section 19.5, 19.6, 19.7):
 * Choreographer vsync playback loop, MasterClock, PlayheadState,
 * FrameSpecMutable zero-allocation resolve, decoder reuse, API 30 Surface.setFrameRate.
 */
class PreviewEngine(
    context: Context,
    private val onPlayheadUpdate: (Long) -> Unit,
    private val onPlaybackStateChange: (Boolean) -> Unit
) : GLSurfaceView.Renderer, Choreographer.FrameCallback {

    val glSurfaceView: GLSurfaceView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(3)
        setRenderer(this@PreviewEngine)
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        holder.setFixedSize(PREVIEW_W, PREVIEW_H)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderer = TransitionRenderer()
    val playheadState = PlayheadState()
    private val masterClock = MasterClock()

    private var surfaceTextureA: SurfaceTexture? = null
    private var surfaceTextureB: SurfaceTexture? = null

    private var frameSourceA: FrameSource? = null
    private var frameSourceB: FrameSource? = null
    private var prefetchSource: FrameSource? = null

    private var currentClipIndexA: Int = -1
    private var currentClipIndexB: Int = -1
    private var prefetchClipIndex: Int = -1

    // Pre-allocated arrays to avoid allocation on hot path (Section 19.10)
    private val matA = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val matB = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val specMutable = FrameSpecMutable()
    private val defaultCrop = floatArrayOf(1f, 1f)

    @Volatile
    var timeline: ProgramTimeline = ProgramTimeline(com.flowcut.app.model.Project())
        set(value) {
            field = value
            requestRender()
        }

    @Volatile
    var playheadMs: Long = 0L
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    private var pendingSeekMs: Long? = null

    @Volatile
    private var isPendingFastScrub: Boolean = false

    override fun doFrame(frameTimeNanos: Long) {
        if (!isPlaying) return

        val nowUs = masterClock.timeUsAt(frameTimeNanos)
        val newMs = (nowUs / 1000L).coerceIn(0L, maxOf(0L, timeline.total))
        playheadMs = newMs
        playheadState.updateTime(nowUs, Driver.PLAYBACK)

        if (newMs >= timeline.total) {
            pause()
        } else {
            glSurfaceView.requestRender()
            Choreographer.getInstance().postFrameCallback(this)
        }

        mainHandler.post {
            onPlayheadUpdate(playheadMs)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        renderer.init()

        surfaceTextureA?.release()
        surfaceTextureB?.release()

        val stA = SurfaceTexture(renderer.textureIdA()).apply {
            setDefaultBufferSize(PREVIEW_W, PREVIEW_H)
        }
        val stB = SurfaceTexture(renderer.textureIdB()).apply {
            setDefaultBufferSize(PREVIEW_W, PREVIEW_H)
        }

        surfaceTextureA = stA
        surfaceTextureB = stB

        releaseDecoders()

        if (timeline.n > 0) {
            decodeCurrentFrame()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0.08f, 0.08f, 0.07f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (timeline.n == 0) return

        val seekTarget = pendingSeekMs
        if (seekTarget != null) {
            val fast = isPendingFastScrub
            pendingSeekMs = null
            playheadMs = seekTarget.coerceIn(0L, maxOf(0L, timeline.total))
            seekToInternal(playheadMs, fastKeyframeOnly = fast)
            mainHandler.post {
                onPlayheadUpdate(playheadMs)
            }
        }

        // Zero-allocation resolve on hot path (Section 19.3 & 19.10)
        timeline.resolve(playheadMs, specMutable)
        val clipA = timeline.project.clips.getOrNull(specMutable.aIndex) ?: return
        val stA = surfaceTextureA ?: return
        val stB = surfaceTextureB ?: return

        // 1. FrameSource A
        if (frameSourceA == null || currentClipIndexA != specMutable.aIndex) {
            if (prefetchSource != null && prefetchClipIndex == specMutable.aIndex) {
                frameSourceA?.release()
                frameSourceA = prefetchSource
                prefetchSource = null
                prefetchClipIndex = -1
            } else if (frameSourceA != null && frameSourceA!!.canReuse(clipA)) {
                // Reuse existing decoder with flush
                frameSourceA!!.clip = clipA
                frameSourceA!!.seekTo(specMutable.aLocalMs * 1000L)
            } else {
                frameSourceA?.release()
                frameSourceA = FrameSource(clipA, stA).apply {
                    prepare()
                    seekTo(specMutable.aLocalMs * 1000L)
                }
            }
            currentClipIndexA = specMutable.aIndex
        }

        frameSourceA?.frameAt(specMutable.aLocalMs * 1000L)
        try {
            stA.updateTexImage()
            stA.getTransformMatrix(matA)
        } catch (_: Exception) {}

        val cropA = TransitionRenderer.calculateCrop(clipA)
        var cropB = defaultCrop

        // 2. Handle Transition and FrameSource B
        if (specMutable.isTransition && specMutable.bIndex >= 0) {
            val clipB = timeline.project.clips.getOrNull(specMutable.bIndex)
            if (clipB != null) {
                if (frameSourceB == null || currentClipIndexB != specMutable.bIndex) {
                    if (frameSourceB != null && frameSourceB!!.canReuse(clipB)) {
                        frameSourceB!!.clip = clipB
                        frameSourceB!!.seekTo(specMutable.bLocalMs * 1000L)
                    } else {
                        frameSourceB?.release()
                        frameSourceB = FrameSource(clipB, stB).apply {
                            prepare()
                            seekTo(specMutable.bLocalMs * 1000L)
                        }
                    }
                    currentClipIndexB = specMutable.bIndex
                } else {
                    frameSourceB?.frameAt(specMutable.bLocalMs * 1000L)
                }

                try {
                    stB.updateTexImage()
                    stB.getTransformMatrix(matB)
                } catch (_: Exception) {}

                cropB = TransitionRenderer.calculateCrop(clipB)
            }
        } else {
            if (frameSourceB != null) {
                frameSourceB?.release()
                frameSourceB = null
                currentClipIndexB = -1
            }
        }

        // 3. Prefetch next clip (Section 19.7)
        val nextClipIndex = specMutable.aIndex + 1
        if (nextClipIndex < timeline.n && specMutable.bIndex < 0) {
            val junctionStart = timeline.start[nextClipIndex]
            if (playheadMs >= junctionStart - PREFETCH_MS) {
                if (prefetchSource == null || prefetchClipIndex != nextClipIndex) {
                    val nextClip = timeline.project.clips[nextClipIndex]
                    prefetchSource?.release()
                    prefetchSource = FrameSource(nextClip, stB).apply {
                        prepare()
                        seekTo(0L)
                    }
                    prefetchClipIndex = nextClipIndex
                }
            }
        }

        val easedP = ProgramTimeline.ease(specMutable.linearP)
        renderer.draw(
            DrawSpec(
                type = specMutable.type,
                easedP = easedP,
                matA = matA,
                matB = matB,
                cropA = cropA,
                cropB = cropB
            )
        )
    }

    fun play() {
        if (timeline.n == 0) return
        if (playheadMs >= timeline.total) {
            playheadMs = 0L
        }
        isPlaying = true
        val startNanos = System.nanoTime()
        masterClock.start(playheadMs * 1000L, startNanos)
        playheadState.updateTime(playheadMs * 1000L, Driver.PLAYBACK)

        // API 30 Frame Rate hint (Section 19.6 & 19.9)
        try {
            val exportFps = if (timeline.project.clips.any { it.frameRate >= 50 }) 60f else 30f
            glSurfaceView.holder.surface?.setFrameRate(exportFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        } catch (_: Exception) {}

        mainHandler.post {
            onPlaybackStateChange(true)
            Choreographer.getInstance().postFrameCallback(this@PreviewEngine)
        }
    }

    fun pause() {
        if (!isPlaying) return
        isPlaying = false
        masterClock.stop(playheadMs * 1000L)
        playheadState.updateTime(playheadMs * 1000L, Driver.IDLE)

        try {
            glSurfaceView.holder.surface?.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        } catch (_: Exception) {}

        mainHandler.post {
            Choreographer.getInstance().removeFrameCallback(this@PreviewEngine)
            onPlaybackStateChange(false)
            glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }

    fun seekTo(targetMs: Long, fastScrub: Boolean = false) {
        val clamped = targetMs.coerceIn(0L, maxOf(0L, timeline.total))
        pendingSeekMs = clamped
        isPendingFastScrub = fastScrub
        playheadState.updateTime(clamped * 1000L, if (fastScrub) Driver.USER_SCRUB else Driver.PROGRAMMATIC_SEEK)
        requestRender()
    }

    private fun seekToInternal(ms: Long, fastKeyframeOnly: Boolean = false) {
        timeline.resolve(ms, specMutable)
        val clipA = timeline.project.clips.getOrNull(specMutable.aIndex) ?: return
        val stA = surfaceTextureA ?: return
        val stB = surfaceTextureB ?: return

        if (frameSourceA == null || currentClipIndexA != specMutable.aIndex) {
            frameSourceA?.release()
            frameSourceA = FrameSource(clipA, stA).apply {
                prepare()
            }
            currentClipIndexA = specMutable.aIndex
        }
        frameSourceA?.seekTo(specMutable.aLocalMs * 1000L, fastKeyframeOnly = fastKeyframeOnly)

        if (specMutable.isTransition && specMutable.bIndex >= 0) {
            val clipB = timeline.project.clips.getOrNull(specMutable.bIndex)
            if (clipB != null) {
                if (frameSourceB == null || currentClipIndexB != specMutable.bIndex) {
                    frameSourceB?.release()
                    frameSourceB = FrameSource(clipB, stB).apply {
                        prepare()
                    }
                    currentClipIndexB = specMutable.bIndex
                }
                frameSourceB?.seekTo(specMutable.bLocalMs * 1000L, fastKeyframeOnly = fastKeyframeOnly)
            }
        } else {
            frameSourceB?.release()
            frameSourceB = null
            currentClipIndexB = -1
        }
    }

    private fun decodeCurrentFrame() {
        glSurfaceView.queueEvent {
            seekToInternal(playheadMs)
            requestRender()
        }
    }

    fun requestRender() {
        glSurfaceView.requestRender()
    }

    fun onPause() {
        pause()
        releaseDecoders()
    }

    fun onResume() {
        decodeCurrentFrame()
    }

    private fun releaseDecoders() {
        frameSourceA?.release()
        frameSourceA = null
        currentClipIndexA = -1

        frameSourceB?.release()
        frameSourceB = null
        currentClipIndexB = -1

        prefetchSource?.release()
        prefetchSource = null
        prefetchClipIndex = -1
    }

    fun release() {
        pause()
        releaseDecoders()
        surfaceTextureA?.release()
        surfaceTextureA = null
        surfaceTextureB?.release()
        surfaceTextureB = null
        renderer.release()
    }
}
