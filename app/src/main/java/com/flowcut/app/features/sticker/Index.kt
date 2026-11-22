/**
 * Feature:     sticker
 * File role:   index
 * Responsible: Public API of the sticker feature module
 * Depends on:  core/feature, features/sticker/StickerLayerRenderer
 * Used by:     app, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.sticker

import com.flowcut.app.core.feature.Feature
import com.flowcut.app.core.feature.FeatureRegistry

/**
 * Public entry point for the Sticker feature.
 * External modules must ONLY import from this file.
 */
object StickerFeature : Feature {
    const val ID = "sticker"
    override val id: String = ID

    override fun register(r: FeatureRegistry) {
        r.itemType(StickerLayerRenderer.KIND_STICKER)
        r.layerRenderer(StickerLayerRenderer())
    }
}
