package com.flowcut.app.engine

import com.flowcut.app.core.feature.LayerItem
import com.flowcut.app.core.feature.LayerRenderer
import com.flowcut.app.core.feature.PassCtx
import com.flowcut.app.core.feature.SimpleBaseFrameInfo
import com.flowcut.app.core.feature.SimplePassCtx
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.TimelineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OverlayStagePassTest {

    private lateinit var stagePass: OverlayStagePass
    private lateinit var mockLegacyRenderer: MockLayerRenderer
    private lateinit var mockHost: MockOverlayHost

    private class MockOverlayHost : OverlayHost {
        override val activeLayerSet: ActiveLayerSet = ActiveLayerSet()
        override var selectedOverlayIdForPreview: String? = null
    }

    private class MockLayerRenderer(
        override val id: String,
        override val kinds: Set<String>,
        private val onDrawAction: (layer: LayerItem, ctx: PassCtx, selected: Boolean) -> Unit = { _, _, _ -> }
    ) : LayerRenderer {
        val drawnItems = mutableListOf<String>()
        val selectedStates = mutableListOf<Boolean>()
        var drawCount = 0

        override fun onGlCreated() {}
        override fun onGlDestroyed() {}

        override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
            drawCount++
            drawnItems.add(layer.id)
            selectedStates.add(selected)
            onDrawAction(layer, ctx, selected)
        }
    }

    @Before
    fun setUp() {
        mockHost = MockOverlayHost()
        mockLegacyRenderer = MockLayerRenderer("mock_legacy", setOf("VIDEO"))
        stagePass = OverlayStagePass(mockHost, mockLegacyRenderer)
        // Bypass GL in unit tests
        stagePass.passScopeRunner = { _, _, _, action -> action() }
    }

    private fun createDummyCtx(): PassCtx {
        return SimplePassCtx(
            tUs = 1_000_000L,
            snapshot = TimelineSnapshot.EMPTY,
            activeSet = ActiveSet(0),
            outputWidth = 720,
            outputHeight = 1280,
            baseFrame = SimpleBaseFrameInfo(
                textureId = 1,
                matrix = FloatArray(16),
                crop = floatArrayOf(1f, 1f)
            )
        )
    }

    @Test
    fun testSpecificRendererBeatsLegacyByKind() {
        val textItem = OverlayItem(
            id = "text_1",
            type = LayerType.TEXT,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L
        )
        val videoItem = OverlayItem(
            id = "video_1",
            type = LayerType.VIDEO,
            laneIndex = 1,
            zIndex = 1,
            startUs = 0L,
            endUs = 2_000_000L
        )

        val plan = CompiledLayerPlan(
            items = listOf(textItem, videoItem),
            segments = arrayOf(
                Segment(
                    startUs = 0L,
                    endUs = 2_000_000L,
                    activeVisualIndices = intArrayOf(0, 1),
                    activeFilterIndices = intArrayOf()
                )
            )
        )
        PreviewRenderer.setLayerPlan(plan)

        val specificTextRenderer = MockLayerRenderer("specific_text", setOf("TEXT"))
        stagePass.registerLayerRenderer(specificTextRenderer)

        stagePass.draw(createDummyCtx())

        // Specific renderer should draw text_1
        assertEquals(listOf("text_1"), specificTextRenderer.drawnItems)
        // Fallback legacy renderer should draw video_1
        assertEquals(listOf("video_1"), mockLegacyRenderer.drawnItems)
    }

    @Test
    fun testDisableAfter3ConsecutiveFailuresAndFallback() {
        var shouldThrow = true
        val failingVideoRenderer = MockLayerRenderer("failing_video", setOf("VIDEO")) { _, _, _ ->
            if (shouldThrow) throw RuntimeException("Simulated video tool failure")
        }
        stagePass.registerLayerRenderer(failingVideoRenderer)

        val videoItem = OverlayItem(
            id = "video_crash",
            type = LayerType.VIDEO,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L
        )
        val plan = CompiledLayerPlan(
            items = listOf(videoItem),
            segments = arrayOf(
                Segment(
                    startUs = 0L,
                    endUs = 2_000_000L,
                    activeVisualIndices = intArrayOf(0),
                    activeFilterIndices = intArrayOf()
                )
            )
        )
        PreviewRenderer.setLayerPlan(plan)
        val ctx = createDummyCtx()

        // Frame 1: throws
        stagePass.draw(ctx)
        assertEquals(1, failingVideoRenderer.drawCount)
        assertFalse(stagePass.isRendererDisabled("failing_video"))

        // Frame 2: throws
        stagePass.draw(ctx)
        assertEquals(2, failingVideoRenderer.drawCount)
        assertFalse(stagePass.isRendererDisabled("failing_video"))

        // Frame 3: throws -> disabled for session
        stagePass.draw(ctx)
        assertEquals(3, failingVideoRenderer.drawCount)
        assertTrue(stagePass.isRendererDisabled("failing_video"))

        // Frame 4: failing renderer disabled; falls back to legacy renderer smoothly
        shouldThrow = false
        stagePass.draw(ctx)
        assertEquals(3, failingVideoRenderer.drawCount) // Not called again!
        assertEquals(listOf("video_crash"), mockLegacyRenderer.drawnItems)
    }

    @Test
    fun testSelectedOnTopDrawOrder() {
        val itemA = OverlayItem(id = "item_a", type = LayerType.VIDEO, laneIndex = 0, zIndex = 0, startUs = 0L, endUs = 2_000_000L)
        val itemB = OverlayItem(id = "item_b", type = LayerType.VIDEO, laneIndex = 1, zIndex = 1, startUs = 0L, endUs = 2_000_000L)
        val itemC = OverlayItem(id = "item_c", type = LayerType.VIDEO, laneIndex = 2, zIndex = 2, startUs = 0L, endUs = 2_000_000L)

        val plan = CompiledLayerPlan(
            items = listOf(itemA, itemB, itemC),
            segments = arrayOf(
                Segment(
                    startUs = 0L,
                    endUs = 2_000_000L,
                    activeVisualIndices = intArrayOf(0, 1, 2),
                    activeFilterIndices = intArrayOf()
                )
            )
        )
        PreviewRenderer.setLayerPlan(plan)

        // Select item_b (middle zIndex)
        mockHost.selectedOverlayIdForPreview = "item_b"

        stagePass.draw(createDummyCtx())

        // Unselected pass draws item_a, item_c in order (selected = false).
        // Selected pass draws item_b last (selected = true).
        assertEquals(listOf("item_a", "item_c", "item_b"), mockLegacyRenderer.drawnItems)
        assertEquals(listOf(false, false, true), mockLegacyRenderer.selectedStates)
    }

    @Test
    fun testUnknownKindSkippedSafelyWithoutCrashing() {
        val passWithoutLegacy = OverlayStagePass(mockHost, null, null as RenderServices?)
        passWithoutLegacy.passScopeRunner = { _, _, _, action -> action() }

        val unhandledItem = OverlayItem(
            id = "unhandled_kind",
            type = LayerType.STICKER,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L
        )
        val plan = CompiledLayerPlan(
            items = listOf(unhandledItem),
            segments = arrayOf(
                Segment(
                    startUs = 0L,
                    endUs = 2_000_000L,
                    activeVisualIndices = intArrayOf(0),
                    activeFilterIndices = intArrayOf()
                )
            )
        )
        PreviewRenderer.setLayerPlan(plan)

        // Must execute cleanly without exception
        passWithoutLegacy.draw(createDummyCtx())
    }
}
