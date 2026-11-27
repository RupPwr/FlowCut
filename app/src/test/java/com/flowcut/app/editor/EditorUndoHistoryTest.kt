package com.flowcut.app.editor

import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.engine.AspectRatioMode
import com.flowcut.app.engine.BackgroundType
import com.flowcut.app.engine.ClipBackground
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.ClipTransform
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.model.Clip
import com.flowcut.app.model.Project
import com.flowcut.app.model.UNDO_LIMIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorUndoHistoryTest {

    private fun sampleClip(id: String, durationMs: Long = 3000L): Clip = Clip(
        id = id,
        path = "/tmp/$id.mp4",
        durationMs = durationMs,
        width = 1080,
        height = 1920,
        rotation = 0,
        frameRate = 30
    )

    @Test
    fun testAtomicUndoRedoKeepsAllEditorDomainsAlignedIncludingAspectRatioAndTransitions() {
        val history = EditorUndoHistory(maxCapacity = UNDO_LIMIT)

        val clip1 = sampleClip("c1")
        val state0 = EditorUndoSnapshot(
            project = Project(clips = listOf(clip1)),
            timeMaps = mapOf("c1" to TimeMap(0L, 3_000_000L, 100, 100)),
            volumes = mapOf("c1" to 1.0f),
            transforms = mapOf("c1" to ClipTransform.DEFAULT),
            colorAdjusts = mapOf("c1" to ClipColorAdjust.DEFAULT),
            backgrounds = mapOf("c1" to ClipBackground.DEFAULT),
            layerItems = emptyList(),
            aspectRatioMode = AspectRatioMode.RATIO_9_16,
            junctionTransitionModes = emptyMap()
        )

        // Edit 1: Change aspect ratio to 16:9
        history.push(state0)
        val state1 = state0.copy(aspectRatioMode = AspectRatioMode.RATIO_16_9)

        // Edit 2: Adjust color clarity + volume + transition mode (without changing aspect ratio)
        history.push(state1)
        val overlay = OverlayItem(
            id = "ov1",
            type = LayerType.TEXT,
            laneIndex = 0,
            zIndex = 1,
            startUs = 0L,
            endUs = 1_500_000L,
            text = "Hello"
        )
        val state2 = state1.copy(
            volumes = mapOf("c1" to 0.5f),
            colorAdjusts = mapOf("c1" to ClipColorAdjust(clarity = 0.6f, sharpPlus = 0.4f)),
            backgrounds = mapOf("c1" to ClipBackground(type = BackgroundType.BLUR, blurLevel = 0.7f)),
            layerItems = listOf(overlay),
            junctionTransitionModes = mapOf(0 to 2)
        )

        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(2, history.undoSize)

        // Undo Edit 2 -> aspect ratio MUST stay 16:9 (not erroneously jump back to 9:16!)
        val afterUndo1 = history.undo(state2)
        assertNotNull(afterUndo1)
        assertEquals(AspectRatioMode.RATIO_16_9, afterUndo1!!.aspectRatioMode)
        assertEquals(1.0f, afterUndo1.volumes["c1"] ?: -1f, 0.0001f)
        assertEquals(0.0f, afterUndo1.colorAdjusts["c1"]?.clarity ?: -1f, 0.0001f)
        assertTrue(afterUndo1.layerItems.isEmpty())
        assertTrue(afterUndo1.junctionTransitionModes.isEmpty())
        assertTrue(history.canRedo)

        // Undo Edit 1 -> aspect ratio now returns to 9:16
        val afterUndo2 = history.undo(afterUndo1)
        assertNotNull(afterUndo2)
        assertEquals(AspectRatioMode.RATIO_9_16, afterUndo2!!.aspectRatioMode)
        assertFalse(history.canUndo)
        assertEquals(2, history.redoSize)

        // Redo Edit 1 -> 16:9
        val afterRedo1 = history.redo(afterUndo2)
        assertNotNull(afterRedo1)
        assertEquals(AspectRatioMode.RATIO_16_9, afterRedo1!!.aspectRatioMode)
        assertTrue(afterRedo1.layerItems.isEmpty())

        // Redo Edit 2 -> clarity/sharpPlus/overlay/transition restored atomically
        val afterRedo2 = history.redo(afterRedo1)
        assertNotNull(afterRedo2)
        assertEquals(AspectRatioMode.RATIO_16_9, afterRedo2!!.aspectRatioMode)
        assertEquals(0.6f, afterRedo2.colorAdjusts["c1"]?.clarity ?: -1f, 0.0001f)
        assertEquals(0.4f, afterRedo2.colorAdjusts["c1"]?.sharpPlus ?: -1f, 0.0001f)
        assertEquals(1, afterRedo2.layerItems.size)
        assertEquals(2, afterRedo2.junctionTransitionModes[0])
        assertFalse(history.canRedo)
    }

    @Test
    fun testDefensiveCopyProtectsSnapshotFromPostPushMutations() {
        val history = EditorUndoHistory(maxCapacity = UNDO_LIMIT)
        val mutableTimeMaps = mutableMapOf("c1" to TimeMap(0L, 5_000_000L, 100, 100))
        val mutableVolumes = mutableMapOf("c1" to 0.75f)
        val mutableLayers = mutableListOf(
            OverlayItem(id = "t1", type = LayerType.TEXT, laneIndex = 0, zIndex = 0, startUs = 0L, endUs = 1_000_000L, text = "Title")
        )

        val clip = sampleClip("c1", 5000L)
        history.push(
            EditorUndoSnapshot(
                project = Project(clips = listOf(clip)),
                timeMaps = mutableTimeMaps,
                volumes = mutableVolumes,
                layerItems = mutableLayers
            )
        )

        // Simulate deleteClip mutating live maps after pushing undo
        mutableTimeMaps.remove("c1")
        mutableVolumes.remove("c1")
        mutableLayers.clear()

        val restored = history.undo(
            EditorUndoSnapshot(
                project = Project(clips = emptyList()),
                timeMaps = mutableTimeMaps,
                volumes = mutableVolumes,
                layerItems = mutableLayers
            )
        )

        assertNotNull(restored)
        assertEquals(1, restored!!.project.clips.size)
        assertEquals(5_000_000L, restored.timeMaps["c1"]?.outUs)
        assertEquals(0.75f, restored.volumes["c1"] ?: -1f, 0.0001f)
        assertEquals(1, restored.layerItems.size)
    }

    @Test
    fun testCapacityLimitEvictsOldestEntryUniformlyAcrossAllFields() {
        val capacity = 30
        val history = EditorUndoHistory(maxCapacity = capacity)
        val modes = listOf(
            AspectRatioMode.RATIO_9_16,
            AspectRatioMode.RATIO_16_9,
            AspectRatioMode.RATIO_1_1,
            AspectRatioMode.RATIO_4_5
        )

        // Push 45 edits (exceeding UNDO_LIMIT of 30)
        for (i in 0 until 45) {
            val clip = sampleClip("c$i", durationMs = 1000L + i)
            history.push(
                EditorUndoSnapshot(
                    project = Project(clips = listOf(clip)),
                    volumes = mapOf("c$i" to (i / 100f)),
                    aspectRatioMode = modes[i % modes.size],
                    junctionTransitionModes = mapOf(0 to (i % 8))
                )
            )
        }

        assertEquals(capacity, history.undoSize)

        var current = EditorUndoSnapshot(project = Project(clips = listOf(sampleClip("c45"))))
        var poppedCount = 0
        var lastPoppedIndex = 45
        while (history.canUndo) {
            val prev = history.undo(current)!!
            val expectedIndex = lastPoppedIndex - 1
            assertEquals("c$expectedIndex", prev.project.clips.first().id)
            assertEquals(expectedIndex / 100f, prev.volumes["c$expectedIndex"] ?: -1f, 0.0001f)
            assertEquals(modes[expectedIndex % modes.size], prev.aspectRatioMode)
            assertEquals(expectedIndex % 8, prev.junctionTransitionModes[0])
            lastPoppedIndex = expectedIndex
            current = prev
            poppedCount++
        }

        assertEquals(capacity, poppedCount)
        assertEquals(15, lastPoppedIndex) // Oldest 15 (0..14) evicted, 15..44 retained
        assertNull(history.undo(current))
    }

    @Test
    fun testNewPushAfterUndoClearsRedoAndClearResetsBothStacks() {
        val history = EditorUndoHistory(maxCapacity = 10)
        val s0 = EditorUndoSnapshot(project = Project(clips = listOf(sampleClip("c0"))))
        val s1 = EditorUndoSnapshot(project = Project(clips = listOf(sampleClip("c1"))))
        val s2 = EditorUndoSnapshot(project = Project(clips = listOf(sampleClip("c2"))))

        history.push(s0)
        val undone = history.undo(s1)
        assertNotNull(undone)
        assertTrue(history.canRedo)

        // Pushing a new branch must clear redo stack
        history.push(s0)
        assertFalse(history.canRedo)
        assertNull(history.redo(s2))

        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(0, history.undoSize)
        assertEquals(0, history.redoSize)
    }
}
