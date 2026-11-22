package com.flowcut.app.features.mosaic

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
import com.flowcut.app.engine.MosaicDrawer
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

class MosaicLayerRendererTest {

    private lateinit var mockMosaicDrawer: MockMosaicDrawer
    private lateinit var mockTextureProvider: MockTextureProvider
    private lateinit var mockQuadDrawer: MockQuadDrawer
    private lateinit var services: RenderServices
    private lateinit var renderer: MosaicLayerRenderer

    private class MockMosaicDrawer : MosaicDrawer {
        var drawCallCount = 0
        var lastVideoTexId: Int = 0
        var lastVideoMat: FloatArray? = null
        var lastCrop: FloatArray? = null
        var lastTransform: ClipTransform? = null
        var lastStyle: Int = 0
        var lastIntensity: Float = 0f
        var lastFeather: Float = 0f
        var lastOpacity: Float = 0f
        var lastDisplayWidth: Int = 0
        var lastDisplayHeight: Int = 0

        override fun drawMosaicRegion(
            videoTexId: Int,
            videoMat: FloatArray,
            crop: FloatArray,
            transform: ClipTransform,
            mosaicStyle: Int,
            mosaicIntensity: Float,
            mosaicFeather: Float,
            opacity: Float,
            displayWidth: Int,
            displayHeight: Int
        ) {
            drawCallCount++
            lastVideoTexId = videoTexId
            lastVideoMat = videoMat.clone()
            lastCrop = crop.clone()
            lastTransform = transform
            lastStyle = mosaicStyle
            lastIntensity = mosaicIntensity
            lastFeather = mosaicFeather
            lastOpacity = opacity
            lastDisplayWidth = displayWidth
            lastDisplayHeight = displayHeight
        }
    }

    private class MockTextureProvider : TextureProvider {
        override fun getTexture(item: OverlayItem): LayerTexture? = null
        override fun releaseTexture(itemId: String) {}
        override fun releaseKind(kind: String) {}
    }

    private class MockQuadDrawer : QuadDrawer {
        override fun drawOverlayQuad(
            texId: Int, texWidth: Int, texHeight: Int, transform: ClipTransform,
            opacity: Float, displayWidth: Int, displayHeight: Int, cropLeft: Float,
            cropTop: Float, cropRight: Float, cropBottom: Float, maskType: Int,
            maskFeather: Float, maskInvert: Boolean, chromaEnabled: Boolean,
            chromaColorArgb: Int, chromaThreshold: Float, chromaBlend: Float,
            adjustBrightness: Float, adjustContrast: Float, adjustSaturation: Float
        ) {}
    }

    private class MockHost : OverlayHost {
        override val activeLayerSet: ActiveLayerSet = ActiveLayerSet()
        override var selectedOverlayIdForPreview: String? = null
    }

    private class MockLegacyLayerRenderer(
        override val kinds: Set<String> = setOf("VIDEO")
    ) : LayerRenderer {
        override val id: String = "mock_legacy"
        val drawnItems = mutableListOf<String>()
        val selectedStates = mutableListOf<Boolean>()

        override fun onGlCreated() {}
        override fun onGlDestroyed() {}

        override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
            drawnItems.add(layer.id)
            selectedStates.add(selected)
        }
    }

    private class MockVisualLayerRenderer(
        override val id: String,
        override val kinds: Set<String>
    ) : LayerRenderer {
        val drawnItems = mutableListOf<String>()
        val selectedStates = mutableListOf<Boolean>()

        override fun onGlCreated() {}
        override fun onGlDestroyed() {}

        override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
            drawnItems.add(layer.id)
            selectedStates.add(selected)
        }
    }

    @Before
    fun setUp() {
        mockMosaicDrawer = MockMosaicDrawer()
        mockTextureProvider = MockTextureProvider()
        mockQuadDrawer = MockQuadDrawer()
        services = DefaultRenderServices(
            quadDrawer = mockQuadDrawer,
            textureProvider = mockTextureProvider,
            mosaicDrawer = mockMosaicDrawer
        )
        renderer = MosaicLayerRenderer()
        renderer.attachRenderServices(services)
    }

    private fun createDummyCtx(videoTexId: Int = 101): PassCtx {
        val matrix = FloatArray(16) { if (it % 5 == 0) 1.0f else 0.0f }
        val crop = floatArrayOf(0.9f, 0.8f)
        return SimplePassCtx(
            tUs = 1_000_000L,
            snapshot = TimelineSnapshot.EMPTY,
            activeSet = ActiveSet(0),
            outputWidth = 1080,
            outputHeight = 1920,
            baseFrame = SimpleBaseFrameInfo(
                textureId = videoTexId,
                matrix = matrix,
                crop = crop
            )
        )
    }

    @Test
    fun testMosaicDrawCallsMosaicDrawerWithBaseFrameInfo() {
        val item = OverlayItem(
            id = "mosaic_1",
            type = LayerType.MOSAIC,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L,
            mosaicStyle = 2,
            mosaicIntensity = 0.75f,
            mosaicFeather = 0.15f
        )
        val ctx = createDummyCtx(videoTexId = 555)

        renderer.draw(item, ctx, selected = false)

        assertEquals(1, mockMosaicDrawer.drawCallCount)
        assertEquals(555, mockMosaicDrawer.lastVideoTexId)
        assertEquals(2, mockMosaicDrawer.lastStyle)
        assertEquals(0.75f, mockMosaicDrawer.lastIntensity, 0.001f)
        assertEquals(0.15f, mockMosaicDrawer.lastFeather, 0.001f)
        assertEquals(1.0f, mockMosaicDrawer.lastOpacity, 0.001f)
        assertEquals(1080, mockMosaicDrawer.lastDisplayWidth)
        assertEquals(1920, mockMosaicDrawer.lastDisplayHeight)
        assertNotNull(mockMosaicDrawer.lastVideoMat)
        assertNotNull(mockMosaicDrawer.lastCrop)
        assertEquals(0.9f, mockMosaicDrawer.lastCrop!![0], 0.001f)
        assertEquals(0.8f, mockMosaicDrawer.lastCrop!![1], 0.001f)
    }

    @Test
    fun testIgnoresNonMosaicOrZeroOpacityOrMissingTexture() {
        val nonMosaic = OverlayItem(
            id = "text_item",
            type = LayerType.TEXT,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L
        )
        renderer.draw(nonMosaic, createDummyCtx(), selected = false)
        assertEquals(0, mockMosaicDrawer.drawCallCount)

        val zeroOpacity = OverlayItem(
            id = "transparent_mosaic",
            type = LayerType.MOSAIC,
            laneIndex = 0,
            zIndex = 0,
            transform = ClipTransform(opacity = 0.0f),
            startUs = 0L,
            endUs = 3_000_000L
        )
        renderer.draw(zeroOpacity, createDummyCtx(), selected = false)
        assertEquals(0, mockMosaicDrawer.drawCallCount)

        val missingTexCtx = createDummyCtx(videoTexId = 0)
        val normalMosaic = OverlayItem(
            id = "normal_mosaic",
            type = LayerType.MOSAIC,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L
        )
        renderer.draw(normalMosaic, missingTexCtx, selected = false)
        assertEquals(0, mockMosaicDrawer.drawCallCount)
    }

    @Test
    fun testKindRoutingInOverlayStagePass() {
        val host = MockHost()
        val mockLegacy = MockLegacyLayerRenderer()
        val stagePass = OverlayStagePass(host, mockLegacy, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        stagePass.registerLayerRenderer(renderer)

        val mosaicItem = OverlayItem(
            id = "routed_mosaic",
            type = LayerType.MOSAIC,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L
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
            items = listOf(mosaicItem, videoItem),
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

        // MOSAIC routed to MosaicLayerRenderer
        assertEquals(1, mockMosaicDrawer.drawCallCount)

        // VIDEO routed to LegacyLayerRenderer
        assertEquals(listOf("routed_video"), mockLegacy.drawnItems)
    }

    @Test
    fun testFailureIsolationBetweenMosaicAndOtherPlugins() {
        val host = MockHost()
        val mockLegacy = MockLegacyLayerRenderer()
        val stagePass = OverlayStagePass(host, mockLegacy, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        // Register working visual layer renderer (e.g. TEXT)
        val visualRenderer = MockVisualLayerRenderer("text_plugin", setOf("TEXT"))
        stagePass.registerLayerRenderer(visualRenderer)

        // Register failing Mosaic renderer
        var shouldCrash = true
        val failingMosaicRenderer = object : LayerRenderer {
            override val id: String = "failing_mosaic"
            override val kinds: Set<String> = setOf("MOSAIC")
            var calls = 0
            override fun onGlCreated() {}
            override fun onGlDestroyed() {}
            override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
                calls++
                if (shouldCrash) throw RuntimeException("Crashing mosaic renderer")
            }
        }
        stagePass.registerLayerRenderer(failingMosaicRenderer)

        val mosaicItem = OverlayItem(
            id = "crashing_mosaic",
            type = LayerType.MOSAIC,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L
        )
        val textItem = OverlayItem(
            id = "working_text",
            type = LayerType.TEXT,
            laneIndex = 1,
            zIndex = 1,
            startUs = 0L,
            endUs = 2_000_000L
        )

        val plan = CompiledLayerPlan(
            items = listOf(mosaicItem, textItem),
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

        // 3 consecutive crashes on mosaic
        stagePass.draw(ctx)
        stagePass.draw(ctx)
        stagePass.draw(ctx)
        assertTrue(stagePass.isRendererDisabled("failing_mosaic"))
        assertFalse(stagePass.isRendererDisabled("text_plugin"))

        // Frame 4: failing mosaic is disabled for the session; text continues drawing
        visualRenderer.drawnItems.clear()
        stagePass.draw(ctx)

        // Text drew successfully
        assertEquals(listOf("working_text"), visualRenderer.drawnItems)
        // Mosaic is disabled and not sent to legacy (since legacy does not support MOSAIC)
        assertFalse(mockLegacy.drawnItems.contains("crashing_mosaic"))
    }

    @Test
    fun testMosaicOrderedBeforeVisualOverlaysAndSelectedOnTop() {
        val host = MockHost()
        val recordedDrawOrder = mutableListOf<String>()

        val mosaicPlugin = object : LayerRenderer {
            override val id: String = "mosaic_recorder"
            override val kinds: Set<String> = setOf("MOSAIC")
            override fun onGlCreated() {}
            override fun onGlDestroyed() {}
            override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
                recordedDrawOrder.add("${layer.id}:selected=$selected")
            }
        }

        val visualPlugin = object : LayerRenderer {
            override val id: String = "visual_recorder"
            override val kinds: Set<String> = setOf("STICKER")
            override fun onGlCreated() {}
            override fun onGlDestroyed() {}
            override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
                recordedDrawOrder.add("${layer.id}:selected=$selected")
            }
        }

        val stagePass = OverlayStagePass(host, MockLegacyLayerRenderer(), services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }
        stagePass.registerLayerRenderer(mosaicPlugin)
        stagePass.registerLayerRenderer(visualPlugin)

        val mosaicItem = OverlayItem(
            id = "mosaic_layer",
            type = LayerType.MOSAIC,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L
        )
        val stickerItem = OverlayItem(
            id = "sticker_layer",
            type = LayerType.STICKER,
            laneIndex = 1,
            zIndex = 1,
            startUs = 0L,
            endUs = 2_000_000L
        )

        val plan = CompiledLayerPlan(
            items = listOf(mosaicItem, stickerItem),
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

        // 1. Unselected: mosaic is drawn before visual overlay according to zIndex order
        recordedDrawOrder.clear()
        stagePass.draw(ctx)
        assertEquals(listOf("mosaic_layer:selected=false", "sticker_layer:selected=false"), recordedDrawOrder)

        // 2. Selected on top: when mosaic is selected, unselected sticker is drawn first, then selected mosaic on top
        host.selectedOverlayIdForPreview = "mosaic_layer"
        recordedDrawOrder.clear()
        stagePass.draw(ctx)
        assertEquals(listOf("sticker_layer:selected=false", "mosaic_layer:selected=true"), recordedDrawOrder)
    }
}
