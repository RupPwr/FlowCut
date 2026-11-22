package com.flowcut.app.core.feature

import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.TimelineSnapshot

/**
 * Feature plug-in contract (Section 10).
 * Every feature packages itself into a Feature object and registers with the FeatureRegistry.
 * Core has zero knowledge of feature names or internals.
 */
interface Feature {
    val id: String
    fun register(r: FeatureRegistry)
}

/**
 * Registry where features contribute lane painters, video/audio contributors,
 * prefetchers, and clip tools.
 */
interface FeatureRegistry {
    fun itemType(kind: String)
    fun lanePainter(kind: String, p: LanePainter)
    fun renderContributor(c: RenderContributor)
    fun audioContributor(c: AudioContributor)
    fun prefetcher(p: Prefetcher)
    fun clipTool(t: ClipTool)
    fun renderPass(p: RenderPass)
    fun layerRenderer(r: LayerRenderer)
}

/**
 * Context provided to LanePainter during draw phase.
 * Pure Kotlin interface; drawing primitives are abstracted so core has zero UI framework dependencies.
 */
interface PainterCtx {
    val snapshot: TimelineSnapshot
    val halfWidthPx: Float
    val offsetPx: Long
    val pxPerUs: Double
    val leftMs: Long
    val rightMs: Long
    val density: Float

    fun timeMsToX(timeMs: Long): Float
    fun timeUsToX(timeUs: Long): Float

    // Drawing primitives
    fun drawRect(x: Float, y: Float, width: Float, height: Float, colorArgb: Int)
    fun drawText(text: String, x: Float, y: Float, colorArgb: Int, fontSizeSp: Float = 12f)
}

/**
 * Contributed by a feature to paint items on its assigned timeline lanes (UI thread).
 */
interface LanePainter {
    fun draw(ctx: PainterCtx, laneIndex: Int, from: Int, to: Int)
}

/**
 * Context provided to RenderContributor on each frame on the GL thread.
 */
interface FrameCtx {
    val tUs: Long
    val snapshot: TimelineSnapshot
    val active: ActiveSet
    val outputWidth: Int
    val outputHeight: Int
}

/**
 * Contributed by a feature to apply video effects or overlays on the GL thread.
 */
interface RenderContributor {
    val order: Int
    val kinds: Set<String>
    fun onFrame(f: FrameCtx)
    fun release()
}

/**
 * Contributed by a feature to mix audio on the audio thread.
 */
interface AudioContributor {
    val kinds: Set<String>
    fun render(startFrame: Long, frames: Int, out: FloatArray)
}

/**
 * Contributed by a feature to prefetch data ahead of the playhead on a background thread (~10 Hz).
 */
interface Prefetcher {
    fun prefetch(s: TimelineSnapshot, fromUs: Long, windowUs: Long)
}

/**
 * Tool contributed to the clip toolbar sheet.
 */
interface ClipTool {
    val id: String
    val label: String get() = id
    fun appliesTo(clip: Clip): Boolean
}

