package com.flowcut.app.engine.perf

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityGovernorTest {

    @Test
    fun testInitialLevelIsFull() {
        val governor = QualityGovernor()
        assertEquals(Level.FULL, governor.currentLevel)
    }

    @Test
    fun testInstantDropToReducedOnModerateThermal() {
        val governor = QualityGovernor()
        val level = governor.update(
            nowMs = 1_000L,
            moderate = true,
            severe = false,
            headroom = Float.NaN,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.REDUCED, level)
        assertEquals(Level.REDUCED, governor.currentLevel)
    }

    @Test
    fun testInstantDropToReducedOnHighLateFrames() {
        val governor = QualityGovernor()
        val level = governor.update(
            nowMs = 1_000L,
            moderate = false,
            severe = false,
            headroom = Float.NaN,
            lateFramePct = 9.5f,
            memLow = false
        )
        assertEquals(Level.REDUCED, level)
    }

    @Test
    fun testInstantDropToReducedOnHeadroomNearThreshold() {
        val governor = QualityGovernor()
        val level = governor.update(
            nowMs = 1_000L,
            moderate = false,
            severe = false,
            headroom = 0.96f,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.REDUCED, level)
    }

    @Test
    fun testInstantDropToMinimalOnSevereThermal() {
        val governor = QualityGovernor()
        val level = governor.update(
            nowMs = 1_000L,
            moderate = true,
            severe = true,
            headroom = Float.NaN,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.MINIMAL, level)
        assertEquals(Level.MINIMAL, governor.currentLevel)
    }

    @Test
    fun testInstantDropToMinimalOnMemoryPressure() {
        val governor = QualityGovernor()
        val level = governor.update(
            nowMs = 1_000L,
            moderate = false,
            severe = false,
            headroom = Float.NaN,
            lateFramePct = 0f,
            memLow = true
        )
        assertEquals(Level.MINIMAL, level)
    }

    @Test
    fun testInstantDropToMinimalOnHeadroomExceeded() {
        val governor = QualityGovernor()
        val level = governor.update(
            nowMs = 1_000L,
            moderate = false,
            severe = false,
            headroom = 1.05f,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.MINIMAL, level)
    }

    @Test
    fun testFloatNaNDoesNotTriggerDowngrade() {
        val governor = QualityGovernor()
        val level = governor.update(
            nowMs = 1_000L,
            moderate = false,
            severe = false,
            headroom = Float.NaN,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.FULL, level)
    }

    @Test
    fun testCalmRecoveryIsDelayedAndStepByStep() {
        val governor = QualityGovernor(upgradeAfterMs = 30_000L)

        // 1. Force drop to MINIMAL
        governor.update(
            nowMs = 1_000L,
            moderate = false,
            severe = true,
            headroom = Float.NaN,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.MINIMAL, governor.currentLevel)

        // 2. Signals return to calm at 2_000 ms. Recovery timer starts.
        governor.update(
            nowMs = 2_000L,
            moderate = false,
            severe = false,
            headroom = 0.5f,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.MINIMAL, governor.currentLevel)

        // 3. At 20_000 ms (18s calm), should still remain MINIMAL (< 30s)
        governor.update(
            nowMs = 20_000L,
            moderate = false,
            severe = false,
            headroom = 0.5f,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.MINIMAL, governor.currentLevel)

        // 4. At 32_001 ms (30.001s calm), should step up ONE level from MINIMAL to REDUCED
        governor.update(
            nowMs = 32_001L,
            moderate = false,
            severe = false,
            headroom = 0.5f,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.REDUCED, governor.currentLevel)

        // 5. Next calm check at 33_000 ms sets new calm timer for REDUCED -> FULL
        governor.update(
            nowMs = 33_000L,
            moderate = false,
            severe = false,
            headroom = 0.5f,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.REDUCED, governor.currentLevel)

        // 6. At 63_001 ms (30.001s additional calm), should step up to FULL
        governor.update(
            nowMs = 63_001L,
            moderate = false,
            severe = false,
            headroom = 0.5f,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.FULL, governor.currentLevel)
    }

    @Test
    fun testResetRestoresFullLevel() {
        val governor = QualityGovernor()
        governor.update(
            nowMs = 1_000L,
            moderate = true,
            severe = true,
            headroom = Float.NaN,
            lateFramePct = 0f,
            memLow = false
        )
        assertEquals(Level.MINIMAL, governor.currentLevel)

        governor.reset()
        assertEquals(Level.FULL, governor.currentLevel)
    }
}
