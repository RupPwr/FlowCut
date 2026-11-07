package com.flowcut.app.core.timeline

import com.flowcut.app.core.model.Project
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs

class MainIndex(project: Project) {
    val n: Int = project.clips.size
    val durMs: LongArray = LongArray(n) { project.clips[it].durationMs }
    val dMs: LongArray = LongArray(maxOf(0, n - 1)) { project.transitions[it].durationMs }
    val types: Array<TransitionType> = Array(maxOf(0, n - 1)) { project.transitions[it].type }
    val startMs: LongArray = LongArray(n)
    val endMs: LongArray = LongArray(n)
    val totalMs: Long

    init {
        var s = 0L
        for (k in 0 until n) {
            startMs[k] = s
            endMs[k] = s + durMs[k]
            s = endMs[k] - (if (k < n - 1) dMs[k] else 0L)
        }
        totalMs = if (n == 0) 0L else endMs[n - 1]
    }

    fun lastStartAtOrBefore(tMs: Long): Int {
        var lo = 0
        var hi = n - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (startMs[mid] <= tMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun resolve(tUs: Long, out: ActiveSet) {
        if (n == 0) {
            out.aIndex = -1
            out.aLocalUs = 0L
            out.bIndex = -1
            out.bLocalUs = 0L
            out.type = TransitionType.NONE
            out.linearP = 0f
            return
        }

        val maxTotalUs = maxOf(0L, msToUs(totalMs) - 1L)
        val tt = tUs.coerceIn(0L, maxTotalUs)
        val tMs = usToMs(tt)
        val k = lastStartAtOrBefore(tMs)

        if (k > 0 && tMs < endMs[k - 1]) {
            // Overlap: transition (k - 1) is active
            out.aIndex = k - 1
            out.aLocalUs = tt - msToUs(startMs[k - 1])
            out.bIndex = k
            out.bLocalUs = tt - msToUs(startMs[k])
            out.type = types[k - 1]
            val transDurUs = msToUs(dMs[k - 1])
            out.linearP = if (transDurUs > 0L) {
                ((tt - msToUs(startMs[k])).toFloat() / transDurUs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        } else {
            // Solo playback on clip k
            out.aIndex = k
            out.aLocalUs = tt - msToUs(startMs[k])
            out.bIndex = -1
            out.bLocalUs = 0L
            out.type = TransitionType.NONE
            out.linearP = 0f
        }
    }
}

