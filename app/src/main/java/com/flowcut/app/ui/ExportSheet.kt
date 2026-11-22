package com.flowcut.app.ui

import androidx.compose.runtime.Composable
import com.flowcut.app.features.export.ExportSheet as FeatureExportSheet

/**
 * Preserves binary and source compatibility for all existing usages in
 * MainActivity, EditorViewModel, ExportCoordinator, and OverlaySheets.
 * Adheres to Section 2 (public API rule) of ARCHITECTURE_RULES.md.
 */
sealed class ExportState {
    data object Ready : ExportState()
    data class Exporting(val progress: Float) : ExportState() // 0..1
    data object Done : ExportState()
    data object Failed : ExportState()

    fun toShared(): com.flowcut.app.shared.ExportState = when (this) {
        is Ready -> com.flowcut.app.shared.ExportState.Ready
        is Exporting -> com.flowcut.app.shared.ExportState.Exporting(progress)
        is Done -> com.flowcut.app.shared.ExportState.Done
        is Failed -> com.flowcut.app.shared.ExportState.Failed
    }
}

@Composable
fun ExportSheet(
    state: ExportState,
    totalDurationMs: Long,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    FeatureExportSheet(
        state = state.toShared(),
        totalDurationMs = totalDurationMs,
        onStartExport = onStartExport,
        onCancelExport = onCancelExport,
        onShare = onShare,
        onDismiss = onDismiss
    )
}
