package com.flowcut.app.shared

/**
 * Shared representation of video export lifecycle state.
 */
sealed class ExportState {
    data object Ready : ExportState()
    data class Exporting(val progress: Float) : ExportState() // 0.0..1.0
    data object Done : ExportState()
    data object Failed : ExportState()
}
