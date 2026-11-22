package com.flowcut.app.features.sticker

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

class StickerLayerRendererTest {

    private lateinit var mockTextureProvider: MockTextureProvider
    private lateinit var mockQuadDrawer: MockQuadDrawer
    private lateinit var services: RenderServices
    private lateinit var renderer: StickerLayerRenderer

    private class MockLayerTexture(
        override val texId: Int = 101,
        override val width: Int = 256,
        override val height: Int = 256
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
        renderer = StickerLayerRenderer(services)
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
        assertEquals("sticker_layer_renderer", renderer.id)
        assertEquals(setOf("STICKER"), renderer.kinds)
    }

    @Test
    fun testDrawStickerCallsTextureProviderAndQuadDrawer() {
        val stickerItem = OverlayItem(
            id = "sticker_1",
            type = LayerType.STICKER,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L,
            text = "🔥",
            transform = ClipTransform(scale = 2.0f, opacity = 0.85f)
        )
        val ctx = createDummyCtx()

        renderer.draw(stickerItem, ctx, selected = false)

        assertEquals(listOf("sticker_1"), mockTextureProvider.requestedItems)
        assertEquals(1, mockQuadDrawer.drawCallCount)
        assertEquals(101, mockQuadDrawer.lastTexId)
        assertNotNull(mockQuadDrawer.lastTransform)
        assertEquals(2.0f, mockQuadDrawer.lastTransform!!.scale, 0.001f)
        assertEquals(0.85f, mockQuadDrawer.lastOpacity, 0.001f)
    }

    @Test
    fun testDrawIgnoresNonStickerLayer() {
        val textItem = OverlayItem(
            id = "text_not_sticker",
            type = LayerType.TEXT,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L
        )
        val ctx = createDummyCtx()

        renderer.draw(textItem, ctx, selected = false)

        assertTrue(mockTextureProvider.requestedItems.isEmpty())
        assertEquals(0, mockQuadDrawer.drawCallCount)
    }

    @Test
    fun testOnGlDestroyedReleasesOnlyStickerKind() {
        renderer.onGlDestroyed()

        assertEquals(listOf("STICKER"), mockTextureProvider.releasedKinds)
        assertTrue(mockTextureProvider.releasedItems.isEmpty())
    }

    @Test
    fun testKindRoutingInOverlayStagePass() {
        val host = MockHost()
        val mockLegacy = MockLegacyLayerRenderer()
        val stagePass = OverlayStagePass(host, mockLegacy, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        stagePass.registerLayerRenderer(renderer)

        val stickerItem = OverlayItem(
            id = "routed_sticker",
            type = LayerType.STICKER,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L,
            text = "⭐"
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
            items = listOf(stickerItem, videoItem),
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

        // STICKER went to StickerLayerRenderer
        assertEquals(listOf("routed_sticker"), mockTextureProvider.requestedItems)
        assertEquals(1, mockQuadDrawer.drawCallCount)

        // VIDEO went to LegacyLayerRenderer
        assertEquals(listOf("routed_video"), mockLegacy.drawnItems)
    }

    @Test
    fun testStickerDisabledAfter3FailuresSimplyNotDrawn() {
        val host = MockHost()
        val mockLegacy = MockLegacyLayerRenderer()
        val stagePass = OverlayStagePass(host, mockLegacy, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        var shouldCrash = true
        val failingStickerRenderer = object : LayerRenderer {
            override val id: String = "failing_sticker"
            override val kinds: Set<String> = setOf("STICKER")
            var calls = 0
            override fun onGlCreated() {}
            override fun onGlDestroyed() {}
            override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
                calls++
                if (shouldCrash) throw RuntimeException("Crashing sticker renderer")
            }
        }
        stagePass.registerLayerRenderer(failingStickerRenderer)

        val stickerItem = OverlayItem(
            id = "sticker_failure_test",
            type = LayerType.STICKER,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L,
            text = "❌"
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
            items = listOf(stickerItem, videoItem),
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
        assertTrue(stagePass.isRendererDisabled("failing_sticker"))

        // Frame 4: sticker is disabled -> simply NOT drawn by legacy either
        shouldCrash = false
        mockLegacy.drawnItems.clear()
        stagePass.draw(ctx)

        // Sticker was NOT drawn by legacy
        assertFalse(mockLegacy.drawnItems.contains("sticker_failure_test"))
        // But video is still drawn smoothly!
        assertTrue(mockLegacy.drawnItems.contains("video_stays_alive"))
    }
}
