/**
 * Feature:     text
 * File role:   renderer
 * Responsible: Isolated preview renderer for TEXT overlay items on the GL thread
 * Depends on:  core/feature, engine/LayerPlan, engine/RenderServices
 * Used by:     TextFeature, OverlayStagePass
 * Never touches: PreviewRenderer directly, TransitionRenderer directly, other features
 */
package com.flowcut.app.features.text

import com.flowcut.app.core.feature.LayerItem
import com.flowcut.app.core.feature.LayerRenderer
import com.flowcut.app.core.feature.PassCtx
import com.flowcut.app.engine.AnimatedTransformEvaluator
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.RenderServices
import com.flowcut.app.engine.RenderServicesAware

/**
 * Isolated preview renderer for the TEXT layer kind.
 * Contains all TEXT-specific preview rendering logic previously in PreviewRenderer / LegacyLayerRenderer.
 */
class TextLayerRenderer(
    var services: RenderServices? = null
) : LayerRenderer, RenderServicesAware {

    override val id: String = ID
    override val kinds: Set<String> = KINDS

    private val animEvaluator = AnimatedTransformEvaluator()

    override fun attachRenderServices(services: RenderServices) {
        this.services = services
    }

    override fun onGlCreated() {
        // Texture rasterization and GL upload happen on-demand via textureProvider
    }

    override fun onGlDestroyed() {
        // Release only textures belonging to TEXT; other layer caches remain untouched
        services?.textureProvider?.releaseKind(KIND_TEXT)
    }

    override fun draw(layer: LayerItem, ctx: PassCtx, selected: Boolean) {
        val item = layer as? OverlayItem ?: return
        if (item.type != LayerType.TEXT) return

        val effTransform = animEvaluator.evaluate(item, ctx.tUs)
        if (effTransform.opacity <= 0.001f) return

        val s = services ?: return
        val cached = s.textureProvider.getTexture(item) ?: return

        if (cached.texId > 0) {
            s.quadDrawer.drawOverlayQuad(
                texId = cached.texId,
                texWidth = cached.width,
                texHeight = cached.height,
                transform = effTransform,
                opacity = effTransform.opacity,
                displayWidth = ctx.outputWidth,
                displayHeight = ctx.outputHeight,
                cropLeft = item.cropLeft,
                cropTop = item.cropTop,
                cropRight = item.cropRight,
                cropBottom = item.cropBottom,
                maskType = item.maskType,
                maskFeather = item.maskFeather,
                maskInvert = item.maskInvert,
                chromaEnabled = item.chromaEnabled,
                chromaColorArgb = item.chromaColorArgb,
                chromaThreshold = item.chromaThreshold,
                chromaBlend = item.chromaBlend,
                adjustBrightness = item.adjustBrightness,
                adjustContrast = item.adjustContrast,
                adjustSaturation = item.adjustSaturation
            )
        }
    }

    companion object {
        const val ID = "text_layer_renderer"
        const val KIND_TEXT = "TEXT"
        val KINDS: Set<String> = setOf(KIND_TEXT)
    }
}
