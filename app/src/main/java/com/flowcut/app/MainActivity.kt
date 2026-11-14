package com.flowcut.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.flowcut.app.editor.AppScreen
import com.flowcut.app.editor.EditorViewModel
import com.flowcut.app.engine.PreviewEngine
import com.flowcut.app.engine.TransitionRenderer
import com.flowcut.app.model.MAX_CLIPS
import com.flowcut.app.ui.EditorScreen
import com.flowcut.app.engine.perf.SessionGuard
import com.flowcut.app.ui.ExportState
import com.flowcut.app.ui.FlowCutTheme
import com.flowcut.app.ui.HomeScreen

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()
    private var previewEngine: PreviewEngine? = null
    private var jankStats: androidx.metrics.performance.JankStats? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionGuard(this).recordCrashIfAny()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        enableEdgeToEdge()

        try {
            jankStats = androidx.metrics.performance.JankStats.createAndTrack(window) { frameData ->
                // Logs or tracks jank if needed in debug
            }
        } catch (e: Exception) {
            // Best effort
        }

        val engine = if (!com.flowcut.app.engine.USE_NEW_CORE) {
            PreviewEngine(
                context = this,
                onPlayheadUpdate = { ms -> viewModel.onPlayheadUpdate(ms) },
                onPlaybackStateChange = { playing -> viewModel.onPlaybackStateChange(playing) }
            ).also {
                previewEngine = it
                viewModel.initPreviewEngine(it)
            }
        } else null

        val importUriString = intent?.getStringExtra("import_uri")
        if (importUriString != null) {
            val uri = android.net.Uri.parse(importUriString)
            viewModel.createNewProject(listOf(uri))
        } else if (intent?.getBooleanExtra("open_editor", false) == true) {
            viewModel.navigateTo(AppScreen.EDITOR)
        }

        if (intent?.getBooleanExtra("test_shader_init", false) == true) {
            testShaderWarmup()
        }

        setContent {
            FlowCutTheme {
                val uiState by viewModel.uiState.collectAsState()

                // Handle back button on EditorScreen to return to HomeScreen
                BackHandler(enabled = uiState.currentScreen == AppScreen.EDITOR) {
                    viewModel.navigateTo(AppScreen.HOME)
                }

                // Safe photo picker max limit (must be <= MediaStore.getPickImagesMaxLimit())
                val maxPickerItems = remember {
                    val systemLimit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try {
                            android.provider.MediaStore.getPickImagesMaxLimit()
                        } catch (_: Exception) {
                            100
                        }
                    } else {
                        try {
                            val method = android.provider.MediaStore::class.java.getMethod("getPickImagesMaxLimit")
                            (method.invoke(null) as? Int) ?: 100
                        } catch (_: Exception) {
                            100
                        }
                    }
                    minOf(MAX_CLIPS, maxOf(2, systemLimit))
                }

                // 1. New Project Video Pickers
                val newProjectMultiplePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxPickerItems)
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        viewModel.createNewProject(uris)
                    }
                }

                val newProjectFallbackPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetMultipleContents()
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        viewModel.createNewProject(uris)
                    }
                }

                // 2. Editor "Add clips" Pickers (appends to existing project)
                val remainingSlots = maxOf(2, minOf(maxPickerItems, MAX_CLIPS - uiState.project.clips.size))
                val editorMultiplePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = remainingSlots)
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        viewModel.importClips(uris)
                    }
                }

                val editorFallbackPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetMultipleContents()
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        viewModel.importClips(uris)
                    }
                }

                // Media Permission for Android 11 (API 30) fallback when photo picker is not available
                val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_VIDEO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

                var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        pendingAction?.invoke()
                    } else {
                        viewModel.showToast("Storage permission is required to select videos")
                    }
                    pendingAction = null
                }

                val launchWithPermissionIfNeeded: (() -> Unit) -> Unit = { action ->
                    val isPhotoPickerAvail = ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this@MainActivity)
                    if (isPhotoPickerAvail || ContextCompat.checkSelfPermission(this@MainActivity, mediaPermission) == PackageManager.PERMISSION_GRANTED) {
                        action()
                    } else {
                        pendingAction = action
                        permissionLauncher.launch(mediaPermission)
                    }
                }

                val openNewProjectPicker = {
                    launchWithPermissionIfNeeded {
                        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this@MainActivity)) {
                            newProjectMultiplePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        } else {
                            newProjectFallbackPicker.launch("video/*")
                        }
                    }
                }

                val openEditorAddClipsPicker = {
                    val slots = MAX_CLIPS - uiState.project.clips.size
                    if (slots <= 0) {
                        viewModel.showToast("Maximum clip limit reached ($MAX_CLIPS clips)")
                    } else {
                        launchWithPermissionIfNeeded {
                            if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this@MainActivity)) {
                                editorMultiplePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            } else {
                                editorFallbackPicker.launch("video/*")
                            }
                        }
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

                // Navigation: HomeScreen vs EditorScreen
                when (uiState.currentScreen) {
                    AppScreen.HOME -> {
                        HomeScreen(
                            currentProject = uiState.project,
                            onNewProjectClick = openNewProjectPicker,
                            onResumeProjectClick = { viewModel.openExistingProject() },
                            onResetAndNewProjectClick = {
                                viewModel.resetProject {
                                    openNewProjectPicker()
                                }
                            }
                        )
                    }

                    AppScreen.EDITOR -> {
                        EditorScreen(
                            timeline = viewModel.timeline,
                            currentMs = uiState.currentMs,
                            isPlaying = uiState.isPlaying,
                            canUndo = uiState.canUndo,
                            canRedo = uiState.canRedo,
                            selectedClipIndex = uiState.selectedClipIndex,
                            selectedJunctionIndex = uiState.selectedJunctionIndex,
                            glSurfaceView = engine?.glSurfaceView,
                            exportSheetVisible = uiState.exportSheetVisible,
                            durationSheetVisible = uiState.durationSheetVisible,
                            exportState = uiState.exportState,
                            onBackClick = { viewModel.navigateTo(AppScreen.HOME) },
                            onExportButtonClick = { viewModel.openExportSheet() },
                            onAddClipsClick = openEditorAddClipsPicker,
                            onUndoClick = { viewModel.undo() },
                            onRedoClick = { viewModel.redo() },
                            onPlayPauseClick = { viewModel.playPause() },
                            onSeekScrub = { ms -> viewModel.scrubTo(ms) },
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
                            onCloseExportSheet = { viewModel.closeExportSheet() },
                            onPlaybackStateChange = { playing -> viewModel.onPlaybackStateChange(playing) },
                            onPlayheadUpdate = { ms -> viewModel.onPlayheadUpdate(ms) }
                        )
                    }

                    AppScreen.EXPORT -> {
                        com.flowcut.app.ui.ExportScreen(
                            uiState = uiState,
                            aspectRatioMode = uiState.aspectRatioMode,
                            onBackClick = { viewModel.returnToEditorFromExport() },
                            onCancelExport = { reason -> viewModel.cancelExport(reason) },
                            onRetryExport = { viewModel.startExport() },
                            onShareExport = {
                                uiState.exportedUri?.let { uri ->
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(Intent.createChooser(shareIntent, "Share video"))
                                }
                            }
                        )
                    }
                }

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
        jankStats?.isTrackingEnabled = false
        viewModel.onAppBackground()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppStop()
    }

    override fun onResume() {
        super.onResume()
        jankStats?.isTrackingEnabled = true
        viewModel.onAppResume()
    }

    private fun testShaderWarmup() {
        Thread {
            try {
                val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val vers = IntArray(2)
                EGL14.eglInitialize(dpy, vers, 0, vers, 1)
                val configAttrs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, 0x0040,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                EGL14.eglChooseConfig(dpy, configAttrs, 0, configs, 0, 1, numConfigs, 0)
                val ctxAttrs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE
                )
                val ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
                val pbufAttrs = intArrayOf(
                    EGL14.EGL_WIDTH, 64,
                    EGL14.EGL_HEIGHT, 64,
                    EGL14.EGL_NONE
                )
                val surf = EGL14.eglCreatePbufferSurface(dpy, configs[0], pbufAttrs, 0)
                EGL14.eglMakeCurrent(dpy, surf, surf, ctx)

                android.util.Log.d("FlowCutPerf", "Offscreen EGL initialized for shader test")
                val renderer = TransitionRenderer(cacheDir)
                val t0 = android.os.SystemClock.elapsedRealtime()
                renderer.init()
                val initMs = android.os.SystemClock.elapsedRealtime() - t0
                android.util.Log.d("FlowCutPerf", "TransitionRenderer init finished in ${initMs} ms")

                renderer.release()
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, surf)
                EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            } catch (e: Exception) {
                android.util.Log.e("FlowCutPerf", "testShaderWarmup failed", e)
            }
        }.start()
    }
}
