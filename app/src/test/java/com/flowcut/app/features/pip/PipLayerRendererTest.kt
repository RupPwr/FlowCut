package com.flowcut.app.features.pip

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
import com.flowcut.app.engine.PipTextureProvider
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

class PipLayerRendererTest {

    private lateinit var mockPipTextureProvider: MockPipTextureProvider
    private lateinit var mockTextureProvider: MockTextureProvider
    private lateinit var mockQuadDrawer: MockQuadDrawer
    private lateinit var services: RenderServices
    private lateinit var renderer: PipLayerRenderer

    private class MockLayerTexture(
        override val texId: Int = 303,
        override val width: Int = 640,
        override val height: Int = 360
    ) : LayerTexture

    private class MockPipTextureProvider : PipTextureProvider {
        val requestedItems = mutableListOf<String>()
        val requestedTimestamps = mutableListOf<Long>()
        val releasedItems = mutableListOf<String>()
        var textureToReturn: LayerTexture? = MockLayerTexture()

        override fun getVideoTexture(item: OverlayItem, timelineUs: Long): LayerTexture? {
            requestedItems.add(item.id)
            requestedTimestamps.add(timelineUs)
            return textureToReturn
        }

        override fun releaseTexture(itemId: String) {
            releasedItems.add(itemId)
        }
    }

    private class MockTextureProvider : TextureProvider {
        override fun getTexture(item: OverlayItem): LayerTexture? = null
        override fun releaseTexture(itemId: String) {}
        override fun releaseKind(kind: String) {}
    }

    private class MockQuadDrawer : QuadDrawer {
        var drawCallCount = 0
        var lastTexId: Int = 0
        var lastTexWidth: Int = 0
        var lastTexHeight: Int = 0
        var lastTransform: ClipTransform? = null
        var lastOpacity: Float = 0f
        var lastDisplayWidth: Int = 0
        var lastDisplayHeight: Int = 0
        var lastCropLeft: Float = 0f
        var lastCropTop: Float = 0f
        var lastCropRight: Float = 1f
        var lastCropBottom: Float = 1f
        var lastMaskType: Int = 0
        var lastMaskFeather: Float = 0f
        var lastMaskInvert: Boolean = false
        var lastChromaEnabled: Boolean = false
        var lastChromaColorArgb: Int = 0
        var lastChromaThreshold: Float = 0f
        var lastChromaBlend: Float = 0f
        var lastAdjustBrightness: Float = 0f
        var lastAdjustContrast: Float = 1f
        var lastAdjustSaturation: Float = 1f

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
            lastTexWidth = texWidth
            lastTexHeight = texHeight
            lastTransform = transform
            lastOpacity = opacity
            lastDisplayWidth = displayWidth
            lastDisplayHeight = displayHeight
            lastCropLeft = cropLeft
            lastCropTop = cropTop
            lastCropRight = cropRight
            lastCropBottom = cropBottom
            lastMaskType = maskType
            lastMaskFeather = maskFeather
            lastMaskInvert = maskInvert
            lastChromaEnabled = chromaEnabled
            lastChromaColorArgb = chromaColorArgb
            lastChromaThreshold = chromaThreshold
            lastChromaBlend = chromaBlend
            lastAdjustBrightness = adjustBrightness
            lastAdjustContrast = adjustContrast
            lastAdjustSaturation = adjustSaturation
        }
    }

    private class MockHost : OverlayHost {
        override val activeLayerSet: ActiveLayerSet = ActiveLayerSet()
        override var selectedOverlayIdForPreview: String? = null
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
        mockPipTextureProvider = MockPipTextureProvider()
        mockTextureProvider = MockTextureProvider()
        mockQuadDrawer = MockQuadDrawer()
        services = DefaultRenderServices(
            quadDrawer = mockQuadDrawer,
            textureProvider = mockTextureProvider,
            pipTextureProvider = mockPipTextureProvider
        )
        renderer = PipLayerRenderer()
        renderer.attachRenderServices(services)
    }

    private fun createDummyCtx(tUs: Long = 1_500_000L): PassCtx {
        return SimplePassCtx(
            tUs = tUs,
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
    fun testPipDrawCallsPipTextureProviderAndQuadDrawer() {
        val item = OverlayItem(
            id = "pip_video_1",
            type = LayerType.VIDEO,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L,
            mediaPath = "/sdcard/pip.mp4",
            cropLeft = 0.1f,
            cropTop = 0.2f,
            cropRight = 0.9f,
            cropBottom = 0.8f,
            maskType = 1,
            maskFeather = 0.08f,
            maskInvert = true,
            chromaEnabled = true,
            chromaColorArgb = 0xFF00FF00.toInt(),
            chromaThreshold = 0.3f,
            chromaBlend = 0.15f,
            adjustBrightness = 0.05f,
            adjustContrast = 1.1f,
            adjustSaturation = 1.2f
        )
        val ctx = createDummyCtx(tUs = 1_250_000L)

        renderer.draw(item, ctx, selected = false)

        assertEquals(listOf("pip_video_1"), mockPipTextureProvider.requestedItems)
        assertEquals(listOf(1_250_000L), mockPipTextureProvider.requestedTimestamps)
        assertEquals(1, mockQuadDrawer.drawCallCount)
        assertEquals(303, mockQuadDrawer.lastTexId)
        assertEquals(640, mockQuadDrawer.lastTexWidth)
        assertEquals(360, mockQuadDrawer.lastTexHeight)
        assertEquals(1080, mockQuadDrawer.lastDisplayWidth)
        assertEquals(1920, mockQuadDrawer.lastDisplayHeight)
        assertEquals(0.1f, mockQuadDrawer.lastCropLeft, 0.001f)
        assertEquals(0.2f, mockQuadDrawer.lastCropTop, 0.001f)
        assertEquals(0.9f, mockQuadDrawer.lastCropRight, 0.001f)
        assertEquals(0.8f, mockQuadDrawer.lastCropBottom, 0.001f)
        assertEquals(1, mockQuadDrawer.lastMaskType)
        assertEquals(0.08f, mockQuadDrawer.lastMaskFeather, 0.001f)
        assertTrue(mockQuadDrawer.lastMaskInvert)
        assertTrue(mockQuadDrawer.lastChromaEnabled)
        assertEquals(0xFF00FF00.toInt(), mockQuadDrawer.lastChromaColorArgb)
        assertEquals(0.3f, mockQuadDrawer.lastChromaThreshold, 0.001f)
        assertEquals(0.15f, mockQuadDrawer.lastChromaBlend, 0.001f)
        assertEquals(0.05f, mockQuadDrawer.lastAdjustBrightness, 0.001f)
        assertEquals(1.1f, mockQuadDrawer.lastAdjustContrast, 0.001f)
        assertEquals(1.2f, mockQuadDrawer.lastAdjustSaturation, 0.001f)
    }

    @Test
    fun testIgnoresNonVideoOrZeroOpacityOrMissingTexture() {
        val nonVideo = OverlayItem(
            id = "image_item",
            type = LayerType.IMAGE,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L
        )
        renderer.draw(nonVideo, createDummyCtx(), selected = false)
        assertEquals(0, mockQuadDrawer.drawCallCount)

        val zeroOpacity = OverlayItem(
            id = "transparent_video",
            type = LayerType.VIDEO,
            laneIndex = 0,
            zIndex = 0,
            transform = ClipTransform(opacity = 0.0f),
            startUs = 0L,
            endUs = 3_000_000L
        )
        renderer.draw(zeroOpacity, createDummyCtx(), selected = false)
        assertEquals(0, mockQuadDrawer.drawCallCount)

        mockPipTextureProvider.textureToReturn = null
        val normalVideo = OverlayItem(
            id = "normal_video",
            type = LayerType.VIDEO,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 3_000_000L
        )
        renderer.draw(normalVideo, createDummyCtx(), selected = false)
        assertEquals(0, mockQuadDrawer.drawCallCount)
    }

    @Test
    fun testKindRoutingInOverlayStagePass() {
        val host = MockHost()
        val stagePass = OverlayStagePass(host, null, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        stagePass.registerLayerRenderer(renderer)

        val videoItem = OverlayItem(
            id = "routed_pip_video",
            type = LayerType.VIDEO,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L,
            mediaPath = "/path/pip.mp4"
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

        stagePass.draw(ctx)

        // VIDEO routed to PipLayerRenderer
        assertEquals(listOf("routed_pip_video"), mockPipTextureProvider.requestedItems)
        assertEquals(1, mockQuadDrawer.drawCallCount)
    }

    @Test
    fun testUnknownKindsSkippedSafelyWithoutCrashing() {
        val host = MockHost()
        // OverlayStagePass without any legacy renderer
        val stagePass = OverlayStagePass(host, null, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        // Register PipLayerRenderer (handles VIDEO only)
        stagePass.registerLayerRenderer(renderer)

        // Overlay plan has an unregistered kind (e.g. STICKER)
        val stickerItem = OverlayItem(
            id = "unregistered_kind_item",
            type = LayerType.STICKER,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L
        )

        val plan = CompiledLayerPlan(
            items = listOf(stickerItem),
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

        // Must execute cleanly, safely skipping the unhandled layer
        stagePass.draw(createDummyCtx())
        assertEquals(0, mockQuadDrawer.drawCallCount)
    }

    @Test
    fun testFailureIsolationBetweenPipAndOtherPlugins() {
        val host = MockHost()
        val stagePass = OverlayStagePass(host, null, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }

        // Register working visual layer renderer (e.g. TEXT)
        val textRenderer = MockVisualLayerRenderer("text_plugin", setOf("TEXT"))
        stagePass.registerLayerRenderer(textRenderer)

        // Register failing PiP renderer
        var shouldCrash = true
        val failingPipRenderer = object : LayerRenderer {
            override val id: String = "failing_pip"
            override val kinds: Set<String> = setOf("VIDEO")
            var calls = 0
            override fun onGlCreated() {}
            override fun onGlDestroyed() {}
            override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
                calls++
                if (shouldCrash) throw RuntimeException("Simulated PiP decode failure")
            }
        }
        stagePass.registerLayerRenderer(failingPipRenderer)

        val videoItem = OverlayItem(
            id = "crashing_pip",
            type = LayerType.VIDEO,
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
            items = listOf(videoItem, textItem),
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

        // 3 consecutive crashes on PiP
        stagePass.draw(ctx)
        stagePass.draw(ctx)
        stagePass.draw(ctx)
        assertTrue(stagePass.isRendererDisabled("failing_pip"))
        assertFalse(stagePass.isRendererDisabled("text_plugin"))

        // Frame 4: failing PiP is disabled for session; text continues drawing smoothly
        textRenderer.drawnItems.clear()
        stagePass.draw(ctx)

        assertEquals(listOf("working_text"), textRenderer.drawnItems)
    }

    @Test
    fun testPipOrderingAndSelectedOnTop() {
        val host = MockHost()
        val recordedDrawOrder = mutableListOf<String>()

        val pipPlugin = object : LayerRenderer {
            override val id: String = "pip_recorder"
            override val kinds: Set<String> = setOf("VIDEO")
            override fun onGlCreated() {}
            override fun onGlDestroyed() {}
            override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
                recordedDrawOrder.add("${layer.id}:selected=$selected")
            }
        }

        val textPlugin = object : LayerRenderer {
            override val id: String = "text_recorder"
            override val kinds: Set<String> = setOf("TEXT")
            override fun onGlCreated() {}
            override fun onGlDestroyed() {}
            override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
                recordedDrawOrder.add("${layer.id}:selected=$selected")
            }
        }

        val stagePass = OverlayStagePass(host, null, services)
        stagePass.passScopeRunner = { _, _, _, action -> action() }
        stagePass.registerLayerRenderer(pipPlugin)
        stagePass.registerLayerRenderer(textPlugin)

        val pipItem = OverlayItem(
            id = "pip_bottom",
            type = LayerType.VIDEO,
            laneIndex = 0,
            zIndex = 0,
            startUs = 0L,
            endUs = 2_000_000L
        )
        val textItem = OverlayItem(
            id = "text_top",
            type = LayerType.TEXT,
            laneIndex = 1,
            zIndex = 1,
            startUs = 0L,
            endUs = 2_000_000L
        )

        val plan = CompiledLayerPlan(
            items = listOf(pipItem, textItem),
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

        // 1. Unselected: normal z-order (pip_bottom first, text_top second)
        recordedDrawOrder.clear()
        stagePass.draw(ctx)
        assertEquals(listOf("pip_bottom:selected=false", "text_top:selected=false"), recordedDrawOrder)

        // 2. Selected on top: when pip_bottom is selected, text_top is drawn first (pass 1), pip_bottom drawn last (pass 2)
        host.selectedOverlayIdForPreview = "pip_bottom"
        recordedDrawOrder.clear()
        stagePass.draw(ctx)
        assertEquals(listOf("text_top:selected=false", "pip_bottom:selected=true"), recordedDrawOrder)
    }
}
