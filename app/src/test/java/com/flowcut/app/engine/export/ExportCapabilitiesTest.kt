package com.flowcut.app.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportCapabilitiesTest {

    @Test
    fun testIsHardwareRequiredLogic() {
        // 1080p @ 30fps: 1080x1920 = 2,073,600 px (at threshold), 30fps -> hardware NOT required (software allowed)
        val spec1080p30 = OutputSpec(Aspect.R9_16, 1080, 30, Quality.HIGH, OutCodec.AVC)
        assertFalse("1080p30 should not strictly require hardware encoder", ExportCapabilities.isHardwareRequired(spec1080p30))

        // 720p @ 30fps: 720x1280 = 921,600 px, 30fps -> software allowed
        val spec720p30 = OutputSpec(Aspect.R9_16, 720, 30, Quality.HIGH, OutCodec.AVC)
        assertFalse("720p30 should not strictly require hardware encoder", ExportCapabilities.isHardwareRequired(spec720p30))

        // 1080p @ 60fps: fps > 30 -> hardware REQUIRED
        val spec1080p60 = OutputSpec(Aspect.R9_16, 1080, 60, Quality.HIGH, OutCodec.AVC)
        assertTrue("1080p60 must require hardware encoder", ExportCapabilities.isHardwareRequired(spec1080p60))

        // 1080p @ 50fps: fps > 30 -> hardware REQUIRED
        val spec1080p50 = OutputSpec(Aspect.R9_16, 1080, 50, Quality.HIGH, OutCodec.AVC)
        assertTrue("1080p50 must require hardware encoder", ExportCapabilities.isHardwareRequired(spec1080p50))

        // 2K (1440p) @ 30fps: > 2,073,600 px -> hardware REQUIRED
        val spec1440p30 = OutputSpec(Aspect.R9_16, 1440, 30, Quality.HIGH, OutCodec.AVC)
        assertTrue("2K (1440p) must require hardware encoder", ExportCapabilities.isHardwareRequired(spec1440p30))

        // 4K (2160p) @ 30fps: > 2,073,600 px -> hardware REQUIRED
        val spec4k30 = OutputSpec(Aspect.R16_9, 2160, 30, Quality.HIGH, OutCodec.AVC)
        assertTrue("4K (2160p) must require hardware encoder", ExportCapabilities.isHardwareRequired(spec4k30))
    }

    @Test
    fun testClampBitrate() {
        val min = 200_000
        val max = 20_000_000

        // Normal within range
        assertEquals(10_000_000, ExportCapabilities.clampBitrate(10_000_000, min, max))

        // Below minimum
        assertEquals(min, ExportCapabilities.clampBitrate(50_000, min, max))

        // Above maximum
        assertEquals(max, ExportCapabilities.clampBitrate(30_000_000, min, max))

        // Edge values
        assertEquals(min, ExportCapabilities.clampBitrate(min, min, max))
        assertEquals(max, ExportCapabilities.clampBitrate(max, min, max))
    }
}
