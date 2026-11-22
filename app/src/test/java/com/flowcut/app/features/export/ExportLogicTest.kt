package com.flowcut.app.features.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportLogicTest {

    @Test
    fun `estimateFileSizeMb scales with duration and bitrate`() {
        // 10 seconds at 12 Mbps = 120 Mbits = 15 MB
        val size10s = ExportLogic.estimateFileSizeMb(10_000L, 12_000_000L)
        // 15 MB = 15 * 10^6 / (1024 * 1024) ~ 14.3 MB
        assertTrue(size10s in 14.0f..14.5f)
    }

    @Test
    fun `formatResolution labels portrait and landscape correctly`() {
        val portrait = ExportLogic.formatResolution(1080, 1920)
        assertEquals("1080 x 1920 (9:16)", portrait)

        val landscape = ExportLogic.formatResolution(1920, 1080)
        assertEquals("1920 x 1080 (16:9)", landscape)
    }

    @Test
    fun `progressToPercent clamps bounds`() {
        assertEquals(0, ExportLogic.progressToPercent(-0.1f))
        assertEquals(50, ExportLogic.progressToPercent(0.5f))
        assertEquals(100, ExportLogic.progressToPercent(1.2f))
    }
}
