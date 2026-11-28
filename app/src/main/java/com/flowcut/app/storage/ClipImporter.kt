package com.flowcut.app.storage

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.flowcut.app.audio.WaveformService
import com.flowcut.app.engine.export.FrameGate
import com.flowcut.app.engine.media.ColorInfoCheck
import com.flowcut.app.model.Clip
import com.flowcut.app.model.MAX_1080P_LONG_SIDE
import com.flowcut.app.model.MAX_1080P_SHORT_SIDE
import com.flowcut.app.model.MAX_4K_LONG_SIDE
import com.flowcut.app.model.MAX_4K_SHORT_SIDE
import com.flowcut.app.model.MAX_CLIP_MS
import com.flowcut.app.model.MAX_SIDE_PX
import com.flowcut.app.model.MAX_TOTAL_MS
import com.flowcut.app.model.MIN_CLIP_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

sealed class ImportValidationResult {
    data class Success(val clip: Clip) : ImportValidationResult()
    data class Error(val message: String) : ImportValidationResult()
}

class ClipImporter(private val context: Context) {

    private val importerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val waveformService = WaveformService(context, importerScope)

    fun release() {
        importerScope.cancel()
    }

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
                    waveformService.ensure(listOf(res.clip.path)) {}
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
        var retrW = 0
        var retrH = 0
        var retrRot = 0
        var frameRate = 30
        val fileSize: Long

        var videoTrackFormat: MediaFormat? = null
        var videoMime: String? = null

        try {
            fileSize = pfd.statSize
            val usableSpace = clipsDir.usableSpace
            val requiredBytes = if (fileSize > 0L) (fileSize * 1.2).toLong() else 50L * 1024L * 1024L
            if (usableSpace < requiredBytes) {
                pfd.close()
                return ImportValidationResult.Error("Not enough storage")
            }

            // 1. Pre-validate tracks and codec via MediaExtractor directly from file descriptor
            val extractor = MediaExtractor()
            var videoTrackIndex = -1
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

            // Codec check: H.264 or HEVC (Dolby Vision and 10-bit/HDR rejected)
            val isAvc = videoMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
            val isHevc = videoMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            val isDolbyVision = videoMime.contains("dolby-vision", ignoreCase = true)
            if (isDolbyVision) {
                pfd.close()
                return ImportValidationResult.Error("HDR / 10-bit videos abhi support nahi hain.")
            }
            if (!isAvc && !isHevc) {
                pfd.close()
                return ImportValidationResult.Error("Unsupported video format")
            }

            if (videoTrackFormat != null) {
                val verdict = ColorInfoCheck.check(videoTrackFormat)
                if (verdict != ColorInfoCheck.Verdict.OK) {
                    pfd.close()
                    return ImportValidationResult.Error("HDR / 10-bit videos abhi support nahi hain.")
                }
            }

            // 2. Pre-validate metadata via MediaMetadataRetriever directly from file descriptor
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durStr?.toLongOrNull() ?: 0L

                val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                retrW = wStr?.toIntOrNull() ?: 0

                val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                retrH = hStr?.toIntOrNull() ?: 0

                val rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                retrRot = rotStr?.toIntOrNull() ?: 0

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

        // Canonical raw coded dimensions and rotation resolution:
        val formatW = if (videoTrackFormat != null && videoTrackFormat.containsKey(MediaFormat.KEY_WIDTH)) {
            videoTrackFormat.getInteger(MediaFormat.KEY_WIDTH)
        } else 0
        val formatH = if (videoTrackFormat != null && videoTrackFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
            videoTrackFormat.getInteger(MediaFormat.KEY_HEIGHT)
        } else 0
        val formatRot = if (videoTrackFormat != null && videoTrackFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            videoTrackFormat.getInteger(MediaFormat.KEY_ROTATION)
        } else -1

        val canonicalRotation = when {
            formatRot in listOf(0, 90, 180, 270) -> formatRot
            retrRot in listOf(0, 90, 180, 270) -> retrRot
            else -> 0
        }

        // Canonical raw coded dimensions (unrotated buffer dimensions):
        val rawWidth: Int
        val rawHeight: Int
        if (formatW > 0 && formatH > 0) {
            rawWidth = formatW
            rawHeight = formatH
        } else if (retrW > 0 && retrH > 0) {
            if ((canonicalRotation == 90 || canonicalRotation == 270) && retrW < retrH) {
                // Guard against OEM MediaMetadataRetriever implementations that already return pre-rotated display dimensions
                rawWidth = retrH
                rawHeight = retrW
            } else {
                rawWidth = retrW
                rawHeight = retrH
            }
        } else {
            rawWidth = maxOf(formatW, retrW)
            rawHeight = maxOf(formatH, retrH)
        }

        // Display dimensions (after container rotation):
        val displayWidth = if (canonicalRotation == 90 || canonicalRotation == 270) rawHeight else rawWidth
        val displayHeight = if (canonicalRotation == 90 || canonicalRotation == 270) rawWidth else rawHeight
        val shortSide = minOf(displayWidth, displayHeight)
        val longSide = maxOf(displayWidth, displayHeight)

        // 3. Validations:
        // Duration < 1000 ms: "Clip must be at least 1 second"
        if (durationMs < MIN_CLIP_MS) {
            return ImportValidationResult.Error("Clip must be at least 1 second")
        }

        // Duration > 900000 ms: "Video exceeds 15-minute limit"
        if (durationMs > MAX_CLIP_MS) {
            return ImportValidationResult.Error("Video exceeds 15-minute limit")
        }

        // Reject only if video resolution exceeds maximum 4K limit
        if (shortSide > MAX_4K_SHORT_SIDE || longSide > MAX_4K_LONG_SIDE) {
            return ImportValidationResult.Error(
                "Video resolution (${displayWidth}x${displayHeight}) exceeds maximum 4K limit (${MAX_4K_LONG_SIDE}px)."
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

            val clipId = UUID.randomUUID().toString()
            val destFile = File(clipsDir, "$clipId.mp4")

            val mime = videoMime ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val isWithin1080p = shortSide <= MAX_1080P_SHORT_SIDE && longSide <= MAX_1080P_LONG_SIDE
            val canHwDecode = isWithin1080p || isHardwareDecoderSupported(mime, rawWidth, rawHeight)

            var finalWidth = rawWidth
            var finalHeight = rawHeight
            var finalRotation = canonicalRotation

            if (canHwDecode) {
                // Direct import with ZERO re-encoding (standard/aligned 1080p or hardware-supported 2K/4K)
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
            } else {
                // Gracefully downscale/transcode 2K/4K to 1080p preserving aspect ratio
                val (targetW, targetH) = computeTarget1080pDimensions(displayWidth, displayHeight)
                val ok = downscaleTo1080p(tempFile, destFile, mime, targetW, targetH)
                tempFile.delete()
                if (!ok || !destFile.exists() || destFile.length() <= 0L) {
                    destFile.delete()
                    return ImportValidationResult.Error("Failed to transcode high-resolution video")
                }
                finalWidth = targetW
                finalHeight = targetH
                finalRotation = 0
            }

            // Trigger keyframe index in background (Section 19.8)
            importerScope.launch {
                KeyframeIndex.build(destFile.absolutePath, destFile.absolutePath.replace(".mp4", ".idx"))
            }

            val clip = Clip(
                id = clipId,
                path = destFile.absolutePath,
                durationMs = durationMs,
                width = finalWidth,
                height = finalHeight,
                rotation = finalRotation,
                frameRate = frameRate
            )
            return ImportValidationResult.Success(clip)
        } catch (e: Exception) {
            tempFile.delete()
            return ImportValidationResult.Error("Unsupported video format")
        }
    }

    private fun isHardwareDecoderSupported(mime: String, width: Int, height: Int): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                val types = info.supportedTypes
                if (types.none { it.equals(mime, ignoreCase = true) }) continue
                val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("omx.google.", ignoreCase = true) &&
                            !info.name.startsWith("c2.android.", ignoreCase = true)
                }
                if (!isHw) continue
                val caps = try {
                    info.getCapabilitiesForType(mime)
                } catch (_: Throwable) {
                    null
                } ?: continue
                val videoCaps = caps.videoCapabilities ?: continue
                if (videoCaps.isSizeSupported(width, height) || videoCaps.isSizeSupported(height, width)) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun computeTarget1080pDimensions(displayW: Int, displayH: Int): Pair<Int, Int> {
        val isPortrait = displayW <= displayH
        val targetW: Int
        val targetH: Int
        if (isPortrait) {
            val aspect = displayW.toDouble() / displayH.toDouble()
            targetW = 1080
            val h = (1080.0 / aspect).roundToInt()
            targetH = (minOf(h, MAX_1080P_LONG_SIDE) and 1.inv()).coerceAtLeast(2)
        } else {
            val aspect = displayW.toDouble() / displayH.toDouble()
            targetH = 1080
            val w = (1080.0 * aspect).roundToInt()
            targetW = (minOf(w, MAX_1080P_LONG_SIDE) and 1.inv()).coerceAtLeast(2)
        }
        return Pair(targetW and 1.inv(), targetH and 1.inv())
    }

    private fun downscaleTo1080p(
        sourceFile: File,
        outputFile: File,
        videoMime: String,
        targetW: Int,
        targetH: Int
    ): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var encoderInputSurface: Surface? = null
        var frameCallbackThread: HandlerThread? = null
        var muxer: MediaMuxer? = null
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var renderer: ImportTextureRenderer? = null
        var audioExtractor: MediaExtractor? = null

        var eglDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext? = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        val texIds = IntArray(1)
        var muxerStarted = false
        var videoTrackIndexInMuxer = -1
        var audioTrackIndexInMuxer = -1

        try {
            val ext = MediaExtractor()
            extractor = ext
            ext.setDataSource(sourceFile.absolutePath)

            var videoTrackIdx = -1
            var videoFormat: MediaFormat? = null
            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until ext.trackCount) {
                val format = ext.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIdx == -1) {
                    videoTrackIdx = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIdx == -1) {
                    audioTrackIdx = i
                    audioFormat = format
                }
            }

            if (videoTrackIdx < 0 || videoFormat == null) return false
            ext.selectTrack(videoTrackIdx)

            // 1. Configure video encoder (AVC, target dimensions, 8 Mbps, 30 fps)
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }
            val vEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = vEnc
            vEnc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = vEnc.createInputSurface()
            encoderInputSurface = inputSurface
            vEnc.start()

            // 2. EGL setup for encoder inputSurface
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            eglDisplay = display
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val configCandidates = arrayOf(
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    0x3142, 1, // EGL_RECORDABLE_ANDROID
                    EGL14.EGL_NONE
                ),
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
                )
            )
            var config: EGLConfig? = null
            for (attribs in configCandidates) {
                configs[0] = null
                numConfigs[0] = 0
                if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0 && configs[0] != null
                ) {
                    config = configs[0]
                    break
                }
            }
            if (config == null) return false

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) return false
            eglContext = ctx

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            val surf = EGL14.eglCreateWindowSurface(display, config, inputSurface, surfAttribs, 0)
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) return false
            eglSurface = surf

            if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) return false

            // 3. OES Texture and Renderer setup
            val rnd = ImportTextureRenderer()
            renderer = rnd
            rnd.init()

            GLES20.glGenTextures(1, texIds, 0)
            val oesTexId = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val frameGate = FrameGate()
            val cbThread = HandlerThread("FlowCut-ImportCb").apply { start() }
            frameCallbackThread = cbThread
            val cbHandler = Handler(cbThread.looper)
            val st = SurfaceTexture(oesTexId).apply {
                setDefaultBufferSize(targetW, targetH)
                setOnFrameAvailableListener({
                    frameGate.signal()
                }, cbHandler)
            }
            surfaceTexture = st
            val decSurf = Surface(st)
            decoderSurface = decSurf

            // 4. Configure video decoder
            val vDec = MediaCodec.createDecoderByType(videoMime)
            decoder = vDec
            vDec.configure(videoFormat, decSurf, null, 0)
            vDec.start()

            // 5. MediaMuxer & interleaved audio extractor setup
            val mux = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            var audioBuf: ByteBuffer? = null
            val aBufInfo = MediaCodec.BufferInfo()
            var audioEos = audioTrackIdx < 0
            var lastAudioPtsUs = -1L
            if (audioTrackIdx >= 0) {
                val aExt = MediaExtractor()
                audioExtractor = aExt
                aExt.setDataSource(sourceFile.absolutePath)
                aExt.selectTrack(audioTrackIdx)
                val maxAudioInput = try {
                    if (audioFormat != null && audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else {
                        256 * 1024
                    }
                } catch (_: Throwable) {
                    256 * 1024
                }.coerceAtLeast(256 * 1024)
                audioBuf = ByteBuffer.allocateDirect(maxAudioInput)
            }

            fun drainAudioUpTo(limitPtsUs: Long) {
                val aExt = audioExtractor ?: return
                val buf = audioBuf ?: return
                if (audioEos || audioTrackIndexInMuxer < 0 || !muxerStarted) return
                while (true) {
                    val rawPtsUs = aExt.sampleTime
                    if (rawPtsUs < 0L) {
                        audioEos = true
                        break
                    }
                    if (rawPtsUs > limitPtsUs) break
                    buf.clear()
                    val sampleSize = aExt.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        audioEos = true
                        break
                    }
                    val monoPtsUs = if (rawPtsUs < lastAudioPtsUs) lastAudioPtsUs else rawPtsUs
                    lastAudioPtsUs = monoPtsUs
                    buf.position(0)
                    buf.limit(sampleSize)
                    aBufInfo.offset = 0
                    aBufInfo.size = sampleSize
                    aBufInfo.presentationTimeUs = monoPtsUs
                    aBufInfo.flags = mapExtractorFlagsToCodecFlags(aExt.sampleFlags)
                    mux.writeSampleData(audioTrackIndexInMuxer, buf, aBufInfo)
                    if (!aExt.advance()) {
                        audioEos = true
                        break
                    }
                }
            }

            // 6. Transcoding loop
            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            val stMatrix = FloatArray(16)
            var inputEos = false
            var decoderOutputEos = false
            var encoderOutputEos = false
            var eosRetries = 0
            var lastEncodedPtsUs = -1L
            var lastMuxedVideoPtsUs = -1L

            while (!encoderOutputEos) {
                // Feed decoder
                if (!inputEos) {
                    val inIdx = vDec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = vDec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = ext.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                vDec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                val pts = ext.sampleTime.coerceAtLeast(0L)
                                val codecFlags = mapExtractorFlagsToCodecFlags(ext.sampleFlags)
                                vDec.queueInputBuffer(inIdx, 0, sampleSize, pts, codecFlags)
                                ext.advance()
                            }
                        }
                    }
                }

                // Drain decoder
                if (!decoderOutputEos) {
                    val decStatus = vDec.dequeueOutputBuffer(decBufferInfo, 10_000L)
                    if (decStatus >= 0) {
                        val render = decBufferInfo.size > 0
                        if (render) {
                            frameGate.arm()
                        }
                        vDec.releaseOutputBuffer(decStatus, render)
                        if (render) {
                            if (frameGate.await(2000L)) {
                                st.updateTexImage()
                                st.getTransformMatrix(stMatrix)
                                GLES20.glViewport(0, 0, targetW, targetH)
                                rnd.draw(stMatrix, oesTexId)
                                val rawPtsUs = decBufferInfo.presentationTimeUs.coerceAtLeast(0L)
                                val monoPtsUs = if (rawPtsUs <= lastEncodedPtsUs) lastEncodedPtsUs + 100L else rawPtsUs
                                lastEncodedPtsUs = monoPtsUs
                                EGLExt.eglPresentationTimeANDROID(display, surf, monoPtsUs * 1000L)
                                EGL14.eglSwapBuffers(display, surf)
                            }
                        }
                        if ((decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderOutputEos = true
                            vEnc.signalEndOfInputStream()
                        }
                    }
                }

                // Drain encoder
                while (true) {
                    val encStatus = vEnc.dequeueOutputBuffer(encBufferInfo, 10_000L)
                    if (encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            videoTrackIndexInMuxer = mux.addTrack(vEnc.outputFormat)
                            if (audioFormat != null) {
                                try {
                                    audioTrackIndexInMuxer = mux.addTrack(audioFormat)
                                } catch (_: Throwable) {
                                    audioTrackIndexInMuxer = -1
                                }
                            }
                            mux.start()
                            muxerStarted = true
                        }
                    } else if (encStatus >= 0) {
                        val outBuf = vEnc.getOutputBuffer(encStatus)
                        if (outBuf != null && encBufferInfo.size > 0 && muxerStarted) {
                            if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (encBufferInfo.presentationTimeUs <= lastMuxedVideoPtsUs) {
                                    encBufferInfo.presentationTimeUs = lastMuxedVideoPtsUs + 100L
                                }
                                if (encBufferInfo.presentationTimeUs < 0L) {
                                    encBufferInfo.presentationTimeUs = 0L
                                }
                                lastMuxedVideoPtsUs = encBufferInfo.presentationTimeUs
                                outBuf.position(encBufferInfo.offset)
                                outBuf.limit(encBufferInfo.offset + encBufferInfo.size)
                                mux.writeSampleData(videoTrackIndexInMuxer, outBuf, encBufferInfo)
                                // Interleave audio packets up to the current muxed video PTS
                                drainAudioUpTo(lastMuxedVideoPtsUs)
                            }
                        }
                        vEnc.releaseOutputBuffer(encStatus, false)
                        if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderOutputEos = true
                            break
                        }
                    }
                }

                if (decoderOutputEos && !encoderOutputEos) {
                    eosRetries++
                    if (eosRetries > 300) {
                        break
                    }
                }
            }

            // 7. Flush any remaining interleaved audio samples after video EOS
            if (muxerStarted && !audioEos) {
                drainAudioUpTo(Long.MAX_VALUE)
            }

            return true
        } catch (_: Throwable) {
            return false
        } finally {
            try { audioExtractor?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { decoderSurface?.release() } catch (_: Throwable) {}
            try { surfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
            try { surfaceTexture?.release() } catch (_: Throwable) {}
            try { frameCallbackThread?.quitSafely() } catch (_: Throwable) {}
            try { renderer?.release() } catch (_: Throwable) {}
            if (texIds[0] != 0) {
                try { GLES20.glDeleteTextures(1, texIds, 0) } catch (_: Throwable) {}
            }
            if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                } catch (_: Throwable) {}
                if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                    try { EGL14.eglDestroySurface(eglDisplay, eglSurface) } catch (_: Throwable) {}
                }
                if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                    try { EGL14.eglDestroyContext(eglDisplay, eglContext) } catch (_: Throwable) {}
                }
                // Never call EGL14.eglTerminate(EGL_DEFAULT_DISPLAY): it is process-shared with Preview/HWUI.
                try { EGL14.eglReleaseThread() } catch (_: Throwable) {}
            }
            try { encoderInputSurface?.release() } catch (_: Throwable) {}
            try { encoder?.stop() } catch (_: Throwable) {}
            try { encoder?.release() } catch (_: Throwable) {}
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Throwable) {}
            }
            try { muxer?.release() } catch (_: Throwable) {}
        }
    }

    private class ImportTextureRenderer {
        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """.trimIndent()

        private var program = 0
        private var uSTMatrixHandle = 0
        private var aPositionHandle = 0
        private var aTextureHandle = 0

        private val triangleVerticesData = floatArrayOf(
            -1.0f, -1.0f, 0f, 0f, 0f,
             1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f,  1.0f, 0f, 0f, 1f,
             1.0f,  1.0f, 0f, 1f, 1f
        )
        private val triangleVertices = ByteBuffer.allocateDirect(triangleVerticesData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(triangleVerticesData)
                position(0)
            }

        fun init() {
            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vShader)
            GLES20.glAttachShader(program, fShader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vShader)
            GLES20.glDeleteShader(fShader)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                program = 0
                throw IllegalStateException("Import shader link failed: $log")
            }
            aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        }

        fun draw(stMatrix: FloatArray, textureId: Int) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            triangleVertices.position(0)
            GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, triangleVertices)
            GLES20.glEnableVertexAttribArray(aPositionHandle)

            triangleVertices.position(3)
            GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, triangleVertices)
            GLES20.glEnableVertexAttribArray(aTextureHandle)

            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("Import shader compile failed: $log")
            }
            return shader
        }
    }

    companion object {
        internal fun mapExtractorFlagsToCodecFlags(extractorFlags: Int): Int {
            var codecFlags = 0
            if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }
            return codecFlags
        }
    }
}
