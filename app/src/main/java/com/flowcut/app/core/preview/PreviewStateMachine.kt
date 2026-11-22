package com.flowcut.app.core.preview

/**
 * Centralized Preview State Machine with anti-flicker buffering protection (ARCHITECTURE_RULES.md Section 3.4).
 *
 * Rules:
 * - Anti-flicker: show the loader only if loading lasts longer than 150 ms.
 * - Once shown, keep the loader visible for at least 300 ms to avoid visual stutter.
 * - UI only reads this state, never writes loader flags directly.
 */
class PreviewStateMachine(
    private val loadingThresholdMs: Long = 150L,
    private val minLoaderVisibleMs: Long = 300L
) {
    interface PreviewStateTransitionListener {
        fun onTransition(from: PreviewStatus, to: PreviewStatus, currentTimeMs: Long, error: String?, loaderVisible: Boolean)
        fun onLoaderVisibilityChanged(visible: Boolean, durationMs: Long, currentTimeMs: Long)
    }

    var transitionListener: PreviewStateTransitionListener? = null

    private var currentStatus: PreviewStatus = PreviewStatus.IDLE
    private var errorMessage: String? = null

    private var loadingStartTimeMs: Long? = null
    private var loaderShownTimeMs: Long? = null

    var currentState: PreviewState = PreviewState()
        private set

    /**
     * Handles explicit state transitions.
     */
    fun transition(
        toStatus: PreviewStatus,
        currentTimeMs: Long,
        error: String? = null
    ): PreviewState {
        val oldStatus = currentStatus
        currentStatus = toStatus
        errorMessage = error

        when (toStatus) {
            PreviewStatus.LOADING, PreviewStatus.SEEKING -> {
                if (loadingStartTimeMs == null) {
                    loadingStartTimeMs = currentTimeMs
                }
            }
            PreviewStatus.IDLE, PreviewStatus.READY, PreviewStatus.PLAYING -> {
                loadingStartTimeMs = null
            }
            PreviewStatus.ERROR -> {
                loadingStartTimeMs = null
                loaderShownTimeMs = null
            }
        }

        val nextState = evaluateState(currentTimeMs)
        if (oldStatus != toStatus) {
            transitionListener?.onTransition(oldStatus, toStatus, currentTimeMs, error, nextState.isLoaderVisible)
        }
        return nextState
    }

    /**
     * Periodic tick (e.g. per frame or timer) to update anti-flicker loader visibility.
     */
    fun tick(currentTimeMs: Long): PreviewState {
        return evaluateState(currentTimeMs)
    }

    private fun evaluateState(currentTimeMs: Long): PreviewState {
        val wasLoaderVisible = currentState.isLoaderVisible
        var showLoader = wasLoaderVisible

        // 1. Should we turn ON the loader?
        val start = loadingStartTimeMs
        if (start != null && (currentStatus == PreviewStatus.LOADING || currentStatus == PreviewStatus.SEEKING)) {
            val durationLoading = currentTimeMs - start
            if (durationLoading >= loadingThresholdMs && !showLoader) {
                showLoader = true
                loaderShownTimeMs = currentTimeMs
                transitionListener?.onLoaderVisibilityChanged(true, 0L, currentTimeMs)
            }
        }

        // 2. Should we turn OFF the loader?
        if (showLoader && (currentStatus == PreviewStatus.READY || currentStatus == PreviewStatus.PLAYING || currentStatus == PreviewStatus.IDLE)) {
            val shownAt = loaderShownTimeMs
            if (shownAt == null || (currentTimeMs - shownAt >= minLoaderVisibleMs)) {
                showLoader = false
                val dur = if (shownAt != null) currentTimeMs - shownAt else 0L
                loaderShownTimeMs = null
                transitionListener?.onLoaderVisibilityChanged(false, dur, currentTimeMs)
            }
        }

        currentState = PreviewState(
            status = currentStatus,
            isLoaderVisible = showLoader,
            errorMessage = errorMessage
        )
        return currentState
    }
}
