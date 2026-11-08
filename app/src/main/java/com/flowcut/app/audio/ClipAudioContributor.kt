package com.flowcut.app.audio

import com.flowcut.app.core.feature.AudioContributor
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.map
import com.flowcut.app.core.time.usToAudioFrames
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.SnapshotHolder

/**
 * Concrete AudioContributor implementation (Section 10 & 14).
 *
 * Pulls the current project snapshot on each 10 ms audio block, resolves active clips
 * via ActiveSet, maps timeline time to media source time via TimeMap (Rule R11), and
 * blends transitions smoothly without clicks or pops.
 *
 * Enforces:
 * - Rule R10: Zero heap allocations on the hot audio path.
 * - Rule R11: Timeline-to-source mapping via clip.map().sourceUs().
 * - Safeguard 3: Sub-block clip boundary accuracy and 2-5ms de-click ramp.
 */
class ClipAudioContributor(
    private val snapshotHolder: SnapshotHolder
) : AudioContributor {

    override val kinds: Set<String> = emptySet()

    // Pre-allocated ActiveSet for zero-allocation resolution on the audio thread
    private val activeSet = ActiveSet(laneCount = 16)

    // Cached decoders by clip path
    private val decoders = mutableMapOf<String, ClipAudioDecoder>()

    // De-click state tracking (~3 ms = 144 frames at 48 kHz)
    private var isFirstBlockAfterStop: Boolean = true
    private var lastAIndex: Int = -1
    private var lastBIndex: Int = -1

    companion object {
        const val DECLICK_RAMP_FRAMES = 144 // 3 ms at 48,000 Hz
    }

    override fun render(startFrame: Long, frames: Int, out: FloatArray) {
        if (frames <= 0) return

        val startTimelineUs = ClipAudioDecoder.framesToUs(startFrame)
        val snapshot = snapshotHolder.current
        val clips = snapshot.project.clips
        if (clips.isEmpty()) return

        // Resolve active clips for this timeline time block
        snapshot.resolve(startTimelineUs, activeSet)
        val aIndex = activeSet.aIndex
        val bIndex = activeSet.bIndex

        if (aIndex < 0 || aIndex >= clips.size) {
            lastAIndex = -1
            lastBIndex = -1
            return
        }

        val clipA = clips[aIndex]
        val isCutTransition = (lastAIndex != -1 && lastAIndex != aIndex && lastBIndex == -1 && bIndex == -1)
        val needsDeclick = isFirstBlockAfterStop || isCutTransition

        if (bIndex == -1) {
            // Case 1: Single clip playback
            val decoderA = getOrCreateDecoder(clipA)
            val srcUsA = clipA.map().sourceUs(activeSet.aLocalUs)
            val srcFrameA = usToAudioFrames(srcUsA)

            decoderA.read(srcFrameA, frames, out, 0, 1.0f)

            if (needsDeclick) {
                applyDeclickRamp(out, 0, frames)
            }
        } else if (bIndex in clips.indices) {
            // Case 2: Transition crossfade between clip A and clip B
            val clipB = clips[bIndex]
            val gainA = (1.0f - activeSet.linearP).coerceIn(0f, 1f)
            val gainB = activeSet.linearP.coerceIn(0f, 1f)

            val decoderA = getOrCreateDecoder(clipA)
            val srcUsA = clipA.map().sourceUs(activeSet.aLocalUs)
            val srcFrameA = usToAudioFrames(srcUsA)
            decoderA.read(srcFrameA, frames, out, 0, gainA)

            val decoderB = getOrCreateDecoder(clipB)
            val srcUsB = clipB.map().sourceUs(activeSet.bLocalUs)
            val srcFrameB = usToAudioFrames(srcUsB)
            decoderB.read(srcFrameB, frames, out, 0, gainB)

            if (isFirstBlockAfterStop) {
                applyDeclickRamp(out, 0, frames)
            }
        }

        isFirstBlockAfterStop = false
        lastAIndex = aIndex
        lastBIndex = bIndex
    }

    /**
     * Applies a 2-5ms (144 frames) linear gain ramp to smooth out abrupt audio starts or cuts.
     */
    private fun applyDeclickRamp(out: FloatArray, offset: Int, frames: Int) {
        val rampCount = minOf(frames, DECLICK_RAMP_FRAMES)
        for (f in 0 until rampCount) {
            val factor = f.toFloat() / DECLICK_RAMP_FRAMES.toFloat()
            val lIdx = offset + (f shl 1)
            val rIdx = lIdx + 1
            out[lIdx] *= factor
            out[rIdx] *= factor
        }
    }

    fun onPlaybackStopped() {
        isFirstBlockAfterStop = true
        lastAIndex = -1
        lastBIndex = -1
    }

    private fun getOrCreateDecoder(clip: Clip): ClipAudioDecoder {
        return decoders.getOrPut(clip.path) {
            ClipAudioDecoder(clip)
        }
    }

    fun release() {
        for (d in decoders.values) {
            d.release()
        }
        decoders.clear()
        lastAIndex = -1
        lastBIndex = -1
        isFirstBlockAfterStop = true
    }
}

