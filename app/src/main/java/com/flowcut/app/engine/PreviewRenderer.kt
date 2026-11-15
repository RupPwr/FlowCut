package com.flowcut.app.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live preview resolution (720x1280, 9:16 portrait).
 * ExportEngine stays at full 1080x1920.
 */
const val PREVIEW_W: Int = 720
const val PREVIEW_H: Int = 1280

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
 * Per-clip transform state: rotation in degrees (continuous Float)
 * and horizontal mirror (isFlippedH).
 */
data class ClipTransform(
    var rotationDeg: Float = 0f,
    var isFlippedH: Boolean = false,
    var scale: Float = 1.0f, // 0.25f to 5.0f
    var panX: Float = 0.0f,  // normalized screen offset
    var panY: Float = 0.0f,  // normalized screen offset
    var opacity: Float = 1.0f // 0.0f to 1.0f, default 1.0f = 100%
) {
    companion object {
        val DEFAULT = ClipTransform(0f, false, 1.0f, 0.0f, 0.0f, 1.0f)
    }
}

/**
 * Per-clip color adjustment state (Requirement 1):
 * Normalized values in -1.0f..1.0f (sharpness in 0.0f..1.0f), all defaulting to 0.0f.
 */
data class ClipColorAdjust(
    val brightness: Float = 0f,  // -1f..1f (UI: -50 to +50)
    val contrast: Float = 0f,    // -1f..1f (UI: -50 to +50)
    val saturation: Float = 0f,  // -1f..1f (UI: -50 to +50, -1f = grayscale)
    val exposure: Float = 0f,    // -1f..1f (UI: -50 to +50)
    val warmth: Float = 0f,      // -1f..1f (UI: -50 to +50, cool to warm)
    val tint: Float = 0f,        // -1f..1f (UI: -50 to +50, green to magenta)
    val highlights: Float = 0f,  // -1f..1f (UI: -50 to +50)
    val shadows: Float = 0f,     // -1f..1f (UI: -50 to +50)
    val sharpness: Float = 0f,   //  0f..1f (UI:  0 to 100)
    val vignette: Float = 0f     // -1f..1f (UI: -50 to +50)
) {
    val isIdentity: Boolean get() =
        brightness == 0f && contrast == 0f && saturation == 0f &&
        exposure == 0f && warmth == 0f && tint == 0f &&
        highlights == 0f && shadows == 0f && sharpness == 0f && vignette == 0f

    companion object {
        val DEFAULT = ClipColorAdjust()
    }
}

/**
 * Per-clip canvas background state (Requirement 1):
 * NONE (black padding), COLOR (solid ARGB color), BLUR (creamy Gaussian blur of the clip), or GRADIENT (Phase 9).
 */
enum class BackgroundType { NONE, COLOR, BLUR }

data class ClipBackground(
    val type: BackgroundType = BackgroundType.NONE,
    val colorArgb: Int = 0xFF000000.toInt(), // Used when type == COLOR
    val blurLevel: Float = 0.5f,             // 0.1f..1.0f (presets: 0.25f, 0.5f, 0.75f, 1.0f)
    val gradientIndex: Int = -1              // -1 = none, 0..5 = smooth 2-color vertical gradients (Phase 9)
) {
    companion object {
        val DEFAULT = ClipBackground()
    }
}

/**
 * 12 Pro aspect ratios supported across Preview and Export (Phase 9).
 * All export dimensions are even integers divisible by 2 for MediaCodec H.264 compatibility.
 */
enum class AspectRatioMode(
    val id: Int,
    val label: String,
    val aspect: Float,
    val exportWidth: Int,
    val exportHeight: Int,
    val ratioWidth: Int = 9,
    val ratioHeight: Int = 16
) {
    ORIGINAL(-1, "Original", 0f, 0, 0, 0, 0),
    RATIO_9_16(0, "9:16", 9f / 16f, 1080, 1920, 9, 16),
    RATIO_16_9(1, "16:9", 16f / 9f, 1920, 1080, 16, 9),
    RATIO_1_1(2, "1:1", 1f / 1f, 1080, 1080, 1, 1),
    RATIO_4_5(3, "4:5", 4f / 5f, 1080, 1350, 4, 5),
    RATIO_4_3(4, "4:3", 4f / 3f, 1440, 1080, 4, 3),
    RATIO_3_4(5, "3:4", 3f / 4f, 1080, 1440, 3, 4),
    RATIO_5_4(6, "5:4", 5f / 4f, 1350, 1080, 5, 4),
    RATIO_3_2(7, "3:2", 3f / 2f, 1620, 1080, 3, 2),
    RATIO_2_3(8, "2:3", 2f / 3f, 1080, 1620, 2, 3),
    RATIO_2_1(9, "2:1", 2f / 1f, 1920, 960, 2, 1),
    RATIO_1_2(10, "1:2", 1f / 2f, 960, 1920, 1, 2),
    RATIO_21_9(11, "21:9", 21f / 9f, 1920, 822, 21, 9);

    val width: Int get() = exportWidth
    val height: Int get() = exportHeight

    companion object {
        fun fromIndex(index: Int): AspectRatioMode =
            entries.firstOrNull { it.id == index } ?: entries.getOrNull(index) ?: RATIO_9_16

        fun fromLabel(label: String): AspectRatioMode =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: RATIO_9_16
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
    var onRenderRequested: (() -> Unit)? = null,
    val onVideoDecoderPrefetch: ((clipIndex: Int, sourceUs: Long) -> Unit)? = null
) : GLSurfaceView.Renderer {

    val glSurfaceView: GLSurfaceView? = context?.let { ctx ->
        GLSurfaceView(ctx).apply {
            setEGLContextClientVersion(3)
            setRenderer(this@PreviewRenderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            this.holder.setSizeFromLayout()
        }
    }

    private val renderer = TransitionRenderer(context?.cacheDir)
    private val stCallbackThread = HandlerThread("FlowCut-ST-Frames").apply { start() }
    private val stCallbackHandler = Handler(stCallbackThread.looper)

    @Volatile
    var isFirstFrameLatched: Boolean = false

    @Volatile
    var isWaitingForFrame: Boolean = false
        set(value) {
            val wasWaiting = field
            if (field != value) {
                field = value
                onWaitingForFrameChanged?.invoke(value)
                if (wasWaiting && !value) {
                    onRenderRequested?.invoke()
                }
            }
        }

    var onWaitingForFrameChanged: ((Boolean) -> Unit)? = null
    @Volatile
    var lateFrames: Long = 0L
    @Volatile
    var renderedFrames: Long = 0L

    val overlayTextureCache = OverlayTextureCache().apply {
        onFrameReady = { requestRender() }
    }
    private val animEvaluator = AnimatedTransformEvaluator()
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
    private var lastTransitionIncomingSlot: Int = -1
    private var lastTransitionIncomingClipIndex: Int = -1
    private var lastRenderedTransitionJunction: Int = -1
    private var smoothedTransitionP: Float = 0f

    // Pre-allocated arrays and structures (Rule R10: Zero heap allocations on hot path)
    @Volatile var trimPreviewTimeUs: Long = -1L
    private var activeSet: ActiveSet = ActiveSet(holder.current.lanes.size)
    private val activeLayerSet = ActiveLayerSet()
    private val resolvedFrame = ResolvedFrame()
    private val frameCtx = MutableFrameCtx()

    private val matA = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
    private val matB = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
    private val rawMatA = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val rawMatB = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val cropA = FloatArray(2) { 1f }
    private val cropB = FloatArray(2) { 1f }
    private val scissorRect = IntArray(4)

    // Pre-allocated per-slot color adjustment uniforms (Rule R10: Zero heap allocations on GL render path)
    private val adjA0 = FloatArray(4)
    private val adjA1 = FloatArray(4)
    private val adjA2 = FloatArray(4)
    private val adjB0 = FloatArray(4)
    private val adjB1 = FloatArray(4)
    private val adjB2 = FloatArray(4)
    private val texelSize = FloatArray(2).apply {
        this[0] = 1.0f / PREVIEW_W.toFloat()
        this[1] = 1.0f / PREVIEW_H.toFloat()
    }

    private var viewportWidth: Int = PREVIEW_W
    private var viewportHeight: Int = PREVIEW_H

    @Volatile
    var isSuspendedForExport: Boolean = false

    init {
        activeInstance = this
    }

    @Volatile
    private var _canvasAspectRatio: Float = Companion.canvasAspectRatio
    val canvasAspectRatio: Float get() = _canvasAspectRatio

    fun setAspectRatioMode(mode: AspectRatioMode, firstClip: Any? = null) {
        val clip0 = firstClip ?: holder.current.project.clips.firstOrNull()
        Companion.setAspectRatioMode(mode, clip0)
        val aspect = Companion.resolveEffectiveAspect(mode, clip0)
        setCanvasAspectRatio(aspect)
    }

    fun setCanvasAspectRatio(aspect: Float) {
        if (aspect > 0f) {
            _canvasAspectRatio = aspect
            Companion.setCanvasAspectRatio(aspect)
            TransitionRenderer.canvasAspect = aspect
            glSurfaceView?.holder?.setSizeFromLayout()
            requestRender()
            onRenderRequested?.invoke()
        }
    }

    fun setJunctionTransitionType(junctionIndex: Int, type: Int) {
        Companion.setJunctionTransitionType(junctionIndex, type)
    }

    fun getJunctionTransitionType(junctionIndex: Int): Int =
        Companion.getJunctionTransitionType(junctionIndex)

    // Zero-allocation frame-time telemetry logger (Rule R10)
    private var frameTimeSumUs: Long = 0L
    private var frameTimeMaxUs: Long = 0L
    private var frameCount: Int = 0

    // Pre-allocated per-slot background and foreground matrices & parameters (Rule R10)
    private val fgNormMatA = FloatArray(16)
    private val fgNormMatB = FloatArray(16)
    private val bgNormMat = FloatArray(16)
    private val bgMatA = FloatArray(16)
    private val bgMatB = FloatArray(16)
    private val bgParamsA = FloatArray(4)
    private val bgParamsB = FloatArray(4)
    private var lastActiveVideoTexId: Int = 0
    private val lastActiveVideoMat = FloatArray(16)
    private val lastActiveCrop = floatArrayOf(1f, 1f)

    private val drawSpec = DrawSpec()

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

    /**
     * Currently selected overlay item ID drawn last (topmost) on preview without mutating project z-index.
     */
    @Volatile
    var selectedOverlayIdForPreview: String? = null

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
            setOnFrameAvailableListener({
                hasPendingSurfaceFrameA.set(true)
                onRenderRequested?.invoke()
                glSurfaceView?.requestRender()
            }, stCallbackHandler)
        }
        val stB = SurfaceTexture(renderer.textureIdB()).apply {
            setDefaultBufferSize(PREVIEW_W, PREVIEW_H)
            setOnFrameAvailableListener({
                hasPendingSurfaceFrameB.set(true)
                onRenderRequested?.invoke()
                glSurfaceView?.requestRender()
            }, stCallbackHandler)
        }

        surfaceTextureA = stA
        surfaceTextureB = stB

        releaseDecoders()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            viewportWidth = width
            viewportHeight = height
            texelSize[0] = 1.0f / width.toFloat()
            texelSize[1] = 1.0f / height.toFloat()
            val surfaceAspect = width.toFloat() / height.toFloat()
            val currentMode = currentAspectRatioMode
            val firstClip = holder.current.project.clips.firstOrNull()
            val resolvedAspect = if (firstClip != null || currentMode != AspectRatioMode.ORIGINAL) {
                Companion.resolveEffectiveAspect(currentMode, firstClip)
            } else {
                surfaceAspect
            }
            val targetAspect = if (resolvedAspect > 0f) resolvedAspect else if (_canvasAspectRatio > 0f) _canvasAspectRatio else surfaceAspect
            _canvasAspectRatio = targetAspect
            Companion.setCanvasAspectRatio(targetAspect)
            TransitionRenderer.canvasAspect = targetAspect
        }
        GLES30.glViewport(0, 0, width, height)
        requestRender()
        onRenderRequested?.invoke()
    }

    override fun onDrawFrame(gl: GL10?) {
        if (isSuspendedForExport) {
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val startNs = System.nanoTime()
        val wasWaitingInitial = isWaitingForFrame
        try {
            val s = holder.current
            val clips = s.project.clips
            if (clips.isEmpty()) {
                isFirstFrameLatched = false
                isWaitingForFrame = false
                GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
                GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                return
            }

            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)

            val frame = renderFrame(viewportWidth, viewportHeight)
            if (frame.clipAIndex < 0) {
                GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                return
            }

            val stA = surfaceTextureA ?: return
            val stB = surfaceTextureB ?: return
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
                    val isOutgoingOnSlotB = (currentClipIndexB == frame.clipAIndex && frameSourceB?.clip?.id == clipA.id && currentClipIndexA != frame.clipAIndex)
                    drawSpec.swapTextures = isOutgoingOnSlotB

                    val transformA = getClipTransform(clipA.id)
                    val transformB = getClipTransform(clipB.id)

                    if (isOutgoingOnSlotB) {
                        // Outgoing clipA is on Slot B (stB)
                        val clipAChanged = frameSourceB == null ||
                                currentClipIndexB != frame.clipAIndex ||
                                frameSourceB?.clip?.id != clipA.id
                        if (clipAChanged) {
                            frameSourceB?.release()
                            frameSourceB = FrameSource(toLegacyClip(clipA), stB)
                            currentClipIndexB = frame.clipAIndex
                        }
                        val fsOutgoing = frameSourceB
                        if (fsOutgoing != null && fsOutgoing.prewarmStage < 3) {
                            fsOutgoing.ensureDecodedAt(frame.clipASourceUs)
                        }
                        val renderedA = try {
                            fsOutgoing?.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing) ?: false
                        } catch (_: Exception) { false }
                        if (renderedA || hasPendingSurfaceFrameB.getAndSet(false)) {
                            try {
                                stB.updateTexImage()
                                stB.getTransformMatrix(rawMatB)
                                isFirstFrameLatched = true
                                isWaitingForFrame = false
                            } catch (_: Exception) {}
                        }

                        // Incoming clipB is on Slot A (stA)
                        val clipBChanged = frameSourceA == null ||
                                currentClipIndexA != frame.clipBIndex ||
                                frameSourceA?.clip?.id != clipB.id
                        if (clipBChanged) {
                            frameSourceA?.release()
                            frameSourceA = FrameSource(toLegacyClip(clipB), stA)
                            currentClipIndexA = frame.clipBIndex
                        }
                        val fsIncoming = frameSourceA
                        if (fsIncoming != null && fsIncoming.prewarmStage < 3) {
                            fsIncoming.ensureDecodedAt(frame.clipBSourceUs)
                        }
                        val renderedB = try {
                            fsIncoming?.frameAt(frame.clipBSourceUs, isScrubbing = isScrubbing) ?: false
                        } catch (_: Exception) { false }
                        if (renderedB || hasPendingSurfaceFrameA.getAndSet(false)) {
                            try {
                                stA.updateTexImage()
                                stA.getTransformMatrix(rawMatA)
                                isFirstFrameLatched = true
                                isWaitingForFrame = false
                            } catch (_: Exception) {}
                        }

                        computeFgNormMatrix(fgNormMatA, transformA, clipA.displayWidth, clipA.displayHeight, this.canvasAspectRatio)
                        System.arraycopy(rawMatB, 0, matA, 0, 16)
                        calculateClipCrop(clipA.displayWidth, clipA.displayHeight, transformA, cropA)
                        computeBgNormMatrix(bgNormMat, transformA, clipA.displayWidth, clipA.displayHeight, this.canvasAspectRatio)
                        Matrix.multiplyMM(bgMatA, 0, rawMatB, 0, bgNormMat, 0)

                        computeFgNormMatrix(fgNormMatB, transformB, clipB.displayWidth, clipB.displayHeight, this.canvasAspectRatio)
                        System.arraycopy(rawMatA, 0, matB, 0, 16)
                        calculateClipCrop(clipB.displayWidth, clipB.displayHeight, transformB, cropB)
                        computeBgNormMatrix(bgNormMat, transformB, clipB.displayWidth, clipB.displayHeight, this.canvasAspectRatio)
                        Matrix.multiplyMM(bgMatB, 0, rawMatA, 0, bgNormMat, 0)
                    } else {
                        // Outgoing clipA is on Slot A (stA)
                        val clipAChanged = frameSourceA == null ||
                                currentClipIndexA != frame.clipAIndex ||
                                frameSourceA?.clip?.id != clipA.id
                        if (clipAChanged) {
                            frameSourceA?.release()
                            frameSourceA = FrameSource(toLegacyClip(clipA), stA)
                            currentClipIndexA = frame.clipAIndex
                        }
                        val fsOutgoing = frameSourceA
                        if (fsOutgoing != null && fsOutgoing.prewarmStage < 3) {
                            fsOutgoing.ensureDecodedAt(frame.clipASourceUs)
                        }
                        val renderedA = try {
                            fsOutgoing?.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing) ?: false
                        } catch (_: Exception) { false }
                        if (renderedA || hasPendingSurfaceFrameA.getAndSet(false)) {
                            try {
                                stA.updateTexImage()
                                stA.getTransformMatrix(rawMatA)
                                isFirstFrameLatched = true
                                isWaitingForFrame = false
                            } catch (_: Exception) {}
                        }

                        // Incoming clipB is on Slot B (stB)
                        val clipBChanged = frameSourceB == null ||
                                currentClipIndexB != frame.clipBIndex ||
                                frameSourceB?.clip?.id != clipB.id
                        if (clipBChanged) {
                            frameSourceB?.release()
                            frameSourceB = FrameSource(toLegacyClip(clipB), stB)
                            currentClipIndexB = frame.clipBIndex
                        }
                        val fsIncoming = frameSourceB
                        if (fsIncoming != null && fsIncoming.prewarmStage < 3) {
                            fsIncoming.ensureDecodedAt(frame.clipBSourceUs)
                        }
                        val renderedB = try {
                            fsIncoming?.frameAt(frame.clipBSourceUs, isScrubbing = isScrubbing) ?: false
                        } catch (_: Exception) { false }
                        if (renderedB || hasPendingSurfaceFrameB.getAndSet(false)) {
                            try {
                                stB.updateTexImage()
                                stB.getTransformMatrix(rawMatB)
                                isFirstFrameLatched = true
                                isWaitingForFrame = false
                            } catch (_: Exception) {}
                        }

                        computeFgNormMatrix(fgNormMatA, transformA, clipA.displayWidth, clipA.displayHeight, this.canvasAspectRatio)
                        System.arraycopy(rawMatA, 0, matA, 0, 16)
                        calculateClipCrop(clipA.displayWidth, clipA.displayHeight, transformA, cropA)
                        computeBgNormMatrix(bgNormMat, transformA, clipA.displayWidth, clipA.displayHeight, this.canvasAspectRatio)
                        Matrix.multiplyMM(bgMatA, 0, rawMatA, 0, bgNormMat, 0)

                        computeFgNormMatrix(fgNormMatB, transformB, clipB.displayWidth, clipB.displayHeight, this.canvasAspectRatio)
                        System.arraycopy(rawMatB, 0, matB, 0, 16)
                        calculateClipCrop(clipB.displayWidth, clipB.displayHeight, transformB, cropB)
                        computeBgNormMatrix(bgNormMat, transformB, clipB.displayWidth, clipB.displayHeight, this.canvasAspectRatio)
                        Matrix.multiplyMM(bgMatB, 0, rawMatB, 0, bgNormMat, 0)
                    }

                    val aScreen = if (canvasAspectRatio > 0f) canvasAspectRatio else (9f / 16f)

                    val bgA = getClipBackground(clipA.id)
                    var bgModeA = packBackground(bgA, bgParamsA)
                    val videoAspectA = computeVideoAspect(clipA.displayWidth, clipA.displayHeight, transformA)
                    if (bgModeA == 0 && (kotlin.math.abs(videoAspectA - aScreen) > 0.01f || transformA.scale < 0.999f)) {
                        bgModeA = 2
                        bgParamsA[0] = 0f
                        bgParamsA[1] = 0f
                        bgParamsA[2] = 0f
                        bgParamsA[3] = if (bgA.blurLevel > 0.01f) bgA.blurLevel.coerceIn(0.01f, 1.0f) else 0.5f
                    }
                    if (bgModeA != 0 && isForegroundCoveringCanvas(transformA, clipA.displayWidth, clipA.displayHeight)) {
                        bgModeA = 0
                    }

                    val bgB = getClipBackground(clipB.id)
                    var bgModeB = packBackground(bgB, bgParamsB)
                    val videoAspectB = computeVideoAspect(clipB.displayWidth, clipB.displayHeight, transformB)
                    if (bgModeB == 0 && (kotlin.math.abs(videoAspectB - aScreen) > 0.01f || transformB.scale < 0.999f)) {
                        bgModeB = 2
                        bgParamsB[0] = 0f
                        bgParamsB[1] = 0f
                        bgParamsB[2] = 0f
                        bgParamsB[3] = if (bgB.blurLevel > 0.01f) bgB.blurLevel.coerceIn(0.01f, 1.0f) else 0.5f
                    }
                    if (bgModeB != 0 && isForegroundCoveringCanvas(transformB, clipB.displayWidth, clipB.displayHeight)) {
                        bgModeB = 0
                    }

                    val targetLinearP = frame.linearP.coerceIn(0.0f, 1.0f)
                    val effectiveLinearP: Float
                    if (playhead.mode == PlayMode.PLAYING && !isScrubbing) {
                        if (lastRenderedTransitionJunction != frame.clipAIndex) {
                            lastRenderedTransitionJunction = frame.clipAIndex
                            smoothedTransitionP = 0f
                        }
                        smoothedTransitionP = if (targetLinearP > smoothedTransitionP) {
                            (smoothedTransitionP + 0.25f).coerceAtMost(targetLinearP)
                        } else {
                            targetLinearP
                        }
                        effectiveLinearP = smoothedTransitionP
                    } else {
                        lastRenderedTransitionJunction = frame.clipAIndex
                        smoothedTransitionP = targetLinearP
                        effectiveLinearP = targetLinearP
                    }
                    val easedP = ProgramTimeline.ease(effectiveLinearP)
                    val legacyType = when (frame.transitionType) {
                        TransitionType.NONE -> com.flowcut.app.model.TransitionType.NONE
                        TransitionType.SLIDE -> com.flowcut.app.model.TransitionType.SLIDE
                        TransitionType.CIRCLE -> com.flowcut.app.model.TransitionType.CIRCLE
                        TransitionType.ZOOM -> com.flowcut.app.model.TransitionType.ZOOM
                        TransitionType.FADE -> com.flowcut.app.model.TransitionType.FADE
                    }

                    val needsScissor = (bgModeA == 0 && bgModeB == 0) &&
                        computeScissorRect(viewportWidth, viewportHeight, transformA, clipA.displayWidth, clipA.displayHeight, scissorRect)
                    if (needsScissor) {
                        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                        GLES30.glScissor(scissorRect[0], scissorRect[1], scissorRect[2], scissorRect[3])
                    }
                    try {
                        val adjustA = getClipColorAdjust(clipA.id)
                        packAdjust(adjustA, adjA0, adjA1, adjA2)
                        val adjustB = getClipColorAdjust(clipB.id)
                        packAdjust(adjustB, adjB0, adjB1, adjB2)

                        drawSpec.type = legacyType
                        drawSpec.transitionType = getJunctionTransitionType(frame.clipAIndex)
                        drawSpec.easedP = easedP
                        drawSpec.matA = matA
                        drawSpec.matB = matB
                        drawSpec.cropA = cropA
                        drawSpec.cropB = cropB
                        drawSpec.opacityA = transformA.opacity
                        drawSpec.opacityB = transformB.opacity
                        drawSpec.adjA0 = adjA0
                        drawSpec.adjA1 = adjA1
                        drawSpec.adjA2 = adjA2
                        drawSpec.adjB0 = adjB0
                        drawSpec.adjB1 = adjB1
                        drawSpec.adjB2 = adjB2
                        drawSpec.texelSize = texelSize
                        drawSpec.fgNormMatA = fgNormMatA
                        drawSpec.fgNormMatB = fgNormMatB
                        drawSpec.bgMatA = bgMatA
                        drawSpec.bgMatB = bgMatB
                        drawSpec.bgModeA = bgModeA
                        drawSpec.bgModeB = bgModeB
                        drawSpec.bgParamsA = bgParamsA
                        drawSpec.bgParamsB = bgParamsB
                        drawSpec.aspectRatio = if (canvasAspectRatio > 0f) canvasAspectRatio else if (viewportHeight > 0) viewportWidth.toFloat() / viewportHeight.toFloat() else (9.0f / 16.0f)

                        if (isOutgoingOnSlotB) {
                            if (easedP < 0.5f) {
                                lastActiveVideoTexId = renderer.textureIdB()
                                Matrix.multiplyMM(lastActiveVideoMat, 0, rawMatB, 0, fgNormMatA, 0)
                                lastActiveCrop[0] = cropA[0]
                                lastActiveCrop[1] = cropA[1]
                            } else {
                                lastActiveVideoTexId = renderer.textureIdA()
                                Matrix.multiplyMM(lastActiveVideoMat, 0, rawMatA, 0, fgNormMatB, 0)
                                lastActiveCrop[0] = cropB[0]
                                lastActiveCrop[1] = cropB[1]
                            }
                        } else {
                            if (easedP < 0.5f) {
                                lastActiveVideoTexId = renderer.textureIdA()
                                Matrix.multiplyMM(lastActiveVideoMat, 0, rawMatA, 0, fgNormMatA, 0)
                                lastActiveCrop[0] = cropA[0]
                                lastActiveCrop[1] = cropA[1]
                            } else {
                                lastActiveVideoTexId = renderer.textureIdB()
                                Matrix.multiplyMM(lastActiveVideoMat, 0, rawMatB, 0, fgNormMatB, 0)
                                lastActiveCrop[0] = cropB[0]
                                lastActiveCrop[1] = cropB[1]
                            }
                        }

                        resolveFilterAndEffect(clipA.id, frame.tUs)
                        renderer.draw(drawSpec)
                    } catch (_: Exception) {
                    } finally {
                        if (needsScissor) {
                            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                        }
                    }

                    lastTransitionIncomingSlot = if (isOutgoingOnSlotB) 0 else 1
                    lastTransitionIncomingClipIndex = frame.clipBIndex

                    renderOverlays(frame.tUs)

            val isTransition = frame.isTransition && frame.clipBIndex >= 0
            val hasValidFrameNear = if (isTransition) {
                (frameSourceA?.hasValidFrameNear(frame.clipASourceUs) == true) &&
                        (frameSourceB?.hasValidFrameNear(frame.clipBSourceUs) == true)
            } else {
                val activeFs = if (primarySlot == 0) frameSourceA else frameSourceB
                activeFs?.hasValidFrameNear(frame.clipASourceUs) == true
            }
            val isCatchingUp = if (hasValidFrameNear) {
                false
            } else if (isTransition) {
                !isFirstFrameLatched ||
                    (frameSourceA != null && (frameSourceA!!.prewarmStage < 3 || frameSourceA!!.isCatchingUpFromSeek)) ||
                    (frameSourceB != null && (frameSourceB!!.prewarmStage < 3 || frameSourceB!!.isCatchingUpFromSeek))
            } else {
                val activeFs = if (primarySlot == 0) frameSourceA else frameSourceB
                !isFirstFrameLatched || (activeFs != null && (activeFs.prewarmStage < 3 || activeFs.isCatchingUpFromSeek))
            }

            if (isCatchingUp) {
                isWaitingForFrame = true
                glSurfaceView?.requestRender()
                onRenderRequested?.invoke()
            } else {
                isWaitingForFrame = false
            }
                    if (playhead.mode == PlayMode.PLAYING) {
                        onRenderRequested?.invoke()
                        glSurfaceView?.requestRender()
                    }
                    return
                }
            }

            // Case 2: Single clip playback with Ping-Pong Primary & Standby Slots (Hard Cut)
        // Handover from Case 1 Transition exit:
        if (lastTransitionIncomingClipIndex != -1) {
            if (lastTransitionIncomingClipIndex == frame.clipAIndex) {
                primarySlot = lastTransitionIncomingSlot
                if (primarySlot == 0) {
                    frameSourceB?.release()
                    frameSourceB = null
                    currentClipIndexB = -1
                } else {
                    frameSourceA?.release()
                    frameSourceA = null
                    currentClipIndexA = -1
                }
                standbySlotReadyForClipId = null
                standbyPreparedPath = null
                standbyPreparedInUs = -1L
            }
            lastTransitionIncomingClipIndex = -1
        }
        lastRenderedTransitionJunction = -1
        smoothedTransitionP = 0f

        // 0ms Instant Cut Handover at the Boundary:
        if (primarySlot == 0) {
            if ((standbySlotReadyForClipId == clipA.id || (standbyPreparedPath == clipA.path && currentClipIndexB == frame.clipAIndex)) &&
                frameSourceB != null && (frameSourceB?.clip?.id == clipA.id || currentClipIndexB == frame.clipAIndex)) {
                primarySlot = 1
                standbySlotReadyForClipId = null
                standbyPreparedPath = null
                standbyPreparedInUs = -1L
                frameSourceA?.release()
                frameSourceA = null
                currentClipIndexA = -1
            }
        } else {
            // primarySlot == 1: Release frameSourceA if it still held an old clip from before the transition
            if (frameSourceA != null && frameSourceA?.clip?.id != clipA.id && currentClipIndexA < frame.clipAIndex) {
                frameSourceA?.release()
                frameSourceA = null
                currentClipIndexA = -1
            }
            if ((standbySlotReadyForClipId == clipA.id || (standbyPreparedPath == clipA.path && currentClipIndexA == frame.clipAIndex)) &&
                frameSourceA != null && (frameSourceA?.clip?.id == clipA.id || currentClipIndexA == frame.clipAIndex)) {
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
            val clipAChanged = frameSourceA == null || frameSourceA?.clip?.id != clipA.id || currentClipIndexA != frame.clipAIndex

            if (clipAChanged) {
                frameSourceA?.release()
                val legacyClipA = toLegacyClip(clipA)
                frameSourceA = FrameSource(legacyClipA, stA)
                currentClipIndexA = frame.clipAIndex
            } else {
                currentClipIndexA = frame.clipAIndex
            }

            val fsA = frameSourceA
            if (fsA != null) {
                when (fsA.prewarmStage) {
                    0 -> {
                        fsA.prepareExtractorOnly()
                        onRenderRequested?.invoke()
                        glSurfaceView?.requestRender()
                    }
                    1 -> {
                        fsA.startCodecAndSeek(frame.clipASourceUs)
                        onRenderRequested?.invoke()
                        glSurfaceView?.requestRender()
                    }
                    2 -> {
                        fsA.stepPrewarmTo(frame.clipASourceUs)
                        onRenderRequested?.invoke()
                        glSurfaceView?.requestRender()
                    }
                    else -> {
                        try {
                            fsA.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing)
                        } catch (_: Exception) { false }
                    }
                }
            }

            if (hasPendingSurfaceFrameA.getAndSet(false)) {
                try {
                    stA.updateTexImage()
                    stA.getTransformMatrix(rawMatA)
                    isFirstFrameLatched = true
                    isWaitingForFrame = false
                } catch (_: Exception) {}
            }
            val transformA = getClipTransform(clipA.id)
            System.arraycopy(rawMatA, 0, matA, 0, 16)
            calculateClipCrop(clipA.displayWidth, clipA.displayHeight, transformA, cropA)

            // Standby Slot B: 3-Stage Incremental Pre-warm of upcoming clip within 3.0s
            val m = frame.clipAIndex + 1
            if (m in s.project.clips.indices && !isScrubbing) {
                val nextClip = s.project.clips[m]
                val transitionStartUs = msToUs(s.main.startMs[m])
                val timeUntilTransitionUs = transitionStartUs - frame.tUs
                if (timeUntilTransitionUs in -500_000L..3_000_000L) {
                    val nextClipInUs = nextClip.map().inUs
                    val alreadyPrepared = (standbyPreparedPath == nextClip.path && standbyPreparedInUs == nextClipInUs) ||
                            (standbySlotReadyForClipId == nextClip.id && (frameSourceB?.prewarmStage ?: 0) >= 3)
                    if (!alreadyPrepared) {
                        if (frameSourceB == null || frameSourceB?.clip?.id != nextClip.id || currentClipIndexB != m) {
                            frameSourceB?.release()
                            frameSourceB = FrameSource(toLegacyClip(nextClip), stB)
                            currentClipIndexB = m
                        }
                        val fsB = frameSourceB
                        if (fsB != null) {
                            val isNearTransition = timeUntilTransitionUs <= 800_000L || playhead.mode == PlayMode.PAUSED
                            if (isNearTransition) {
                                if (fsB.prewarmStage < 1) fsB.prepareExtractorOnly()
                                if (fsB.prewarmStage < 2) fsB.startCodecAndSeek(nextClipInUs)
                                if (fsB.prewarmStage < 3) fsB.stepPrewarmTo(nextClipInUs)
                                if (fsB.prewarmStage < 3) fsB.ensureDecodedAt(nextClipInUs)
                                if (hasPendingSurfaceFrameB.getAndSet(false) || fsB.prewarmStage >= 3) {
                                    try {
                                        stB.updateTexImage()
                                        stB.getTransformMatrix(rawMatB)
                                    } catch (_: Exception) {}
                                    val nextTransform = getClipTransform(nextClip.id)
                                    System.arraycopy(rawMatB, 0, matB, 0, 16)
                                    applyTransformMatrix(matB, nextTransform, nextClip.displayWidth, nextClip.displayHeight)
                                    calculateClipCrop(nextClip.displayWidth, nextClip.displayHeight, nextTransform, cropB)
                                    standbySlotReadyForClipId = nextClip.id
                                    standbyPreparedPath = nextClip.path
                                    standbyPreparedInUs = nextClipInUs
                                }
                            } else {
                                when (fsB.prewarmStage) {
                                    0 -> {
                                        fsB.prepareExtractorOnly()
                                        onRenderRequested?.invoke()
                                        glSurfaceView?.requestRender()
                                    }
                                    1 -> {
                                        fsB.startCodecAndSeek(nextClipInUs)
                                        onRenderRequested?.invoke()
                                        glSurfaceView?.requestRender()
                                    }
                                    2 -> {
                                        val ready = fsB.stepPrewarmTo(nextClipInUs)
                                        if (ready || hasPendingSurfaceFrameB.getAndSet(false)) {
                                            try {
                                                stB.updateTexImage()
                                                stB.getTransformMatrix(rawMatB)
                                            } catch (_: Exception) {}
                                            val nextTransform = getClipTransform(nextClip.id)
                                            System.arraycopy(rawMatB, 0, matB, 0, 16)
                                            applyTransformMatrix(matB, nextTransform, nextClip.displayWidth, nextClip.displayHeight)
                                            calculateClipCrop(nextClip.displayWidth, nextClip.displayHeight, nextTransform, cropB)
                                            standbySlotReadyForClipId = nextClip.id
                                            standbyPreparedPath = nextClip.path
                                            standbyPreparedInUs = nextClipInUs
                                        } else {
                                            onRenderRequested?.invoke()
                                            glSurfaceView?.requestRender()
                                        }
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

            computeFgNormMatrix(fgNormMatA, transformA, clipA.displayWidth, clipA.displayHeight, this.canvasAspectRatio)
            computeBgNormMatrix(bgNormMat, transformA, clipA.displayWidth, clipA.displayHeight, this.canvasAspectRatio)
            Matrix.multiplyMM(bgMatA, 0, rawMatA, 0, bgNormMat, 0)
            val bgA = getClipBackground(clipA.id)
            var bgModeA = packBackground(bgA, bgParamsA)
            val videoAspectA = computeVideoAspect(clipA.displayWidth, clipA.displayHeight, transformA)
            val aScreen = if (canvasAspectRatio > 0f) canvasAspectRatio else (9f / 16f)
            if (bgModeA == 0 && (kotlin.math.abs(videoAspectA - aScreen) > 0.01f || transformA.scale < 0.999f)) {
                bgModeA = 2
                bgParamsA[0] = 0f
                bgParamsA[1] = 0f
                bgParamsA[2] = 0f
                bgParamsA[3] = if (bgA.blurLevel > 0.01f) bgA.blurLevel.coerceIn(0.01f, 1.0f) else 0.5f
            }
            if (bgModeA != 0 && isForegroundCoveringCanvas(transformA, clipA.displayWidth, clipA.displayHeight)) {
                bgModeA = 0
            }

            val needsScissor = (bgModeA == 0) &&
                computeScissorRect(viewportWidth, viewportHeight, transformA, clipA.displayWidth, clipA.displayHeight, scissorRect)
            if (needsScissor) {
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                GLES30.glScissor(scissorRect[0], scissorRect[1], scissorRect[2], scissorRect[3])
            }
            try {
                val adjustA = getClipColorAdjust(clipA.id)
                packAdjust(adjustA, adjA0, adjA1, adjA2)
                packAdjust(ClipColorAdjust.DEFAULT, adjB0, adjB1, adjB2)

                drawSpec.type = com.flowcut.app.model.TransitionType.NONE
                drawSpec.transitionType = 0
                drawSpec.easedP = 0.0f
                drawSpec.swapTextures = false
                drawSpec.matA = matA
                drawSpec.matB = matB
                drawSpec.cropA = cropA
                drawSpec.cropB = cropB
                drawSpec.opacityA = transformA.opacity
                drawSpec.opacityB = 0.0f
                drawSpec.adjA0 = adjA0
                drawSpec.adjA1 = adjA1
                drawSpec.adjA2 = adjA2
                drawSpec.adjB0 = adjB0
                drawSpec.adjB1 = adjB1
                drawSpec.adjB2 = adjB2
                drawSpec.texelSize = texelSize
                drawSpec.fgNormMatA = fgNormMatA
                drawSpec.fgNormMatB = null
                drawSpec.bgMatA = bgMatA
                drawSpec.bgMatB = null
                drawSpec.bgModeA = bgModeA
                drawSpec.bgModeB = 0
                drawSpec.bgParamsA = bgParamsA
                drawSpec.bgParamsB = null
                drawSpec.aspectRatio = if (canvasAspectRatio > 0f) canvasAspectRatio else if (viewportHeight > 0) viewportWidth.toFloat() / viewportHeight.toFloat() else (9.0f / 16.0f)
                lastActiveVideoTexId = renderer.textureIdA()
                Matrix.multiplyMM(lastActiveVideoMat, 0, rawMatA, 0, fgNormMatA, 0)
                lastActiveCrop[0] = cropA[0]
                lastActiveCrop[1] = cropA[1]

                resolveFilterAndEffect(clipA.id, frame.tUs)
                renderer.draw(drawSpec)
            } catch (_: Exception) {
            } finally {
                if (needsScissor) {
                    GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                }
            }
            renderOverlays(frame.tUs)

            val isTransition = frame.isTransition && frame.clipBIndex >= 0
            val hasValidFrameNear = if (isTransition) {
                (frameSourceA?.hasValidFrameNear(frame.clipASourceUs) == true) &&
                        (frameSourceB?.hasValidFrameNear(frame.clipBSourceUs) == true)
            } else {
                val activeFs = if (primarySlot == 0) frameSourceA else frameSourceB
                activeFs?.hasValidFrameNear(frame.clipASourceUs) == true
            }
            val isCatchingUp = if (hasValidFrameNear) {
                false
            } else if (isTransition) {
                !isFirstFrameLatched ||
                    (frameSourceA != null && (frameSourceA!!.prewarmStage < 3 || frameSourceA!!.isCatchingUpFromSeek)) ||
                    (frameSourceB != null && (frameSourceB!!.prewarmStage < 3 || frameSourceB!!.isCatchingUpFromSeek))
            } else {
                val activeFs = if (primarySlot == 0) frameSourceA else frameSourceB
                !isFirstFrameLatched || (activeFs != null && (activeFs.prewarmStage < 3 || activeFs.isCatchingUpFromSeek))
            }

            if (isCatchingUp) {
                isWaitingForFrame = true
                glSurfaceView?.requestRender()
                onRenderRequested?.invoke()
            } else {
                isWaitingForFrame = false
            }
        } else {
            // --- Slot B is Primary (stB / texBId) ---
            val clipBChanged = frameSourceB == null || frameSourceB?.clip?.id != clipA.id || currentClipIndexB != frame.clipAIndex

            if (clipBChanged) {
                frameSourceB?.release()
                val legacyClipA = toLegacyClip(clipA)
                frameSourceB = FrameSource(legacyClipA, stB)
                currentClipIndexB = frame.clipAIndex
            } else {
                currentClipIndexB = frame.clipAIndex
            }

            val fsB = frameSourceB
            if (fsB != null) {
                when (fsB.prewarmStage) {
                    0 -> {
                        fsB.prepareExtractorOnly()
                        onRenderRequested?.invoke()
                        glSurfaceView?.requestRender()
                    }
                    1 -> {
                        fsB.startCodecAndSeek(frame.clipASourceUs)
                        onRenderRequested?.invoke()
                        glSurfaceView?.requestRender()
                    }
                    2 -> {
                        fsB.stepPrewarmTo(frame.clipASourceUs)
                        onRenderRequested?.invoke()
                        glSurfaceView?.requestRender()
                    }
                    else -> {
                        try {
                            fsB.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing)
                        } catch (_: Exception) { false }
                    }
                }
            }

            if (hasPendingSurfaceFrameB.getAndSet(false)) {
                try {
                    stB.updateTexImage()
                    stB.getTransformMatrix(rawMatB)
                    isFirstFrameLatched = true
                    isWaitingForFrame = false
                } catch (_: Exception) {}
            }
            val transformB = getClipTransform(clipA.id)
            System.arraycopy(rawMatB, 0, matB, 0, 16)
            calculateClipCrop(clipA.displayWidth, clipA.displayHeight, transformB, cropB)

            // Standby Slot A: Pre-warm upcoming clip m = frame.clipAIndex + 1 within 3.0s (true symmetrical ping-pong)
            val m = frame.clipAIndex + 1
            if (m in s.project.clips.indices && !isScrubbing) {
                val nextClip = s.project.clips[m]
                val transitionStartUs = msToUs(s.main.startMs[m])
                val timeUntilTransitionUs = transitionStartUs - frame.tUs
                if (timeUntilTransitionUs in -500_000L..3_000_000L) {
                    val nextClipInUs = nextClip.map().inUs
                    val alreadyPrepared = (standbyPreparedPath == nextClip.path && standbyPreparedInUs == nextClipInUs) ||
                            (standbySlotReadyForClipId == nextClip.id && (frameSourceA?.prewarmStage ?: 0) >= 3)
                    if (!alreadyPrepared) {
                        if (frameSourceA == null || frameSourceA?.clip?.id != nextClip.id || currentClipIndexA != m) {
                            frameSourceA?.release()
                            frameSourceA = FrameSource(toLegacyClip(nextClip), stA)
                            currentClipIndexA = m
                        }
                        val fsA = frameSourceA
                        if (fsA != null) {
                            val isNearTransition = timeUntilTransitionUs <= 800_000L || playhead.mode == PlayMode.PAUSED
                            if (isNearTransition) {
                                if (fsA.prewarmStage < 1) fsA.prepareExtractorOnly()
                                if (fsA.prewarmStage < 2) fsA.startCodecAndSeek(nextClipInUs)
                                if (fsA.prewarmStage < 3) fsA.stepPrewarmTo(nextClipInUs)
                                if (fsA.prewarmStage < 3) fsA.ensureDecodedAt(nextClipInUs)
                                if (hasPendingSurfaceFrameA.getAndSet(false) || fsA.prewarmStage >= 3) {
                                    try {
                                        stA.updateTexImage()
                                        stA.getTransformMatrix(rawMatA)
                                    } catch (_: Exception) {}
                                    val nextTransform = getClipTransform(nextClip.id)
                                    System.arraycopy(rawMatA, 0, matA, 0, 16)
                                    applyTransformMatrix(matA, nextTransform, nextClip.displayWidth, nextClip.displayHeight)
                                    calculateClipCrop(nextClip.displayWidth, nextClip.displayHeight, nextTransform, cropA)
                                    standbySlotReadyForClipId = nextClip.id
                                    standbyPreparedPath = nextClip.path
                                    standbyPreparedInUs = nextClipInUs
                                }
                            } else {
                                when (fsA.prewarmStage) {
                                    0 -> {
                                        fsA.prepareExtractorOnly()
                                        onRenderRequested?.invoke()
                                        glSurfaceView?.requestRender()
                                    }
                                    1 -> {
                                        fsA.startCodecAndSeek(nextClipInUs)
                                        onRenderRequested?.invoke()
                                        glSurfaceView?.requestRender()
                                    }
                                    2 -> {
                                        val ready = fsA.stepPrewarmTo(nextClipInUs)
                                        if (ready || hasPendingSurfaceFrameA.getAndSet(false)) {
                                            try {
                                                stA.updateTexImage()
                                                stA.getTransformMatrix(rawMatA)
                                            } catch (_: Exception) {}
                                            val nextTransform = getClipTransform(nextClip.id)
                                            System.arraycopy(rawMatA, 0, matA, 0, 16)
                                            applyTransformMatrix(matA, nextTransform, nextClip.displayWidth, nextClip.displayHeight)
                                            calculateClipCrop(nextClip.displayWidth, nextClip.displayHeight, nextTransform, cropA)
                                            standbySlotReadyForClipId = nextClip.id
                                            standbyPreparedPath = nextClip.path
                                            standbyPreparedInUs = nextClipInUs
                                        } else {
                                            onRenderRequested?.invoke()
                                            glSurfaceView?.requestRender()
                                        }
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

            computeFgNormMatrix(fgNormMatB, transformB, clipA.displayWidth, clipA.displayHeight, this.canvasAspectRatio)
            computeBgNormMatrix(bgNormMat, transformB, clipA.displayWidth, clipA.displayHeight, this.canvasAspectRatio)
            Matrix.multiplyMM(bgMatB, 0, rawMatB, 0, bgNormMat, 0)
            val bgB = getClipBackground(clipA.id)
            var bgModeB = packBackground(bgB, bgParamsB)
            val videoAspectB = computeVideoAspect(clipA.displayWidth, clipA.displayHeight, transformB)
            val aScreen = if (canvasAspectRatio > 0f) canvasAspectRatio else (9f / 16f)
            if (bgModeB == 0 && (kotlin.math.abs(videoAspectB - aScreen) > 0.01f || transformB.scale < 0.999f)) {
                bgModeB = 2
                bgParamsB[0] = 0f
                bgParamsB[1] = 0f
                bgParamsB[2] = 0f
                bgParamsB[3] = if (bgB.blurLevel > 0.01f) bgB.blurLevel.coerceIn(0.01f, 1.0f) else 0.5f
            }
            if (bgModeB != 0 && isForegroundCoveringCanvas(transformB, clipA.displayWidth, clipA.displayHeight)) {
                bgModeB = 0
            }

            val needsScissor = (bgModeB == 0) &&
                computeScissorRect(viewportWidth, viewportHeight, transformB, clipA.displayWidth, clipA.displayHeight, scissorRect)
            if (needsScissor) {
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                GLES30.glScissor(scissorRect[0], scissorRect[1], scissorRect[2], scissorRect[3])
            }
            try {
                packAdjust(ClipColorAdjust.DEFAULT, adjA0, adjA1, adjA2)
                val adjustB = getClipColorAdjust(clipA.id)
                packAdjust(adjustB, adjB0, adjB1, adjB2)

                drawSpec.type = com.flowcut.app.model.TransitionType.NONE
                drawSpec.transitionType = 0
                drawSpec.easedP = 0.0f
                drawSpec.swapTextures = false
                drawSpec.matA = matA
                drawSpec.matB = matB
                drawSpec.cropA = cropA
                drawSpec.cropB = cropB
                drawSpec.opacityA = 0.0f
                drawSpec.opacityB = transformB.opacity
                drawSpec.adjA0 = adjA0
                drawSpec.adjA1 = adjA1
                drawSpec.adjA2 = adjA2
                drawSpec.adjB0 = adjB0
                drawSpec.adjB1 = adjB1
                drawSpec.adjB2 = adjB2
                drawSpec.texelSize = texelSize
                drawSpec.fgNormMatA = null
                drawSpec.fgNormMatB = fgNormMatB
                drawSpec.bgMatA = null
                drawSpec.bgMatB = bgMatB
                drawSpec.bgModeA = 0
                drawSpec.bgModeB = bgModeB
                drawSpec.bgParamsA = null
                drawSpec.bgParamsB = bgParamsB
                drawSpec.aspectRatio = if (canvasAspectRatio > 0f) canvasAspectRatio else if (viewportHeight > 0) viewportWidth.toFloat() / viewportHeight.toFloat() else (9.0f / 16.0f)
                lastActiveVideoTexId = renderer.textureIdB()
                Matrix.multiplyMM(lastActiveVideoMat, 0, rawMatB, 0, fgNormMatB, 0)
                lastActiveCrop[0] = cropB[0]
                lastActiveCrop[1] = cropB[1]

                resolveFilterAndEffect(clipA.id, frame.tUs)
                renderer.draw(drawSpec)
            } catch (_: Exception) {
            } finally {
                if (needsScissor) {
                    GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                }
            }
            renderOverlays(frame.tUs)

            val isTransition = frame.isTransition && frame.clipBIndex >= 0
            val hasValidFrameNear = if (isTransition) {
                (frameSourceA?.hasValidFrameNear(frame.clipASourceUs) == true) &&
                        (frameSourceB?.hasValidFrameNear(frame.clipBSourceUs) == true)
            } else {
                val activeFs = if (primarySlot == 0) frameSourceA else frameSourceB
                activeFs?.hasValidFrameNear(frame.clipASourceUs) == true
            }
            val isCatchingUp = if (hasValidFrameNear) {
                false
            } else if (isTransition) {
                !isFirstFrameLatched ||
                    (frameSourceA != null && (frameSourceA!!.prewarmStage < 3 || frameSourceA!!.isCatchingUpFromSeek)) ||
                    (frameSourceB != null && (frameSourceB!!.prewarmStage < 3 || frameSourceB!!.isCatchingUpFromSeek))
            } else {
                val activeFs = if (primarySlot == 0) frameSourceA else frameSourceB
                !isFirstFrameLatched || (activeFs != null && (activeFs.prewarmStage < 3 || activeFs.isCatchingUpFromSeek))
            }

            if (isCatchingUp) {
                isWaitingForFrame = true
                glSurfaceView?.requestRender()
                onRenderRequested?.invoke()
            } else {
                isWaitingForFrame = false
            }
        }
        } finally {
            val finalWaiting = isWaitingForFrame
            if (wasWaitingInitial && !finalWaiting) {
                onRenderRequested?.invoke()
            } else if (finalWaiting) {
                onRenderRequested?.invoke()
                glSurfaceView?.requestRender()
            }

            val durationUs = (System.nanoTime() - startNs) / 1000L
            renderedFrames++
            if (durationUs > 16_666L) {
                lateFrames++
            }
            frameTimeSumUs += durationUs
            if (durationUs > frameTimeMaxUs) {
                frameTimeMaxUs = durationUs
            }
            frameCount++
            if (frameCount >= 120) {
                val avgMs = frameTimeSumUs.toDouble() / (frameCount * 1000.0)
                val maxMs = frameTimeMaxUs.toDouble() / 1000.0
                try {
                    android.util.Log.d(
                        "FlowCutPerf",
                        "Render frame time (last 120): avg=${String.format(java.util.Locale.US, "%.2f", avgMs)}ms, max=${String.format(java.util.Locale.US, "%.2f", maxMs)}ms"
                    )
                } catch (_: Throwable) {}
                frameTimeSumUs = 0L
                frameTimeMaxUs = 0L
                frameCount = 0
            }
        }
    }

    private fun resolveFilterAndEffect(clipId: String?, tUs: Long) {
        val st = getClipFilterState(clipId)
        var filterId = st.filterPresetId
        var filterInt = st.filterIntensity
        var effectId = st.effectPresetId
        var effectInt = st.effectIntensity

        val plan = currentLayerPlan
        if (plan.segments.isNotEmpty()) {
            val found = plan.resolveAt(tUs, activeLayerSet)
            if (found && activeLayerSet.filterCount > 0) {
                for (f in 0 until activeLayerSet.filterCount) {
                    val idx = activeLayerSet.activeFilterIndices[f]
                    val item = plan.items.getOrNull(idx) ?: continue
                    if (item.type == LayerType.FILTER && item.filterPresetId > 0) {
                        filterId = item.filterPresetId
                        filterInt = item.filterIntensity
                    }
                    if (item.type == LayerType.EFFECT && item.effectPresetId > 0) {
                        effectId = item.effectPresetId
                        effectInt = item.effectIntensity
                    }
                }
            }
        }

        drawSpec.filterPresetId = filterId
        drawSpec.filterIntensity = filterInt
        drawSpec.effectPresetId = effectId
        drawSpec.effectIntensity = effectInt
        drawSpec.timeSec = (tUs.toDouble() / 1_000_000.0).toFloat()
    }

    private fun renderOverlays(tUs: Long) {
        val plan = currentLayerPlan
        if (plan.segments.isEmpty()) return
        val found = plan.resolveAt(tUs, activeLayerSet)
        if (!found || activeLayerSet.visualCount == 0) return

        val selId = selectedOverlayIdForPreview
        var selectedItemIndex = -1

        // Pass 1: Render unselected items in normal z-order
        for (i in 0 until activeLayerSet.visualCount) {
            val itemIndex = activeLayerSet.activeVisualIndices[i]
            val item = plan.items.getOrNull(itemIndex) ?: continue
            if (selId != null && item.id == selId) {
                selectedItemIndex = itemIndex
                continue
            }
            val effTransform = animEvaluator.evaluate(item, tUs)
            if (effTransform.opacity <= 0.001f) continue

            if (item.type == LayerType.MOSAIC) {
                val vTex = if (lastActiveVideoTexId > 0) lastActiveVideoTexId else renderer.textureIdA()
                renderer.drawMosaicRegion(
                    videoTexId = vTex,
                    videoMat = lastActiveVideoMat,
                    crop = lastActiveCrop,
                    transform = effTransform,
                    mosaicStyle = item.mosaicStyle,
                    mosaicIntensity = item.mosaicIntensity,
                    mosaicFeather = item.mosaicFeather,
                    opacity = effTransform.opacity,
                    displayWidth = viewportWidth,
                    displayHeight = viewportHeight
                )
                continue
            }

            val cached = if (item.type == LayerType.VIDEO) {
                overlayTextureCache.getOrUpdateVideoTexture(item, tUs, isSynchronous = false)
            } else {
                overlayTextureCache.getOrBakeTexture(item)
            } ?: continue
            if (cached.texId > 0) {
                renderer.drawOverlayQuad(
                    texId = cached.texId,
                    texWidth = cached.width,
                    texHeight = cached.height,
                    transform = effTransform,
                    opacity = effTransform.opacity,
                    displayWidth = viewportWidth,
                    displayHeight = viewportHeight,
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

        // Pass 2: Render selected visual overlay item last (topmost) without mutating stored zIndex (Rule R10)
        if (selectedItemIndex >= 0) {
            val item = plan.items.getOrNull(selectedItemIndex)
            if (item != null) {
                val effTransform = animEvaluator.evaluate(item, tUs)
                if (effTransform.opacity > 0.001f) {
                    if (item.type == LayerType.MOSAIC) {
                        val vTex = if (lastActiveVideoTexId > 0) lastActiveVideoTexId else renderer.textureIdA()
                        renderer.drawMosaicRegion(
                            videoTexId = vTex,
                            videoMat = lastActiveVideoMat,
                            crop = lastActiveCrop,
                            transform = effTransform,
                            mosaicStyle = item.mosaicStyle,
                            mosaicIntensity = item.mosaicIntensity,
                            mosaicFeather = item.mosaicFeather,
                            opacity = effTransform.opacity,
                            displayWidth = viewportWidth,
                            displayHeight = viewportHeight
                        )
                        return
                    }
                    val cached = if (item.type == LayerType.VIDEO) {
                        overlayTextureCache.getOrUpdateVideoTexture(item, tUs, isSynchronous = false)
                    } else {
                        overlayTextureCache.getOrBakeTexture(item)
                    }
                    if (cached != null && cached.texId > 0) {
                        renderer.drawOverlayQuad(
                            texId = cached.texId,
                            texWidth = cached.width,
                            texHeight = cached.height,
                            transform = effTransform,
                            opacity = effTransform.opacity,
                            displayWidth = viewportWidth,
                            displayHeight = viewportHeight,
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
            }
        }
    }

    private fun releaseDecoders() {
        isFirstFrameLatched = false
        frameSourceA?.release()
        frameSourceA = null
        currentClipIndexA = -1

        frameSourceB?.release()
        frameSourceB = null
        currentClipIndexB = -1

        primarySlot = 0
        standbySlotReadyForClipId = null
        lastTransitionIncomingSlot = -1
        lastTransitionIncomingClipIndex = -1
        lastRenderedTransitionJunction = -1
        smoothedTransitionP = 0f
        Matrix.setIdentityM(rawMatA, 0)
        Matrix.setIdentityM(rawMatB, 0)
    }

    fun suspendForExport() {
        isSuspendedForExport = true
        val gl = glSurfaceView
        if (gl != null) {
            gl.queueEvent {
                releaseDecoders()
            }
        } else {
            releaseDecoders()
        }
    }

    fun resumeFromExport() {
        isSuspendedForExport = false
        onRenderRequested?.invoke()
        glSurfaceView?.requestRender()
    }

    fun release() {
        if (activeInstance === this) {
            activeInstance = null
        }
        trimPreviewTimeUs = -1L
        stopPrefetch()
        releaseDecoders()
        try {
            stCallbackThread.quitSafely()
        } catch (_: Throwable) {}
        surfaceTextureA?.setOnFrameAvailableListener(null)
        surfaceTextureA?.release()
        surfaceTextureA = null
        surfaceTextureB?.setOnFrameAvailableListener(null)
        surfaceTextureB?.release()
        surfaceTextureB = null
        hasPendingSurfaceFrameA.set(false)
        hasPendingSurfaceFrameB.set(false)
        try {
            overlayTextureCache.release()
        } catch (_: Throwable) {}
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
        @Volatile
        var activeInstance: PreviewRenderer? = null

        fun releaseActiveDecoders() {
            activeInstance?.suspendForExport()
        }

        fun resumeActiveDecoders() {
            activeInstance?.resumeFromExport()
        }

        fun activeLateFrames(): Long = activeInstance?.lateFrames ?: 0L
        fun activeRenderedFrames(): Long = activeInstance?.renderedFrames ?: 0L

        @Volatile
        private var _canvasAspectRatio: Float = 9f / 16f
        val canvasAspectRatio: Float get() = _canvasAspectRatio

        @Volatile
        var currentAspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_9_16

        fun resolveEffectiveAspect(mode: AspectRatioMode, firstClip: Any? = null): Float {
            if (mode == AspectRatioMode.ORIGINAL) {
                if (firstClip != null) {
                    val (w, h) = when (firstClip) {
                        is com.flowcut.app.model.Clip -> {
                            val rot = firstClip.rotation
                            val w = if (rot == 90 || rot == 270) firstClip.height else firstClip.width
                            val h = if (rot == 90 || rot == 270) firstClip.width else firstClip.height
                            w to h
                        }
                        is com.flowcut.app.core.model.Clip -> {
                            val rot = firstClip.rotation
                            val w = if (rot == 90 || rot == 270) firstClip.height else firstClip.width
                            val h = if (rot == 90 || rot == 270) firstClip.width else firstClip.height
                            w to h
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

        @Volatile
        var currentLayerPlan: CompiledLayerPlan = CompiledLayerPlan.EMPTY

        fun setLayerPlan(plan: CompiledLayerPlan) {
            currentLayerPlan = plan
        }

        private val overlayTextures = ConcurrentHashMap<String, Int>()

        fun setOverlayTextureId(itemId: String, texId: Int) {
            if (texId > 0) {
                overlayTextures[itemId] = texId
            } else {
                overlayTextures.remove(itemId)
            }
        }

        fun getOverlayTextureId(itemId: String): Int {
            return overlayTextures[itemId] ?: 0
        }

        fun clearOverlayTextures() {
            overlayTextures.clear()
        }

        val clipTransforms = ConcurrentHashMap<String, ClipTransform>()
        val clipColorAdjusts = ConcurrentHashMap<String, ClipColorAdjust>()
        val clipBackgrounds = ConcurrentHashMap<String, ClipBackground>()

        fun getClipOpacity(clipId: String?): Float {
            if (clipId == null) return 1.0f
            return getClipTransform(clipId).opacity
        }

        fun setClipOpacity(clipId: String, opacity: Float) {
            val t = getClipTransform(clipId)
            setClipTransform(clipId, t.copy(opacity = opacity.coerceIn(0f, 1f)))
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

        val clipFilterStates = ConcurrentHashMap<String, ClipFilterState>()

        fun getClipFilterState(clipId: String?): ClipFilterState {
            if (clipId == null) return ClipFilterState.DEFAULT
            return clipFilterStates[clipId] ?: ClipFilterState.DEFAULT
        }

        fun setClipFilter(clipId: String, presetId: Int, intensity: Float) {
            val existing = getClipFilterState(clipId)
            clipFilterStates[clipId] = existing.copy(filterPresetId = presetId, filterIntensity = intensity)
        }

        fun setClipEffect(clipId: String, presetId: Int, intensity: Float) {
            val existing = getClipFilterState(clipId)
            clipFilterStates[clipId] = existing.copy(effectPresetId = presetId, effectIntensity = intensity)
        }

        fun syncFilterStates(states: Map<String, ClipFilterState>) {
            clipFilterStates.clear()
            clipFilterStates.putAll(states)
        }

        val junctionTransitionTypes = ConcurrentHashMap<Int, Int>()

        fun resetAll() {
            clipTransforms.clear()
            clipColorAdjusts.clear()
            clipBackgrounds.clear()
            clipFilterStates.clear()
            junctionTransitionTypes.clear()
            clearOverlayTextures()
            setLayerPlan(CompiledLayerPlan.EMPTY)
            setAspectRatioMode(AspectRatioMode.RATIO_9_16)
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

        fun packBackground(bg: ClipBackground, outParams: FloatArray): Int {
            if (bg.gradientIndex in 0..5) {
                outParams[0] = bg.gradientIndex.toFloat()
                outParams[1] = 0f
                outParams[2] = 0f
                outParams[3] = 0f
                return 3
            }
            when (bg.type) {
                BackgroundType.NONE -> {
                    outParams[0] = 0f
                    outParams[1] = 0f
                    outParams[2] = 0f
                    outParams[3] = 0f
                    return 0
                }
                BackgroundType.COLOR -> {
                    val r = ((bg.colorArgb ushr 16) and 0xFF) / 255f
                    val g = ((bg.colorArgb ushr 8) and 0xFF) / 255f
                    val b = (bg.colorArgb and 0xFF) / 255f
                    val a = ((bg.colorArgb ushr 24) and 0xFF) / 255f
                    outParams[0] = r
                    outParams[1] = g
                    outParams[2] = b
                    outParams[3] = a
                    return 1
                }
                BackgroundType.BLUR -> {
                    outParams[0] = 0f
                    outParams[1] = 0f
                    outParams[2] = 0f
                    outParams[3] = bg.blurLevel.coerceIn(0.01f, 1.0f)
                    return 2
                }
            }
        }

        /**
         * Checks whether the foreground clip completely covers the 9:16 viewport with zero exposed background bars:
         * Specifically when rotationDeg % 180f == 0f (or 90°/270° aligned) and the transformed clip's
         * normalized half-extents (hw, hh) satisfy panX - hw <= -0.5f && panX + hw >= 0.5f && panY - hh <= -0.5f && panY + hh >= 0.5f.
         */
        fun computeVideoAspect(
            displayWidth: Int = 1080,
            displayHeight: Int = 1920,
            transform: ClipTransform = ClipTransform.DEFAULT,
            cropLeft: Float = 0.0f,
            cropTop: Float = 0.0f,
            cropRight: Float = 1.0f,
            cropBottom: Float = 1.0f
        ): Float {
            val rawW = if (displayWidth > 0) displayWidth.toFloat() else 1080f
            val rawH = if (displayHeight > 0) displayHeight.toFloat() else 1920f
            val cropW = (cropRight - cropLeft).coerceIn(0.05f, 1.0f)
            val cropH = (cropBottom - cropTop).coerceIn(0.05f, 1.0f)
            return ((rawW * cropW) / (rawH * cropH)).coerceIn(0.01f, 100f)
        }

        fun isForegroundCoveringCanvas(
            transform: ClipTransform,
            displayWidth: Int = 1080,
            displayHeight: Int = 1920,
            canvasAspect: Float = canvasAspectRatio
        ): Boolean {
            val normRot = ((transform.rotationDeg % 360f) + 360f) % 360f
            val nearestCardinal = (Math.round(normRot / 90.0).toInt() * 90) % 360
            if (kotlin.math.abs(normRot - nearestCardinal) > 0.05f) {
                return false
            }
            val aScreen = if (canvasAspect > 0f) canvasAspect else (9.0f / 16.0f)
            val videoAspect = computeVideoAspect(displayWidth, displayHeight, transform)
            val isRot90or270 = (nearestCardinal == 90 || nearestCardinal == 270)
            val effAspect = if (isRot90or270) (1.0f / videoAspect) else videoAspect

            val baseHw = if (effAspect <= aScreen) 0.5f * (effAspect / aScreen) else 0.5f
            val baseHh = if (effAspect <= aScreen) 0.5f else 0.5f * (aScreen / effAspect)
            val scale = transform.scale.coerceIn(0.1f, 10.0f)
            val hw = baseHw * scale
            val hh = baseHh * scale

            return (transform.panX - hw <= -0.5f) &&
                   (transform.panX + hw >= 0.5f) &&
                   (transform.panY - hh <= -0.5f) &&
                   (transform.panY + hh >= 0.5f)
        }

        fun computeFgNormMatrix(
            outMat: FloatArray,
            transform: ClipTransform,
            displayWidth: Int = 1080,
            displayHeight: Int = 1920,
            canvasAspect: Float = canvasAspectRatio,
            cropLeft: Float = 0.0f,
            cropTop: Float = 0.0f,
            cropRight: Float = 1.0f,
            cropBottom: Float = 1.0f
        ) {
            Matrix.setIdentityM(outMat, 0)
            val aScreen = if (canvasAspect > 0f) canvasAspect else (9.0f / 16.0f)
            val videoAspect = computeVideoAspect(displayWidth, displayHeight, transform, cropLeft, cropTop, cropRight, cropBottom)

            val fitScaleX: Float
            val fitScaleY: Float
            if (videoAspect < aScreen) {
                fitScaleX = videoAspect / aScreen
                fitScaleY = 1.0f
            } else {
                fitScaleX = 1.0f
                fitScaleY = aScreen / videoAspect
            }

            Matrix.translateM(outMat, 0, 0.5f, 0.5f, 0f)
            Matrix.scaleM(outMat, 0, 1.0f / fitScaleX, 1.0f / fitScaleY, 1f)
            val sInv = 1.0f / transform.scale.coerceIn(0.1f, 10.0f)
            Matrix.scaleM(outMat, 0, sInv, sInv, 1f)
            Matrix.scaleM(outMat, 0, 1.0f / aScreen, 1.0f, 1f)
            if (transform.rotationDeg != 0f) {
                Matrix.rotateM(outMat, 0, -transform.rotationDeg, 0f, 0f, 1f)
            }
            Matrix.scaleM(outMat, 0, aScreen, 1.0f, 1f)
            if (transform.isFlippedH) {
                Matrix.scaleM(outMat, 0, -1f, 1f, 1f)
            }
            Matrix.translateM(outMat, 0, -0.5f - transform.panX, -0.5f - transform.panY, 0f)
        }

        fun computeBgNormMatrix(
            outMat: FloatArray,
            transform: ClipTransform,
            displayWidth: Int = 1080,
            displayHeight: Int = 1920,
            canvasAspect: Float = canvasAspectRatio
        ) {
            Matrix.setIdentityM(outMat, 0)
            val aScreen = if (canvasAspect > 0f) canvasAspect else (9.0f / 16.0f)
            val rot = (((Math.round(transform.rotationDeg / 90.0).toInt() * 90) % 360 + 360) % 360)
            val videoAspect = computeVideoAspect(displayWidth, displayHeight, transform)

            val scaleX: Float
            val scaleY: Float
            if (videoAspect < aScreen) {
                scaleX = 1.0f
                scaleY = videoAspect / aScreen
            } else {
                scaleX = aScreen / videoAspect
                scaleY = 1.0f
            }

            Matrix.translateM(outMat, 0, 0.5f, 0.5f, 0f)
            if (rot != 0) {
                Matrix.rotateM(outMat, 0, -rot.toFloat(), 0f, 0f, 1f)
            }
            if (transform.isFlippedH) {
                Matrix.scaleM(outMat, 0, -1f, 1f, 1f)
            }
            Matrix.scaleM(outMat, 0, scaleX, scaleY, 1f)
            Matrix.translateM(outMat, 0, -0.5f, -0.5f, 0f)
        }

        fun packAdjust(adjust: ClipColorAdjust, out0: FloatArray, out1: FloatArray, out2: FloatArray) {
            out0[0] = adjust.brightness
            out0[1] = adjust.contrast
            out0[2] = adjust.saturation
            out0[3] = adjust.exposure
            out1[0] = adjust.warmth
            out1[1] = adjust.tint
            out1[2] = adjust.highlights
            out1[3] = adjust.shadows
            out2[0] = adjust.sharpness
            out2[1] = adjust.vignette
            out2[2] = 0f
            out2[3] = 0f
        }

        fun calculateClipCrop(
            displayWidth: Int,
            displayHeight: Int,
            transform: ClipTransform,
            out: FloatArray,
            cropLeft: Float = 0.0f,
            cropTop: Float = 0.0f,
            cropRight: Float = 1.0f,
            cropBottom: Float = 1.0f
        ) {
            val cropW = (cropRight - cropLeft).coerceIn(0.01f, 1.0f)
            val cropH = (cropBottom - cropTop).coerceIn(0.01f, 1.0f)
            out[0] = cropW
            out[1] = cropH
        }

        fun calculateCropInto(displayWidth: Int, displayHeight: Int, out: FloatArray) {
            val aspect = displayWidth.toFloat() / displayHeight.toFloat()
            val target = if (canvasAspectRatio > 0f) canvasAspectRatio else (9f / 16f)
            if (aspect > target) {
                out[0] = target / aspect
                out[1] = 1.0f
            } else {
                out[0] = 1.0f
                out[1] = aspect / target
            }
        }

        fun applyTransformMatrix(
            mat: FloatArray,
            transform: ClipTransform,
            displayWidth: Int = 1080,
            displayHeight: Int = 1920,
            canvasAspect: Float = canvasAspectRatio,
            cropLeft: Float = 0.0f,
            cropTop: Float = 0.0f,
            cropRight: Float = 1.0f,
            cropBottom: Float = 1.0f
        ) {
            computeFgNormMatrix(mat, transform, displayWidth, displayHeight, canvasAspect, cropLeft, cropTop, cropRight, cropBottom)
        }

        fun computeScissorRect(
            width: Int,
            height: Int,
            transform: ClipTransform,
            displayWidth: Int = 1080,
            displayHeight: Int = 1920,
            out: IntArray
        ): Boolean {
            val aScreen = if (height > 0) width.toFloat() / height.toFloat() else if (canvasAspectRatio > 0f) canvasAspectRatio else (9.0f / 16.0f)
            val videoAspect = computeVideoAspect(displayWidth, displayHeight, transform)
            val baseHw = if (videoAspect <= aScreen) 0.5f * (videoAspect / aScreen) else 0.5f
            val baseHh = if (videoAspect <= aScreen) 0.5f else 0.5f * (aScreen / videoAspect)

            val scale = transform.scale.coerceIn(0.25f, 5.0f)
            val hw = baseHw * scale
            val hh = baseHh * scale

            val rad = Math.toRadians(transform.rotationDeg.toDouble())
            val cos = Math.abs(Math.cos(rad)).toFloat()
            val sin = Math.abs(Math.sin(rad)).toFloat()

            val rx = hw * cos + (hh * sin / aScreen)
            val ry = (hw * aScreen * sin) + (hh * cos)

            val leftNorm = 0.5f + transform.panX - rx
            val rightNorm = 0.5f + transform.panX + rx
            val bottomNorm = 0.5f + transform.panY - ry
            val topNorm = 0.5f + transform.panY + ry

            if (leftNorm <= 0f && rightNorm >= 1f && bottomNorm <= 0f && topNorm >= 1f) {
                out[0] = 0
                out[1] = 0
                out[2] = width
                out[3] = height
                return false
            }

            val xMin = (leftNorm * width).toInt().coerceIn(0, width)
            val xMax = (rightNorm * width).toInt().coerceIn(0, width)
            val yMin = (bottomNorm * height).toInt().coerceIn(0, height)
            val yMax = (topNorm * height).toInt().coerceIn(0, height)

            val scissorW = (xMax - xMin).coerceAtLeast(0)
            val scissorH = (yMax - yMin).coerceAtLeast(0)

            out[0] = xMin
            out[1] = yMin
            out[2] = scissorW
            out[3] = scissorH
            return true
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
