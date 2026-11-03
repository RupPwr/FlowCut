package com.flowcut.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowcut.app.editor.EditorViewModel
import com.flowcut.app.engine.PreviewEngine
import com.flowcut.app.model.MAX_CLIPS
import com.flowcut.app.ui.EditorScreen
import com.flowcut.app.ui.ExportState
import com.flowcut.app.ui.FlowCutTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()
    private var previewEngine: PreviewEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val engine = PreviewEngine(
            context = this,
            onPlayheadUpdate = { ms -> viewModel.onPlayheadUpdate(ms) },
            onPlaybackStateChange = { playing -> viewModel.onPlaybackStateChange(playing) }
        )
        previewEngine = engine
        viewModel.initPreviewEngine(engine)

        setContent {
            FlowCutTheme {
                val uiState by viewModel.uiState.collectAsState()

                // Photo picker launcher
                val remainingSlots = maxOf(1, MAX_CLIPS - uiState.project.clips.size)
                val multiplePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickMultipleVisualMedia(
                        maxItems = if (remainingSlots > 1) remainingSlots else 2
                    )
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        viewModel.importClips(uris)
                    }
                }

                val singlePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        viewModel.importClips(listOf(uri))
                    }
                }

                val openPhotoPicker = {
                    val slots = MAX_CLIPS - uiState.project.clips.size
                    if (slots <= 0) {
                        viewModel.showToast("You can add up to 10 clips")
                    } else if (slots == 1) {
                        singlePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    } else {
                        multiplePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    }
                }

                // Toast observer
                LaunchedEffect(uiState.toastMessage) {
                    uiState.toastMessage?.let { msg ->
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        viewModel.clearToast()
                    }
                }

                // Keep screen on during export
                LaunchedEffect(uiState.exportState) {
                    if (uiState.exportState is ExportState.Exporting) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                EditorScreen(
                    timeline = viewModel.timeline,
                    currentMs = uiState.currentMs,
                    isPlaying = uiState.isPlaying,
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    selectedClipIndex = uiState.selectedClipIndex,
                    selectedJunctionIndex = uiState.selectedJunctionIndex,
                    glSurfaceView = engine.glSurfaceView,
                    exportSheetVisible = uiState.exportSheetVisible,
                    durationSheetVisible = uiState.durationSheetVisible,
                    exportState = uiState.exportState,
                    onBackClick = { finish() },
                    onExportButtonClick = { viewModel.openExportSheet() },
                    onAddClipsClick = openPhotoPicker,
                    onUndoClick = { viewModel.undo() },
                    onRedoClick = { viewModel.redo() },
                    onPlayPauseClick = { viewModel.playPause() },
                    onSeekScrub = { ms -> viewModel.seekTo(ms) },
                    onSelectClip = { index -> viewModel.selectClip(index) },
                    onDeleteClip = { index -> viewModel.deleteClip(index) },
                    onSelectJunction = { index -> viewModel.selectJunction(index) },
                    onDeselectAll = { viewModel.deselectAll() },
                    onApplyTransition = { type -> viewModel.applyTransition(type) },
                    onUpdateTransitionDuration = { dur -> viewModel.updateTransitionDurationLive(dur) },
                    onCommitTransitionDuration = { dur -> viewModel.commitTransitionDuration(dur) },
                    onRemoveTransition = { viewModel.removeTransition() },
                    onCloseDurationSheet = { viewModel.closeDurationSheet() },
                    onStartExport = { viewModel.startExport() },
                    onCancelExport = { viewModel.cancelExport() },
                    onShareExport = {
                        uiState.exportedUri?.let { uri ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(shareIntent, "Share video"))
                        }
                    },
                    onCloseExportSheet = { viewModel.closeExportSheet() }
                )

                // Non-dismissible blocking import dialog: "Importing 2/3..."
                if (uiState.isImporting) {
                    val colors = FlowCutTheme.colors
                    Dialog(
                        onDismissRequest = { /* Blocking, cannot dismiss */ },
                        properties = DialogProperties(
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .background(colors.surface1, RoundedCornerShape(12.dp))
                                .padding(horizontal = 24.dp, vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = colors.accent,
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = uiState.importProgressText,
                                    color = colors.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onAppBackground()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResume()
    }
}
