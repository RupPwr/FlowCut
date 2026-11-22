package com.flowcut.app.engine.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreviewEventLogTest {

    @Before
    fun setUp() {
        PreviewEventLog.isEnabledForTest = true
        PreviewEventLog.resetForTest()
    }

    @Test
    fun testRingBufferWrappingAndFifo() {
        // Write 600 events (more than 512 capacity)
        for (i in 0 until 600) {
            PreviewEventLog.log(
                eventType = PreviewEventType.SEEK_ENTER,
                arg0 = i.toLong(),
                reason = "event_$i",
                timestampMs = 1000L + i
            )
        }

        // Dump last 100 events
        val last100 = PreviewEventLog.dumpLastEvents(100)
        assertEquals(100, last100.size)

        // The oldest of the last 100 should be event 500, and latest should be event 599
        assertEquals(500L, last100.first().arg0)
        assertEquals("event_500", last100.first().reason)
        assertEquals(599L, last100.last().arg0)
        assertEquals("event_599", last100.last().reason)

        // Dump last 512 events (full capacity)
        val full = PreviewEventLog.dumpLastEvents(512)
        assertEquals(512, full.size)
        assertEquals(88L, full.first().arg0)
        assertEquals(599L, full.last().arg0)

        // Verify formatted output contains the expected lines
        val sb = StringBuilder()
        PreviewEventLog.dumpLastEventsFormatted(sb, count = 10)
        val lines = sb.toString().trim().lines()
        assertEquals(10, lines.size)
        assertTrue(lines.last().contains("event_599"))
    }

    @Test
    fun testBlackoutTracking() {
        // Initially no blackout
        assertEquals(0L, PreviewEventLog.totalConsecutiveBlackoutMs)

        // First blackout clear branch
        PreviewEventLog.onBlackoutClearBranch("clips_empty", "clipCount=0")
        assertEquals(0L, PreviewEventLog.totalConsecutiveBlackoutMs)

        // Second blackout clear branch 100ms later
        val dump1 = PreviewEventLog.dumpLastEvents(10)
        assertEquals(1, dump1.size)
        assertEquals(PreviewEventType.BLACKOUT_CLEAR, dump1[0].eventType)

        // Video drawn clears blackout
        PreviewEventLog.onVideoDrawn()
        assertEquals(0L, PreviewEventLog.totalConsecutiveBlackoutMs)

        val dump2 = PreviewEventLog.dumpLastEvents(10)
        assertEquals(2, dump2.size)
        assertEquals(PreviewEventType.BLACKOUT_END, dump2[1].eventType)
    }

    @Test
    fun testFlushRateCounterUnderThreshold() {
        var warnCalled = false
        val counter = FlushRateCounter(
            windowMs = 1000L,
            thresholdPerSec = 3,
            warnThrottleMs = 1000L,
            onWarnCallback = { _, _ -> warnCalled = true }
        )

        // 3 flushes within 1s is <= threshold (3 per sec), should NOT trigger warn
        assertFalse(counter.recordFlush("scrub_backward", nowMs = 100L))
        assertFalse(counter.recordFlush("scrub_backward", nowMs = 200L))
        assertFalse(counter.recordFlush("scrub_backward", nowMs = 300L))
        assertFalse(warnCalled)
    }

    @Test
    fun testFlushRateCounterOverThresholdAndThrottled() {
        var warnCount = 0
        var reportedCount = 0
        var reportedReasons = ""
        val counter = FlushRateCounter(
            windowMs = 1000L,
            thresholdPerSec = 3,
            warnThrottleMs = 1000L,
            onWarnCallback = { count, reasons ->
                warnCount++
                reportedCount = count
                reportedReasons = reasons
            }
        )

        // 4 flushes within 1s exceeds threshold 3
        counter.recordFlush("scrub_jump", nowMs = 100L)
        counter.recordFlush("scrub_jump", nowMs = 200L)
        counter.recordFlush("split_reuse", nowMs = 300L)
        val warned = counter.recordFlush("scrub_jump", nowMs = 400L)

        assertTrue(warned)
        assertEquals(1, warnCount)
        assertEquals(4, reportedCount)
        assertTrue(reportedReasons.contains("scrub_jump: 3"))
        assertTrue(reportedReasons.contains("split_reuse: 1"))

        // 5th flush at 500ms (within 1s throttle) should NOT trigger a second warning
        val warnedThrottled = counter.recordFlush("scrub_jump", nowMs = 500L)
        assertFalse(warnedThrottled)
        assertEquals(1, warnCount)

        // Flush at 1500ms (> 1000ms throttle) with high rate in [500ms..1500ms] should warn again
        counter.recordFlush("seek_scrub", nowMs = 1400L)
        counter.recordFlush("seek_scrub", nowMs = 1450L)
        val warnedAfterThrottle = counter.recordFlush("seek_scrub", nowMs = 1500L)
        assertTrue(warnedAfterThrottle)
        assertEquals(2, warnCount)
    }
}
