package com.flowcut.app.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import com.flowcut.app.model.PREFETCH_MS
import com.flowcut.app.model.PREVIEW_H
import com.flowcut.app.model.PREVIEW_W
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.TransitionType
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class PreviewEngine(
    context: Context,
    private val onPlayheadUpdate: (Long) -> Unit,
    private val onPlaybackStateChange: (Boolean) -> Unit
) : GLSurfaceView.Renderer {

    val glSurfaceView: GLSurfaceView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(3)
        setRenderer(this@PreviewEngine)
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        holder.setFixedSize(PREVIEW_W, PREVIEW_H)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderer = TransitionRenderer()

    private var surfaceTextureA: SurfaceTexture? = null
    private var surfaceTextureB: SurfaceTexture? = null

    private var frameSourceA: FrameSource? = null
    private var frameSourceB: FrameSource? = null
    private var prefetchSource: FrameSource? = null

    private var currentClipIndexA: Int = -1
    private var currentClipIndexB: Int = -1
    private var prefetchClipIndex: Int = -1

    private val matA = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val matB = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

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

    private var lastFrameTimeNs: Long = 0L

    @Volatile
    private var pendingSeekMs: Long? = null

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

        // Invalidate current frame sources so they get recreated on new textures
        releaseDecoders()

        // If we have clips, decode current frame
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

        val now = System.nanoTime()

        // Handle seeking if requested
        val seekTarget = pendingSeekMs
        if (seekTarget != null) {
            pendingSeekMs = null
            playheadMs = seekTarget.coerceIn(0L, maxOf(0L, timeline.total))
            lastFrameTimeNs = now
            seekToInternal(playheadMs)
        } else if (isPlaying) {
            val deltaMs = if (lastFrameTimeNs > 0) ((now - lastFrameTimeNs) / 1_000_000L) else 0L
            playheadMs += deltaMs
            lastFrameTimeNs = now

            if (playheadMs >= timeline.total) {
                playheadMs = timeline.total
                isPlaying = false
                mainHandler.post {
                    onPlaybackStateChange(false)
                    glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                }
            }
        }

        mainHandler.post {
            onPlayheadUpdate(playheadMs)
        }

        val spec = timeline.resolve(playheadMs)
        val clipA = timeline.project.clips.getOrNull(spec.aIndex) ?: return
        val stA = surfaceTextureA ?: return
        val stB = surfaceTextureB ?: return

        // 1. Ensure FrameSource A is ready
        if (frameSourceA == null || currentClipIndexA != spec.aIndex) {
            // Check if prefetch matches
            if (prefetchSource != null && prefetchClipIndex == spec.aIndex) {
                frameSourceA?.release()
                frameSourceA = prefetchSource
                prefetchSource = null
                prefetchClipIndex = -1
            } else {
                frameSourceA?.release()
                frameSourceA = FrameSource(clipA, stA).apply {
                    prepare()
                    seekTo(spec.aLocalMs * 1000L)
                }
            }
            currentClipIndexA = spec.aIndex
        }

        // Render Frame A
        frameSourceA?.frameAt(spec.aLocalMs * 1000L)
        try {
            stA.updateTexImage()
            stA.getTransformMatrix(matA)
        } catch (_: Exception) {}

        val cropA = TransitionRenderer.calculateCrop(clipA)
        var cropB = floatArrayOf(1f, 1f)

        // 2. Handle Transition and FrameSource B
        if (spec.isTransition && spec.bIndex != null) {
            val clipB = timeline.project.clips.getOrNull(spec.bIndex)
            if (clipB != null) {
                if (frameSourceB == null || currentClipIndexB != spec.bIndex) {
                    frameSourceB?.release()
                    frameSourceB = FrameSource(clipB, stB).apply {
                        prepare()
                        seekTo(spec.bLocalMs!! * 1000L)
                    }
                    currentClipIndexB = spec.bIndex
                } else {
                    frameSourceB?.frameAt(spec.bLocalMs!! * 1000L)
                }

                try {
                    stB.updateTexImage()
                    stB.getTransformMatrix(matB)
                } catch (_: Exception) {}

                cropB = TransitionRenderer.calculateCrop(clipB)
            }
        } else {
            // No transition: release B if any
            if (frameSourceB != null) {
                frameSourceB?.release()
                frameSourceB = null
                currentClipIndexB = -1
            }
        }

        // 3. Prefetch next clip if approaching junction
        val nextClipIndex = spec.aIndex + 1
        if (nextClipIndex < timeline.n && spec.bIndex == null) {
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

        val easedP = ProgramTimeline.ease(spec.linearP)
        renderer.draw(
            DrawSpec(
                type = spec.type,
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
        lastFrameTimeNs = System.nanoTime()
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        onPlaybackStateChange(true)
    }

    fun pause() {
        if (!isPlaying) return
        isPlaying = false
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        onPlaybackStateChange(false)
    }

    fun seekTo(targetMs: Long) {
        val clamped = targetMs.coerceIn(0L, maxOf(0L, timeline.total))
        pendingSeekMs = clamped
        requestRender()
    }

    private fun seekToInternal(ms: Long) {
        val spec = timeline.resolve(ms)
        val clipA = timeline.project.clips.getOrNull(spec.aIndex) ?: return
        val stA = surfaceTextureA ?: return
        val stB = surfaceTextureB ?: return

        if (frameSourceA == null || currentClipIndexA != spec.aIndex) {
            frameSourceA?.release()
            frameSourceA = FrameSource(clipA, stA).apply {
                prepare()
            }
            currentClipIndexA = spec.aIndex
        }
        frameSourceA?.seekTo(spec.aLocalMs * 1000L)

        if (spec.isTransition && spec.bIndex != null) {
            val clipB = timeline.project.clips.getOrNull(spec.bIndex)
            if (clipB != null) {
                if (frameSourceB == null || currentClipIndexB != spec.bIndex) {
                    frameSourceB?.release()
                    frameSourceB = FrameSource(clipB, stB).apply {
                        prepare()
                    }
                    currentClipIndexB = spec.bIndex
                }
                frameSourceB?.seekTo(spec.bLocalMs!! * 1000L)
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

