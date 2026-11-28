package com.flowcut.app.editor

import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.engine.AspectRatioMode
import com.flowcut.app.engine.ClipBackground
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.ClipTransform
import com.flowcut.app.engine.OverlayItem
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

/**
 * Atomic snapshot of all undoable editor state. Storing every sidecar map, layer list,
 * aspect ratio, and junction transition mode together guarantees 1:1 stack alignment
 * and uniform capacity eviction across undo/redo operations.
 */
data class EditorUndoSnapshot(
    val project: Project,
    val timeMaps: Map<String, TimeMap> = emptyMap(),
    val volumes: Map<String, Float> = emptyMap(),
    val transforms: Map<String, ClipTransform> = emptyMap(),
    val colorAdjusts: Map<String, ClipColorAdjust> = emptyMap(),
    val backgrounds: Map<String, ClipBackground> = emptyMap(),
    val layerItems: List<OverlayItem> = emptyList(),
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_9_16,
    val junctionTransitionModes: Map<Int, Int> = emptyMap()
) {
    fun defensiveCopy(): EditorUndoSnapshot = EditorUndoSnapshot(
        project = project,
        timeMaps = HashMap(timeMaps),
        volumes = HashMap(volumes),
        transforms = transforms.mapValuesTo(HashMap(transforms.size)) { (_, v) -> v.copy() },
        colorAdjusts = HashMap(colorAdjusts),
        backgrounds = HashMap(backgrounds),
        layerItems = ArrayList(layerItems),
        aspectRatioMode = aspectRatioMode,
        junctionTransitionModes = HashMap(junctionTransitionModes)
    )
}

class EditorUndoHistory(private val maxCapacity: Int = UNDO_LIMIT) {
    private val undoList = ArrayDeque<EditorUndoSnapshot>()
    private val redoList = ArrayDeque<EditorUndoSnapshot>()

    val canUndo: Boolean
        get() = undoList.isNotEmpty()

    val canRedo: Boolean
        get() = redoList.isNotEmpty()

    val undoSize: Int
        get() = undoList.size

    val redoSize: Int
        get() = redoList.size

    fun push(snapshot: EditorUndoSnapshot) {
        if (maxCapacity <= 0) return
        while (undoList.size >= maxCapacity) {
            undoList.removeFirst()
        }
        undoList.addLast(snapshot.defensiveCopy())
        redoList.clear()
    }

    fun undo(current: EditorUndoSnapshot): EditorUndoSnapshot? {
        if (undoList.isEmpty()) return null
        val previous = undoList.removeLast()
        redoList.addLast(current.defensiveCopy())
        return previous
    }

    fun redo(current: EditorUndoSnapshot): EditorUndoSnapshot? {
        if (redoList.isEmpty()) return null
        val next = redoList.removeLast()
        while (undoList.size >= maxCapacity && maxCapacity > 0) {
            undoList.removeFirst()
        }
        undoList.addLast(current.defensiveCopy())
        return next
    }

    fun clear() {
        undoList.clear()
        redoList.clear()
    }
}


