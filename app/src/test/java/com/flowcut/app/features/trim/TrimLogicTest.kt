package com.flowcut.app.features.trim

import com.flowcut.app.core.time.TimeMap
import org.junit.Assert.assertEquals
import org.junit.Test

class TrimLogicTest {

    @Test
    fun `clampTrimRange preserves valid bounds`() {
        val (inUs, outUs) = TrimLogic.clampTrimRange(
            desiredInUs = 1_000_000L,
            desiredOutUs = 4_000_000L,
            maxSourceUs = 10_000_000L
        )
        assertEquals(1_000_000L, inUs)
        assertEquals(4_000_000L, outUs)
    }

    @Test
    fun `clampTrimRange enforces minimum duration`() {
        val minUs = 200_000L
        val (inUs, outUs) = TrimLogic.clampTrimRange(
            desiredInUs = 1_000_000L,
            desiredOutUs = 1_050_000L, // only 50ms apart
            maxSourceUs = 10_000_000L,
            minDurationUs = minUs
        )
        assertEquals(1_000_000L, inUs)
        assertEquals(1_200_000L, outUs)
    }

    @Test
    fun `clampTrimRange clamps negative inUs and excessive outUs`() {
        val (inUs, outUs) = TrimLogic.clampTrimRange(
            desiredInUs = -500_000L,
            desiredOutUs = 25_000_000L,
            maxSourceUs = 10_000_000L
        )
        assertEquals(0L, inUs)
        assertEquals(10_000_000L, outUs)
    }

    @Test
    fun `createTrimmedTimeMap preserves existing speed ratio`() {
        val original = TimeMap(
            inUs = 0L,
            outUs = 5_000_000L,
            speedNum = 3,
            speedDen = 2
        )
        val trimmed = TrimLogic.createTrimmedTimeMap(
            currentMap = original,
            clipDurationMs = 5000L,
            newInUs = 1_000_000L,
            newOutUs = 3_000_000L
        )
        assertEquals(1_000_000L, trimmed.inUs)
        assertEquals(3_000_000L, trimmed.outUs)
        assertEquals(3, trimmed.speedNum)
        assertEquals(2, trimmed.speedDen)
    }
}
