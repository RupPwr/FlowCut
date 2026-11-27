package com.flowcut.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.model.Project
import com.flowcut.app.storage.DraftLibraryUtils
import com.flowcut.app.storage.ProjectDraftSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Video Guru-inspired FlowCut Studio HomeScreen:
 * - Clean dark studio branding header
 * - Non-destructive hero "New Project" gradient CTA (preserves existing drafts)
 * - Multi-project "My Drafts" 2-column grid with thumbnails, aspect ratio & duration badges,
 *   relative timestamps, and per-draft Rename / Duplicate / Delete actions.
 */
@Composable
fun HomeScreen(
    currentProject: Project,
    onNewProjectClick: () -> Unit,
    onResumeProjectClick: () -> Unit,
    onResetAndNewProjectClick: () -> Unit,
    modifier: Modifier = Modifier,
    drafts: List<ProjectDraftSummary> = emptyList(),
    onOpenDraft: ((String) -> Unit)? = null,
    onRenameDraft: ((String, String) -> Unit)? = null,
    onDuplicateDraft: ((String) -> Unit)? = null,
    onDeleteDraft: ((String) -> Unit)? = null
) {
    val colors = FlowCutTheme.colors

    // Fallback summary if caller hasn't migrated to multi-draft list yet
    val effectiveDrafts = remember(drafts, currentProject) {
        if (drafts.isNotEmpty()) {
            drafts
        } else if (currentProject.clips.isNotEmpty()) {
            listOf(
                ProjectDraftSummary(
                    id = "__current__",
                    title = "Current Draft",
                    updatedAtMs = System.currentTimeMillis(),
                    clipCount = currentProject.clips.size,
                    totalDurationMs = currentProject.clips.sumOf { it.durationMs },
                    aspectRatioLabel = PreviewRenderer.currentAspectRatioMode.label,
                    coverPath = currentProject.clips.firstOrNull()?.path,
                    coverTimeUs = 0L
                )
            )
        } else {
            emptyList()
        }
    }

    var renamingDraft by remember { mutableStateOf<ProjectDraftSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingDraft by remember { mutableStateOf<ProjectDraftSummary?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface0)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Studio Header
            item(span = { GridItemSpan(2) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFFFF2D55), Color(0xFFFF6B35))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "FlowCut",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                                letterSpacing = (-0.3).sp
                            )
                            Text(
                                text = "Video Editor Studio",
                                fontSize = 11.sp,
                                color = colors.textSecondary
                            )
                        }
                    }

                    if (effectiveDrafts.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.surface1,
                            border = BorderStroke(0.5.dp, colors.border)
                        ) {
                            Text(
                                text = if (effectiveDrafts.size == 1) "1 draft" else "${effectiveDrafts.size} drafts",
                                color = colors.textSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            // 2. Hero "New Project" Card (non-destructive)
            item(span = { GridItemSpan(2) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFFFF2D55), Color(0xFFFF5E3A))
                            )
                        )
                        .clickable { onNewProjectClick() }
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "New Project",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Cut, trim, filters, music, PiP & captions",
                                fontSize = 12.5.sp,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Create New Project",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            // 3. Drafts Section Header
            item(span = { GridItemSpan(2) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "My Drafts",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    if (effectiveDrafts.isNotEmpty()) {
                        Text(
                            text = "Tap any draft to resume",
                            fontSize = 11.5.sp,
                            color = colors.textMuted
                        )
                    }
                }
            }

            // 4. Draft Cards or Clean Empty State
            if (effectiveDrafts.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface1,
                        border = BorderStroke(0.5.dp, colors.border)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 34.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(colors.surface2),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Movie,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "No saved drafts yet",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap New Project above to pick clips and start editing. Your work auto-saves here.",
                                fontSize = 12.5.sp,
                                color = colors.textSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            } else {
                items(effectiveDrafts, key = { it.id }) { draft ->
                    DraftCard(
                        draft = draft,
                        colors = colors,
                        onOpen = {
                            if (draft.id == "__current__" || onOpenDraft == null) {
                                onResumeProjectClick()
                            } else {
                                onOpenDraft(draft.id)
                            }
                        },
                        onRenameClick = if (onRenameDraft != null && draft.id != "__current__") {
                            {
                                renamingDraft = draft
                                renameText = draft.title
                            }
                        } else null,
                        onDuplicateClick = if (onDuplicateDraft != null && draft.id != "__current__") {
                            { onDuplicateDraft(draft.id) }
                        } else null,
                        onDeleteClick = {
                            deletingDraft = draft
                        }
                    )
                }
            }
        }
    }

    // Rename Draft Dialog
    renamingDraft?.let { target ->
        AlertDialog(
            onDismissRequest = { renamingDraft = null },
            containerColor = colors.surface1,
            title = {
                Text(
                    text = "Rename Project",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { if (it.length <= 40) renameText = it },
                    singleLine = true,
                    placeholder = { Text("Project name", color = colors.textMuted) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surface2,
                        unfocusedContainerColor = colors.surface2,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedIndicatorColor = colors.accent,
                        unfocusedIndicatorColor = colors.border,
                        cursorColor = colors.accent
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty()) {
                            onRenameDraft?.invoke(target.id, trimmed)
                        }
                        renamingDraft = null
                    }
                ) {
                    Text("Save", color = colors.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingDraft = null }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }

    // Delete Draft Confirmation Dialog
    deletingDraft?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingDraft = null },
            containerColor = colors.surface1,
            title = {
                Text(
                    text = "Delete \"${target.title}\"?",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "This removes the saved draft from FlowCut. Original videos in your gallery stay safe.",
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingDraft = null
                        if (target.id == "__current__" || onDeleteDraft == null) {
                            onResetAndNewProjectClick()
                        } else {
                            onDeleteDraft(target.id)
                        }
                    }
                ) {
                    Text("Delete", color = colors.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingDraft = null }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun DraftCard(
    draft: ProjectDraftSummary,
    colors: FlowCutColors,
    onOpen: () -> Unit,
    onRenameClick: (() -> Unit)?,
    onDuplicateClick: (() -> Unit)?,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var thumbBitmap by remember(draft.id, draft.coverPath, draft.coverTimeUs) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(draft.id, draft.coverPath, draft.coverTimeUs) {
        val path = draft.coverPath ?: return@LaunchedEffect
        thumbBitmap = withContext(Dispatchers.IO) {
            decodeDraftThumbnail(path, draft.coverTimeUs)
        }
    }

    val totalSec = (draft.totalDurationMs / 1000L).coerceAtLeast(0L)
    val durationFormatted = String.format(Locale.US, "%02d:%02d", totalSec / 60L, totalSec % 60L)
    val clipText = if (draft.clipCount == 1) "1 clip" else "${draft.clipCount} clips"
    val timeAgo = remember(draft.updatedAtMs) {
        DraftLibraryUtils.formatRelativeTime(draft.updatedAtMs, System.currentTimeMillis())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface1)
            .border(0.5.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable { onOpen() }
    ) {
        // 16:10 Cover Thumbnail with badges
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(colors.surface2)
        ) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap!!.asImageBitmap(),
                    contentDescription = draft.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier
                        .size(30.dp)
                        .align(Alignment.Center)
                )
            }

            // Subtle bottom gradient for badge legibility
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        )
                    )
            )

            // Aspect ratio badge (bottom-left)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = draft.aspectRatioLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            // Duration badge (bottom-right)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = durationFormatted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }

        // Card Footer: Title + metadata + overflow menu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$clipText · $timeAgo",
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Draft options",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = colors.surface2
                ) {
                    if (onRenameClick != null) {
                        DropdownMenuItem(
                            text = { Text("Rename", color = colors.textPrimary, fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(17.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onRenameClick()
                            }
                        )
                    }
                    if (onDuplicateClick != null) {
                        DropdownMenuItem(
                            text = { Text("Duplicate", color = colors.textPrimary, fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(17.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onDuplicateClick()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = colors.danger, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = colors.danger, modifier = Modifier.size(17.dp))
                        },
                        onClick = {
                            menuExpanded = false
                            onDeleteClick()
                        }
                    )
                }
            }
        }
    }
}

private fun decodeDraftThumbnail(path: String, coverTimeUs: Long): Bitmap? {
    val file = File(path)
    if (!file.exists() || !file.canRead()) return null

    // Handle custom image cover files directly without MediaMetadataRetriever
    val lower = path.lowercase(Locale.US)
    val isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
        lower.endsWith(".png") || lower.endsWith(".webp")
    if (isImage) {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            while (maxDim / (sample * 2) >= 320) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val raw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                coverTimeUs.coerceAtLeast(0L),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                320,
                200
            ) ?: retriever.getFrameAtTime(coverTimeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } else {
            retriever.getFrameAtTime(coverTimeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        if (raw != null && (raw.width > 360 || raw.height > 360)) {
            val scale = 320f / maxOf(raw.width, raw.height).toFloat()
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled != raw) raw.recycle()
            scaled
        } else {
            raw
        }
    } catch (_: Exception) {
        null
    } finally {
        try { retriever.release() } catch (_: Exception) {}
    }
}
