package com.flowcut.app.features.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLogicTest {

    @Test
    fun `calculateTextAspect produces reasonable aspect ratios`() {
        // Single short line
        val aspect1 = TextLogic.calculateTextAspect("Hello")
        assertTrue(aspect1 in 0.8f..4.0f)

        // Multiple lines
        val aspectMulti = TextLogic.calculateTextAspect("Line 1\nLine 2\nLine 3\nLine 4")
        assertTrue(aspectMulti in 0.8f..4.0f)
    }

    @Test
    fun `preset colors contains at least 8 distinct colors`() {
        assertTrue(TextLogic.PRESET_COLORS.size >= 8)
        assertEquals(TextLogic.PRESET_COLORS.size, TextLogic.PRESET_COLORS.distinct().size)
    }
}
