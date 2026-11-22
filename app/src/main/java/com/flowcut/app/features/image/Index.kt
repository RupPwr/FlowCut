/**
 * Feature:     image
 * File role:   index
 * Responsible: Public API of the image feature module
 * Depends on:  core/feature, features/image/ImageLayerRenderer
 * Used by:     app, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.image

import com.flowcut.app.core.feature.Feature
import com.flowcut.app.core.feature.FeatureRegistry

/**
 * Public entry point for the Image feature.
 * External modules must ONLY import from this file.
 */
object ImageFeature : Feature {
    const val ID = "image"
    override val id: String = ID

    override fun register(r: FeatureRegistry) {
        r.itemType(ImageLayerRenderer.KIND_IMAGE)
        r.layerRenderer(ImageLayerRenderer())
    }
}
