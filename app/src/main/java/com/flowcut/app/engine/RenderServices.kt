package com.flowcut.app.engine

/**
 * Holder for a baked OpenGL texture.
 */
interface LayerTexture {
    val texId: Int
    val width: Int
    val height: Int
}

/**
 * Narrow texture provider interface allowing layer renderers to obtain baked textures
 * and release their kind's cache entries without accessing internal cache structures.
 */
interface TextureProvider {
    fun getTexture(item: OverlayItem): LayerTexture?
    fun releaseTexture(itemId: String)
    fun releaseKind(kind: String)
}

/**
 * Narrow video texture provider interface allowing layer renderers to obtain
 * dynamic frame-accurate video textures without accessing internal cache structures.
 */
interface PipTextureProvider {
    fun getVideoTexture(item: OverlayItem, timelineUs: Long): LayerTexture?
    fun releaseTexture(itemId: String)
}

/**
 * Narrow quad drawing interface allowing layer renderers to draw overlay quads
 * without accessing TransitionRenderer or PreviewRenderer directly.
 */
interface QuadDrawer {
    fun drawOverlayQuad(
        texId: Int,
        texWidth: Int,
        texHeight: Int,
        transform: ClipTransform,
        opacity: Float = 1.0f,
        displayWidth: Int = 1080,
        displayHeight: Int = 1920,
        cropLeft: Float = 0.0f,
        cropTop: Float = 0.0f,
        cropRight: Float = 1.0f,
        cropBottom: Float = 1.0f,
        maskType: Int = 0,
        maskFeather: Float = 0.05f,
        maskInvert: Boolean = false,
        chromaEnabled: Boolean = false,
        chromaColorArgb: Int = 0xFF00FF00.toInt(),
        chromaThreshold: Float = 0.25f,
        chromaBlend: Float = 0.10f,
        adjustBrightness: Float = 0.0f,
        adjustContrast: Float = 1.0f,
        adjustSaturation: Float = 1.0f
    )
}

/**
 * Narrow mosaic drawing interface allowing layer renderers to draw mosaic regions
 * without accessing TransitionRenderer or PreviewRenderer directly.
 */
interface MosaicDrawer {
    fun drawMosaicRegion(
        videoTexId: Int,
        videoMat: FloatArray,
        crop: FloatArray,
        transform: ClipTransform,
        mosaicStyle: Int = 0,
        mosaicIntensity: Float = 0.5f,
        mosaicFeather: Float = 0.05f,
        opacity: Float = 1.0f,
        displayWidth: Int = 1080,
        displayHeight: Int = 1920
    )
}

/**
 * Narrow services interface provided to layer renderers on the GL thread.
 * Decouples plugins from PreviewRenderer and TransitionRenderer internals.
 */
interface RenderServices {
    val quadDrawer: QuadDrawer
    val textureProvider: TextureProvider
    val mosaicDrawer: MosaicDrawer
    val pipTextureProvider: PipTextureProvider
}

/**
 * Optional interface for LayerRenderers requiring host RenderServices injection.
 */
interface RenderServicesAware {
    fun attachRenderServices(services: RenderServices)
}

/**
 * No-op implementation of MosaicDrawer for testing or fallbacks.
 */
class NoOpMosaicDrawer : MosaicDrawer {
    override fun drawMosaicRegion(
        videoTexId: Int,
        videoMat: FloatArray,
        crop: FloatArray,
        transform: ClipTransform,
        mosaicStyle: Int,
        mosaicIntensity: Float,
        mosaicFeather: Float,
        opacity: Float,
        displayWidth: Int,
        displayHeight: Int
    ) {
        // No-op
    }
}

/**
 * No-op implementation of PipTextureProvider for testing or fallbacks.
 */
class NoOpPipTextureProvider : PipTextureProvider {
    override fun getVideoTexture(item: OverlayItem, timelineUs: Long): LayerTexture? = null
    override fun releaseTexture(itemId: String) {}
}

/**
 * Default implementation of RenderServices.
 */
data class DefaultRenderServices(
    override val quadDrawer: QuadDrawer,
    override val textureProvider: TextureProvider,
    override val mosaicDrawer: MosaicDrawer = NoOpMosaicDrawer(),
    override val pipTextureProvider: PipTextureProvider = NoOpPipTextureProvider()
) : RenderServices {
    constructor(
        quadDrawer: QuadDrawer,
        textureProvider: TextureProvider
    ) : this(quadDrawer, textureProvider, NoOpMosaicDrawer(), NoOpPipTextureProvider())

    constructor(
        quadDrawer: QuadDrawer,
        textureProvider: TextureProvider,
        mosaicDrawer: MosaicDrawer
    ) : this(quadDrawer, textureProvider, mosaicDrawer, NoOpPipTextureProvider())
}
