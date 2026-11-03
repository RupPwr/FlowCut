package com.flowcut.app.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowcut.app.engine.ExportEngine
import com.flowcut.app.engine.PreviewEngine
import com.flowcut.app.model.AUTO_PLAY_MARGIN_MS
import com.flowcut.app.model.Clip
import com.flowcut.app.model.MAX_CLIPS
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.Project
import com.flowcut.app.model.TRANSITION_DEFAULT_MS
import com.flowcut.app.model.Transition
import com.flowcut.app.model.TransitionType
import com.flowcut.app.storage.ClipImporter
import com.flowcut.app.storage.ProjectStore
import com.flowcut.app.ui.ExportState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val project: Project = Project(),
    val currentMs: Long = 0L,
    val isPlaying: Boolean = false,
    val selectedClipIndex: Int? = null,
    val selectedJunctionIndex: Int? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isImporting: Boolean = false,
    val importProgressText: String = "",
    val durationSheetVisible: Boolean = false,
    val exportSheetVisible: Boolean = false,
    val exportState: ExportState = ExportState.Ready,
    val exportedUri: Uri? = null,
    val toastMessage: String? = null
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val projectStore = ProjectStore(application)
    private val clipImporter = ClipImporter(application)
    private val exportEngine = ExportEngine(application)
    private val undoStack = UndoStack()

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    val timeline: ProgramTimeline
        get() = ProgramTimeline(_uiState.value.project)

    var previewEngine: PreviewEngine? = null

    private var autoSaveJob: Job? = null
    private var exportJob: Job? = null
    private var autoPlayJob: Job? = null

    private var preDragProjectSnapshot: Project? = null

    init {
        loadInitialProject()
    }

    fun initPreviewEngine(engine: PreviewEngine) {
        previewEngine = engine
        engine.timeline = timeline
    }

    private fun loadInitialProject() {
        viewModelScope.launch {
            val loaded = projectStore.load()
            projectStore.cleanupOrphanClips(loaded)
            _uiState.update {
                it.copy(
                    project = loaded,
                    currentMs = 0L,
                    canUndo = undoStack.canUndo,
                    canRedo = undoStack.canRedo
                )
            }
            previewEngine?.let { engine ->
                engine.timeline = ProgramTimeline(loaded)
                engine.seekTo(0L)
            }
        }
    }

    private fun updateProject(newProject: Project, recordUndo: Boolean = true) {
        if (recordUndo) {
            undoStack.push(_uiState.value.project)
        }
        val newTimeline = ProgramTimeline(newProject)
        val clampedMs = _uiState.value.currentMs.coerceIn(0L, maxOf(0L, newTimeline.total))

        _uiState.update {
            it.copy(
                project = newProject,
                currentMs = clampedMs,
                canUndo = undoStack.canUndo,
                canRedo = undoStack.canRedo
            )
        }

        previewEngine?.let { engine ->
            engine.timeline = newTimeline
            engine.seekTo(clampedMs)
        }

        // Debounced 500ms auto-save
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(500L)
            projectStore.save(newProject)
        }
    }

    fun onPlayheadUpdate(newMs: Long) {
        _uiState.update { it.copy(currentMs = newMs) }
    }

    fun onPlaybackStateChange(isPlaying: Boolean) {
        _uiState.update { it.copy(isPlaying = isPlaying) }
    }

    fun playPause() {
        val engine = previewEngine ?: return
        if (engine.isPlaying) {
            engine.pause()
        } else {
            engine.play()
        }
    }

    fun seekTo(ms: Long) {
        val engine = previewEngine ?: return
        if (engine.isPlaying) {
            engine.pause()
        }
        engine.seekTo(ms)
        _uiState.update { it.copy(currentMs = ms) }
    }

    fun selectClip(index: Int) {
        _uiState.update {
            it.copy(
                selectedClipIndex = index,
                selectedJunctionIndex = null
            )
        }
    }

    fun selectJunction(index: Int) {
        _uiState.update {
            it.copy(
                selectedJunctionIndex = index,
                selectedClipIndex = null
            )
        }
    }

    fun deselectAll() {
        _uiState.update {
            it.copy(
                selectedClipIndex = null,
                selectedJunctionIndex = null
            )
        }
    }

    fun deleteClip(index: Int) {
        val newProject = ProgramTimeline.deleteClip(_uiState.value.project, index)
        updateProject(newProject, recordUndo = true)
        _uiState.update {
            it.copy(
                selectedClipIndex = null,
                selectedJunctionIndex = null
            )
        }
    }

    fun importClips(uris: List<Uri>) {
        val currentClipsCount = _uiState.value.project.clips.size
        if (currentClipsCount >= MAX_CLIPS) {
            showToast("You can add up to 10 clips")
            return
        }

        val availableSlots = MAX_CLIPS - currentClipsCount
        val urisToProcess = uris.take(availableSlots)

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressText = "Importing 1/${urisToProcess.size}..."
                )
            }

            val imported = clipImporter.importUris(
                uris = urisToProcess,
                currentTotalMs = timeline.total,
                onProgress = { cur, tot ->
                    _uiState.update { it.copy(importProgressText = "Importing $cur/$tot...") }
                },
                onError = { message ->
                    showToast(message)
                }
            )

            if (imported.isNotEmpty()) {
                val newProject = ProgramTimeline.addClips(_uiState.value.project, imported)
                updateProject(newProject, recordUndo = true)
            }

            _uiState.update {
                it.copy(isImporting = false, importProgressText = "")
            }
        }
    }

    fun applyTransition(type: TransitionType) {
        val junctionIndex = _uiState.value.selectedJunctionIndex ?: return
        val currentT = _uiState.value.project.transitions.getOrNull(junctionIndex) ?: return

        if (currentT.type == type) {
            // Same type: open Duration sheet
            _uiState.update { it.copy(durationSheetVisible = true) }
            return
        }

        val maxDur = timeline.maxDuration(junctionIndex)
        if (maxDur < 300L) {
            showToast("Clip is too short for this transition")
            return
        }

        val newDuration = if (currentT.type == TransitionType.NONE) {
            minOf(TRANSITION_DEFAULT_MS, maxDur)
        } else {
            minOf(currentT.durationMs, maxDur)
        }

        val newTransitions = _uiState.value.project.transitions.toMutableList()
        newTransitions[junctionIndex] = Transition(type, newDuration)
        val newProject = _uiState.value.project.copy(transitions = newTransitions)

        updateProject(newProject, recordUndo = true)

        // Auto-play preview:
        // overlapStart - 700 ms to overlapEnd + 700 ms, then pause
        val tl = ProgramTimeline(newProject)
        val overlapStart = tl.start[junctionIndex + 1]
        val overlapEnd = tl.end[junctionIndex]
        val playStart = maxOf(0L, overlapStart - AUTO_PLAY_MARGIN_MS)
        val playEnd = minOf(tl.total, overlapEnd + AUTO_PLAY_MARGIN_MS)

        seekTo(playStart)
        previewEngine?.play()

        autoPlayJob?.cancel()
        autoPlayJob = viewModelScope.launch {
            while (true) {
                delay(16L)
                val cur = _uiState.value.currentMs
                if (cur >= playEnd || !_uiState.value.isPlaying) {
                    previewEngine?.pause()
                    break
                }
            }
        }
    }

    fun onDurationSliderStart() {
        preDragProjectSnapshot = _uiState.value.project
    }

    fun updateTransitionDurationLive(durMs: Long) {
        val junctionIndex = _uiState.value.selectedJunctionIndex ?: return
        val currentT = _uiState.value.project.transitions.getOrNull(junctionIndex) ?: return
        val newTransitions = _uiState.value.project.transitions.toMutableList()
        newTransitions[junctionIndex] = currentT.copy(durationMs = durMs)
        val newProject = _uiState.value.project.copy(transitions = newTransitions)

        // Live update without undo step
        updateProject(newProject, recordUndo = false)
    }

    fun commitTransitionDuration(finalMs: Long) {
        val snapshot = preDragProjectSnapshot
        if (snapshot != null) {
            undoStack.push(snapshot)
            preDragProjectSnapshot = null
            _uiState.update {
                it.copy(
                    canUndo = undoStack.canUndo,
                    canRedo = undoStack.canRedo
                )
            }
        }
        updateTransitionDurationLive(finalMs)
    }

    fun removeTransition() {
        val junctionIndex = _uiState.value.selectedJunctionIndex ?: return
        val newTransitions = _uiState.value.project.transitions.toMutableList()
        newTransitions[junctionIndex] = Transition(TransitionType.NONE, 0L)
        val newProject = _uiState.value.project.copy(transitions = newTransitions)
        updateProject(newProject, recordUndo = true)
        _uiState.update { it.copy(durationSheetVisible = false) }
    }

    fun closeDurationSheet() {
        _uiState.update { it.copy(durationSheetVisible = false) }
    }

    fun undo() {
        val previous = undoStack.undo(_uiState.value.project) ?: return
        updateProject(previous, recordUndo = false)
    }

    fun redo() {
        val next = undoStack.redo(_uiState.value.project) ?: return
        updateProject(next, recordUndo = false)
    }

    fun openExportSheet() {
        _uiState.update {
            it.copy(
                exportSheetVisible = true,
                exportState = ExportState.Ready
            )
        }
    }

    fun closeExportSheet() {
        if (_uiState.value.exportState is ExportState.Exporting) return
        _uiState.update { it.copy(exportSheetVisible = false) }
    }

    fun startExport() {
        exportJob?.cancel()
        _uiState.update { it.copy(exportState = ExportState.Exporting(0f)) }

        exportJob = viewModelScope.launch {
            try {
                val uri = exportEngine.export(timeline) { progress ->
                    _uiState.update { it.copy(exportState = ExportState.Exporting(progress)) }
                }
                _uiState.update {
                    it.copy(
                        exportState = ExportState.Done,
                        exportedUri = uri
                    )
                }
            } catch (e: Exception) {
                if (_uiState.value.exportState !is ExportState.Ready) {
                    _uiState.update { it.copy(exportState = ExportState.Failed) }
                }
            }
        }
    }

    fun cancelExport() {
        exportEngine.cancel()
        exportJob?.cancel()
        _uiState.update {
            it.copy(
                exportState = ExportState.Ready,
                exportSheetVisible = false
            )
        }
    }

    fun onAppBackground() {
        previewEngine?.onPause()
        if (_uiState.value.exportState is ExportState.Exporting) {
            cancelExport()
            showToast("Export stopped because the app went to background")
        }
    }

    fun onAppResume() {
        previewEngine?.onResume()
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        previewEngine?.release()
    }
}
