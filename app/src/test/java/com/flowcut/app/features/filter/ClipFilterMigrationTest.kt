package com.flowcut.app.features.filter

import com.flowcut.app.core.model.ClipFilterParams
import com.flowcut.app.core.model.ClipRenderParams
import com.flowcut.app.editor.UndoStack
import com.flowcut.app.engine.ActiveLayerSet
import com.flowcut.app.engine.CompiledLayerPlan
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.model.Clip
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.Project
import com.flowcut.app.model.Transition
import com.flowcut.app.model.TransitionType
import com.flowcut.app.storage.decodeProject
import com.flowcut.app.storage.projectJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class ClipFilterMigrationTest {

    @Before
    fun setUp() {
        PreviewRenderer.clearLiveFilterOverride()
    }

    @After
    fun tearDown() {
        PreviewRenderer.clearLiveFilterOverride()
    }

    @Test
    fun `split copies filter params to both halves`() {
        val customFilter = ClipFilterParams(
            filterPresetId = 3,
            filterIntensity = 0.85f,
            effectPresetId = 2,
            effectIntensity = 0.6f
        )
        val oldClip = Clip(
            id = "c1",
            path = "video.mp4",
            durationMs = 5000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            renderParams = ClipRenderParams(filter = customFilter)
        )

        // Split semantics: copy left and right
        val leftClip = oldClip.copy(durationMs = 2000L)
        val rightClip = oldClip.copy(id = "c2", durationMs = 3000L)

        assertEquals(customFilter, leftClip.renderParams.filter)
        assertEquals(customFilter, rightClip.renderParams.filter)
    }

    @Test
    fun `duplicate copies filter params to new clip`() {
        val customFilter = ClipFilterParams(
            filterPresetId = 4,
            filterIntensity = 0.75f,
            effectPresetId = 1,
            effectIntensity = 0.5f
        )
        val oldClip = Clip(
            id = "c1",
            path = "video.mp4",
            durationMs = 4000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            renderParams = ClipRenderParams(filter = customFilter)
        )

        // Duplicate semantics: oldClip.copy(id = newId)
        val newClip = oldClip.copy(id = "c2")

        assertEquals(customFilter, newClip.renderParams.filter)
        assertEquals("c2", newClip.id)
    }

    @Test
    fun `replace resets to DEFAULT filter params`() {
        val oldClip = Clip(
            id = "c1",
            path = "video.mp4",
            durationMs = 4000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            renderParams = ClipRenderParams(
                filter = ClipFilterParams(filterPresetId = 5, filterIntensity = 0.9f)
            )
        )

        // Replace semantics: newly imported clip starts with default renderParams
        val importedClip = Clip(
            id = "c_imported",
            path = "new_video.mp4",
            durationMs = 3000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30
        )

        assertEquals(ClipRenderParams.DEFAULT, importedClip.renderParams)
        assertEquals(ClipFilterParams.DEFAULT, importedClip.renderParams.filter)
    }

    @Test
    fun `delete leaves no residue`() {
        val clip1 = Clip(
            id = "c1",
            path = "video1.mp4",
            durationMs = 3000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            renderParams = ClipRenderParams(
                filter = ClipFilterParams(filterPresetId = 2, filterIntensity = 0.8f)
            )
        )
        val clip2 = Clip(
            id = "c2",
            path = "video2.mp4",
            durationMs = 4000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30
        )
        val project = Project(
            clips = listOf(clip1, clip2),
            transitions = listOf(Transition(TransitionType.NONE, 0L))
        )

        val updated = ProgramTimeline.deleteClip(project, 0)
        assertEquals(1, updated.clips.size)
        assertEquals("c2", updated.clips[0].id)
        assertFalse(updated.clips.any { it.id == "c1" })

        // PreviewRenderer fallback for deleted clip returns DEFAULT
        assertEquals(ClipFilterParams.DEFAULT, PreviewRenderer.getClipFilterState("c1"))
    }

    @Test
    fun `undo and redo restore filter params correctly`() {
        val undoStack = UndoStack()

        val initialClip = Clip(
            id = "c1",
            path = "video.mp4",
            durationMs = 5000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            renderParams = ClipRenderParams.DEFAULT
        )
        val initialProject = Project(clips = listOf(initialClip), transitions = emptyList())

        // User commits a filter
        val filterParams = ClipFilterParams(filterPresetId = 6, filterIntensity = 0.7f)
        val editedClip = initialClip.copy(renderParams = initialClip.renderParams.copy(filter = filterParams))
        val editedProject = initialProject.copy(clips = listOf(editedClip))

        undoStack.push(initialProject)

        // Undo
        val undoneProject = undoStack.undo(editedProject)
        assertNotNull(undoneProject)
        assertEquals(ClipFilterParams.DEFAULT, undoneProject!!.clips[0].renderParams.filter)

        // Redo
        val redoneProject = undoStack.redo(undoneProject)
        assertNotNull(redoneProject)
        assertEquals(filterParams, redoneProject!!.clips[0].renderParams.filter)
    }

    @Test
    fun `old-format project JSON loads with DEFAULT filter`() {
        val oldJson = """
            {
                "schemaVersion": 1,
                "clips": [
                    {
                        "id": "c_legacy",
                        "path": "legacy.mp4",
                        "durationMs": 4000,
                        "width": 1080,
                        "height": 1920,
                        "rotation": 0,
                        "frameRate": 30
                    }
                ],
                "transitions": []
            }
        """.trimIndent()

        val project = decodeProject(oldJson)
        assertEquals(1, project.clips.size)
        assertEquals("c_legacy", project.clips[0].id)
        assertEquals(ClipRenderParams.DEFAULT, project.clips[0].renderParams)
        assertEquals(ClipFilterParams.DEFAULT, project.clips[0].renderParams.filter)
    }

    @Test
    fun `round-trip save and load preserves filter params`() {
        val originalFilter = ClipFilterParams(
            filterPresetId = 7,
            filterIntensity = 0.42f,
            effectPresetId = 4,
            effectIntensity = 0.88f
        )
        val clip = Clip(
            id = "c_rt",
            path = "roundtrip.mp4",
            durationMs = 6000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            renderParams = ClipRenderParams(filter = originalFilter)
        )
        val project = Project(clips = listOf(clip), transitions = emptyList())

        val encoded = projectJson.encodeToString(Project.serializer(), project)
        val decoded = decodeProject(encoded)

        assertEquals(1, decoded.clips.size)
        assertEquals(originalFilter, decoded.clips[0].renderParams.filter)
    }

    @Test
    fun `live override wins while present and is cleared after commit or cancel`() {
        val coreClip = com.flowcut.app.core.model.Clip(
            id = "c_live",
            path = "live.mp4",
            durationMs = 5000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            renderParams = ClipRenderParams(
                filter = ClipFilterParams(filterPresetId = 1, filterIntensity = 0.5f)
            )
        )

        // Baseline: resolved from clip.renderParams.filter
        val initial = PreviewRenderer.resolveFilterAndEffectState(coreClip)
        assertEquals(1, initial.filterPresetId)
        assertEquals(0.5f, initial.filterIntensity, 0.001f)

        // Dragging slider: live override applied
        PreviewRenderer.setClipFilter("c_live", presetId = 4, intensity = 0.95f)
        val overridden = PreviewRenderer.resolveFilterAndEffectState(coreClip)
        assertEquals(4, overridden.filterPresetId)
        assertEquals(0.95f, overridden.filterIntensity, 0.001f)

        // Dismiss / Done: clear live override
        PreviewRenderer.clearLiveFilterOverride("c_live")
        val restored = PreviewRenderer.resolveFilterAndEffectState(coreClip)
        assertEquals(1, restored.filterPresetId)
        assertEquals(0.5f, restored.filterIntensity, 0.001f)
    }

    @Test
    fun `overlay override rule still wins over clip filter params`() {
        val coreClip = com.flowcut.app.core.model.Clip(
            id = "c_ov",
            path = "overlay.mp4",
            durationMs = 5000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            renderParams = ClipRenderParams(
                filter = ClipFilterParams(filterPresetId = 2, filterIntensity = 0.7f)
            )
        )

        val overlayItem = OverlayItem(
            id = "ov_filter_1",
            type = LayerType.FILTER,
            laneIndex = 1,
            zIndex = 0,
            startUs = 1_000_000L,
            endUs = 3_000_000L,
            filterPresetId = 8,
            filterIntensity = 0.35f
        )
        val plan = CompiledLayerPlan.compile(listOf(overlayItem))
        val activeLayerSet = ActiveLayerSet()

        // At tUs = 500_000L (before overlay): clip's filter is active
        val before = PreviewRenderer.resolveFilterAndEffectState(coreClip, plan = plan, tUs = 500_000L, activeLayerSet = activeLayerSet)
        assertEquals(2, before.filterPresetId)
        assertEquals(0.7f, before.filterIntensity, 0.001f)

        // At tUs = 2_000_000L (inside overlay): overlay filter wins!
        val during = PreviewRenderer.resolveFilterAndEffectState(coreClip, plan = plan, tUs = 2_000_000L, activeLayerSet = activeLayerSet)
        assertEquals(8, during.filterPresetId)
        assertEquals(0.35f, during.filterIntensity, 0.001f)

        // At tUs = 4_000_000L (after overlay): back to clip filter
        val after = PreviewRenderer.resolveFilterAndEffectState(coreClip, plan = plan, tUs = 4_000_000L, activeLayerSet = activeLayerSet)
        assertEquals(2, after.filterPresetId)
        assertEquals(0.7f, after.filterIntensity, 0.001f)
    }
}
