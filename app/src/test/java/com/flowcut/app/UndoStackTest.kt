package com.flowcut.app

import com.flowcut.app.editor.UndoStack
import com.flowcut.app.model.Clip
import com.flowcut.app.model.Project
import com.flowcut.app.model.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {

    private fun dummyProject(clipCount: Int): Project {
        val clips = (0 until clipCount).map {
            Clip(id = "c$it", path = "", durationMs = 1000L, width = 1080, height = 1920, rotation = 0)
        }
        val transitions = if (clipCount > 1) {
            (0 until clipCount - 1).map { Transition() }
        } else emptyList()
        return Project(clips, transitions)
    }

    @Test
    fun testUndoRedoFlow() {
        val stack = UndoStack()
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)

        val p1 = dummyProject(1)
        val p2 = dummyProject(2)
        val p3 = dummyProject(3)

        stack.push(p1)
        assertTrue(stack.canUndo)
        assertFalse(stack.canRedo)

        stack.push(p2)
        assertEquals(p2, stack.undo(p3))
        assertTrue(stack.canRedo)
        assertTrue(stack.canUndo)

        assertEquals(p1, stack.undo(p2))
        assertFalse(stack.canUndo)
        assertTrue(stack.canRedo)

        assertEquals(p2, stack.redo(p1))
        assertTrue(stack.canUndo)
        assertTrue(stack.canRedo)

        assertEquals(p3, stack.redo(p2))
        assertFalse(stack.canRedo)
    }

    @Test
    fun testMax30Capacity() {
        val stack = UndoStack(maxCapacity = 30)
        for (i in 1..40) {
            stack.push(dummyProject(1))
        }
        var undoCount = 0
        var cur = dummyProject(1)
        while (stack.canUndo) {
            cur = stack.undo(cur)!!
            undoCount++
        }
        assertEquals(30, undoCount)
    }

    @Test
    fun testPushClearsRedo() {
        val stack = UndoStack()
        val p1 = dummyProject(1)
        val p2 = dummyProject(2)
        val p3 = dummyProject(3)

        stack.push(p1)
        stack.push(p2)
        stack.undo(p3)
        assertTrue(stack.canRedo)

        // New edit clears redo stack
        stack.push(dummyProject(4))
        assertFalse(stack.canRedo)
    }
}
