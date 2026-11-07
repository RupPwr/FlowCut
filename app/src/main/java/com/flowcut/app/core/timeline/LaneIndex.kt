package com.flowcut.app.core.timeline

import com.flowcut.app.core.model.Lane

fun interface ComparisonCounter {
    fun inc()
}

class LaneIndex(lane: Lane) {
    val n: Int = lane.items.size
    val startMs: LongArray = LongArray(n) { lane.items[it].startMs }
    val endMs: LongArray = LongArray(n) { lane.items[it].startMs + lane.items[it].durationMs }

    /**
     * Returns the index of the active item at [tMs] (such that startMs[i] <= tMs < endMs[i]),
     * or -1 if no item is active at [tMs].
     */
    fun indexAt(tMs: Long, counter: ComparisonCounter? = null): Int {
        if (n == 0) return -1
        var lo = 0
        var hi = n - 1
        var candidate = -1
        while (lo <= hi) {
            counter?.inc()
            val mid = (lo + hi) ushr 1
            if (startMs[mid] <= tMs) {
                candidate = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (candidate != -1) {
            counter?.inc()
            if (tMs < endMs[candidate]) {
                return candidate
            }
        }
        return -1
    }

    /**
     * Smallest index i such that endMs[i] > tMs, or n if none.
     */
    fun firstEndingAfter(tMs: Long, counter: ComparisonCounter? = null): Int {
        var lo = 0
        var hi = n - 1
        var ans = n
        while (lo <= hi) {
            counter?.inc()
            val mid = (lo + hi) ushr 1
            if (endMs[mid] > tMs) {
                ans = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }

    /**
     * Largest index i such that startMs[i] < tMs, or -1 if none.
     */
    fun lastStartingBefore(tMs: Long, counter: ComparisonCounter? = null): Int {
        var lo = 0
        var hi = n - 1
        var ans = -1
        while (lo <= hi) {
            counter?.inc()
            val mid = (lo + hi) ushr 1
            if (startMs[mid] < tMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

