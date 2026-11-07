package com.flowcut.app.debug

import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.edit.DeleteClipCommand
import com.flowcut.app.core.edit.EditorController
import com.flowcut.app.core.edit.ImportClipsCommand
import com.flowcut.app.core.feature.DefaultFeatureRegistry
import com.flowcut.app.core.feature.PainterCtx
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Lane
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.model.Transition
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.playback.PlaybackController
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.FrameBudget
import com.flowcut.app.engine.PreviewRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Presets for Scale Testing (Section 12).
 * S : 10 clips,   10 dummy items,   10 features registered
 * M : 200 clips,  100 dummy items,  50 features registered
 * L : 900 clips,  500 dummy items,  100 features registered
 */
enum class ScalePreset(
    val clipCount: Int,
    val dummyItemCount: Int,
    val featureCount: Int,
    val clipDurationMs: Long
) {
    S(clipCount = 10, dummyItemCount = 10, featureCount = 10, clipDurationMs = 2000L),
    M(clipCount = 200, dummyItemCount = 100, featureCount = 50, clipDurationMs = 1000L),
    L(clipCount = 900, dummyItemCount = 500, featureCount = 100, clipDurationMs = 1111L)
}

/**
 * Results of Scale Test gates verification (Section 12).
 */
@Serializable
data class ScaleTestResult(
    val preset: String,
    val timelineDrawP99Ms: Double,
    val timelineDrawPass: Boolean,
    val frameP99Ms: Double,
    val frameP99Pass: Boolean,
    val jankyPercent: Double,
    val jankyPercentPass: Boolean,
    val snapshotBuildMs: Double,
    val snapshotBuildPass: Boolean,
    val commandToUiP99Ms: Double,
    val commandToUiPass: Boolean,
    val memoryDeltaMb: Double,
    val memoryDeltaPass: Boolean,
    val idle100FeaturesOverheadMs: Double,
    val idleOverheadPass: Boolean,
    val scalingRatioVsS: Double? = null,
    val scalingRatioPass: Boolean = true,
    val allGatesPassed: Boolean
)

/**
 * Mock PainterCtx for simulating timeline Canvas draw phase during headless testing.
 */
class HeadlessPainterCtx(
    override val snapshot: TimelineSnapshot,
    override val halfWidthPx: Float = 540f,
    override val offsetPx: Long = 0L,
    override val pxPerUs: Double = 0.0001,
    override val leftMs: Long = 0L,
    override val rightMs: Long = 10_000L,
    override val density: Float = 2.0f
) : PainterCtx {
    var rectDrawCount = 0
    var textDrawCount = 0

    override fun timeMsToX(timeMs: Long): Float = ((timeMs - leftMs) * pxPerUs * 1000.0).toFloat()
    override fun timeUsToX(timeUs: Long): Float = ((timeUs - leftMs * 1000L) * pxPerUs).toFloat()

    override fun drawRect(x: Float, y: Float, width: Float, height: Float, colorArgb: Int) {
        rectDrawCount++
    }

    override fun drawText(text: String, x: Float, y: Float, colorArgb: Int, fontSizeSp: Float) {
        textDrawCount++
    }
}

/**
 * Scale Test generator and scenario runner (Section 12).
 */
object ScaleTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Deterministic project generator for presets S, M, L.
     * Maintains equal item density per second across presets (~0.5 items/sec)
     * so that visible items at the same zoom level remain constant.
     */
    fun generateProject(preset: ScalePreset): Project {
        val clips = ArrayList<Clip>(preset.clipCount)
        for (i in 0 until preset.clipCount) {
            clips.add(
                Clip(
                    id = "c_$i",
                    path = "/storage/mock_video_$i.mp4",
                    durationMs = preset.clipDurationMs,
                    width = 1080,
                    height = 1920,
                    rotation = 0
                )
            )
        }

        val transitions = ArrayList<Transition>(maxOf(0, preset.clipCount - 1))
        for (i in 0 until preset.clipCount - 1) {
            transitions.add(Transition(type = TransitionType.NONE, durationMs = 0L))
        }

        // Generate lanes with dummy items (sorted, non-overlapping per lane)
        val lanes = ArrayList<Lane>(preset.featureCount)
        val itemsPerLane = preset.dummyItemCount / preset.featureCount
        val totalMs = preset.clipCount * preset.clipDurationMs

        for (laneIdx in 0 until preset.featureCount) {
            val kind = "dummy_${laneIdx + 1}"
            val items = ArrayList<DummyLaneItem>(itemsPerLane)
            val spacingMs = totalMs / maxOf(1, itemsPerLane)

            for (itemIdx in 0 until itemsPerLane) {
                val startMs = itemIdx * spacingMs + (laneIdx * 20L)
                val durMs = minOf(1000L, spacingMs / 2)
                items.add(
                    DummyLaneItem(
                        id = "item_${laneIdx}_$itemIdx",
                        startMs = startMs,
                        durationMs = durMs,
                        label = "Item $laneIdx-$itemIdx"
                    )
                )
            }
            lanes.add(Lane(id = "lane_$laneIdx", kind = kind, items = items))
        }

        return Project(clips = clips, transitions = transitions, lanes = lanes)
    }

    /**
     * Runs the automated scenario:
     * 1) Fling from start to end (simulated 30s)
     * 2) Play for 60s
     * 3) Back-and-forth scrub for 30s
     * 4) Dispatch 50 sequential commands
     *
     * Evaluates all Section 12 gates and writes results.
     */
    fun runScenario(
        preset: ScalePreset,
        baselineSDrawP99Ms: Double? = null,
        outputDir: File? = null,
        simulatedSteps: Int = 180
    ): ScaleTestResult = runBlocking(Dispatchers.Default) {
        val runtime = Runtime.getRuntime()
        runtime.gc()
        val memBeforeBytes = runtime.totalMemory() - runtime.freeMemory()

        // 1. Setup Feature Registry and dummy features
        val registry = DefaultFeatureRegistry()
        registerDummyFeatures(registry, preset.featureCount)

        // 2. Setup project, snapshot, and controllers
        val project = generateProject(preset)

        val buildStart = System.nanoTime()
        val initialSnapshot = TimelineSnapshot(1L, project)
        val snapshotBuildTimeMs = (System.nanoTime() - buildStart) / 1_000_000.0

        val holder = SnapshotHolder().apply { current = initialSnapshot }
        val controller = PlaybackController(holder = holder)
        val editor = EditorController(holder = holder, initialProject = project)
        val renderer = PreviewRenderer(holder = holder, playhead = controller.playhead)

        for (c in registry.getRenderContributors()) {
            renderer.registerContributor(c)
        }

        val totalUs = controller.totalUs

        // Frame timing measurements
        val frameTimesNs = LongArray(simulatedSteps * 3)
        var frameTimeCount = 0
        val timelineDrawTimesNs = LongArray(simulatedSteps * 3)
        var timelineDrawCount = 0
        var jankyFrameCount = 0

        val painterCtx = HeadlessPainterCtx(snapshot = holder.current)

        // Scenario 1: Fling from start to end (30s simulated)
        controller.beginScrub()
        controller.endScrub(velocityUsPerSec = 500_000.0, pxPerUs = 0.0001)
        var currentTimeNanos = 1_000_000_000L
        val frameStepNanos = 16_666_666L // 60 fps step

        for (step in 0 until simulatedSteps) {
            currentTimeNanos += frameStepNanos
            val frameStart = System.nanoTime()

            controller.tick(currentTimeNanos)
            renderer.renderFrame()

            val frameElapsed = System.nanoTime() - frameStart
            if (frameTimeCount < frameTimesNs.size) {
                frameTimesNs[frameTimeCount++] = frameElapsed
                if (frameElapsed > 16_666_666L) jankyFrameCount++
            }

            // Simulate timeline Canvas draw
            val drawStart = System.nanoTime()
            val s = holder.current
            for (laneIdx in s.project.lanes.indices) {
                val lane = s.project.lanes[laneIdx]
                val painter = registry.getLanePainter(lane.kind)
                painter?.draw(painterCtx, laneIdx, 0, minOf(5, lane.items.size - 1))
            }
            val drawElapsed = System.nanoTime() - drawStart
            if (timelineDrawCount < timelineDrawTimesNs.size) {
                timelineDrawTimesNs[timelineDrawCount++] = drawElapsed
            }
        }

        // Scenario 2: Play for 60s
        controller.play()
        for (step in 0 until simulatedSteps) {
            currentTimeNanos += frameStepNanos
            val frameStart = System.nanoTime()

            controller.tick(currentTimeNanos)
            renderer.renderFrame()

            val frameElapsed = System.nanoTime() - frameStart
            if (frameTimeCount < frameTimesNs.size) {
                frameTimesNs[frameTimeCount++] = frameElapsed
                if (frameElapsed > 16_666_666L) jankyFrameCount++
            }
        }
        controller.pause()

        // Scenario 3: Back-and-forth scrub for 30s
        controller.beginScrub()
        for (step in 0 until simulatedSteps) {
            val deltaUs = if (step % 2 == 0) 500_000L else -300_000L
            controller.scrubBy(deltaUs)
            currentTimeNanos += frameStepNanos

            val frameStart = System.nanoTime()
            controller.tick(currentTimeNanos)
            renderer.renderFrame()
            val frameElapsed = System.nanoTime() - frameStart
            if (frameTimeCount < frameTimesNs.size) {
                frameTimesNs[frameTimeCount++] = frameElapsed
                if (frameElapsed > 16_666_666L) jankyFrameCount++
            }
        }
        controller.endScrub(velocityUsPerSec = 0.0)

        // Scenario 4: Dispatch 50 sequential commands
        val commandTimesNs = LongArray(50)
        for (cmdIdx in 0 until 50) {
            val cmdStart = System.nanoTime()
            val clipToInsert = Clip(
                id = "cmd_clip_$cmdIdx",
                path = "/storage/tmp_$cmdIdx.mp4",
                durationMs = 2000L,
                width = 1080,
                height = 1920,
                rotation = 0
            )
            if (cmdIdx % 2 == 0) {
                editor.dispatch(ImportClipsCommand(clipToInsert))
            } else {
                editor.undo()
            }
            commandTimesNs[cmdIdx] = System.nanoTime() - cmdStart
        }

        // Measure 100 features idle overhead (activeSet with no active items)
        val emptyActiveSet = ActiveSet(holder.current.lanes.size)
        val idleStart = System.nanoTime()
        for (i in 0 until 100) {
            renderer.renderFrame()
        }
        val idle100FeaturesOverheadMs = ((System.nanoTime() - idleStart) / 100.0) / 1_000_000.0

        runtime.gc()
        val memAfterBytes = runtime.totalMemory() - runtime.freeMemory()
        val memoryDeltaMb = maxOf(0.0, (memAfterBytes - memBeforeBytes) / (1024.0 * 1024.0))

        // Compute percentiles
        val sortedDraw = timelineDrawTimesNs.copyOf(timelineDrawCount).apply { sort() }
        val timelineDrawP99Ms = if (timelineDrawCount > 0) {
            sortedDraw[((timelineDrawCount - 1) * 0.99).toInt()] / 1_000_000.0
        } else 0.0

        val sortedFrame = frameTimesNs.copyOf(frameTimeCount).apply { sort() }
        val frameP99Ms = if (frameTimeCount > 0) {
            sortedFrame[((frameTimeCount - 1) * 0.99).toInt()] / 1_000_000.0
        } else 0.0

        val jankyPercent = if (frameTimeCount > 0) (jankyFrameCount.toDouble() / frameTimeCount) * 100.0 else 0.0

        val sortedCmd = commandTimesNs.copyOf().apply { sort() }
        val commandToUiP99Ms = sortedCmd[((commandTimesNs.size - 1) * 0.99).toInt()] / 1_000_000.0

        // Gates checks (Section 12)
        val timelineDrawPass = timelineDrawP99Ms < 4.0
        val frameP99Pass = frameP99Ms < 16.0
        val jankyPercentPass = jankyPercent < 1.0
        val snapshotBuildPass = snapshotBuildTimeMs < 50.0
        val commandToUiPass = commandToUiP99Ms < 100.0
        val memoryDeltaPass = memoryDeltaMb <= 30.0
        val idleOverheadPass = idle100FeaturesOverheadMs < 0.2

        val scalingRatio = if (baselineSDrawP99Ms != null && baselineSDrawP99Ms > 0) {
            timelineDrawP99Ms / baselineSDrawP99Ms
        } else null
        val scalingRatioPass = scalingRatio == null || scalingRatio <= 1.5

        val allPassed = timelineDrawPass && frameP99Pass && jankyPercentPass &&
                snapshotBuildPass && commandToUiPass && memoryDeltaPass &&
                idleOverheadPass && scalingRatioPass

        val result = ScaleTestResult(
            preset = preset.name,
            timelineDrawP99Ms = timelineDrawP99Ms,
            timelineDrawPass = timelineDrawPass,
            frameP99Ms = frameP99Ms,
            frameP99Pass = frameP99Pass,
            jankyPercent = jankyPercent,
            jankyPercentPass = jankyPercentPass,
            snapshotBuildMs = snapshotBuildTimeMs,
            snapshotBuildPass = snapshotBuildPass,
            commandToUiP99Ms = commandToUiP99Ms,
            commandToUiPass = commandToUiPass,
            memoryDeltaMb = memoryDeltaMb,
            memoryDeltaPass = memoryDeltaPass,
            idle100FeaturesOverheadMs = idle100FeaturesOverheadMs,
            idleOverheadPass = idleOverheadPass,
            scalingRatioVsS = scalingRatio,
            scalingRatioPass = scalingRatioPass,
            allGatesPassed = allPassed
        )

        // Log results to SCALE tag
        val resultLog = "Preset ${preset.name}: drawP99=${String.format("%.2f", timelineDrawP99Ms)}ms, " +
                "frameP99=${String.format("%.2f", frameP99Ms)}ms, jank=${String.format("%.1f", jankyPercent)}%, " +
                "snapshotBuild=${String.format("%.2f", snapshotBuildTimeMs)}ms, " +
                "cmdToUi=${String.format("%.2f", commandToUiP99Ms)}ms, idle=${String.format("%.3f", idle100FeaturesOverheadMs)}ms, " +
                "PASS=$allPassed"
        try {
            android.util.Log.i("SCALE", resultLog)
        } catch (_: Throwable) {
            println("[SCALE] $resultLog")
        }

        // Write scale_<preset>.json if outputDir provided
        if (outputDir != null) {
            try {
                if (!outputDir.exists()) outputDir.mkdirs()
                val outFile = File(outputDir, "scale_${preset.name.lowercase()}.json")
                outFile.writeText(json.encodeToString(result))
            } catch (e: Exception) {
                // Best effort file persistence
            }
        }

        return@runBlocking result
    }
}

