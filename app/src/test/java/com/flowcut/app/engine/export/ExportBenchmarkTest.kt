package com.flowcut.app.engine.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportBenchmarkTest {

    @Test
    fun testComputeMeasuredFps() {
        // Standard normal cases
        assertEquals(60.0, ExportBenchmark.computeMeasuredFps(60, 1000L), 0.001)
        assertEquals(60.0, ExportBenchmark.computeMeasuredFps(90, 1500L), 0.001)
        assertEquals(30.0, ExportBenchmark.computeMeasuredFps(30, 1000L), 0.001)
        assertEquals(25.0, ExportBenchmark.computeMeasuredFps(50, 2000L), 0.001)

        // Edge cases
        assertEquals(0.0, ExportBenchmark.computeMeasuredFps(0, 1000L), 0.001)
        assertEquals(0.0, ExportBenchmark.computeMeasuredFps(60, 0L), 0.001)
        assertEquals(0.0, ExportBenchmark.computeMeasuredFps(60, -500L), 0.001)
        assertEquals(0.0, ExportBenchmark.computeMeasuredFps(-10, 1000L), 0.001)
    }

    @Test
    fun testFallbackFps() {
        val spec30 = OutputSpec(Aspect.R9_16, 1080, 30, Quality.HIGH, OutCodec.AVC)
        // Hardware: 30 * 1.5 = 45.0
        assertEquals(45.0, ExportBenchmark.fallbackFps(spec30, isHardware = true), 0.001)
        // Software: 30 * 0.75 = 22.5
        assertEquals(22.5, ExportBenchmark.fallbackFps(spec30, isHardware = false), 0.001)

        val spec60 = OutputSpec(Aspect.R9_16, 1080, 60, Quality.HIGH, OutCodec.AVC)
        // Hardware: 60 * 1.5 = 90.0
        assertEquals(90.0, ExportBenchmark.fallbackFps(spec60, isHardware = true), 0.001)
        // Software: 60 * 0.75 = 45.0
        assertEquals(45.0, ExportBenchmark.fallbackFps(spec60, isHardware = false), 0.001)
    }

    @Test
    fun testEstimateProjectEtaMs() {
        val spec30 = OutputSpec(Aspect.R9_16, 1080, 30, Quality.HIGH, OutCodec.AVC)
        val duration5MinMs = 300_000L // 5 minutes

        // 5 min at 30 fps = 9000 frames
        // At 45 fps -> 9000 / 45 = 200 seconds = 200_000 ms
        val etaMs45 = ExportBenchmark.estimateProjectEtaMs(duration5MinMs, spec30, 45.0)
        assertEquals(200_000L, etaMs45)

        // At 30 fps (realtime) -> 300,000 ms
        val etaMs30 = ExportBenchmark.estimateProjectEtaMs(duration5MinMs, spec30, 30.0)
        assertEquals(300_000L, etaMs30)

        // At 60 fps -> 150,000 ms
        val etaMs60 = ExportBenchmark.estimateProjectEtaMs(duration5MinMs, spec30, 60.0)
        assertEquals(150_000L, etaMs60)

        // Invalid FPS returns -1
        assertEquals(-1L, ExportBenchmark.estimateProjectEtaMs(duration5MinMs, spec30, 0.0))
        assertEquals(-1L, ExportBenchmark.estimateProjectEtaMs(duration5MinMs, spec30, -10.0))

        // 0 duration returns -1
        assertEquals(-1L, ExportBenchmark.estimateProjectEtaMs(0L, spec30, 30.0))
    }
}
