package com.flowcut.app.engine

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import com.flowcut.app.model.Clip
import java.io.File
import java.util.concurrent.Executors

/**
 * FrameSource (Section 19.6 & 19.7):
 * Low-latency hardware decoder wrapper with async cleanup, API 30 flags, and flush reuse.
 */
class FrameSource(
    var clip: Clip,
    private val surfaceTexture: SurfaceTexture
) {
    companion object {
        private val releaseExecutor = Executors.newSingleThreadExecutor()
    }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var isPrepared = false
    private var isEos = false
    private var currentPtsUs: Long = -1L
    private var currentMime: String = ""

    fun prepare() {
        if (isPrepared) return
        val file = File(clip.path)
        if (!file.exists()) return

        try {
            val ext = MediaExtractor()
            ext.setDataSource(clip.path)
            var videoTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    format = f
                    break
                }
            }
            if (videoTrack == -1 || format == null) {
                ext.release()
                return
            }

            ext.selectTrack(videoTrack)
            extractor = ext

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            currentMime = mime

            // Low-latency API 30 flags (Section 19.6)
            try {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                format.setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
            } catch (_: Exception) {}

            val decoder = MediaCodec.createDecoderByType(mime)
            surface = Surface(surfaceTexture)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            isPrepared = true
            isEos = false
            currentPtsUs = -1L
        } catch (e: Exception) {
            // Recreate retry once if needed
            isPrepared = false
        }
    }

    /**
     * Check if decoder can be reused for another clip with same mime & dimensions (Section 19.7).
     */
    fun canReuse(other: Clip): Boolean {
        return isPrepared && codec != null && clip.width == other.width && clip.height == other.height
    }

    fun seekTo(localUs: Long) {
        val ext = extractor ?: return
        val dec = codec ?: return
        try {
            ext.seekTo(localUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            dec.flush()
            isEos = false
            currentPtsUs = -1L

            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L
            var rendered = false
            var feedEos = false
            var attempts = 0

            while (!rendered && attempts < 120) {
                attempts++
                if (!feedEos) {
                    val inputIndex = dec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = dec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = ext.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                dec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                feedEos = true
                            } else {
                                dec.queueInputBuffer(inputIndex, 0, sampleSize, ext.sampleTime, 0)
                                ext.advance()
                            }
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val pts = bufferInfo.presentationTimeUs
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isEos = true
                    }
                    if (pts >= localUs - 16_000L || isEos) {
                        dec.releaseOutputBuffer(outIndex, true)
                        currentPtsUs = pts
                        rendered = true
                    } else {
                        dec.releaseOutputBuffer(outIndex, false)
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && feedEos) {
                    break
                }
            }
        } catch (_: Exception) {}
    }

    fun frameAt(localUs: Long): Boolean {
        val ext = extractor ?: return false
        val dec = codec ?: return false

        // If target is behind current by more than 50ms, seek back
        if (currentPtsUs > localUs + 50_000L) {
            seekTo(localUs)
            return true
        }

        // If current frame is already close enough, we can render/keep it
        if (currentPtsUs in (localUs - 16_000L)..(localUs + 16_000L)) {
            return true
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 5_000L
        var attempts = 0

        while (attempts < 120) {
            attempts++
            if (!isEos) {
                val inIndex = dec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inBuf = dec.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        val sampleSize = ext.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, sampleSize, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                }
            }

            val outIndex = dec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outIndex >= 0) {
                val pts = bufferInfo.presentationTimeUs
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    isEos = true
                }
                if (pts >= localUs - 16_000L || isEos) {
                    dec.releaseOutputBuffer(outIndex, true)
                    currentPtsUs = pts
                    return true
                } else {
                    dec.releaseOutputBuffer(outIndex, false)
                }
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && isEos) {
                return false
            }
        }
        return true
    }

    /**
     * Release decoders on background thread (Section 19.7) so GL thread is never blocked.
     */
    fun release() {
        val oldCodec = codec
        val oldExt = extractor
        val oldSurf = surface

        codec = null
        extractor = null
        surface = null
        isPrepared = false
        currentPtsUs = -1L

        releaseExecutor.execute {
            try {
                oldCodec?.stop()
            } catch (_: Exception) {}
            try {
                oldCodec?.release()
            } catch (_: Exception) {}
            try {
                oldExt?.release()
            } catch (_: Exception) {}
            try {
                oldSurf?.release()
            } catch (_: Exception) {}
        }
    }
}
