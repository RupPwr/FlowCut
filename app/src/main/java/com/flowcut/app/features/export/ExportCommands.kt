/**
 * Feature:     export
 * File role:   commands
 * Responsible: Export workflow action triggers and listener contracts
 * Depends on:  shared
 * Used by:     ExportUi, Index
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.export

/**
 * High-level export actions listener.
 */
interface ExportActionListener {
    fun onStartExport()
    fun onCancelExport()
    fun onShare()
    fun onDismiss()
}
