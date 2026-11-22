package com.flowcut.app.core.feature

import com.flowcut.app.core.timeline.TimelineSnapshot

/**
 * Concrete implementation of FeatureRegistry in pure Kotlin (Sections 4 & 10).
 * Stores registered item types, painters, contributors, prefetchers, and clip tools.
 * Provides unknown item handling hook to protect against project corruption.
 * Zero android.* or external UI imports.
 */
class DefaultFeatureRegistry : FeatureRegistry {

    private val itemTypes = mutableSetOf<String>()
    private val lanePainters = mutableMapOf<String, LanePainter>()
    private val renderContributors = mutableListOf<RenderContributor>()
    private val audioContributors = mutableListOf<AudioContributor>()
    private val prefetchers = mutableListOf<Prefetcher>()
    private val clipTools = mutableListOf<ClipTool>()
    private val renderPasses = mutableListOf<RenderPass>()
    private val layerRenderers = mutableListOf<LayerRenderer>()

    /**
     * Listener invoked when a render pass is registered.
     */
    var onRenderPassAdded: ((RenderPass) -> Unit)? = null

    /**
     * Listener invoked when a layer renderer is registered.
     */
    var onLayerRendererAdded: ((LayerRenderer) -> Unit)? = null

    /**
     * Hook invoked when an item type is encountered that is not registered.
     * Protects project load from corruption or crashes (Section 4).
     */
    var unknownItemWarningHook: ((kind: String, itemId: String) -> Unit)? = null

    override fun itemType(kind: String) {
        itemTypes.add(kind)
    }

    override fun lanePainter(kind: String, p: LanePainter) {
        lanePainters[kind] = p
    }

    override fun renderContributor(c: RenderContributor) {
        if (!renderContributors.contains(c)) {
            renderContributors.add(c)
            renderContributors.sortBy { it.order }
        }
    }

    override fun audioContributor(c: AudioContributor) {
        if (!audioContributors.contains(c)) {
            audioContributors.add(c)
        }
    }

    override fun prefetcher(p: Prefetcher) {
        if (!prefetchers.contains(p)) {
            prefetchers.add(p)
        }
    }

    override fun clipTool(t: ClipTool) {
        if (!clipTools.contains(t)) {
            clipTools.add(t)
        }
    }

    override fun renderPass(p: RenderPass) {
        if (!renderPasses.contains(p)) {
            renderPasses.add(p)
            renderPasses.sortBy { it.order }
            onRenderPassAdded?.invoke(p)
        }
    }

    override fun layerRenderer(r: LayerRenderer) {
        if (!layerRenderers.contains(r)) {
            layerRenderers.add(r)
            onLayerRendererAdded?.invoke(r)
        }
    }

    fun isItemTypeRegistered(kind: String): Boolean = itemTypes.contains(kind)

    fun handleUnknownItem(kind: String, itemId: String) {
        unknownItemWarningHook?.invoke(kind, itemId)
    }

    fun getLanePainter(kind: String): LanePainter? = lanePainters[kind]

    fun getRenderContributors(): List<RenderContributor> = renderContributors

    fun getAudioContributors(): List<AudioContributor> = audioContributors

    fun getPrefetchers(): List<Prefetcher> = prefetchers

    fun getClipTools(): List<ClipTool> = clipTools

    fun getRenderPasses(): List<RenderPass> = renderPasses

    fun getLayerRenderers(): List<LayerRenderer> = layerRenderers

    fun clear() {
        itemTypes.clear()
        lanePainters.clear()
        renderContributors.clear()
        audioContributors.clear()
        prefetchers.clear()
        clipTools.clear()
        renderPasses.clear()
        layerRenderers.clear()
    }

    /**
     * Precomputes mapping of kind -> lane indices for fast O(1) active contributor dispatch (Section 10).
     */
    fun computeKindToLaneIndices(snapshot: TimelineSnapshot): Map<String, IntArray> {
        val kindMap = mutableMapOf<String, MutableList<Int>>()
        for (i in snapshot.project.lanes.indices) {
            val kind = snapshot.project.lanes[i].kind
            kindMap.getOrPut(kind) { mutableListOf() }.add(i)
        }
        return kindMap.mapValues { it.value.toIntArray() }
    }

    /**
     * Precomputes lane indices for all registered render contributors against the given snapshot.
     */
    fun precomputeContributorLaneMap(snapshot: TimelineSnapshot): Map<RenderContributor, IntArray> {
        val kindMap = computeKindToLaneIndices(snapshot)
        return renderContributors.associateWith { c ->
            if (c.kinds.isEmpty()) {
                IntArray(0)
            } else {
                val indices = mutableListOf<Int>()
                for (k in c.kinds) {
                    kindMap[k]?.let { arr ->
                        for (idx in arr) {
                            indices.add(idx)
                        }
                    }
                }
                indices.toIntArray()
            }
        }
    }
}

