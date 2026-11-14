package com.flowcut.app.engine.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorInfoCheckTest {

    @Test
    fun testRejectsHdrTransferCurves() {
        // ST2084 PQ curve
        val verdictST2084 = ColorInfoCheck.check(
            mime = "video/hevc",
            colorTransfer = ColorInfoCheck.COLOR_TRANSFER_ST2084,
            profile = null
        )
        assertEquals(ColorInfoCheck.Verdict.REJECT_HDR, verdictST2084)

        // HLG curve
        val verdictHLG = ColorInfoCheck.check(
            mime = "video/hevc",
            colorTransfer = ColorInfoCheck.COLOR_TRANSFER_HLG,
            profile = null
        )
        assertEquals(ColorInfoCheck.Verdict.REJECT_HDR, verdictHLG)

        // AVC with HLG
        val verdictAvcHLG = ColorInfoCheck.check(
            mime = "video/avc",
            colorTransfer = ColorInfoCheck.COLOR_TRANSFER_HLG,
            profile = 8 // High 8-bit
        )
        assertEquals(ColorInfoCheck.Verdict.REJECT_HDR, verdictAvcHLG)
    }

    @Test
    fun testRejectsDolbyVisionMime() {
        val verdictDv = ColorInfoCheck.check(
            mime = "video/dolby-vision",
            colorTransfer = null,
            profile = null
        )
        assertEquals(ColorInfoCheck.Verdict.REJECT_HDR, verdictDv)

        val verdictDvMixedCase = ColorInfoCheck.check(
            mime = "video/Dolby-Vision",
            colorTransfer = null,
            profile = null
        )
        assertEquals(ColorInfoCheck.Verdict.REJECT_HDR, verdictDvMixedCase)
    }

    @Test
    fun testRejects10BitHevcProfiles() {
        // HEVC Main 10
        val verdictMain10 = ColorInfoCheck.check(
            mime = "video/hevc",
            colorTransfer = null,
            profile = ColorInfoCheck.HEVC_PROFILE_MAIN10
        )
        assertEquals(ColorInfoCheck.Verdict.REJECT_10BIT, verdictMain10)

        // HEVC Main 10 HDR10
        val verdictHdr10 = ColorInfoCheck.check(
            mime = "video/hevc",
            colorTransfer = null,
            profile = ColorInfoCheck.HEVC_PROFILE_MAIN10_HDR10
        )
        assertEquals(ColorInfoCheck.Verdict.REJECT_10BIT, verdictHdr10)

        // HEVC Main 10 HDR10+
        val verdictHdr10Plus = ColorInfoCheck.check(
            mime = "video/hevc",
            colorTransfer = null,
            profile = ColorInfoCheck.HEVC_PROFILE_MAIN10_HDR10_PLUS
        )
        assertEquals(ColorInfoCheck.Verdict.REJECT_10BIT, verdictHdr10Plus)
    }

    @Test
    fun testRejects10BitAvcProfile() {
        // AVC High 10
        val verdictAvc10 = ColorInfoCheck.check(
            mime = "video/avc",
            colorTransfer = null,
            profile = ColorInfoCheck.AVC_PROFILE_HIGH10
        )
        assertEquals(ColorInfoCheck.Verdict.REJECT_10BIT, verdictAvc10)
    }

    @Test
    fun testAcceptsNormal8BitVideos() {
        // AVC Baseline (1), Main (2), High (8)
        assertEquals(
            ColorInfoCheck.Verdict.OK,
            ColorInfoCheck.check(mime = "video/avc", colorTransfer = 3 /* SDR */, profile = 1)
        )
        assertEquals(
            ColorInfoCheck.Verdict.OK,
            ColorInfoCheck.check(mime = "video/avc", colorTransfer = 3 /* SDR */, profile = 2)
        )
        assertEquals(
            ColorInfoCheck.Verdict.OK,
            ColorInfoCheck.check(mime = "video/avc", colorTransfer = 3 /* SDR */, profile = 8)
        )

        // HEVC Main (1 = Main 8-bit)
        assertEquals(
            ColorInfoCheck.Verdict.OK,
            ColorInfoCheck.check(mime = "video/hevc", colorTransfer = 3 /* SDR */, profile = 1)
        )
    }

    @Test
    fun testAcceptsNullOrMissingMetadata() {
        // Best-effort: when transfer or profile cannot be extracted, accept
        assertEquals(
            ColorInfoCheck.Verdict.OK,
            ColorInfoCheck.check(mime = "video/avc", colorTransfer = null, profile = null)
        )
        assertEquals(
            ColorInfoCheck.Verdict.OK,
            ColorInfoCheck.check(mime = "video/hevc", colorTransfer = null, profile = null)
        )
        assertEquals(
            ColorInfoCheck.Verdict.OK,
            ColorInfoCheck.check(mime = null, colorTransfer = null, profile = null)
        )
    }
}
