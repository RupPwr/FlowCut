package com.flowcut.app.features.text

import com.flowcut.app.core.feature.LayerItem
import com.flowcut.app.core.feature.LayerRenderer
import com.flowcut.app.core.feature.PassCtx
import com.flowcut.app.core.feature.SimpleBaseFrameInfo
import com.flowcut.app.core.feature.SimplePassCtx
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.ActiveLayerSet
import com.flowcut.app.engine.ClipTransform
import com.flowcut.app.engine.CompiledLayerPlan
import com.flowcut.app.engine.DefaultRenderServices
import com.flowcut.app.engine.LayerTexture
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayHost
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.OverlayStagePass
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.engine.QuadDrawer
import com.flowcut.app.engine.RenderServices
import com.flowcut.app.engine.Segment
import com.flowcut.app.engine.TextureProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TextLayerRendererTest {

    private lateinit var mockTextureProvider: MockTextureProvider
    private lateinit var mockQuadDrawer: MockQuadDrawer
    private lateinit var services: RenderServices
    private lateinit var renderer: TextLayerRenderer

    private class MockLayerTexture(
        override val texId: Int = 42,
        override val width: Int = 200,
        override val height: Int = 100
    ) : LayerTexture

    private class MockTextureProvider : TextureProvider {
        val requestedItems = mutableListOf<String>()
        val releasedItems = mutableListOf<String>()
        val releasedKinds = mutableListOf<String>()
        var textureToReturn: LayerTexture? = MockLayerTexture()

        override fun getTexture(item: OverlayItem): LayerTexture? {
            requestedItems.add(item.id)
            return textureToReturn
        }

        override fun releaseTexture(itemId: String) {
            releasedItems.add(itemId)
        }

        override fun releaseKind(kind: String) {
            releasedKinds.add(kind)
        }
    }

    private class MockQuadDrawer : QuadDrawer {
        var drawCallCount = 0
        var lastTexId: Int = 0
        var lastTransform: ClipTransform? = null
        var lastOpacity: Float = 0f

        override fun drawOverlayQuad(
            texId: Int,
            texWidth: Int,
            texHeight: Int,
            transform: ClipTransform,
            opacity: Float,
            displayWidth: Int,
            displayHeight: Int,
            cropLeft: Float,
            cropTop: Float,
            cropRight: Float,
            cropBottom: Float,
            maskType: Int,
            maskFeather: Float,
            maskInvert: Boolean,
            chromaEnabled: Boolean,
            chromaColorArgb: Int,
            chromaThreshold: Float,
            chromaBlend: Float,
            adjustBrightness: Float,
            adjustContrast: Float,
            adjustSaturation: Float
        ) {
            drawCallCount++
            lastTexId = texId
            lastTransform = transform
            lastOpacity = opacity
        }
    }

    private class MockHost : OverlayHost {
        override val activeLayerSet: ActiveLayerSet = ActiveLayerSet()
        override var selectedOverlayIdForPreview: String? = null
    }

    private class MockLegacyLayerRenderer(
        override val id: String = "mock_legacy",
        override val kinds: Set<String> = setOf("MOSAIC", "VIDEO")
    ) : LayerRenderer {
        val drawnItems = mutableListOf<String>()
        override fun onGlCreated() {}
        override fun onGlDestroyed() {}
        override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
            drawnItems.add(layer.id)
        }
    }

    @Before
    fun setUp() {
        mockTextureProvider = MockTextureProvider()
        mockQuadDrawer = MockQuadDrawer()
        services = DefaultRenderServices(mockQuadDrawer, mockTextureProvider)
        renderer = TextLayerRenderer(services)
    }

    private fun createDummyCtx(): PassCtx {
        return SimplePassCtx(
            tUs = 1_000_000L,
            snapshot = TimelineSnapshot.EMPTY,
            activeSet = ActiveSet(0),
            outputWidth = 1080,
            outputHeight = 1920,
            baseFrame = SimpleBaseFrameInfo(
                textureId = 1,
                matrix = FloatArray(16),
                crop = floatArrayOf(1f, 1f)
            )
        )
    }

    @Test
    fun testRendererMetadata() {
        assertEquals("text_layer_renderer", renderer.id)
        assertEquals(setOf("TEXT"), renderer.kinds)
    }

    @Test
    fun testDrawTextLayerCallsTextureProviderAndQuadDrawer() {
        val textItem = OverlayItem(
            id = "text_test_1",
            type = LayerType.TEXT,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L,
            text = "FlowCut Plugin",
            transform = ClipTransform(scale = 1.5f, opacity = 0.9f)
        )
        val ctx = createDummyCtx()

        renderer.draw(textItem, ctx, selected = false)

        assertEquals(listOf("text_test_1"), mockTextureProvider.requestedItems)
        assertEquals(1, mockQuadDrawer.drawCallCount)
        assertEquals(42, mockQuadDrawer.lastTexId)
        assertNotNull(mockQuadDrawer.lastTransform)
        assertEquals(1.5f, mockQuadDrawer.lastTransform!!.scale, 0.001f)
        assertEquals(0.9f, mockQuadDrawer.lastOpacity, 0.001f)
    }

    @Test
    fun testDrawIgnoresNonTextLayer() {
        val imageItem = OverlayItem(
            id = "image_not_text",
            type = LayerType.IMAGE,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L
        )
        val ctx = createDummyCtx()

        renderer.draw(imageItem, ctx, selected = false)

        assertTrue(mockTextureProvider.requestedItems.isEmpty())
        assertEquals(0, mockQuadDrawer.drawCallCount)
    }

    @Test
    fun testDrawIgnoresZeroOpacity() {
        val hiddenItem = OverlayItem(
            id = "text_hidden",
            type = LayerType.TEXT,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L,
            transform = ClipTransform(opacity = 0.0f)
        )
        val ctx = createDummyCtx()

        renderer.draw(hiddenItem, ctx, selected = false)

        assertTrue(mockTextureProvider.requestedItems.isEmpty())
        assertEquals(0, mockQuadDrawer.drawCallCount)
    }

    @Test
    fun testOnGlDestroyedReleasesOnlyTextKind() {
        renderer.onGlDestroyed()

        assertEquals(listOf("TEXT"), mockTextureProvider.releasedKinds)
        assertTrue(mockTextureProvider.releasedItems.isEmpty())
    }

    @Test
    fun testKindRoutingInOverlayStagePass() {
        val host = MockHost()
        val mockLegacy = MockLegacyLayerRenderer()
        val stagePass = OverlayStagePass(host, mockLegacy, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        stagePass.registerLayerRenderer(renderer)

        val textItem = OverlayItem(
            id = "routed_text",
            type = LayerType.TEXT,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L,
            text = "Plugin Route"
        )
        val videoItem = OverlayItem(
            id = "routed_video",
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
        val ctx = createDummyCtx()

        stagePass.draw(ctx)

        // TEXT went to TextLayerRenderer
        assertEquals(listOf("routed_text"), mockTextureProvider.requestedItems)
        assertEquals(1, mockQuadDrawer.drawCallCount)

        // VIDEO went to LegacyLayerRenderer
        assertEquals(listOf("routed_video"), mockLegacy.drawnItems)
    }

    @Test
    fun testTextDisabledAfter3FailuresSimplyNotDrawn() {
        val host = MockHost()
        val mockLegacy = MockLegacyLayerRenderer()
        val stagePass = OverlayStagePass(host, mockLegacy, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        var shouldCrash = true
        val failingTextRenderer = object : LayerRenderer {
            override val id: String = "failing_text"
            override val kinds: Set<String> = setOf("TEXT")
            var calls = 0
            override fun onGlCreated() {}
            override fun onGlDestroyed() {}
            override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
                calls++
                if (shouldCrash) throw RuntimeException("Crashing text renderer")
            }
        }
        stagePass.registerLayerRenderer(failingTextRenderer)

        val textItem = OverlayItem(
            id = "text_failure_test",
            type = LayerType.TEXT,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L
        )
        val videoItem = OverlayItem(
            id = "video_stays_alive",
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
        val ctx = createDummyCtx()

        // 3 consecutive crashes
        stagePass.draw(ctx)
        stagePass.draw(ctx)
        stagePass.draw(ctx)
        assertTrue(stagePass.isRendererDisabled("failing_text"))

        // Frame 4: text is disabled -> simply NOT drawn by legacy either
        shouldCrash = false
        mockLegacy.drawnItems.clear()
        stagePass.draw(ctx)

        // Text was NOT drawn by legacy (not in drawnItems)
        assertFalse(mockLegacy.drawnItems.contains("text_failure_test"))
        // But video is still drawn smoothly!
        assertTrue(mockLegacy.drawnItems.contains("video_stays_alive"))
    }

    @Test
    fun testOverlayStagePassWiresServicesToTextRenderer() {
        val host = MockHost()
        val mockLegacy = MockLegacyLayerRenderer()
        val stagePass = OverlayStagePass(host, mockLegacy, services)
        val textRenderer = TextLayerRenderer()
        stagePass.registerLayerRenderer(textRenderer)
        assertEquals(services, textRenderer.services)
    }
}
