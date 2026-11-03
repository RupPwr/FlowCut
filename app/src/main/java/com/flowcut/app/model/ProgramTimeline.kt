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

class ProgramTimeline(val project: Project) {
    val n = project.clips.size
    val dur = LongArray(n) { project.clips[it].durationMs }
    val d = LongArray(if (n > 1) n - 1 else 0) {
        if (project.transitions[it].type != TransitionType.NONE) project.transitions[it].durationMs else 0L
    }

    val start = LongArray(n)
    val end = LongArray(n)
    val total: Long

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

    fun resolve(t: Long): FrameSpec {
        if (n == 0) {
            return FrameSpec(0, 0L, null, null, TransitionType.NONE, 0f)
        }
        val tt = t.coerceIn(0L, maxOf(0L, total - 1L))
        // 1. Overlap check first
        for (k in 0 until n - 1) {
            if (d[k] > 0L && tt >= start[k + 1] && tt < end[k]) {
                val p = (tt - start[k + 1]).toFloat() / d[k].toFloat()
                return FrameSpec(
                    aIndex = k,
                    aLocalMs = tt - start[k],
                    bIndex = k + 1,
                    bLocalMs = tt - start[k + 1],
                    type = project.transitions[k].type,
                    linearP = p.coerceIn(0f, 1f)
                )
            }
        }
        // 2. Single clip
        for (k in 0 until n) {
            if (tt >= start[k] && tt < end[k]) {
                return FrameSpec(
                    aIndex = k,
                    aLocalMs = tt - start[k],
                    bIndex = null,
                    bLocalMs = null,
                    type = TransitionType.NONE,
                    linearP = 0f
                )
            }
        }
        // Fallback for edge cases
        return FrameSpec(
            aIndex = n - 1,
            aLocalMs = dur[n - 1],
            bIndex = null,
            bLocalMs = null,
            type = TransitionType.NONE,
            linearP = 0f
        )
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
            type = project.transitions[j].type,
            durationMs = project.transitions[j].durationMs
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

            // Old transitions indices:
            // 0: clips[0]-clips[1]
            // ...
            // indexToDelete - 1: clips[indexToDelete-1]-clips[indexToDelete]
            // indexToDelete: clips[indexToDelete]-clips[indexToDelete+1]
            for (i in 0 until newClips.size - 1) {
                if (indexToDelete == 0) {
                    // Deleted first clip: old transitions[0] removed, rest shifted by 1
                    newTransitions.add(oldTransitions[i + 1])
                } else if (indexToDelete == project.clips.size - 1) {
                    // Deleted last clip: keep oldTransitions[0 until size - 1]
                    newTransitions.add(oldTransitions[i])
                } else {
                    // Deleted middle clip:
                    // Before indexToDelete - 1: unchanged
                    // At indexToDelete - 1 (junction between indexToDelete-1 and indexToDelete+1): new NONE transition
                    // After indexToDelete - 1: takes oldTransitions[i + 1]
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

            // If project had clips, junction between last old clip and first new clip is NONE
            var currentClipCount = project.clips.size
            if (currentClipCount == 0) {
                currentClipCount = 1
            }
            while (newTransitions.size < newClips.size - 1) {
                newTransitions.add(Transition(TransitionType.NONE, 0L))
            }
            return Project(newClips, newTransitions)
        }
    }
}
