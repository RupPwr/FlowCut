package com.flowcut.app.engine.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.ExportEngine
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.engine.perf.SessionGuard
import com.flowcut.app.shared.ExportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private fun buildLegacySpec(width: Int, height: Int, fps: Int): OutputSpec {
    var a = width.coerceAtLeast(1)
    var b = height.coerceAtLeast(1)
    while (b != 0) {
        val t = b
        b = a % b
        a = t
    }
    val g = a
    val wSafe = width.coerceAtLeast(1)
    val hSafe = height.coerceAtLeast(1)
    return OutputSpec(Aspect(wSafe / g, hSafe / g), minOf(wSafe, hSafe), fps, Quality.HIGH, OutCodec.AVC)
}

data class ExportRequest(
    val snapshot: TimelineSnapshot,
    val spec: OutputSpec,
    val explicitBitrateBps: Int? = null
) {
    val width: Int get() = spec.size().w
    val height: Int get() = spec.size().h
    val fps: Int get() = spec.fps
    val bitrateBps: Int get() = explicitBitrateBps ?: BitrateTable.videoBps(width, height, fps, spec.quality, spec.codec)

    // Backward-compatible constructor for existing callers
    constructor(
        snapshot: TimelineSnapshot,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int
    ) : this(
        snapshot = snapshot,
        spec = buildLegacySpec(width, height, fps),
        explicitBitrateBps = bitrateBps
    )
}

object ExportCoordinator {

    private val _state = MutableStateFlow<ExportState>(ExportState.Ready)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    @Volatile
    var exportedUri: Uri? = null
        private set

    @Volatile
    var errorMessage: String? = null
        private set

    @Volatile
    var lastCompletedSpec: OutputSpec? = null
        private set

    @Volatile
    var fallbackNotice: String? = null
        private set

    @Volatile
    private var isCancelRequested = false

    private val isExportInProgress = AtomicBoolean(false)
    private val pendingRequest = AtomicReference<ExportRequest?>()
    private val activeEngine = AtomicReference<ExportEngine?>()

    fun takePendingRequest(): ExportRequest? = pendingRequest.getAndSet(null)

    fun start(context: Context, request: ExportRequest) {
        if (!isExportInProgress.compareAndSet(false, true)) {
            Log.w("EXPORT", "Export start ignored: an export is already in progress")
            return
        }

        val preflight = ExportPreflight.check(context, request.snapshot.totalMs, request.bitrateBps)
        if (preflight is PreflightResult.InsufficientStorage) {
            isExportInProgress.set(false)
            fail(preflight.message)
            return
        }

        isCancelRequested = false
        exportedUri = null
        errorMessage = null
        lastCompletedSpec = null
        fallbackNotice = null
        pendingRequest.set(request)
        _state.value = ExportState.Exporting(0f)

        val intent = Intent(context, ExportService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            pendingRequest.set(null)
            isExportInProgress.set(false)
            fail(e.message ?: "Failed to start export foreground service")
        }
    }

    fun cancel(reason: String = "Export cancelled by user") {
        isCancelRequested = true
        val hadPending = pendingRequest.getAndSet(null) != null
        val engine = activeEngine.get()
        engine?.cancel()
        if (engine == null && hadPending) {
            isExportInProgress.set(false)
        }
        errorMessage = reason
        _state.value = ExportState.Failed
    }

    fun fail(message: String) {
        pendingRequest.set(null)
        if (activeEngine.get() == null) {
            isExportInProgress.set(false)
        }
        errorMessage = message
        _state.value = ExportState.Failed
    }

    suspend fun run(
        context: Context,
        request: ExportRequest,
        onProgress: (Int) -> Unit
    ) {
        isExportInProgress.set(true)
        val guard = SessionGuard(context)
        val ladder = ExportLadder.candidates(request.spec).filter { !guard.isExcluded(it.key()) }
        val candidateSpecs = if (ladder.isNotEmpty()) ladder else listOf(request.spec)

        PreviewRenderer.releaseActiveDecoders()
        val engine = ExportEngine(context)
        activeEngine.set(engine)

        var lastError: Throwable? = null
        try {
            for ((attemptIndex, candidateSpec) in candidateSpecs.withIndex()) {
                if (isCancelRequested) {
                    throw kotlinx.coroutines.CancellationException("Export cancelled by user")
                }

                if (attemptIndex > 0) {
                    val notice = "Export stepped down to ${candidateSpec.shortSide}p ${candidateSpec.fps}fps (${candidateSpec.codec})"
                    fallbackNotice = notice
                    Log.w("EXPORT", "Export fallback attempt $attemptIndex: $notice")
                }

                guard.markExportStart(candidateSpec.key())
                var lastNotifiedPct = -1
                try {
                    val uri = engine.export(
                        snapshot = request.snapshot,
                        spec = candidateSpec,
                        explicitBitrateBps = if (candidateSpec == request.spec) request.explicitBitrateBps else null
                    ) { progress ->
                        _state.value = ExportState.Exporting(progress)
                        val pct = (progress * 100).toInt().coerceIn(0, 100)
                        if (pct != lastNotifiedPct) {
                            lastNotifiedPct = pct
                            onProgress(pct)
                        }
                    }
                    guard.markExportEnd()
                    lastCompletedSpec = candidateSpec
                    exportedUri = uri
                    _state.value = ExportState.Done
                    return
                } catch (e: Throwable) {
                    guard.markExportEnd()
                    val isCancelled = e is kotlinx.coroutines.CancellationException ||
                            e.message?.contains("cancelled", ignoreCase = true) == true
                    if (isCancelled) {
                        throw e
                    }
                    Log.e("EXPORT", "Candidate ${candidateSpec.key()} failed: ${e.message}. Stepping down ladder.")
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("All export ladder candidates failed")
        } catch (e: Throwable) {
            val isCancelled = e is kotlinx.coroutines.CancellationException ||
                    e.message?.contains("cancelled", ignoreCase = true) == true
            val msg = if (isCancelled) (errorMessage ?: "Export cancelled by user") else (e.message ?: "Export failed")
            errorMessage = msg
            _state.value = ExportState.Failed
            throw e
        } finally {
            activeEngine.set(null)
            isExportInProgress.set(false)
            PreviewRenderer.resumeActiveDecoders()
        }
    }
}
