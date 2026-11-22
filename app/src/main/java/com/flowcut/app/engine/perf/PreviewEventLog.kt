package com.flowcut.app.engine.perf

import android.os.SystemClock
import android.util.Log
import com.flowcut.app.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class PreviewEventType {
    NONE,
    FRAMESOURCE_CREATE,
    FRAMESOURCE_CONFIGURE,
    FRAMESOURCE_START,
    FRAMESOURCE_FLUSH,
    FRAMESOURCE_STOP,
    FRAMESOURCE_RELEASE,
    FRAMESOURCE_REUSE,
    SEEK_ENTER,
    SEEK_SYNC,
    SEEK_RESULT,
    BLACKOUT_CLEAR,
    BLACKOUT_END,
    RESOURCE_CREATED,
    RESOURCE_RELEASED,
    STATE_MACHINE_TRANSITION,
    LOADER_SHOW,
    LOADER_HIDE,
    LOADING_INDICATOR_SHOWN,
    LOADING_INDICATOR_HIDDEN,
    NEW_FRAME_LATCHED,
    STALL_FLAG,
    EXCEPTION_SWALLOWED,
    GL_SURFACE_CREATED,
    GL_SURFACE_CHANGED,
    GL_SURFACE_PAUSE,
    GL_SURFACE_RESUME,
    ACTIVITY_LIFECYCLE,
    GL_LIFECYCLE,
    ANDROID_VIEW_LIFECYCLE,
    EGL_CONTEXT_LOSS
}

class LogEntry {
    @Volatile var timestampMs: Long = 0L
    @Volatile var threadId: Int = 0
    @Volatile var eventType: PreviewEventType = PreviewEventType.NONE
    @Volatile var slot: Int = -1
    @Volatile var clipId: String? = null
    @Volatile var arg0: Long = 0L
    @Volatile var arg1: Long = 0L
    @Volatile var reason: String? = null

    fun copyFrom(other: LogEntry) {
        this.timestampMs = other.timestampMs
        this.threadId = other.threadId
        this.eventType = other.eventType
        this.slot = other.slot
        this.clipId = other.clipId
        this.arg0 = other.arg0
        this.arg1 = other.arg1
        this.reason = other.reason
    }

    override fun toString(): String {
        return "[$timestampMs ms][T:$threadId][$eventType] slot=$slot clip=${clipId ?: "none"} arg0=$arg0 arg1=$arg1 reason=${reason ?: "none"}"
    }
}

/**
 * Preallocated, lock-free ring buffer tracking the last 512 preview and playback events (Step 16).
 * Zero per-frame heap allocations. Guarded by BuildConfig.PERF_TELEMETRY.
 */
object PreviewEventLog {
    const val RING_SIZE = 512
    private const val RING_MASK = 511

    private val entries = Array(RING_SIZE) { LogEntry() }
    private val writeIndex = AtomicLong(0L)

    @Volatile var isEnabledForTest: Boolean? = null
    val isEnabled: Boolean get() = isEnabledForTest ?: BuildConfig.PERF_TELEMETRY

    @Volatile private var blackoutStartMs: Long = 0L
    @Volatile var totalConsecutiveBlackoutMs: Long = 0L
        private set

    private val reportedExceptions = ConcurrentHashMap<String, Boolean>()

    val flushCounter = FlushRateCounter()

    fun currentMonotonicMs(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    fun log(
        eventType: PreviewEventType,
        slot: Int = -1,
        clipId: String? = null,
        arg0: Long = 0L,
        arg1: Long = 0L,
        reason: String? = null,
        timestampMs: Long = currentMonotonicMs()
    ) {
        if (!isEnabled) return
        val idx = (writeIndex.getAndIncrement() and RING_MASK.toLong()).toInt()
        val entry = entries[idx]
        entry.timestampMs = timestampMs
        entry.threadId = Thread.currentThread().id.toInt()
        entry.eventType = eventType
        entry.slot = slot
        entry.clipId = clipId
        entry.arg0 = arg0
        entry.arg1 = arg1
        entry.reason = reason
    }

    fun onBlackoutClearBranch(branch: String, conditions: String) {
        if (!isEnabled) return
        val now = currentMonotonicMs()
        if (blackoutStartMs == 0L) {
            blackoutStartMs = now
        }
        totalConsecutiveBlackoutMs = now - blackoutStartMs
        log(
            eventType = PreviewEventType.BLACKOUT_CLEAR,
            arg0 = totalConsecutiveBlackoutMs,
            reason = "$branch: $conditions",
            timestampMs = now
        )
    }

    fun onVideoDrawn() {
        if (!isEnabled) return
        val now = currentMonotonicMs()
        if (blackoutStartMs > 0L) {
            val dur = now - blackoutStartMs
            log(
                eventType = PreviewEventType.BLACKOUT_END,
                arg0 = dur,
                reason = "consecutive_blackout_ended",
                timestampMs = now
            )
            blackoutStartMs = 0L
            totalConsecutiveBlackoutMs = 0L
        }
    }

    fun logSwallowedException(location: String, throwable: Throwable) {
        if (!isEnabled) return
        val key = "$location:${throwable.javaClass.name}"
        val isFirst = reportedExceptions.putIfAbsent(key, true) == null
        if (isFirst) {
            val topStack = throwable.stackTrace.take(5).joinToString("\n  at ")
            try {
                Log.e("PreviewErr", "Swallowed exception at $location: ${throwable.javaClass.name}: ${throwable.message}\n  at $topStack", throwable)
            } catch (_: Throwable) {
                System.err.println("PreviewErr: Swallowed exception at $location: ${throwable.javaClass.name}: ${throwable.message}\n  at $topStack")
            }
        }
        log(
            eventType = PreviewEventType.EXCEPTION_SWALLOWED,
            reason = "$location: ${throwable.javaClass.simpleName}: ${throwable.message}"
        )
    }

    fun dumpLastEvents(count: Int = 100): List<LogEntry> {
        val current = writeIndex.get()
        val requestedCount = count.coerceIn(1, RING_SIZE)
        val start = if (current > requestedCount) current - requestedCount else 0L
        val result = ArrayList<LogEntry>((current - start).toInt())
        for (i in start until current) {
            val idx = (i and RING_MASK.toLong()).toInt()
            val copy = LogEntry()
            copy.copyFrom(entries[idx])
            result.add(copy)
        }
        return result
    }

    fun dumpLastEventsFormatted(sb: StringBuilder, count: Int = 100) {
        val current = writeIndex.get()
        val requestedCount = count.coerceIn(1, RING_SIZE)
        val start = if (current > requestedCount) current - requestedCount else 0L
        for (i in start until current) {
            val idx = (i and RING_MASK.toLong()).toInt()
            val e = entries[idx]
            sb.append("[").append(e.timestampMs).append(" ms] [T:").append(e.threadId).append("] [")
                .append(e.eventType).append("] slot=").append(e.slot).append(" clip=")
                .append(e.clipId ?: "none").append(" arg0=").append(e.arg0).append(" arg1=")
                .append(e.arg1).append(" reason=").append(e.reason ?: "none").append("\n")
        }
    }

    fun resetForTest() {
        writeIndex.set(0L)
        blackoutStartMs = 0L
        totalConsecutiveBlackoutMs = 0L
        reportedExceptions.clear()
        flushCounter.reset()
        for (i in 0 until RING_SIZE) {
            entries[i].timestampMs = 0L
            entries[i].threadId = 0
            entries[i].eventType = PreviewEventType.NONE
            entries[i].slot = -1
            entries[i].clipId = null
            entries[i].arg0 = 0L
            entries[i].arg1 = 0L
            entries[i].reason = null
        }
    }
}
