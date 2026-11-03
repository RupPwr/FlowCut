package com.flowcut.app.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.flowcut.app.model.Clip
import com.flowcut.app.model.MAX_CLIP_MS
import com.flowcut.app.model.MAX_SIDE_PX
import com.flowcut.app.model.MAX_TOTAL_MS
import com.flowcut.app.model.MIN_CLIP_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

sealed class ImportValidationResult {
    data class Success(val clip: Clip) : ImportValidationResult()
    data class Error(val message: String) : ImportValidationResult()
}

class ClipImporter(private val context: Context) {

    private val clipsDir: File
        get() = File(context.filesDir, "clips").apply { if (!exists()) mkdirs() }

    suspend fun importUris(
        uris: List<Uri>,
        currentTotalMs: Long,
        onProgress: (current: Int, total: Int) -> Unit,
        onError: (message: String) -> Unit
    ): List<Clip> = withContext(Dispatchers.IO) {
        val importedClips = mutableListOf<Clip>()
        var runningTotalMs = currentTotalMs
        val total = uris.size

        for ((index, uri) in uris.withIndex()) {
            onProgress(index + 1, total)
            when (val res = validateAndImport(uri, runningTotalMs)) {
                is ImportValidationResult.Success -> {
                    importedClips.add(res.clip)
                    runningTotalMs += res.clip.durationMs
                }
                is ImportValidationResult.Error -> {
                    withContext(Dispatchers.Main) {
                        onError(res.message)
                    }
                }
            }
        }
        importedClips
    }

    private fun validateAndImport(uri: Uri, currentTotalMs: Long): ImportValidationResult {
        // 1. Copy URI to a temporary file in clips directory
        val tempId = UUID.randomUUID().toString()
        val tempFile = File(clipsDir, "temp_$tempId.mp4")

        try {
            val contentResolver = context.contentResolver
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: return ImportValidationResult.Error("Unsupported video format")

            val fileSize = pfd.statSize
            pfd.close()

            // Storage check: Free storage < file size * 1.2
            val usableSpace = clipsDir.usableSpace
            if (usableSpace < (fileSize * 1.2)) {
                return ImportValidationResult.Error("Not enough storage")
            }

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return ImportValidationResult.Error("Unsupported video format")

            // 2. Extract and validate video track and format via MediaExtractor
            val extractor = MediaExtractor()
            var videoTrackIndex = -1
            var videoMime: String? = null
            var videoTrackFormat: MediaFormat? = null
            try {
                extractor.setDataSource(tempFile.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        videoTrackIndex = i
                        videoMime = mime
                        videoTrackFormat = format
                        break
                    }
                }
            } catch (e: Exception) {
                tempFile.delete()
                return ImportValidationResult.Error("Unsupported video format")
            } finally {
                try {
                    extractor.release()
                } catch (e: Exception) {}
            }

            if (videoTrackIndex == -1 || videoMime == null) {
                tempFile.delete()
                return ImportValidationResult.Error("Unsupported video format")
            }

            // Codec check: H.264 or HEVC
            val isAvc = videoMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
            val isHevc = videoMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            if (!isAvc && !isHevc) {
                tempFile.delete()
                return ImportValidationResult.Error("Unsupported video format")
            }

            // 3. Extract metadata via MediaMetadataRetriever
            val retriever = MediaMetadataRetriever()
            var durationMs = 0L
            var width = 0
            var height = 0
            var rotation = 0

            try {
                retriever.setDataSource(tempFile.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durStr?.toLongOrNull() ?: 0L

                val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                width = wStr?.toIntOrNull() ?: 0

                val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                height = hStr?.toIntOrNull() ?: 0

                val rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                rotation = rotStr?.toIntOrNull() ?: 0
            } catch (e: Exception) {
                tempFile.delete()
                return ImportValidationResult.Error("Unsupported video format")
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {}
            }

            // Validations:
            // Duration < 1000 ms: "Clip must be at least 1 second"
            if (durationMs < MIN_CLIP_MS) {
                tempFile.delete()
                return ImportValidationResult.Error("Clip must be at least 1 second")
            }

            // Duration > 300000 ms: "Clip is longer than 5 minutes"
            if (durationMs > MAX_CLIP_MS) {
                tempFile.delete()
                return ImportValidationResult.Error("Clip is longer than 5 minutes")
            }

            // 1080p limit check (Section 12.1 / P0-8 / P0-9): Max 1080p
            val minSide = minOf(width, height)
            val maxSide = maxOf(width, height)
            if (minSide > 1080 || maxSide > MAX_SIDE_PX) {
                tempFile.delete()
                return ImportValidationResult.Error("Video exceeds 1080p limit (max 1080p supported)")
            }

            // Total timeline > 300000 ms: "Total length limit is 5 minutes"
            if (currentTotalMs + durationMs > MAX_TOTAL_MS) {
                tempFile.delete()
                return ImportValidationResult.Error("Total length limit is 5 minutes")
            }

            // Frame rate detection (Section 19.9):
            var frameRate = 30
            try {
                if (videoTrackFormat != null && videoTrackFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    frameRate = videoTrackFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                }
            } catch (e: Exception) {
                try {
                    frameRate = videoTrackFormat?.getFloat(MediaFormat.KEY_FRAME_RATE)?.toInt() ?: 30
                } catch (ignored: Exception) {}
            }
            if (frameRate <= 0) {
                val fpsMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                frameRate = fpsMeta?.toFloatOrNull()?.toInt() ?: 30
            }
            if (frameRate <= 0) {
                frameRate = 30
            }

            // Move tempFile to destination: filesDir/clips/<clipId>.mp4
            val clipId = UUID.randomUUID().toString()
            val destFile = File(clipsDir, "$clipId.mp4")
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            // Trigger keyframe index in background (Section 19.8)
            CoroutineScope(Dispatchers.IO).launch {
                KeyframeIndex.build(destFile.absolutePath, destFile.absolutePath.replace(".mp4", ".idx"))
            }

            val clip = Clip(
                id = clipId,
                path = destFile.absolutePath,
                durationMs = durationMs,
                width = width,
                height = height,
                rotation = rotation,
                frameRate = frameRate
            )
            return ImportValidationResult.Success(clip)

        } catch (e: Exception) {
            tempFile.delete()
            return ImportValidationResult.Error("Unsupported video format")
        }
    }
}
