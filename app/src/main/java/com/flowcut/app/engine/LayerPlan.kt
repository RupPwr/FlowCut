package com.flowcut.app.engine

import java.util.TreeSet

/**
 * Supported timeline layer types with enforced maximum item caps.
 */
enum class LayerType(val maxCap: Int) {
    VIDEO(maxCap = 40),
    TEXT(maxCap = 40),
    IMAGE(maxCap = 40),
    STICKER(maxCap = 40),
    FILTER(maxCap = 40),
    EFFECT(maxCap = 40),
    AUDIO(maxCap = 40),
    MOSAIC(maxCap = 40)
}

/**
 * Core model representing a single layer item on the multi-layer timeline.
 * Minimum duration is 200,000 microseconds (200ms).
 */
data class OverlayItem(
    val id: String,
    val type: LayerType,
    val laneIndex: Int,
    val zIndex: Int, // Deterministic back-to-front rendering order
    val startUs: Long,
    val endUs: Long,
    val transform: ClipTransform = ClipTransform(),
    val colorAdjust: ClipColorAdjust = ClipColorAdjust(),
    val text: String = "",
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val bgStyle: Int = 0,
    val fontIndex: Int = 0,
    val textAlpha: Float = 1.0f,
    val labelColorArgb: Int = 0x00000000,
    val labelAlpha: Float = 0.0f,
    val borderColorArgb: Int = 0xFF000000.toInt(),
    val borderAlpha: Float = 0.0f,
    val shadowColorArgb: Int = 0xFF000000.toInt(),
    val shadowAlpha: Float = 0.6f,
    val fontName: String = "Roboto-Medium",
    val customFontPath: String = "",
    val textAlign: Int = 1, // 0 = Left, 1 = Center, 2 = Right
    val letterSpacing: Float = 0.0f,
    val lineSpacing: Float = 1.0f,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isUppercase: Boolean = false,
    val animInType: Int = 0,
    val animInDurationUs: Long = 500_000L,
    val animOutType: Int = 0,
    val animOutDurationUs: Long = 500_000L,
    val animLoopType: Int = 0,
    val mediaPath: String = "",
    val intrinsicWidth: Int = 0,
    val intrinsicHeight: Int = 0,
    val filterPresetId: Int = 0,
    val filterIntensity: Float = 1.0f,
    val effectPresetId: Int = 0,
    val effectIntensity: Float = 1.0f,
    val audioSubType: Int = 0,
    val volume: Float = 1.0f,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val speed: Float = 1.0f,
    val sourceInUs: Long = 0L,
    val naturalDurationUs: Long = 0L,
    val keyframes: List<OverlayKeyframe> = emptyList(),
    // Phase 7: Advanced GPU Tools (Mask, Crop, Chroma Cutout)
    val maskType: Int = 0, // 0 = None, 1 = Circle, 2 = Rounded Rect, 3 = Heart, 4 = Star, 5 = Linear Split, 6 = Mirror Band
    val maskFeather: Float = 0.05f, // 0.0f..0.5f soft edge feathering
    val maskInvert: Boolean = false,
    val cropLeft: Float = 0.0f,
    val cropTop: Float = 0.0f,
    val cropRight: Float = 1.0f,
    val cropBottom: Float = 1.0f,
    val chromaEnabled: Boolean = false,
    val chromaColorArgb: Int = 0xFF00FF00.toInt(), // Default green screen #00FF00
    val chromaThreshold: Float = 0.25f, // 0.0f..0.8f
    val chromaBlend: Float = 0.10f, // 0.01f..0.4f
    // Phase 8: Per-Overlay Color Adjustment
    val adjustBrightness: Float = 0.0f, // -0.5f..0.5f
    val adjustContrast: Float = 1.0f, // 0.5f..1.8f
    val adjustSaturation: Float = 1.0f, // 0.0f..2.0f
    // Phase 9: Mosaic / Privacy Blur Layer
    val mosaicStyle: Int = 0, // 0 = Pixelate Grid, 1 = Frost/Box Blur, 2 = Halftone/Dot Matrix, 3 = Solid Blackout Censor Bar
    val mosaicIntensity: Float = 0.5f, // 0.1f..1.0f controlling pixel block size / blur radius
    val mosaicFeather: Float = 0.05f, // 0.0f..0.3f soft edge blending
    // Text Wrapping Constraint: 0.0f = auto / single-line, > 0.0f = normalized wrap width ratio (0.15f..1.4f)
    val wrapWidth: Float = 0.0f
) {
    companion object {
        const val MIN_DURATION_US: Long = 200_000L
    }

    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(0L)

    fun isValid(): Boolean = (endUs - startUs) >= MIN_DURATION_US
}

/**
 * Immutable keyframe representing spatial transform & opacity at an offset relative to item.startUs.
 */
data class OverlayKeyframe(
    val offsetUs: Long, // offset relative to item.startUs (0L .. item.durationUs)
    val panX: Float,
    val panY: Float,
    val scale: Float,
    val rotationDeg: Float,
    val opacity: Float = 1.0f
)

/**
 * Pre-allocated zero-allocation evaluator for time-based overlay animations and keyframe interpolation (Rule R10).
 * Evaluates keyframes, IN, OUT, and LOOP animations at 60fps without heap allocations.
 */
class AnimatedTransformEvaluator {
    val animatedTransform = ClipTransform()

    fun evaluate(item: OverlayItem, timeUs: Long): ClipTransform {
        val base = item.transform
        val inType = item.animInType
        val outType = item.animOutType
        val loopType = item.animLoopType
        val kfs = item.keyframes

        if (inType == 0 && outType == 0 && loopType == 0 && kfs.isEmpty()) {
            return base
        }

        val localUs = (timeUs - item.startUs).coerceIn(0L, item.durationUs)
        val remainingUs = (item.endUs - timeUs).coerceAtLeast(0L)
        val inDurUs = item.animInDurationUs.coerceAtLeast(100_000L)
        val outDurUs = item.animOutDurationUs.coerceAtLeast(100_000L)

        var opacity = base.opacity
        var scale = base.scale
        var panX = base.panX
        var panY = base.panY
        var rotationDeg = base.rotationDeg

        // 0. Keyframe Transform Interpolation (smoothstep 60fps zero-allocation)
        if (kfs.isNotEmpty()) {
            if (kfs.size == 1) {
                val kf = kfs[0]
                panX = kf.panX
                panY = kf.panY
                scale = kf.scale
                rotationDeg = kf.rotationDeg
                opacity = kf.opacity
            } else if (localUs <= kfs[0].offsetUs) {
                val kf = kfs[0]
                panX = kf.panX
                panY = kf.panY
                scale = kf.scale
                rotationDeg = kf.rotationDeg
                opacity = kf.opacity
            } else if (localUs >= kfs[kfs.size - 1].offsetUs) {
                val kf = kfs[kfs.size - 1]
                panX = kf.panX
                panY = kf.panY
                scale = kf.scale
                rotationDeg = kf.rotationDeg
                opacity = kf.opacity
            } else {
                var idx = 0
                for (i in 0 until kfs.size - 1) {
                    if (localUs >= kfs[i].offsetUs && localUs <= kfs[i + 1].offsetUs) {
                        idx = i
                        break
                    }
                }
                val kfA = kfs[idx]
                val kfB = kfs[idx + 1]
                val span = (kfB.offsetUs - kfA.offsetUs).coerceAtLeast(1L).toFloat()
                val linearT = ((localUs - kfA.offsetUs).toFloat() / span).coerceIn(0f, 1f)
                val t = linearT * linearT * (3f - 2f * linearT)

                panX = kfA.panX + (kfB.panX - kfA.panX) * t
                panY = kfA.panY + (kfB.panY - kfA.panY) * t
                scale = kfA.scale + (kfB.scale - kfA.scale) * t
                rotationDeg = kfA.rotationDeg + (kfB.rotationDeg - kfA.rotationDeg) * t
                opacity = kfA.opacity + (kfB.opacity - kfA.opacity) * t
            }
        }

        // 1. IN Animation (0=None, 1=Fade In, 2=Zoom In, 3=Zoom Out, 4=Slide L, 5=Slide R, 6=Slide Up, 7=Slide Down, 8=Spin In, 9=Pop Bounce)
        if (inType > 0 && localUs < inDurUs) {
            val progress = (localUs.toFloat() / inDurUs.toFloat()).coerceIn(0f, 1f)
            val inv = 1.0f - progress
            val easeOutCubic = 1.0f - inv * inv * inv
            when (inType) {
                1 -> {
                    opacity *= easeOutCubic
                }
                2 -> {
                    scale *= (0.05f + 0.95f * easeOutCubic)
                    opacity *= easeOutCubic
                }
                3 -> {
                    scale *= (1.0f + 1.5f * (1.0f - easeOutCubic))
                    opacity *= easeOutCubic
                }
                4 -> {
                    panX += (1.0f - easeOutCubic) * 0.75f
                    opacity *= (0.2f + 0.8f * easeOutCubic)
                }
                5 -> {
                    panX -= (1.0f - easeOutCubic) * 0.75f
                    opacity *= (0.2f + 0.8f * easeOutCubic)
                }
                6 -> {
                    panY -= (1.0f - easeOutCubic) * 0.75f
                    opacity *= (0.2f + 0.8f * easeOutCubic)
                }
                7 -> {
                    panY += (1.0f - easeOutCubic) * 0.75f
                    opacity *= (0.2f + 0.8f * easeOutCubic)
                }
                8 -> {
                    rotationDeg += (1.0f - easeOutCubic) * 360f
                    scale *= (0.1f + 0.9f * easeOutCubic)
                    opacity *= easeOutCubic
                }
                9 -> {
                    val overshoot = if (progress < 0.6f) {
                        (progress / 0.6f) * 1.45f
                    } else if (progress < 0.85f) {
                        1.45f - ((progress - 0.6f) / 0.25f) * 0.55f
                    } else {
                        0.90f + ((progress - 0.85f) / 0.15f) * 0.10f
                    }
                    scale *= overshoot
                    opacity *= (progress * 1.5f).coerceIn(0f, 1f)
                }
            }
        }

        // 2. OUT Animation (0=None, 1=Fade Out, 2=Zoom In, 3=Zoom Out, 4=Slide L, 5=Slide R, 6=Slide Up, 7=Slide Down)
        if (outType > 0 && remainingUs < outDurUs) {
            val progress = (remainingUs.toFloat() / outDurUs.toFloat()).coerceIn(0f, 1f)
            val inv = 1.0f - progress
            val easeInCubic = inv * inv * inv
            when (outType) {
                1 -> {
                    opacity *= progress
                }
                2 -> {
                    val s = 1.0f + 1.5f * easeInCubic
                    scale *= s
                    opacity *= progress
                }
                3 -> {
                    val s = (progress * progress).coerceAtLeast(0.01f)
                    scale *= s
                    opacity *= progress
                }
                4 -> {
                    panX -= easeInCubic * 0.75f
                    opacity *= progress
                }
                5 -> {
                    panX += easeInCubic * 0.75f
                    opacity *= progress
                }
                6 -> {
                    panY += easeInCubic * 0.75f
                    opacity *= progress
                }
                7 -> {
                    panY -= easeInCubic * 0.75f
                    opacity *= progress
                }
            }
        }

        // 3. LOOP Animation (0=None, 1=Pulse, 2=Float, 3=Pendulum, 4=Spin, 5=Blink, 6=Wiggle)
        if (loopType > 0) {
            val periodUs = 1_500_000L
            val loopPhase = ((localUs % periodUs).toFloat() / periodUs.toFloat()) * 2f * Math.PI.toFloat()
            val sinVal = kotlin.math.sin(loopPhase)
            val cosVal = kotlin.math.cos(loopPhase)

            when (loopType) {
                1 -> {
                    scale *= (1.0f + 0.25f * sinVal)
                }
                2 -> {
                    panY += 0.08f * sinVal
                }
                3 -> {
                    rotationDeg += 20f * sinVal
                }
                4 -> {
                    val rotPhase = ((localUs % 2_000_000L).toFloat() / 2_000_000f) * 360f
                    rotationDeg += rotPhase
                }
                5 -> {
                    val b = if (sinVal > 0f) 1.0f else 0.15f
                    opacity *= b
                }
                6 -> {
                    val quickSin = kotlin.math.sin(loopPhase * 4f)
                    rotationDeg += 16f * quickSin
                    panX += 0.04f * cosVal
                }
            }
        }

        animatedTransform.scale = scale
        animatedTransform.panX = panX
        animatedTransform.panY = panY
        animatedTransform.rotationDeg = rotationDeg
        animatedTransform.isFlippedH = base.isFlippedH
        animatedTransform.opacity = opacity.coerceIn(0f, 1f)

        return animatedTransform
    }
}

/**
 * Track group classification for the 4-row lower timeline engine.
 * Above Main Track: TEXT, STICKER.
 * Below Main Track: PIP (VIDEO/IMAGE) and AUDIO.
 */
enum class LayerTrackGroup {
    PIP,
    AUDIO
}

fun OverlayItem.trackGroup(): LayerTrackGroup? = when (type) {
    LayerType.VIDEO, LayerType.IMAGE -> LayerTrackGroup.PIP
    LayerType.AUDIO -> LayerTrackGroup.AUDIO
    else -> null
}

data class RowAllocationResult(
    val laneIndex: Int,
    val startUs: Long,
    val endUs: Long
)

/**
 * Returns the maximum allowed item count for the specified layer type.
 */
fun getLayerCap(targetType: LayerType): Int = when (targetType) {
    LayerType.TEXT -> 40
    LayerType.MOSAIC -> 40
    LayerType.STICKER -> 40
    LayerType.IMAGE, LayerType.VIDEO -> 40
    LayerType.AUDIO -> 40
    LayerType.FILTER -> 40
    LayerType.EFFECT -> 40
}

/**
 * Validates whether another layer item of the specified type can be added.
 * - Text: max 40
 * - Mosaic: max 40
 * - Sticker: max 40
 * - PIP (Image + Video combined): max 40 total items
 * - Audio (Music, Sounds, Local, Effects, Extract, Record): max 40 total items
 * - Filter: max 40
 * - Effect: max 40
 */
fun canAddLayerItem(currentItems: List<OverlayItem>, targetType: LayerType): Boolean = when (targetType) {
    LayerType.IMAGE, LayerType.VIDEO ->
        currentItems.count { it.type == LayerType.IMAGE || it.type == LayerType.VIDEO } < 40
    LayerType.TEXT -> currentItems.count { it.type == LayerType.TEXT } < 40
    LayerType.STICKER -> currentItems.count { it.type == LayerType.STICKER } < 40
    LayerType.MOSAIC -> currentItems.count { it.type == LayerType.MOSAIC } < 40
    LayerType.AUDIO -> currentItems.count { it.type == LayerType.AUDIO } < 40
    LayerType.FILTER -> currentItems.count { it.type == LayerType.FILTER } < 40
    LayerType.EFFECT -> currentItems.count { it.type == LayerType.EFFECT } < 40
}

/**
 * Validates whether another layer item of the specified type can be added.
 * Delegates directly to [canAddLayerItem].
 */
fun canAddLayer(items: List<OverlayItem>, type: LayerType): Boolean = canAddLayerItem(items, type)

/**
 * Deterministic 4-row non-overlapping track allocator for PIP and Audio groups.
 * Checks rows 0, 1, 2, 3 in order.
 * - Finds a row where the full duration fits without any collision.
 * - If no row fits fully, finds the first row in 0..3 that is free at desiredStartUs and has
 *   at least MIN_DURATION_US (200ms) before the next item, clamping endUs.
 * - If all 4 rows are occupied at desiredStartUs (or have < 200ms space), returns null.
 */
fun findAvailableRowOrClamp(
    items: List<OverlayItem>,
    group: LayerTrackGroup,
    desiredStartUs: Long,
    desiredDurationUs: Long,
    totalProjectDurationUs: Long,
    excludeItemId: String? = null
): RowAllocationResult? {
    val clampedStartUs = desiredStartUs.coerceAtLeast(0L)
    val maxProjectEndUs = totalProjectDurationUs.coerceAtLeast(clampedStartUs + OverlayItem.MIN_DURATION_US)
    val candidateEndUs = minOf(clampedStartUs + desiredDurationUs, maxProjectEndUs)
    if (candidateEndUs - clampedStartUs < OverlayItem.MIN_DURATION_US) return null

    val groupItems = items.filter { it.id != excludeItemId && it.trackGroup() == group }

    // Pass 1: Look for a row in 0..3 where the full duration fits without any collision
    for (row in 0..3) {
        val rowItems = groupItems.filter { it.laneIndex.coerceIn(0, 3) == row }
        val hasOverlap = rowItems.any { it.startUs < candidateEndUs && it.endUs > clampedStartUs }
        if (!hasOverlap) {
            return RowAllocationResult(
                laneIndex = row,
                startUs = clampedStartUs,
                endUs = candidateEndUs
            )
        }
    }

    // Pass 2: Look for a row in 0..3 that is free at clampedStartUs and has at least MIN_DURATION_US before the next item
    for (row in 0..3) {
        val rowItems = groupItems.filter { it.laneIndex.coerceIn(0, 3) == row }
        val occupiesStart = rowItems.any { it.startUs <= clampedStartUs && clampedStartUs < it.endUs }
        if (!occupiesStart) {
            val nextItemStartUs = rowItems
                .filter { it.startUs > clampedStartUs }
                .minOfOrNull { it.startUs }
            val availableEndUs = if (nextItemStartUs != null) {
                minOf(candidateEndUs, nextItemStartUs)
            } else {
                candidateEndUs
            }
            if (availableEndUs - clampedStartUs >= OverlayItem.MIN_DURATION_US) {
                return RowAllocationResult(
                    laneIndex = row,
                    startUs = clampedStartUs,
                    endUs = availableEndUs
                )
            }
        }
    }

    return null
}

/**
 * Clamps candidate trim timestamps (startUs and endUs) against neighboring items on the same row,
 * preventing any two items on the same row from overlapping in time.
 */
fun clampItemTrimBoundsOnRow(
    items: List<OverlayItem>,
    itemId: String,
    candidateStartUs: Long,
    candidateEndUs: Long,
    totalProjectDurationUs: Long
): Pair<Long, Long> {
    val target = items.firstOrNull { it.id == itemId } ?: return Pair(
        candidateStartUs.coerceAtLeast(0L),
        candidateEndUs.coerceAtLeast(candidateStartUs + OverlayItem.MIN_DURATION_US)
    )
    val group = target.trackGroup()
    val maxProjectTimeUs = totalProjectDurationUs.coerceAtLeast(OverlayItem.MIN_DURATION_US)

    // Calculate natural media limits if naturalDurationUs is populated
    val minAllowedStartFromSource = if (target.naturalDurationUs > 0L) {
        val speedVal = if (target.speed > 0f) target.speed.toDouble() else 1.0
        target.startUs - (target.sourceInUs.toDouble() / speedVal).toLong()
    } else 0L

    val maxAllowedEndFromNatural = if (target.naturalDurationUs > 0L) {
        val speedVal = if (target.speed > 0f) target.speed.toDouble() else 1.0
        val remainingMediaUs = (target.naturalDurationUs - target.sourceInUs).coerceAtLeast(0L)
        val maxTimelineSpanUs = (remainingMediaUs.toDouble() / speedVal).toLong()
        target.startUs + maxTimelineSpanUs
    } else Long.MAX_VALUE

    if (target.type == LayerType.FILTER || target.type == LayerType.EFFECT) {
        val sameTypeNeighbors = items.filter {
            it.id != itemId && it.type == target.type
        }

        val prevSameTypeEndUs = sameTypeNeighbors
            .filter { it.endUs <= target.startUs }
            .maxOfOrNull { it.endUs } ?: 0L

        val nextSameTypeStartUs = sameTypeNeighbors
            .filter { it.startUs >= target.endUs }
            .minOfOrNull { it.startUs } ?: maxProjectTimeUs

        val minAllowedStartUs = maxOf(0L, prevSameTypeEndUs, minAllowedStartFromSource)
        val maxAllowedEndUs = minOf(nextSameTypeStartUs, maxProjectTimeUs, maxAllowedEndFromNatural)

        val safeCandidateEndUs = candidateEndUs.coerceAtMost(maxAllowedEndUs)
        val clampedStartUs = candidateStartUs.coerceIn(
            minAllowedStartUs,
            (safeCandidateEndUs - OverlayItem.MIN_DURATION_US).coerceAtLeast(minAllowedStartUs)
        )
        val clampedEndUs = safeCandidateEndUs.coerceIn(
            clampedStartUs + OverlayItem.MIN_DURATION_US,
            maxAllowedEndUs.coerceAtLeast(clampedStartUs + OverlayItem.MIN_DURATION_US)
        )

        return Pair(clampedStartUs, clampedEndUs)
    }

    if (group == null) {
        val baseMinStart = maxOf(0L, minAllowedStartFromSource)
        val baseMaxEnd = minOf(maxProjectTimeUs, maxAllowedEndFromNatural)
        val clampedStart = candidateStartUs.coerceIn(baseMinStart, (candidateEndUs - OverlayItem.MIN_DURATION_US).coerceAtLeast(baseMinStart))
        val clampedEnd = candidateEndUs.coerceIn(clampedStart + OverlayItem.MIN_DURATION_US, baseMaxEnd.coerceAtLeast(clampedStart + OverlayItem.MIN_DURATION_US))
        return Pair(clampedStart, clampedEnd)
    }

    val sameRowNeighbors = items.filter {
        it.id != itemId && it.trackGroup() == group && it.laneIndex.coerceIn(0, 3) == target.laneIndex.coerceIn(0, 3)
    }

    val prevNeighborEndUs = sameRowNeighbors
        .filter { it.endUs <= target.startUs }
        .maxOfOrNull { it.endUs } ?: 0L

    val nextNeighborStartUs = sameRowNeighbors
        .filter { it.startUs >= target.endUs }
        .minOfOrNull { it.startUs } ?: maxProjectTimeUs

    val minAllowedStartUs = maxOf(prevNeighborEndUs, minAllowedStartFromSource)
    val maxAllowedEndUs = minOf(nextNeighborStartUs, maxProjectTimeUs, maxAllowedEndFromNatural)

    val safeCandidateEndUs = candidateEndUs.coerceAtMost(maxAllowedEndUs)
    val clampedStartUs = candidateStartUs.coerceIn(
        minAllowedStartUs,
        (safeCandidateEndUs - OverlayItem.MIN_DURATION_US).coerceAtLeast(minAllowedStartUs)
    )
    val clampedEndUs = safeCandidateEndUs.coerceIn(
        clampedStartUs + OverlayItem.MIN_DURATION_US,
        maxAllowedEndUs.coerceAtLeast(clampedStartUs + OverlayItem.MIN_DURATION_US)
    )

    return Pair(clampedStartUs, clampedEndUs)
}

/**
 * Computes a valid, non-overlapping drop position on the 4-row lower timeline (rows 0..3)
 * or anywhere on the timeline for Row A items (TEXT / STICKER).
 * Guarantees that two PIP items or two Audio items on the same row NEVER overlap.
 */
fun findValidDropPositionOnRows(
    items: List<OverlayItem>,
    itemId: String,
    targetLaneIndex: Int,
    desiredStartUs: Long,
    totalProjectDurationUs: Long
): RowAllocationResult? {
    val item = items.firstOrNull { it.id == itemId } ?: return null
    val durationUs = item.durationUs
    if (durationUs < OverlayItem.MIN_DURATION_US) return null

    val maxProjectEndUs = totalProjectDurationUs.coerceAtLeast(durationUs)
    val maxStartUs = (maxProjectEndUs - durationUs).coerceAtLeast(0L)
    val candidateStartUs = desiredStartUs.coerceIn(0L, maxStartUs)
    val candidateEndUs = candidateStartUs + durationUs

    val group = item.trackGroup()
    if (group == null) {
        // Text or Sticker (Row A)
        return RowAllocationResult(
            laneIndex = 0,
            startUs = candidateStartUs,
            endUs = candidateEndUs
        )
    }

    val sameGroupNeighbors = items.filter { it.id != itemId && it.trackGroup() == group }
    val desiredLane = targetLaneIndex.coerceIn(0, 3)

    // Option 1: Desired lane has NO overlap
    val targetLaneItems = sameGroupNeighbors.filter { it.laneIndex.coerceIn(0, 3) == desiredLane }
    val targetOverlap = targetLaneItems.any { it.startUs < candidateEndUs && it.endUs > candidateStartUs }
    if (!targetOverlap) {
        return RowAllocationResult(
            laneIndex = desiredLane,
            startUs = candidateStartUs,
            endUs = candidateEndUs
        )
    }

    // Option 2: Try other rows in 0..3 ordered by closeness to desiredLane
    val alternateRows = (0..3).sortedBy { kotlin.math.abs(it - desiredLane) }
    for (row in alternateRows) {
        if (row == desiredLane) continue
        val rowItems = sameGroupNeighbors.filter { it.laneIndex.coerceIn(0, 3) == row }
        val overlap = rowItems.any { it.startUs < candidateEndUs && it.endUs > candidateStartUs }
        if (!overlap) {
            return RowAllocationResult(
                laneIndex = row,
                startUs = candidateStartUs,
                endUs = candidateEndUs
            )
        }
    }

    // Option 3: Clamp flush against neighbor on desiredLane
    val prevNeighbor = targetLaneItems
        .filter { it.endUs <= candidateStartUs + (durationUs / 2L) }
        .maxByOrNull { it.endUs }
    val nextNeighbor = targetLaneItems
        .filter { it.startUs >= candidateEndUs - (durationUs / 2L) }
        .minByOrNull { it.startUs }

    if (prevNeighbor != null) {
        val flushStart = prevNeighbor.endUs
        val flushEnd = flushStart + durationUs
        val nextAfterFlush = targetLaneItems.filter { it.startUs >= flushEnd }.minOfOrNull { it.startUs } ?: maxProjectEndUs
        if (flushEnd <= nextAfterFlush && flushEnd <= maxProjectEndUs) {
            return RowAllocationResult(desiredLane, flushStart, flushEnd)
        }
    }

    if (nextNeighbor != null) {
        val flushEnd = nextNeighbor.startUs
        val flushStart = flushEnd - durationUs
        val prevBeforeFlush = targetLaneItems.filter { it.endUs <= flushStart }.maxOfOrNull { it.endUs } ?: 0L
        if (flushStart >= prevBeforeFlush && flushStart >= 0L) {
            return RowAllocationResult(desiredLane, flushStart, flushEnd)
        }
    }

    // Fallback: stay at item's current valid non-overlapping position
    return RowAllocationResult(
        laneIndex = item.laneIndex.coerceIn(0, 3),
        startUs = item.startUs,
        endUs = item.endUs
    )
}

/**
 * Pre-compiled non-overlapping timeline interval.
 * Visual item indices are pre-sorted back-to-front by (zIndex, laneIndex).
 */
data class Segment(
    val startUs: Long,
    val endUs: Long,
    val activeVisualIndices: IntArray,
    val activeFilterIndices: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Segment
        if (startUs != other.startUs) return false
        if (endUs != other.endUs) return false
        if (!activeVisualIndices.contentEquals(other.activeVisualIndices)) return false
        if (!activeFilterIndices.contentEquals(other.activeFilterIndices)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = startUs.hashCode()
        result = 31 * result + endUs.hashCode()
        result = 31 * result + activeVisualIndices.contentHashCode()
        result = 31 * result + activeFilterIndices.contentHashCode()
        return result
    }
}

/**
 * Pre-allocated zero-allocation active layer container for 60/120fps hot path (Rule R10).
 * Maximum visual capacity 64 and filter capacity 8 cover all possible hard-capped layer combinations.
 */
class ActiveLayerSet(maxVisuals: Int = 64, maxFilters: Int = 8) {
    var visualCount: Int = 0
    val activeVisualIndices: IntArray = IntArray(maxVisuals)
    var filterCount: Int = 0
    val activeFilterIndices: IntArray = IntArray(maxFilters)

    fun reset() {
        visualCount = 0
        filterCount = 0
    }
}

/**
 * Compiled timeline plan enabling O(log N) zero-allocation lookup of active layers
 * during preview playback and media export.
 */
class CompiledLayerPlan(
    val items: List<OverlayItem>,
    val segments: Array<Segment>
) {
    /**
     * Resolves active layers at the given timeline timestamp with ZERO heap allocations.
     * Binary search runs in O(log N) time (< 0.001 ms).
     */
    fun resolveAt(timeUs: Long, outActiveState: ActiveLayerSet): Boolean {
        if (segments.isEmpty()) {
            outActiveState.reset()
            return false
        }

        var low = 0
        var high = segments.size - 1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val seg = segments[mid]
            if (timeUs < seg.startUs) {
                high = mid - 1
            } else if (timeUs >= seg.endUs) {
                low = mid + 1
            } else {
                // Segment match found: copy indices without allocation
                val vCount = seg.activeVisualIndices.size
                outActiveState.visualCount = vCount
                if (vCount > 0) {
                    System.arraycopy(
                        seg.activeVisualIndices,
                        0,
                        outActiveState.activeVisualIndices,
                        0,
                        vCount.coerceAtMost(outActiveState.activeVisualIndices.size)
                    )
                }

                val fCount = seg.activeFilterIndices.size
                outActiveState.filterCount = fCount
                if (fCount > 0) {
                    System.arraycopy(
                        seg.activeFilterIndices,
                        0,
                        outActiveState.activeFilterIndices,
                        0,
                        fCount.coerceAtMost(outActiveState.activeFilterIndices.size)
                    )
                }
                return true
            }
        }

        outActiveState.reset()
        return false
    }

    companion object {
        val EMPTY = CompiledLayerPlan(emptyList(), emptyArray())

        /**
         * Compiles a list of OverlayItems into sorted, non-overlapping intervals.
         * Runs on edit operations, never inside the 60fps render loop.
         */
        fun compile(items: List<OverlayItem>): CompiledLayerPlan {
            if (items.isEmpty()) return EMPTY

            // 1. Collect all boundary points
            val boundaries = TreeSet<Long>()
            for (item in items) {
                if (item.endUs > item.startUs) {
                    boundaries.add(item.startUs)
                    boundaries.add(item.endUs)
                }
            }

            if (boundaries.size < 2) return EMPTY

            val bList = boundaries.toList()
            val segmentList = ArrayList<Segment>(bList.size - 1)

            // 2. Iterate adjacent intervals [bList[i], bList[i+1])
            for (i in 0 until bList.size - 1) {
                val tStart = bList[i]
                val tEnd = bList[i + 1]
                if (tStart >= tEnd) continue

                val visualIndices = ArrayList<Int>()
                val filterIndices = ArrayList<Int>()

                for (idx in items.indices) {
                    val itm = items[idx]
                    if (itm.startUs <= tStart && itm.endUs >= tEnd) {
                        if (itm.type == LayerType.FILTER || itm.type == LayerType.EFFECT) {
                            filterIndices.add(idx)
                        } else if (itm.type != LayerType.AUDIO) {
                            visualIndices.add(idx)
                        }
                    }
                }

                // Sort visual items back-to-front by (zIndex, laneIndex)
                visualIndices.sortWith(Comparator { a, b ->
                    val itemA = items[a]
                    val itemB = items[b]
                    if (itemA.zIndex != itemB.zIndex) {
                        itemA.zIndex.compareTo(itemB.zIndex)
                    } else {
                        itemA.laneIndex.compareTo(itemB.laneIndex)
                    }
                })

                // Sort filter items by (zIndex, laneIndex)
                filterIndices.sortWith(Comparator { a, b ->
                    val itemA = items[a]
                    val itemB = items[b]
                    if (itemA.zIndex != itemB.zIndex) {
                        itemA.zIndex.compareTo(itemB.zIndex)
                    } else {
                        itemA.laneIndex.compareTo(itemB.laneIndex)
                    }
                })

                val visualArray = IntArray(visualIndices.size)
                for (v in visualIndices.indices) {
                    visualArray[v] = visualIndices[v]
                }

                val filterArray = IntArray(filterIndices.size)
                for (f in filterIndices.indices) {
                    filterArray[f] = filterIndices[f]
                }

                segmentList.add(Segment(tStart, tEnd, visualArray, filterArray))
            }

            return CompiledLayerPlan(ArrayList(items), segmentList.toTypedArray())
        }
    }
}

