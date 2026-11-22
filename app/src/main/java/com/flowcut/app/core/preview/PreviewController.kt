package com.flowcut.app.core.preview

import com.flowcut.app.core.playback.PlaybackController
import com.flowcut.app.core.playback.PlayheadReader

/**
 * PreviewController: Centralized contract for the single preview and playback owner (ARCHITECTURE_RULES.md Section 3.1 & Section 9).
 * Ensures a single owner controls play, pause, seek, and playback buffering state.
 */
interface PreviewController {
    val playhead: PlayheadReader
    val state: PreviewState

    fun play(currentTimeMs: Long = System.currentTimeMillis())
    fun pause(currentTimeMs: Long = System.currentTimeMillis())
    fun seekTo(timeUs: Long, currentTimeMs: Long = System.currentTimeMillis())
    fun tick(frameTimeNanos: Long, currentTimeMs: Long = System.currentTimeMillis())
}

/**
 * Default implementation coordinating PlaybackController and PreviewStateMachine.
 */
class DefaultPreviewController(
    private val playbackController: PlaybackController,
    private val stateMachine: PreviewStateMachine = PreviewStateMachine()
) : PreviewController {

    override val playhead: PlayheadReader get() = playbackController.playhead
    override val state: PreviewState get() = stateMachine.currentState

    override fun play(currentTimeMs: Long) {
        playbackController.play()
        stateMachine.transition(PreviewStatus.PLAYING, currentTimeMs)
    }

    override fun pause(currentTimeMs: Long) {
        playbackController.pause()
        stateMachine.transition(PreviewStatus.READY, currentTimeMs)
    }

    override fun seekTo(timeUs: Long, currentTimeMs: Long) {
        playbackController.seekTo(timeUs)
        stateMachine.transition(PreviewStatus.SEEKING, currentTimeMs)
    }

    override fun tick(frameTimeNanos: Long, currentTimeMs: Long) {
        playbackController.tick(frameTimeNanos)
        stateMachine.tick(currentTimeMs)
    }
}
