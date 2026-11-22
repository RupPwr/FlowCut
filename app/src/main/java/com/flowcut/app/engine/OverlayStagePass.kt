package com.flowcut.app.engine

import android.opengl.GLES30
import android.util.Log
import com.flowcut.app.BuildConfig
import com.flowcut.app.core.feature.DefaultFeatureRegistry
import com.flowcut.app.core.feature.FeatureRegistry
import com.flowcut.app.core.feature.LayerRenderer
import com.flowcut.app.core.feature.PassCtx
import com.flowcut.app.core.feature.RenderPass

data class LayerRendererTiming(
    val id: String,
    val kinds: Set<String>,
    val lastDurationNs: Long,
    val avgDurationNs: Double,
    val lastDurationMs: Double,
    val avgDurationMs: Double,
    val frameCount: Long,
    val consecutiveFailures: Int,
    val isDisabled: Boolean
)

/**
 * Host contract providing access to active layer container and selected overlay id.
 */
interface OverlayHost {
    val activeLayerSet: ActiveLayerSet
    val selectedOverlayIdForPreview: String?
}

/**
 * RenderPass executing the overlay stage by walking active visual layers in deterministic
 * z-order (and selected-on-top order), dispatching each layer to its registered LayerRenderer.
 */
class OverlayStagePass(
    private val host: OverlayHost,
    val legacyRenderer: LayerRenderer? = null,
    private val servicesProvider: (() -> RenderServices?)? = null
) : RenderPass {

    constructor(
        host: OverlayHost,
        renderServices: RenderServices?
    ) : this(host, null, { renderServices })

    constructor(
        host: OverlayHost,
        legacyRenderer: LayerRenderer?,
        renderServices: RenderServices?
    ) : this(host, legacyRenderer, { renderServices })

    constructor(renderer: PreviewRenderer) : this(
        host = renderer,
        legacyRenderer = null,
        servicesProvider = {
            val qd: QuadDrawer? = renderer.transitionRenderer
            val tp: TextureProvider? = renderer.overlayTextureCache
            val md: MosaicDrawer? = renderer.transitionRenderer
            val pip: PipTextureProvider? = renderer.overlayTextureCache
            if (qd != null && tp != null && md != null && pip != null) {
                DefaultRenderServices(qd, tp, md, pip)
            } else {
                null
            }
        }
    )

    override val id: String = ID
    override val order: Int = ORDER

    companion object {
        const val ID: String = "overlay_stage"
        const val ORDER: Int = 100
        const val TAG: String = "OverlayStagePass"
        const val MAX_CONSECUTIVE_FAILURES: Int = 3
        const val WARN_DURATION_NS: Long = 4_000_000L // 4 ms
    }

    private val lock = Any()
    private val legacyEntry = legacyRenderer?.let { RendererEntry(it) }
    private val customRenderers = mutableListOf<RendererEntry>()

    // Pre-allocated fast lookup array indexed by LayerType.ordinal for zero-allocation hot path
    @Volatile
    private var kindToEntry: Array<RendererEntry?> = arrayOfNulls(LayerType.values().size)
    private val legacySupportsKind = BooleanArray(LayerType.values().size)
    private val unknownKindsLogged = mutableSetOf<String>()

    @Volatile
    var isGlContextActive: Boolean = false
        private set

    internal val isDebug: Boolean = BuildConfig.PERF_TELEMETRY

    private fun setupOverlayGlState() {
        try {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        } catch (_: Throwable) {
            // JVM test fallback
        }
    }

    internal var passScopeRunner: (name: String, width: Int, height: Int, action: () -> Unit) -> Unit = { name, w, h, action ->
        try {
            GlPassScope.runPass(
                passName = name,
                viewportWidth = w,
                viewportHeight = h,
                enableBlend = true,
                enableScissor = false,
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

    init {
        rebuildLookupTable()
    }

    private var cachedRenderServices: RenderServices? = null
    val renderServices: RenderServices?
        get() {
            var s = cachedRenderServices
            if (s == null) {
                s = servicesProvider?.invoke()
                cachedRenderServices = s
            }
            return s
        }

    private fun attachServicesIfNeeded(layerRenderer: LayerRenderer) {
        if (layerRenderer is RenderServicesAware) {
            renderServices?.let { layerRenderer.attachRenderServices(it) }
        }
    }

    /**
     * Registers a specific LayerRenderer for its declared kinds.
     * Specific renderers take priority over the legacy fallback.
     */
    fun registerLayerRenderer(layerRenderer: LayerRenderer) {
        synchronized(lock) {
            if (customRenderers.any { it.renderer.id == layerRenderer.id }) return
            attachServicesIfNeeded(layerRenderer)
            val entry = RendererEntry(layerRenderer)
            customRenderers.add(entry)
            rebuildLookupTable()

            if (isGlContextActive) {
                try {
                    layerRenderer.onGlCreated()
                } catch (e: Throwable) {
                    com.flowcut.app.engine.perf.PreviewEventLog.logSwallowedException("OverlayStagePass.onGlCreated(${layerRenderer.id})", e)
                    logError(TAG, "onGlCreated failed for layer renderer '${layerRenderer.id}'", e)
                }
            }
        }
    }

    /**
     * Unregisters a specific LayerRenderer.
     */
    fun unregisterLayerRenderer(rendererId: String) {
        synchronized(lock) {
            val idx = customRenderers.indexOfFirst { it.renderer.id == rendererId }
            if (idx >= 0) {
                val removed = customRenderers.removeAt(idx)
                rebuildLookupTable()
                if (isGlContextActive) {
                    try {
                        removed.renderer.onGlDestroyed()
                    } catch (e: Throwable) {
                        com.flowcut.app.engine.perf.PreviewEventLog.logSwallowedException("OverlayStagePass.onGlDestroyed($rendererId)", e)
                        logError(TAG, "onGlDestroyed failed for layer renderer '$rendererId'", e)
                    }
                }
            }
        }
    }

    /**
     * Connects FeatureRegistry layer renderer registrations to this pass.
     */
    fun wireTo(featureRegistry: FeatureRegistry) {
        if (featureRegistry is DefaultFeatureRegistry) {
            featureRegistry.getLayerRenderers().forEach { registerLayerRenderer(it) }
            featureRegistry.onLayerRendererAdded = { registerLayerRenderer(it) }
        }
    }

    private fun rebuildLookupTable() {
        val table = arrayOfNulls<RendererEntry>(LayerType.values().size)
        for (type in LayerType.values()) {
            val typeName = type.name
            val specific = customRenderers.firstOrNull { entry ->
                entry.renderer.kinds.any { it.equals(typeName, ignoreCase = true) }
            }
            table[type.ordinal] = specific ?: legacyEntry
            legacySupportsKind[type.ordinal] = legacyEntry?.renderer?.kinds?.any { it.equals(typeName, ignoreCase = true) } == true
        }
        kindToEntry = table
    }

    override fun onGlCreated() {
        isGlContextActive = true
        legacyRenderer?.onGlCreated()
        val list = synchronized(lock) { customRenderers.toList() }
        for (entry in list) {
            try {
                attachServicesIfNeeded(entry.renderer)
                entry.renderer.onGlCreated()
            } catch (e: Throwable) {
                logError(TAG, "onGlCreated failed for layer renderer '${entry.renderer.id}'", e)
            }
        }
    }

    override fun onGlDestroyed() {
        isGlContextActive = false
        cachedRenderServices = null
        legacyRenderer?.onGlDestroyed()
        val list = synchronized(lock) { customRenderers.toList() }
        for (entry in list) {
            try {
                entry.renderer.onGlDestroyed()
            } catch (e: Throwable) {
                logError(TAG, "onGlDestroyed failed for layer renderer '${entry.renderer.id}'", e)
            }
        }
    }

    override fun draw(ctx: PassCtx) {
        val plan = PreviewRenderer.currentLayerPlan
        if (plan.segments.isEmpty()) return
        val found = plan.resolveAt(ctx.tUs, host.activeLayerSet)
        if (!found || host.activeLayerSet.visualCount == 0) return

        passScopeRunner(ID, ctx.outputWidth, ctx.outputHeight) {
            val selId = host.selectedOverlayIdForPreview
            val table = kindToEntry
            var selectedItemIndex = -1

            // Pass 1: Render unselected items in normal z-order
            val visualCount = host.activeLayerSet.visualCount
            for (i in 0 until visualCount) {
                val itemIndex = host.activeLayerSet.activeVisualIndices[i]
                val item = plan.items.getOrNull(itemIndex) ?: continue
                if (selId != null && item.id == selId) {
                    selectedItemIndex = itemIndex
                    continue
                }
                drawItem(item, ctx, selected = false, table = table)
            }

            // Pass 2: Render selected visual overlay item last (topmost) without mutating stored zIndex (Rule R10)
            if (selectedItemIndex >= 0) {
                val item = plan.items.getOrNull(selectedItemIndex)
                if (item != null) {
                    drawItem(item, ctx, selected = true, table = table)
                }
            }
        }
    }

    private fun drawItem(item: OverlayItem, ctx: PassCtx, selected: Boolean, table: Array<RendererEntry?>) {
        var entry = if (item.type.ordinal in table.indices) table[item.type.ordinal] else null
        if (entry == null || entry.disabled) {
            entry = legacyEntry
        }
        if (entry == null || entry.disabled) {
            val kindName = item.type.name
            if (isDebug && unknownKindsLogged.add(kindName)) {
                logWarn(TAG, "No LayerRenderer registered for kind '$kindName', skipping layer '${item.id}'")
            }
            return
        }
        if (entry === legacyEntry && !legacySupportsKind[item.type.ordinal]) {
            val kindName = item.type.name
            if (isDebug && unknownKindsLogged.add(kindName)) {
                logWarn(TAG, "No LayerRenderer registered for kind '$kindName', skipping layer '${item.id}'")
            }
            return
        }

        attachServicesIfNeeded(entry.renderer)

        val target = entry
        var threw = false
        var caughtThrowable: Throwable? = null
        val startNs = if (BuildConfig.PERF_TELEMETRY) System.nanoTime() else 0L

        try {
            target.renderer.draw(item, ctx, selected)
        } catch (t: Throwable) {
            com.flowcut.app.engine.perf.PreviewEventLog.logSwallowedException("OverlayStagePass.draw(${target.renderer.id})", t)
            threw = true
            caughtThrowable = t
            // Failure isolation: restore baseline state and reset overlay state for remaining layers
            try {
                GlPassScope.restoreBaseline(ctx.outputWidth, ctx.outputHeight)
                setupOverlayGlState()
            } catch (_: Throwable) {}
        }

        if (BuildConfig.PERF_TELEMETRY) {
            val durationNs = System.nanoTime() - startNs
            target.recordTiming(durationNs)
            if (durationNs > WARN_DURATION_NS) {
                logWarn(TAG, "LayerRenderer '${target.renderer.id}' took ${durationNs / 1_000_000.0} ms (> 4 ms threshold)")
            }
        }

        if (threw) {
            target.consecutiveFailures++
            if (target.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !target.disabled) {
                target.disabled = true
                if (!target.disabledLogged) {
                    target.disabledLogged = true
                    logError(
                        TAG,
                        "LayerRenderer '${target.renderer.id}' disabled for session after $MAX_CONSECUTIVE_FAILURES consecutive failures",
                        caughtThrowable
                    )
                }
            }
        } else {
            target.consecutiveFailures = 0
        }
    }

    fun isRendererDisabled(rendererId: String): Boolean {
        if (legacyEntry?.renderer?.id == rendererId) return legacyEntry.disabled
        return synchronized(lock) {
            customRenderers.firstOrNull { it.renderer.id == rendererId }?.disabled ?: false
        }
    }

    internal fun resetWindowTimings() {
        legacyEntry?.resetWindow()
        for (i in customRenderers.indices) {
            customRenderers[i].resetWindow()
        }
    }

    internal fun appendDebugTimings(sb: StringBuilder) {
        val legacy = legacyEntry
        if (legacy != null && legacy.frameCount > 0L) {
            val avgMs = (legacy.totalDurationNs.toDouble() / legacy.frameCount) / 1_000_000.0
            val maxMs = legacy.maxDurationNs / 1_000_000.0
            sb.append(", ").append(legacy.renderer.id).append(": avg=").append(String.format(java.util.Locale.US, "%.2f", avgMs)).append("ms max=").append(String.format(java.util.Locale.US, "%.2f", maxMs)).append("ms")
        }
        for (i in customRenderers.indices) {
            val entry = customRenderers[i]
            if (entry.frameCount > 0L) {
                val avgMs = (entry.totalDurationNs.toDouble() / entry.frameCount) / 1_000_000.0
                val maxMs = entry.maxDurationNs / 1_000_000.0
                sb.append(", ").append(entry.renderer.id).append(": avg=").append(String.format(java.util.Locale.US, "%.2f", avgMs)).append("ms max=").append(String.format(java.util.Locale.US, "%.2f", maxMs)).append("ms")
            }
        }
    }

    fun getRendererTimings(): List<LayerRendererTiming> {
        val list = mutableListOf<LayerRendererTiming>()
        legacyEntry?.let { legacy ->
            list.add(
                LayerRendererTiming(
                    id = legacy.renderer.id,
                    kinds = legacy.renderer.kinds,
                    lastDurationNs = legacy.lastDurationNs,
                    avgDurationNs = legacy.avgDurationNs,
                    lastDurationMs = legacy.lastDurationNs / 1_000_000.0,
                    avgDurationMs = legacy.avgDurationNs / 1_000_000.0,
                    frameCount = legacy.frameCount,
                    consecutiveFailures = legacy.consecutiveFailures,
                    isDisabled = legacy.disabled
                )
            )
        }
        synchronized(lock) {
            for (entry in customRenderers) {
                list.add(
                    LayerRendererTiming(
                        id = entry.renderer.id,
                        kinds = entry.renderer.kinds,
                        lastDurationNs = entry.lastDurationNs,
                        avgDurationNs = entry.avgDurationNs,
                        lastDurationMs = entry.lastDurationNs / 1_000_000.0,
                        avgDurationMs = entry.avgDurationNs / 1_000_000.0,
                        frameCount = entry.frameCount,
                        consecutiveFailures = entry.consecutiveFailures,
                        isDisabled = entry.disabled
                    )
                )
            }
        }
        return list
    }

    private class RendererEntry(val renderer: LayerRenderer) {
        var consecutiveFailures: Int = 0
        var disabled: Boolean = false
        var disabledLogged: Boolean = false
        var lastDurationNs: Long = 0L
        var totalDurationNs: Long = 0L
        var maxDurationNs: Long = 0L
        var frameCount: Long = 0L

        val avgDurationNs: Double
            get() = if (frameCount > 0L) totalDurationNs.toDouble() / frameCount else 0.0

        fun recordTiming(durationNs: Long) {
            lastDurationNs = durationNs
            totalDurationNs += durationNs
            if (durationNs > maxDurationNs) {
                maxDurationNs = durationNs
            }
            frameCount++
        }

        fun resetWindow() {
            totalDurationNs = 0L
            maxDurationNs = 0L
            frameCount = 0L
        }
    }
}
