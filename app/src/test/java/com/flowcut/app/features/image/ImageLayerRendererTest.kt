package com.flowcut.app.features.image

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
import com.flowcut.app.features.sticker.StickerLayerRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageLayerRendererTest {

    private lateinit var mockTextureProvider: MockTextureProvider
    private lateinit var mockQuadDrawer: MockQuadDrawer
    private lateinit var services: RenderServices
    private lateinit var renderer: ImageLayerRenderer

    private class MockLayerTexture(
        override val texId: Int = 202,
        override val width: Int = 512,
        override val height: Int = 512
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
        renderer = ImageLayerRenderer(services)
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
        assertEquals("image_layer_renderer", renderer.id)
        assertEquals(setOf("IMAGE"), renderer.kinds)
    }

    @Test
    fun testDrawImageCallsTextureProviderAndQuadDrawer() {
        val imageItem = OverlayItem(
            id = "image_1",
            type = LayerType.IMAGE,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L,
            mediaPath = "/dummy/path/photo.jpg",
            transform = ClipTransform(scale = 1.2f, opacity = 0.95f),
            cropLeft = 0.1f,
            cropRight = 0.9f
        )
        val ctx = createDummyCtx()

        renderer.draw(imageItem, ctx, selected = false)

        assertEquals(listOf("image_1"), mockTextureProvider.requestedItems)
        assertEquals(1, mockQuadDrawer.drawCallCount)
        assertEquals(202, mockQuadDrawer.lastTexId)
        assertNotNull(mockQuadDrawer.lastTransform)
        assertEquals(1.2f, mockQuadDrawer.lastTransform!!.scale, 0.001f)
        assertEquals(0.95f, mockQuadDrawer.lastOpacity, 0.001f)
    }

    @Test
    fun testDrawIgnoresNonImageLayer() {
        val stickerItem = OverlayItem(
            id = "sticker_not_image",
            type = LayerType.STICKER,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L
        )
        val ctx = createDummyCtx()

        renderer.draw(stickerItem, ctx, selected = false)

        assertTrue(mockTextureProvider.requestedItems.isEmpty())
        assertEquals(0, mockQuadDrawer.drawCallCount)
    }

    @Test
    fun testOnGlDestroyedReleasesOnlyImageKind() {
        renderer.onGlDestroyed()

        assertEquals(listOf("IMAGE"), mockTextureProvider.releasedKinds)
        assertTrue(mockTextureProvider.releasedItems.isEmpty())
    }

    @Test
    fun testKindRoutingInOverlayStagePass() {
        val host = MockHost()
        val mockLegacy = MockLegacyLayerRenderer()
        val stagePass = OverlayStagePass(host, mockLegacy, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        stagePass.registerLayerRenderer(renderer)

        val imageItem = OverlayItem(
            id = "routed_image",
            type = LayerType.IMAGE,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L,
            mediaPath = "/path/img.png"
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
            items = listOf(imageItem, videoItem),
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

        // IMAGE went to ImageLayerRenderer
        assertEquals(listOf("routed_image"), mockTextureProvider.requestedItems)
        assertEquals(1, mockQuadDrawer.drawCallCount)

        // VIDEO went to LegacyLayerRenderer
        assertEquals(listOf("routed_video"), mockLegacy.drawnItems)
    }

    @Test
    fun testFailureIsolationBetweenStickerAndImage() {
        val host = MockHost()
        val mockLegacy = MockLegacyLayerRenderer()
        val stagePass = OverlayStagePass(host, mockLegacy, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        // Register functioning Image renderer
        stagePass.registerLayerRenderer(renderer)

        // Register failing Sticker renderer
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
            id = "crashing_sticker",
            type = LayerType.STICKER,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L,
            text = "💥"
        )
        val imageItem = OverlayItem(
            id = "working_image",
            type = LayerType.IMAGE,
            laneIndex = 1,
            zIndex = 1,
            startUs = 0L,
            endUs = 2_000_000L,
            mediaPath = "/path/valid.jpg"
        )

        val plan = CompiledLayerPlan(
            items = listOf(stickerItem, imageItem),
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

        // 3 consecutive crashes on sticker
        stagePass.draw(ctx)
        stagePass.draw(ctx)
        stagePass.draw(ctx)
        assertTrue(stagePass.isRendererDisabled("failing_sticker"))
        assertFalse(stagePass.isRendererDisabled("image_layer_renderer"))

        // Frame 4: failing sticker disabled; image still draws smoothly!
        mockQuadDrawer.drawCallCount = 0
        mockTextureProvider.requestedItems.clear()
        stagePass.draw(ctx)

        // Image drew successfully
        assertEquals(listOf("working_image"), mockTextureProvider.requestedItems)
        assertEquals(1, mockQuadDrawer.drawCallCount)
        // Sticker is disabled and not drawn
        assertFalse(mockLegacy.drawnItems.contains("crashing_sticker"))
    }
}
