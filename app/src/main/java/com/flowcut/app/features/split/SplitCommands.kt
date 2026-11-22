/**
 * Feature:     split
 * File role:   commands
 * Responsible: Pure timeline edit command for splitting a clip at a boundary
 * Depends on:  core/edit, core/model, core/time, features/split/SplitLogic
 * Used by:     SplitUi, Index
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.split

import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.model.Transition
import com.flowcut.app.core.model.TransitionType

/**
 * Pure command to split a clip at [clipIndex] into two clips with an inserted NONE transition.
 */
data class SplitClipCommand(
    val clipIndex: Int,
    val leftClip: Clip,
    val rightClip: Clip,
    override val label: String = "Split Clip",
    override val pausesPlayback: Boolean = true,
    override val coalesceKey: String? = null
) : Command {

    override fun apply(p: Project): Project {
        if (clipIndex !in p.clips.indices) return p

        val newClips = p.clips.toMutableList()
        newClips[clipIndex] = leftClip
        newClips.add(clipIndex + 1, rightClip)

        val newTransitions = ArrayList<Transition>(p.transitions.size + 1)
        for (i in 0 until clipIndex) {
            newTransitions.add(p.transitions[i])
        }
        newTransitions.add(Transition(TransitionType.NONE, 0L))
        for (i in clipIndex until p.transitions.size) {
            newTransitions.add(p.transitions[i])
        }

        return p.copy(clips = newClips, transitions = newTransitions)
    }
}
