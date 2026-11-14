package com.flowcut.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HomeScreen (Section 20.6):
 * Centered single column, FlowCut theme tokens.
 * - "FlowCut", 28 sp medium
 * - "Resume Draft" Card (cover thumbnail, aspect ratio badge, clip count, MM:SS duration)
 * - "Continue editing" button (filled accent, 320 dp x 48 dp) if >= 1 clip
 * - "New project" button: Outlined if clips exist, with delete confirmation dialog
 */
@Composable
fun HomeScreen(
    currentProject: Project,
    onNewProjectClick: () -> Unit,
    onResumeProjectClick: () -> Unit,
    onResetAndNewProjectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FlowCutTheme.colors
    val hasRecentProject = currentProject.clips.isNotEmpty()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val firstClip = currentProject.clips.firstOrNull()
    var thumbnailBitmap by remember(firstClip?.id) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(firstClip?.id, firstClip?.path) {
        val path = firstClip?.path ?: return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val raw = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (raw != null) {
                    val scaled = android.graphics.Bitmap.createScaledBitmap(raw, 200, 200, true)
                    if (scaled != raw) raw.recycle()
                    scaled
                } else null
            } catch (_: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
        thumbnailBitmap = bmp
    }

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
                modifier = Modifier.padding(8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // When saved project has >= 1 clip: Show Polish Draft Card + Continue Button + New Project Outlined
            if (hasRecentProject) {
                // Resume Draft Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface1)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .clickable { onResumeProjectClick() }
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Thumbnail with Aspect Ratio Badge
                        Box(
                            modifier = Modifier
                                .size(width = 68.dp, height = 68.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface2),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            if (thumbnailBitmap != null) {
                                Image(
                                    bitmap = thumbnailBitmap!!.asImageBitmap(),
                                    contentDescription = "Project Cover",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .align(Alignment.Center)
                                )
                            }

                            // Active aspect ratio badge
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.70f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = PreviewRenderer.currentAspectRatioMode.label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        // Clip Count & Duration
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Resume Draft",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val clipCount = currentProject.clips.size
                            val clipText = if (clipCount == 1) "1 clip" else "$clipCount clips"
                            val totalSec = currentProject.clips.sumOf { it.durationMs } / 1000
                            val durationFormatted = String.format("%02d:%02d", totalSec / 60, totalSec % 60)
                            Text(
                                text = "$clipText · $durationFormatted",
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                        }

                        // Arrow forward indicator
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Resume",
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary "Continue editing" button
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

                Spacer(modifier = Modifier.height(12.dp))

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
}
