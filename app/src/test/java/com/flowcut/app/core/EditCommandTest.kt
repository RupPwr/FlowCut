package com.flowcut.app.core.edit

import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.model.Transition
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.timeline.SnapshotHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EditCommandTest {

    private fun createClip(id: String, durationMs: Long): Clip {
        return Clip(
            id = id,
            path = "/videos/$id.mp4",
            durationMs = durationMs,
            width = 1080,
            height = 1920,
            rotation = 0
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Batch import creates a single undo step
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testBatchImportCreatesSingleUndoStep() = runBlocking {
        val holder = SnapshotHolder()
        val controller = EditorController(holder = holder, defaultDispatcher = Dispatchers.Unconfined)

        val batch = listOf(
            createClip("c0", 3000L),
            createClip("c1", 4000L),
            createClip("c2", 5000L)
        )

        val success = controller.dispatch(ImportClipsCommand(batch))
        assertTrue("Batch import should succeed", success)
        assertEquals(3, controller.currentProject.clips.size)
        assertEquals(2, controller.currentProject.transitions.size)
        assertEquals(3, holder.current.project.clips.size)
        assertTrue(controller.canUndo)

        // Single undo must revert the entire batch import back to empty
        val undone = controller.undo()
        assertTrue(undone)
        assertEquals(0, controller.currentProject.clips.size)
        assertEquals(0, controller.currentProject.transitions.size)
        assertEquals(0, holder.current.project.clips.size)
        assertFalse(controller.canUndo)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Clip deletion maintains transitions.size == maxOf(0, clips.size - 1)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testClipDeletionMaintainsTransitionInvariant() = runBlocking {
        val initialClips = listOf(
            createClip("c0", 5000L),
            createClip("c1", 4000L),
            createClip("c2", 6000L)
        )
        val initialTransitions = listOf(
            Transition(TransitionType.FADE, 1000L),
            Transition(TransitionType.SLIDE, 800L)
        )
        val project = Project(clips = initialClips, transitions = initialTransitions)
        val controller = EditorController(
            initialProject = project,
            defaultDispatcher = Dispatchers.Unconfined
        )

        // Delete middle clip (index 1)
        val delMiddle = controller.dispatch(DeleteClipCommand(1))
        assertTrue(delMiddle)
        assertEquals(2, controller.currentProject.clips.size)
        assertEquals(1, controller.currentProject.transitions.size)
        assertEquals(maxOf(0, controller.currentProject.clips.size - 1), controller.currentProject.transitions.size)
        // Flanking transitions removed, replaced with NONE transition between c0 and c2
        assertEquals(TransitionType.NONE, controller.currentProject.transitions[0].type)
        assertEquals(0L, controller.currentProject.transitions[0].durationMs)
        assertEquals("c0", controller.currentProject.clips[0].id)
        assertEquals("c2", controller.currentProject.clips[1].id)

        // Delete first clip (index 0)
        val delFirst = controller.dispatch(DeleteClipCommand(0))
        assertTrue(delFirst)
        assertEquals(1, controller.currentProject.clips.size)
        assertEquals(0, controller.currentProject.transitions.size)
        assertEquals("c2", controller.currentProject.clips[0].id)

        // Delete the last remaining clip
        val delLast = controller.dispatch(DeleteClipCommand(0))
        assertTrue(delLast)
        assertEquals(0, controller.currentProject.clips.size)
        assertEquals(0, controller.currentProject.transitions.size)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. undo() and redo() restore exact project state and update SnapshotHolder.current
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testUndoRedoRestoresStateAndUpdateHolder() = runBlocking {
        val holder = SnapshotHolder()
        val c0 = createClip("c0", 5000L)
        val c1 = createClip("c1", 4000L)
        val p0 = Project(clips = listOf(c0), transitions = emptyList())

        val controller = EditorController(
            holder = holder,
            initialProject = p0,
            defaultDispatcher = Dispatchers.Unconfined
        )

        // Step 1: Import c1
        controller.dispatch(ImportClipsCommand(listOf(c1)))
        val p1 = controller.currentProject
        assertEquals(2, p1.clips.size)
        assertEquals(p1, holder.current.project)

        // Step 2: Set transition between c0 and c1
        controller.dispatch(SetTransitionCommand(0, TransitionType.FADE, 1000L))
        val p2 = controller.currentProject
        assertEquals(TransitionType.FADE, p2.transitions[0].type)
        assertEquals(1000L, p2.transitions[0].durationMs)
        assertEquals(p2, holder.current.project)

        // Undo Step 2 -> p1
        assertTrue(controller.undo())
        assertEquals(p1, controller.currentProject)
        assertEquals(p1, holder.current.project)

        // Undo Step 1 -> p0
        assertTrue(controller.undo())
        assertEquals(p0, controller.currentProject)
        assertEquals(p0, holder.current.project)
        assertFalse(controller.canUndo)

        // Redo -> p1
        assertTrue(controller.redo())
        assertEquals(p1, controller.currentProject)
        assertEquals(p1, holder.current.project)

        // Redo -> p2
        assertTrue(controller.redo())
        assertEquals(p2, controller.currentProject)
        assertEquals(p2, holder.current.project)
        assertFalse(controller.canRedo)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. coalesceKey merges consecutive slider/live-commit commands into 1 undo step
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testCoalesceKeyMergesConsecutiveCommandsIntoSingleUndo() = runBlocking {
        val clip = createClip("c0", 6000L)
        val initialProject = Project(clips = listOf(clip), transitions = emptyList())
        val controller = EditorController(
            initialProject = initialProject,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val coalesceKey = "speed_slider_clip_0"

        // Simulate 4 successive slider updates during a single drag gesture
        controller.dispatch(
            SetTimeMapCommand(
                clipIndex = 0,
                timeMap = TimeMap(inUs = 0L, outUs = msToUs(6000L), speedNum = 1, speedDen = 1),
                coalesceKey = coalesceKey
            )
        )
        controller.dispatch(
            SetTimeMapCommand(
                clipIndex = 0,
                timeMap = TimeMap(inUs = 0L, outUs = msToUs(6000L), speedNum = 2, speedDen = 1),
                coalesceKey = coalesceKey
            )
        )
        controller.dispatch(
            SetTimeMapCommand(
                clipIndex = 0,
                timeMap = TimeMap(inUs = 0L, outUs = msToUs(6000L), speedNum = 3, speedDen = 1),
                coalesceKey = coalesceKey
            )
        )
        controller.dispatch(
            SetTimeMapCommand(
                clipIndex = 0,
                timeMap = TimeMap(inUs = 0L, outUs = msToUs(6000L), speedNum = 4, speedDen = 1),
                coalesceKey = coalesceKey
            )
        )

        // Current clip has speed 4x -> timeline duration 6000ms / 4 = 1500ms
        assertEquals(1500L, controller.currentProject.clips[0].durationMs)
        assertEquals(1, controller.undoStack.undoCount) // Only 1 entry on undo stack!

        // Single undo must jump all the way back to initial project (6000ms, null timeMap)
        assertTrue(controller.undo())
        assertEquals(6000L, controller.currentProject.clips[0].durationMs)
        assertNull(controller.currentProject.clips[0].timeMap)
        assertFalse(controller.canUndo)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. 15-minute limit rejection leaves project & undo stack untouched
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testFifteenMinuteLimitRejectionLeavesStateUntouched() = runBlocking {
        // Create project with 14 minutes of clips
        val initialClip = createClip("c0", 14 * 60_000L)
        val initialProject = Project(clips = listOf(initialClip), transitions = emptyList())

        var rejectMessage: String? = null
        val controller = EditorController(
            initialProject = initialProject,
            defaultDispatcher = Dispatchers.Unconfined,
            onReject = { reason -> rejectMessage = reason }
        )

        // Try importing another 2-minute clip (total = 16 minutes > 15 minutes limit)
        val excessClip = createClip("c1", 2 * 60_000L)
        val success = controller.dispatch(ImportClipsCommand(listOf(excessClip)))

        assertFalse("Command exceeding 15 minutes must be rejected", success)
        assertNotNull(rejectMessage)
        assertTrue(rejectMessage!!.contains("15 minutes"))
        assertEquals(rejectMessage, controller.lastRejectionReason)

        // State and UndoStack must be completely untouched
        assertEquals(1, controller.currentProject.clips.size)
        assertEquals(14 * 60_000L, controller.currentProject.clips[0].durationMs)
        assertFalse("Undo stack must remain empty", controller.canUndo)
        assertEquals(1, controller.holder.current.project.clips.size)
    }

    // ---------------------------------------------------------------------------------------------
    // 6. UndoStack 30-step cap
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testUndoStackThirtyStepCap() {
        val undoStack = UndoStack(maxCapacity = 30)

        // Push 40 distinct projects
        for (i in 0 until 40) {
            val project = Project(
                clips = listOf(createClip("clip_$i", 1000L + i)),
                transitions = emptyList()
            )
            undoStack.push(project, coalesceKey = null)
        }

        assertTrue(undoStack.canUndo)
        assertEquals(30, undoStack.undoCount)

        // Should be able to undo exactly 30 times
        var current = Project(clips = listOf(createClip("final", 5000L)), transitions = emptyList())
        var undoCount = 0
        while (undoStack.canUndo) {
            val prev = undoStack.undo(current)
            assertNotNull(prev)
            current = prev!!
            undoCount++
        }

        assertEquals(30, undoCount)
        assertFalse(undoStack.canUndo)
        assertNull(undoStack.undo(current))
    }

    // ---------------------------------------------------------------------------------------------
    // 7. PausesPlayback hook and Debounced Save Callback
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testPausesPlaybackAndDebouncedSave() = runBlocking {
        val pauseInvoked = AtomicBoolean(false)
        val saveCounter = AtomicInteger(0)

        val controller = EditorController(
            defaultDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            saveDebounceMs = 10L,
            onPausePlayback = { pauseInvoked.set(true) },
            projectSaver = { saveCounter.incrementAndGet() }
        )

        val clip = createClip("c0", 3000L)
        controller.dispatch(ImportClipsCommand(clip))

        assertTrue("Command with pausesPlayback=true must invoke pause hook", pauseInvoked.get())

        // Allow debounce delay to fire
        delay(50L)
        assertTrue("Project saver must be invoked", saveCounter.get() >= 1)
    }
}

