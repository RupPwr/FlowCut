package com.flowcut.app.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.flowcut.app.model.Clip
import com.flowcut.app.model.MAX_1080P_LONG_SIDE
import com.flowcut.app.model.MAX_1080P_SHORT_SIDE
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
        val contentResolver = context.contentResolver
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: return ImportValidationResult.Error("Unsupported video format")

        var durationMs = 0L
        var width = 0
        var height = 0
        var rotation = 0
        var frameRate = 30
        val fileSize: Long

        try {
            fileSize = pfd.statSize
            val usableSpace = clipsDir.usableSpace
            if (usableSpace < (fileSize * 1.2)) {
                pfd.close()
                return ImportValidationResult.Error("Not enough storage")
            }

            // 1. Pre-validate tracks and codec via MediaExtractor directly from file descriptor
            val extractor = MediaExtractor()
            var videoTrackIndex = -1
            var videoMime: String? = null
            var videoTrackFormat: MediaFormat? = null
            try {
                extractor.setDataSource(pfd.fileDescriptor)
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
                pfd.close()
                return ImportValidationResult.Error("Unsupported video format")
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }

            if (videoTrackIndex == -1 || videoMime == null) {
                pfd.close()
                return ImportValidationResult.Error("Unsupported video format")
            }

            // Codec check: H.264, HEVC, or Dolby Vision
            val isAvc = videoMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
            val isHevc = videoMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            val isDolbyVision = videoMime.contains("dolby-vision", ignoreCase = true)
            if (!isAvc && !isHevc && !isDolbyVision) {
                pfd.close()
                return ImportValidationResult.Error("Unsupported video format")
            }

            // 2. Pre-validate metadata via MediaMetadataRetriever directly from file descriptor
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durStr?.toLongOrNull() ?: 0L

                val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                width = wStr?.toIntOrNull() ?: 0

                val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                height = hStr?.toIntOrNull() ?: 0

                val rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                rotation = rotStr?.toIntOrNull() ?: 0

                // Frame rate detection from format or metadata BEFORE releasing retriever
                try {
                    if (videoTrackFormat != null && videoTrackFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        frameRate = videoTrackFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                    }
                } catch (e: Exception) {
                    try {
                        frameRate = videoTrackFormat?.getFloat(MediaFormat.KEY_FRAME_RATE)?.toInt() ?: 30
                    } catch (_: Exception) {}
                }
                if (frameRate <= 0) {
                    val fpsMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    frameRate = fpsMeta?.toFloatOrNull()?.toInt() ?: 30
                }
                if (frameRate <= 0) {
                    frameRate = 30
                }
            } catch (e: Exception) {
                pfd.close()
                return ImportValidationResult.Error("Unsupported video format")
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } finally {
            try { pfd.close() } catch (_: Exception) {}
        }

        // 3. Validations:
        // Duration < 1000 ms: "Clip must be at least 1 second"
        if (durationMs < MIN_CLIP_MS) {
            return ImportValidationResult.Error("Clip must be at least 1 second")
        }

        // Duration > 900000 ms: "Video exceeds 15-minute limit"
        if (durationMs > MAX_CLIP_MS) {
            return ImportValidationResult.Error("Video exceeds 15-minute limit")
        }

        // 1080p limit check: Supports both Vertical and Horizontal videos up to 1080p
        val effectiveWidth = if (rotation == 90 || rotation == 270) height else width
        val effectiveHeight = if (rotation == 90 || rotation == 270) width else height
        val shortSide = minOf(effectiveWidth, effectiveHeight)
        val longSide = maxOf(effectiveWidth, effectiveHeight)

        if (shortSide > MAX_1080P_SHORT_SIDE) {
            return ImportValidationResult.Error(
                "Video resolution (${effectiveWidth}x${effectiveHeight}) exceeds 1080p limit. Videos higher than 1080p (e.g. 1440p, 4K) are not supported."
            )
        }

        if (longSide > MAX_1080P_LONG_SIDE) {
            return ImportValidationResult.Error(
                "Video dimension (${effectiveWidth}x${effectiveHeight}) exceeds maximum supported limit (${MAX_1080P_LONG_SIDE}px)."
            )
        }

        // Total timeline > 900000 ms: "Project duration exceeds 15-minute limit"
        if (currentTotalMs + durationMs > MAX_TOTAL_MS) {
            return ImportValidationResult.Error("Project duration exceeds 15-minute limit")
        }

        // 4. All validations passed: Copy URI stream to tempFile using 64KB buffer
        val tempId = UUID.randomUUID().toString()
        val tempFile = File(clipsDir, "temp_$tempId.mp4")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return ImportValidationResult.Error("Unsupported video format")

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
