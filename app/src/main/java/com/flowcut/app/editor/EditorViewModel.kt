package com.flowcut.app.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowcut.app.audio.ClipAudioContributor
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.ClipTransform
import com.flowcut.app.engine.ExportEngine
import com.flowcut.app.engine.PreviewEngine
import com.flowcut.app.engine.PreviewRenderer
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

enum class AppScreen {
    HOME,
    EDITOR
}

data class EditorUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
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
    val toastMessage: String? = null,
    val isMainTrackMuted: Boolean = false
)

data class SplitResult(
    val newSnapshot: TimelineSnapshot,
    val newRightClipIndex: Int
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

    private val undoTimeMaps = ArrayDeque<Map<String, TimeMap>>()
    private val redoTimeMaps = ArrayDeque<Map<String, TimeMap>>()
    val clipTimeMaps = mutableMapOf<String, TimeMap>()
    val clipNaturalDurations = mutableMapOf<String, Long>()

    // Per-clip volume (0.0f to 2.0f, default 1.0f), pre-mute memory, and undo/redo stacks
    private val undoVolumes = ArrayDeque<Map<String, Float>>()
    private val redoVolumes = ArrayDeque<Map<String, Float>>()
    val clipVolumes = mutableMapOf<String, Float>()
    val savedPreMuteVolumes = mutableMapOf<String, Float>()

    // Per-clip transform (rotation: 0/90/180/270, isFlippedH: Boolean) and undo/redo stacks
    private val undoTransforms = ArrayDeque<Map<String, ClipTransform>>()
    private val redoTransforms = ArrayDeque<Map<String, ClipTransform>>()
    val clipTransforms = mutableMapOf<String, ClipTransform>()

    // Per-clip color adjustments and undo/redo stacks (Requirement 1 & 4)
    private val undoColorAdjusts = ArrayDeque<Map<String, ClipColorAdjust>>()
    private val redoColorAdjusts = ArrayDeque<Map<String, ClipColorAdjust>>()
    val clipColorAdjusts = mutableMapOf<String, ClipColorAdjust>()

    fun getClipColorAdjust(clipId: String): ClipColorAdjust =
        clipColorAdjusts[clipId] ?: ClipColorAdjust.DEFAULT

    fun commitClipColorAdjust(clipIndex: Int, adjust: ClipColorAdjust) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        clipColorAdjusts[clip.id] = adjust
        PreviewRenderer.setClipColorAdjust(clip.id, adjust)

        updateProject(currentProject, recordUndo = false)
    }

    fun commitApplyColorAdjustToAll(adjust: ClipColorAdjust) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        for (c in currentProject.clips) {
            clipColorAdjusts[c.id] = adjust
            PreviewRenderer.setClipColorAdjust(c.id, adjust)
        }

        updateProject(currentProject, recordUndo = false)
    }

    fun getClipTransform(clipId: String): ClipTransform =
        clipTransforms[clipId] ?: ClipTransform.DEFAULT

    fun rotateSelectedClip(): ClipTransform? {
        val selectedIndex = _uiState.value.selectedClipIndex ?: return null
        val clip = _uiState.value.project.clips.getOrNull(selectedIndex) ?: return null
        val current = clipTransforms[clip.id] ?: ClipTransform.DEFAULT
        val nextDeg = (((Math.round(current.rotationDeg / 90.0) * 90 + 90) % 360 + 360) % 360).toFloat()
        val newTransform = current.copy(rotationDeg = nextDeg)

        val currentProject = _uiState.value.project
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        clipTransforms[clip.id] = newTransform
        PreviewRenderer.setClipTransform(clip.id, newTransform)

        updateProject(currentProject, recordUndo = false)
        return newTransform
    }

    fun flipSelectedClip(): ClipTransform? {
        val selectedIndex = _uiState.value.selectedClipIndex ?: return null
        val clip = _uiState.value.project.clips.getOrNull(selectedIndex) ?: return null
        val current = clipTransforms[clip.id] ?: ClipTransform.DEFAULT
        val newTransform = current.copy(isFlippedH = !current.isFlippedH)

        val currentProject = _uiState.value.project
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        clipTransforms[clip.id] = newTransform
        PreviewRenderer.setClipTransform(clip.id, newTransform)

        updateProject(currentProject, recordUndo = false)
        return newTransform
    }

    fun commitClipTransform(clipIndex: Int, transform: ClipTransform) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        clipTransforms[clip.id] = transform
        PreviewRenderer.setClipTransform(clip.id, transform)

        updateProject(currentProject, recordUndo = false)
    }

    fun applyTransformToAll(sourceClipId: String) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return
        val sourceTransform = clipTransforms[sourceClipId] ?: ClipTransform.DEFAULT

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        for (c in currentProject.clips) {
            val current = clipTransforms[c.id] ?: ClipTransform.DEFAULT
            val newTransform = current.copy(
                scale = sourceTransform.scale,
                panX = sourceTransform.panX,
                panY = sourceTransform.panY,
                rotationDeg = sourceTransform.rotationDeg,
                isFlippedH = sourceTransform.isFlippedH
            )
            clipTransforms[c.id] = newTransform
            PreviewRenderer.setClipTransform(c.id, newTransform)
        }

        updateProject(currentProject, recordUndo = false)
    }

    fun commitClipScale(clipIndex: Int, scale: Float, panX: Float, panY: Float) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return
        val current = clipTransforms[clip.id] ?: ClipTransform.DEFAULT
        val newTransform = current.copy(
            scale = scale.coerceIn(0.5f, 4.0f),
            panX = panX.coerceIn(-1.0f, 1.0f),
            panY = panY.coerceIn(-1.0f, 1.0f)
        )

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        clipTransforms[clip.id] = newTransform
        PreviewRenderer.setClipTransform(clip.id, newTransform)

        updateProject(currentProject, recordUndo = false)
    }

    fun commitApplyScaleToAll(scale: Float, panX: Float, panY: Float) {
        val currentProject = _uiState.value.project
        if (currentProject.clips.isEmpty()) return

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        val clampedScale = scale.coerceIn(0.5f, 4.0f)
        val clampedPanX = panX.coerceIn(-1.0f, 1.0f)
        val clampedPanY = panY.coerceIn(-1.0f, 1.0f)

        for (c in currentProject.clips) {
            val current = clipTransforms[c.id] ?: ClipTransform.DEFAULT
            val newTransform = current.copy(
                scale = clampedScale,
                panX = clampedPanX,
                panY = clampedPanY
            )
            clipTransforms[c.id] = newTransform
            PreviewRenderer.setClipTransform(c.id, newTransform)
        }

        updateProject(currentProject, recordUndo = false)
    }

    fun getClipVolume(clipId: String): Float = clipVolumes[clipId] ?: 1.0f

    fun getPreMuteVolume(clipId: String): Float = savedPreMuteVolumes[clipId] ?: 1.0f

    fun isAllClipsMuted(): Boolean {
        val clips = _uiState.value.project.clips
        return clips.isNotEmpty() && clips.all { (clipVolumes[it.id] ?: 1.0f) <= 0.0001f }
    }

    /**
     * Toggles timeline track-level mute state with 100% bidirectional sync:
     * - If any clip is currently unmuted (> 0f): saves non-zero volumes and mutes all clips to 0f.
     * - If all clips are currently muted (<= 0.0001f): restores each clip to its saved pre-mute volume (or 1.0f).
     */
    fun toggleTrackMute() {
        val currentProject = _uiState.value.project
        val clips = currentProject.clips
        if (clips.isEmpty()) return

        val currentlyAllMuted = isAllClipsMuted()

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        if (!currentlyAllMuted) {
            // Mute everything: save current non-zero volumes
            for (c in clips) {
                val currentVol = clipVolumes[c.id] ?: 1.0f
                if (currentVol > 0.0001f) {
                    savedPreMuteVolumes[c.id] = currentVol
                }
                clipVolumes[c.id] = 0.0f
                ClipAudioContributor.setClipVolume(c.id, 0.0f)
            }
            ClipAudioContributor.isMainTrackMuted = true
            _uiState.update { it.copy(isMainTrackMuted = true) }
        } else {
            // Unmute everything: restore saved pre-mute volumes or default to 1.0f
            for (c in clips) {
                val restored = savedPreMuteVolumes[c.id]?.takeIf { it > 0.0001f } ?: 1.0f
                clipVolumes[c.id] = restored
                ClipAudioContributor.setClipVolume(c.id, restored)
            }
            ClipAudioContributor.isMainTrackMuted = false
            _uiState.update { it.copy(isMainTrackMuted = false) }
        }

        updateProject(currentProject, recordUndo = false)
    }

    fun commitClipVolume(clipIndex: Int, volume: Float) {
        val currentProject = _uiState.value.project
        val clip = currentProject.clips.getOrNull(clipIndex) ?: return
        val clampedVol = volume.coerceIn(0.0f, 2.0f)

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        clipVolumes[clip.id] = clampedVol
        ClipAudioContributor.setClipVolume(clip.id, clampedVol)
        if (clampedVol > 0.0001f) {
            savedPreMuteVolumes[clip.id] = clampedVol
        }

        val isAllMuted = isAllClipsMuted()
        ClipAudioContributor.isMainTrackMuted = isAllMuted
        _uiState.update { it.copy(isMainTrackMuted = isAllMuted) }

        updateProject(currentProject, recordUndo = false)
    }

    fun commitApplyVolumeToAll(volume: Float) {
        val currentProject = _uiState.value.project
        val clampedVol = volume.coerceIn(0.0f, 2.0f)

        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        for (c in currentProject.clips) {
            clipVolumes[c.id] = clampedVol
            ClipAudioContributor.setClipVolume(c.id, clampedVol)
            if (clampedVol > 0.0001f) {
                savedPreMuteVolumes[c.id] = clampedVol
            }
        }

        val isAllMuted = clampedVol <= 0.0001f
        ClipAudioContributor.isMainTrackMuted = isAllMuted
        _uiState.update { it.copy(isMainTrackMuted = isAllMuted) }

        updateProject(currentProject, recordUndo = false)
    }

    fun getClipNaturalDurationUs(clipIndex: Int): Long {
        val clip = _uiState.value.project.clips.getOrNull(clipIndex) ?: return 0L
        return clipNaturalDurations.getOrPut(clip.id) {
            getMediaFileDurationUs(clip)
        }
    }

    private fun getMediaFileDurationUs(clip: Clip): Long {
        return try {
            val file = java.io.File(clip.path)
            if (file.exists()) {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                durStr?.toLongOrNull()?.let { msToUs(it) } ?: msToUs(clip.durationMs)
            } else {
                msToUs(clip.durationMs)
            }
        } catch (_: Exception) {
            msToUs(clip.durationMs)
        }
    }

    fun commitClipTrim(clipIndex: Int, newInUs: Long, newOutUs: Long) {
        val currentProject = _uiState.value.project
        if (clipIndex !in currentProject.clips.indices) return
        val oldClip = currentProject.clips[clipIndex]
        val newDurationMs = usToMs(newOutUs - newInUs)
        if (newDurationMs < 200L) return

        val newClip = oldClip.copy(durationMs = newDurationMs)
        val newClips = currentProject.clips.toMutableList()
        newClips[clipIndex] = newClip

        // Adjust adjacent transitions if needed (Section 4 invariants: transition <= min(d1, d2) / 2)
        val newTransitions = currentProject.transitions.toMutableList()
        for (j in maxOf(0, clipIndex - 1)..minOf(newTransitions.size - 1, clipIndex)) {
            val maxAllowed = minOf(newClips[j].durationMs, newClips[j + 1].durationMs) / 2L
            if (newTransitions[j].durationMs > maxAllowed) {
                newTransitions[j] = newTransitions[j].copy(durationMs = maxAllowed)
            }
        }

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()
        clipTimeMaps[oldClip.id] = TimeMap(newInUs, newOutUs)
        updateProject(newProject, recordUndo = false)
    }

    fun splitAtPlayhead(playheadUs: Long, preferredClipIndex: Int? = null): SplitResult? {
        val currentProject = _uiState.value.project
        val tl = ProgramTimeline(currentProject)
        if (tl.n == 0) {
            showToast("No clips to split")
            return null
        }

        val tMs = usToMs(playheadUs)

        // 1. Exact Target Clip & Local Cut Position at playhead.timeUs
        val targetK: Int = if (preferredClipIndex != null && preferredClipIndex in 0 until tl.n) {
            val clipStartUs = msToUs(tl.startMs[preferredClipIndex])
            val clipEndUs = msToUs(tl.endMs[preferredClipIndex])
            if (playheadUs > clipStartUs && playheadUs < clipEndUs) {
                preferredClipIndex
            } else {
                val candidate = tl.lastStartAtOrBefore(tMs)
                if (candidate in 0 until tl.n && tMs >= tl.startMs[candidate] && tMs < tl.endMs[candidate]) {
                    candidate
                } else -1
            }
        } else {
            val candidate = tl.lastStartAtOrBefore(tMs)
            if (candidate in 0 until tl.n && tMs >= tl.startMs[candidate] && tMs < tl.endMs[candidate]) {
                candidate
            } else -1
        }

        if (targetK !in 0 until tl.n) {
            showToast("No clip under playhead to split")
            return null
        }

        // 2. Enforce 0.2s (200 ms / 200_000L us) Minimum Clip Duration Rule
        val startUs = msToUs(tl.startMs[targetK])
        val localCutUs = playheadUs - startUs
        val clipDurationUs = msToUs(tl.durMs[targetK])

        if (localCutUs < 200_000L || (clipDurationUs - localCutUs) < 200_000L) {
            showToast("Cannot split: clips must be at least 0.2s")
            return null
        }

        // 3. Frame-Accurate TimeMap Split & Natural Duration Preservation
        val oldClip = currentProject.clips[targetK]
        val oldMap = clipTimeMaps[oldClip.id] ?: TimeMap(0L, msToUs(oldClip.durationMs))

        val leftDurMs = usToMs(localCutUs)
        val alignedLocalCutUs = msToUs(leftDurMs)
        val splitSourceUs = msToUs(usToMs(oldMap.sourceUs(alignedLocalCutUs)))
        val rightDurMs = oldClip.durationMs - leftDurMs

        val leftMap = TimeMap(
            inUs = oldMap.inUs,
            outUs = splitSourceUs,
            speedNum = oldMap.speedNum,
            speedDen = oldMap.speedDen
        )
        val rightClipId = java.util.UUID.randomUUID().toString()
        val rightMap = TimeMap(
            inUs = splitSourceUs,
            outUs = oldMap.outUs,
            speedNum = oldMap.speedNum,
            speedDen = oldMap.speedDen
        )

        val leftClip = oldClip.copy(durationMs = leftDurMs)
        val rightClip = oldClip.copy(id = rightClipId, durationMs = rightDurMs)

        val newClips = currentProject.clips.toMutableList()
        newClips[targetK] = leftClip
        newClips.add(targetK + 1, rightClip)

        // 4. Transitions: insert NONE at junction targetK and preserve existing transitions
        val newTransitions = ArrayList<Transition>(currentProject.transitions.size + 1)
        for (i in 0 until targetK) {
            newTransitions.add(currentProject.transitions[i])
        }
        newTransitions.add(Transition(TransitionType.NONE, 0L))
        for (i in targetK until currentProject.transitions.size) {
            newTransitions.add(currentProject.transitions[i])
        }

        // Adjust adjacent transitions if needed (duration <= min(d1, d2) / 2)
        for (j in maxOf(0, targetK - 1)..minOf(newTransitions.size - 1, targetK + 1)) {
            if (j < newTransitions.size && j + 1 < newClips.size) {
                val maxAllowed = minOf(newClips[j].durationMs, newClips[j + 1].durationMs) / 2L
                if (newTransitions[j].durationMs > maxAllowed) {
                    newTransitions[j] = newTransitions[j].copy(durationMs = maxAllowed)
                }
            }
        }

        val newProject = currentProject.copy(clips = newClips, transitions = newTransitions)

        // Push current state onto Undo stack BEFORE applying the split
        undoStack.push(currentProject)
        undoTimeMaps.addLast(HashMap(clipTimeMaps))
        undoVolumes.addLast(HashMap(clipVolumes))
        undoTransforms.addLast(HashMap(clipTransforms))
        undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
        redoTimeMaps.clear()
        redoVolumes.clear()
        redoTransforms.clear()
        redoColorAdjusts.clear()

        // Register natural duration for both halves
        val naturalDur = clipNaturalDurations[oldClip.id] ?: getMediaFileDurationUs(oldClip)
        clipNaturalDurations[oldClip.id] = naturalDur
        clipNaturalDurations[rightClipId] = naturalDur

        // Update clipTimeMaps
        clipTimeMaps[oldClip.id] = leftMap
        clipTimeMaps[rightClipId] = rightMap

        // Inherit volume and pre-mute memory for both halves
        val origVol = clipVolumes[oldClip.id] ?: 1.0f
        clipVolumes[oldClip.id] = origVol
        clipVolumes[rightClipId] = origVol
        savedPreMuteVolumes[oldClip.id]?.let { savedPreMuteVolumes[rightClipId] = it }
        ClipAudioContributor.setClipVolume(oldClip.id, origVol)
        ClipAudioContributor.setClipVolume(rightClipId, origVol)

        // Inherit transform for both halves
        val origTransform = clipTransforms[oldClip.id] ?: ClipTransform.DEFAULT
        clipTransforms[oldClip.id] = origTransform
        clipTransforms[rightClipId] = origTransform
        PreviewRenderer.setClipTransform(oldClip.id, origTransform)
        PreviewRenderer.setClipTransform(rightClipId, origTransform)

        // Inherit color adjust for both halves (Requirement 4)
        val origAdjust = clipColorAdjusts[oldClip.id] ?: ClipColorAdjust.DEFAULT
        clipColorAdjusts[oldClip.id] = origAdjust
        clipColorAdjusts[rightClipId] = origAdjust
        PreviewRenderer.setClipColorAdjust(oldClip.id, origAdjust)
        PreviewRenderer.setClipColorAdjust(rightClipId, origAdjust)

        // Update project and select the new Right Clip (targetK + 1)
        updateProject(newProject, recordUndo = false)
        _uiState.update {
            it.copy(
                selectedClipIndex = targetK + 1,
                selectedJunctionIndex = null
            )
        }

        val newSnapshot = getSnapshot()
        return SplitResult(newSnapshot, targetK + 1)
    }

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
            for (clip in loaded.clips) {
                if (!clipNaturalDurations.containsKey(clip.id)) {
                    clipNaturalDurations[clip.id] = getMediaFileDurationUs(clip)
                }
            }
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
            undoTimeMaps.addLast(HashMap(clipTimeMaps))
            undoVolumes.addLast(HashMap(clipVolumes))
            undoTransforms.addLast(HashMap(clipTransforms))
            redoTimeMaps.clear()
            redoVolumes.clear()
            redoTransforms.clear()
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
        val engine = previewEngine
        if (engine != null) {
            if (engine.isPlaying) {
                engine.pause()
            } else {
                engine.play()
            }
        } else {
            _uiState.update { it.copy(isPlaying = !it.isPlaying) }
        }
    }

    fun pause() {
        val engine = previewEngine
        if (engine != null) {
            if (engine.isPlaying) {
                engine.pause()
            }
        } else {
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    private var preciseSeekJob: Job? = null

    fun scrubTo(ms: Long) {
        val engine = previewEngine
        if (engine != null) {
            if (engine.isPlaying) {
                engine.pause()
            }
            _uiState.update { it.copy(currentMs = ms) }
            engine.seekTo(ms, fastScrub = true)

            // Debounce precise seek by 120ms (Section 19.8)
            preciseSeekJob?.cancel()
            preciseSeekJob = viewModelScope.launch {
                delay(120L)
                engine.seekTo(ms, fastScrub = false)
            }
        } else {
            _uiState.update { it.copy(currentMs = ms, isPlaying = false) }
        }
    }

    fun seekTo(ms: Long) {
        val engine = previewEngine
        if (engine != null) {
            if (engine.isPlaying) {
                engine.pause()
            }
            engine.seekTo(ms)
            _uiState.update { it.copy(currentMs = ms) }
        } else {
            _uiState.update { it.copy(currentMs = ms, isPlaying = false) }
        }
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
        val deletedId = _uiState.value.project.clips.getOrNull(index)?.id
        if (deletedId != null) {
            clipTimeMaps.remove(deletedId)
            clipNaturalDurations.remove(deletedId)
            clipVolumes.remove(deletedId)
            savedPreMuteVolumes.remove(deletedId)
            ClipAudioContributor.clipVolumes.remove(deletedId)
            clipTransforms.remove(deletedId)
            PreviewRenderer.clipTransforms.remove(deletedId)
            clipColorAdjusts.remove(deletedId)
            PreviewRenderer.clipColorAdjusts.remove(deletedId)
        }
        val newProject = ProgramTimeline.deleteClip(_uiState.value.project, index)
        val isAllMuted = newProject.clips.isNotEmpty() && newProject.clips.all { (clipVolumes[it.id] ?: 1.0f) <= 0.0001f }
        ClipAudioContributor.isMainTrackMuted = isAllMuted
        updateProject(newProject, recordUndo = true)
        _uiState.update {
            it.copy(
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                isMainTrackMuted = isAllMuted
            )
        }
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun openExistingProject() {
        _uiState.update { it.copy(currentScreen = AppScreen.EDITOR) }
        previewEngine?.seekTo(_uiState.value.currentMs)
    }

    fun resetProject(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            projectStore.clearAll()
            undoStack.clear()
            undoTimeMaps.clear()
            redoTimeMaps.clear()
            clipTimeMaps.clear()
            clipNaturalDurations.clear()
            undoVolumes.clear()
            redoVolumes.clear()
            clipVolumes.clear()
            savedPreMuteVolumes.clear()
            ClipAudioContributor.clipVolumes.clear()
            ClipAudioContributor.isMainTrackMuted = false
            undoTransforms.clear()
            redoTransforms.clear()
            clipTransforms.clear()
            PreviewRenderer.clipTransforms.clear()
            undoColorAdjusts.clear()
            redoColorAdjusts.clear()
            clipColorAdjusts.clear()
            PreviewRenderer.clipColorAdjusts.clear()
            _uiState.update {
                it.copy(
                    project = Project(),
                    currentMs = 0L,
                    selectedClipIndex = null,
                    selectedJunctionIndex = null,
                    isMainTrackMuted = false,
                    canUndo = false,
                    canRedo = false
                )
            }
            previewEngine?.let { engine ->
                engine.timeline = ProgramTimeline(Project())
                engine.seekTo(0L)
            }
            onComplete()
        }
    }

    fun createNewProject(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val urisToProcess = uris.take(MAX_CLIPS)

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressText = "Importing 1/${urisToProcess.size}..."
                )
            }

            val imported = clipImporter.importUris(
                uris = urisToProcess,
                currentTotalMs = 0L,
                onProgress = { cur, tot ->
                    _uiState.update { it.copy(importProgressText = "Importing $cur/$tot...") }
                },
                onError = { message ->
                    showToast(message)
                }
            )

            if (imported.isNotEmpty()) {
                for (c in imported) {
                    clipNaturalDurations[c.id] = msToUs(c.durationMs)
                }
                val newProject = ProgramTimeline.addClips(Project(), imported)
                undoStack.clear()
                updateProject(newProject, recordUndo = false)
                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.EDITOR,
                        currentMs = 0L,
                        selectedClipIndex = null,
                        selectedJunctionIndex = null
                    )
                }
                previewEngine?.seekTo(0L)
            }

            _uiState.update {
                it.copy(isImporting = false, importProgressText = "")
            }
        }
    }

    fun importClips(uris: List<Uri>) {
        val currentClipsCount = _uiState.value.project.clips.size
        if (currentClipsCount >= MAX_CLIPS) {
            showToast("Maximum clip limit reached ($MAX_CLIPS clips)")
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
                for (c in imported) {
                    clipNaturalDurations[c.id] = msToUs(c.durationMs)
                }
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
        if (undoTimeMaps.isNotEmpty()) {
            val prevMaps = undoTimeMaps.removeLast()
            redoTimeMaps.addLast(HashMap(clipTimeMaps))
            clipTimeMaps.clear()
            clipTimeMaps.putAll(prevMaps)
        }
        if (undoVolumes.isNotEmpty()) {
            val prevVols = undoVolumes.removeLast()
            redoVolumes.addLast(HashMap(clipVolumes))
            clipVolumes.clear()
            clipVolumes.putAll(prevVols)
            ClipAudioContributor.syncVolumes(clipVolumes)
        }
        if (undoTransforms.isNotEmpty()) {
            val prevTransforms = undoTransforms.removeLast()
            redoTransforms.addLast(HashMap(clipTransforms))
            clipTransforms.clear()
            clipTransforms.putAll(prevTransforms)
            PreviewRenderer.syncTransforms(clipTransforms)
        }
        if (undoColorAdjusts.isNotEmpty()) {
            val prevAdjusts = undoColorAdjusts.removeLast()
            redoColorAdjusts.addLast(HashMap(clipColorAdjusts))
            clipColorAdjusts.clear()
            clipColorAdjusts.putAll(prevAdjusts)
            PreviewRenderer.syncColorAdjusts(clipColorAdjusts)
        }
        val isAllMuted = previous.clips.isNotEmpty() && previous.clips.all { (clipVolumes[it.id] ?: 1.0f) <= 0.0001f }
        ClipAudioContributor.isMainTrackMuted = isAllMuted
        updateProject(previous, recordUndo = false)
        _uiState.update {
            it.copy(
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                isMainTrackMuted = isAllMuted
            )
        }
    }

    fun redo() {
        val next = undoStack.redo(_uiState.value.project) ?: return
        if (redoTimeMaps.isNotEmpty()) {
            val nextMaps = redoTimeMaps.removeLast()
            undoTimeMaps.addLast(HashMap(clipTimeMaps))
            clipTimeMaps.clear()
            clipTimeMaps.putAll(nextMaps)
        }
        if (redoVolumes.isNotEmpty()) {
            val nextVols = redoVolumes.removeLast()
            undoVolumes.addLast(HashMap(clipVolumes))
            clipVolumes.clear()
            clipVolumes.putAll(nextVols)
            ClipAudioContributor.syncVolumes(clipVolumes)
        }
        if (redoTransforms.isNotEmpty()) {
            val nextTransforms = redoTransforms.removeLast()
            undoTransforms.addLast(HashMap(clipTransforms))
            clipTransforms.clear()
            clipTransforms.putAll(nextTransforms)
            PreviewRenderer.syncTransforms(clipTransforms)
        }
        if (redoColorAdjusts.isNotEmpty()) {
            val nextAdjusts = redoColorAdjusts.removeLast()
            undoColorAdjusts.addLast(HashMap(clipColorAdjusts))
            clipColorAdjusts.clear()
            clipColorAdjusts.putAll(nextAdjusts)
            PreviewRenderer.syncColorAdjusts(clipColorAdjusts)
        }
        val isAllMuted = next.clips.isNotEmpty() && next.clips.all { (clipVolumes[it.id] ?: 1.0f) <= 0.0001f }
        ClipAudioContributor.isMainTrackMuted = isAllMuted
        updateProject(next, recordUndo = false)
        _uiState.update {
            it.copy(
                selectedClipIndex = null,
                selectedJunctionIndex = null,
                isMainTrackMuted = isAllMuted
            )
        }
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

    fun getSnapshot(): TimelineSnapshot {
        val project = _uiState.value.project
        val coreClips = project.clips.map { clip ->
            val timeMap = clipTimeMaps[clip.id]
            com.flowcut.app.core.model.Clip(
                id = clip.id,
                path = clip.path,
                durationMs = clip.durationMs,
                width = clip.width,
                height = clip.height,
                rotation = clip.rotation,
                frameRate = clip.frameRate,
                timeMap = timeMap
            )
        }
        val coreTransitions = project.transitions.map { trans ->
            com.flowcut.app.core.model.Transition(
                type = when (trans.type) {
                    TransitionType.NONE -> com.flowcut.app.core.model.TransitionType.NONE
                    TransitionType.SLIDE -> com.flowcut.app.core.model.TransitionType.SLIDE
                    TransitionType.CIRCLE -> com.flowcut.app.core.model.TransitionType.CIRCLE
                    TransitionType.ZOOM -> com.flowcut.app.core.model.TransitionType.ZOOM
                    TransitionType.FADE -> com.flowcut.app.core.model.TransitionType.FADE
                },
                durationMs = trans.durationMs
            )
        }
        return TimelineSnapshot(System.nanoTime(), com.flowcut.app.core.model.Project(coreClips, coreTransitions))
    }

    fun startExport() {
        exportJob?.cancel()
        _uiState.update { it.copy(exportState = ExportState.Exporting(0f)) }

        val snapshot = getSnapshot()
        exportJob = viewModelScope.launch {
            try {
                val uri = exportEngine.export(snapshot) { progress ->
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

