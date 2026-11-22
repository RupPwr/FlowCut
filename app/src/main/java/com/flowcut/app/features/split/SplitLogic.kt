/**
 * Feature:     split
 * File role:   logic
 * Responsible: Pure calculation of clip boundary cuts, TimeMap division, and minimum duration invariants
 * Depends on:  core/model, core/time, shared
 * Used by:     SplitCommands, SplitUi
 * Never touches: Preview/Player directly, other features
 */
package com.flowcut.app.features.split

import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.map
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.shared.SharedConstants

/**
 * Result of pure split validation and boundary calculation.
 */
sealed class SplitValidationResult {
    data class Valid(
        val targetIndex: Int,
        val leftClip: Clip,
        val rightClip: Clip,
        val leftTimeMap: TimeMap,
        val rightTimeMap: TimeMap
    ) : SplitValidationResult()

    data class Invalid(val reason: String) : SplitValidationResult()
}

object SplitLogic {

    /**
     * Minimum clip duration required for splitting (200ms in microseconds).
     */
    const val MIN_SPLIT_DURATION_US = SharedConstants.MIN_TRIM_DURATION_US

    /**
     * Pure validation and calculation of clip split at given timeline playhead position.
     */
    fun calculateSplit(
        clipIndex: Int,
        clip: Clip,
        clipStartMs: Long,
        playheadUs: Long,
        newRightClipId: String = java.util.UUID.randomUUID().toString()
    ): SplitValidationResult {
        val clipStartUs = msToUs(clipStartMs)
        val clipDurationUs = msToUs(clip.durationMs)
        val localCutUs = playheadUs - clipStartUs

        if (localCutUs < MIN_SPLIT_DURATION_US || (clipDurationUs - localCutUs) < MIN_SPLIT_DURATION_US) {
            return SplitValidationResult.Invalid("Cannot split: clips must be at least 0.2s")
        }

        val oldMap = clip.map()
        val leftDurMs = usToMs(localCutUs)
        val alignedLocalCutUs = msToUs(leftDurMs)
        val splitSourceUs = msToUs(usToMs(oldMap.sourceUs(alignedLocalCutUs)))
        val rightDurMs = clip.durationMs - leftDurMs

        val leftMap = TimeMap(
            inUs = oldMap.inUs,
            outUs = splitSourceUs,
            speedNum = oldMap.speedNum,
            speedDen = oldMap.speedDen
        )
        val rightMap = TimeMap(
            inUs = splitSourceUs,
            outUs = oldMap.outUs,
            speedNum = oldMap.speedNum,
            speedDen = oldMap.speedDen
        )

        val leftClip = clip.copy(durationMs = leftDurMs, timeMap = leftMap)
        val rightClip = clip.copy(id = newRightClipId, durationMs = rightDurMs, timeMap = rightMap)

        return SplitValidationResult.Valid(
            targetIndex = clipIndex,
            leftClip = leftClip,
            rightClip = rightClip,
            leftTimeMap = leftMap,
            rightTimeMap = rightMap
        )
    }
}
