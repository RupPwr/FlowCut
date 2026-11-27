package com.flowcut.app.editor

import com.flowcut.app.core.model.ClipFilterParams
import com.flowcut.app.core.model.ClipRenderParams
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.engine.AspectRatioMode
import com.flowcut.app.engine.BackgroundType
import com.flowcut.app.engine.ClipBackground
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.ClipTransform
import com.flowcut.app.engine.CompiledLayerPlan
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.engine.RenderStateStore
import com.flowcut.app.audio.ClipAudioContributor
import com.flowcut.app.model.Clip
import com.flowcut.app.model.PREVIEW_H
import com.flowcut.app.model.PREVIEW_W
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.Project
import com.flowcut.app.model.Transition
import com.flowcut.app.model.TransitionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase2ArchitectureConsolidationTest {

    @Before
    fun setUp() {
        RenderStateStore.resetAll()
    }

    @After
    fun tearDown() {
        RenderStateStore.resetAll()
    }

    @Test
    fun testPreviewDimensionConstantsMatchPreviewRenderer() {
        assertEquals(com.flowcut.app.engine.PREVIEW_W, PREVIEW_W)
        assertEquals(com.flowcut.app.engine.PREVIEW_H, PREVIEW_H)
    }

    @Test
    fun testRenderStateStoreDelegatesConsistentlyBetweenPreviewRendererAndAudioContributor() {
        val transform = ClipTransform(rotationDeg = 90f, isFlippedH = true, scale = 1.5f, opacity = 0.8f)
        val adjust = ClipColorAdjust(brightness = 0.2f, clarity = 0.5f, sharpPlus = 0.3f)
        val bg = ClipBackground(type = BackgroundType.BLUR, blurLevel = 0.8f)
        val overlay = OverlayItem(
            id = "ov-1",
            type = LayerType.TEXT,
            laneIndex = 0,
            zIndex = 1,
            startUs = 0L,
            endUs = 2_000_000L,
            text = "SyncTest"
        )
        val plan = CompiledLayerPlan.compile(listOf(overlay))

        RenderStateStore.syncAll(
            transforms = mapOf("c1" to transform),
            colorAdjusts = mapOf("c1" to adjust),
            backgrounds = mapOf("c1" to bg),
            junctionTransitions = mapOf(0 to 4),
            volumes = mapOf("c1" to 0.65f),
            mainTrackMuted = false,
            layerPlan = plan,
            aspectRatioMode = AspectRatioMode.RATIO_16_9
        )

        // PreviewRenderer companion reads exact same unified store
        assertEquals(transform, PreviewRenderer.getClipTransform("c1"))
        assertEquals(adjust, PreviewRenderer.getClipColorAdjust("c1"))
        assertEquals(bg, PreviewRenderer.getClipBackground("c1"))
        assertEquals(4, PreviewRenderer.getJunctionTransitionType(0))
        assertEquals(AspectRatioMode.RATIO_16_9, PreviewRenderer.currentAspectRatioMode)
        assertEquals(16f / 9f, PreviewRenderer.canvasAspectRatio, 0.001f)
        assertEquals(1, PreviewRenderer.currentLayerPlan.items.size)

        // ClipAudioContributor companion reads exact same unified store
        assertEquals(0.65f, ClipAudioContributor.getClipVolume("c1"), 0.0001f)
        assertFalse(ClipAudioContributor.isMainTrackMuted)

        // Atomic metadata copy & remove
        RenderStateStore.copyClipMetadata("c1", "c2", overrideVolume = 0f)
        assertEquals(transform, PreviewRenderer.getClipTransform("c2"))
        assertEquals(adjust, PreviewRenderer.getClipColorAdjust("c2"))
        assertEquals(bg, PreviewRenderer.getClipBackground("c2"))
        assertEquals(0f, ClipAudioContributor.getClipVolume("c2"), 0.0001f)

        RenderStateStore.removeClipMetadata("c1")
        assertEquals(ClipTransform.DEFAULT, PreviewRenderer.getClipTransform("c1"))
        assertEquals(ClipColorAdjust.DEFAULT, PreviewRenderer.getClipColorAdjust("c1"))
        assertEquals(ClipBackground.DEFAULT, PreviewRenderer.getClipBackground("c1"))
        assertEquals(1.0f, ClipAudioContributor.getClipVolume("c1"), 0.0001f)
    }

    @Test
    fun testCanonicalToCoreProjectPreservesTimeMapsTransitionsAndRenderParams() {
        val filterParams = ClipFilterParams(filterPresetId = 5, filterIntensity = 0.75f)
        val clip1 = Clip(
            id = "c1",
            path = "/tmp/1.mp4",
            durationMs = 4000L,
            width = 1080,
            height = 1920,
            rotation = 0,
            frameRate = 30,
            renderParams = ClipRenderParams(filter = filterParams)
        )
        val clip2 = Clip(
            id = "c2",
            path = "/tmp/2.mp4",
            durationMs = 3000L,
            width = 1920,
            height = 1080,
            rotation = 90,
            frameRate = 60
        )
        val project = Project(
            clips = listOf(clip1, clip2),
            transitions = listOf(Transition(TransitionType.SLIDE, 600L))
        )
        val tm = TimeMap(inUs = 500_000L, outUs = 4_500_000L, speedNum = 100, speedDen = 100)

        val snapshot = buildTimelineSnapshot(project, mapOf("c1" to tm), version = 42L)
        assertEquals(42L, snapshot.version)
        assertEquals(2, snapshot.project.clips.size)
        assertEquals(tm, snapshot.project.clips[0].timeMap)
        assertEquals(filterParams, snapshot.project.clips[0].renderParams.filter)
        assertEquals(com.flowcut.app.core.model.TransitionType.SLIDE, snapshot.project.transitions[0].type)
        assertEquals(600L, snapshot.project.transitions[0].durationMs)
    }

    @Test
    fun testClampAdjacentTransitionDurationsEnforcesHalfMinDurationInvariant() {
        val clips = listOf(
            Clip("c1", "/tmp/1.mp4", 1000L, 1080, 1920, 0, 30),
            Clip("c2", "/tmp/2.mp4", 600L, 1080, 1920, 0, 30),
            Clip("c3", "/tmp/3.mp4", 4000L, 1080, 1920, 0, 30)
        )
        val transitions = mutableListOf(
            Transition(TransitionType.FADE, 800L),
            Transition(TransitionType.ZOOM, 500L)
        )

        clampAdjacentTransitionDurations(clips, transitions)

        assertEquals(300L, transitions[0].durationMs) // min(1000, 600) / 2 = 300
        assertEquals(300L, transitions[1].durationMs) // min(600, 4000) / 2 = 300
    }

    @Test
    fun testAspectRatioMatchingAndOriginalRotationHandling() {
        assertEquals(AspectRatioMode.RATIO_16_9, findMatchingAspectRatioModeForDimensions(1920, 1080))
        assertEquals(AspectRatioMode.RATIO_9_16, findMatchingAspectRatioModeForDimensions(1080, 1920))
        assertEquals(AspectRatioMode.RATIO_1_1, findMatchingAspectRatioModeForDimensions(1080, 1080))

        val rotatedClip = Clip("rot", "/tmp/r.mp4", 2000L, width = 1920, height = 1080, rotation = 90, frameRate = 30)
        assertEquals(1080f / 1920f, resolveEffectiveAspectForClip(AspectRatioMode.ORIGINAL, rotatedClip), 0.001f)
    }

    @Test
    fun testOverlayHandleSnapBoundsAndBitrateHelpers() {
        val project = Project(
            clips = listOf(
                Clip("c1", "/tmp/1.mp4", 3000L, 1080, 1920, 0, 30),
                Clip("c2", "/tmp/2.mp4", 4000L, 1080, 1920, 0, 30)
            ),
            transitions = listOf(Transition(TransitionType.NONE, 0L))
        )
        val tl = ProgramTimeline(project)
        val (s, e) = resolveOverlayHandleSnapBounds(
            itemStartUs = 1_000_000L,
            itemEndUs = 2_000_000L,
            isRightHandle = true,
            action = OverlayEndAlignment.VIDEO_END,
            tl = tl
        )
        assertEquals(1_000_000L, s)
        assertEquals(7_000_000L, e)

        assertEquals(14_000_000, computeRecommendedExportBitrateBps(1080, 30))
        assertTrue(computeRecommendedExportBitrateBps(1080, 60) > computeRecommendedExportBitrateBps(1080, 30))
    }
}
