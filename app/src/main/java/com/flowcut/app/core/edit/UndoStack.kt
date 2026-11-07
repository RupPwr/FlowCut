package com.flowcut.app.core.edit

import com.flowcut.app.core.model.Project

/**
 * Undo and redo stack holding immutable Project snapshots (Section 9 & 15).
 * Enforces a maximum capacity limit (default 30 steps) and supports coalescing
 * consecutive commands with the same non-null coalesceKey (e.g. slider drags).
 */
class UndoStack(
    val maxCapacity: Int = DEFAULT_MAX_CAPACITY,
    initialProject: Project? = null
) {
    companion object {
        const val DEFAULT_MAX_CAPACITY = 30
    }

    private data class Entry(val project: Project, val coalesceKey: String?)

    private val undoList = ArrayDeque<Entry>()
    private val redoList = ArrayDeque<Project>()
    private var lastPushedCoalesceKey: String? = null

    var currentProject: Project? = initialProject
        internal set

    val canUndo: Boolean get() = undoList.isNotEmpty()
    val canRedo: Boolean get() = redoList.isNotEmpty()
    val undoCount: Int get() = undoList.size
    val redoCount: Int get() = redoList.size

    /**
     * Pushes [project] (the state before the edit) onto the undo stack.
     * If [coalesceKey] is non-null and matches the previously pushed key, the new snapshot
     * is coalesced (skipped) so all steps in the sequence undo as a single action.
     */
    fun push(project: Project, coalesceKey: String? = null) {
        redoList.clear()
        if (coalesceKey != null && coalesceKey == lastPushedCoalesceKey && undoList.isNotEmpty()) {
            // Coalesced into existing undo step; do not push intermediate state
            return
        }
        if (undoList.size >= maxCapacity) {
            undoList.removeFirst()
        }
        undoList.addLast(Entry(project, coalesceKey))
        lastPushedCoalesceKey = coalesceKey
    }

    /**
     * Undoes the last change, pushing [current] to the redo list and returning the previous Project.
     */
    fun undo(current: Project): Project? {
        if (undoList.isEmpty()) return null
        val entry = undoList.removeLast()
        redoList.addLast(current)
        lastPushedCoalesceKey = null
        currentProject = entry.project
        return entry.project
    }

    /**
     * Undoes using tracked [currentProject].
     */
    fun undo(): Project? {
        val curr = currentProject ?: return null
        return undo(curr)
    }

    /**
     * Redoes the next change, pushing [current] to the undo list and returning the re-applied Project.
     */
    fun redo(current: Project): Project? {
        if (redoList.isEmpty()) return null
        val next = redoList.removeLast()
        if (undoList.size >= maxCapacity) {
            undoList.removeFirst()
        }
        undoList.addLast(Entry(current, null))
        lastPushedCoalesceKey = null
        currentProject = next
        return next
    }

    /**
     * Redoes using tracked [currentProject].
     */
    fun redo(): Project? {
        val curr = currentProject ?: return null
        return redo(curr)
    }

    fun clear() {
        undoList.clear()
        redoList.clear()
        lastPushedCoalesceKey = null
    }
}

