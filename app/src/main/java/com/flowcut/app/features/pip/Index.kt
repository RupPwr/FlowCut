/**
 * Feature:     pip
 * File role:   index
 * Responsible: Public API of the pip feature module
 * Depends on:  core/feature, features/pip/PipLayerRenderer
 * Used by:     app, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.pip

import com.flowcut.app.core.feature.Feature
import com.flowcut.app.core.feature.FeatureRegistry

/**
 * Public entry point for the PiP feature.
 * External modules must ONLY import from this file.
 */
object PipFeature : Feature {
    const val ID = "pip"
    override val id: String = ID

    override fun register(r: FeatureRegistry) {
        r.itemType(PipLayerRenderer.KIND_VIDEO)
        r.layerRenderer(PipLayerRenderer())
    }
}
