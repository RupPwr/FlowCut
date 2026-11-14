package com.flowcut.app.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPreflightTest {

    private val margin = ExportPreflight.MARGIN_BYTES // 200 MB

    @Test
    fun testEstimateBytesCalculation() {
        // 60 seconds (60,000 ms) at 10 Mbps (10,000,000 bps)
        // totalBitrate = 10,000,000 + 192,000 = 10,192,000
        // expectedBytes = (10,192,000 * 60,000) / 8,000 = 76,440,000 bytes (~72.9 MB)
        val durationMs = 60_000L
        val videoBitrateBps = 10_000_000
        val bytes = ExportPreflight.estimateBytes(durationMs, videoBitrateBps)
        assertEquals(76_440_000L, bytes)
    }

    @Test
    fun testSameVolumeInsufficientFreeSpaceFails() {
        val estimated = 50L * 1024L * 1024L // 50 MB
        // Required on same volume = 2 * 50MB + 200MB = 300 MB
        val freeBytes = 250L * 1024L * 1024L // 250 MB (less than 300 MB)

        val result = ExportPreflight.checkSpace(
            estimatedBytes = estimated,
            cacheFreeBytes = freeBytes,
            moviesFreeBytes = freeBytes,
            sameVolume = true
        )

        assertTrue("Expected InsufficientStorage on same volume with 250MB free for 300MB required",
            result is PreflightResult.InsufficientStorage)
        val failure = result as PreflightResult.InsufficientStorage
        assertEquals((50L * 2 * 1024L * 1024L) + margin, failure.requiredBytes)
        assertEquals(freeBytes, failure.availableBytes)
        assertTrue(failure.message.contains("Not enough storage space"))
    }

    @Test
    fun testSameVolumeSufficientFreeSpaceSucceeds() {
        val estimated = 50L * 1024L * 1024L // 50 MB
        // Required = 300 MB, Available = 400 MB
        val freeBytes = 400L * 1024L * 1024L

        val result = ExportPreflight.checkSpace(
            estimatedBytes = estimated,
            cacheFreeBytes = freeBytes,
            moviesFreeBytes = freeBytes,
            sameVolume = true
        )

        assertTrue("Expected Ok when free space exceeds requirement", result is PreflightResult.Ok)
        val ok = result as PreflightResult.Ok
        assertEquals(estimated, ok.estimatedBytes)
        assertEquals(freeBytes, ok.freeBytes)
    }

    @Test
    fun testSeparateVolumeCacheInsufficientFails() {
        val estimated = 50L * 1024L * 1024L // 50 MB
        // Required per volume = 50MB + 200MB = 250 MB
        val cacheFree = 200L * 1024L * 1024L // 200 MB (< 250 MB)
        val moviesFree = 500L * 1024L * 1024L // 500 MB (sufficient)

        val result = ExportPreflight.checkSpace(
            estimatedBytes = estimated,
            cacheFreeBytes = cacheFree,
            moviesFreeBytes = moviesFree,
            sameVolume = false
        )

        assertTrue(result is PreflightResult.InsufficientStorage)
        val failure = result as PreflightResult.InsufficientStorage
        assertTrue(failure.message.contains("app cache volume"))
    }

    @Test
    fun testSeparateVolumeMoviesInsufficientFails() {
        val estimated = 50L * 1024L * 1024L // 50 MB
        val cacheFree = 500L * 1024L * 1024L // 500 MB (sufficient)
        val moviesFree = 210L * 1024L * 1024L // 210 MB (< 250 MB)

        val result = ExportPreflight.checkSpace(
            estimatedBytes = estimated,
            cacheFreeBytes = cacheFree,
            moviesFreeBytes = moviesFree,
            sameVolume = false
        )

        assertTrue(result is PreflightResult.InsufficientStorage)
        val failure = result as PreflightResult.InsufficientStorage
        assertTrue(failure.message.contains("Movies volume"))
    }

    @Test
    fun testSeparateVolumeBothSufficientSucceeds() {
        val estimated = 50L * 1024L * 1024L // 50 MB
        val cacheFree = 300L * 1024L * 1024L
        val moviesFree = 400L * 1024L * 1024L

        val result = ExportPreflight.checkSpace(
            estimatedBytes = estimated,
            cacheFreeBytes = cacheFree,
            moviesFreeBytes = moviesFree,
            sameVolume = false
        )

        assertTrue(result is PreflightResult.Ok)
    }
}
