package com.flowcut.app.editor

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.flowcut.app.engine.perf.PreviewEventLog
import com.flowcut.app.engine.perf.PreviewEventType

/**
 * Standard loading source constants for heavy operations.
 */
object LoadingSources {
    const val SCRUBBING = "SCRUBBING"
    const val SEEKING = "SEEKING"
    const val THUMBNAIL_GEN = "THUMBNAIL_GEN"
    const val WAVEFORM_GEN = "WAVEFORM_GEN"
    const val HEAVY_PROCESSING = "HEAVY_PROCESSING"
    const val IMPORTING = "IMPORTING"
    const val FRAME_CATCHUP = "FRAME_CATCHUP"
}

/**
 * Manages global loading states across the video editor with anti-flicker delay protection.
 *
 * Requirements:
 * - Anti-flicker delay: Adds 300-400ms delay before showing the loader. If work finishes
 *   within this delay, loader is never shown.
 * - Immediate dismissal: As soon as heavy work finishes, loader hides immediately (0ms delay).
 * - Multi-source tracking: Keeps loader visible until all active heavy sources complete.
 * - Customizable message: Defaults to "Processing...".
 */
class LoadingStateManager(
    private val scope: CoroutineScope,
    private val delayMs: Long = 350L
) {
    private val _activeSources = MutableStateFlow<Set<String>>(emptySet())
    val activeSources: StateFlow<Set<String>> = _activeSources.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _isLoadingVisible = MutableStateFlow(false)
    val isLoadingVisible: StateFlow<Boolean> = _isLoadingVisible.asStateFlow()

    private val _loadingMessage = MutableStateFlow("Processing...")
    val loadingMessage: StateFlow<String> = _loadingMessage.asStateFlow()

    private var delayJob: Job? = null
    private var loaderShownTimeMs: Long = 0L
    private val lock = Any()

    private val sourceStartTimes = mutableMapOf<String, Long>()
    private var watchdogJob: Job? = null

    private fun getElapsedRealtime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    private fun logW(tag: String, msg: String) {
        if (!com.flowcut.app.BuildConfig.DEBUG) return
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            // Fallback for JVM test environments
        }
    }

    private fun logE(tag: String, msg: String) {
        try {
            Log.e(tag, msg)
        } catch (_: Throwable) {
            // Fallback for JVM test environments
        }
    }

    private fun setLoadingVisibleLocked(visible: Boolean, reason: String = "") {
        val wasVisible = _isLoadingVisible.value
        if (wasVisible != visible) {
            _isLoadingVisible.value = visible
            if (visible) {
                loaderShownTimeMs = System.currentTimeMillis()
                PreviewEventLog.log(PreviewEventType.LOADING_INDICATOR_SHOWN, reason = reason)
            } else {
                val dur = if (loaderShownTimeMs > 0L) System.currentTimeMillis() - loaderShownTimeMs else 0L
                loaderShownTimeMs = 0L
                PreviewEventLog.log(PreviewEventType.LOADING_INDICATOR_HIDDEN, arg0 = dur, reason = reason)
            }
            logW("LOAD", "visible=$visible active=${_activeSources.value.toList()}")
        }
    }

    private fun startWatchdogLocked() {
        if (watchdogJob == null) {
            watchdogJob = scope.launch {
                while (isActive) {
                    delay(1000L)
                    checkWatchdog()
                }
            }
        }
    }

    private fun stopWatchdogLocked() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun checkWatchdog() {
        val stuckSourcesToClear = mutableListOf<String>()
        synchronized(lock) {
            if (_activeSources.value.isEmpty()) {
                stopWatchdogLocked()
                return
            }
            val now = getElapsedRealtime()
            for (source in _activeSources.value) {
                val start = sourceStartTimes[source] ?: continue
                val heldMs = now - start
                if (heldMs > 2000L) {
                    logE("LOAD", "SLOW source=$source held=${heldMs}ms")
                }
                val lower = source.lowercase()
                if (heldMs > 10000L && !lower.contains("export") && !lower.contains("proxy")) {
                    logE("LOAD", "FORCE-CLEAR stuck source=$source")
                    stuckSourcesToClear.add(source)
                }
            }
            for (source in stuckSourcesToClear) {
                stopLoadingLocked(source)
            }
        }
    }

    /**
     * Registers a heavy operation as active.
     */
    fun startLoading(source: String, message: String = "Processing...") {
        synchronized(lock) {
            _loadingMessage.value = message
            val now = getElapsedRealtime()
            if (!sourceStartTimes.containsKey(source)) {
                sourceStartTimes[source] = now
            }
            val current = _activeSources.value + source
            _activeSources.value = current
            _isBusy.value = true

            startWatchdogLocked()

            if (!_isLoadingVisible.value && delayJob == null) {
                delayJob = scope.launch {
                    delay(delayMs)
                    synchronized(lock) {
                        if (_activeSources.value.isNotEmpty()) {
                            setLoadingVisibleLocked(true, reason = source)
                        }
                        delayJob = null
                    }
                }
            }
        }
    }

    /**
     * Unregisters a heavy operation when it completes.
     */
    fun stopLoading(source: String) {
        synchronized(lock) {
            stopLoadingLocked(source)
        }
    }

    private fun stopLoadingLocked(source: String) {
        if (!_activeSources.value.contains(source)) {
            sourceStartTimes.remove(source)
            return
        }
        val startTime = sourceStartTimes.remove(source)
        val now = getElapsedRealtime()
        val heldMs = if (startTime != null) now - startTime else 0L
        logW("LOAD", "source=$source held=${heldMs}ms")

        val current = _activeSources.value - source
        _activeSources.value = current
        if (current.isEmpty()) {
            _isBusy.value = false
            delayJob?.cancel()
            delayJob = null
            stopWatchdogLocked()
            setLoadingVisibleLocked(false, reason = source)
        }
    }

    /**
     * Convenience method to update a source's loading state.
     */
    fun setLoading(source: String, isLoading: Boolean, message: String = "Processing...") {
        if (isLoading) {
            startLoading(source, message)
        } else {
            stopLoading(source)
        }
    }

    /**
     * Checks if a particular loading source is currently active.
     */
    fun isSourceLoading(source: String): Boolean {
        return _activeSources.value.contains(source)
    }

    /**
     * Clears all loading sources and hides the loader immediately.
     */
    fun clearAll() {
        synchronized(lock) {
            val now = getElapsedRealtime()
            val sources = _activeSources.value.toList()
            for (source in sources) {
                val startTime = sourceStartTimes.remove(source)
                val heldMs = if (startTime != null) now - startTime else 0L
                logW("LOAD", "source=$source held=${heldMs}ms")
            }
            sourceStartTimes.clear()
            delayJob?.cancel()
            delayJob = null
            stopWatchdogLocked()
            _activeSources.value = emptySet()
            _isBusy.value = false
            setLoadingVisibleLocked(false, reason = "clearAll")
        }
    }

    /**
     * Wraps a suspending block with automatic loading state management.
     */
    suspend fun <T> withLoading(
        source: String,
        message: String = "Processing...",
        block: suspend () -> T
    ): T {
        startLoading(source, message)
        try {
            return block()
        } finally {
            stopLoading(source)
        }
    }
}
