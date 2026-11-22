package com.flowcut.app.features.filter

import com.flowcut.app.engine.ClipColorAdjust
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterLogicTest {

    @Test
    fun `getToolValue retrieves correct property`() {
        val adjust = ClipColorAdjust(
            brightness = 0.25f,
            contrast = -0.5f,
            saturation = 0.8f,
            sharpness = 0.4f
        )
        assertEquals(0.25f, FilterLogic.getToolValue(adjust, AdjustTool.BRIGHTNESS), 0.001f)
        assertEquals(-0.5f, FilterLogic.getToolValue(adjust, AdjustTool.CONTRAST), 0.001f)
        assertEquals(0.8f, FilterLogic.getToolValue(adjust, AdjustTool.SATURATION), 0.001f)
        assertEquals(0.4f, FilterLogic.getToolValue(adjust, AdjustTool.SHARPNESS), 0.001f)
    }

    @Test
    fun `withToolValue immutably updates single property`() {
        val initial = ClipColorAdjust()
        val updated = FilterLogic.withToolValue(initial, AdjustTool.EXPOSURE, 0.75f)

        assertEquals(0.75f, updated.exposure, 0.001f)
        assertEquals(0f, updated.brightness, 0.001f)
        assertEquals(0f, updated.contrast, 0.001f)
        assertEquals(0f, initial.exposure, 0.001f)
    }
}
