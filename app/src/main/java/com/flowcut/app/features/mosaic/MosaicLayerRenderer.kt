/**
 * Feature:     mosaic
 * File role:   renderer
 * Responsible: Isolated preview renderer for MOSAIC overlay items on the GL thread
 * Depends on:  core/feature, engine/LayerPlan, engine/RenderServices
 * Used by:     MosaicFeature, OverlayStagePass
 * Never touches: PreviewRenderer directly, TransitionRenderer directly, other features
 */
package com.flowcut.app.features.mosaic

import com.flowcut.app.core.feature.LayerItem
import com.flowcut.app.core.feature.LayerRenderer
import com.flowcut.app.core.feature.PassCtx
import com.flowcut.app.engine.AnimatedTransformEvaluator
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.RenderServices
import com.flowcut.app.engine.RenderServicesAware

/**
 * Isolated preview renderer for the MOSAIC layer kind.
 * Contains all MOSAIC-specific preview rendering logic previously in PreviewRenderer / LegacyLayerRenderer.
 */
class MosaicLayerRenderer(
    var services: RenderServices? = null
) : LayerRenderer, RenderServicesAware {

    override val id: String = ID
    override val kinds: Set<String> = KINDS

    private val animEvaluator = AnimatedTransformEvaluator()

    override fun attachRenderServices(services: RenderServices) {
        this.services = services
    }

    override fun onGlCreated() {
        // Shaders and GL programs are managed on-demand via host services
    }

    override fun onGlDestroyed() {
        // Release any plugin-owned state or resources
    }

    override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
        val item = layer as? OverlayItem ?: return
        if (item.type != LayerType.MOSAIC) return

        val effTransform = animEvaluator.evaluate(item, ctx.tUs)
        if (effTransform.opacity <= 0.001f) return

        val vTex = ctx.baseFrame.textureId
        if (vTex <= 0) return

        val s = services ?: return
        s.mosaicDrawer.drawMosaicRegion(
            videoTexId = vTex,
            videoMat = ctx.baseFrame.matrix,
            crop = ctx.baseFrame.crop,
            transform = effTransform,
            mosaicStyle = item.mosaicStyle,
            mosaicIntensity = item.mosaicIntensity,
            mosaicFeather = item.mosaicFeather,
            opacity = effTransform.opacity,
            displayWidth = ctx.outputWidth,
            displayHeight = ctx.outputHeight
        )
    }

    companion object {
        const val ID = "mosaic_layer_renderer"
        const val KIND_MOSAIC = "MOSAIC"
        val KINDS: Set<String> = setOf(KIND_MOSAIC)
    }
}
