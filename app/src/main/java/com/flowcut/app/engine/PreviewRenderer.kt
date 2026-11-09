package com.flowcut.app.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.flowcut.app.core.feature.FrameCtx
import com.flowcut.app.core.feature.Prefetcher
import com.flowcut.app.core.feature.RenderContributor
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.model.map
import com.flowcut.app.core.playback.PlayMode
import com.flowcut.app.core.playback.PlayheadReader
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.model.PREVIEW_H
import com.flowcut.app.model.PREVIEW_W
import com.flowcut.app.model.ProgramTimeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global migration switch (Section 15).
 * Switched to true default on completion of Step F2.7.
 */
const val USE_NEW_CORE: Boolean = true

/**
 * Minimal PlayheadReader implementation for testing or standalone playback.
 */
class DefaultPlayheadReader(
    @Volatile override var timeUs: Long = 0L,
    @Volatile override var mode: PlayMode = PlayMode.PAUSED,
    @Volatile override var generation: Int = 0
) : PlayheadReader

/**
 * Zero-allocation container holding resolved frame metadata for the current render tick.
 */
class ResolvedFrame {
    var clipAIndex: Int = -1
    var clipAId: String? = null
    var clipAPath: String? = null
    var clipASourceUs: Long = 0L

    var isTransition: Boolean = false
    var clipBIndex: Int = -1
    var clipBId: String? = null
    var clipBPath: String? = null
    var clipBSourceUs: Long = 0L

    var transitionType: TransitionType = TransitionType.NONE
    var linearP: Float = 0f
    var generation: Int = 0
    var tUs: Long = 0L

    fun reset() {
        clipAIndex = -1
        clipAId = null
        clipAPath = null
        clipASourceUs = 0L
        isTransition = false
        clipBIndex = -1
        clipBId = null
        clipBPath = null
        clipBSourceUs = 0L
        transitionType = TransitionType.NONE
        linearP = 0f
        generation = 0
        tUs = 0L
    }
}

/**
 * Reusable FrameCtx implementation passed to RenderContributors without heap allocation.
 */
class MutableFrameCtx : FrameCtx {
    override var tUs: Long = 0L
    override var snapshot: TimelineSnapshot = TimelineSnapshot.EMPTY
    override var active: ActiveSet = ActiveSet(0)
    override var outputWidth: Int = PREVIEW_W
    override var outputHeight: Int = PREVIEW_H
}

/**
 * Background prefetch coordinator (Sections 10 & 11).
 * Runs at ~10 Hz on Dispatchers.Default, invoking registered Prefetchers
 * and calculating video decoder prefetch using TimelineSnapshot and TimeMap (Rule R11).
 */
class PrefetchCoordinator(
    private val holder: SnapshotHolder,
    private val playhead: PlayheadReader,
    private val prefetchersProvider: () -> List<Prefetcher>,
    private val onVideoDecoderPrefetch: (clipIndex: Int, sourceUs: Long) -> Unit = { _, _ -> },
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var job: Job? = null

    var isRunning: Boolean = false
        private set

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        job = scope.launch(defaultDispatcher) {
            while (isActive) {
                delay(100L) // ~10 Hz
                triggerPrefetch()
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
    }

    fun triggerPrefetch(windowUs: Long = 2_000_000L) {
        val s = holder.current
        val tUs = playhead.timeUs

        // 1. Registered Prefetchers
        val list = prefetchersProvider()
        for (i in list.indices) {
            try {
                list[i].prefetch(s, tUs, windowUs)
            } catch (_: Throwable) {
                // Feature failure isolation
            }
        }

        // 2. Video decoder prefetch using TimelineSnapshot + TimeMap (Rule R11)
        if (s.main.n > 0) {
            val tMs = usToMs(tUs)
            val currentClipIndex = s.main.lastStartAtOrBefore(tMs)
            val nextClipIndex = currentClipIndex + 1
            if (nextClipIndex < s.main.n && currentClipIndex in s.project.clips.indices) {
                val currentClip = s.project.clips[currentClipIndex]
                val nextClip = s.project.clips[nextClipIndex]
                if (nextClip.path != currentClip.path) {
                    val nextStartUs = msToUs(s.main.startMs[nextClipIndex])
                    if (tUs + windowUs >= nextStartUs) {
                        val sourceUs = nextClip.map().sourceUs(0L)
                        onVideoDecoderPrefetch(nextClipIndex, sourceUs)
                    }
                }
            }
        }
    }
}

/**
 * PreviewRenderer on the new core architecture (Sections 5, 7, 10, 11 & 15).
 * - Reads `val s = holder.current` and `val tUs = playhead.timeUs` ONCE per frame on the render path.
 * - Reuses a single pre-allocated `ActiveSet` (resized only if `s.lanes.size` grows) and calls `s.resolve(tUs, activeSet)`.
 * - Strictly enforces Rule R11 (TimeMap) for clip source timestamps.
 * - Dispatches to registered RenderContributors filtered by active items in activeSet.laneItem, with failure isolation.
 * - Manages GL rendering pipeline (FrameSource, SurfaceTexture, TransitionRenderer) on Android and clean headless execution in tests.
 */
class PreviewRenderer(
    val holder: SnapshotHolder = SnapshotHolder(),
    val playhead: PlayheadReader = DefaultPlayheadReader(),
    val context: Context? = null,
    val onRenderRequested: (() -> Unit)? = null,
    val onVideoDecoderPrefetch: ((clipIndex: Int, sourceUs: Long) -> Unit)? = null
) : GLSurfaceView.Renderer {

    val glSurfaceView: GLSurfaceView? = context?.let { ctx ->
        GLSurfaceView(ctx).apply {
            setEGLContextClientVersion(3)
            setRenderer(this@PreviewRenderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            this.holder.setFixedSize(PREVIEW_W, PREVIEW_H)
        }
    }

    private val renderer = TransitionRenderer()
    private var surfaceTextureA: SurfaceTexture? = null
    private var surfaceTextureB: SurfaceTexture? = null

    private var frameSourceA: FrameSource? = null
    private var frameSourceB: FrameSource? = null

    private var currentClipIndexA: Int = -1
    private var currentClipIndexB: Int = -1
    private var primarySlot: Int = 0 // 0 = Slot A (stA / texAId), 1 = Slot B (stB / texBId)
    private var standbySlotReadyForClipId: String? = null
    private var standbyPreparedPath: String? = null
    private var standbyPreparedInUs: Long = -1L

    // Pre-allocated arrays and structures (Rule R10: Zero heap allocations on hot path)
    @Volatile var trimPreviewTimeUs: Long = -1L
    private var activeSet: ActiveSet = ActiveSet(holder.current.lanes.size)
    private val resolvedFrame = ResolvedFrame()
    private val frameCtx = MutableFrameCtx()

    private val matA = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
    private val matB = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
    private val cropA = FloatArray(2) { 1f }
    private val cropB = FloatArray(2) { 1f }

    private val contributors = mutableListOf<RenderContributor>()
    private val disabledContributors = mutableSetOf<RenderContributor>()
    private var sortedContributors: Array<RenderContributor> = emptyArray()
    private var cachedSnapshot: TimelineSnapshot? = null
    private var contributorLaneMap: Array<IntArray> = emptyArray()

    private val prefetchers = mutableListOf<Prefetcher>()
    private val hasPendingSurfaceFrameA = AtomicBoolean(false)
    private val hasPendingSurfaceFrameB = AtomicBoolean(false)

    val prefetchCoordinator = PrefetchCoordinator(
        holder = holder,
        playhead = playhead,
        prefetchersProvider = { prefetchers },
        onVideoDecoderPrefetch = { clipIndex, sourceUs ->
            onVideoDecoderPrefetch?.invoke(clipIndex, sourceUs)
        }
    )

    fun registerContributor(c: RenderContributor) {
        if (!contributors.contains(c)) {
            contributors.add(c)
            rebuildContributors()
        }
    }

    fun unregisterContributor(c: RenderContributor) {
        if (contributors.remove(c)) {
            disabledContributors.remove(c)
            rebuildContributors()
        }
    }

    fun isContributorDisabled(c: RenderContributor): Boolean = disabledContributors.contains(c)

    private fun rebuildContributors() {
        val sorted = contributors.sortedBy { it.order }
        sortedContributors = sorted.toTypedArray()
        cachedSnapshot = null
    }

    fun registerPrefetcher(p: Prefetcher) {
        if (!prefetchers.contains(p)) {
            prefetchers.add(p)
        }
    }

    fun unregisterPrefetcher(p: Prefetcher) {
        prefetchers.remove(p)
    }

    fun startPrefetch(scope: CoroutineScope) {
        // Disabled: Standby slot in onDrawFrame handles pre-warming safely without extra decoders.
    }

    fun stopPrefetch() {
        // Disabled
    }

    fun triggerPrefetch(windowUs: Long = 2_000_000L) {
        // Disabled
    }

    fun requestRender() {
        glSurfaceView?.requestRender()
        onRenderRequested?.invoke()
    }

    /**
     * Resolves the current frame and executes active RenderContributors (Hot Path).
     * Zero allocations, zero locks, zero sleep.
     */
    fun renderFrame(
        outputWidth: Int = PREVIEW_W,
        outputHeight: Int = PREVIEW_H
    ): ResolvedFrame {
        // Step 1: Read snapshot and playhead ONCE per frame
        val s = holder.current
        val previewTimeUs = trimPreviewTimeUs
        val tUs = if (previewTimeUs >= 0L) previewTimeUs else playhead.timeUs
        val gen = playhead.generation

        // Step 2: Ensure pre-allocated ActiveSet has capacity for lanes
        if (activeSet.laneItem.size < s.lanes.size) {
            activeSet = ActiveSet(s.lanes.size)
        }
        s.resolve(tUs, activeSet)

        // Step 3: Enforce Rule R11 (TimeMap) for clip source timestamps
        val clipA = if (activeSet.aIndex in s.project.clips.indices) {
            s.project.clips[activeSet.aIndex]
        } else null
        val sourceUsA = clipA?.map()?.sourceUs(activeSet.aLocalUs) ?: 0L

        val isTransition = activeSet.bIndex >= 0 && activeSet.bIndex in s.project.clips.indices
        val clipB = if (isTransition) s.project.clips[activeSet.bIndex] else null
        val sourceUsB = clipB?.map()?.sourceUs(activeSet.bLocalUs) ?: 0L

        // Step 4: Write resolved state into pre-allocated container
        resolvedFrame.clipAIndex = activeSet.aIndex
        resolvedFrame.clipAId = clipA?.id
        resolvedFrame.clipAPath = clipA?.path
        resolvedFrame.clipASourceUs = sourceUsA

        resolvedFrame.isTransition = isTransition
        resolvedFrame.clipBIndex = if (isTransition) activeSet.bIndex else -1
        resolvedFrame.clipBId = clipB?.id
        resolvedFrame.clipBPath = clipB?.path
        resolvedFrame.clipBSourceUs = sourceUsB

        resolvedFrame.transitionType = activeSet.type
        resolvedFrame.linearP = activeSet.linearP
        resolvedFrame.generation = gen
        resolvedFrame.tUs = tUs

        // Step 5: Update lane index table if snapshot changed (not on normal hot path)
        if (s !== cachedSnapshot) {
            updateLaneMapping(s)
        }

        // Step 6: Dispatch to active RenderContributors with failure isolation
        frameCtx.tUs = tUs
        frameCtx.snapshot = s
        frameCtx.active = activeSet
        frameCtx.outputWidth = outputWidth
        frameCtx.outputHeight = outputHeight

        for (i in sortedContributors.indices) {
            val c = sortedContributors[i]
            if (disabledContributors.contains(c)) continue

            val laneIndices = contributorLaneMap[i]
            var isActive = false
            for (j in laneIndices.indices) {
                val laneIdx = laneIndices[j]
                if (laneIdx in activeSet.laneItem.indices && activeSet.laneItem[laneIdx] >= 0) {
                    isActive = true
                    break
                }
            }

            if (isActive) {
                try {
                    c.onFrame(frameCtx)
                } catch (e: Throwable) {
                    disabledContributors.add(c)
                    try {
                        android.util.Log.e(
                            "PreviewRenderer",
                            "Disabling failing contributor ${c.javaClass.simpleName}: ${e.message}",
                            e
                        )
                    } catch (_: Throwable) {
                        System.err.println("PreviewRenderer: Disabling failing contributor ${c.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }

        return resolvedFrame
    }

    private fun updateLaneMapping(s: TimelineSnapshot) {
        cachedSnapshot = s
        val map = Array(sortedContributors.size) { i ->
            val c = sortedContributors[i]
            if (c.kinds.isEmpty()) {
                IntArray(0)
            } else {
                val indices = mutableListOf<Int>()
                for (laneIdx in s.project.lanes.indices) {
                    if (s.project.lanes[laneIdx].kind in c.kinds) {
                        indices.add(laneIdx)
                    }
                }
                indices.toIntArray()
            }
        }
        contributorLaneMap = map
    }

    // ---------------------------------------------------------------------------------------------
    // GLSurfaceView.Renderer implementation
    // ---------------------------------------------------------------------------------------------
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        renderer.init()

        surfaceTextureA?.setOnFrameAvailableListener(null)
        surfaceTextureA?.release()
        surfaceTextureB?.setOnFrameAvailableListener(null)
        surfaceTextureB?.release()
        hasPendingSurfaceFrameA.set(false)
        hasPendingSurfaceFrameB.set(false)

        val stA = SurfaceTexture(renderer.textureIdA()).apply {
            setDefaultBufferSize(PREVIEW_W, PREVIEW_H)
            setOnFrameAvailableListener {
                hasPendingSurfaceFrameA.set(true)
            }
        }
        val stB = SurfaceTexture(renderer.textureIdB()).apply {
            setDefaultBufferSize(PREVIEW_W, PREVIEW_H)
            setOnFrameAvailableListener {
                hasPendingSurfaceFrameB.set(true)
            }
        }

        surfaceTextureA = stA
        surfaceTextureB = stB

        releaseDecoders()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0.08f, 0.08f, 0.07f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val frame = renderFrame(PREVIEW_W, PREVIEW_H)
        if (frame.clipAIndex < 0) return

        val stA = surfaceTextureA ?: return
        val stB = surfaceTextureB ?: return
        val s = holder.current
        val clipA = s.project.clips.getOrNull(frame.clipAIndex) ?: return

        val isScrubbing = (trimPreviewTimeUs >= 0L) ||
                playhead.mode == PlayMode.SCRUBBING ||
                playhead.mode == PlayMode.FLINGING

        if (isScrubbing) {
            standbySlotReadyForClipId = null
            standbyPreparedPath = null
            standbyPreparedInUs = -1L
        }

        // Case 1: Active transition between Clip A and Clip B
        if (frame.isTransition && frame.clipBIndex >= 0) {
            val clipB = s.project.clips.getOrNull(frame.clipBIndex)
            if (clipB != null) {
                // Ensure Clip A is on Slot A
                val clipAChanged = frameSourceA == null ||
                        currentClipIndexA != frame.clipAIndex ||
                        frameSourceA?.clip?.path != clipA.path

                if (clipAChanged) {
                    frameSourceA?.release()
                    val legacyClipA = toLegacyClip(clipA)
                    frameSourceA = FrameSource(legacyClipA, stA).apply {
                        prepare()
                        seekTo(frame.clipASourceUs, fastKeyframeOnly = isScrubbing)
                    }
                    currentClipIndexA = frame.clipAIndex
                    hasPendingSurfaceFrameA.set(false)
                    try {
                        stA.updateTexImage()
                        stA.getTransformMatrix(matA)
                    } catch (_: Exception) {}
                } else {
                    val renderedA = try {
                        frameSourceA?.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing) == true
                    } catch (_: Exception) { false }
                    if (renderedA || hasPendingSurfaceFrameA.getAndSet(false)) {
                        try {
                            stA.updateTexImage()
                            stA.getTransformMatrix(matA)
                        } catch (_: Exception) {}
                    }
                }
                calculateCropInto(clipA.displayWidth, clipA.displayHeight, cropA)

                // Ensure Clip B is on Slot B
                val clipBChanged = frameSourceB == null ||
                        currentClipIndexB != frame.clipBIndex ||
                        frameSourceB?.clip?.path != clipB.path

                if (clipBChanged) {
                    frameSourceB?.release()
                    val legacyClipB = toLegacyClip(clipB)
                    frameSourceB = FrameSource(legacyClipB, stB).apply {
                        prepare()
                        seekTo(frame.clipBSourceUs, fastKeyframeOnly = isScrubbing)
                    }
                    currentClipIndexB = frame.clipBIndex
                    hasPendingSurfaceFrameB.set(false)
                    try {
                        stB.updateTexImage()
                        stB.getTransformMatrix(matB)
                    } catch (_: Exception) {}
                } else {
                    val renderedB = try {
                        frameSourceB?.frameAt(frame.clipBSourceUs, isScrubbing = isScrubbing) == true
                    } catch (_: Exception) { false }
                    if (renderedB || hasPendingSurfaceFrameB.getAndSet(false)) {
                        try {
                            stB.updateTexImage()
                            stB.getTransformMatrix(matB)
                        } catch (_: Exception) {}
                    }
                }
                calculateCropInto(clipB.displayWidth, clipB.displayHeight, cropB)

                val easedP = ProgramTimeline.ease(frame.linearP)
                val legacyType = when (frame.transitionType) {
                    TransitionType.NONE -> com.flowcut.app.model.TransitionType.NONE
                    TransitionType.SLIDE -> com.flowcut.app.model.TransitionType.SLIDE
                    TransitionType.CIRCLE -> com.flowcut.app.model.TransitionType.CIRCLE
                    TransitionType.ZOOM -> com.flowcut.app.model.TransitionType.ZOOM
                    TransitionType.FADE -> com.flowcut.app.model.TransitionType.FADE
                }

                try {
                    renderer.draw(
                        DrawSpec(
                            type = legacyType,
                            easedP = easedP,
                            matA = matA,
                            matB = matB,
                            cropA = cropA,
                            cropB = cropB
                        )
                    )
                } catch (_: Exception) {}

                // Transition completes into Slot B as primary
                primarySlot = 1
                standbySlotReadyForClipId = null
                return
            }
        }

        // Case 2: Single clip playback with Ping-Pong Primary & Standby Slots (Hard Cut)
        // 0ms Instant Cut Handover at the Boundary:
        if (primarySlot == 0) {
            if ((standbySlotReadyForClipId == clipA.id || standbyPreparedPath == clipA.path) && frameSourceB != null && frameSourceB?.currentPath == clipA.path) {
                primarySlot = 1
                standbySlotReadyForClipId = null
                standbyPreparedPath = null
                standbyPreparedInUs = -1L
                frameSourceA?.release()
                frameSourceA = null
                currentClipIndexA = -1
            }
        } else {
            if ((standbySlotReadyForClipId == clipA.id || standbyPreparedPath == clipA.path) && frameSourceA != null && frameSourceA?.currentPath == clipA.path) {
                primarySlot = 0
                standbySlotReadyForClipId = null
                standbyPreparedPath = null
                standbyPreparedInUs = -1L
                frameSourceB?.release()
                frameSourceB = null
                currentClipIndexB = -1
            }
        }

        if (primarySlot == 0) {
            // --- Slot A is Primary (stA / texAId) ---
            val clipAChanged = frameSourceA == null || frameSourceA?.currentPath != clipA.path

            if (clipAChanged) {
                frameSourceA?.release()
                val legacyClipA = toLegacyClip(clipA)
                frameSourceA = FrameSource(legacyClipA, stA).apply {
                    prepare()
                    seekTo(frame.clipASourceUs, fastKeyframeOnly = isScrubbing)
                }
                currentClipIndexA = frame.clipAIndex
                hasPendingSurfaceFrameA.set(false)
                try {
                    stA.updateTexImage()
                    stA.getTransformMatrix(matA)
                } catch (_: Exception) {}
            } else {
                currentClipIndexA = frame.clipAIndex
                val renderedA = try {
                    frameSourceA?.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing) == true
                } catch (_: Exception) { false }

                if (renderedA || hasPendingSurfaceFrameA.getAndSet(false)) {
                    try {
                        stA.updateTexImage()
                        stA.getTransformMatrix(matA)
                    } catch (_: Exception) {}
                }
            }
            calculateCropInto(clipA.displayWidth, clipA.displayHeight, cropA)

            // Standby Slot B: 3-Stage Incremental Pre-warm of upcoming different-path clip within 500ms
            var m = frame.clipAIndex + 1
            while (m < s.project.clips.size && s.project.clips[m].path == clipA.path) {
                m++
            }

            if (m in s.project.clips.indices && !isScrubbing && playhead.mode == PlayMode.PLAYING) {
                val nextClip = s.project.clips[m]
                val nextStartUs = msToUs(s.main.startMs[m])
                val timeUntilClipMUs = nextStartUs - frame.tUs
                if (timeUntilClipMUs in 1L..500_000L) {
                    val nextClipInUs = nextClip.map().inUs
                    val alreadyPrepared = (standbyPreparedPath == nextClip.path && standbyPreparedInUs == nextClipInUs) ||
                            (standbySlotReadyForClipId == nextClip.id)
                    if (!alreadyPrepared) {
                        if (frameSourceB == null || frameSourceB?.currentPath != nextClip.path) {
                            frameSourceB?.release()
                            frameSourceB = FrameSource(toLegacyClip(nextClip), stB)
                            currentClipIndexB = m
                        }
                        val fsB = frameSourceB
                        if (fsB != null) {
                            when (fsB.prewarmStage) {
                                0 -> fsB.prepareExtractorOnly()
                                1 -> fsB.startCodecAndSeek(nextClipInUs)
                                2 -> {
                                    val ready = fsB.stepPrewarmTo(nextClipInUs)
                                    if (ready) {
                                        hasPendingSurfaceFrameB.set(false)
                                        try {
                                            stB.updateTexImage()
                                            stB.getTransformMatrix(matB)
                                        } catch (_: Exception) {}
                                        calculateCropInto(nextClip.displayWidth, nextClip.displayHeight, cropB)
                                        standbySlotReadyForClipId = nextClip.id
                                        standbyPreparedPath = nextClip.path
                                        standbyPreparedInUs = nextClipInUs
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!isScrubbing && standbySlotReadyForClipId != null && m !in s.project.clips.indices) {
                frameSourceB?.release()
                frameSourceB = null
                currentClipIndexB = -1
                standbySlotReadyForClipId = null
                standbyPreparedPath = null
                standbyPreparedInUs = -1L
            }

            try {
                renderer.draw(
                    DrawSpec(
                        type = com.flowcut.app.model.TransitionType.NONE,
                        easedP = 0.0f,
                        matA = matA,
                        matB = matB,
                        cropA = cropA,
                        cropB = cropB
                    )
                )
            } catch (_: Exception) {}
        } else {
            // --- Slot B is Primary (stB / texBId) ---
            val clipBChanged = frameSourceB == null || frameSourceB?.currentPath != clipA.path

            if (clipBChanged) {
                frameSourceB?.release()
                val legacyClipA = toLegacyClip(clipA)
                frameSourceB = FrameSource(legacyClipA, stB).apply {
                    prepare()
                    seekTo(frame.clipASourceUs, fastKeyframeOnly = isScrubbing)
                }
                currentClipIndexB = frame.clipAIndex
                hasPendingSurfaceFrameB.set(false)
                try {
                    stB.updateTexImage()
                    stB.getTransformMatrix(matB)
                } catch (_: Exception) {}
            } else {
                currentClipIndexB = frame.clipAIndex
                val renderedB = try {
                    frameSourceB?.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing) == true
                } catch (_: Exception) { false }

                if (renderedB || hasPendingSurfaceFrameB.getAndSet(false)) {
                    try {
                        stB.updateTexImage()
                        stB.getTransformMatrix(matB)
                    } catch (_: Exception) {}
                }
            }
            calculateCropInto(clipA.displayWidth, clipA.displayHeight, cropB)

            // Standby Slot A: 3-Stage Incremental Pre-warm of upcoming different-path clip within 500ms
            var m = frame.clipAIndex + 1
            while (m < s.project.clips.size && s.project.clips[m].path == clipA.path) {
                m++
            }

            if (m in s.project.clips.indices && !isScrubbing && playhead.mode == PlayMode.PLAYING) {
                val nextClip = s.project.clips[m]
                val nextStartUs = msToUs(s.main.startMs[m])
                val timeUntilClipMUs = nextStartUs - frame.tUs
                if (timeUntilClipMUs in 1L..500_000L) {
                    val nextClipInUs = nextClip.map().inUs
                    val alreadyPrepared = (standbyPreparedPath == nextClip.path && standbyPreparedInUs == nextClipInUs) ||
                            (standbySlotReadyForClipId == nextClip.id)
                    if (!alreadyPrepared) {
                        if (frameSourceA == null || frameSourceA?.currentPath != nextClip.path) {
                            frameSourceA?.release()
                            frameSourceA = FrameSource(toLegacyClip(nextClip), stA)
                            currentClipIndexA = m
                        }
                        val fsA = frameSourceA
                        if (fsA != null) {
                            when (fsA.prewarmStage) {
                                0 -> fsA.prepareExtractorOnly()
                                1 -> fsA.startCodecAndSeek(nextClipInUs)
                                2 -> {
                                    val ready = fsA.stepPrewarmTo(nextClipInUs)
                                    if (ready) {
                                        hasPendingSurfaceFrameA.set(false)
                                        try {
                                            stA.updateTexImage()
                                            stA.getTransformMatrix(matA)
                                        } catch (_: Exception) {}
                                        calculateCropInto(nextClip.displayWidth, nextClip.displayHeight, cropA)
                                        standbySlotReadyForClipId = nextClip.id
                                        standbyPreparedPath = nextClip.path
                                        standbyPreparedInUs = nextClipInUs
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!isScrubbing && standbySlotReadyForClipId != null && m !in s.project.clips.indices) {
                frameSourceA?.release()
                frameSourceA = null
                currentClipIndexA = -1
                standbySlotReadyForClipId = null
                standbyPreparedPath = null
                standbyPreparedInUs = -1L
            }

            try {
                renderer.draw(
                    DrawSpec(
                        type = com.flowcut.app.model.TransitionType.FADE,
                        easedP = 1.0f,
                        matA = matA,
                        matB = matB,
                        cropA = cropA,
                        cropB = cropB
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun releaseDecoders() {
        frameSourceA?.release()
        frameSourceA = null
        currentClipIndexA = -1

        frameSourceB?.release()
        frameSourceB = null
        currentClipIndexB = -1

        primarySlot = 0
        standbySlotReadyForClipId = null
    }

    fun release() {
        trimPreviewTimeUs = -1L
        stopPrefetch()
        releaseDecoders()
        surfaceTextureA?.setOnFrameAvailableListener(null)
        surfaceTextureA?.release()
        surfaceTextureA = null
        surfaceTextureB?.setOnFrameAvailableListener(null)
        surfaceTextureB?.release()
        surfaceTextureB = null
        hasPendingSurfaceFrameA.set(false)
        hasPendingSurfaceFrameB.set(false)
        try {
            renderer.release()
        } catch (_: Throwable) {}
        for (c in contributors) {
            try {
                c.release()
            } catch (_: Throwable) {}
        }
    }

    companion object {
        fun calculateCropInto(displayWidth: Int, displayHeight: Int, out: FloatArray) {
            val aspect = displayWidth.toFloat() / displayHeight.toFloat()
            val target = 9f / 16f
            if (aspect > target) {
                out[0] = target / aspect
                out[1] = 1.0f
            } else {
                out[0] = 1.0f
                out[1] = aspect / target
            }
        }

        fun toLegacyClip(clip: Clip): com.flowcut.app.model.Clip {
            return com.flowcut.app.model.Clip(
                id = clip.id,
                path = clip.path,
                durationMs = clip.durationMs,
                width = clip.width,
                height = clip.height,
                rotation = clip.rotation,
                frameRate = clip.frameRate
            )
        }
    }
}
