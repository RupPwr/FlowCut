package com.flowcut.app.engine

import android.view.Choreographer
import androidx.compose.runtime.MutableLongState
import com.flowcut.app.core.playback.PlaybackController

/**
 * Single vsync driver for the entire application (Section 7 & Rule R4).
 * Drives timeline playhead, Compose draw invalidation via frameTick, and preview rendering.
 * Draws 0 CPU when idle (needsFrames() == false).
 */
class FrameDriver(
    private val controller: PlaybackController,
    private val frameTick: MutableLongState,
    private val onRequestRender: () -> Unit = {}
) : Choreographer.FrameCallback {

    private var running = false
    var keepAlive: () -> Boolean = { false }

    fun wake() {
        if (!running) {
            running = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        if (running) {
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        val isPerfPlaying = com.flowcut.app.BuildConfig.PERF_TELEMETRY && controller.playhead.mode == com.flowcut.app.core.playback.PlayMode.PLAYING
        if (isPerfPlaying) {
            PacePerfTracker.onChoreographerFrame(frameTimeNanos, controller.playhead.mode)
        }
        val startNs = if (isPerfPlaying) System.nanoTime() else 0L

        controller.tick(frameTimeNanos)
        frameTick.longValue = frameTimeNanos
        onRequestRender()

        if (isPerfPlaying) {
            val durNs = System.nanoTime() - startNs
            PacePerfTracker.onDoFrameDuration(durNs, controller.playhead.mode)
        }

        if (controller.needsFrames() || keepAlive()) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
        }
    }
}

