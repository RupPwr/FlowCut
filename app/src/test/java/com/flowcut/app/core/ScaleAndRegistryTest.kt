package com.flowcut.app.core

import com.flowcut.app.core.feature.DefaultFeatureRegistry
import com.flowcut.app.core.feature.FrameCtx
import com.flowcut.app.core.feature.RenderContributor
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.debug.DummyFeature
import com.flowcut.app.debug.ScalePreset
import com.flowcut.app.debug.ScaleTest
import com.flowcut.app.debug.registerDummyFeatures
import com.flowcut.app.engine.FrameBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScaleAndRegistryTest {

    private class MockContributor(
        override val order: Int,
        override val kinds: Set<String> = emptySet()
    ) : RenderContributor {
        override fun onFrame(f: FrameCtx) {}
        override fun release() {}
    }

    // ---------------------------------------------------------------------------------------------
    // 1. DefaultFeatureRegistry registration, sorting by order, and unknown item hook
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testDefaultFeatureRegistry_RegistrationAndOrderSorting() {
        val registry = DefaultFeatureRegistry()

        val c100 = MockContributor(order = 100)
        val c10 = MockContributor(order = 10)
        val c50 = MockContributor(order = 50)
        val c20 = MockContributor(order = 20)

        registry.renderContributor(c100)
        registry.renderContributor(c10)
        registry.renderContributor(c50)
        registry.renderContributor(c20)

        val sorted = registry.getRenderContributors()
        assertEquals(4, sorted.size)
        assertEquals(10, sorted[0].order)
        assertEquals(20, sorted[1].order)
        assertEquals(50, sorted[2].order)
        assertEquals(100, sorted[3].order)
    }

    @Test
    fun testDefaultFeatureRegistry_UnknownItemSafeSkipping() {
        val registry = DefaultFeatureRegistry()
        registry.itemType("text")
        registry.itemType("sticker")

        assertTrue(registry.isItemTypeRegistered("text"))
        assertTrue(registry.isItemTypeRegistered("sticker"))
        assertFalse(registry.isItemTypeRegistered("unknown_legacy_filter"))

        var warnedKind: String? = null
        var warnedItemId: String? = null

        registry.unknownItemWarningHook = { kind, itemId ->
            warnedKind = kind
            warnedItemId = itemId
        }

        // Simulating encounter of unknown item during project handling
        if (!registry.isItemTypeRegistered("unknown_legacy_filter")) {
            registry.handleUnknownItem("unknown_legacy_filter", "item_42")
        }

        assertEquals("unknown_legacy_filter", warnedKind)
        assertEquals("item_42", warnedItemId)
    }

    @Test
    fun testDefaultFeatureRegistry_100DummyFeatures() {
        val registry = DefaultFeatureRegistry()
        val dummyFeatures = registerDummyFeatures(registry, 100)

        assertEquals(100, dummyFeatures.size)
        assertEquals(100, registry.getRenderContributors().size)
        assertEquals(100, registry.getAudioContributors().size)

        // Verify each kind is registered
        for (i in 1..100) {
            val kind = "dummy_$i"
            assertTrue("Kind $kind should be registered", registry.isItemTypeRegistered(kind))
            assertNotNull("Lane painter for $kind should exist", registry.getLanePainter(kind))
        }

        // Test precomputeContributorLaneMap against a project
        val project = ScaleTest.generateProject(ScalePreset.S)
        val snapshot = TimelineSnapshot(1L, project)
        val contributorMap = registry.precomputeContributorLaneMap(snapshot)

        assertEquals(100, contributorMap.size)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. FrameBudget zero-allocation recording, percentiles, and violation detection
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testFrameBudget_RecordingAndPercentiles() {
        FrameBudget.reset()
        val stageName = "TestStage"
        val recorder = FrameBudget.getOrCreateStage(stageName, defaultBudgetNs = 10_000_000L) // 10 ms budget
        recorder.reset()

        // Record 100 samples from 1ms up to 100ms
        for (i in 1..100) {
            recorder.record(i * 1_000_000L) // i ms
        }

        val stats = recorder.computePercentiles()
        assertEquals(100, stats.sampleCount)

        // p50 should be ~50ms
        assertTrue("p50 should be around 50ms: ${stats.p50Ms}", stats.p50Ms in 49.0..51.0)
        // p95 should be ~95ms
        assertTrue("p95 should be around 95ms: ${stats.p95Ms}", stats.p95Ms in 94.0..96.0)
        // p99 should be ~99ms
        assertTrue("p99 should be around 99ms: ${stats.p99Ms}", stats.p99Ms in 98.0..100.0)
        // max should be 100ms
        assertEquals(100.0, stats.maxMs, 0.001)

        // Since budget was 10ms and p99 is ~99ms, it should be violating
        assertTrue(recorder.isViolating())

        val violations = FrameBudget.checkBudgets()
        assertTrue(violations.any { it.stageName == stageName })

        val report = FrameBudget.formatReport()
        assertTrue(report.contains(stageName))
        assertTrue(report.contains("VIOLATIONS DETECTED"))
    }

    @Test
    fun testFrameBudget_WithinBudgetCheck() {
        val recorder = FrameBudget.getOrCreateStage("FastStage", defaultBudgetNs = 4_000_000L) // 4 ms
        recorder.reset()

        // Record fast samples (all <= 1ms)
        for (i in 1..100) {
            recorder.record(500_000L) // 0.5 ms
        }

        assertFalse(recorder.isViolating())
    }

    // ---------------------------------------------------------------------------------------------
    // 3. ScaleTest project generator and deterministic properties
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testScaleTest_ProjectGenerator_Presets() {
        val projS = ScaleTest.generateProject(ScalePreset.S)
        assertEquals(10, projS.clips.size)
        assertEquals(9, projS.transitions.size)
        assertEquals(10, projS.lanes.size)
        assertEquals(10, projS.lanes.sumOf { it.items.size })

        val projM = ScaleTest.generateProject(ScalePreset.M)
        assertEquals(200, projM.clips.size)
        assertEquals(199, projM.transitions.size)
        assertEquals(50, projM.lanes.size)
        assertEquals(100, projM.lanes.sumOf { it.items.size })

        val projL = ScaleTest.generateProject(ScalePreset.L)
        assertEquals(900, projL.clips.size)
        assertEquals(899, projL.transitions.size)
        assertEquals(100, projL.lanes.size)
        assertEquals(500, projL.lanes.sumOf { it.items.size })

        // Check non-overlapping items invariant in every lane
        for (lane in projL.lanes) {
            for (i in 0 until lane.items.size - 1) {
                val currentEnd = lane.items[i].startMs + lane.items[i].durationMs
                val nextStart = lane.items[i + 1].startMs
                assertTrue("Lane items must not overlap: $currentEnd <= $nextStart", currentEnd <= nextStart)
            }
        }
    }

    @Test
    fun testScaleTest_PresetL_SnapshotBuildAndResolveBudgets() {
        val projL = ScaleTest.generateProject(ScalePreset.L)

        // Gate: Snapshot build (L) < 50 ms
        val buildStart = System.nanoTime()
        val snapshot = TimelineSnapshot(1L, projL)
        val buildDurationMs = (System.nanoTime() - buildStart) / 1_000_000.0

        assertTrue(
            "Snapshot build for Preset L must be < 50 ms, actual: $buildDurationMs ms",
            buildDurationMs < 50.0
        )

        // Gate: snapshot.resolve < 50 us (50,000 ns)
        val activeSet = ActiveSet(snapshot.lanes.size)
        val warmupRuns = 1000
        for (i in 0 until warmupRuns) {
            snapshot.resolve((i * 100_000L), activeSet)
        }

        val testRuns = 500
        val startResolve = System.nanoTime()
        for (i in 0 until testRuns) {
            snapshot.resolve((i * 100_000L), activeSet)
        }
        val avgResolveNs = (System.nanoTime() - startResolve) / testRuns.toDouble()
        val avgResolveUs = avgResolveNs / 1000.0

        assertTrue(
            "snapshot.resolve for Preset L must be < 50 us, actual: $avgResolveUs us ($avgResolveNs ns)",
            avgResolveUs < 50.0
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Automated scenario runner across S, M, L gates
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testScaleTest_ScenarioRunner_S_M_L_Gates() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "scale_test_output")

        // 1. Run Preset S
        val resS = ScaleTest.runScenario(
            preset = ScalePreset.S,
            outputDir = tempDir,
            simulatedSteps = 60
        )
        assertTrue("Preset S timelineDrawPass failed: ${resS.timelineDrawP99Ms} ms", resS.timelineDrawPass)
        assertTrue("Preset S frameP99Pass failed: ${resS.frameP99Ms} ms", resS.frameP99Pass)
        assertTrue("Preset S snapshotBuildPass failed: ${resS.snapshotBuildMs} ms", resS.snapshotBuildPass)
        assertTrue("Preset S allGatesPassed failed", resS.allGatesPassed)

        // 2. Run Preset M
        val resM = ScaleTest.runScenario(
            preset = ScalePreset.M,
            baselineSDrawP99Ms = resS.timelineDrawP99Ms,
            outputDir = tempDir,
            simulatedSteps = 60
        )
        assertTrue("Preset M allGatesPassed failed", resM.allGatesPassed)

        // 3. Run Preset L
        val resL = ScaleTest.runScenario(
            preset = ScalePreset.L,
            baselineSDrawP99Ms = resS.timelineDrawP99Ms,
            outputDir = tempDir,
            simulatedSteps = 60
        )
        assertTrue("Preset L snapshotBuildPass failed: ${resL.snapshotBuildMs} ms", resL.snapshotBuildPass)
        assertTrue("Preset L timelineDrawPass failed: ${resL.timelineDrawP99Ms} ms", resL.timelineDrawPass)
        assertTrue("Preset L frameP99Pass failed: ${resL.frameP99Ms} ms", resL.frameP99Pass)
        assertTrue("Preset L idleOverheadPass failed: ${resL.idle100FeaturesOverheadMs} ms", resL.idleOverheadPass)
        assertTrue("Preset L scalingRatioPass failed: ${resL.scalingRatioVsS}", resL.scalingRatioPass)
        assertTrue("Preset L allGatesPassed failed", resL.allGatesPassed)

        // Verify JSON files were generated
        assertTrue(File(tempDir, "scale_s.json").exists())
        assertTrue(File(tempDir, "scale_m.json").exists())
        assertTrue(File(tempDir, "scale_l.json").exists())

        // Cleanup temp dir
        tempDir.deleteRecursively()
    }
}

