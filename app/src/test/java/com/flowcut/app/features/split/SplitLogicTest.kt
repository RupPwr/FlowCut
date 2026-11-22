package com.flowcut.app.features.split

import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.time.TimeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLogicTest {

    @Test
    fun `calculateSplit fails if cut is too close to start`() {
        val clip = Clip(id = "c1", path = "path", durationMs = 2000L, width = 1080, height = 1920, rotation = 0)
        val result = SplitLogic.calculateSplit(
            clipIndex = 0,
            clip = clip,
            clipStartMs = 0L,
            playheadUs = 100_000L // 100ms (< 200ms min)
        )
        assertTrue(result is SplitValidationResult.Invalid)
    }

    @Test
    fun `calculateSplit fails if cut is too close to end`() {
        val clip = Clip(id = "c1", path = "path", durationMs = 2000L, width = 1080, height = 1920, rotation = 0)
        val result = SplitLogic.calculateSplit(
            clipIndex = 0,
            clip = clip,
            clipStartMs = 0L,
            playheadUs = 1_900_000L // 100ms remaining (< 200ms min)
        )
        assertTrue(result is SplitValidationResult.Invalid)
    }

    @Test
    fun `calculateSplit succeeds and splits duration and timeMap accurately`() {
        val clip = Clip(
            id = "c1",
            path = "path",
            durationMs = 2000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            timeMap = TimeMap(inUs = 0L, outUs = 2_000_000L, speedNum = 1, speedDen = 1)
        )
        val result = SplitLogic.calculateSplit(
            clipIndex = 0,
            clip = clip,
            clipStartMs = 0L,
            playheadUs = 800_000L, // at 800ms
            newRightClipId = "c2"
        )
        assertTrue(result is SplitValidationResult.Valid)
        val valid = result as SplitValidationResult.Valid

        assertEquals(800L, valid.leftClip.durationMs)
        assertEquals(1200L, valid.rightClip.durationMs)
        assertEquals("c1", valid.leftClip.id)
        assertEquals("c2", valid.rightClip.id)

        assertEquals(0L, valid.leftTimeMap.inUs)
        assertEquals(800_000L, valid.leftTimeMap.outUs)
        assertEquals(800_000L, valid.rightTimeMap.inUs)
        assertEquals(2_000_000L, valid.rightTimeMap.outUs)
    }
}
