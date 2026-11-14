package com.flowcut.app.engine.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.ExportEngine
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.ui.ExportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

data class ExportRequest(
    val snapshot: TimelineSnapshot,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int
)

object ExportCoordinator {

    private val _state = MutableStateFlow<ExportState>(ExportState.Ready)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    @Volatile
    var exportedUri: Uri? = null
        private set

    @Volatile
    var errorMessage: String? = null
        private set

    private val pendingRequest = AtomicReference<ExportRequest?>()
    private val activeEngine = AtomicReference<ExportEngine?>()

    fun takePendingRequest(): ExportRequest? = pendingRequest.getAndSet(null)

    fun start(context: Context, request: ExportRequest) {
        val preflight = ExportPreflight.check(context, request.snapshot.totalMs, request.bitrateBps)
        if (preflight is PreflightResult.InsufficientStorage) {
            fail(preflight.message)
            return
        }

        exportedUri = null
        errorMessage = null
        pendingRequest.set(request)
        _state.value = ExportState.Exporting(0f)

        val intent = Intent(context, ExportService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            fail(e.message ?: "Failed to start export foreground service")
        }
    }

    fun cancel(reason: String = "Export cancelled by user") {
        activeEngine.get()?.cancel()
        errorMessage = reason
        _state.value = ExportState.Failed
    }

    fun fail(message: String) {
        errorMessage = message
        _state.value = ExportState.Failed
    }

    suspend fun run(
        context: Context,
        request: ExportRequest,
        onProgress: (Int) -> Unit
    ) {
        PreviewRenderer.releaseActiveDecoders()
        val engine = ExportEngine(context)
        activeEngine.set(engine)
        try {
            val uri = engine.export(
                snapshot = request.snapshot,
                width = request.width,
                height = request.height,
                fps = request.fps,
                bitrateBps = request.bitrateBps
            ) { progress ->
                _state.value = ExportState.Exporting(progress)
                onProgress((progress * 100).toInt().coerceIn(0, 100))
            }
            exportedUri = uri
            _state.value = ExportState.Done
        } catch (e: Throwable) {
            val isCancelled = e is kotlinx.coroutines.CancellationException ||
                    e.message?.contains("cancelled", ignoreCase = true) == true
            val msg = if (isCancelled) (errorMessage ?: "Export cancelled by user") else (e.message ?: "Export failed")
            errorMessage = msg
            _state.value = ExportState.Failed
            throw e
        } finally {
            activeEngine.set(null)
            PreviewRenderer.resumeActiveDecoders()
        }
    }
}
