package com.flowcut.app.engine.media

import android.media.MediaCodecInfo.CodecProfileLevel as P
import android.media.MediaFormat

object ColorInfoCheck {
    enum class Verdict { OK, REJECT_HDR, REJECT_10BIT }

    const val COLOR_TRANSFER_ST2084 = 6
    const val COLOR_TRANSFER_HLG = 7

    const val HEVC_PROFILE_MAIN10 = 2
    const val HEVC_PROFILE_MAIN10_HDR10 = 4096
    const val HEVC_PROFILE_MAIN10_HDR10_PLUS = 8192
    const val AVC_PROFILE_HIGH10 = 16

    const val MIMETYPE_VIDEO_HEVC = "video/hevc"
    const val MIMETYPE_VIDEO_AVC = "video/avc"

    /** MediaExtractor.getTrackFormat() format wrapper. Best-effort: key na ho to OK maano. */
    fun check(f: MediaFormat): Verdict {
        val mime = if (f.containsKey(MediaFormat.KEY_MIME)) f.getString(MediaFormat.KEY_MIME) else null
        val transfer = if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            try { f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) } catch (_: Exception) { null }
        } else null
        val profile = if (f.containsKey(MediaFormat.KEY_PROFILE)) {
            try { f.getInteger(MediaFormat.KEY_PROFILE) } catch (_: Exception) { null }
        } else null
        return check(mime = mime, colorTransfer = transfer, profile = profile)
    }

    /** Pure-Kotlin overload testable in standard JVM unit tests without android.media.* runtime */
    fun check(mime: String?, colorTransfer: Int?, profile: Int?): Verdict {
        val m = mime.orEmpty()
        if (m.contains("dolby-vision", ignoreCase = true)) {
            return Verdict.REJECT_HDR
        }
        if (colorTransfer == COLOR_TRANSFER_ST2084 ||
            colorTransfer == COLOR_TRANSFER_HLG ||
            (try { colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 || colorTransfer == MediaFormat.COLOR_TRANSFER_HLG } catch (_: Throwable) { false })
        ) {
            return Verdict.REJECT_HDR
        }
        if (profile == null) return Verdict.OK

        val isTenBit = when {
            m.equals(MIMETYPE_VIDEO_HEVC, ignoreCase = true) ||
            (try { m.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } catch (_: Throwable) { false }) ->
                profile == HEVC_PROFILE_MAIN10 ||
                profile == HEVC_PROFILE_MAIN10_HDR10 ||
                profile == HEVC_PROFILE_MAIN10_HDR10_PLUS ||
                (try {
                    profile == P.HEVCProfileMain10 ||
                    profile == P.HEVCProfileMain10HDR10 ||
                    profile == P.HEVCProfileMain10HDR10Plus
                } catch (_: Throwable) { false })

            m.equals(MIMETYPE_VIDEO_AVC, ignoreCase = true) ||
            (try { m.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } catch (_: Throwable) { false }) ->
                profile == AVC_PROFILE_HIGH10 ||
                (try { profile == P.AVCProfileHigh10 } catch (_: Throwable) { false })

            else -> false
        }
        return if (isTenBit) Verdict.REJECT_10BIT else Verdict.OK
    }
}
