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
        controller.tick(frameTimeNanos)
        frameTick.longValue = frameTimeNanos
        onRequestRender()

        if (controller.needsFrames()) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
        }
    }
}

