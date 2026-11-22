package com.flowcut.app.engine

import android.util.Log
import com.flowcut.app.BuildConfig
import com.flowcut.app.core.feature.BaseFrameInfo
import com.flowcut.app.core.feature.DefaultFeatureRegistry
import com.flowcut.app.core.feature.FeatureRegistry
import com.flowcut.app.core.feature.PassCtx
import com.flowcut.app.core.feature.RenderPass
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.TimelineSnapshot

/**
 * Reusable zero-allocation BaseFrameInfo implementation for PreviewRenderer hot path.
 */
class MutableBaseFrameInfo(
    override var textureId: Int = 0,
    override var matrix: FloatArray = FloatArray(16),
    override var crop: FloatArray = floatArrayOf(1f, 1f)
) : BaseFrameInfo {
    fun update(texId: Int, mat: FloatArray, cropVals: FloatArray) {
        this.textureId = texId
        System.arraycopy(mat, 0, this.matrix, 0, 16)
        this.crop[0] = cropVals[0]
        this.crop[1] = cropVals[1]
    }
}

/**
 * Reusable zero-allocation PassCtx implementation for PreviewRenderer hot path.
 */
class MutablePassCtx(
    override var tUs: Long = 0L,
    override var snapshot: TimelineSnapshot = TimelineSnapshot.EMPTY,
    override var activeSet: ActiveSet = ActiveSet(0),
    override var outputWidth: Int = PREVIEW_W,
    override var outputHeight: Int = PREVIEW_H,
    override var baseFrame: BaseFrameInfo = MutableBaseFrameInfo()
) : PassCtx

/**
 * Debug timing snapshot for a registered render pass.
 */
data class PassTiming(
    val id: String,
    val lastDurationNs: Long,
    val avgDurationNs: Double,
    val lastDurationMs: Double,
    val avgDurationMs: Double,
    val frameCount: Long,
    val consecutiveFailures: Int,
    val isDisabled: Boolean
)

/**
 * Central registry and dispatcher for preview render passes (GL thread).
 * Keeps passes sorted by order, manages GL context lifecycles, and executes passes
 * in isolated GlPassScope boundaries with failure isolation and hot-path nanosecond timing.
 */
class PassRegistry {

    companion object {
        const val TAG: String = "PassRegistry"
        const val MAX_CONSECUTIVE_FAILURES: Int = 3
        const val WARN_DURATION_NS: Long = 4_000_000L // 4 ms
    }

    private val lock = Any()
    private val entries = mutableListOf<PassEntry>()

    @Volatile
    private var fastArray: Array<PassEntry> = emptyArray()

    @Volatile
    var isGlContextActive: Boolean = false
        private set

    internal var isDebug: Boolean = BuildConfig.DEBUG

    internal var passScopeRunner: (name: String, width: Int, height: Int, action: () -> Unit) -> Unit = { name, w, h, action ->
        try {
            GlPassScope.runPass(
                passName = name,
                viewportWidth = w,
                viewportHeight = h,
                pass = action
            )
        } catch (e: RuntimeException) {
            if (e.message?.contains("not mocked") == true) {
                // JVM test environment fallback
                action()
            } else {
                throw e
            }
        }
    }

    internal var logError: (tag: String, msg: String, t: Throwable?) -> Unit = { tag, msg, t ->
        try {
            Log.e(tag, msg, t)
        } catch (_: Throwable) {
            System.err.println("[$tag] ERROR: $msg")
            t?.printStackTrace()
        }
    }

    internal var logWarn: (tag: String, msg: String) -> Unit = { tag, msg ->
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] WARN: $msg")
        }
    }

    /**
     * Registers a RenderPass, keeping passes sorted by order.
     * If GL context is already active, invokes onGlCreated() for the new pass.
     */
    fun register(pass: RenderPass) {
        synchronized(lock) {
            if (entries.any { it.pass.id == pass.id }) {
                return
            }
            val entry = PassEntry(pass)
            entries.add(entry)
            entries.sortBy { it.pass.order }
            fastArray = entries.toTypedArray()

            if (isGlContextActive) {
                try {
                    pass.onGlCreated()
                } catch (e: Throwable) {
                    com.flowcut.app.engine.perf.PreviewEventLog.logSwallowedException("PassRegistry.onGlCreated(${pass.id})", e)
                    logError(TAG, "onGlCreated failed for pass '${pass.id}'", e)
                }
            }
        }
    }

    /**
     * Unregisters a RenderPass by ID. If GL context is active, invokes onGlDestroyed().
     */
    fun unregister(passId: String) {
        synchronized(lock) {
            val idx = entries.indexOfFirst { it.pass.id == passId }
            if (idx >= 0) {
                val removed = entries.removeAt(idx)
                fastArray = entries.toTypedArray()
                if (isGlContextActive) {
                    try {
                        removed.pass.onGlDestroyed()
                    } catch (e: Throwable) {
                        com.flowcut.app.engine.perf.PreviewEventLog.logSwallowedException("PassRegistry.onGlDestroyed($passId)", e)
                        logError(TAG, "onGlDestroyed failed for pass '$passId'", e)
                    }
                }
            }
        }
    }

    /**
     * Wires existing and future render passes registered in FeatureRegistry to this PassRegistry.
     */
    fun wireTo(featureRegistry: FeatureRegistry) {
        if (featureRegistry is DefaultFeatureRegistry) {
            featureRegistry.getRenderPasses().forEach { register(it) }
            featureRegistry.onRenderPassAdded = { register(it) }
        }
    }

    /**
     * Invoked on the GL thread when a new GL context is created.
     * Calls onGlCreated() for every registered pass.
     */
    fun onGlCreated() {
        if (isGlContextActive) {
            onGlDestroyed()
        }
        isGlContextActive = true
        val array = fastArray
        for (i in array.indices) {
            try {
                array[i].pass.onGlCreated()
            } catch (e: Throwable) {
                logError(TAG, "onGlCreated failed for pass '${array[i].pass.id}'", e)
            }
        }
    }

    /**
     * Invoked on the GL thread when the GL context is lost or renderer is released.
     * Calls onGlDestroyed() for every registered pass.
     */
    fun onGlDestroyed() {
        isGlContextActive = false
        val array = fastArray
        for (i in array.indices) {
            try {
                array[i].pass.onGlDestroyed()
            } catch (e: Throwable) {
                logError(TAG, "onGlDestroyed failed for pass '${array[i].pass.id}'", e)
            }
        }
    }

    /**
     * Executes all enabled passes sorted by order inside GlPassScope.runPass boundaries.
     * Hot path: zero heap allocations.
     * Disables passes that fail 3 consecutive frames and logs an error once.
     */
    fun drawAll(ctx: PassCtx) {
        val array = fastArray
        for (i in array.indices) {
            val entry = array[i]
            if (entry.disabled) continue

            var passThrew = false
            var caughtThrowable: Throwable? = null
            val startNs = if (isDebug) System.nanoTime() else 0L

            try {
                passScopeRunner(entry.pass.id, ctx.outputWidth, ctx.outputHeight) {
                    try {
                        entry.pass.draw(ctx)
                    } catch (t: Throwable) {
                        passThrew = true
                        caughtThrowable = t
                        throw t
                    }
                }
            } catch (t: Throwable) {
                com.flowcut.app.engine.perf.PreviewEventLog.logSwallowedException("PassRegistry.renderActivePasses(${entry.pass.id})", t)
                passThrew = true
                if (caughtThrowable == null) {
                    caughtThrowable = t
                }
            }

            if (isDebug) {
                val durationNs = System.nanoTime() - startNs
                entry.recordTiming(durationNs)
                if (durationNs > WARN_DURATION_NS) {
                    logWarn(TAG, "Pass '${entry.pass.id}' took ${durationNs / 1_000_000.0} ms (> 4 ms threshold)")
                }
            }

            if (passThrew) {
                entry.consecutiveFailures++
                if (entry.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !entry.disabled) {
                    entry.disabled = true
                    if (!entry.disabledLogged) {
                        entry.disabledLogged = true
                        logError(
                            TAG,
                            "RenderPass '${entry.pass.id}' disabled for the session after $MAX_CONSECUTIVE_FAILURES consecutive failures",
                            caughtThrowable
                        )
                    }
                }
            } else {
                entry.consecutiveFailures = 0
            }
        }
    }

    fun isPassDisabled(passId: String): Boolean {
        return fastArray.firstOrNull { it.pass.id == passId }?.disabled ?: false
    }

    fun getPasses(): List<RenderPass> = fastArray.map { it.pass }

    fun getPassTiming(id: String): PassTiming? {
        val entry = fastArray.firstOrNull { it.pass.id == id } ?: return null
        return PassTiming(
            id = entry.pass.id,
            lastDurationNs = entry.lastDurationNs,
            avgDurationNs = entry.avgDurationNs,
            lastDurationMs = entry.lastDurationNs / 1_000_000.0,
            avgDurationMs = entry.avgDurationNs / 1_000_000.0,
            frameCount = entry.frameCount,
            consecutiveFailures = entry.consecutiveFailures,
            isDisabled = entry.disabled
        )
    }

    fun getPassTimings(): List<PassTiming> {
        return fastArray.map { entry ->
            PassTiming(
                id = entry.pass.id,
                lastDurationNs = entry.lastDurationNs,
                avgDurationNs = entry.avgDurationNs,
                lastDurationMs = entry.lastDurationNs / 1_000_000.0,
                avgDurationMs = entry.avgDurationNs / 1_000_000.0,
                frameCount = entry.frameCount,
                consecutiveFailures = entry.consecutiveFailures,
                isDisabled = entry.disabled
            )
        }
    }

    private class PassEntry(val pass: RenderPass) {
        var consecutiveFailures: Int = 0
        var disabled: Boolean = false
        var disabledLogged: Boolean = false
        var lastDurationNs: Long = 0L
        var totalDurationNs: Long = 0L
        var frameCount: Long = 0L

        val avgDurationNs: Double
            get() = if (frameCount > 0L) totalDurationNs.toDouble() / frameCount else 0.0

        fun recordTiming(durationNs: Long) {
            lastDurationNs = durationNs
            totalDurationNs += durationNs
            frameCount++
        }
    }
}
