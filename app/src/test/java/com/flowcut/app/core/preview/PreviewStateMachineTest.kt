package com.flowcut.app.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewStateMachineTest {

    @Test
    fun `initial state is idle and loader is hidden`() {
        val sm = PreviewStateMachine()
        val state = sm.currentState
        assertEquals(PreviewStatus.IDLE, state.status)
        assertFalse(state.isLoaderVisible)
    }

    @Test
    fun `quick loading under 150ms does not flicker loader`() {
        val sm = PreviewStateMachine()

        // t = 0: start loading
        sm.transition(PreviewStatus.LOADING, currentTimeMs = 0L)
        assertFalse(sm.currentState.isLoaderVisible)

        // t = 100ms: still loading (< 150ms threshold)
        sm.tick(currentTimeMs = 100L)
        assertFalse(sm.currentState.isLoaderVisible)

        // t = 120ms: ready! (total loading was 120ms < 150ms)
        sm.transition(PreviewStatus.READY, currentTimeMs = 120L)
        assertFalse(sm.currentState.isLoaderVisible)
        assertEquals(PreviewStatus.READY, sm.currentState.status)
    }

    @Test
    fun `loading over 150ms displays loader and keeps it for at least 300ms`() {
        val sm = PreviewStateMachine()

        // t = 0: start loading
        sm.transition(PreviewStatus.LOADING, currentTimeMs = 0L)
        assertFalse(sm.currentState.isLoaderVisible)

        // t = 160ms: exceeds threshold, loader must appear
        sm.tick(currentTimeMs = 160L)
        assertTrue(sm.currentState.isLoaderVisible)

        // t = 200ms: becomes ready quickly after loader appeared (only shown for 40ms)
        sm.transition(PreviewStatus.READY, currentTimeMs = 200L)
        // Must stay visible due to minLoaderVisibleMs (300ms from 160ms = 460ms)
        assertTrue(sm.currentState.isLoaderVisible)

        // t = 350ms: still within 300ms minimum window
        sm.tick(currentTimeMs = 350L)
        assertTrue(sm.currentState.isLoaderVisible)

        // t = 465ms: 300ms minimum has elapsed, loader disappears
        sm.tick(currentTimeMs = 465L)
        assertFalse(sm.currentState.isLoaderVisible)
        assertEquals(PreviewStatus.READY, sm.currentState.status)
    }

    @Test
    fun `seeking behaves with same anti-flicker rules`() {
        val sm = PreviewStateMachine()

        sm.transition(PreviewStatus.SEEKING, currentTimeMs = 1000L)
        assertFalse(sm.currentState.isLoaderVisible)

        sm.tick(currentTimeMs = 1200L)
        assertTrue(sm.currentState.isLoaderVisible)

        sm.transition(PreviewStatus.PLAYING, currentTimeMs = 1250L)
        assertTrue(sm.currentState.isLoaderVisible)

        sm.tick(currentTimeMs = 1510L)
        assertFalse(sm.currentState.isLoaderVisible)
        assertEquals(PreviewStatus.PLAYING, sm.currentState.status)
    }
}
