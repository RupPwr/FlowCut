package com.flowcut.app.editor

import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.TimelineSnapshot
import com.flowcut.app.engine.AspectRatioMode
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.getLayerCap
import com.flowcut.app.model.Clip
import com.flowcut.app.model.MAX_TOTAL_MS
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.Project
import com.flowcut.app.model.Transition
import com.flowcut.app.model.TransitionType

enum class OverlayEndAlignment {
    CLIP_START,
    CLIP_END,
    VIDEO_START,
    VIDEO_END,
    LEFT_CLIP_END,
    LEFT_VIDEO_END,
    RIGHT_CLIP_END,
    RIGHT_VIDEO_END
}

val MAX_TIMELINE_DURATION_US: Long = msToUs(MAX_TOTAL_MS)

fun getLayerCapLimitMessage(type: LayerType): String {
    val cap = getLayerCap(type)
    val name = when (type) {
        LayerType.TEXT -> "Text"
        LayerType.MOSAIC -> "Mosaic"
        LayerType.STICKER -> "Sticker"
        LayerType.IMAGE, LayerType.VIDEO -> "PIP"
        LayerType.AUDIO -> "Audio"
        LayerType.FILTER -> "Filter"
        LayerType.EFFECT -> "Effect"
    }
    return "Maximum limit reached: $cap $name"
}

/**
 * Single canonical conversion from legacy editor [Project] + sidecar [clipTimeMaps]
 * into immutable [com.flowcut.app.core.model.Project] used by Preview, Audio, and Export.
 */
fun toCoreProject(
    project: Project,
    clipTimeMaps: Map<String, TimeMap>
): com.flowcut.app.core.model.Project {
    val coreClips = project.clips.map { clip ->
        com.flowcut.app.core.model.Clip(
            id = clip.id,
            path = clip.path,
            durationMs = clip.durationMs,
            width = clip.width,
            height = clip.height,
            rotation = clip.rotation,
            frameRate = clip.frameRate,
            timeMap = clipTimeMaps[clip.id],
            renderParams = clip.renderParams
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
    return com.flowcut.app.core.model.Project(coreClips, coreTransitions)
}

fun buildTimelineSnapshot(
    project: Project,
    clipTimeMaps: Map<String, TimeMap>,
    version: Long = System.nanoTime()
): TimelineSnapshot = TimelineSnapshot(version, toCoreProject(project, clipTimeMaps))

fun resolveEffectiveAspectForClip(mode: AspectRatioMode, firstClip: Clip?): Float {
    if (mode == AspectRatioMode.ORIGINAL) {
        if (firstClip != null) {
            val w = if (firstClip.rotation == 90 || firstClip.rotation == 270) firstClip.height else firstClip.width
            val h = if (firstClip.rotation == 90 || firstClip.rotation == 270) firstClip.width else firstClip.height
            if (w > 0 && h > 0) return w.toFloat() / h.toFloat()
        }
        return 9f / 16f
    }
    return if (mode.ratioWidth > 0 && mode.ratioHeight > 0) {
        mode.ratioWidth.toFloat() / mode.ratioHeight.toFloat()
    } else if (mode.aspect > 0f) {
        mode.aspect
    } else {
        9f / 16f
    }
}

fun findMatchingAspectRatioModeForDimensions(displayWidth: Int, displayHeight: Int): AspectRatioMode {
    if (displayWidth <= 0 || displayHeight <= 0) return AspectRatioMode.RATIO_9_16
    val clipAspect = displayWidth.toFloat() / displayHeight.toFloat()
    val standardModes = listOf(
        AspectRatioMode.RATIO_16_9,
        AspectRatioMode.RATIO_9_16,
        AspectRatioMode.RATIO_1_1,
        AspectRatioMode.RATIO_4_5,
        AspectRatioMode.RATIO_4_3,
        AspectRatioMode.RATIO_3_4,
        AspectRatioMode.RATIO_5_4,
        AspectRatioMode.RATIO_3_2,
        AspectRatioMode.RATIO_2_3,
        AspectRatioMode.RATIO_2_1,
        AspectRatioMode.RATIO_1_2,
        AspectRatioMode.RATIO_21_9
    )
    var bestMode: AspectRatioMode? = null
    var minDiff = Float.MAX_VALUE
    for (mode in standardModes) {
        val diff = kotlin.math.abs(clipAspect - mode.aspect)
        if (diff < minDiff) {
            minDiff = diff
            bestMode = mode
        }
    }
    return if (minDiff <= 0.02f && bestMode != null) {
        bestMode
    } else {
        AspectRatioMode.ORIGINAL
    }
}

fun mapTransitionModeToModelType(mode: Int): TransitionType = when (mode) {
    1 -> TransitionType.FADE    // Dissolve
    2 -> TransitionType.FADE    // Black Fade
    3 -> TransitionType.FADE    // White Flash
    4 -> TransitionType.SLIDE   // Slide Left
    5 -> TransitionType.SLIDE   // Slide Right
    6 -> TransitionType.ZOOM    // Zoom In
    7 -> TransitionType.ZOOM    // Zoom Out
    8 -> TransitionType.CIRCLE  // Vertical Wipe
    else -> TransitionType.NONE // None (0)
}

fun inferTransitionModeFromModelType(type: TransitionType?): Int = when (type) {
    TransitionType.SLIDE -> 4
    TransitionType.CIRCLE -> 8
    TransitionType.ZOOM -> 6
    TransitionType.FADE -> 1
    else -> 0
}

/**
 * Enforces the transition duration invariant (`durationMs <= min(left.durationMs, right.durationMs) / 2`)
 * across the inclusive junction range [startJunction..endJunction].
 */
fun clampAdjacentTransitionDurations(
    clips: List<Clip>,
    transitions: MutableList<Transition>,
    startJunction: Int = 0,
    endJunction: Int = transitions.lastIndex
) {
    if (transitions.isEmpty() || clips.size < 2) return
    val from = startJunction.coerceAtLeast(0)
    val to = endJunction.coerceAtMost(minOf(transitions.lastIndex, clips.size - 2))
    for (j in from..to) {
        val maxAllowed = minOf(clips[j].durationMs, clips[j + 1].durationMs) / 2L
        if (transitions[j].durationMs > maxAllowed) {
            transitions[j] = transitions[j].copy(durationMs = maxAllowed)
        }
    }
}

/**
 * Shifts custom GL transition style modes when a new NONE junction is inserted at [insertedJunctionIndex]
 * (e.g., during clip split or duplicate). Keeps indices `< insertedJunctionIndex` and shifts `>= insertedJunctionIndex` by +1.
 */
fun shiftJunctionModesOnInsert(
    modes: Map<Int, Int>,
    insertedJunctionIndex: Int
): Map<Int, Int> {
    if (modes.isEmpty()) return emptyMap()
    val updated = HashMap<Int, Int>(modes.size)
    for ((j, mode) in modes) {
        if (mode <= 0) continue
        if (j < insertedJunctionIndex) {
            updated[j] = mode
        } else {
            updated[j + 1] = mode
        }
    }
    return updated
}

/**
 * Shifts custom GL transition style modes when clip [deletedClipIndex] is removed from a timeline
 * that previously had [oldClipCount] clips. Matches [ProgramTimeline.deleteClip] junction remapping 1:1.
 */
fun shiftJunctionModesOnDeleteClip(
    modes: Map<Int, Int>,
    deletedClipIndex: Int,
    oldClipCount: Int
): Map<Int, Int> {
    if (modes.isEmpty() || oldClipCount <= 2) return emptyMap()
    val updated = HashMap<Int, Int>(modes.size)
    val lastOldClip = oldClipCount - 1
    for ((j, mode) in modes) {
        if (mode <= 0) continue
        when {
            deletedClipIndex == 0 -> {
                if (j >= 1) updated[j - 1] = mode
            }
            deletedClipIndex == lastOldClip -> {
                if (j < lastOldClip - 1) updated[j] = mode
            }
            else -> {
                // Middle clip removed: junctions (deletedClipIndex - 1) and (deletedClipIndex) collapse to NONE.
                if (j < deletedClipIndex - 1) {
                    updated[j] = mode
                } else if (j > deletedClipIndex) {
                    updated[j - 1] = mode
                }
            }
        }
    }
    return updated
}

fun computeRecommendedExportBitrateBps(resolution: Int, fps: Int): Int {
    val baseBps = when {
        resolution <= 480 -> 4_000_000
        resolution <= 720 -> 8_000_000
        resolution <= 1080 -> 14_000_000
        resolution <= 1440 -> 24_000_000
        else -> 45_000_000
    }
    return if (fps >= 50) (baseBps * 1.35f).toInt() else baseBps
}

fun computeTargetExportBitrateBps(resolution: Int, fps: Int, bitratePreset: Int): Int {
    val baseBps = when {
        resolution <= 480 -> when (bitratePreset) {
            0 -> 2_500_000
            2 -> 6_000_000
            else -> 4_000_000
        }
        resolution <= 720 -> when (bitratePreset) {
            0 -> 5_000_000
            2 -> 12_000_000
            else -> 8_000_000
        }
        resolution <= 1080 -> when (bitratePreset) {
            0 -> 8_000_000
            2 -> 22_000_000
            else -> 14_000_000
        }
        resolution <= 1440 -> when (bitratePreset) {
            0 -> 16_000_000
            2 -> 35_000_000
            else -> 24_000_000
        }
        else -> when (bitratePreset) {
            0 -> 30_000_000
            2 -> 60_000_000
            else -> 45_000_000
        }
    }
    return if (fps >= 50) (baseBps * 1.35f).toInt() else baseBps
}

fun resolveOverlayHandleSnapBounds(
    itemStartUs: Long,
    itemEndUs: Long,
    isRightHandle: Boolean,
    action: OverlayEndAlignment,
    tl: ProgramTimeline,
    minSpanUs: Long = OverlayItem.MIN_DURATION_US
): Pair<Long, Long> {
    val totalDurationUs = msToUs(tl.totalMs)
    if (tl.n == 0) return Pair(itemStartUs, itemEndUs)

    val canonicalAction = when (action) {
        OverlayEndAlignment.CLIP_START -> OverlayEndAlignment.CLIP_START
        OverlayEndAlignment.CLIP_END,
        OverlayEndAlignment.LEFT_CLIP_END,
        OverlayEndAlignment.RIGHT_CLIP_END -> OverlayEndAlignment.CLIP_END
        OverlayEndAlignment.VIDEO_START -> OverlayEndAlignment.VIDEO_START
        OverlayEndAlignment.VIDEO_END,
        OverlayEndAlignment.LEFT_VIDEO_END,
        OverlayEndAlignment.RIGHT_VIDEO_END -> OverlayEndAlignment.VIDEO_END
    }

    if (!isRightHandle) {
        val clampedHandleUs = itemStartUs.coerceIn(0L, totalDurationUs)
        val clipIdx = (tl.n - 1 downTo 0).firstOrNull { i ->
            clampedHandleUs >= msToUs(tl.startMs[i]) && clampedHandleUs <= msToUs(tl.endMs[i])
        } ?: (0 until tl.n).firstOrNull { i ->
            clampedHandleUs in msToUs(tl.startMs[i])..msToUs(tl.endMs[i])
        } ?: tl.lastStartAtOrBefore(usToMs(clampedHandleUs)).coerceIn(0, maxOf(0, tl.n - 1))

        val clipStartUs = msToUs(tl.startMs[clipIdx])
        val clipEndUs = msToUs(tl.endMs[clipIdx])

        val newStartUs = when (canonicalAction) {
            OverlayEndAlignment.CLIP_START -> {
                clipStartUs.coerceIn(0L, maxOf(0L, itemEndUs - minSpanUs))
            }
            OverlayEndAlignment.CLIP_END -> {
                minOf(clipEndUs, itemEndUs - minSpanUs).coerceAtLeast(clipStartUs)
            }
            OverlayEndAlignment.VIDEO_START -> {
                0L.coerceIn(0L, maxOf(0L, itemEndUs - minSpanUs))
            }
            OverlayEndAlignment.VIDEO_END -> {
                minOf(totalDurationUs, maxOf(0L, itemEndUs - minSpanUs))
            }
            else -> itemStartUs
        }
        return Pair(newStartUs, itemEndUs)
    } else {
        val clampedHandleUs = itemEndUs.coerceIn(0L, totalDurationUs)
        val clipIdx = (0 until tl.n).firstOrNull { i ->
            clampedHandleUs >= msToUs(tl.startMs[i]) && clampedHandleUs <= msToUs(tl.endMs[i])
        } ?: tl.lastStartAtOrBefore(usToMs(clampedHandleUs)).coerceIn(0, maxOf(0, tl.n - 1))

        val clipStartUs = msToUs(tl.startMs[clipIdx])
        val clipEndUs = msToUs(tl.endMs[clipIdx])

        val newEndUs = when (canonicalAction) {
            OverlayEndAlignment.CLIP_END -> {
                clipEndUs.coerceIn(minOf(totalDurationUs, itemStartUs + minSpanUs), totalDurationUs)
            }
            OverlayEndAlignment.CLIP_START -> {
                maxOf(clipStartUs, itemStartUs + minSpanUs).coerceAtMost(clipEndUs)
            }
            OverlayEndAlignment.VIDEO_END -> {
                totalDurationUs.coerceIn(minOf(totalDurationUs, itemStartUs + minSpanUs), totalDurationUs)
            }
            OverlayEndAlignment.VIDEO_START -> {
                maxOf(0L, minOf(totalDurationUs, itemStartUs + minSpanUs))
            }
            else -> itemEndUs
        }
        return Pair(itemStartUs, newEndUs)
    }
}
