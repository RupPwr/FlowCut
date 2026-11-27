package com.flowcut.app.editor

import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.ClipFilterParams
import com.flowcut.app.core.model.ClipRenderParams
import com.flowcut.app.engine.ActiveLayerSet
import com.flowcut.app.engine.CompiledLayerPlan
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.PipDecodePolicy
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.engine.RenderStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3Step2FeatureStabilityTest {

    @Test
    fun captionParser_parsesSrtAndWebVttWithOffsetAndStripsHtml() {
        val srt = """
            1
            00:00:01,500 --> 00:00:03,250
            <i>Hello</i> world!

            2
            00:04.000 --> 00:04.050
            <b>Fast cue</b>
            Second line
        """.trimIndent()

        assertTrue(CaptionParser.containsSrtTimestamps(srt))
        val cues = CaptionParser.parseSrt(srt, offsetUs = 500_000L, minDurationUs = 200_000L)
        assertEquals(2, cues.size)

        assertEquals("Hello world!", cues[0].text)
        assertEquals(2_000_000L, cues[0].startUs)
        assertEquals(3_750_000L, cues[0].endUs)

        assertEquals("Fast cue\nSecond line", cues[1].text)
        assertEquals(4_500_000L, cues[1].startUs)
        // 50ms cue clamped up to minDurationUs (200ms)
        assertEquals(4_700_000L, cues[1].endUs)
    }

    @Test
    fun captionParser_autoSplitsLongParagraphAndWeightsDurationProportionally() {
        val paragraph = "Welcome back everyone to another exciting tutorial on editing smooth mobile videos easily. Let us dive right in!"
        val split = CaptionParser.splitScriptIntoLines(paragraph, maxWordsPerCue = 8)
        assertTrue("Expected paragraph to split into at least 3 readable cues", split.size >= 3)
        assertTrue(split.all { it.split(" ").size <= 8 })

        val cues = CaptionParser.distributePhrasesToTimedCues(
            phrases = listOf("Hi!", "This is a much longer subtitle line that needs more reading time."),
            startUs = 1_000_000L,
            endUs = 11_000_000L,
            minDurationUs = 200_000L
        )
        assertEquals(2, cues.size)
        assertEquals(1_000_000L, cues[0].startUs)
        assertEquals(cues[0].endUs, cues[1].startUs)
        assertEquals(11_000_000L, cues[1].endUs)
        assertTrue("Longer phrase should get significantly more duration", cues[1].durationUs > cues[0].durationUs * 2)
    }

    @Test
    fun pipDecodePolicy_preservesExportAccuracyAndAdaptsOnSlowOrScrubbingPreview() {
        // Export always uses exact frame decoding
        assertFalse(
            PipDecodePolicy.shouldUseKeyframeFastPath(
                isSynchronousExport = true,
                hasReadyProxy = false,
                lastBucket = 0L,
                targetBucket = 50L,
                lastDecodeDurationMs = 200L
            )
        )
        // Ready proxy always uses exact frame decoding
        assertFalse(
            PipDecodePolicy.shouldUseKeyframeFastPath(
                isSynchronousExport = false,
                hasReadyProxy = true,
                lastBucket = 0L,
                targetBucket = 50L,
                lastDecodeDurationMs = 200L
            )
        )
        // Normal playback on fast unproxied media uses exact decoding
        assertFalse(
            PipDecodePolicy.shouldUseKeyframeFastPath(
                isSynchronousExport = false,
                hasReadyProxy = false,
                lastBucket = 10L,
                targetBucket = 11L,
                lastDecodeDurationMs = 18L
            )
        )
        // Fast scrub jump (> 2 buckets) switches to keyframe fast path
        assertTrue(
            PipDecodePolicy.shouldUseKeyframeFastPath(
                isSynchronousExport = false,
                hasReadyProxy = false,
                lastBucket = 10L,
                targetBucket = 15L,
                lastDecodeDurationMs = 15L
            )
        )
        // Slow unproxied decode (>= 55ms) switches to keyframe fast path
        assertTrue(
            PipDecodePolicy.shouldUseKeyframeFastPath(
                isSynchronousExport = false,
                hasReadyProxy = false,
                lastBucket = 10L,
                targetBucket = 11L,
                lastDecodeDurationMs = 80L
            )
        )

        // Failure retry cooldown prevents 60fps spin on corrupt frames
        assertTrue(PipDecodePolicy.shouldSkipFailedBucket(failedBucket = 5L, targetBucket = 5L, lastFailedAtMs = 1000L, nowMs = 1200L))
        assertFalse(PipDecodePolicy.shouldSkipFailedBucket(failedBucket = 5L, targetBucket = 5L, lastFailedAtMs = 1000L, nowMs = 1600L))
        assertFalse(PipDecodePolicy.shouldSkipFailedBucket(failedBucket = 5L, targetBucket = 6L, lastFailedAtMs = 1000L, nowMs = 1200L))
    }

    @Test
    fun shiftJunctionModes_staysAlignedAcrossSplitDuplicateAndDelete() {
        // 4 clips -> junctions 0, 1, 2
        val initial = mapOf(0 to 2, 1 to 4, 2 to 6)

        // Split/duplicate clip 1 -> inserts NONE at junction 1 -> old 1->2, old 2->3
        val afterInsert = shiftJunctionModesOnInsert(initial, insertedJunctionIndex = 1)
        assertEquals(mapOf(0 to 2, 2 to 4, 3 to 6), afterInsert)

        // Delete first clip (0) from 4 clips -> junction 0 dropped, 1->0, 2->1
        assertEquals(mapOf(0 to 4, 1 to 6), shiftJunctionModesOnDeleteClip(initial, deletedClipIndex = 0, oldClipCount = 4))

        // Delete middle clip (1) from 4 clips -> junctions 0 and 1 collapse to NONE, junction 2->1
        assertEquals(mapOf(1 to 6), shiftJunctionModesOnDeleteClip(initial, deletedClipIndex = 1, oldClipCount = 4))

        // Delete last clip (3) from 4 clips -> junction 2 dropped, 0 and 1 preserved
        assertEquals(mapOf(0 to 2, 1 to 4), shiftJunctionModesOnDeleteClip(initial, deletedClipIndex = 3, oldClipCount = 4))

        // Delete from 2-clip project -> 1 clip left -> 0 junctions
        assertTrue(shiftJunctionModesOnDeleteClip(mapOf(0 to 4), deletedClipIndex = 0, oldClipCount = 2).isEmpty())
    }

    @Test
    fun resolveFilterAndEffectState_respectsLiveOverrideAndPinnedLayersForPreviewExportParity() {
        RenderStateStore.resetAll()
        try {
            val clip = Clip(
                id = "c1",
                path = "/tmp/a.mp4",
                durationMs = 5000L,
                width = 1080,
                height = 1920,
                rotation = 0,
                frameRate = 30,
                renderParams = ClipRenderParams(
                    filter = ClipFilterParams(filterPresetId = 2, filterIntensity = 0.6f)
                )
            )

            // Live override takes priority over static clip renderParams
            PreviewRenderer.setClipFilter("c1", presetId = 5, intensity = 0.85f)
            val resolvedOverride = PreviewRenderer.resolveFilterAndEffectState(
                clip = clip,
                liveOverride = PreviewRenderer.liveFilterOverrides[clip.id]
            )
            assertEquals(5, resolvedOverride.filterPresetId)
            assertEquals(0.85f, resolvedOverride.filterIntensity, 0.0001f)

            // Pinned LayerType.FILTER overrides clip-level filter during its active range
            val pinFilter = OverlayItem(
                id = "f1",
                type = LayerType.FILTER,
                laneIndex = 0,
                zIndex = 1,
                startUs = 1_000_000L,
                endUs = 3_000_000L,
                filterPresetId = 9,
                filterIntensity = 0.9f
            )
            val plan = CompiledLayerPlan.compile(listOf(pinFilter))
            val activeSet = ActiveLayerSet()

            val insidePin = PreviewRenderer.resolveFilterAndEffectState(
                clip = clip,
                liveOverride = PreviewRenderer.liveFilterOverrides[clip.id],
                plan = plan,
                tUs = 2_000_000L,
                activeLayerSet = activeSet
            )
            assertEquals(9, insidePin.filterPresetId)
            assertEquals(0.9f, insidePin.filterIntensity, 0.0001f)
        } finally {
            RenderStateStore.resetAll()
        }
    }
}
