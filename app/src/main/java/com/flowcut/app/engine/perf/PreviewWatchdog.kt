package com.flowcut.app.engine.perf

import android.os.Looper
import android.util.Log
import com.flowcut.app.BuildConfig
import com.flowcut.app.core.playback.PlayMode

interface PreviewWatchdogStateProvider {
    val playMode: PlayMode
    val generation: Int
    val slotAInfo: String
    val slotBInfo: String
    val pendingSurfaceFramesCountA: Int
    val pendingSurfaceFramesCountB: Int
    val pendingSeekInfo: String
    val isSuspended: Boolean
    val stateMachineState: String
}

/**
 * Low-priority background watchdog thread monitoring GL thread health, deadlocks,
 * render stalls, blackouts, and flushes (Step 16, Step C). Guarded by BuildConfig.PERF_TELEMETRY.
 */
object PreviewWatchdog {

    @Volatile var glThread: Thread? = null

    @Volatile private var glDrawStartNs: Long = 0L
    @Volatile private var glDrawCompletedNs: Long = 0L
    @Volatile private var isRenderPending: Boolean = false
    @Volatile private var renderRequestedNs: Long = 0L

    @Volatile private var lastDumpMs: Long = 0L
    @Volatile private var lastResourceLogMs: Long = 0L

    @Volatile private var stateProvider: PreviewWatchdogStateProvider? = null

    private val watchdogLock = Any()
    @Volatile private var running: Boolean = false
    private var watchdogThread: Thread? = null

    fun start(provider: PreviewWatchdogStateProvider) {
        stateProvider = provider
        if (!BuildConfig.PERF_TELEMETRY) return
        if (running) return
        running = true
        watchdogThread = Thread({
            while (running) {
                try {
                    synchronized(watchdogLock) {
                        (watchdogLock as java.lang.Object).wait(250L)
                    }
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                tick()
            }
        }, "FlowCut-PreviewWatchdog").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        synchronized(watchdogLock) {
            (watchdogLock as java.lang.Object).notifyAll()
        }
        watchdogThread = null
    }

    fun onDrawFrameStart() {
        if (!BuildConfig.PERF_TELEMETRY) return
        if (glThread == null) {
            glThread = Thread.currentThread()
        }
        glDrawStartNs = System.nanoTime()
    }

    fun onDrawFrameEnd() {
        if (!BuildConfig.PERF_TELEMETRY) return
        glDrawStartNs = 0L
        glDrawCompletedNs = System.nanoTime()
        isRenderPending = false
    }

    fun onRequestRender() {
        if (!BuildConfig.PERF_TELEMETRY) return
        isRenderPending = true
        renderRequestedNs = System.nanoTime()
    }

    fun onActivityPauseOrStop(caller: String = "Activity onPause/onStop") {
        if (!BuildConfig.PERF_TELEMETRY) return
        dump("Triggered by $caller", force = true)
    }

    private fun tick() {
        if (!BuildConfig.PERF_TELEMETRY) return
        val nowMs = PreviewEventLog.currentMonotonicMs()
        val nowNs = System.nanoTime()

        // 1. Periodic alive resource log every 2 seconds
        if (nowMs - lastResourceLogMs >= 2000L) {
            lastResourceLogMs = nowMs
            ResourceTracker.logAliveCounts("2s periodic")
        }

        val provider = stateProvider ?: return

        // 2. Heartbeat condition 1: render requested or PLAYING and no frame completed for > 1 s
        val mode = provider.playMode
        val isStalledCompletion = (isRenderPending || mode == PlayMode.PLAYING) &&
                (glDrawCompletedNs > 0L && (nowNs - glDrawCompletedNs > 1_000_000_000L))

        // 3. Heartbeat condition 2: GL thread inside one onDrawFrame for > 1 s
        val drawStart = glDrawStartNs
        val isStuckInDraw = drawStart > 0L && (nowNs - drawStart > 1_000_000_000L)

        // 4. Blackout condition: blackout branch active > 500 ms
        val blackoutDuration = PreviewEventLog.totalConsecutiveBlackoutMs
        val isBlackoutStall = blackoutDuration > 500L

        if (isStalledCompletion || isStuckInDraw || isBlackoutStall) {
            val reason = when {
                isStuckInDraw -> "GL thread inside onDrawFrame for > 1 s (${(nowNs - drawStart) / 1_000_000L} ms)"
                isStalledCompletion -> "No onDrawFrame completed for > 1 s (mode=$mode, renderPending=$isRenderPending)"
                else -> "Blackout branch active for > 500 ms (${blackoutDuration} ms)"
            }
            dump(reason)
        }
    }

    fun dump(triggerReason: String, force: Boolean = false) {
        val nowMs = PreviewEventLog.currentMonotonicMs()
        if (!force && (nowMs - lastDumpMs < 5000L)) {
            return
        }
        lastDumpMs = nowMs

        val provider = stateProvider
        val sb = java.lang.StringBuilder(4096)
        sb.append("=== PREVIEW WATCHDOG REPORT ===\n")
        sb.append("Trigger: ").append(triggerReason).append("\n")

        if (provider != null) {
            sb.append("-- Key State --\n")
            sb.append("PlayMode: ").append(provider.playMode).append("\n")
            sb.append("Generation: ").append(provider.generation).append("\n")
            sb.append("Slot A: ").append(provider.slotAInfo).append("\n")
            sb.append("Slot B: ").append(provider.slotBInfo).append("\n")
            sb.append("Pending Surface Frames: A=").append(provider.pendingSurfaceFramesCountA)
                .append(", B=").append(provider.pendingSurfaceFramesCountB).append("\n")
            sb.append("Pending Seek: ").append(provider.pendingSeekInfo).append("\n")
            sb.append("Suspended for Export: ").append(provider.isSuspended).append("\n")
            sb.append("State Machine: ").append(provider.stateMachineState).append("\n")
        }

        sb.append("-- Alive Resources --\n")
        sb.append("Surface=").append(ResourceTracker.getAliveCount(ResourceTracker.ResourceType.SURFACE))
            .append(", SurfaceTexture=").append(ResourceTracker.getAliveCount(ResourceTracker.ResourceType.SURFACE_TEXTURE))
            .append(", MediaCodec=").append(ResourceTracker.getAliveCount(ResourceTracker.ResourceType.MEDIA_CODEC))
            .append(", MediaExtractor=").append(ResourceTracker.getAliveCount(ResourceTracker.ResourceType.MEDIA_EXTRACTOR))
            .append(", AudioTrack=").append(ResourceTracker.getAliveCount(ResourceTracker.ResourceType.AUDIO_TRACK))
            .append(", MediaMetadataRetriever=").append(ResourceTracker.getAliveCount(ResourceTracker.ResourceType.MEDIA_METADATA_RETRIEVER))
            .append("\n")

        sb.append("-- GL Thread Stack Trace --\n")
        val glTrace = glThread?.stackTrace
        if (glTrace != null && glTrace.isNotEmpty()) {
            sb.append("GL Thread: ").append(glThread?.name).append(" (state=").append(glThread?.state).append(")\n")
            glTrace.take(15).forEach {
                sb.append("  at ").append(it).append("\n")
            }
        } else {
            sb.append("GL thread trace unavailable\n")
        }

        sb.append("-- Main Thread Stack Trace --\n")
        try {
            val mainTrace = Looper.getMainLooper()?.thread?.stackTrace
            if (mainTrace != null && mainTrace.isNotEmpty()) {
                mainTrace.take(15).forEach {
                    sb.append("  at ").append(it).append("\n")
                }
            } else {
                sb.append("Main thread trace unavailable\n")
            }
        } catch (_: Throwable) {
            sb.append("Main thread trace exception\n")
        }

        sb.append("-- Event Ring Log (Last 100 events) --\n")
        PreviewEventLog.dumpLastEventsFormatted(sb, 100)
        sb.append("=== END WATCHDOG REPORT ===")

        val report = sb.toString()
        try {
            Log.e("PreviewWatchdog", report)
        } catch (_: Throwable) {
            System.err.println(report)
        }
    }
}
