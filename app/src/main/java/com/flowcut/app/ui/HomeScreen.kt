package com.flowcut.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.BuildConfig
import com.flowcut.app.debug.ScalePreset
import com.flowcut.app.debug.ScaleTest
import com.flowcut.app.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HomeScreen (Section 20.6):
 * Centered single column, FlowCut theme tokens.
 * - "FlowCut", 28 sp medium
 * - Title long-press in debug opens stress test menu (Section 19.11)
 * - "Continue editing" button (filled accent, 320 dp x 48 dp) if >= 1 clip, with "{N} clips · {mm:ss}" (11 sp)
 * - "New project" button: Outlined if clips exist, with delete confirmation dialog
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    currentProject: Project,
    onNewProjectClick: () -> Unit,
    onResumeProjectClick: () -> Unit,
    onResetAndNewProjectClick: () -> Unit,
    onStressTestSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors
    val hasRecentProject = currentProject.clips.isNotEmpty()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showStressDialog by remember { mutableStateOf(false) }
    var isRunningScaleTest by remember { mutableStateOf(false) }
    var scaleTestStatusText by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface0)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Name: "FlowCut", 28 sp medium
            Text(
                text = "FlowCut",
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (BuildConfig.DEBUG) {
                                showStressDialog = true
                            }
                        }
                    )
                    .padding(8.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            // "Continue editing" button (only when saved project has >= 1 clip)
            if (hasRecentProject) {
                Button(
                    onClick = onResumeProjectClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.onAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "Continue editing",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Subtext: "{N} clips · {mm:ss}" (11 sp, textSecondary)
                val clipCount = currentProject.clips.size
                val clipText = if (clipCount == 1) "1 clip" else "$clipCount clips"
                val totalSec = currentProject.clips.sumOf { it.durationMs } / 1000
                val durationFormatted = String.format("%02d:%02d", totalSec / 60, totalSec % 60)

                Text(
                    text = "$clipText · $durationFormatted",
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // "New project" button: Outlined when recent project exists
                OutlinedButton(
                    onClick = { showDeleteConfirmDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, colors.border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "New project",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                // When no project exists: Primary Filled "New project" button
                Button(
                    onClick = onNewProjectClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.onAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "New project",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // Confirmation Dialog: "Start a new project? Your current project will be deleted."
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = "Start a new project?",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Your current project will be deleted.",
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onResetAndNewProjectClick()
                    }
                ) {
                    Text(
                        text = "Delete and start",
                        color = colors.accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false }
                ) {
                    Text(
                        text = "Cancel",
                        color = colors.textSecondary
                    )
                }
            },
            containerColor = colors.surface1
        )
    }

    // Debug Menu: Scale Test S / M / L (Section 12) & Stress Test
    if (showStressDialog) {
        AlertDialog(
            onDismissRequest = { showStressDialog = false },
            title = {
                Text(
                    text = "Debug Menu (Scale & Stress)",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Foundation Scale Test (Section 12):",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    listOf(
                        ScalePreset.S to "Scale Test S (10 clips, 10 items)",
                        ScalePreset.M to "Scale Test M (200 clips, 100 items)",
                        ScalePreset.L to "Scale Test L (900 clips, 500 items)"
                    ).forEach { (preset, label) ->
                        Button(
                            onClick = {
                                showStressDialog = false
                                isRunningScaleTest = true
                                coroutineScope.launch(Dispatchers.Default) {
                                    val res = ScaleTest.runScenario(preset, outputDir = context.filesDir)
                                    val status = if (res.allGatesPassed) "PASS" else "FAIL"
                                    val report = "$status\n\nPreset ${preset.name} (${preset.clipCount} clips, ${preset.dummyItemCount} items, ${preset.featureCount} features)\n" +
                                            "• Timeline draw p99: ${String.format("%.2f", res.timelineDrawP99Ms)} ms (< 4.0 ms)\n" +
                                            "• Frame p99: ${String.format("%.2f", res.frameP99Ms)} ms (< 16.0 ms)\n" +
                                            "• Snapshot build: ${String.format("%.2f", res.snapshotBuildMs)} ms (< 50.0 ms)\n" +
                                            "• Command to UI: ${String.format("%.2f", res.commandToUiP99Ms)} ms (< 100.0 ms)\n" +
                                            "• 100 Features idle: ${String.format("%.3f", res.idle100FeaturesOverheadMs)} ms (< 0.2 ms)\n" +
                                            "• Memory delta: ${String.format("%.1f", res.memoryDeltaMb)} MB (<= 30 MB)\n" +
                                            (if (res.scalingRatioVsS != null) "• Scaling ratio: ${String.format("%.2f", res.scalingRatioVsS)}x (<= 1.5x)\n" else "") +
                                            "\nResults written to filesDir/scale_${preset.name.lowercase()}.json & logged to tag SCALE"
                                    withContext(Dispatchers.Main) {
                                        scaleTestStatusText = report
                                        isRunningScaleTest = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.onAccent
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Text(text = label, fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Legacy Clip Benchmarks:",
                        color = colors.textSecondary,
                        fontSize = 12.sp
                    )
                    listOf(10, 50, 200, 1000).forEach { count ->
                        Button(
                            onClick = {
                                showStressDialog = false
                                onStressTestSelected(count)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.surface2,
                                contentColor = colors.textPrimary
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) {
                            Text(text = "Stress: $count clips", fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showStressDialog = false }) {
                    Text(text = "Cancel", color = colors.textSecondary)
                }
            },
            containerColor = colors.surface1
        )
    }

    // Scale Test Running Indicator
    if (isRunningScaleTest) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(text = "Running Scale Test...", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = "Executing fling, play, scrub, and 50 commands. Please wait...", color = colors.textSecondary)
            },
            confirmButton = {},
            containerColor = colors.surface1
        )
    }

    // Scale Test Results Dialog (PASS / FAIL on screen, Section 12)
    scaleTestStatusText?.let { report ->
        AlertDialog(
            onDismissRequest = { scaleTestStatusText = null },
            title = {
                val isPass = report.startsWith("PASS")
                Text(
                    text = if (isPass) "SCALE TEST: PASS" else "SCALE TEST: FAIL",
                    color = if (isPass) colors.accent else colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = report,
                    color = colors.textPrimary,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(onClick = { scaleTestStatusText = null }) {
                    Text("OK")
                }
            },
            containerColor = colors.surface1
        )
    }
}
