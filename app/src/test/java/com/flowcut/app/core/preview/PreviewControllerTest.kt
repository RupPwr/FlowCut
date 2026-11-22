package com.flowcut.app.core.preview

import com.flowcut.app.core.playback.PlaybackController
import com.flowcut.app.core.playback.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewControllerTest {

    @Test
    fun `controller forwards play and updates preview state`() {
        val playback = PlaybackController()
        val controller = DefaultPreviewController(playback)

        controller.play(currentTimeMs = 1000L)

        assertEquals(PlayMode.PLAYING, controller.playhead.mode)
        assertEquals(PreviewStatus.PLAYING, controller.state.status)
    }

    @Test
    fun `controller forwards pause and updates preview state`() {
        val playback = PlaybackController()
        val controller = DefaultPreviewController(playback)

        controller.play(currentTimeMs = 1000L)
        controller.pause(currentTimeMs = 1100L)

        assertEquals(PlayMode.PAUSED, controller.playhead.mode)
        assertEquals(PreviewStatus.READY, controller.state.status)
    }

    @Test
    fun `controller forwards seek and updates preview state to seeking`() {
        val playback = PlaybackController()
        val controller = DefaultPreviewController(playback)

        controller.seekTo(500_000L, currentTimeMs = 1200L)
        assertEquals(PreviewStatus.SEEKING, controller.state.status)
    }
}
