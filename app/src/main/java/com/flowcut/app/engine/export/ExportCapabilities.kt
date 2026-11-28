package com.flowcut.app.engine.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

class ExportCapabilities(
    private val encoders: List<MediaCodecInfo> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
    } catch (_: Throwable) {
        emptyList()
    }
) {
    data class Choice(
        val codecName: String,
        val hardware: Boolean,
        val width: Int,
        val height: Int,
        val minBps: Int,
        val maxBps: Int,
        val supportsVbr: Boolean = true,
        val supportedProfile: Int = 0,
        val supportedLevel: Int = 0
    )

    companion object {
        /**
         * Pure-Kotlin rule: >1080p area (2,073,600 px) or >30 FPS requires hardware acceleration.
         */
        fun isHardwareRequired(spec: OutputSpec): Boolean {
            val base = spec.size()
            val pixelArea = base.w.toLong() * base.h
            return pixelArea > 2_073_600L || spec.fps > 30
        }

        /**
         * Clamps requested bitrate into the encoder's supported bitrate bounds.
         */
        fun clampBitrate(bps: Int, minBps: Int, maxBps: Int): Int {
            val lower = minOf(minBps, maxBps).coerceAtLeast(1)
            val upper = maxOf(minBps, maxBps).coerceAtLeast(lower)
            return bps.coerceIn(lower, upper)
        }

        fun buildVideoFormat(spec: OutputSpec, c: Choice, bps: Int): MediaFormat {
            val mime = if (spec.codec == OutCodec.AVC) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
            return MediaFormat.createVideoFormat(mime, c.width, c.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, clampBitrate(bps, c.minBps, c.maxBps))
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (c.supportsVbr) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                if (c.supportedProfile > 0 && c.supportedLevel > 0) {
                    setInteger(MediaFormat.KEY_PROFILE, c.supportedProfile)
                    setInteger(MediaFormat.KEY_LEVEL, c.supportedLevel)
                }
            }
        }

        fun buildMinimalVideoFormat(spec: OutputSpec, c: Choice, bps: Int): MediaFormat {
            val mime = if (spec.codec == OutCodec.AVC) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
            return MediaFormat.createVideoFormat(mime, c.width, c.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, clampBitrate(bps, c.minBps, c.maxBps))
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        }
    }

    fun find(spec: OutputSpec, allowSoftware: Boolean = true): Choice? {
        val mime = if (spec.codec == OutCodec.AVC) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
        val needHardware = isHardwareRequired(spec)
        val desiredProfile = if (spec.codec == OutCodec.AVC) {
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain
        } else {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        }

        var softwareFallback: Choice? = null

        for (info in encoders) {
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val hw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("omx.google.", ignoreCase = true) &&
                        !info.name.startsWith("c2.android.", ignoreCase = true)
            }
            if (!hw && (!allowSoftware || needHardware)) continue

            val caps = try {
                info.getCapabilitiesForType(mime)
            } catch (_: Throwable) {
                null
            } ?: continue

            val colorFormats = caps.colorFormats
            if (colorFormats != null && colorFormats.isNotEmpty() &&
                !colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            ) {
                continue
            }

            val vc = caps.videoCapabilities ?: continue
            val alignW = vc.widthAlignment.coerceAtLeast(2)
            val alignH = vc.heightAlignment.coerceAtLeast(2)
            val size = spec.size(alignW, alignH)

            if (!vc.isSizeSupported(size.w, size.h)) continue
            if (!vc.areSizeAndRateSupported(size.w, size.h, spec.fps.toDouble())) continue

            val range = vc.bitrateRange
            val minBps = range?.lower ?: 200_000
            val maxBps = range?.upper ?: Int.MAX_VALUE
            val supportsVbr = try {
                caps.encoderCapabilities?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ) ?: true
            } catch (_: Throwable) {
                false
            }
            var matchedProfile = 0
            var maxLevelForProfile = 0
            try {
                for (pl in caps.profileLevels.orEmpty()) {
                    if (pl.profile == desiredProfile && pl.level > maxLevelForProfile) {
                        matchedProfile = pl.profile
                        maxLevelForProfile = pl.level
                    }
                }
            } catch (_: Throwable) {}

            val candidate = Choice(
                codecName = info.name,
                hardware = hw,
                width = size.w,
                height = size.h,
                minBps = minBps,
                maxBps = maxBps,
                supportsVbr = supportsVbr,
                supportedProfile = matchedProfile,
                supportedLevel = maxLevelForProfile
            )
            if (hw) {
                return candidate
            } else if (softwareFallback == null) {
                softwareFallback = candidate
            }
        }
        return softwareFallback
    }

    fun dumpSupportedSummary(): String {
        val sb = StringBuilder("=== Export Encoder Capabilities ===\n")
        for (info in encoders) {
            val hw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("omx.google.", ignoreCase = true) &&
                        !info.name.startsWith("c2.android.", ignoreCase = true)
            }
            sb.append("Codec: ${info.name} (hardware=$hw)\n")
            for (type in info.supportedTypes) {
                if (type.contains("video", ignoreCase = true)) {
                    val vc = try { info.getCapabilitiesForType(type).videoCapabilities } catch (_: Throwable) { null }
                    if (vc != null) {
                        sb.append("  - $type: align=[${vc.widthAlignment}x${vc.heightAlignment}], " +
                                "widths=${vc.supportedWidths}, heights=${vc.supportedHeights}, " +
                                "frameRates=${vc.supportedFrameRates}, bitrates=${vc.bitrateRange}\n")
                    } else {
                        sb.append("  - $type\n")
                    }
                }
            }
        }
        return sb.toString()
    }
}
