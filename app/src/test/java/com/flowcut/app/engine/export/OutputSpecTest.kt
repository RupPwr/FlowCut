package com.flowcut.app.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputSpecTest {

    @Test
    fun testOutputSpecDimensionsAndAlignment() {
        fun spec(aspect: Aspect, shortSide: Int, alignW: Int = 2, alignH: Int = 2): OutSize =
            OutputSpec(aspect, shortSide, 30, Quality.HIGH, OutCodec.AVC).size(alignW, alignH)

        // 9:16 portrait: shortSide = 1080 -> 1080 x 1920
        assertEquals(OutSize(1080, 1920), spec(Aspect.R9_16, 1080))

        // 16:9 landscape: shortSide = 2160 -> 3840 x 2160
        assertEquals(OutSize(3840, 2160), spec(Aspect.R16_9, 2160))

        // 4:3 landscape: shortSide = 1080 -> 1440 x 1080
        assertEquals(OutSize(1440, 1080), spec(Aspect.R4_3, 1080))

        // 1:1 square: shortSide = 1440 -> 1440 x 1440
        assertEquals(OutSize(1440, 1440), spec(Aspect.R1_1, 1440))

        // 9:16 low res with even alignment: 240 -> 240 x 426
        assertEquals(OutSize(240, 426), spec(Aspect.R9_16, 240))

        // 9:16 low res with even alignment: 480 -> 480 x 854
        assertEquals(OutSize(480, 854), spec(Aspect.R9_16, 480))

        // Custom 16-byte alignment test
        val aligned16 = spec(Aspect.R9_16, 1080, alignW = 16, alignH = 16)
        assertEquals(0, aligned16.w % 16)
        assertEquals(0, aligned16.h % 16)
        assertEquals(OutSize(1088, 1920), aligned16)
    }

    @Test
    fun testOutputSpecKeyAndLabels() {
        val spec = OutputSpec(Aspect.R9_16, 1080, 30, Quality.HIGH, OutCodec.AVC)
        assertEquals("9x16_1080_30_HIGH_AVC", spec.key())

        assertEquals("1080p", Resolutions.label(1080))
        assertEquals("2K", Resolutions.label(1440))
        assertEquals("4K", Resolutions.label(2160))
        assertEquals("720p", Resolutions.label(720))
    }

    @Test
    fun testBitrateTableCalculations() {
        // Standard 1080x1920 @ 30fps HIGH AVC baseline
        val baseBps = BitrateTable.videoBps(1080, 1920, 30, Quality.HIGH, OutCodec.AVC)
        assertEquals(9_953_280, baseBps)

        // HEVC should be 30% lower (0.7 factor)
        val hevcBps = BitrateTable.videoBps(1080, 1920, 30, Quality.HIGH, OutCodec.HEVC)
        assertEquals(6_967_296, hevcBps)
        assertEquals((baseBps * 0.7).toLong(), hevcBps.toLong())

        // 60fps should have temporal 0.8 factor
        val fps60Bps = BitrateTable.videoBps(1080, 1920, 60, Quality.HIGH, OutCodec.AVC)
        // raw = 2073600 * 60 * 0.16 * 1.0 * 0.8 * 1.0 = 15,925,248
        assertEquals(15_925_248, fps60Bps)

        // 4K resolution factor (<= 4M is 0.8, >4M is 0.65)
        // 3840 * 2160 = 8,294,400 px (> 4M -> 0.65)
        val uhdBps = BitrateTable.videoBps(3840, 2160, 30, Quality.HIGH, OutCodec.AVC)
        // raw = 8294400 * 30 * 0.16 * 0.65 * 1.0 * 1.0 = 25,878,528
        assertEquals(25_878_528, uhdBps)

        // Min/Max clamping
        val clampedLow = BitrateTable.videoBps(1080, 1920, 30, Quality.LOW, OutCodec.AVC, minBps = 15_000_000)
        assertEquals(15_000_000, clampedLow)

        val clampedHigh = BitrateTable.videoBps(1080, 1920, 30, Quality.HIGH, OutCodec.AVC, maxBps = 5_000_000)
        assertEquals(5_000_000, clampedHigh)
    }

    @Test
    fun testExportEstimator() {
        val durationMs = 60_000L // 60s
        val videoBps = 10_000_000

        // totalBitrate = 10_000_000 + 192_000 = 10_192_000 bps
        // baseBytes = 10_192_000 * 60 / 8 = 76_440_000 bytes
        // with 2% container overhead (* 102 / 100) = 77_968_800 bytes
        val estimatedBytes = ExportEstimator.sizeBytes(videoBps, durationMs)
        assertEquals(77_968_800L, estimatedBytes)

        // Frame count calculation: 60s at 30fps = 1800 frames
        val frames30 = ExportEstimator.totalFrames(durationMs, 30)
        assertEquals(1800L, frames30)

        // Partial second ceil: 1001 ms at 30fps -> (1001 * 30 + 999) / 1000 = 31029 / 1000 = 31 frames
        val framesPartial = ExportEstimator.totalFrames(1001L, 30)
        assertEquals(31L, framesPartial)

        // ETA calculation
        // 1800 frames at 60 fps render rate -> 30,000 ms (30s)
        val etaMs = ExportEstimator.etaMs(1800L, 60.0)
        assertEquals(30_000L, etaMs)

        // Zero / negative fps returns -1L
        assertEquals(-1L, ExportEstimator.etaMs(1800L, 0.0))
        assertEquals(-1L, ExportEstimator.etaMs(1800L, -5.0))
        assertEquals(-1L, ExportEstimator.etaMs(0L, 30.0))
    }
}
