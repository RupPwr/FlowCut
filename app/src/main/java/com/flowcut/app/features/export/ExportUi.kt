/**
 * Feature:     export
 * File role:   ui
 * Responsible: Bottom sheet UI for video export configuration, progress, and sharing
 * Depends on:  shared, features/export/ExportLogic
 * Used by:     Index, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.engine.export.ExportCoordinator
import com.flowcut.app.shared.ExportState
import com.flowcut.app.shared.TimeFormatters
import com.flowcut.app.ui.FlowCutTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheetUi(
    state: ExportState,
    totalDurationMs: Long,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FlowCutTheme.colors
    val isExporting = state is ExportState.Exporting
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !isExporting }
    )

    ModalBottomSheet(
        onDismissRequest = { if (!isExporting) onDismiss() },
        sheetState = sheetState,
        containerColor = colors.surface0
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            when (state) {
                is ExportState.Ready -> {
                    Text(text = "Export video", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Duration: ${TimeFormatters.formatDurationMs(totalDurationMs)}", fontSize = 13.sp, color = colors.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "1080 x 1920 (9:16)", fontSize = 13.sp, color = colors.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "30 fps, MP4", fontSize = 13.sp, color = colors.textSecondary)
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(text = "Cancel", color = colors.textSecondary, fontSize = 14.sp)
                        }
                        Button(
                            onClick = onStartExport,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                        ) {
                            Text(text = "Start export", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                is ExportState.Exporting -> {
                    Text(text = "Exporting...", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
                    Spacer(modifier = Modifier.height(16.dp))
                    val percent = ExportLogic.progressToPercent(state.progress)
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = colors.accent,
                        trackColor = colors.borderStrong
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "$percent%", fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.align(Alignment.End))
                    val fallbackNotice = ExportCoordinator.fallbackNotice
                    if (fallbackNotice != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = fallbackNotice, fontSize = 12.sp, color = Color(0xFFFFD54F), fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onCancelExport,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = colors.onAccent),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(text = "Cancel export", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }

                is ExportState.Done -> {
                    Text(text = "Saved to Movies/FlowCut", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
                    val fallbackNotice = ExportCoordinator.fallbackNotice
                    if (fallbackNotice != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = fallbackNotice, fontSize = 12.sp, color = Color(0xFFFFD54F), fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(text = "Close", color = colors.textSecondary, fontSize = 14.sp)
                        }
                        Button(
                            onClick = onShare,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                        ) {
                            Text(text = "Share", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                is ExportState.Failed -> {
                    Text(text = "Export failed. Try again.", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.danger)
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(text = "Close", color = colors.textSecondary, fontSize = 14.sp)
                        }
                        Button(
                            onClick = onStartExport,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                        ) {
                            Text(text = "Try again", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
