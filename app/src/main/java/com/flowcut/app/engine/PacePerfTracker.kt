package com.flowcut.app.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.Display
import com.flowcut.app.BuildConfig
import com.flowcut.app.core.playback.PlayMode
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class RenderRequestCaller {
    FRAME_DRIVER_TICK,
    ON_FRAME_AVAILABLE,
    LAUNCHED_EFFECT_PROJECT,
    SEEK,
    OTHER
}

/**
 * Step 10 PacePerf telemetry tracker (Rule R10: Zero heap allocations on hot path).
 * Gathers timing and pacing metrics across FrameDriver (main thread), PreviewRenderer (GL thread),
 * and FrameSource (MediaCodec decoder) and logs once per 2 s with tag "PacePerf" while PLAYING.
 */
internal object PacePerfTracker {

    // 1. FrameDriver.doFrame Choreographer intervals and execution durations (Main thread)
    @Volatile private var lastChoreographerNanos: Long = 0L
    @Volatile private var doFrameIntervalTotalNs: Long = 0L
    @Volatile private var doFrameIntervalMaxNs: Long = 0L
    @Volatile private var doFrameIntervalCount: Long = 0L
    @Volatile private var doFrameIntervalGt20ms: Long = 0L
    @Volatile private var doFrameIntervalGt33ms: Long = 0L

    @Volatile private var doFrameDurTotalNs: Long = 0L
    @Volatile private var doFrameDurMaxNs: Long = 0L
    @Volatile private var doFrameDurCount: Long = 0L

    // 2. RequestRender to onDrawFrame Latency
    @Volatile private var lastRequestRenderNs: Long = 0L
    @Volatile private var reqToDrawTotalNs: Long = 0L
    @Volatile private var reqToDrawMaxNs: Long = 0L
    @Volatile private var reqToDrawCount: Long = 0L

    // 3. Render request counts per 2 s by caller
    private val reqTickCount = AtomicInteger(0)
    private val reqFrameAvailCount = AtomicInteger(0)
    private val reqProjectCount = AtomicInteger(0)
    private val reqSeekCount = AtomicInteger(0)
    private val reqOtherCount = AtomicInteger(0)

    // 4. Decoder metrics per 2 s (GL thread inside FrameSource)
    @Volatile private var decOutputRenderedCount: Long = 0L
    @Volatile private var decOutputDroppedCount: Long = 0L
    @Volatile private var decInputQueuedCount: Long = 0L
    @Volatile private var decTryAgainLaterCount: Long = 0L
    @Volatile private var decMaxPerCallCount: Long = 0L

    // Logging timer & pre-allocated buffer
    @Volatile private var lastPacePerfLogNs: Long = 0L
    private val pacePerfSb = StringBuilder(512)

    fun onChoreographerFrame(frameTimeNanos: Long, mode: PlayMode) {
        if (!BuildConfig.PERF_TELEMETRY || mode != PlayMode.PLAYING) {
            lastChoreographerNanos = 0L
            return
        }
        if (lastChoreographerNanos > 0L) {
            val interval = frameTimeNanos - lastChoreographerNanos
            doFrameIntervalTotalNs += interval
            if (interval > doFrameIntervalMaxNs) doFrameIntervalMaxNs = interval
            doFrameIntervalCount++
            if (interval > 20_000_000L) doFrameIntervalGt20ms++
            if (interval > 33_333_333L) doFrameIntervalGt33ms++
        }
        lastChoreographerNanos = frameTimeNanos
    }

    fun onDoFrameDuration(durNs: Long, mode: PlayMode) {
        if (!BuildConfig.PERF_TELEMETRY || mode != PlayMode.PLAYING) return
        doFrameDurTotalNs += durNs
        if (durNs > doFrameDurMaxNs) doFrameDurMaxNs = durNs
        doFrameDurCount++
    }

    fun onRequestRender(caller: RenderRequestCaller, mode: PlayMode) {
        if (!BuildConfig.PERF_TELEMETRY || mode != PlayMode.PLAYING) return
        lastRequestRenderNs = System.nanoTime()
        when (caller) {
            RenderRequestCaller.FRAME_DRIVER_TICK -> reqTickCount.incrementAndGet()
            RenderRequestCaller.ON_FRAME_AVAILABLE -> reqFrameAvailCount.incrementAndGet()
            RenderRequestCaller.LAUNCHED_EFFECT_PROJECT -> reqProjectCount.incrementAndGet()
            RenderRequestCaller.SEEK -> reqSeekCount.incrementAndGet()
            RenderRequestCaller.OTHER -> reqOtherCount.incrementAndGet()
        }
    }

    fun onDrawFrameStart(mode: PlayMode) {
        if (!BuildConfig.PERF_TELEMETRY || mode != PlayMode.PLAYING) return
        val reqNs = lastRequestRenderNs
        if (reqNs > 0L) {
            val latencyNs = System.nanoTime() - reqNs
            if (latencyNs > 0L) {
                reqToDrawTotalNs += latencyNs
                if (latencyNs > reqToDrawMaxNs) reqToDrawMaxNs = latencyNs
                reqToDrawCount++
            }
        }
    }

    fun onDecoderFrameAtResult(
        rendered: Boolean,
        dropped: Long,
        queued: Long,
        tryAgain: Long,
        decodedThisCall: Long,
        mode: PlayMode
    ) {
        if (!BuildConfig.PERF_TELEMETRY || mode != PlayMode.PLAYING) return
        if (rendered) decOutputRenderedCount++
        decOutputDroppedCount += dropped
        decInputQueuedCount += queued
        decTryAgainLaterCount += tryAgain
        if (decodedThisCall > decMaxPerCallCount) {
            decMaxPerCallCount = decodedThisCall
        }
    }

    fun maybeLog(nowNs: Long, context: Context?, display: Display?, mode: PlayMode) {
        if (!BuildConfig.PERF_TELEMETRY || mode != PlayMode.PLAYING) {
            lastPacePerfLogNs = 0L
            return
        }
        if (lastPacePerfLogNs == 0L) {
            lastPacePerfLogNs = nowNs
            return
        }
        if (nowNs - lastPacePerfLogNs < 2_000_000_000L) return

        val chAvg = if (doFrameIntervalCount > 0) (doFrameIntervalTotalNs.toDouble() / doFrameIntervalCount) / 1_000_000.0 else 0.0
        val chMax = doFrameIntervalMaxNs / 1_000_000.0
        val dfAvg = if (doFrameDurCount > 0) (doFrameDurTotalNs.toDouble() / doFrameDurCount) / 1_000_000.0 else 0.0
        val dfMax = doFrameDurMaxNs / 1_000_000.0
        val latAvg = if (reqToDrawCount > 0) (reqToDrawTotalNs.toDouble() / reqToDrawCount) / 1_000_000.0 else 0.0
        val latMax = reqToDrawMaxNs / 1_000_000.0

        val refreshRate = display?.refreshRate ?: 60f
        val modesStr = if (Build.VERSION.SDK_INT >= 23 && display != null) {
            display.supportedModes.joinToString(", ") { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate.toInt()}Hz" }
        } else {
            "${refreshRate.toInt()}Hz"
        }
        val isDebuggable = context?.let { (it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 } ?: false

        pacePerfSb.setLength(0)
        pacePerfSb.append("Choreographer: interval avg=").append(String.format(Locale.US, "%.2f", chAvg)).append("ms max=").append(String.format(Locale.US, "%.2f", chMax)).append("ms")
            .append(" (>20ms=").append(doFrameIntervalGt20ms)
            .append(", >33ms=").append(doFrameIntervalGt33ms).append(")")
            .append(" | doFrame: avg=").append(String.format(Locale.US, "%.2f", dfAvg)).append("ms max=").append(String.format(Locale.US, "%.2f", dfMax)).append("ms")
            .append(" | reqToDraw: avg=").append(String.format(Locale.US, "%.2f", latAvg)).append("ms max=").append(String.format(Locale.US, "%.2f", latMax)).append("ms")
            .append(" | reqs: tick=").append(reqTickCount.get())
            .append(", onFrameAvail=").append(reqFrameAvailCount.get())
            .append(", coreProject=").append(reqProjectCount.get())
            .append(", seek=").append(reqSeekCount.get())
            .append(", other=").append(reqOtherCount.get())
            .append(" | decoder: rendered=").append(decOutputRenderedCount)
            .append(", dropped=").append(decOutputDroppedCount)
            .append(", queued=").append(decInputQueuedCount)
            .append(", tryAgainLater=").append(decTryAgainLaterCount)
            .append(", maxPerCall=").append(decMaxPerCallCount)
            .append(" | display: ").append(String.format(Locale.US, "%.1f", refreshRate)).append("Hz (modes: ").append(modesStr).append(")")
            .append(", buildType=").append(BuildConfig.BUILD_TYPE)
            .append(", debuggable=").append(isDebuggable).append(" (buildConfig=").append(BuildConfig.DEBUG).append(")")

        try {
            android.util.Log.d("PacePerf", pacePerfSb.toString())
        } catch (_: Throwable) {
            println("[PacePerf] $pacePerfSb")
        }

        // Reset counters for next 2 s window
        doFrameIntervalTotalNs = 0L
        doFrameIntervalMaxNs = 0L
        doFrameIntervalCount = 0L
        doFrameIntervalGt20ms = 0L
        doFrameIntervalGt33ms = 0L
        doFrameDurTotalNs = 0L
        doFrameDurMaxNs = 0L
        doFrameDurCount = 0L

        reqToDrawTotalNs = 0L
        reqToDrawMaxNs = 0L
        reqToDrawCount = 0L

        reqTickCount.set(0)
        reqFrameAvailCount.set(0)
        reqProjectCount.set(0)
        reqSeekCount.set(0)
        reqOtherCount.set(0)

        decOutputRenderedCount = 0L
        decOutputDroppedCount = 0L
        decInputQueuedCount = 0L
        decTryAgainLaterCount = 0L
        decMaxPerCallCount = 0L

        lastPacePerfLogNs = nowNs
    }
}
