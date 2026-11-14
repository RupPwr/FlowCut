package com.flowcut.app.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FrameGateTest {

    @Test
    fun testArmResetsStaleSignal() {
        val gate = FrameGate()
        gate.signal() // Stale signal from previous operation
        gate.arm()    // Arm should reset available to false

        val ok = gate.await(40L)
        assertFalse("Stale signal should not satisfy await after arm()", ok)
    }

    @Test
    fun testSignalWakesAwait() {
        val gate = FrameGate()
        gate.arm()

        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            scheduler.schedule({
                gate.signal()
            }, 30L, TimeUnit.MILLISECONDS)

            val ok = gate.await(1000L)
            assertTrue("Signal should wake await and return true", ok)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun testAwaitTimesOutWithoutSignal() {
        val gate = FrameGate()
        gate.arm()

        val start = System.currentTimeMillis()
        val ok = gate.await(50L)
        val elapsed = System.currentTimeMillis() - start

        assertFalse("Await should return false on timeout", ok)
        assertTrue("Await should wait roughly the timeout duration", elapsed >= 35L)
    }

    @Test
    fun testExportFrameTimeoutException() {
        val ex = ExportFrameTimeoutException(42)
        assertEquals(42, ex.frameIndex)
        assertTrue(ex.message?.contains("42") == true)
    }
}
