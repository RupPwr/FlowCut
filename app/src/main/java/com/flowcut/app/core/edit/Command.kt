package com.flowcut.app.core.edit

import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.model.Transition
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.usToMs

/**
 * Pure edit command interface (Section 9).
 * Every timeline modification must pass through an implementation of this interface.
 */
interface Command {
    val label: String
    val pausesPlayback: Boolean get() = true
    val coalesceKey: String? get() = null

    /**
     * Pure function returning the modified Project.
     * MUST NOT perform I/O, threading, or Android platform calls.
     */
    fun apply(p: Project): Project
}

/**
 * Batch imports one or more clips as a single undo step.
 */
data class ImportClipsCommand(
    val clips: List<Clip>,
    override val label: String = "Import Clips",
    override val pausesPlayback: Boolean = true,
    override val coalesceKey: String? = null
) : Command {
    constructor(clip: Clip, label: String = "Import Clip") : this(listOf(clip), label)

    override fun apply(p: Project): Project {
        if (clips.isEmpty()) return p
        val newClips = p.clips + clips
        val newTransitions = p.transitions.toMutableList()
        while (newTransitions.size < newClips.size - 1) {
            newTransitions.add(Transition(TransitionType.NONE, 0L))
        }
        return p.copy(clips = newClips, transitions = newTransitions)
    }
}

/**
 * Deletes a clip while preserving transition count invariants.
 * If an interior clip is deleted, its flanking transitions are removed and replaced
 * by a default NONE transition between the newly adjacent clips.
 */
data class DeleteClipCommand(
    val clipIndex: Int,
    override val label: String = "Delete Clip",
    override val pausesPlayback: Boolean = true,
    override val coalesceKey: String? = null
) : Command {
    override fun apply(p: Project): Project {
        if (clipIndex !in p.clips.indices) return p
        val newClips = p.clips.toMutableList()
        newClips.removeAt(clipIndex)

        if (newClips.isEmpty()) {
            return p.copy(clips = emptyList(), transitions = emptyList())
        }
        if (newClips.size == 1) {
            return p.copy(clips = newClips, transitions = emptyList())
        }

        val newTransitions = ArrayList<Transition>(newClips.size - 1)
        val oldTransitions = p.transitions

        for (i in 0 until newClips.size - 1) {
            if (clipIndex == 0) {
                newTransitions.add(oldTransitions[i + 1])
            } else if (clipIndex == p.clips.size - 1) {
                newTransitions.add(oldTransitions[i])
            } else {
                if (i < clipIndex - 1) {
                    newTransitions.add(oldTransitions[i])
                } else if (i == clipIndex - 1) {
                    newTransitions.add(Transition(TransitionType.NONE, 0L))
                } else {
                    newTransitions.add(oldTransitions[i + 1])
                }
            }
        }
        return p.copy(clips = newClips, transitions = newTransitions)
    }
}

/**
 * Sets or removes a transition at a given junction index.
 */
data class SetTransitionCommand(
    val junctionIndex: Int,
    val type: TransitionType,
    val durationMs: Long = 0L,
    override val label: String = "Set Transition",
    override val pausesPlayback: Boolean = true,
    override val coalesceKey: String? = null
) : Command {
    constructor(junctionIndex: Int) : this(junctionIndex, TransitionType.NONE, 0L, label = "Remove Transition")

    override fun apply(p: Project): Project {
        if (junctionIndex !in p.transitions.indices) return p
        val newTransitions = p.transitions.toMutableList()
        val effectiveDuration = if (type == TransitionType.NONE) 0L else durationMs
        newTransitions[junctionIndex] = Transition(type, effectiveDuration)
        return p.copy(transitions = newTransitions)
    }
}

/**
 * Sets the TimeMap (speed, trim range) for a clip, recalculating its timeline duration.
 */
data class SetTimeMapCommand(
    val clipIndex: Int,
    val timeMap: TimeMap,
    override val label: String = "Set Time Map",
    override val pausesPlayback: Boolean = true,
    override val coalesceKey: String? = null
) : Command {
    override fun apply(p: Project): Project {
        if (clipIndex !in p.clips.indices) return p
        val oldClip = p.clips[clipIndex]
        val newDurationMs = if (timeMap.speedNum == 0 && timeMap.inUs == timeMap.outUs) {
            oldClip.durationMs
        } else {
            usToMs(timeMap.timelineDurationUs())
        }
        val newClip = oldClip.copy(timeMap = timeMap, durationMs = newDurationMs)
        val newClips = p.clips.toMutableList()
        newClips[clipIndex] = newClip

        // Adjust adjacent transitions if needed so duration does not exceed min(d1, d2) / 2
        val newTransitions = p.transitions.toMutableList()
        for (j in maxOf(0, clipIndex - 1)..minOf(newTransitions.size - 1, clipIndex)) {
            val maxAllowed = minOf(newClips[j].durationMs, newClips[j + 1].durationMs) / 2L
            if (newTransitions[j].durationMs > maxAllowed) {
                newTransitions[j] = newTransitions[j].copy(durationMs = maxAllowed)
            }
        }

        return p.copy(clips = newClips, transitions = newTransitions)
    }
}

