package com.flowcut.app.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.flowcut.app.core.feature.FeatureRegistry
import com.flowcut.app.core.feature.FrameCtx
import com.flowcut.app.core.feature.LayerRenderer
import com.flowcut.app.core.feature.PassCtx
import com.flowcut.app.core.feature.Prefetcher
import com.flowcut.app.core.feature.RenderContributor
import com.flowcut.app.core.feature.RenderPass
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
import com.flowcut.app.engine.perf.PreviewEventLog
import com.flowcut.app.engine.perf.PreviewEventType
import com.flowcut.app.engine.perf.PreviewWatchdog
import com.flowcut.app.engine.perf.PreviewWatchdogStateProvider
import com.flowcut.app.engine.perf.ResourceTracker
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
import java.util.concurrent.atomic.AtomicInteger

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
                if (nextClip.id != currentClip.id) {
                    val isContinuousSameFileSplit = (nextClip.path == currentClip.path) &&
                            kotlin.math.abs(nextClip.map().inUs - currentClip.map().outUs) <= 50_000L
                    if (!isContinuousSameFileSplit) {
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
    val vignette: Float = 0f,    // -1f..1f (UI: -50 to +50)
    val clarity: Float = 0f,     // -1f..1f (UI: -50 to +50)
    val sharpPlus: Float = 0f    //  0f..1f (UI:  0 to 100)
) {
    val isIdentity: Boolean get() =
        brightness == 0f && contrast == 0f && saturation == 0f &&
        exposure == 0f && warmth == 0f && tint == 0f &&
        highlights == 0f && shadows == 0f && sharpness == 0f && vignette == 0f &&
        clarity == 0f && sharpPlus == 0f

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
    val onVideoDecoderPrefetch: ((clipIndex: Int, sourceUs: Long) -> Unit)? = null,
    val featureRegistry: FeatureRegistry? = null
) : GLSurfaceView.Renderer, OverlayHost, PreviewWatchdogStateProvider {

    private var surfaceCreatedCount: Int = 0
    private var lastNewFrameMsA: Long = 0L
    private var lastNewFrameMsB: Long = 0L

    private fun onNewFrameLatchedSlotA(ts: Long) {
        val now = System.currentTimeMillis()
        if (lastNewFrameMsA > 0L) {
            val delta = now - lastNewFrameMsA
            if (delta > 300L) {
                PreviewEventLog.log(PreviewEventType.STALL_FLAG, slot = 0, arg0 = delta, reason = "stall_slot_0")
            }
        }
        lastNewFrameMsA = now
        PreviewEventLog.log(PreviewEventType.NEW_FRAME_LATCHED, slot = 0, arg0 = ts)
    }

    private fun onNewFrameLatchedSlotB(ts: Long) {
        val now = System.currentTimeMillis()
        if (lastNewFrameMsB > 0L) {
            val delta = now - lastNewFrameMsB
            if (delta > 300L) {
                PreviewEventLog.log(PreviewEventType.STALL_FLAG, slot = 1, arg0 = delta, reason = "stall_slot_1")
            }
        }
        lastNewFrameMsB = now
        PreviewEventLog.log(PreviewEventType.NEW_FRAME_LATCHED, slot = 1, arg0 = ts)
    }

    override val playMode: PlayMode get() = playhead.mode
    override val generation: Int get() = playhead.generation
    override val slotAInfo: String get() = "clipId=${frameSourceA?.clip?.id}, stage=${frameSourceA?.prewarmStage}, catchUp=${frameSourceA?.isCatchingUpFromSeek}, lastPts=${frameSourceA?.lastRenderedPtsUs}, curPts=${frameSourceA?.currentPtsUs}"
    override val slotBInfo: String get() = "clipId=${frameSourceB?.clip?.id}, stage=${frameSourceB?.prewarmStage}, catchUp=${frameSourceB?.isCatchingUpFromSeek}, lastPts=${frameSourceB?.lastRenderedPtsUs}, curPts=${frameSourceB?.currentPtsUs}"
    override val pendingSurfaceFramesCountA: Int get() = this.pendingSurfaceFramesA.get()
    override val pendingSurfaceFramesCountB: Int get() = this.pendingSurfaceFramesB.get()
    override val pendingSeekInfo: String get() = "accuratePending=$forceAccurateSeekPending, waiting=$isWaitingForFrame, trimPreview=$trimPreviewTimeUs"
    override val isSuspended: Boolean get() = isSuspendedForExport
    override val stateMachineState: String get() = "waiting=$isWaitingForFrame, latched=$isFirstFrameLatched"

    private val renderer = TransitionRenderer(context?.cacheDir)
    internal val transitionRenderer: TransitionRenderer get() = renderer

    val overlayTextureCache = OverlayTextureCache().apply {
        onFrameReady = { requestRender() }
    }

    val passRegistry: PassRegistry = PassRegistry()
    val overlayStagePass: OverlayStagePass = OverlayStagePass(this)
    private val baseFrameInfo = MutableBaseFrameInfo()
    private val passCtx = MutablePassCtx(baseFrame = baseFrameInfo)

    init {
        passRegistry.register(overlayStagePass)
        featureRegistry?.let {
            passRegistry.wireTo(it)
            overlayStagePass.wireTo(it)
        }
        PreviewWatchdog.start(this)
    }

    val glSurfaceView: GLSurfaceView? = context?.let { ctx ->
        GLSurfaceView(ctx).apply {
            setEGLContextClientVersion(3)
            setRenderer(this@PreviewRenderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            this.holder.setSizeFromLayout()
        }
    }

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
    private val animEvaluator = AnimatedTransformEvaluator()
    private var surfaceTextureA: SurfaceTexture? = null
    private var surfaceTextureB: SurfaceTexture? = null

    private var frameSourceA: FrameSource? = null
    private var frameSourceB: FrameSource? = null

    val owesFrame: Boolean
        get() = isWaitingForFrame ||
                (frameSourceA?.isCatchingUpFromSeek == true) ||
                (frameSourceB?.isCatchingUpFromSeek == true)

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
    override val activeLayerSet: ActiveLayerSet = ActiveLayerSet()
    private val resolvedFrame = ResolvedFrame()
    private val frameCtx = MutableFrameCtx()

    private val matA = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
    private val matB = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
    private val rawMatA = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
    private val rawMatB = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
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

    private fun safeRequestRender(caller: RenderRequestCaller = RenderRequestCaller.OTHER) {
        PreviewWatchdog.onRequestRender()
        if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
            PacePerfTracker.onRequestRender(caller, playhead.mode)
        }
        val req = onRenderRequested
        if (req != null) {
            req.invoke()
        } else {
            glSurfaceView?.requestRender()
        }
    }

    // Step C: Debug-only metrics (zero allocations per frame, reused StringBuilder)
    private var passPerfFramesDrawn: Long = 0L
    private var passPerfFramesLate: Long = 0L
    private var passPerfBasePassTotalNs: Long = 0L
    private var passPerfBasePassMaxNs: Long = 0L
    private var passPerfBasePassCount: Long = 0L
    private var lastPassPerfLogNs: Long = 0L
    private val passPerfSb: StringBuilder = StringBuilder(256)

    // STEP A: Debug-only FramePerf telemetry (zero allocations per frame, reused StringBuilder)
    // PassPerf "late" counter definition (Rule R10 / Step A.4):
    // The existing `passPerfFramesLate` counter is defined as DRAW EXECUTION DURATION of onDrawFrame() exceeding 16.666 ms
    // (i.e. (System.nanoTime() - startNs) / 1000L > 16_666L), measuring CPU/GPU submission time of a single draw pass.
    // It does NOT measure frame interval / pacing cadence (the elapsed time between consecutive frame starts).
    // In FramePerf below, we track both whole onDrawFrame draw duration and frame interval between starts
    // (including interval > 20ms and > 33ms dropped-vsync intervals) to separate decoder/draw bottlenecks from pacing jitter.
    private var framePerfDecoderWaitTotalNs: Long = 0L
    private var framePerfDecoderWaitMaxNs: Long = 0L
    private var framePerfDecoderWaitCount: Long = 0L

    private var framePerfUpdateTexTotalNs: Long = 0L
    private var framePerfUpdateTexMaxNs: Long = 0L
    private var framePerfUpdateTexCount: Long = 0L

    private var framePerfDrawCpuTotalNs: Long = 0L
    private var framePerfDrawCpuMaxNs: Long = 0L
    private var framePerfDrawCpuCount: Long = 0L

    private var framePerfWholeDrawTotalNs: Long = 0L
    private var framePerfWholeDrawMaxNs: Long = 0L
    private var framePerfWholeDrawCount: Long = 0L

    private var framePerfLastFrameStartNs: Long = 0L
    private var framePerfIntervalTotalNs: Long = 0L
    private var framePerfIntervalMaxNs: Long = 0L
    private var framePerfIntervalCount: Long = 0L
    private var framePerfIntervalGt20ms: Long = 0L
    private var framePerfIntervalGt33ms: Long = 0L

    private var framePerfNewFramesCount: Long = 0L
    private var framePerfRepeatedFramesCount: Long = 0L

    private var lastFramePerfLogNs: Long = 0L
    private val framePerfSb: StringBuilder = StringBuilder(512)
    private var framePerfLoggedPlaybackStart: Boolean = false
    private var lastSeenTexTimestampA: Long = -1L
    private var lastSeenTexTimestampB: Long = -1L

    private fun logPlaybackStart(clip: Clip, fs: FrameSource?) {
        val codecStr = fs?.codecName ?: fs?.currentMimeType ?: "unknown"
        val proxyStore = overlayTextureCache.proxyStore ?: OverlayTextureCache.defaultProxyStore
        val isProxyUsed = proxyStore?.getReadyProxyPath(clip.path) != null
        try {
            android.util.Log.i(
                "FramePerf",
                "Playback Start: clipId=${clip.id}, res=${clip.width}x${clip.height} (display=${clip.displayWidth}x${clip.displayHeight}), fps=${clip.frameRate}, codec=$codecStr, proxyUsed=$isProxyUsed"
            )
        } catch (_: Throwable) {
            println("[FramePerf] Playback Start: clipId=${clip.id}, res=${clip.width}x${clip.height} (display=${clip.displayWidth}x${clip.displayHeight}), fps=${clip.frameRate}, codec=$codecStr, proxyUsed=$isProxyUsed")
        }
    }

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

    internal val drawSpec = DrawSpec()

    private val contributors = mutableListOf<RenderContributor>()
    private val disabledContributors = mutableSetOf<RenderContributor>()
    private var sortedContributors: Array<RenderContributor> = emptyArray()
    private var cachedSnapshot: TimelineSnapshot? = null
    private var contributorLaneMap: Array<IntArray> = emptyArray()

    private val prefetchers = mutableListOf<Prefetcher>()
    private val pendingSurfaceFramesA = AtomicInteger(0)
    private val pendingSurfaceFramesB = AtomicInteger(0)
    private val frameLockA = java.lang.Object()
    private val frameLockB = java.lang.Object()

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
    override var selectedOverlayIdForPreview: String? = null

    fun stopPrefetch() {
        // Disabled
    }

    fun triggerPrefetch(windowUs: Long = 2_000_000L) {
        // Disabled
    }

    @Volatile
    private var forceAccurateSeekPending = false

    fun requestAccurateSeek() {
        forceAccurateSeekPending = true
        isWaitingForFrame = true
        pendingSurfaceFramesA.set(0)
        pendingSurfaceFramesB.set(0)
        PreviewEventLog.log(PreviewEventType.SEEK_ENTER, reason = "requestAccurateSeek")
        PreviewWatchdog.onRequestRender()
        if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
            PacePerfTracker.onRequestRender(RenderRequestCaller.SEEK, playhead.mode)
        }
        glSurfaceView?.requestRender()
        onRenderRequested?.invoke()
    }

    fun requestRender(caller: RenderRequestCaller = RenderRequestCaller.OTHER) {
        PreviewWatchdog.onRequestRender()
        if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
            PacePerfTracker.onRequestRender(caller, playhead.mode)
        }
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
        PreviewWatchdog.glThread = Thread.currentThread()
        surfaceCreatedCount++
        val isEglLoss = surfaceCreatedCount > 1
        PreviewEventLog.log(
            if (isEglLoss) PreviewEventType.EGL_CONTEXT_LOSS else PreviewEventType.GL_SURFACE_CREATED,
            arg0 = surfaceCreatedCount.toLong(),
            reason = "recreate: renderer.init, textures, decoders, passes"
        )
        renderer.init()
        GlPassScope.restoreBaseline(PREVIEW_W, PREVIEW_H)
        recreateSurfaceTextures()
        releaseDecoders(synchronous = true)
        passRegistry.onGlCreated()
    }

    private fun recreateSurfaceTextures() {
        if (surfaceTextureA != null) {
            surfaceTextureA?.setOnFrameAvailableListener(null)
            surfaceTextureA?.release()
            ResourceTracker.onReleased(ResourceTracker.ResourceType.SURFACE_TEXTURE, "stA")
        }
        if (surfaceTextureB != null) {
            surfaceTextureB?.setOnFrameAvailableListener(null)
            surfaceTextureB?.release()
            ResourceTracker.onReleased(ResourceTracker.ResourceType.SURFACE_TEXTURE, "stB")
        }
        pendingSurfaceFramesA.set(0)
        pendingSurfaceFramesB.set(0)

        val stA = SurfaceTexture(renderer.textureIdA()).apply {
            ResourceTracker.onCreated(ResourceTracker.ResourceType.SURFACE_TEXTURE, "stA")
            setDefaultBufferSize(PREVIEW_W, PREVIEW_H)
            setOnFrameAvailableListener({
                pendingSurfaceFramesA.incrementAndGet()
                synchronized(frameLockA) {
                    frameLockA.notifyAll()
                }
                isWaitingForFrame = false
                if (playhead.mode != PlayMode.PLAYING) {
                    onRenderRequested?.invoke()
                    glSurfaceView?.requestRender()
                } else if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                    PacePerfTracker.onRequestRender(RenderRequestCaller.ON_FRAME_AVAILABLE, playhead.mode)
                }
            }, stCallbackHandler)
        }
        val stB = SurfaceTexture(renderer.textureIdB()).apply {
            ResourceTracker.onCreated(ResourceTracker.ResourceType.SURFACE_TEXTURE, "stB")
            setDefaultBufferSize(PREVIEW_W, PREVIEW_H)
            setOnFrameAvailableListener({
                pendingSurfaceFramesB.incrementAndGet()
                synchronized(frameLockB) {
                    frameLockB.notifyAll()
                }
                isWaitingForFrame = false
                if (playhead.mode != PlayMode.PLAYING) {
                    onRenderRequested?.invoke()
                    glSurfaceView?.requestRender()
                } else if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                    PacePerfTracker.onRequestRender(RenderRequestCaller.ON_FRAME_AVAILABLE, playhead.mode)
                }
            }, stCallbackHandler)
        }

        surfaceTextureA = stA
        surfaceTextureB = stB
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        PreviewWatchdog.glThread = Thread.currentThread()
        PreviewEventLog.log(PreviewEventType.GL_SURFACE_CHANGED, arg0 = width.toLong(), arg1 = height.toLong(), reason = "viewport=${width}x${height}")
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
        GlPassScope.restoreBaseline(width, height)
        requestRender()
        onRenderRequested?.invoke()
    }

    private var lastPausedTargetUs: Long = -1L
    private var pausedCatchUpAttempts: Int = 0

    private fun checkIsCatchingUp(frame: ResolvedFrame, isTransition: Boolean): Boolean {
        // During active scrubbing/flinging: fast keyframe preview is shown, no loader needed.
        // Only signal catching-up (loader) when truly paused and waiting for accurate frame.
        val isScrubOrFling = playhead.mode == PlayMode.SCRUBBING || playhead.mode == PlayMode.FLINGING
        if (isScrubOrFling) {
            lastPausedTargetUs = -1L
            pausedCatchUpAttempts = 0
            return false
        }

        return if (playhead.mode == PlayMode.PAUSED) {
            if (frame.tUs != lastPausedTargetUs) {
                lastPausedTargetUs = frame.tUs
                pausedCatchUpAttempts = 0
            }
            pausedCatchUpAttempts++
            if (pausedCatchUpAttempts > 120) {
                frameSourceA?.isAccuratePausedFrameLatched = true
                frameSourceB?.isAccuratePausedFrameLatched = true
                false
            } else {
                val activeFs = if (primarySlot == 0) frameSourceA else frameSourceB
                if (isTransition) {
                    val readyA = frameSourceA != null && frameSourceA!!.prewarmStage >= 3 &&
                            !frameSourceA!!.isCatchingUpFromSeek && frameSourceA!!.hasValidFrameNear(frame.clipASourceUs)
                    val readyB = frameSourceB != null && frameSourceB!!.prewarmStage >= 3 &&
                            !frameSourceB!!.isCatchingUpFromSeek && frameSourceB!!.hasValidFrameNear(frame.clipBSourceUs)
                    !(readyA && readyB)
                } else {
                    val isReady = activeFs != null && activeFs.prewarmStage >= 3 &&
                            !activeFs.isCatchingUpFromSeek && activeFs.hasValidFrameNear(frame.clipASourceUs)
                    !isReady
                }
            }
        } else {
            lastPausedTargetUs = -1L
            pausedCatchUpAttempts = 0
            val hasValidFrameNear = if (isTransition) {
                (frameSourceA?.hasValidFrameNear(frame.clipASourceUs) == true) &&
                        (frameSourceB?.hasValidFrameNear(frame.clipBSourceUs) == true)
            } else {
                val activeFs = if (primarySlot == 0) frameSourceA else frameSourceB
                activeFs?.hasValidFrameNear(frame.clipASourceUs) == true
            }
            if (hasValidFrameNear) {
                false
            } else if (isTransition) {
                !isFirstFrameLatched ||
                    (frameSourceA != null && (frameSourceA!!.prewarmStage < 3 || frameSourceA!!.isCatchingUpFromSeek)) ||
                    (frameSourceB != null && (frameSourceB!!.prewarmStage < 3 || frameSourceB!!.isCatchingUpFromSeek))
            } else {
                val activeFs = if (primarySlot == 0) frameSourceA else frameSourceB
                !isFirstFrameLatched || (activeFs != null && (activeFs.prewarmStage < 3 || activeFs.isCatchingUpFromSeek))
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        PreviewWatchdog.onDrawFrameStart()
        val startNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) System.nanoTime() else 0L
        val wasWaitingInitial = isWaitingForFrame
        var showedNewFrame = false
        try {
            if (isSuspendedForExport) {
                PreviewEventLog.onBlackoutClearBranch("isSuspendedForExport", "isSuspended=true")
                GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
                GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                return
            }
            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                PacePerfTracker.onDrawFrameStart(playhead.mode)
            }
            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                if (playhead.mode == PlayMode.PLAYING) {
                    if (framePerfLastFrameStartNs > 0L) {
                        val intervalNs = startNs - framePerfLastFrameStartNs
                        framePerfIntervalTotalNs += intervalNs
                        if (intervalNs > framePerfIntervalMaxNs) framePerfIntervalMaxNs = intervalNs
                        framePerfIntervalCount++
                        if (intervalNs > 20_000_000L) framePerfIntervalGt20ms++
                        if (intervalNs > 33_333_333L) framePerfIntervalGt33ms++
                    }
                    framePerfLastFrameStartNs = startNs
                } else {
                    framePerfLastFrameStartNs = 0L
                }
            }
            val s = holder.current
            val clips = s.project.clips
            if (clips.isEmpty()) {
                PreviewEventLog.onBlackoutClearBranch("clips_empty", "clipCount=0")
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
                PreviewEventLog.onBlackoutClearBranch("slot_invalid_negative_clip_index", "clipAIndex=${frame.clipAIndex}")
                GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                isWaitingForFrame = false
                return
            }

            val stA = surfaceTextureA
            val stB = surfaceTextureB
            if (stA == null || stB == null) {
                PreviewEventLog.onBlackoutClearBranch("surface_textures_null", "stA=${stA != null}, stB=${stB != null}")
                return
            }
            val clipA = s.project.clips.getOrNull(frame.clipAIndex)
            if (clipA == null) {
                PreviewEventLog.onBlackoutClearBranch("clipA_null", "clipAIndex=${frame.clipAIndex}, clipCount=${clips.size}")
                isWaitingForFrame = false
                return
            }

            val speedA = clipA.timeMap?.let { tm ->
                tm.speedNum.toFloat() / tm.speedDen.coerceAtLeast(1).toFloat()
            } ?: 1.0f
            val clipBForSpeed = if (frame.isTransition && frame.clipBIndex >= 0) s.project.clips.getOrNull(frame.clipBIndex) else null
            val speedB = clipBForSpeed?.timeMap?.let { tm ->
                tm.speedNum.toFloat() / tm.speedDen.coerceAtLeast(1).toFloat()
            } ?: 1.0f

            val isAccurateSeek = forceAccurateSeekPending && playhead.mode == PlayMode.PAUSED
            if (isAccurateSeek) {
                forceAccurateSeekPending = false
            }
            val isPaused = playhead.mode == PlayMode.PAUSED
            val isScrubbing = !isAccurateSeek && ((trimPreviewTimeUs >= 0L) ||
                    playhead.mode == PlayMode.SCRUBBING ||
                    playhead.mode == PlayMode.FLINGING)

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
                                frameSourceB?.clip?.id != clipA.id ||
                                frameSourceB?.clip?.path != clipA.path
                        if (clipAChanged) {
                            frameSourceB?.release(synchronous = true)
                            frameSourceB = FrameSource(toLegacyClip(clipA), stB).apply { slot = 1 }
                            ResourceTracker.logAliveCounts("slot_recreated_B")
                            currentClipIndexB = frame.clipAIndex
                        } else {
                            PreviewEventLog.log(PreviewEventType.FRAMESOURCE_REUSE, slot = 1, clipId = clipA.id, reason = "case1_outgoing_reuse")
                            frameSourceB?.clip = toLegacyClip(clipA)
                        }
                        val fsWaitStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                        val fsOutgoing = frameSourceB
                        fsOutgoing?.playbackSpeed = speedA
                        if (fsOutgoing != null && fsOutgoing.prewarmStage < 3) {
                            fsOutgoing.ensureDecodedAt(frame.clipASourceUs)
                        }
                        val renderedA = try {
                            fsOutgoing?.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing, isPaused = isPaused) ?: false
                        } catch (e: Exception) {
                            PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.case1_fsOutgoing_B", e)
                            false
                        }
                        if (!renderedA && fsOutgoing?.isCatchingUpFromSeek == true) {
                            safeRequestRender()
                        }
                        val pendingB = pendingSurfaceFramesB.getAndSet(0)
                        if (renderedA || pendingB > 0) {
                            val updateStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                            try {
                                var drained = 0
                                while (drained < pendingB) {
                                    stB.updateTexImage()
                                    drained++
                                }
                                if (drained == 0 && renderedA) {
                                    stB.updateTexImage()
                                }
                                stB.getTransformMatrix(rawMatB)
                                isFirstFrameLatched = true
                                isWaitingForFrame = false
                                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                                    val ts = stB.timestamp
                                    if (ts != lastSeenTexTimestampB) {
                                        lastSeenTexTimestampB = ts
                                        showedNewFrame = true
                                        onNewFrameLatchedSlotB(ts)
                                    }
                                }
                            } catch (e: Exception) {
                                PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.case1_stB", e)
                            }
                            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                                val updateDur = System.nanoTime() - updateStartNs
                                framePerfUpdateTexTotalNs += updateDur
                                if (updateDur > framePerfUpdateTexMaxNs) framePerfUpdateTexMaxNs = updateDur
                                framePerfUpdateTexCount++
                            }
                        }

                        // Incoming clipB is on Slot A (stA)
                        val clipBChanged = frameSourceA == null ||
                                currentClipIndexA != frame.clipBIndex ||
                                frameSourceA?.clip?.id != clipB.id ||
                                frameSourceA?.clip?.path != clipB.path
                        if (clipBChanged) {
                            frameSourceA?.release(synchronous = true)
                            frameSourceA = FrameSource(toLegacyClip(clipB), stA).apply { slot = 0 }
                            ResourceTracker.logAliveCounts("slot_recreated_A")
                            currentClipIndexA = frame.clipBIndex
                        } else {
                            PreviewEventLog.log(PreviewEventType.FRAMESOURCE_REUSE, slot = 0, clipId = clipB.id, reason = "case1_incoming_reuse")
                            frameSourceA?.clip = toLegacyClip(clipB)
                        }
                        val fsIncoming = frameSourceA
                        fsIncoming?.playbackSpeed = speedB
                        if (fsIncoming != null && fsIncoming.prewarmStage < 3) {
                            fsIncoming.ensureDecodedAt(frame.clipBSourceUs)
                        }
                        val renderedB = try {
                            fsIncoming?.frameAt(frame.clipBSourceUs, isScrubbing = isScrubbing, isPaused = isPaused) ?: false
                        } catch (e: Exception) {
                            PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.case1_fsIncoming_A", e)
                            false
                        }
                        if (!renderedB && fsIncoming?.isCatchingUpFromSeek == true) {
                            safeRequestRender()
                        }
                        val pendingA = pendingSurfaceFramesA.getAndSet(0)
                        if (renderedB || pendingA > 0) {
                            val updateStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                            try {
                                var drained = 0
                                while (drained < pendingA) {
                                    stA.updateTexImage()
                                    drained++
                                }
                                if (drained == 0 && renderedB) {
                                    stA.updateTexImage()
                                }
                                stA.getTransformMatrix(rawMatA)
                                isFirstFrameLatched = true
                                isWaitingForFrame = false
                                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                                    val ts = stA.timestamp
                                    if (ts != lastSeenTexTimestampA) {
                                        lastSeenTexTimestampA = ts
                                        showedNewFrame = true
                                        onNewFrameLatchedSlotA(ts)
                                    }
                                }
                            } catch (e: Exception) {
                                PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.case1_stA", e)
                            }
                            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                                val updateDur = System.nanoTime() - updateStartNs
                                framePerfUpdateTexTotalNs += updateDur
                                if (updateDur > framePerfUpdateTexMaxNs) framePerfUpdateTexMaxNs = updateDur
                                framePerfUpdateTexCount++
                            }
                        }
                        if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                            val fsWaitDur = System.nanoTime() - fsWaitStartNs
                            framePerfDecoderWaitTotalNs += fsWaitDur
                            if (fsWaitDur > framePerfDecoderWaitMaxNs) framePerfDecoderWaitMaxNs = fsWaitDur
                            framePerfDecoderWaitCount++
                            if (!framePerfLoggedPlaybackStart) {
                                framePerfLoggedPlaybackStart = true
                                logPlaybackStart(clipA, fsOutgoing)
                            }
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
                                frameSourceA?.clip?.id != clipA.id ||
                                frameSourceA?.clip?.path != clipA.path
                        if (clipAChanged) {
                            frameSourceA?.release(synchronous = true)
                            frameSourceA = FrameSource(toLegacyClip(clipA), stA).apply { slot = 0 }
                            ResourceTracker.logAliveCounts("slot_recreated_A")
                            currentClipIndexA = frame.clipAIndex
                        } else {
                            PreviewEventLog.log(PreviewEventType.FRAMESOURCE_REUSE, slot = 0, clipId = clipA.id, reason = "case1_outgoing_reuse")
                            frameSourceA?.clip = toLegacyClip(clipA)
                        }
                        val fsWaitStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                        val fsOutgoing = frameSourceA
                        fsOutgoing?.playbackSpeed = speedA
                        if (fsOutgoing != null && fsOutgoing.prewarmStage < 3) {
                            fsOutgoing.ensureDecodedAt(frame.clipASourceUs)
                        }
                        val renderedA = try {
                            fsOutgoing?.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing, isPaused = isPaused) ?: false
                        } catch (e: Exception) {
                            PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.case1_fsOutgoing_A", e)
                            false
                        }
                        if (!renderedA && fsOutgoing?.isCatchingUpFromSeek == true) {
                            safeRequestRender()
                        }
                        val pendingA = pendingSurfaceFramesA.getAndSet(0)
                        if (renderedA || pendingA > 0) {
                            val updateStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                            try {
                                var drained = 0
                                while (drained < pendingA) {
                                    stA.updateTexImage()
                                    drained++
                                }
                                if (drained == 0 && renderedA) {
                                    stA.updateTexImage()
                                }
                                stA.getTransformMatrix(rawMatA)
                                isFirstFrameLatched = true
                                isWaitingForFrame = false
                                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                                    val ts = stA.timestamp
                                    if (ts != lastSeenTexTimestampA) {
                                        lastSeenTexTimestampA = ts
                                        showedNewFrame = true
                                        onNewFrameLatchedSlotA(ts)
                                    }
                                }
                            } catch (e: Exception) {
                                PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.case1_stA", e)
                            }
                            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                                val updateDur = System.nanoTime() - updateStartNs
                                framePerfUpdateTexTotalNs += updateDur
                                if (updateDur > framePerfUpdateTexMaxNs) framePerfUpdateTexMaxNs = updateDur
                                framePerfUpdateTexCount++
                            }
                        }

                        // Incoming clipB is on Slot B (stB)
                        val clipBChanged = frameSourceB == null ||
                                currentClipIndexB != frame.clipBIndex ||
                                frameSourceB?.clip?.id != clipB.id ||
                                frameSourceB?.clip?.path != clipB.path
                        if (clipBChanged) {
                            frameSourceB?.release(synchronous = true)
                            frameSourceB = FrameSource(toLegacyClip(clipB), stB).apply { slot = 1 }
                            ResourceTracker.logAliveCounts("slot_recreated_B")
                            currentClipIndexB = frame.clipBIndex
                        } else {
                            PreviewEventLog.log(PreviewEventType.FRAMESOURCE_REUSE, slot = 1, clipId = clipB.id, reason = "case1_incoming_reuse")
                            frameSourceB?.clip = toLegacyClip(clipB)
                        }
                        val fsIncoming = frameSourceB
                        fsIncoming?.playbackSpeed = speedB
                        if (fsIncoming != null && fsIncoming.prewarmStage < 3) {
                            fsIncoming.ensureDecodedAt(frame.clipBSourceUs)
                        }
                        val renderedB = try {
                            fsIncoming?.frameAt(frame.clipBSourceUs, isScrubbing = isScrubbing, isPaused = isPaused) ?: false
                        } catch (e: Exception) {
                            PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.case1_fsIncoming_B", e)
                            false
                        }
                        if (!renderedB && fsIncoming?.isCatchingUpFromSeek == true) {
                            safeRequestRender()
                        }
                        val pendingB = pendingSurfaceFramesB.getAndSet(0)
                        if (renderedB || pendingB > 0) {
                            val updateStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                            try {
                                var drained = 0
                                while (drained < pendingB) {
                                    stB.updateTexImage()
                                    drained++
                                }
                                if (drained == 0 && renderedB) {
                                    stB.updateTexImage()
                                }
                                stB.getTransformMatrix(rawMatB)
                                isFirstFrameLatched = true
                                isWaitingForFrame = false
                                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                                    val ts = stB.timestamp
                                    if (ts != lastSeenTexTimestampB) {
                                        lastSeenTexTimestampB = ts
                                        showedNewFrame = true
                                        onNewFrameLatchedSlotB(ts)
                                    }
                                }
                            } catch (e: Exception) {
                                PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.case1_stB", e)
                            }
                            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                                val updateDur = System.nanoTime() - updateStartNs
                                framePerfUpdateTexTotalNs += updateDur
                                if (updateDur > framePerfUpdateTexMaxNs) framePerfUpdateTexMaxNs = updateDur
                                framePerfUpdateTexCount++
                            }
                        }
                        if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                            val fsWaitDur = System.nanoTime() - fsWaitStartNs
                            framePerfDecoderWaitTotalNs += fsWaitDur
                            if (fsWaitDur > framePerfDecoderWaitMaxNs) framePerfDecoderWaitMaxNs = fsWaitDur
                            framePerfDecoderWaitCount++
                            if (!framePerfLoggedPlaybackStart) {
                                framePerfLoggedPlaybackStart = true
                                logPlaybackStart(clipA, fsOutgoing)
                            }
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
                    val baseStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) System.nanoTime() else 0L
                    GlPassScope.runPass(
                        passName = "TransitionVideoPass",
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        enableBlend = false,
                        enableScissor = needsScissor,
                        scissorRect = if (needsScissor) scissorRect else null
                    ) {
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

                        resolveFilterAndEffect(clipA, frame.tUs)
                        val drawCpuStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                        PreviewEventLog.onVideoDrawn()
                        renderer.draw(drawSpec)
                        if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                            val drawCpuDur = System.nanoTime() - drawCpuStartNs
                            framePerfDrawCpuTotalNs += drawCpuDur
                            if (drawCpuDur > framePerfDrawCpuMaxNs) framePerfDrawCpuMaxNs = drawCpuDur
                            framePerfDrawCpuCount++
                        }
                    }

                    if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                        val baseDur = System.nanoTime() - baseStartNs
                        passPerfBasePassTotalNs += baseDur
                        if (baseDur > passPerfBasePassMaxNs) passPerfBasePassMaxNs = baseDur
                        passPerfBasePassCount++
                    }

                    lastTransitionIncomingSlot = if (isOutgoingOnSlotB) 0 else 1
                    lastTransitionIncomingClipIndex = frame.clipBIndex

                    renderOverlaysStage(frame.tUs)

            val isTransition = frame.isTransition && frame.clipBIndex >= 0
            val isCatchingUp = checkIsCatchingUp(frame, isTransition)

            if (isCatchingUp) {
                // Only show loader spinner when paused (not during scrubbing/flinging)
                if (playhead.mode == PlayMode.PAUSED) {
                    isWaitingForFrame = true
                    val activelyCatchingUp = (frameSourceA?.isCatchingUpFromSeek == true) || (frameSourceB?.isCatchingUpFromSeek == true)
                    if (activelyCatchingUp) {
                        safeRequestRender()
                    }
                } else {
                    safeRequestRender()
                }
            } else {
                // No pending surface frame here. Do NOT clear isWaitingForFrame.
                // Only onFrameAvailable and a successful updateTexImage may clear it.
            }
                    if (playhead.mode == PlayMode.PLAYING) {
                        safeRequestRender()
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
            val isCrossingToNextClip = currentClipIndexA != -1 && currentClipIndexA != frame.clipAIndex
            val continuityLimitUs = maxOf(150_000L, (200_000L * speedA).toLong())
            val canSlotAContinue = !isCrossingToNextClip && frameSourceA != null && frameSourceA?.clip?.path == clipA.path &&
                (frameSourceA!!.prewarmStage >= 3) &&
                (kotlin.math.abs(frame.clipASourceUs - frameSourceA!!.lastDecodedPtsUs) <= continuityLimitUs ||
                 (frame.clipASourceUs >= frameSourceA!!.lastDecodedPtsUs && frame.clipASourceUs - frameSourceA!!.lastDecodedPtsUs <= continuityLimitUs))

            if (isCrossingToNextClip && !canSlotAContinue && (standbySlotReadyForClipId == clipA.id || (standbyPreparedPath == clipA.path && currentClipIndexB == frame.clipAIndex)) &&
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
            val isCrossingToNextClip = currentClipIndexB != -1 && currentClipIndexB != frame.clipAIndex
            val continuityLimitUs = maxOf(150_000L, (200_000L * speedA).toLong())
            val canSlotBContinue = !isCrossingToNextClip && frameSourceB != null && frameSourceB?.clip?.path == clipA.path &&
                (frameSourceB!!.prewarmStage >= 3) &&
                (kotlin.math.abs(frame.clipASourceUs - frameSourceB!!.lastDecodedPtsUs) <= continuityLimitUs ||
                 (frame.clipASourceUs >= frameSourceB!!.lastDecodedPtsUs && frame.clipASourceUs - frameSourceB!!.lastDecodedPtsUs <= continuityLimitUs))

            // primarySlot == 1: Release frameSourceA if it still held an old clip from before the transition
            if (frameSourceA != null && (frameSourceA?.clip?.id != clipA.id || frameSourceA?.clip?.path != clipA.path) && currentClipIndexA < frame.clipAIndex && !canSlotBContinue) {
                frameSourceA?.release()
                frameSourceA = null
                currentClipIndexA = -1
            }
            if (isCrossingToNextClip && !canSlotBContinue && (standbySlotReadyForClipId == clipA.id || (standbyPreparedPath == clipA.path && currentClipIndexA == frame.clipAIndex)) &&
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
            val clipAChanged = frameSourceA == null ||
                frameSourceA?.clip?.id != clipA.id ||
                frameSourceA?.clip?.path != clipA.path ||
                currentClipIndexA != frame.clipAIndex

            if (clipAChanged) {
                val legacyClipA = toLegacyClip(clipA)
                val activeFs = frameSourceA
                if (activeFs != null && activeFs.clip.path == clipA.path && activeFs.canReuse(legacyClipA)) {
                    // Reuse active decoder across split clips sharing the same media path
                    activeFs.clip = legacyClipA
                    PreviewEventLog.log(PreviewEventType.FRAMESOURCE_REUSE, slot = 0, clipId = clipA.id, reason = "split_reuse")
                    currentClipIndexA = frame.clipAIndex
                    if (activeFs.prewarmStage >= 3) {
                        val currentPts = activeFs.lastDecodedPtsUs
                        val targetPts = frame.clipASourceUs
                        val effectiveTargetPts =
                            if (activeFs.isAtEndOfMedia)
                                minOf(targetPts, activeFs.lastAvailablePtsUs)
                            else targetPts
                        val fwdLimit = maxOf(600_000L, (400_000L * speedA).toLong())
                        val isContinuous = (currentPts != -1L) &&
                            (effectiveTargetPts >= currentPts - 100_000L && effectiveTargetPts <= currentPts + fwdLimit)
                        val shouldSeek = if (!isPaused && !isScrubbing) {
                            currentPts != -1L && (effectiveTargetPts < currentPts - 100_000L || effectiveTargetPts > currentPts + fwdLimit)
                        } else {
                            !isContinuous && (currentPts == -1L || kotlin.math.abs(effectiveTargetPts - currentPts) > 60_000L)
                        }
                        Log.w("SEEKTRACE", "DECIDE shouldSeek=$shouldSeek target=$targetPts eff=$effectiveTargetPts atEnd=${activeFs.isAtEndOfMedia}")
                        if (shouldSeek) {
                            pendingSurfaceFramesA.set(0)
                            val useFast = isScrubbing &&
                                activeFs.keyframeIntervalUs in 1..1_500_000L
                            activeFs.seekTo(effectiveTargetPts, fastKeyframeOnly = useFast)
                        }
                    }
                } else {
                    frameSourceA?.release(synchronous = true)
                    pendingSurfaceFramesA.set(0)
                    frameSourceA = FrameSource(legacyClipA, stA).apply { slot = 0 }
                    ResourceTracker.logAliveCounts("slot_recreated_A")
                    currentClipIndexA = frame.clipAIndex
                }
            } else {
                currentClipIndexA = frame.clipAIndex
                val legacyClipA = toLegacyClip(clipA)
                if (frameSourceA == null || frameSourceA?.clip?.path != clipA.path) {
                    frameSourceA?.release(synchronous = true)
                    pendingSurfaceFramesA.set(0)
                    frameSourceA = FrameSource(legacyClipA, stA).apply { slot = 0 }
                    ResourceTracker.logAliveCounts("slot_recreated_A")
                } else {
                    PreviewEventLog.log(PreviewEventType.FRAMESOURCE_REUSE, slot = 0, clipId = clipA.id, reason = "path_match_reuse")
                    frameSourceA?.clip = legacyClipA
                }
            }

            val fsWaitStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
            val fsA = frameSourceA
            if (fsA != null) {
                fsA.playbackSpeed = speedA
                if (fsA.prewarmStage < 2) {
                    fsA.startCodecAndSeek(frame.clipASourceUs)
                    safeRequestRender()
                }
                if (fsA.prewarmStage == 2) {
                    fsA.stepPrewarmTo(frame.clipASourceUs)
                    safeRequestRender()
                }
                if (fsA.prewarmStage >= 3) {
                    val rendered = try {
                        fsA.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing, isPaused = isPaused, mode = playhead.mode)
                    } catch (e: Exception) {
                        PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.fsA", e)
                        false
                    }
                    if (!rendered && fsA.isCatchingUpFromSeek) {
                        safeRequestRender()
                    }
                }
            }
            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                val fsWaitDur = System.nanoTime() - fsWaitStartNs
                framePerfDecoderWaitTotalNs += fsWaitDur
                if (fsWaitDur > framePerfDecoderWaitMaxNs) framePerfDecoderWaitMaxNs = fsWaitDur
                framePerfDecoderWaitCount++
                if (!framePerfLoggedPlaybackStart) {
                    framePerfLoggedPlaybackStart = true
                    logPlaybackStart(clipA, fsA)
                }
            }

            if (playhead.mode == PlayMode.PLAYING && (fsA?.didReleaseOutputBuffer == true) && pendingSurfaceFramesA.get() == 0) {
                synchronized(frameLockA) {
                    if (pendingSurfaceFramesA.get() == 0) {
                        try {
                            frameLockA.wait(3L)
                        } catch (_: InterruptedException) {}
                    }
                }
            }

            val pendingA = pendingSurfaceFramesA.getAndSet(0)
            if (pendingA > 0) {
                val updateStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                try {
                    var drained = 0
                    while (drained < pendingA) {
                        stA.updateTexImage()
                        drained++
                    }
                    stA.getTransformMatrix(rawMatA)
                    isFirstFrameLatched = true
                    isWaitingForFrame = false
                    if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                        val ts = stA.timestamp
                        if (ts != lastSeenTexTimestampA) {
                            lastSeenTexTimestampA = ts
                            showedNewFrame = true
                            onNewFrameLatchedSlotA(ts)
                        }
                    }
                } catch (e: Exception) {
                    PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.stA", e)
                }
                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                    val updateDur = System.nanoTime() - updateStartNs
                    framePerfUpdateTexTotalNs += updateDur
                    if (updateDur > framePerfUpdateTexMaxNs) framePerfUpdateTexMaxNs = updateDur
                    framePerfUpdateTexCount++
                }
            }
            val transformA = getClipTransform(clipA.id)
            System.arraycopy(rawMatA, 0, matA, 0, 16)
            calculateClipCrop(clipA.displayWidth, clipA.displayHeight, transformA, cropA)

            // Standby Slot B: 3-Stage Incremental Pre-warm of upcoming clip within 3.0s
            val m = frame.clipAIndex + 1
            if (m in s.project.clips.indices && !isScrubbing) {
                val nextClip = s.project.clips[m]
                val isContinuousSameFileSplit = (nextClip.path == clipA.path) &&
                        kotlin.math.abs(nextClip.map().inUs - clipA.map().outUs) <= 50_000L
                if (!isContinuousSameFileSplit) {
                    val transitionStartUs = msToUs(s.main.startMs[m])
                val timeUntilTransitionUs = transitionStartUs - frame.tUs
                if (timeUntilTransitionUs in -500_000L..3_000_000L) {
                    val nextClipInUs = nextClip.map().inUs
                    val alreadyPrepared = (standbyPreparedPath == nextClip.path && standbyPreparedInUs == nextClipInUs) ||
                            (standbySlotReadyForClipId == nextClip.id && (frameSourceB?.prewarmStage ?: 0) >= 3)
                    if (!alreadyPrepared) {
                        if (frameSourceB == null ||
                            frameSourceB?.clip?.id != nextClip.id ||
                            frameSourceB?.clip?.path != nextClip.path ||
                            currentClipIndexB != m) {
                            frameSourceB?.release(synchronous = true)
                            pendingSurfaceFramesB.set(0)
                            frameSourceB = FrameSource(toLegacyClip(nextClip), stB).apply { slot = 1 }
                            ResourceTracker.logAliveCounts("slot_recreated_B")
                            currentClipIndexB = m
                        }
                        val fsB = frameSourceB
                        if (fsB != null) {
                            val isNearTransition = timeUntilTransitionUs in 0L..800_000L
                            if (isNearTransition) {
                                if (fsB.prewarmStage < 1) fsB.prepareExtractorOnly()
                                if (fsB.prewarmStage < 2) fsB.startCodecAndSeek(nextClipInUs)
                                if (fsB.prewarmStage < 3) fsB.stepPrewarmTo(nextClipInUs)
                                if (pendingSurfaceFramesB.getAndSet(0) > 0 || fsB.prewarmStage >= 3) {
                                    try {
                                        stB.updateTexImage()
                                        stB.getTransformMatrix(rawMatB)
                                        if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                                            val ts = stB.timestamp
                                            if (ts != lastSeenTexTimestampB) {
                                                lastSeenTexTimestampB = ts
                                                onNewFrameLatchedSlotB(ts)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.standby_stB", e)
                                    }
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
                                        safeRequestRender()
                                    }
                                    1 -> {
                                        fsB.startCodecAndSeek(nextClipInUs)
                                        safeRequestRender()
                                    }
                                    2 -> {
                                        val ready = fsB.stepPrewarmTo(nextClipInUs)
                                        if (ready || pendingSurfaceFramesB.getAndSet(0) > 0) {
                                            try {
                                                stB.updateTexImage()
                                                stB.getTransformMatrix(rawMatB)
                                                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                                                    val ts = stB.timestamp
                                                    if (ts != lastSeenTexTimestampB) {
                                                        lastSeenTexTimestampB = ts
                                                        onNewFrameLatchedSlotB(ts)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.standby_stB", e)
                                            }
                                            val nextTransform = getClipTransform(nextClip.id)
                                            System.arraycopy(rawMatB, 0, matB, 0, 16)
                                            applyTransformMatrix(matB, nextTransform, nextClip.displayWidth, nextClip.displayHeight)
                                            calculateClipCrop(nextClip.displayWidth, nextClip.displayHeight, nextTransform, cropB)
                                            standbySlotReadyForClipId = nextClip.id
                                            standbyPreparedPath = nextClip.path
                                            standbyPreparedInUs = nextClipInUs
                                        } else {
                                            safeRequestRender()
                                        }
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
            val baseStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) System.nanoTime() else 0L
            GlPassScope.runPass(
                passName = "BaseVideoPassSlotA",
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                enableBlend = false,
                enableScissor = needsScissor,
                scissorRect = if (needsScissor) scissorRect else null
            ) {
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

                resolveFilterAndEffect(clipA, frame.tUs)
                val drawCpuStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                PreviewEventLog.onVideoDrawn()
                renderer.draw(drawSpec)
                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                    val drawCpuDur = System.nanoTime() - drawCpuStartNs
                    framePerfDrawCpuTotalNs += drawCpuDur
                    if (drawCpuDur > framePerfDrawCpuMaxNs) framePerfDrawCpuMaxNs = drawCpuDur
                    framePerfDrawCpuCount++
                }
            }

            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                val baseDur = System.nanoTime() - baseStartNs
                passPerfBasePassTotalNs += baseDur
                if (baseDur > passPerfBasePassMaxNs) passPerfBasePassMaxNs = baseDur
                passPerfBasePassCount++
            }

            renderOverlaysStage(frame.tUs)

            val isTransition = frame.isTransition && frame.clipBIndex >= 0
            val isCatchingUp = checkIsCatchingUp(frame, isTransition)

            if (isCatchingUp) {
                // Only show loader spinner when paused (not during scrubbing/flinging)
                if (playhead.mode == PlayMode.PAUSED) {
                    isWaitingForFrame = true
                    val activelyCatchingUp = (frameSourceA?.isCatchingUpFromSeek == true) || (frameSourceB?.isCatchingUpFromSeek == true)
                    if (activelyCatchingUp) {
                        safeRequestRender()
                    }
                } else {
                    safeRequestRender()
                }
            } else {
                // No pending surface frame here. Do NOT clear isWaitingForFrame.
                // Only onFrameAvailable and a successful updateTexImage may clear it.
            }
        } else {
            // --- Slot B is Primary (stB / texBId) ---
            val clipBChanged = frameSourceB == null ||
                frameSourceB?.clip?.id != clipA.id ||
                frameSourceB?.clip?.path != clipA.path ||
                currentClipIndexB != frame.clipAIndex

            if (clipBChanged) {
                val legacyClipA = toLegacyClip(clipA)
                val activeFs = frameSourceB
                if (activeFs != null && activeFs.clip.path == clipA.path && activeFs.canReuse(legacyClipA)) {
                    // Reuse active decoder across split clips sharing the same media path
                    activeFs.clip = legacyClipA
                    PreviewEventLog.log(PreviewEventType.FRAMESOURCE_REUSE, slot = 1, clipId = clipA.id, reason = "split_reuse")
                    currentClipIndexB = frame.clipAIndex
                    if (activeFs.prewarmStage >= 3) {
                        val currentPts = activeFs.lastDecodedPtsUs
                        val targetPts = frame.clipASourceUs
                        val effectiveTargetPts =
                            if (activeFs.isAtEndOfMedia)
                                minOf(targetPts, activeFs.lastAvailablePtsUs)
                            else targetPts
                        val fwdLimit = maxOf(600_000L, (400_000L * speedA).toLong())
                        val isContinuous = (currentPts != -1L) &&
                            (effectiveTargetPts >= currentPts - 100_000L && effectiveTargetPts <= currentPts + fwdLimit)
                        val shouldSeek = if (!isPaused && !isScrubbing) {
                            currentPts != -1L && (effectiveTargetPts < currentPts - 100_000L || effectiveTargetPts > currentPts + fwdLimit)
                        } else {
                            !isContinuous && (currentPts == -1L || kotlin.math.abs(effectiveTargetPts - currentPts) > 60_000L)
                        }
                        Log.w("SEEKTRACE", "DECIDE shouldSeek=$shouldSeek target=$targetPts eff=$effectiveTargetPts atEnd=${activeFs.isAtEndOfMedia}")
                        if (shouldSeek) {
                            pendingSurfaceFramesB.set(0)
                            val useFast = isScrubbing &&
                                activeFs.keyframeIntervalUs in 1..1_500_000L
                            activeFs.seekTo(effectiveTargetPts, fastKeyframeOnly = useFast)
                        }
                    }
                } else {
                    frameSourceB?.release(synchronous = true)
                    pendingSurfaceFramesB.set(0)
                    frameSourceB = FrameSource(legacyClipA, stB).apply { slot = 1 }
                    ResourceTracker.logAliveCounts("slot_recreated_B")
                    currentClipIndexB = frame.clipAIndex
                }
            } else {
                currentClipIndexB = frame.clipAIndex
                val legacyClipA = toLegacyClip(clipA)
                if (frameSourceB == null || frameSourceB?.clip?.path != clipA.path) {
                    frameSourceB?.release(synchronous = true)
                    pendingSurfaceFramesB.set(0)
                    frameSourceB = FrameSource(legacyClipA, stB).apply { slot = 1 }
                    ResourceTracker.logAliveCounts("slot_recreated_B")
                } else {
                    PreviewEventLog.log(PreviewEventType.FRAMESOURCE_REUSE, slot = 1, clipId = clipA.id, reason = "path_match_reuse")
                    frameSourceB?.clip = legacyClipA
                }
            }

            val fsWaitStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
            val fsB = frameSourceB
            if (fsB != null) {
                fsB.playbackSpeed = speedA
                if (fsB.prewarmStage < 2) {
                    fsB.startCodecAndSeek(frame.clipASourceUs)
                    onRenderRequested?.invoke()
                    glSurfaceView?.requestRender()
                }
                if (fsB.prewarmStage == 2) {
                    fsB.stepPrewarmTo(frame.clipASourceUs)
                    onRenderRequested?.invoke()
                    glSurfaceView?.requestRender()
                }
                if (fsB.prewarmStage >= 3) {
                    val rendered = try {
                        fsB.frameAt(frame.clipASourceUs, isScrubbing = isScrubbing, isPaused = isPaused, mode = playhead.mode)
                    } catch (e: Exception) {
                        PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.fsB", e)
                        false
                    }
                    if (!rendered && fsB.isCatchingUpFromSeek) {
                        safeRequestRender()
                    }
                }
            }
            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                val fsWaitDur = System.nanoTime() - fsWaitStartNs
                framePerfDecoderWaitTotalNs += fsWaitDur
                if (fsWaitDur > framePerfDecoderWaitMaxNs) framePerfDecoderWaitMaxNs = fsWaitDur
                framePerfDecoderWaitCount++
                if (!framePerfLoggedPlaybackStart) {
                    framePerfLoggedPlaybackStart = true
                    logPlaybackStart(clipA, fsB)
                }
            }

            if (playhead.mode == PlayMode.PLAYING && (fsB?.didReleaseOutputBuffer == true) && pendingSurfaceFramesB.get() == 0) {
                synchronized(frameLockB) {
                    if (pendingSurfaceFramesB.get() == 0) {
                        try {
                            frameLockB.wait(3L)
                        } catch (_: InterruptedException) {}
                    }
                }
            }

            val pendingB = pendingSurfaceFramesB.getAndSet(0)
            if (pendingB > 0) {
                val updateStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                try {
                    var drained = 0
                    while (drained < pendingB) {
                        stB.updateTexImage()
                        drained++
                    }
                    stB.getTransformMatrix(rawMatB)
                    isFirstFrameLatched = true
                    isWaitingForFrame = false
                    if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                        val ts = stB.timestamp
                        if (ts != lastSeenTexTimestampB) {
                            lastSeenTexTimestampB = ts
                            showedNewFrame = true
                            onNewFrameLatchedSlotB(ts)
                        }
                    }
                } catch (e: Exception) {
                    PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.stB", e)
                }
                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                    val updateDur = System.nanoTime() - updateStartNs
                    framePerfUpdateTexTotalNs += updateDur
                    if (updateDur > framePerfUpdateTexMaxNs) framePerfUpdateTexMaxNs = updateDur
                    framePerfUpdateTexCount++
                }
            }
            val transformB = getClipTransform(clipA.id)
            System.arraycopy(rawMatB, 0, matB, 0, 16)
            calculateClipCrop(clipA.displayWidth, clipA.displayHeight, transformB, cropB)

            // Standby Slot A: Pre-warm upcoming clip m = frame.clipAIndex + 1 within 3.0s (true symmetrical ping-pong)
            val m = frame.clipAIndex + 1
            if (m in s.project.clips.indices && !isScrubbing) {
                val nextClip = s.project.clips[m]
                val isContinuousSameFileSplit = (nextClip.path == clipA.path) &&
                        kotlin.math.abs(nextClip.map().inUs - clipA.map().outUs) <= 50_000L
                if (!isContinuousSameFileSplit) {
                    val transitionStartUs = msToUs(s.main.startMs[m])
                val timeUntilTransitionUs = transitionStartUs - frame.tUs
                if (timeUntilTransitionUs in -500_000L..3_000_000L) {
                    val nextClipInUs = nextClip.map().inUs
                    val alreadyPrepared = (standbyPreparedPath == nextClip.path && standbyPreparedInUs == nextClipInUs) ||
                            (standbySlotReadyForClipId == nextClip.id && (frameSourceA?.prewarmStage ?: 0) >= 3)
                    if (!alreadyPrepared) {
                        if (frameSourceA == null ||
                            frameSourceA?.clip?.id != nextClip.id ||
                            frameSourceA?.clip?.path != nextClip.path ||
                            currentClipIndexA != m) {
                            frameSourceA?.release(synchronous = true)
                            pendingSurfaceFramesA.set(0)
                            frameSourceA = FrameSource(toLegacyClip(nextClip), stA).apply { slot = 0 }
                            ResourceTracker.logAliveCounts("slot_recreated_A")
                            currentClipIndexA = m
                        }
                        val fsA = frameSourceA
                        if (fsA != null) {
                            val isNearTransition = timeUntilTransitionUs in 0L..800_000L
                            if (isNearTransition) {
                                if (fsA.prewarmStage < 1) fsA.prepareExtractorOnly()
                                if (fsA.prewarmStage < 2) fsA.startCodecAndSeek(nextClipInUs)
                                if (fsA.prewarmStage < 3) fsA.stepPrewarmTo(nextClipInUs)
                                if (pendingSurfaceFramesA.getAndSet(0) > 0 || fsA.prewarmStage >= 3) {
                                    try {
                                        stA.updateTexImage()
                                        stA.getTransformMatrix(rawMatA)
                                        if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                                            val ts = stA.timestamp
                                            if (ts != lastSeenTexTimestampA) {
                                                lastSeenTexTimestampA = ts
                                                onNewFrameLatchedSlotA(ts)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.standby_stA", e)
                                    }
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
                                        safeRequestRender()
                                    }
                                    1 -> {
                                        fsA.startCodecAndSeek(nextClipInUs)
                                        safeRequestRender()
                                    }
                                    2 -> {
                                        val ready = fsA.stepPrewarmTo(nextClipInUs)
                                        if (ready || pendingSurfaceFramesA.getAndSet(0) > 0) {
                                            try {
                                                stA.updateTexImage()
                                                stA.getTransformMatrix(rawMatA)
                                                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                                                    val ts = stA.timestamp
                                                    if (ts != lastSeenTexTimestampA) {
                                                        lastSeenTexTimestampA = ts
                                                        onNewFrameLatchedSlotA(ts)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame.standby_stA", e)
                                            }
                                            val nextTransform = getClipTransform(nextClip.id)
                                            System.arraycopy(rawMatA, 0, matA, 0, 16)
                                            applyTransformMatrix(matA, nextTransform, nextClip.displayWidth, nextClip.displayHeight)
                                            calculateClipCrop(nextClip.displayWidth, nextClip.displayHeight, nextTransform, cropA)
                                            standbySlotReadyForClipId = nextClip.id
                                            standbyPreparedPath = nextClip.path
                                            standbyPreparedInUs = nextClipInUs
                                        } else {
                                            safeRequestRender()
                                        }
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
            val baseStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) System.nanoTime() else 0L
            GlPassScope.runPass(
                passName = "BaseVideoPassSlotB",
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                enableBlend = false,
                enableScissor = needsScissor,
                scissorRect = if (needsScissor) scissorRect else null
            ) {
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

                resolveFilterAndEffect(clipA, frame.tUs)
                val drawCpuStartNs = if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) System.nanoTime() else 0L
                PreviewEventLog.onVideoDrawn()
                renderer.draw(drawSpec)
                if (com.flowcut.app.BuildConfig.PERF_TELEMETRY && playhead.mode == PlayMode.PLAYING) {
                    val drawCpuDur = System.nanoTime() - drawCpuStartNs
                    framePerfDrawCpuTotalNs += drawCpuDur
                    if (drawCpuDur > framePerfDrawCpuMaxNs) framePerfDrawCpuMaxNs = drawCpuDur
                    framePerfDrawCpuCount++
                }
            }

            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                val baseDur = System.nanoTime() - baseStartNs
                passPerfBasePassTotalNs += baseDur
                if (baseDur > passPerfBasePassMaxNs) passPerfBasePassMaxNs = baseDur
                passPerfBasePassCount++
            }

            renderOverlaysStage(frame.tUs)

            val isTransition = frame.isTransition && frame.clipBIndex >= 0
            val isCatchingUp = checkIsCatchingUp(frame, isTransition)

            if (isCatchingUp) {
                // Only show loader spinner when paused (not during scrubbing/flinging)
                if (playhead.mode == PlayMode.PAUSED) {
                    isWaitingForFrame = true
                    val activelyCatchingUp = (frameSourceA?.isCatchingUpFromSeek == true) || (frameSourceB?.isCatchingUpFromSeek == true)
                    if (activelyCatchingUp) {
                        safeRequestRender()
                    }
                } else {
                    safeRequestRender()
                }
            } else {
                // No pending surface frame here. Do NOT clear isWaitingForFrame.
                // Only onFrameAvailable and a successful updateTexImage may clear it.
            }
        }
        } catch (e: Throwable) {
            PreviewEventLog.logSwallowedException("PreviewRenderer.onDrawFrame", e)
            PreviewEventLog.onBlackoutClearBranch("exception_caught_onDrawFrame", e.javaClass.simpleName)
            throw e
        } finally {
            PreviewWatchdog.onDrawFrameEnd()
            val finalWaiting = isWaitingForFrame
            val activelyCatchingUp = (frameSourceA?.isCatchingUpFromSeek == true) || (frameSourceB?.isCatchingUpFromSeek == true)
            if (wasWaitingInitial && !finalWaiting) {
                safeRequestRender()
            } else if (finalWaiting && (playhead.mode != PlayMode.PAUSED || activelyCatchingUp)) {
                safeRequestRender()
            }

            if (com.flowcut.app.BuildConfig.PERF_TELEMETRY) {
                val nowNs = System.nanoTime()
                val isPlaying = playhead.mode == PlayMode.PLAYING
                if (isPlaying) {
                    val wholeDurNs = nowNs - startNs
                    framePerfWholeDrawTotalNs += wholeDurNs
                    if (wholeDurNs > framePerfWholeDrawMaxNs) framePerfWholeDrawMaxNs = wholeDurNs
                    framePerfWholeDrawCount++

                    if (showedNewFrame) {
                        framePerfNewFramesCount++
                    } else {
                        framePerfRepeatedFramesCount++
                    }

                    if (lastFramePerfLogNs == 0L) {
                        lastFramePerfLogNs = nowNs
                    } else if (nowNs - lastFramePerfLogNs >= 2_000_000_000L) {
                        val wholeAvgMs = if (framePerfWholeDrawCount > 0L) (framePerfWholeDrawTotalNs.toDouble() / framePerfWholeDrawCount) / 1_000_000.0 else 0.0
                        val wholeMaxMs = framePerfWholeDrawMaxNs / 1_000_000.0
                        val decAvgMs = if (framePerfDecoderWaitCount > 0L) (framePerfDecoderWaitTotalNs.toDouble() / framePerfDecoderWaitCount) / 1_000_000.0 else 0.0
                        val decMaxMs = framePerfDecoderWaitMaxNs / 1_000_000.0
                        val texAvgMs = if (framePerfUpdateTexCount > 0L) (framePerfUpdateTexTotalNs.toDouble() / framePerfUpdateTexCount) / 1_000_000.0 else 0.0
                        val texMaxMs = framePerfUpdateTexMaxNs / 1_000_000.0
                        val drawAvgMs = if (framePerfDrawCpuCount > 0L) (framePerfDrawCpuTotalNs.toDouble() / framePerfDrawCpuCount) / 1_000_000.0 else 0.0
                        val drawMaxMs = framePerfDrawCpuMaxNs / 1_000_000.0
                        val intervalAvgMs = if (framePerfIntervalCount > 0L) (framePerfIntervalTotalNs.toDouble() / framePerfIntervalCount) / 1_000_000.0 else 0.0
                        val intervalMaxMs = framePerfIntervalMaxNs / 1_000_000.0

                        // Definition: "new" frame count represents onDrawFrame calls that latched a new SurfaceTexture frame
                        // (detected by SurfaceTexture.timestamp change after updateTexImage), whereas "repeated" draws re-rendered the previous frame.
                        val activeFs = if (primarySlot == 0) frameSourceA else frameSourceB
                        val clipFps = activeFs?.clip?.frameRate ?: (holder.current.project.clips.firstOrNull()?.frameRate ?: 30)
                        val approxDisplayHz = if (intervalAvgMs > 5.0) 1000.0 / intervalAvgMs else 60.0
                        val expectedNew = ((clipFps / approxDisplayHz) * framePerfWholeDrawCount).toLong().coerceIn(0L, framePerfWholeDrawCount)

                        framePerfSb.setLength(0)
                        framePerfSb.append("draws=").append(framePerfWholeDrawCount)
                            .append(" (new=").append(framePerfNewFramesCount)
                            .append(", repeated=").append(framePerfRepeatedFramesCount)
                            .append(", expectedNew=").append(expectedNew)
                            .append(") | whole: avg=").append(String.format(java.util.Locale.US, "%.2f", wholeAvgMs)).append("ms max=").append(String.format(java.util.Locale.US, "%.2f", wholeMaxMs)).append("ms")
                            .append(" | decWait: avg=").append(String.format(java.util.Locale.US, "%.2f", decAvgMs)).append("ms max=").append(String.format(java.util.Locale.US, "%.2f", decMaxMs)).append("ms")
                            .append(" | texUpdate: avg=").append(String.format(java.util.Locale.US, "%.2f", texAvgMs)).append("ms max=").append(String.format(java.util.Locale.US, "%.2f", texMaxMs)).append("ms")
                            .append(" | drawCpu: avg=").append(String.format(java.util.Locale.US, "%.2f", drawAvgMs)).append("ms max=").append(String.format(java.util.Locale.US, "%.2f", drawMaxMs)).append("ms")
                            .append(" | interval: avg=").append(String.format(java.util.Locale.US, "%.2f", intervalAvgMs)).append("ms max=").append(String.format(java.util.Locale.US, "%.2f", intervalMaxMs)).append("ms")
                            .append(" (>20ms=").append(framePerfIntervalGt20ms)
                            .append(", >33ms=").append(framePerfIntervalGt33ms).append(")")

                        try {
                            android.util.Log.d("FramePerf", framePerfSb.toString())
                        } catch (_: Throwable) {
                            println("[FramePerf] $framePerfSb")
                        }

                        framePerfDecoderWaitTotalNs = 0L
                        framePerfDecoderWaitMaxNs = 0L
                        framePerfDecoderWaitCount = 0L
                        framePerfUpdateTexTotalNs = 0L
                        framePerfUpdateTexMaxNs = 0L
                        framePerfUpdateTexCount = 0L
                        framePerfDrawCpuTotalNs = 0L
                        framePerfDrawCpuMaxNs = 0L
                        framePerfDrawCpuCount = 0L
                        framePerfWholeDrawTotalNs = 0L
                        framePerfWholeDrawMaxNs = 0L
                        framePerfWholeDrawCount = 0L
                        framePerfIntervalTotalNs = 0L
                        framePerfIntervalMaxNs = 0L
                        framePerfIntervalCount = 0L
                        framePerfIntervalGt20ms = 0L
                        framePerfIntervalGt33ms = 0L
                        framePerfNewFramesCount = 0L
                        framePerfRepeatedFramesCount = 0L
                        lastFramePerfLogNs = nowNs
                    }
                } else {
                    lastFramePerfLogNs = 0L
                    framePerfLastFrameStartNs = 0L
                    framePerfLoggedPlaybackStart = false
                }

                // PassPerf "late" counter definition (Rule R10 / Step A.4):
                // Note: The existing `passPerfFramesLate` below is defined as DRAW EXECUTION DURATION
                // of onDrawFrame() exceeding 16.666 ms (durationUs > 16_666L), measuring CPU/GPU submission
                // time of a single frame. It does NOT measure pacing/cadence (intervals between frame starts).
                // Cadence intervals are measured above in FramePerf (>20ms and >33ms).
                val durationUs = (nowNs - startNs) / 1000L
                passPerfFramesDrawn++
                if (durationUs > 16_666L) {
                    passPerfFramesLate++
                }
                if (lastPassPerfLogNs == 0L) {
                    lastPassPerfLogNs = nowNs
                } else if (nowNs - lastPassPerfLogNs >= 2_000_000_000L) {
                    passPerfSb.setLength(0)
                    val baseAvgMs = if (passPerfBasePassCount > 0L) (passPerfBasePassTotalNs.toDouble() / passPerfBasePassCount) / 1_000_000.0 else 0.0
                    val baseMaxMs = passPerfBasePassMaxNs / 1_000_000.0
                    passPerfSb.append("frames=").append(passPerfFramesDrawn)
                        .append(", late=").append(passPerfFramesLate)
                        .append(", base: avg=").append(String.format(java.util.Locale.US, "%.2f", baseAvgMs)).append("ms max=").append(String.format(java.util.Locale.US, "%.2f", baseMaxMs)).append("ms")
                    overlayStagePass.appendDebugTimings(passPerfSb)
                    try {
                        android.util.Log.d("PassPerf", passPerfSb.toString())
                    } catch (_: Throwable) {
                        println("[PassPerf] $passPerfSb")
                    }
                    passPerfFramesDrawn = 0L
                    passPerfFramesLate = 0L
                    passPerfBasePassTotalNs = 0L
                    passPerfBasePassMaxNs = 0L
                    passPerfBasePassCount = 0L
                    overlayStagePass.resetWindowTimings()
                    lastPassPerfLogNs = nowNs
                }
                PacePerfTracker.maybeLog(nowNs, context, glSurfaceView?.display, playhead.mode)
            }
        }
    }

    internal fun resolveFilterAndEffect(clip: com.flowcut.app.core.model.Clip?, tUs: Long) {
        // Note: Transition branch asymmetry currently resolves and applies clipA's filter across the entire transition pass (Step 15: intentionally preserved, to be addressed in a future task).
        val resolved = resolveFilterAndEffectState(
            clip = clip,
            liveOverride = if (clip?.id != null && liveFilterOverrides.isNotEmpty()) liveFilterOverrides[clip.id] else null,
            plan = currentLayerPlan,
            tUs = tUs,
            activeLayerSet = activeLayerSet
        )
        drawSpec.filterPresetId = resolved.filterPresetId
        drawSpec.filterIntensity = resolved.filterIntensity
        drawSpec.effectPresetId = resolved.effectPresetId
        drawSpec.effectIntensity = resolved.effectIntensity
        drawSpec.timeSec = (tUs.toDouble() / 1_000_000.0).toFloat()
    }

    private fun renderOverlaysStage(tUs: Long) {
        val vTex = if (lastActiveVideoTexId > 0) lastActiveVideoTexId else renderer.textureIdA()
        baseFrameInfo.update(vTex, lastActiveVideoMat, lastActiveCrop)
        passCtx.tUs = tUs
        passCtx.snapshot = holder.current
        passCtx.activeSet = activeSet
        passCtx.outputWidth = viewportWidth
        passCtx.outputHeight = viewportHeight
        passRegistry.drawAll(passCtx)
    }



    private fun releaseDecoders(synchronous: Boolean = false) {
        isFirstFrameLatched = false
        pendingSurfaceFramesA.set(0)
        pendingSurfaceFramesB.set(0)
        frameSourceA?.release(synchronous = synchronous)
        frameSourceA = null
        currentClipIndexA = -1

        frameSourceB?.release(synchronous = synchronous)
        frameSourceB = null
        currentClipIndexB = -1

        primarySlot = 0
        standbySlotReadyForClipId = null
        standbyPreparedPath = null
        standbyPreparedInUs = -1L
        lastTransitionIncomingSlot = -1
        lastTransitionIncomingClipIndex = -1
        lastRenderedTransitionJunction = -1
        smoothedTransitionP = 0f
        Matrix.setIdentityM(rawMatA, 0)
        Matrix.setIdentityM(rawMatB, 0)
        ResourceTracker.logAliveCounts("releaseDecoders")
    }

    fun invalidateDecoders() {
        val gl = glSurfaceView
        if (gl != null) {
            gl.queueEvent {
                releaseDecoders(synchronous = true)
                recreateSurfaceTextures()
                glSurfaceView?.requestRender()
                onRenderRequested?.invoke()
            }
        } else {
            releaseDecoders(synchronous = true)
            recreateSurfaceTextures()
            glSurfaceView?.requestRender()
            onRenderRequested?.invoke()
        }
    }

    fun suspendForExport() {
        isSuspendedForExport = true
        val gl = glSurfaceView
        if (gl != null) {
            gl.queueEvent {
                releaseDecoders(synchronous = true)
            }
        } else {
            releaseDecoders(synchronous = true)
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
        releaseDecoders(synchronous = true)
        try {
            stCallbackThread.quitSafely()
        } catch (_: Throwable) {}
        surfaceTextureA?.setOnFrameAvailableListener(null)
        surfaceTextureA?.release()
        ResourceTracker.onReleased(ResourceTracker.ResourceType.SURFACE_TEXTURE, "stA")
        surfaceTextureA = null
        surfaceTextureB?.setOnFrameAvailableListener(null)
        surfaceTextureB?.release()
        ResourceTracker.onReleased(ResourceTracker.ResourceType.SURFACE_TEXTURE, "stB")
        surfaceTextureB = null
        pendingSurfaceFramesA.set(0)
        pendingSurfaceFramesB.set(0)
        PreviewWatchdog.stop()
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
        passRegistry.onGlDestroyed()
    }

    fun registerPass(pass: RenderPass) {
        passRegistry.register(pass)
    }

    fun unregisterPass(passId: String) {
        passRegistry.unregister(passId)
    }

    fun registerLayerRenderer(r: LayerRenderer) {
        overlayStagePass.registerLayerRenderer(r)
    }

    fun unregisterLayerRenderer(id: String) {
        overlayStagePass.unregisterLayerRenderer(id)
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

        val liveFilterOverrides = ConcurrentHashMap<String, ClipFilterState>()

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

        fun getClipFilterState(clipId: String?): ClipFilterState {
            if (clipId == null) return ClipFilterState.DEFAULT
            return liveFilterOverrides[clipId]
                ?: activeInstance?.holder?.current?.project?.clips?.find { it.id == clipId }?.renderParams?.filter
                ?: ClipFilterState.DEFAULT
        }

        fun setClipFilter(clipId: String, presetId: Int, intensity: Float) {
            val existing = getClipFilterState(clipId)
            liveFilterOverrides[clipId] = existing.copy(filterPresetId = presetId, filterIntensity = intensity)
        }

        fun setClipEffect(clipId: String, presetId: Int, intensity: Float) {
            val existing = getClipFilterState(clipId)
            liveFilterOverrides[clipId] = existing.copy(effectPresetId = presetId, effectIntensity = intensity)
        }

        fun resolveFilterAndEffectState(
            clip: com.flowcut.app.core.model.Clip?,
            liveOverride: ClipFilterState? = null,
            plan: CompiledLayerPlan? = null,
            tUs: Long = 0L,
            activeLayerSet: ActiveLayerSet? = null
        ): ClipFilterState {
            val clipId = clip?.id
            val st = liveOverride
                ?: (if (clipId != null && liveFilterOverrides.isNotEmpty()) liveFilterOverrides[clipId] else null)
                ?: clip?.renderParams?.filter
                ?: com.flowcut.app.core.model.ClipFilterParams.DEFAULT
            var filterId = st.filterPresetId
            var filterInt = st.filterIntensity
            var effectId = st.effectPresetId
            var effectInt = st.effectIntensity

            if (plan != null && plan.segments.isNotEmpty() && activeLayerSet != null) {
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
            return com.flowcut.app.core.model.ClipFilterParams(
                filterPresetId = filterId,
                filterIntensity = filterInt,
                effectPresetId = effectId,
                effectIntensity = effectInt
            )
        }

        val junctionTransitionTypes = ConcurrentHashMap<Int, Int>()

        fun resetAll() {
            clipTransforms.clear()
            clipColorAdjusts.clear()
            clipBackgrounds.clear()
            clearLiveFilterOverride()
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

            val rad = Math.toRadians(normRot.toDouble())
            val cos = Math.abs(Math.cos(rad)).toFloat()
            val sin = Math.abs(Math.sin(rad)).toFloat()

            val maxBaseW = (0.5f * aScreen) / (videoAspect * cos + sin).coerceAtLeast(0.0001f)
            val maxBaseH = 0.5f / (videoAspect * sin + cos).coerceAtLeast(0.0001f)
            val baseSize = minOf(maxBaseW, maxBaseH)

            val w_v = baseSize * videoAspect
            val h_v = baseSize

            val scale = transform.scale.coerceIn(0.1f, 10.0f)
            val hw = ((w_v * cos + h_v * sin) / aScreen) * scale
            val hh = (w_v * sin + h_v * cos) * scale

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

            val rad = Math.toRadians(transform.rotationDeg.toDouble())
            val cos = Math.abs(Math.cos(rad)).toFloat()
            val sin = Math.abs(Math.sin(rad)).toFloat()

            // To fit the video inside the canvas with NO cropping:
            // Bounding box of rotated video in isotropic space must fit within canvas half-width (0.5 * aScreen) and half-height (0.5):
            // bbox_w = baseSize * (videoAspect * cos + 1.0f * sin) <= 0.5 * aScreen
            // bbox_h = baseSize * (videoAspect * sin + 1.0f * cos) <= 0.5
            val maxBaseW = (0.5f * aScreen) / (videoAspect * cos + sin).coerceAtLeast(0.0001f)
            val maxBaseH = 0.5f / (videoAspect * sin + cos).coerceAtLeast(0.0001f)
            val baseSize = minOf(maxBaseW, maxBaseH)

            val w_v = baseSize * videoAspect
            val h_v = baseSize

            val scale_vid_x = 0.5f / w_v
            val scale_vid_y = 0.5f / h_v

            Matrix.translateM(outMat, 0, 0.5f, 0.5f, 0f)
            Matrix.scaleM(outMat, 0, scale_vid_x, scale_vid_y, 1f)
            val sInv = 1.0f / transform.scale.coerceIn(0.1f, 10.0f)
            Matrix.scaleM(outMat, 0, sInv, sInv, 1f)
            if (transform.rotationDeg != 0f) {
                Matrix.rotateM(outMat, 0, transform.rotationDeg, 0f, 0f, 1f)
            }
            if (transform.isFlippedH) {
                Matrix.scaleM(outMat, 0, -1f, 1f, 1f)
            }
            Matrix.scaleM(outMat, 0, aScreen, 1.0f, 1f)
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

            val rad = Math.toRadians(rot.toDouble())
            val cos = Math.abs(Math.cos(rad)).toFloat()
            val sin = Math.abs(Math.sin(rad)).toFloat()

            // For background COVER mode:
            // Bounding box of rotated video in isotropic space must be >= canvas size (0.5 * aScreen, 0.5):
            val minBaseW = (0.5f * aScreen) / (videoAspect * cos + sin).coerceAtLeast(0.0001f)
            val minBaseH = 0.5f / (videoAspect * sin + cos).coerceAtLeast(0.0001f)
            val bgBaseSize = maxOf(minBaseW, minBaseH)

            val bg_w_v = bgBaseSize * videoAspect
            val bg_h_v = bgBaseSize

            val scale_bg_x = 0.5f / bg_w_v
            val scale_bg_y = 0.5f / bg_h_v

            Matrix.translateM(outMat, 0, 0.5f, 0.5f, 0f)
            Matrix.scaleM(outMat, 0, scale_bg_x, scale_bg_y, 1f)
            if (rot != 0) {
                Matrix.rotateM(outMat, 0, rot.toFloat(), 0f, 0f, 1f)
            }
            if (transform.isFlippedH) {
                Matrix.scaleM(outMat, 0, -1f, 1f, 1f)
            }
            Matrix.scaleM(outMat, 0, aScreen, 1.0f, 1f)
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
            out2[2] = adjust.clarity
            out2[3] = adjust.sharpPlus
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

            val rad = Math.toRadians(transform.rotationDeg.toDouble())
            val cos = Math.abs(Math.cos(rad)).toFloat()
            val sin = Math.abs(Math.sin(rad)).toFloat()

            val maxBaseW = (0.5f * aScreen) / (videoAspect * cos + sin).coerceAtLeast(0.0001f)
            val maxBaseH = 0.5f / (videoAspect * sin + cos).coerceAtLeast(0.0001f)
            val baseSize = minOf(maxBaseW, maxBaseH)

            val w_v = baseSize * videoAspect
            val h_v = baseSize

            val scale = transform.scale.coerceIn(0.25f, 5.0f)
            val rx = ((w_v * cos + h_v * sin) / aScreen) * scale
            val ry = (w_v * sin + h_v * cos) * scale

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
            val effDurMs = maxOf(clip.durationMs, usToMs(clip.timeMap?.outUs ?: 0L))
            return com.flowcut.app.model.Clip(
                id = clip.id,
                path = clip.path,
                durationMs = effDurMs,
                width = clip.width,
                height = clip.height,
                rotation = clip.rotation,
                frameRate = clip.frameRate
            )
        }
    }
}
