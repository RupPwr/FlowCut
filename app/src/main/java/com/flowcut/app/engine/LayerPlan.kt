package com.flowcut.app.engine

import java.util.TreeSet

/**
 * Supported timeline layer types with enforced maximum item caps.
 */
enum class LayerType(val maxCap: Int) {
    VIDEO(maxCap = 4),
    TEXT(maxCap = 20),
    IMAGE(maxCap = 6),
    STICKER(maxCap = 20),
    FILTER(maxCap = 3)
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
    val mediaPath: String = "",
    val filterPresetId: Int = 0,
    val filterIntensity: Float = 1.0f
) {
    companion object {
        const val MIN_DURATION_US: Long = 200_000L
    }

    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(0L)

    fun isValid(): Boolean = (endUs - startUs) >= MIN_DURATION_US
}

/**
 * Validates whether another layer item of the specified type can be added
 * without exceeding the architectural hard caps.
 */
fun canAddLayer(items: List<OverlayItem>, type: LayerType): Boolean {
    val currentCount = items.count { it.type == type }
    return currentCount < type.maxCap
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
                        if (itm.type == LayerType.FILTER) {
                            filterIndices.add(idx)
                        } else {
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

