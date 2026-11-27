package com.flowcut.app.ui

import androidx.compose.runtime.Composable
import com.flowcut.app.features.export.ExportSheet as FeatureExportSheet
import com.flowcut.app.shared.ExportState

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
        state = state,
        totalDurationMs = totalDurationMs,
        onStartExport = onStartExport,
        onCancelExport = onCancelExport,
        onShare = onShare,
        onDismiss = onDismiss
    )
}
