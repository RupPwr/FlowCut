package com.flowcut.app.core.edit

import com.flowcut.app.core.model.Project
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Single gate for all project modifications (Section 9 & Section 15).
 * Enforces sequential command execution, invariant validation, undo tracking with
 * coalesceKey support, background snapshot generation, and debounced project persistence.
 */
class EditorController(
    val holder: SnapshotHolder = SnapshotHolder(),
    val undoStack: UndoStack = UndoStack(),
    initialProject: Project = Project(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val saveDebounceMs: Long = 500L,
    private val onPausePlayback: () -> Unit = {},
    private val onWakeFrameDriver: () -> Unit = {},
    private val onReject: (String) -> Unit = {},
    private val onProjectChanged: (Project) -> Unit = {},
    private val projectSaver: (suspend (Project) -> Unit)? = null
) {
    private val commandMutex = Mutex()
    private val versionCounter = AtomicLong(1L)
    private var saveJob: Job? = null

    var currentProject: Project = initialProject
        private set

    var lastRejectionReason: String? = null
        private set

    private val _projectFlow = MutableStateFlow(initialProject)
    val projectFlow: StateFlow<Project> get() = _projectFlow.asStateFlow()

    private val _rejectionFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val rejectionFlow: SharedFlow<String> get() = _rejectionFlow.asSharedFlow()

    val canUndo: Boolean get() = undoStack.canUndo
    val canRedo: Boolean get() = undoStack.canRedo

    init {
        val initialSnapshot = TimelineSnapshot(versionCounter.get(), initialProject)
        holder.current = initialSnapshot
        undoStack.currentProject = initialProject
    }

    /**
     * Dispatches a command sequentially through the single edit gate.
     *
     * Flow:
     * 1. If [cmd.pausesPlayback], pause playback immediately.
     * 2. Compute `next = cmd.apply(currentProject)`.
     * 3. Validate invariants. If invalid, reject without mutating state and emit reason.
     * 4. Push current state to [UndoStack] (coalescing if consecutive keys match).
     * 5. Build a new [TimelineSnapshot] on [defaultDispatcher].
     * 6. Atomically update [holder.current] and notify listeners / frame driver.
     * 7. Trigger debounced save on [ioDispatcher].
     *
     * @return true if accepted and applied, false if rejected.
     */
    suspend fun dispatch(cmd: Command): Boolean = commandMutex.withLock {
        if (cmd.pausesPlayback) {
            onPausePlayback()
        }

        val next = cmd.apply(currentProject)
        val validation = Validator.validate(next)
        if (validation is ValidationResult.Invalid) {
            lastRejectionReason = validation.reason
            _rejectionFlow.tryEmit(validation.reason)
            onReject(validation.reason)
            return false
        }

        lastRejectionReason = null
        undoStack.push(currentProject, cmd.coalesceKey)
        currentProject = next
        undoStack.currentProject = next
        _projectFlow.value = next

        val newVersion = versionCounter.incrementAndGet()
        val snapshot = withContext(defaultDispatcher) {
            TimelineSnapshot(newVersion, next)
        }
        holder.current = snapshot

        onProjectChanged(next)
        onWakeFrameDriver()
        scheduleSave(next)
        return true
    }

    /**
     * Undoes the last edit step, updates the snapshot, and triggers notifications.
     */
    suspend fun undo(): Boolean = commandMutex.withLock {
        onPausePlayback()
        val previous = undoStack.undo(currentProject) ?: return false
        val validation = Validator.validate(previous)
        if (validation is ValidationResult.Invalid) {
            lastRejectionReason = validation.reason
            _rejectionFlow.tryEmit(validation.reason)
            onReject(validation.reason)
            return false
        }

        lastRejectionReason = null
        currentProject = previous
        _projectFlow.value = previous

        val newVersion = versionCounter.incrementAndGet()
        val snapshot = withContext(defaultDispatcher) {
            TimelineSnapshot(newVersion, previous)
        }
        holder.current = snapshot

        onProjectChanged(previous)
        onWakeFrameDriver()
        scheduleSave(previous)
        return true
    }

    /**
     * Redoes the next edit step, updates the snapshot, and triggers notifications.
     */
    suspend fun redo(): Boolean = commandMutex.withLock {
        onPausePlayback()
        val next = undoStack.redo(currentProject) ?: return false
        val validation = Validator.validate(next)
        if (validation is ValidationResult.Invalid) {
            lastRejectionReason = validation.reason
            _rejectionFlow.tryEmit(validation.reason)
            onReject(validation.reason)
            return false
        }

        lastRejectionReason = null
        currentProject = next
        _projectFlow.value = next

        val newVersion = versionCounter.incrementAndGet()
        val snapshot = withContext(defaultDispatcher) {
            TimelineSnapshot(newVersion, next)
        }
        holder.current = snapshot

        onProjectChanged(next)
        onWakeFrameDriver()
        scheduleSave(next)
        return true
    }

    private fun scheduleSave(project: Project) {
        val saver = projectSaver ?: return
        saveJob?.cancel()
        saveJob = scope.launch(ioDispatcher) {
            delay(saveDebounceMs)
            saver(project)
        }
    }

    suspend fun flushSave() {
        val saver = projectSaver ?: return
        saveJob?.cancel()
        saveJob = null
        withContext(ioDispatcher) {
            saver(currentProject)
        }
    }
}

