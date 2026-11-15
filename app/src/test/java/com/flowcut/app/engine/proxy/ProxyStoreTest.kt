package com.flowcut.app.engine.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProxyStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testComputeProxyDimensionsScalingAndRotation() {
        // 1. 1920x1080 landscape -> 640x360
        val landscape = computeProxyDimensions(1920, 1080, 0)
        assertEquals(Pair(640, 360), landscape)

        // 2. 1080x1920 portrait -> 360x640
        val portrait = computeProxyDimensions(1080, 1920, 0)
        assertEquals(Pair(360, 640), portrait)

        // 3. Rotated 1920x1080 (90 deg) -> effective 1080x1920 -> 360x640
        val rotated90 = computeProxyDimensions(1920, 1080, 90)
        assertEquals(Pair(360, 640), rotated90)

        // 4. Rotated 1920x1080 (270 deg) -> effective 1080x1920 -> 360x640
        val rotated270 = computeProxyDimensions(1920, 1080, 270)
        assertEquals(Pair(360, 640), rotated270)

        // 5. Small 480x360 -> 480x360 (no upscaling)
        val small = computeProxyDimensions(480, 360, 0)
        assertEquals(Pair(480, 360), small)
    }

    @Test
    fun testComputeProxyDimensionsEvenAlignment() {
        // Odd dimensions: 1921x1081 -> must align to even numbers
        val oddLandscape = computeProxyDimensions(1921, 1081, 0)
        assertTrue("Width must be even", oddLandscape.first % 2 == 0)
        assertTrue("Height must be even", oddLandscape.second % 2 == 0)

        // Odd small dimensions: 481x361 -> aligned to 480x360
        val oddSmall = computeProxyDimensions(481, 361, 0)
        assertEquals(Pair(480, 360), oddSmall)

        // Extreme 1x1 -> coerced to at least 2x2
        val tiny = computeProxyDimensions(1, 1, 0)
        assertEquals(Pair(2, 2), tiny)
    }

    @Test
    fun testComputeCacheKeyDeterminismAndUniqueness() {
        val path = "/storage/emulated/0/DCIM/sample.mp4"
        val size = 52_428_800L
        val mtime = 1_700_000_000_000L

        val key1 = computeCacheKey(path, size, mtime)
        val key2 = computeCacheKey(path, size, mtime)

        // Deterministic
        assertEquals(key1, key2)
        assertEquals("SHA-256 hex string should be 64 characters", 64, key1.length)

        // Key changes when size changes
        val keyDifferentSize = computeCacheKey(path, size + 1, mtime)
        assertNotEquals(key1, keyDifferentSize)

        // Key changes when mtime changes
        val keyDifferentMtime = computeCacheKey(path, size, mtime + 1000L)
        assertNotEquals(key1, keyDifferentMtime)

        // Key changes when path changes
        val keyDifferentPath = computeCacheKey("/other/path.mp4", size, mtime)
        assertNotEquals(key1, keyDifferentPath)
    }

    @Test
    fun testProxyStoreLifecycleAndCommit() {
        val proxyDir = tempFolder.newFolder("pip_proxies")
        val store = ProxyStore(proxyDir, maxBytes = 10_000_000L)

        val dummySource = File(tempFolder.root, "source_video.mp4").apply {
            writeBytes(ByteArray(1024))
        }

        // Before proxy creation: no ready proxy
        assertNull(store.getReadyProxyPath(dummySource.absolutePath))

        val key = store.computeKeyForSource(dummySource.absolutePath)
        val tempFile = store.getTempProxyFile(key)
        tempFile.writeBytes(ByteArray(512))

        // Temp file is NOT considered ready
        assertNull(store.getReadyProxyPath(dummySource.absolutePath))

        // Commit proxy
        val target = store.commitProxy(key)
        assertNotNull(target)
        assertTrue(target!!.exists())
        assertFalse(tempFile.exists())

        // Now ready proxy path is returned
        val readyPath = store.getReadyProxyPath(dummySource.absolutePath)
        assertEquals(target.absolutePath, readyPath)

        // Delete proxy for source
        store.deleteProxyFor(dummySource.absolutePath)
        assertNull(store.getReadyProxyPath(dummySource.absolutePath))
        assertFalse(target.exists())
    }

    @Test
    fun testPruneToBudgetEvictsOldestFiles() {
        val proxyDir = tempFolder.newFolder("pip_proxies_prune")
        // Budget = 300 bytes
        val store = ProxyStore(proxyDir, maxBytes = 300L)

        // Create 4 files of 100 bytes each (total 400 bytes > 300)
        val file1 = File(proxyDir, "proxy1.mp4").apply {
            writeBytes(ByteArray(100))
            setLastModified(1_000L) // oldest
        }
        val file2 = File(proxyDir, "proxy2.mp4").apply {
            writeBytes(ByteArray(100))
            setLastModified(2_000L)
        }
        val file3 = File(proxyDir, "proxy3.mp4").apply {
            writeBytes(ByteArray(100))
            setLastModified(3_000L)
        }
        val file4 = File(proxyDir, "proxy4.mp4").apply {
            writeBytes(ByteArray(100))
            setLastModified(4_000L) // newest
        }

        store.pruneToBudget()

        // file1 (oldest) must have been evicted, total is now 300 bytes
        assertFalse("Oldest file1 should be deleted", file1.exists())
        assertTrue("file2 should remain", file2.exists())
        assertTrue("file3 should remain", file3.exists())
        assertTrue("file4 should remain", file4.exists())

        // Clear all
        store.clearAll()
        assertEquals(0, proxyDir.listFiles()?.size ?: 0)
    }
}
