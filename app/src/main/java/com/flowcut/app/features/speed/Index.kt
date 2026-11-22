/**
 * Feature:     speed
 * File role:   index
 * Responsible: Public API of the speed feature module
 * Depends on:  core/edit, core/model, core/time, features/speed/SpeedUi
 * Used by:     app, ui
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.speed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flowcut.app.core.edit.Command
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.engine.PreviewRenderer
import com.flowcut.app.model.Project

/**
 * Public entry point for the Speed feature.
 * External modules must ONLY import from this file.
 */
object SpeedFeature {
    const val ID = "speed"

    /**
     * Preset speeds list.
     */
    val PRESETS = SpeedLogic.PRESET_SPEEDS

    /**
     * Creates a command to apply speed to a single clip.
     */
    fun setSpeed(clipIndex: Int, clip: Clip, speed: Float): Command {
        return SpeedCommands.createSpeedCommand(clipIndex, clip, speed)
    }

    /**
     * Creates a command to apply speed across all clips.
     */
    fun applyAllSpeed(speed: Float): Command {
        return SpeedCommands.createApplyAllSpeedCommand(speed)
    }

    /**
     * Pure TimeMap generator for speed.
     */
    fun createTimeMap(currentMap: TimeMap?, clipDurationMs: Long, speed: Float): TimeMap {
        return SpeedLogic.createSpeedTimeMap(currentMap, clipDurationMs, speed)
    }
}

/**
 * Public Composable for the Speed bottom sheet.
 */
@Composable
fun SpeedSheet(
    clipIndex: Int,
    snapshotHolder: SnapshotHolder,
    previewRenderer: PreviewRenderer,
    onWakeFrameDriver: () -> Unit = {},
    getClipSpeed: (clipId: String) -> Float,
    getMaxSpeedForClip: (clipIndex: Int) -> Float,
    onSetLiveSpeed: (clipIndex: Int, speed: Float) -> Unit,
    onCommitSingleSpeed: (clipIndex: Int, speed: Float, initialProject: Project, initialTimeMaps: Map<String, TimeMap>) -> Unit,
    onCommitApplyAllSpeed: (speed: Float, initialProject: Project, initialTimeMaps: Map<String, TimeMap>) -> Unit,
    onRevertSpeed: (initialProject: Project, initialTimeMaps: Map<String, TimeMap>) -> Unit,
    initialProject: Project,
    initialTimeMaps: Map<String, TimeMap>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    SpeedSheetUi(
        clipIndex = clipIndex,
        snapshotHolder = snapshotHolder,
        previewRenderer = previewRenderer,
        onWakeFrameDriver = onWakeFrameDriver,
        getClipSpeed = getClipSpeed,
        getMaxSpeedForClip = getMaxSpeedForClip,
        onSetLiveSpeed = onSetLiveSpeed,
        onCommitSingleSpeed = onCommitSingleSpeed,
        onCommitApplyAllSpeed = onCommitApplyAllSpeed,
        onRevertSpeed = onRevertSpeed,
        initialProject = initialProject,
        initialTimeMaps = initialTimeMaps,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
