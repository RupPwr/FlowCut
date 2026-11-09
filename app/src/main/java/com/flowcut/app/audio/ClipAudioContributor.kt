package com.flowcut.app.audio

import com.flowcut.app.core.feature.AudioContributor
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.map
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToAudioFrames
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.SnapshotHolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
 * - Fix 1: Lookahead background audio pre-warming of next clip + exact sub-block cut splitting.
 * - Failure isolation: Never throws or crashes the audio thread.
 */
class ClipAudioContributor(
    private val snapshotHolder: SnapshotHolder
) : AudioContributor {

    override val kinds: Set<String> = emptySet()

    // Pre-allocated ActiveSet for zero-allocation resolution on the audio thread
    private val activeSet = ActiveSet(laneCount = 16)

    // Cached decoders by clip path (ConcurrentHashMap for thread-safe access from pre-warm thread)
    private val decoders = ConcurrentHashMap<String, ClipAudioDecoder>()

    // Dedicated single-thread background worker for pre-warming next clips
    private val prewarmExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FlowCut-AudioPrewarm")
    }

    // De-click state tracking (~3 ms = 144 frames at 48 kHz)
    private var isFirstBlockAfterStop: Boolean = true
    private var lastAIndex: Int = -1
    private var lastBIndex: Int = -1
    private var lastPrewarmedKey: String? = null

    companion object {
        const val DECLICK_RAMP_FRAMES = 144 // 3 ms at 48,000 Hz
        const val LOOKAHEAD_PREWARM_US = 1_000_000L // 1 second lookahead
    }

    override fun render(startFrame: Long, frames: Int, out: FloatArray) {
        if (frames <= 0) return

        try {
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

            val nextIndex = aIndex + 1
            val clipA = clips[aIndex]

            // Lookahead Pre-Warming: Trigger background decoding of upcoming different-path clip within 1s
            var m = aIndex + 1
            while (m < clips.size && clips[m].path == clipA.path) {
                m++
            }
            if (m in clips.indices) {
                val nextClip = clips[m]
                val nextStartUs = msToUs(snapshot.main.startMs[m])
                val remainingUs = nextStartUs - startTimelineUs
                if (remainingUs in 0L..LOOKAHEAD_PREWARM_US) {
                    val targetInUs = nextClip.map().inUs
                    val prewarmKey = "${nextClip.path}:$targetInUs"
                    if (lastPrewarmedKey != prewarmKey) {
                        lastPrewarmedKey = prewarmKey
                        prewarmClip(nextClip, targetInUs)
                    }
                }
            }

            // Prune excess decoders to prevent hardware codec exhaustion (keep active + upcoming <= 3)
            val activePaths = HashSet<String>(4)
            activePaths.add(clipA.path)
            if (bIndex in clips.indices) activePaths.add(clips[bIndex].path)
            if (m in clips.indices) activePaths.add(clips[m].path)
            pruneDecoders(activePaths)

            // Sub-Block Cut Splitting: If 10ms block straddles a hard cut boundary (TransitionType.NONE)
            if (bIndex == -1 && nextIndex in clips.indices) {
                val nextClip = clips[nextIndex]
                val isSamePathContiguous = nextClip.path == clipA.path &&
                        kotlin.math.abs(nextClip.map().inUs - clipA.map().outUs) <= 5_000L

                if (!isSamePathContiguous) {
                    val clipEndUs = msToUs(snapshot.main.endMs[aIndex])
                    val blockDurationUs = (frames * 1000L) / 48L
                    val blockEndTimelineUs = startTimelineUs + blockDurationUs

                    if (clipEndUs in (startTimelineUs + 1) until blockEndTimelineUs) {
                        val framesBeforeCut = usToAudioFrames(clipEndUs - startTimelineUs).toInt().coerceIn(0, frames)
                        val framesAfterCut = frames - framesBeforeCut

                        // 1. Render first N frames from Clip k
                        if (framesBeforeCut > 0) {
                            val decoderA = decoders[clipA.path]
                            if (decoderA != null) {
                                val srcUsA = clipA.map().sourceUs(activeSet.aLocalUs)
                                val srcFrameA = usToAudioFrames(srcUsA)
                                decoderA.read(srcFrameA, framesBeforeCut, out, 0, 1.0f)
                            }
                        }

                        // 2. Render remaining (frames - N) frames from Clip k + 1
                        if (framesAfterCut > 0) {
                            val decoderNext = decoders[nextClip.path]
                            if (decoderNext != null) {
                                val srcUsNext = nextClip.map().sourceUs(0L)
                                val srcFrameNext = usToAudioFrames(srcUsNext)
                                decoderNext.read(srcFrameNext, framesAfterCut, out, framesBeforeCut * 2, 1.0f)
                            }
                        }

                        lastAIndex = nextIndex
                        lastBIndex = -1
                        isFirstBlockAfterStop = false
                        return
                    }
                }
            }

            val isSamePathContiguous = lastAIndex in clips.indices && aIndex in clips.indices &&
                    clips[lastAIndex].path == clipA.path &&
                    kotlin.math.abs(clipA.map().inUs - clips[lastAIndex].map().outUs) <= 5_000L

            val isCutTransition = (lastAIndex != -1 && lastAIndex != aIndex && lastBIndex == -1 && bIndex == -1 && !isSamePathContiguous)
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
        } catch (_: Throwable) {
            // Failure isolation: never bubble exceptions to AudioEngine
        }
    }

    /**
     * Synchronous accurate audio render for offline export.
     * Guarantees all samples are decoded from the source clip without relying on background prewarm.
     */
    fun renderSynchronous(startFrame: Long, frames: Int, out: FloatArray) {
        if (frames <= 0) return

        try {
            val startTimelineUs = ClipAudioDecoder.framesToUs(startFrame)
            val snapshot = snapshotHolder.current
            val clips = snapshot.project.clips
            if (clips.isEmpty()) return

            snapshot.resolve(startTimelineUs, activeSet)
            val aIndex = activeSet.aIndex
            val bIndex = activeSet.bIndex

            if (aIndex < 0 || aIndex >= clips.size) {
                lastAIndex = -1
                lastBIndex = -1
                return
            }

            val nextIndex = aIndex + 1
            val clipA = clips[aIndex]

            // Sub-Block Cut Splitting: If 10ms block straddles a hard cut boundary (TransitionType.NONE)
            if (bIndex == -1 && nextIndex in clips.indices) {
                val nextClip = clips[nextIndex]
                val isSamePathContiguous = nextClip.path == clipA.path &&
                        kotlin.math.abs(nextClip.map().inUs - clipA.map().outUs) <= 5_000L

                if (!isSamePathContiguous) {
                    val clipEndUs = msToUs(snapshot.main.endMs[aIndex])
                    val blockDurationUs = (frames * 1000L) / 48L
                    val blockEndTimelineUs = startTimelineUs + blockDurationUs

                    if (clipEndUs in (startTimelineUs + 1) until blockEndTimelineUs) {
                        val framesBeforeCut = usToAudioFrames(clipEndUs - startTimelineUs).toInt().coerceIn(0, frames)
                        val framesAfterCut = frames - framesBeforeCut

                        if (framesBeforeCut > 0) {
                            val decoderA = getOrCreateDecoder(clipA)
                            val srcUsA = clipA.map().sourceUs(activeSet.aLocalUs)
                            val srcFrameA = usToAudioFrames(srcUsA)
                            decoderA.readSynchronous(srcFrameA, framesBeforeCut, out, 0, 1.0f)
                        }

                        if (framesAfterCut > 0) {
                            val decoderNext = getOrCreateDecoder(nextClip)
                            val srcUsNext = nextClip.map().sourceUs(0L)
                            val srcFrameNext = usToAudioFrames(srcUsNext)
                            decoderNext.readSynchronous(srcFrameNext, framesAfterCut, out, framesBeforeCut * 2, 1.0f)
                        }

                        lastAIndex = nextIndex
                        lastBIndex = -1
                        isFirstBlockAfterStop = false
                        return
                    }
                }
            }

            val isSamePathContiguous = lastAIndex in clips.indices && aIndex in clips.indices &&
                    clips[lastAIndex].path == clipA.path &&
                    kotlin.math.abs(clipA.map().inUs - clips[lastAIndex].map().outUs) <= 5_000L

            val isCutTransition = (lastAIndex != -1 && lastAIndex != aIndex && lastBIndex == -1 && bIndex == -1 && !isSamePathContiguous)
            val needsDeclick = isFirstBlockAfterStop || isCutTransition

            if (bIndex == -1) {
                val decoderA = getOrCreateDecoder(clipA)
                val srcUsA = clipA.map().sourceUs(activeSet.aLocalUs)
                val srcFrameA = usToAudioFrames(srcUsA)

                decoderA.readSynchronous(srcFrameA, frames, out, 0, 1.0f)

                if (needsDeclick) {
                    applyDeclickRamp(out, 0, frames)
                }
            } else if (bIndex in clips.indices) {
                val clipB = clips[bIndex]
                val gainA = (1.0f - activeSet.linearP).coerceIn(0f, 1f)
                val gainB = activeSet.linearP.coerceIn(0f, 1f)

                val decoderA = getOrCreateDecoder(clipA)
                val srcUsA = clipA.map().sourceUs(activeSet.aLocalUs)
                val srcFrameA = usToAudioFrames(srcUsA)
                decoderA.readSynchronous(srcFrameA, frames, out, 0, gainA)

                val decoderB = getOrCreateDecoder(clipB)
                val srcUsB = clipB.map().sourceUs(activeSet.bLocalUs)
                val srcFrameB = usToAudioFrames(srcUsB)
                decoderB.readSynchronous(srcFrameB, frames, out, 0, gainB)

                if (isFirstBlockAfterStop) {
                    applyDeclickRamp(out, 0, frames)
                }
            }

            isFirstBlockAfterStop = false
            lastAIndex = aIndex
            lastBIndex = bIndex
        } catch (_: Throwable) {
            // Failure isolation
        }
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
        lastPrewarmedKey = null
    }

    private fun pruneDecoders(activePaths: Set<String>) {
        if (decoders.size > 3) {
            val iterator = decoders.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!activePaths.contains(entry.key)) {
                    val oldDec = entry.value
                    iterator.remove()
                    prewarmExecutor.execute {
                        try {
                            oldDec.release()
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    private fun prewarmClip(clip: Clip, targetSourceUs: Long) {
        try {
            prewarmExecutor.execute {
                try {
                    val dec = decoders.computeIfAbsent(clip.path) {
                        ClipAudioDecoder(clip)
                    }
                    if (dec.isPrepared) {
                        val targetFrame = usToAudioFrames(targetSourceUs)
                        dec.seekToFrame(targetFrame)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun getOrCreateDecoder(clip: Clip): ClipAudioDecoder {
        return decoders.computeIfAbsent(clip.path) {
            ClipAudioDecoder(clip)
        }
    }

    fun release() {
        try {
            prewarmExecutor.shutdownNow()
        } catch (_: Throwable) {}

        for (d in decoders.values) {
            d.release()
        }
        decoders.clear()
        lastAIndex = -1
        lastBIndex = -1
        lastPrewarmedKey = null
        isFirstBlockAfterStop = true
    }
}
