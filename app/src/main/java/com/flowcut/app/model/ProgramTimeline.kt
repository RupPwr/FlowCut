package com.flowcut.app.model

data class FrameSpec(
    val aIndex: Int,
    val aLocalMs: Long,
    val bIndex: Int?,
    val bLocalMs: Long?,
    val type: TransitionType,
    val linearP: Float
) {
    val isTransition: Boolean
        get() = bIndex != null && type != TransitionType.NONE
}

class FrameSpecMutable {
    var aIndex = 0
    var aLocalMs = 0L
    var bIndex = -1      // -1 means no transition
    var bLocalMs = 0L
    var type = TransitionType.NONE
    var linearP = 0f

    val isTransition: Boolean
        get() = bIndex != -1 && type != TransitionType.NONE

    fun copyFrom(other: FrameSpecMutable) {
        aIndex = other.aIndex
        aLocalMs = other.aLocalMs
        bIndex = other.bIndex
        bLocalMs = other.bLocalMs
        type = other.type
        linearP = other.linearP
    }
}

data class ClipLayoutInfo(
    val index: Int,
    val leftDp: Float,
    val rightDp: Float,
    val widthDp: Float
)

data class JunctionLayoutInfo(
    val index: Int,
    val centerX: Float,
    val bandWidth: Float,
    val type: TransitionType,
    val durationMs: Long
)

class ProgramTimeline(
    val clips: List<Clip>,
    val transitions: List<Transition>
) {
    constructor(project: Project) : this(project.clips, project.transitions)

    val project: Project = Project(clips, transitions)

    val n: Int = clips.size
    val dur: LongArray = LongArray(n) { clips[it].durationMs }
    val durMs: LongArray get() = dur

    val d: LongArray = LongArray(maxOf(0, n - 1)) {
        if (transitions[it].type != TransitionType.NONE) transitions[it].durationMs else 0L
    }
    val dMs: LongArray get() = d

    val start: LongArray = LongArray(n)
    val startMs: LongArray get() = start

    val end: LongArray = LongArray(n)
    val endMs: LongArray get() = end

    val total: Long
    val totalMs: Long get() = total

    init {
        if (n > 0) {
            start[0] = 0L
            for (k in 0 until n - 1) {
                start[k + 1] = start[k] + dur[k] - d[k]
            }
            for (k in 0 until n) {
                end[k] = start[k] + dur[k]
            }
            total = end[n - 1]
        } else {
            total = 0L
        }
    }

    /** Largest index k such that start[k] <= t (binary search O(log n)). */
    fun lastStartAtOrBefore(t: Long): Int {
        var lo = 0
        var hi = n - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (start[mid] <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Zero-allocation frame resolution into a reusable mutable object (Section 19.3). */
    fun resolve(t: Long, out: FrameSpecMutable) {
        if (n == 0) {
            out.aIndex = 0
            out.aLocalMs = 0L
            out.bIndex = -1
            out.bLocalMs = 0L
            out.type = TransitionType.NONE
            out.linearP = 0f
            return
        }
        val tt = t.coerceIn(0L, maxOf(0L, total - 1L))
        val k = lastStartAtOrBefore(tt)
        if (k > 0 && tt < end[k - 1]) {
            // Overlap: transition k-1 active
            out.aIndex = k - 1
            out.aLocalMs = tt - start[k - 1]
            out.bIndex = k
            out.bLocalMs = tt - start[k]
            out.type = transitions[k - 1].type
            val dVal = d[k - 1]
            out.linearP = if (dVal > 0L) {
                ((tt - start[k]).toFloat() / dVal.toFloat()).coerceIn(0f, 1f)
            } else 0f
        } else {
            out.aIndex = k
            out.aLocalMs = tt - start[k]
            out.bIndex = -1
            out.bLocalMs = 0L
            out.type = TransitionType.NONE
            out.linearP = 0f
        }
    }

    /** Immutable resolve for callers and existing unit tests. */
    fun resolve(t: Long): FrameSpec {
        val mutable = FrameSpecMutable()
        resolve(t, mutable)
        return FrameSpec(
            aIndex = mutable.aIndex,
            aLocalMs = mutable.aLocalMs,
            bIndex = if (mutable.bIndex == -1) null else mutable.bIndex,
            bLocalMs = if (mutable.bIndex == -1) null else mutable.bLocalMs,
            type = mutable.type,
            linearP = mutable.linearP
        )
    }

    /** Visible range of clips for virtualized Canvas drawing (Section 19.3 / 19.4). */
    fun visibleRange(scrollOffsetDp: Float, viewportWidthDp: Float): IntRange {
        if (n == 0) return IntRange.EMPTY
        val viewportHalfDp = viewportWidthDp / 2f
        val leftMs = ((scrollOffsetDp - viewportHalfDp) / DP_PER_SECOND * 1000f).toLong()
        val rightMs = leftMs + (viewportWidthDp / DP_PER_SECOND * 1000f).toLong()
        val k0 = maxOf(0, lastStartAtOrBefore(leftMs) - 1)
        val k1 = minOf(n - 1, lastStartAtOrBefore(rightMs) + 1)
        return k0..k1
    }

    fun maxDuration(j: Int): Long {
        if (j < 0 || j >= n - 1) return 0L
        val dInJ = if (j == 0) 0L else d[j - 1]
        val dOutJ1 = if (j + 1 == n - 1) 0L else d[j + 1]
        val limit1 = 2000L
        val limit2 = dur[j] - dInJ - 200L
        val limit3 = dur[j + 1] - dOutJ1 - 200L
        val maxMs = minOf(limit1, limit2, limit3)
        if (maxMs < 0L) return 0L
        return (maxMs / 100L) * 100L
    }

    fun getClipLayout(k: Int): ClipLayoutInfo {
        val dInK = if (k == 0) 0L else d[k - 1]
        val dOutK = if (k == n - 1) 0L else d[k]
        val left = (start[k] + dInK / 2f) / 1000f * DP_PER_SECOND
        val right = (end[k] - dOutK / 2f) / 1000f * DP_PER_SECOND
        return ClipLayoutInfo(k, left, right, right - left)
    }

    fun getJunctionLayout(j: Int): JunctionLayoutInfo {
        val centerX = (start[j + 1] + d[j] / 2f) / 1000f * DP_PER_SECOND
        val bandWidth = d[j] / 1000f * DP_PER_SECOND
        return JunctionLayoutInfo(
            index = j,
            centerX = centerX,
            bandWidth = bandWidth,
            type = transitions[j].type,
            durationMs = transitions[j].durationMs
        )
    }

    companion object {
        fun ease(x: Float): Float {
            val clamped = x.coerceIn(0f, 1f)
            return if (clamped < 0.5f) {
                2f * clamped * clamped
            } else {
                1f - ((-2f * clamped + 2f).let { it * it }) / 2f
            }
        }

        fun deleteClip(project: Project, indexToDelete: Int): Project {
            if (indexToDelete !in project.clips.indices) return project
            val newClips = project.clips.toMutableList()
            newClips.removeAt(indexToDelete)

            if (newClips.isEmpty()) {
                return Project(emptyList(), emptyList())
            }
            if (newClips.size == 1) {
                return Project(newClips, emptyList())
            }

            val newTransitions = mutableListOf<Transition>()
            val oldTransitions = project.transitions

            for (i in 0 until newClips.size - 1) {
                if (indexToDelete == 0) {
                    newTransitions.add(oldTransitions[i + 1])
                } else if (indexToDelete == project.clips.size - 1) {
                    newTransitions.add(oldTransitions[i])
                } else {
                    if (i < indexToDelete - 1) {
                        newTransitions.add(oldTransitions[i])
                    } else if (i == indexToDelete - 1) {
                        newTransitions.add(Transition(TransitionType.NONE, 0L))
                    } else {
                        newTransitions.add(oldTransitions[i + 1])
                    }
                }
            }
            return Project(newClips, newTransitions)
        }

        fun addClips(project: Project, addedClips: List<Clip>): Project {
            if (addedClips.isEmpty()) return project
            val newClips = project.clips + addedClips
            val newTransitions = project.transitions.toMutableList()

            while (newTransitions.size < newClips.size - 1) {
                newTransitions.add(Transition(TransitionType.NONE, 0L))
            }
            return Project(newClips, newTransitions)
        }
    }
}
