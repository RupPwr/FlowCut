package com.flowcut.app.core.feature

/**
 * Pure Kotlin contract representing a timeline layer item passed to LayerRenderers.
 */
interface LayerItem {
    val id: String
    val kind: String
}

/**
 * Pure Kotlin contract for isolated preview renderers for timeline layer kinds
 * (e.g., text, sticker, image, pip video, mosaic).
 */
interface LayerRenderer {
    val id: String
    val kinds: Set<String>
    fun onGlCreated()
    fun onGlDestroyed()
    fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean)
}
