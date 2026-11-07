package com.flowcut.app.debug

import com.flowcut.app.core.feature.AudioContributor
import com.flowcut.app.core.feature.Feature
import com.flowcut.app.core.feature.FeatureRegistry
import com.flowcut.app.core.feature.FrameCtx
import com.flowcut.app.core.feature.LanePainter
import com.flowcut.app.core.feature.PainterCtx
import com.flowcut.app.core.feature.RenderContributor
import com.flowcut.app.core.model.LaneItem
import kotlinx.serialization.Serializable
import kotlin.math.sin

/**
 * Serializable dummy lane item used for scale testing (Section 12).
 */
@Serializable
data class DummyLaneItem(
    override val id: String,
    override val startMs: Long,
    override val durationMs: Long,
    val label: String
) : LaneItem

/**
 * Lane painter for DummyFeature.
 * Paints item rectangles and cached labels for visible items [from..to] with zero allocations.
 */
class DummyLanePainter(val kind: String) : LanePainter {
    override fun draw(ctx: PainterCtx, laneIndex: Int, from: Int, to: Int) {
        val lane = ctx.snapshot.project.lanes.getOrNull(laneIndex) ?: return
        if (from > to || from !in lane.items.indices) return
        val end = minOf(to, lane.items.size - 1)

        val height = 36f * ctx.density
        val y = 0f

        for (i in from..end) {
            val item = lane.items[i]
            val x1 = ctx.timeMsToX(item.startMs)
            val x2 = ctx.timeMsToX(item.startMs + item.durationMs)
            val width = maxOf(2f, x2 - x1)

            // Draw item rectangle
            ctx.drawRect(x1, y, width, height, 0x80336699.toInt())

            // Draw label
            val label = if (item is DummyLaneItem) item.label else item.id
            ctx.drawText(label, x1 + 4f, y + 12f, 0xFFFFFFFF.toInt(), 10f)
        }
    }
}

/**
 * Render contributor for DummyFeature (Section 12).
 * Lightweight quad computations only when this feature's kind is active in activeSet.laneItem.
 * Zero allocations on the hot path.
 */
class DummyRenderContributor(
    val index: Int,
    override val kinds: Set<String>
) : RenderContributor {
    override val order: Int = 100 + index

    var frameCount: Long = 0L
    private val quadCoords = FloatArray(8)

    override fun onFrame(f: FrameCtx) {
        frameCount++
        // Simulate lightweight quad vertices computation
        quadCoords[0] = -0.5f; quadCoords[1] = -0.5f
        quadCoords[2] =  0.5f; quadCoords[3] = -0.5f
        quadCoords[4] = -0.5f; quadCoords[5] =  0.5f
        quadCoords[6] =  0.5f; quadCoords[7] =  0.5f
    }

    override fun release() {}
}

/**
 * Audio contributor for DummyFeature (Section 12).
 * Adds a lightweight sine wave into the audio buffer when active.
 * Zero allocations on the audio render path.
 */
class DummyAudioContributor(
    val index: Int,
    override val kinds: Set<String>
) : AudioContributor {
    var blockCount: Long = 0L
    private val sampleRate = 48000.0
    private val freq = 440.0 + (index * 5.0)

    override fun render(startFrame: Long, frames: Int, out: FloatArray) {
        blockCount++
        val twoPiFOverSr = 2.0 * Math.PI * freq / sampleRate
        val amp = 0.005f

        for (i in 0 until frames) {
            val sampleIndex = startFrame + i
            val s = (sin(sampleIndex * twoPiFOverSr) * amp).toFloat()
            val outIdx = i * 2
            if (outIdx + 1 < out.size) {
                out[outIdx] += s
                out[outIdx + 1] += s
            }
        }
    }
}

/**
 * DummyFeature implementation for scale testing (N = 1..100) (Section 12).
 * Each DummyFeature registers its own kind "dummy_$index" with its painter and contributors.
 */
class DummyFeature(val index: Int) : Feature {
    override val id: String get() = "dummy_$index"
    val kind: String get() = "dummy_$index"

    val painter = DummyLanePainter(kind)
    val renderContributor = DummyRenderContributor(index, setOf(kind))
    val audioContributor = DummyAudioContributor(index, setOf(kind))

    override fun register(r: FeatureRegistry) {
        r.itemType(kind)
        r.lanePainter(kind, painter)
        r.renderContributor(renderContributor)
        r.audioContributor(audioContributor)
    }
}

/**
 * Registers count dummy features into the provided registry.
 */
fun registerDummyFeatures(registry: FeatureRegistry, count: Int = 100): List<DummyFeature> {
    val list = ArrayList<DummyFeature>(count)
    for (i in 1..count) {
        val f = DummyFeature(i)
        f.register(registry)
        list.add(f)
    }
    return list
}

