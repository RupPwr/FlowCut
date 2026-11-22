package com.flowcut.app.engine.perf

import android.util.Log
import com.flowcut.app.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks decoder flushes per second (Step 16, Step B).
 * If > 3 flushes/sec, logs a WARN line under tag "PreviewEvents" at most once per second.
 */
class FlushRateCounter(
    val windowMs: Long = 1000L,
    val thresholdPerSec: Int = 3,
    val warnThrottleMs: Long = 1000L,
    private val onWarnCallback: ((count: Int, reasons: String) -> Unit)? = null
) {
    private val capacity = 128
    private val timestamps = LongArray(capacity)
    private val reasons = Array<String?>(capacity) { null }
    private val writeHead = AtomicLong(0L)
    @Volatile private var lastWarnTimeMs: Long = 0L

    fun recordFlush(reason: String?, nowMs: Long = PreviewEventLog.currentMonotonicMs()): Boolean {
        if (!PreviewEventLog.isEnabled) return false

        val idx = (writeHead.getAndIncrement() and (capacity - 1).toLong()).toInt()
        timestamps[idx] = nowMs
        reasons[idx] = reason

        val head = writeHead.get()
        val minTime = nowMs - windowMs
        var count = 0
        val countsByReason = mutableMapOf<String, Int>()

        val checkCount = minOf(head, capacity.toLong()).toInt()
        for (i in 0 until checkCount) {
            val checkIdx = ((head - 1 - i) and (capacity - 1).toLong()).toInt()
            val t = timestamps[checkIdx]
            if (t in minTime..nowMs) {
                count++
                val r = reasons[checkIdx] ?: "unspecified"
                countsByReason[r] = (countsByReason[r] ?: 0) + 1
            } else if (t < minTime) {
                break
            }
        }

        if (count > thresholdPerSec) {
            if (lastWarnTimeMs == 0L || nowMs - lastWarnTimeMs >= warnThrottleMs) {
                lastWarnTimeMs = nowMs
                val topReasons = countsByReason.entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .joinToString(", ") { "${it.key}: ${it.value}" }
                if (onWarnCallback != null) {
                    onWarnCallback.invoke(count, topReasons)
                } else {
                    try {
                        Log.w("PreviewEvents", "Decoder flush rate warning: $count flushes/sec (> $thresholdPerSec/s). Top reasons: $topReasons")
                    } catch (_: Throwable) {
                        System.err.println("PreviewEvents: Decoder flush rate warning: $count flushes/sec (> $thresholdPerSec/s). Top reasons: $topReasons")
                    }
                }
                return true
            }
        }
        return false
    }

    fun reset() {
        writeHead.set(0L)
        lastWarnTimeMs = 0L
        for (i in 0 until capacity) {
            timestamps[i] = 0L
            reasons[i] = null
        }
    }
}
