/**
 * Feature:     mosaic
 * File role:   index
 * Responsible: Public API of the mosaic feature module
 * Depends on:  core/feature, features/mosaic/MosaicLayerRenderer
 * Used by:     app, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.mosaic

import com.flowcut.app.core.feature.Feature
import com.flowcut.app.core.feature.FeatureRegistry

/**
 * Public entry point for the Mosaic feature.
 * External modules must ONLY import from this file.
 */
object MosaicFeature : Feature {
    const val ID = "mosaic"
    override val id: String = ID

    override fun register(r: FeatureRegistry) {
        r.itemType(MosaicLayerRenderer.KIND_MOSAIC)
        r.layerRenderer(MosaicLayerRenderer())
    }
}
