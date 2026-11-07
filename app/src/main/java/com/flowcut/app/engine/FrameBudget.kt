package com.flowcut.app.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Calculated percentile statistics for a stage.
 */
data class PercentileStats(
    val p50Ns: Long,
    val p95Ns: Long,
    val p99Ns: Long,
    val maxNs: Long,
    val sampleCount: Int
) {
    val p50Ms: Double get() = p50Ns / 1_000_000.0
    val p95Ms: Double get() = p95Ns / 1_000_000.0
    val p99Ms: Double get() = p99Ns / 1_000_000.0
    val maxMs: Double get() = maxNs / 1_000_000.0

    val p50Us: Double get() = p50Ns / 1_000.0
    val p95Us: Double get() = p95Ns / 1_000.0
    val p99Us: Double get() = p99Ns / 1_000.0
    val maxUs: Double get() = maxNs / 1_000.0

    companion object {
        val EMPTY = PercentileStats(0L, 0L, 0L, 0L, 0)
    }
}

/**
 * Budget violation description when a stage exceeds Section 11 thresholds.
 */
data class BudgetViolation(
    val stageName: String,
    val p99Ns: Long,
    val budgetNs: Long,
    val message: String
)

/**
 * Stage recorder using a pre-allocated primitive LongArray ring buffer.
 * Zero heap allocations on the hot path (record).
 */
class StageRecorder(
    val name: String,
    val budgetNs: Long,
    val capacity: Int = 512
) {
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "Capacity must be a power of 2: $capacity"
        }
    }

    private val samples = LongArray(capacity)
    private val scratch = LongArray(capacity)
    private var writeIdx = 0
    private var totalSamples = 0L

    /**
     * Hot path recording.
     * Zero object allocations, zero locks, zero sleep.
     */
    fun record(nanos: Long) {
        samples[writeIdx] = nanos
        writeIdx = (writeIdx + 1) and (capacity - 1)
        totalSamples++
    }

    val sampleCount: Long get() = totalSamples

    fun reset() {
        writeIdx = 0
        totalSamples = 0L
    }

    /**
     * Computes percentiles (p50, p95, p99, max) using the pre-allocated scratch buffer.
     * Called during periodic diagnostics or test assertions (not on hot path).
     */
    fun computePercentiles(): PercentileStats {
        val n = minOf(totalSamples, capacity.toLong()).toInt()
        if (n == 0) return PercentileStats.EMPTY

        System.arraycopy(samples, 0, scratch, 0, n)
        scratch.sort(0, n)

        val idx50 = ((n - 1) * 0.50).toInt().coerceIn(0, n - 1)
        val idx95 = ((n - 1) * 0.95).toInt().coerceIn(0, n - 1)
        val idx99 = ((n - 1) * 0.99).toInt().coerceIn(0, n - 1)

        return PercentileStats(
            p50Ns = scratch[idx50],
            p95Ns = scratch[idx95],
            p99Ns = scratch[idx99],
            maxNs = scratch[n - 1],
            sampleCount = n
        )
    }

    fun isViolating(): Boolean {
        val stats = computePercentiles()
        return stats.sampleCount > 0 && stats.p99Ns > budgetNs
    }
}

/**
 * Nanosecond frame budget monitor (Section 11).
 * Enforces:
 * - Timeline draw (full Canvas): p99 < 4.0 ms
 * - Each LanePainter (100 visible items): p99 < 1.0 ms
 * - GL thread full frame (preview): p99 < 8.0 ms
 * - Audio 10 ms block render: p99 < 2.0 ms
 * - Snapshot build (900 clips + 1000 items): p99 < 50.0 ms
 * - snapshot.resolve (single invocation): p99 < 50 us (50,000 ns)
 * - Main thread single task: p99 < 4.0 ms
 */
object FrameBudget {

    // Section 11 Standard Budget Thresholds (in Nanoseconds)
    const val BUDGET_TIMELINE_DRAW_NS: Long = 4_000_000L      // 4.0 ms
    const val BUDGET_LANE_PAINTER_NS: Long = 1_000_000L        // 1.0 ms
    const val BUDGET_GL_FRAME_NS: Long = 8_000_000L            // 8.0 ms
    const val BUDGET_AUDIO_BLOCK_NS: Long = 2_000_000L         // 2.0 ms
    const val BUDGET_SNAPSHOT_BUILD_NS: Long = 50_000_000L     // 50.0 ms
    const val BUDGET_SNAPSHOT_RESOLVE_NS: Long = 50_000L       // 50 us
    const val BUDGET_MAIN_THREAD_NS: Long = 4_000_000L         // 4.0 ms

    // Predefined Stages
    const val STAGE_TIMELINE_DRAW: String = "TimelineDraw"
    const val STAGE_LANE_PAINTER: String = "LanePainter"
    const val STAGE_GL_FRAME: String = "GLFrame"
    const val STAGE_AUDIO_BLOCK: String = "AudioBlock"
    const val STAGE_SNAPSHOT_BUILD: String = "SnapshotBuild"
    const val STAGE_SNAPSHOT_RESOLVE: String = "SnapshotResolve"
    const val STAGE_MAIN_THREAD: String = "MainThread"

    private val stages = ConcurrentHashMap<String, StageRecorder>()

    init {
        // Register core stages
        registerStage(STAGE_TIMELINE_DRAW, BUDGET_TIMELINE_DRAW_NS)
        registerStage(STAGE_LANE_PAINTER, BUDGET_LANE_PAINTER_NS)
        registerStage(STAGE_GL_FRAME, BUDGET_GL_FRAME_NS)
        registerStage(STAGE_AUDIO_BLOCK, BUDGET_AUDIO_BLOCK_NS)
        registerStage(STAGE_SNAPSHOT_BUILD, BUDGET_SNAPSHOT_BUILD_NS)
        registerStage(STAGE_SNAPSHOT_RESOLVE, BUDGET_SNAPSHOT_RESOLVE_NS)
        registerStage(STAGE_MAIN_THREAD, BUDGET_MAIN_THREAD_NS)
    }

    fun registerStage(name: String, budgetNs: Long = Long.MAX_VALUE): StageRecorder {
        return stages.getOrPut(name) { StageRecorder(name, budgetNs) }
    }

    fun getStage(name: String): StageRecorder? = stages[name]

    fun getOrCreateStage(name: String, defaultBudgetNs: Long = Long.MAX_VALUE): StageRecorder {
        return stages.getOrPut(name) { StageRecorder(name, defaultBudgetNs) }
    }

    /**
     * Hot path record method for a known stage.
     */
    fun record(stageName: String, nanos: Long) {
        stages[stageName]?.record(nanos)
    }

    /**
     * Measure and record duration of block in nanoseconds.
     */
    inline fun <T> measure(stageName: String, block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            val elapsed = System.nanoTime() - start
            record(stageName, elapsed)
        }
    }

    fun reset() {
        stages.values.forEach { it.reset() }
    }

    /**
     * Checks all stages against their Section 11 budgets and returns any violators.
     */
    fun checkBudgets(): List<BudgetViolation> {
        val violations = mutableListOf<BudgetViolation>()
        for ((name, recorder) in stages) {
            val stats = recorder.computePercentiles()
            if (stats.sampleCount > 0 && stats.p99Ns > recorder.budgetNs) {
                violations.add(
                    BudgetViolation(
                        stageName = name,
                        p99Ns = stats.p99Ns,
                        budgetNs = recorder.budgetNs,
                        message = "Stage '$name' violated budget: p99=${String.format("%.3f", stats.p99Ms)} ms, budget=${String.format("%.3f", recorder.budgetNs / 1_000_000.0)} ms"
                    )
                )
            }
        }
        return violations
    }

    /**
     * Generates a 10-second diagnostic report string flagging any violators by name.
     */
    fun formatReport(): String {
        val sb = StringBuilder("=== FlowCut FrameBudget Report ===\n")
        var hasViolations = false

        for ((name, recorder) in stages.entries.sortedBy { it.key }) {
            val stats = recorder.computePercentiles()
            if (stats.sampleCount == 0) continue

            val isViolating = stats.p99Ns > recorder.budgetNs
            if (isViolating) hasViolations = true

            val statusFlag = if (isViolating) " [VIOLATION]" else " [OK]"
            sb.append(
                String.format(
                    "%-18s | samples: %4d | p50: %6.2f ms | p95: %6.2f ms | p99: %6.2f ms | max: %6.2f ms | budget: %6.2f ms%s\n",
                    name,
                    stats.sampleCount,
                    stats.p50Ms,
                    stats.p95Ms,
                    stats.p99Ms,
                    stats.maxMs,
                    recorder.budgetNs / 1_000_000.0,
                    statusFlag
                )
            )
        }

        if (hasViolations) {
            sb.append(">>> BUDGET VIOLATIONS DETECTED! See violators flagged above.\n")
        } else {
            sb.append(">>> All monitored stages within Section 11 budgets.\n")
        }

        return sb.toString()
    }

    /**
     * Periodic logger hook (Section 11).
     */
    fun logDiagnosticReport(tag: String = "FrameBudget") {
        val report = formatReport()
        try {
            android.util.Log.i(tag, report)
        } catch (_: Throwable) {
            println("[$tag] $report")
        }
    }
}

