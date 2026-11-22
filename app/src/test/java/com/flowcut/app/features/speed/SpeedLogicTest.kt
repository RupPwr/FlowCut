package com.flowcut.app.features.speed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeedLogicTest {

    @Test
    fun `speedToSlider and sliderToSpeed round trip at 1_0x snaps cleanly`() {
        val slider = SpeedLogic.speedToSlider(1.0f)
        val speed = SpeedLogic.sliderToSpeed(slider)
        assertEquals(1.0f, speed, 0.001f)
    }

    @Test
    fun `slider snaps magnetically near 1_0x`() {
        val speedNear = SpeedLogic.sliderToSpeed(0.36f)
        assertEquals(1.0f, speedNear, 0.001f)
    }

    @Test
    fun `speedToFraction calculates exact rationals`() {
        val (num1, den1) = SpeedLogic.speedToFraction(1.5f)
        assertEquals(150, num1)
        assertEquals(100, den1)

        val (num2, den2) = SpeedLogic.speedToFraction(0.5f)
        assertEquals(50, num2)
        assertEquals(100, den2)
    }

    @Test
    fun `preset speeds cover full 0_2 to 4_0 range`() {
        assertTrue(SpeedLogic.PRESET_SPEEDS.contains(0.2f))
        assertTrue(SpeedLogic.PRESET_SPEEDS.contains(1.0f))
        assertTrue(SpeedLogic.PRESET_SPEEDS.contains(4.0f))
    }
}
