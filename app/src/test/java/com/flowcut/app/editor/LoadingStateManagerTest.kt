package com.flowcut.app.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingStateManagerTest {

    private fun waitForCondition(timeoutMs: Long = 1000L, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            Thread.sleep(10L)
        }
        return condition()
    }

    @Test
    fun testInitialState() {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val manager = LoadingStateManager(scope, delayMs = 50L)

        assertFalse(manager.isBusy.value)
        assertFalse(manager.isLoadingVisible.value)
        assertTrue(manager.activeSources.value.isEmpty())
        assertEquals("Processing...", manager.loadingMessage.value)
    }

    @Test
    fun testShortOperationDoesNotShowLoader() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val manager = LoadingStateManager(scope, delayMs = 150L)

        manager.startLoading(LoadingSources.SCRUBBING)
        assertTrue(manager.isBusy.value)
        assertFalse(manager.isLoadingVisible.value)

        // Work completes in 30ms (< 150ms delay)
        delay(30L)
        manager.stopLoading(LoadingSources.SCRUBBING)
        assertFalse(manager.isBusy.value)
        assertFalse(manager.isLoadingVisible.value)

        // Wait past 200ms: loader should never have shown
        delay(200L)
        assertFalse(manager.isLoadingVisible.value)
    }

    @Test
    fun testHeavyOperationShowsLoader() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val manager = LoadingStateManager(scope, delayMs = 50L)

        manager.startLoading(LoadingSources.WAVEFORM_GEN, "Generating waveform...")
        assertTrue(manager.isBusy.value)
        assertEquals("Generating waveform...", manager.loadingMessage.value)

        // Before delay
        assertFalse(manager.isLoadingVisible.value)

        // Wait for loader to become visible after delay
        assertTrue("Loader should become visible after delayMs", waitForCondition { manager.isLoadingVisible.value })

        // Finish heavy operation: must hide immediately (0ms delay)
        manager.stopLoading(LoadingSources.WAVEFORM_GEN)
        assertFalse(manager.isLoadingVisible.value)
        assertFalse(manager.isBusy.value)
    }

    @Test
    fun testMultipleSourcesOverlapping() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val manager = LoadingStateManager(scope, delayMs = 50L)

        manager.startLoading(LoadingSources.THUMBNAIL_GEN)
        delay(20L)
        manager.startLoading(LoadingSources.SCRUBBING)

        // Wait for loader to show
        assertTrue(waitForCondition { manager.isLoadingVisible.value })

        // Finish first source: second source is still active so loader remains visible
        manager.stopLoading(LoadingSources.THUMBNAIL_GEN)
        assertTrue(manager.isSourceLoading(LoadingSources.SCRUBBING))
        assertTrue(manager.isLoadingVisible.value)

        // Finish second source: all sources complete -> immediately hidden
        manager.stopLoading(LoadingSources.SCRUBBING)
        assertFalse(manager.isLoadingVisible.value)
        assertFalse(manager.isBusy.value)
    }

    @Test
    fun testClearAll() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val manager = LoadingStateManager(scope, delayMs = 50L)

        manager.startLoading(LoadingSources.SCRUBBING)
        assertTrue(waitForCondition { manager.isLoadingVisible.value })

        manager.clearAll()
        assertFalse(manager.isLoadingVisible.value)
        assertTrue(manager.activeSources.value.isEmpty())
        assertFalse(manager.isBusy.value)
    }

    @Test
    fun testWithLoading() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val manager = LoadingStateManager(scope, delayMs = 50L)

        val result = manager.withLoading(LoadingSources.HEAVY_PROCESSING, "Processing...") {
            assertTrue(manager.isSourceLoading(LoadingSources.HEAVY_PROCESSING))
            "Done"
        }
        assertEquals("Done", result)
        assertFalse(manager.isSourceLoading(LoadingSources.HEAVY_PROCESSING))
    }
}
