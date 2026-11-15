package com.flowcut.app.storage

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import kotlinx.coroutines.yield
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Keyframe index (Section 19.8):
 * Background generation of sync sample timestamps for O(log N) keyframe lookup during scrubbing.
 */
object KeyframeIndex {

    suspend fun build(videoPath: String, indexPath: String) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        } catch (_: Throwable) {}
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    break
                }
            }
            if (videoTrack == -1) return
            extractor.selectTrack(videoTrack)

            val syncTimes = ArrayList<Long>()
            var frameCount = 0
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break
                if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    syncTimes.add(sampleTime / 1000L) // in ms
                }
                frameCount++
                if (frameCount % 400 == 0) {
                    yield()
                }
                if (!extractor.advance()) break
            }

            val file = File(indexPath)
            val tempFile = File("$indexPath.tmp")
            DataOutputStream(FileOutputStream(tempFile).buffered()).use { out ->
                out.writeInt(syncTimes.size)
                for (time in syncTimes) {
                    out.writeLong(time)
                }
            }
            if (tempFile.exists()) {
                tempFile.renameTo(file)
            }
        } catch (e: Exception) {
            // Best-effort background index
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {}
        }
    }

    fun load(indexPath: String): LongArray? {
        val file = File(indexPath)
        if (!file.exists()) return null
        return try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                val size = input.readInt()
                val array = LongArray(size)
                for (i in 0 until size) {
                    array[i] = input.readLong()
                }
                array
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Binary search for closest keyframe at or before targetMs.
     */
    fun findClosestKeyframeAtOrBefore(keyframes: LongArray, targetMs: Long): Long {
        if (keyframes.isEmpty()) return targetMs
        var lo = 0
        var hi = keyframes.size - 1
        var ans = keyframes[0]
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (keyframes[mid] <= targetMs) {
                ans = keyframes[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

