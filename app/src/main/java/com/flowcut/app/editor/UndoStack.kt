package com.flowcut.app.editor

import com.flowcut.app.model.Project
import com.flowcut.app.model.UNDO_LIMIT

class UndoStack(private val maxCapacity: Int = UNDO_LIMIT) {
    private val undoList = ArrayDeque<Project>()
    private val redoList = ArrayDeque<Project>()

    val canUndo: Boolean
        get() = undoList.isNotEmpty()

    val canRedo: Boolean
        get() = redoList.isNotEmpty()

    fun push(project: Project) {
        if (undoList.size >= maxCapacity) {
            undoList.removeFirst()
        }
        undoList.addLast(project)
        redoList.clear()
    }

    fun undo(current: Project): Project? {
        if (undoList.isEmpty()) return null
        val previous = undoList.removeLast()
        redoList.addLast(current)
        return previous
    }

    fun redo(current: Project): Project? {
        if (redoList.isEmpty()) return null
        val next = redoList.removeLast()
        undoList.addLast(current)
        return next
    }

    fun clear() {
        undoList.clear()
        redoList.clear()
    }
}

