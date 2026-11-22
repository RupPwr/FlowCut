/**
 * Feature:     export
 * File role:   index
 * Responsible: Public API of the export feature module
 * Depends on:  shared, features/export/ExportUi, features/export/ExportLogic
 * Used by:     app, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.export

import androidx.compose.runtime.Composable
import com.flowcut.app.shared.ExportState

/**
 * Public entry point for the Export feature.
 * External modules must ONLY import from this file.
 */
object ExportFeature {
    const val ID = "export"

    /**
     * Estimates export file size in MB.
     */
    fun estimateSizeMb(durationMs: Long, bitrateBps: Long = 12_000_000L): Float {
        return ExportLogic.estimateFileSizeMb(durationMs, bitrateBps)
    }
}

/**
 * Public Composable for the Export bottom sheet.
 */
@Composable
fun ExportSheet(
    state: ExportState,
    totalDurationMs: Long,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    ExportSheetUi(
        state = state,
        totalDurationMs = totalDurationMs,
        onStartExport = onStartExport,
        onCancelExport = onCancelExport,
        onShare = onShare,
        onDismiss = onDismiss
    )
}
