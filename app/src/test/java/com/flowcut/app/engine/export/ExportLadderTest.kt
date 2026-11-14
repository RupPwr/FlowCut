package com.flowcut.app.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportLadderTest {

    @Test
    fun test4K60FpsLadderCandidates() {
        val req = OutputSpec(Aspect.R16_9, 2160, 60, Quality.HIGH, OutCodec.AVC)
        val list = ExportLadder.candidates(req)

        // Ensure not empty and no duplicates
        assertTrue(list.isNotEmpty())
        assertEquals(list.size, list.distinct().size)

        // 1. First candidate must be exact requested spec
        assertEquals(req, list[0])

        // 2. Second candidate is alternate codec (HEVC) at same resolution and FPS
        assertEquals(req.copy(codec = OutCodec.HEVC), list[1])

        // 3. Third and fourth candidates step down FPS from 60 to 30
        assertEquals(req.copy(fps = 30), list[2])
        assertEquals(req.copy(fps = 30, codec = OutCodec.HEVC), list[3])

        // 4. Subsequent candidates step down shortSide resolutions in descending order
        val lowerSides = list.drop(4).map { it.shortSide }.distinct()
        assertEquals(listOf(1440, 1080, 720, 540, 480, 360, 240, 144), lowerSides)

        // All stepped-down resolution candidates should use the reduced 30 FPS
        assertTrue(list.drop(4).all { it.fps == 30 })
    }

    @Test
    fun test1080p30FpsLadderCandidates() {
        val req = OutputSpec(Aspect.R9_16, 1080, 30, Quality.HIGH, OutCodec.AVC)
        val list = ExportLadder.candidates(req)

        // Ensure no duplicates
        assertEquals(list.size, list.distinct().size)

        // 1. First candidate is exact requested spec
        assertEquals(req, list[0])

        // 2. Second candidate is HEVC at same resolution
        assertEquals(req.copy(codec = OutCodec.HEVC), list[1])

        // 3. Since FPS is 30, it is not >= 50, so no lowFps intermediate step is added at 1080p
        // Next candidate immediately starts stepping down to 720p
        assertEquals(720, list[2].shortSide)
        assertEquals(30, list[2].fps)

        val descendingSides = list.drop(2).map { it.shortSide }.distinct()
        assertEquals(listOf(720, 540, 480, 360, 240, 144), descendingSides)
        assertFalse(descendingSides.contains(1080))
        assertFalse(descendingSides.contains(1440))
        assertFalse(descendingSides.contains(2160))
    }
}
