package com.flowcut.app.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Single thread-safe source of truth for real-time render and audio state shared across
 * EditorViewModel, PreviewRenderer, ExportEngine, and ClipAudioContributor.
 *
 * Centralizing these maps eliminates split-brain desynchronization between live preview,
 * audio mixing, undo/redo, draft load/reset, and offline export.
 */
object RenderStateStore {

    @Volatile
    private var _canvasAspectRatio: Float = 9f / 16f
    val canvasAspectRatio: Float get() = _canvasAspectRatio

    @Volatile
    var currentAspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_9_16

    @Volatile
    var currentLayerPlan: CompiledLayerPlan = CompiledLayerPlan.EMPTY

    @Volatile
    var isMainTrackMuted: Boolean = false

    @Volatile
    var activeAudioOverlays: List<OverlayItem> = emptyList()

    val clipTransforms = ConcurrentHashMap<String, ClipTransform>()
    val clipColorAdjusts = ConcurrentHashMap<String, ClipColorAdjust>()
    val clipBackgrounds = ConcurrentHashMap<String, ClipBackground>()
    val liveFilterOverrides = ConcurrentHashMap<String, ClipFilterState>()
    val junctionTransitionTypes = ConcurrentHashMap<Int, Int>()
    val clipVolumes = ConcurrentHashMap<String, Float>()
    val overlayTextures = ConcurrentHashMap<String, Int>()

    fun resolveEffectiveAspect(mode: AspectRatioMode, firstClip: Any? = null): Float {
        if (mode == AspectRatioMode.ORIGINAL) {
            if (firstClip != null) {
                val (w, h) = when (firstClip) {
                    is com.flowcut.app.model.Clip -> {
                        val rot = firstClip.rotation
                        val ew = if (rot == 90 || rot == 270) firstClip.height else firstClip.width
                        val eh = if (rot == 90 || rot == 270) firstClip.width else firstClip.height
                        ew to eh
                    }
                    is com.flowcut.app.core.model.Clip -> {
                        val rot = firstClip.rotation
                        val ew = if (rot == 90 || rot == 270) firstClip.height else firstClip.width
                        val eh = if (rot == 90 || rot == 270) firstClip.width else firstClip.height
                        ew to eh
                    }
                    else -> 0 to 0
                }
                if (w > 0 && h > 0) {
                    return w.toFloat() / h.toFloat()
                }
            }
            return 9f / 16f
        }
        return if (mode.ratioWidth > 0 && mode.ratioHeight > 0) {
            mode.ratioWidth.toFloat() / mode.ratioHeight.toFloat()
        } else if (mode.aspect > 0f) {
            mode.aspect
        } else {
            9f / 16f
        }
    }

    fun setAspectRatioMode(mode: AspectRatioMode, firstClip: Any? = null) {
        currentAspectRatioMode = mode
        val effective = resolveEffectiveAspect(mode, firstClip)
        setCanvasAspectRatio(effective)
    }

    fun setCanvasAspectRatio(aspect: Float) {
        if (aspect > 0f) {
            _canvasAspectRatio = aspect
            TransitionRenderer.canvasAspect = aspect
        }
    }

    fun setLayerPlan(plan: CompiledLayerPlan) {
        currentLayerPlan = plan
    }

    fun setOverlayTextureId(itemId: String, texId: Int) {
        if (texId > 0) {
            overlayTextures[itemId] = texId
        } else {
            overlayTextures.remove(itemId)
        }
    }

    fun getOverlayTextureId(itemId: String): Int = overlayTextures[itemId] ?: 0

    fun clearOverlayTextures() {
        overlayTextures.clear()
    }

    fun getClipTransform(clipId: String?): ClipTransform {
        if (clipId == null) return ClipTransform.DEFAULT
        return clipTransforms[clipId] ?: ClipTransform.DEFAULT
    }

    fun setClipTransform(clipId: String, transform: ClipTransform) {
        clipTransforms[clipId] = transform
    }

    fun syncTransforms(transforms: Map<String, ClipTransform>) {
        clipTransforms.clear()
        clipTransforms.putAll(transforms)
    }

    fun getClipColorAdjust(clipId: String?): ClipColorAdjust {
        if (clipId == null) return ClipColorAdjust.DEFAULT
        return clipColorAdjusts[clipId] ?: ClipColorAdjust.DEFAULT
    }

    fun setClipColorAdjust(clipId: String, adjust: ClipColorAdjust) {
        clipColorAdjusts[clipId] = adjust
    }

    fun syncColorAdjusts(adjusts: Map<String, ClipColorAdjust>) {
        clipColorAdjusts.clear()
        clipColorAdjusts.putAll(adjusts)
    }

    fun getClipBackground(clipId: String?): ClipBackground {
        if (clipId == null) return ClipBackground.DEFAULT
        return clipBackgrounds[clipId] ?: ClipBackground.DEFAULT
    }

    fun setClipBackground(clipId: String, bg: ClipBackground) {
        clipBackgrounds[clipId] = bg
    }

    fun syncBackgrounds(bgs: Map<String, ClipBackground>) {
        clipBackgrounds.clear()
        clipBackgrounds.putAll(bgs)
    }

    fun setLiveFilterOverride(clipId: String, filter: ClipFilterState) {
        liveFilterOverrides[clipId] = filter
    }

    fun clearLiveFilterOverride(clipId: String? = null) {
        if (clipId != null) {
            liveFilterOverrides.remove(clipId)
        } else {
            liveFilterOverrides.clear()
        }
    }

    fun getJunctionTransitionType(junctionIndex: Int): Int =
        junctionTransitionTypes[junctionIndex] ?: -1

    fun setJunctionTransitionType(junctionIndex: Int, type: Int) {
        if (type <= 0) {
            junctionTransitionTypes.remove(junctionIndex)
        } else {
            junctionTransitionTypes[junctionIndex] = type
        }
    }

    fun syncJunctionTransitions(types: Map<Int, Int>) {
        junctionTransitionTypes.clear()
        junctionTransitionTypes.putAll(types)
    }

    fun getClipVolume(clipId: String): Float = clipVolumes[clipId] ?: 1.0f

    fun setClipVolume(clipId: String, volume: Float) {
        val clamped = volume.coerceIn(0.0f, 2.0f)
        clipVolumes[clipId] = clamped
        if (clamped > 0.0001f) {
            isMainTrackMuted = false
        }
    }

    fun syncVolumes(volumes: Map<String, Float>) {
        clipVolumes.clear()
        clipVolumes.putAll(volumes)
    }

    /**
     * Atomically removes all per-clip render and audio metadata for a deleted or replaced clip.
     */
    fun removeClipMetadata(clipId: String) {
        clipTransforms.remove(clipId)
        clipColorAdjusts.remove(clipId)
        clipBackgrounds.remove(clipId)
        liveFilterOverrides.remove(clipId)
        clipVolumes.remove(clipId)
    }

    /**
     * Atomically copies per-clip render and audio metadata from [sourceClipId] to [targetClipId].
     */
    fun copyClipMetadata(sourceClipId: String, targetClipId: String, overrideVolume: Float? = null) {
        clipTransforms[targetClipId] = getClipTransform(sourceClipId)
        clipColorAdjusts[targetClipId] = getClipColorAdjust(sourceClipId)
        clipBackgrounds[targetClipId] = getClipBackground(sourceClipId)
        clipVolumes[targetClipId] = (overrideVolume ?: getClipVolume(sourceClipId)).coerceIn(0.0f, 2.0f)
    }

    /**
     * Atomically synchronizes all visual and audio render state in one call (used by load,
     * openExistingProject, and Undo/Redo restore).
     */
    fun syncAll(
        transforms: Map<String, ClipTransform>,
        colorAdjusts: Map<String, ClipColorAdjust>,
        backgrounds: Map<String, ClipBackground>,
        junctionTransitions: Map<Int, Int>,
        volumes: Map<String, Float>,
        mainTrackMuted: Boolean,
        layerPlan: CompiledLayerPlan,
        aspectRatioMode: AspectRatioMode,
        firstClip: Any? = null
    ) {
        syncTransforms(transforms)
        syncColorAdjusts(colorAdjusts)
        syncBackgrounds(backgrounds)
        syncJunctionTransitions(junctionTransitions)
        syncVolumes(volumes)
        isMainTrackMuted = mainTrackMuted
        setLayerPlan(layerPlan)
        setAspectRatioMode(aspectRatioMode, firstClip)
    }

    fun resetAll() {
        clipTransforms.clear()
        clipColorAdjusts.clear()
        clipBackgrounds.clear()
        clearLiveFilterOverride()
        junctionTransitionTypes.clear()
        clipVolumes.clear()
        isMainTrackMuted = false
        activeAudioOverlays = emptyList()
        clearOverlayTextures()
        setLayerPlan(CompiledLayerPlan.EMPTY)
        setAspectRatioMode(AspectRatioMode.RATIO_9_16)
    }
}
