package com.flowcut.app.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailServiceTest {

    @Test
    fun testBucketTimeUsQuantization() {
        // Default bucket step = 500_000 us (500 ms)
        assertEquals(0L, bucketTimeUs(0L))
        assertEquals(0L, bucketTimeUs(100_000L))
        assertEquals(0L, bucketTimeUs(250_000L))
        assertEquals(0L, bucketTimeUs(499_999L))

        assertEquals(500_000L, bucketTimeUs(500_000L))
        assertEquals(500_000L, bucketTimeUs(750_000L))
        assertEquals(500_000L, bucketTimeUs(999_999L))

        assertEquals(1_000_000L, bucketTimeUs(1_000_000L))
        assertEquals(1_000_000L, bucketTimeUs(1_250_000L))

        // Negative timeUs clamped to 0
        assertEquals(0L, bucketTimeUs(-500_000L))
        assertEquals(0L, bucketTimeUs(-1L))

        // Custom bucket step (1_000_000 us = 1s)
        assertEquals(0L, bucketTimeUs(800_000L, bucketStepUs = 1_000_000L))
        assertEquals(1_000_000L, bucketTimeUs(1_200_000L, bucketStepUs = 1_000_000L))
        assertEquals(2_000_000L, bucketTimeUs(2_000_000L, bucketStepUs = 1_000_000L))
    }

    @Test
    fun testTileKeyDeterminismAndUniqueness() {
        val pathA = "/storage/emulated/0/DCIM/clipA.mp4"
        val pathB = "/storage/emulated/0/DCIM/clipB.mp4"
        val bucket1 = 500_000L
        val bucket2 = 1_000_000L

        val key1 = tileKey(pathA, bucket1)
        val key2 = tileKey(pathA, bucket1)

        // Deterministic
        assertEquals(key1, key2)
        assertEquals("SHA-256 key should be 64 characters hex", 64, key1.length)

        // Path sensitivity
        val keyDifferentPath = tileKey(pathB, bucket1)
        assertNotEquals(key1, keyDifferentPath)

        // Bucket time sensitivity
        val keyDifferentBucket = tileKey(pathA, bucket2)
        assertNotEquals(key1, keyDifferentBucket)
    }

    @Test
    fun testFindNearestBucketIndexExactAndFallback() {
        val buckets = longArrayOf(0L, 1_000_000L, 4_000_000L)
        val maxDelta = 2_000_000L

        // Empty array
        assertEquals(-1, ThumbnailService.findNearestBucketIndex(LongArray(0), 500_000L, maxDelta))

        // Exact matches
        assertEquals(0, ThumbnailService.findNearestBucketIndex(buckets, 0L, maxDelta))
        assertEquals(1, ThumbnailService.findNearestBucketIndex(buckets, 1_000_000L, maxDelta))
        assertEquals(2, ThumbnailService.findNearestBucketIndex(buckets, 4_000_000L, maxDelta))

        // Nearest neighbor within maxDelta
        assertEquals(0, ThumbnailService.findNearestBucketIndex(buckets, 400_000L, maxDelta))
        assertEquals(1, ThumbnailService.findNearestBucketIndex(buckets, 600_000L, maxDelta))
        assertEquals(1, ThumbnailService.findNearestBucketIndex(buckets, 2_000_000L, maxDelta))
        assertEquals(2, ThumbnailService.findNearestBucketIndex(buckets, 3_000_000L, maxDelta))
        assertEquals(2, ThumbnailService.findNearestBucketIndex(buckets, 6_000_000L, maxDelta))

        // Outside maxDelta returns -1
        assertEquals(-1, ThumbnailService.findNearestBucketIndex(buckets, 7_000_000L, maxDelta))
        assertEquals(-1, ThumbnailService.findNearestBucketIndex(longArrayOf(10_000_000L), 0L, maxDelta))
    }

    @Test
    fun testStaticImagePathDetection() {
        assertTrue(ThumbnailService.isStaticImagePath("/storage/photo.jpg"))
        assertTrue(ThumbnailService.isStaticImagePath("/storage/photo.JPEG"))
        assertTrue(ThumbnailService.isStaticImagePath("/storage/photo.png"))
        assertTrue(ThumbnailService.isStaticImagePath("/storage/photo.webp"))
        assertFalse(ThumbnailService.isStaticImagePath("/storage/video.mp4"))
        assertFalse(ThumbnailService.isStaticImagePath("/storage/video.mkv"))
        assertFalse(ThumbnailService.isStaticImagePath("/storage/no_extension"))
    }
}
