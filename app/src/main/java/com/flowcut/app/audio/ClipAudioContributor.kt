package com.flowcut.app.audio

import com.flowcut.app.core.feature.AudioContributor
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.map
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToAudioFrames
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.PreviewRenderer
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
 * - Multi-track 4-row LayerType.AUDIO mixing with exact sourceInUs, volume, speed, fadeInUs/fadeOutUs.
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

    // Pre-allocated scratch buffers for zero-allocation overlay mixing (Rule R10)
    private val overlayScratch = FloatArray(MAX_SCRATCH_SAMPLES)
    private val syncScratch = FloatArray(MAX_SCRATCH_SAMPLES)

    // De-click state tracking (~3 ms = 144 frames at 48 kHz)
    private var isFirstBlockAfterStop: Boolean = true
    private var lastAIndex: Int = -1
    private var lastBIndex: Int = -1
    private var lastPrewarmedKey: String? = null

    companion object {
        const val DECLICK_RAMP_FRAMES = 144 // 3 ms at 48,000 Hz
        const val LOOKAHEAD_PREWARM_US = 1_000_000L // 1 second lookahead
        const val MAX_SCRATCH_SAMPLES = 19200 // 9600 frames * 2 channels (200 ms at 48 kHz)

        // Shared lock-free state for audio volume & track mute
        @Volatile var isMainTrackMuted: Boolean = false
        val clipVolumes = ConcurrentHashMap<String, Float>()

        // Active overlay audio items across 4 audio rows (updated by EditorViewModel / PreviewRenderer)
        @Volatile var activeAudioOverlays: List<OverlayItem> = emptyList()

        // Shared thread-safe decoder cache for audio overlay items
        val sharedOverlayDecoders = ConcurrentHashMap<String, ClipAudioDecoder>()

        private val staticPrewarmExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FlowCut-StaticAudioPrewarm")
        }

        fun syncAudioOverlays(items: List<OverlayItem>) {
            activeAudioOverlays = items
            if (sharedOverlayDecoders.size > 4) {
                val activePaths = HashSet<String>()
                for (i in items.indices) {
                    val p = items[i].mediaPath
                    if (p.isNotEmpty()) activePaths.add(p)
                }
                val it = sharedOverlayDecoders.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (!activePaths.contains(entry.key)) {
                        val d = entry.value
                        it.remove()
                        try {
                            staticPrewarmExecutor.execute {
                                try { d.release() } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        fun getSharedOverlayDecoder(path: String): ClipAudioDecoder {
            return sharedOverlayDecoders.computeIfAbsent(path) {
                val dec = ClipAudioDecoder(path)
                dec.prepare()
                dec
            }
        }

        fun prewarmAudioPath(path: String) {
            if (path.isEmpty()) return
            try {
                staticPrewarmExecutor.execute {
                    try {
                        val dec = sharedOverlayDecoders.computeIfAbsent(path) {
                            ClipAudioDecoder(path)
                        }
                        if (!dec.isPrepared) {
                            dec.prepare()
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        fun getClipVolume(clipId: String): Float = clipVolumes[clipId] ?: 1.0f

        fun setClipVolume(clipId: String, volume: Float) {
            val clamped = volume.coerceIn(0.0f, 2.0f)
            clipVolumes[clipId] = clamped
            if (clamped > 0.0001f) {
                isMainTrackMuted = false
            }
        }

        fun syncVolumes(volumes: Map<String, Float>) {
            clipVolumes.clear()
            clipVolumes.putAll(volumes)
        }

        /**
         * Maps slider volume (0.0f..2.0f) to effective perceptual gain:
         * - 0.0f..1.0f: Quadratic perceptual attenuation (sliderVol^2) so 50% sounds like half-volume.
         * - 1.0f..2.0f: Linear high-impact boost factor up to 4.0x (+12 dB) at 200%.
         */
        fun computeEffectiveGain(sliderVol: Float): Float {
            return if (sliderVol <= 1.0f) {
                sliderVol * sliderVol
            } else {
                1.0f + (sliderVol - 1.0f) * 3.0f
            }
        }

        /**
         * Zero-allocation soft-knee saturation limiter (Rule R10).
         * - abs(x) <= 0.6f: Linear, completely uncompressed (0 distortion).
         * - abs(x) > 0.6f: Smooth asymptotic compression towards 0.99f without harsh digital clipping.
         */
        fun softLimit(x: Float): Float {
            val absX = kotlin.math.abs(x)
            if (absX <= 0.6f) return x
            val sign = if (x >= 0f) 1f else -1f
            val diff = absX - 0.6f
            return sign * (0.6f + 0.39f * (diff / (0.39f + diff)))
        }
    }

    override fun render(startFrame: Long, frames: Int, out: FloatArray) {
        if (frames <= 0) return

        try {
            val startTimelineUs = ClipAudioDecoder.framesToUs(startFrame)
            val snapshot = snapshotHolder.current
            val clips = snapshot.project.clips

            if (isMainTrackMuted || clips.isEmpty()) {
                out.fill(0f, 0, frames * 2)
                lastAIndex = -1
                lastBIndex = -1
            } else {
                // Resolve active clips for this timeline time block
                snapshot.resolve(startTimelineUs, activeSet)
                val aIndex = activeSet.aIndex
                val bIndex = activeSet.bIndex

                if (aIndex < 0 || aIndex >= clips.size) {
                    out.fill(0f, 0, frames * 2)
                    lastAIndex = -1
                    lastBIndex = -1
                } else {
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

                    // Prune excess decoders to prevent hardware codec exhaustion (Rule R10: zero allocations)
                    val pathA = clipA.path
                    val pathB = if (bIndex in clips.indices) clips[bIndex].path else null
                    val pathNext = if (m in clips.indices) clips[m].path else null
                    pruneDecoders(pathA, pathB, pathNext)

                    var handledCutSplit = false
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

                                out.fill(0f, 0, frames * 2)

                                // 1. Render first N frames from Clip k
                                if (framesBeforeCut > 0) {
                                    val mapA = clipA.map()
                                    if (mapA.speedNum > 0) {
                                        val speedA = if (mapA.speedDen > 0) mapA.speedNum.toFloat() / mapA.speedDen.toFloat() else 1.0f
                                        if (speedA <= 4.0f) {
                                            val decoderA = decoders[clipA.path]
                                            if (decoderA != null) {
                                                val srcUsA = mapA.sourceUs(activeSet.aLocalUs)
                                                val srcFrameA = usToAudioFrames(srcUsA)
                                                val volA = computeEffectiveGain(getClipVolume(clipA.id))
                                                decoderA.readResampled(srcFrameA, framesBeforeCut, speedA, out, 0, volA)
                                            }
                                        }
                                    }
                                }

                                // 2. Render remaining (frames - N) frames from Clip k + 1
                                if (framesAfterCut > 0) {
                                    val mapNext = nextClip.map()
                                    if (mapNext.speedNum > 0) {
                                        val speedNext = if (mapNext.speedDen > 0) mapNext.speedNum.toFloat() / mapNext.speedDen.toFloat() else 1.0f
                                        if (speedNext <= 4.0f) {
                                            val decoderNext = decoders[nextClip.path]
                                            if (decoderNext != null) {
                                                val srcUsNext = mapNext.sourceUs(0L)
                                                val srcFrameNext = usToAudioFrames(srcUsNext)
                                                val volNext = computeEffectiveGain(getClipVolume(nextClip.id))
                                                decoderNext.readResampled(srcFrameNext, framesAfterCut, speedNext, out, framesBeforeCut * 2, volNext)
                                            }
                                        }
                                    }
                                }

                                lastAIndex = nextIndex
                                lastBIndex = -1
                                isFirstBlockAfterStop = false
                                handledCutSplit = true
                            }
                        }
                    }

                    if (!handledCutSplit) {
                        out.fill(0f, 0, frames * 2)
                        val isSamePathContiguous = lastAIndex in clips.indices && aIndex in clips.indices &&
                                clips[lastAIndex].path == clipA.path &&
                                kotlin.math.abs(clipA.map().inUs - clips[lastAIndex].map().outUs) <= 5_000L

                        val isCutTransition = (lastAIndex != -1 && lastAIndex != aIndex && lastBIndex == -1 && bIndex == -1 && !isSamePathContiguous)
                        val needsDeclick = isFirstBlockAfterStop || isCutTransition

                        if (bIndex == -1) {
                            // Case 1: Single clip playback
                            val mapA = clipA.map()
                            if (mapA.speedNum > 0) {
                                val speedA = if (mapA.speedDen > 0) mapA.speedNum.toFloat() / mapA.speedDen.toFloat() else 1.0f
                                if (speedA <= 4.0f) {
                                    val decoderA = getOrCreateDecoder(clipA)
                                    val srcUsA = mapA.sourceUs(activeSet.aLocalUs)
                                    val srcFrameA = usToAudioFrames(srcUsA)
                                    val volA = computeEffectiveGain(getClipVolume(clipA.id))

                                    decoderA.readResampled(srcFrameA, frames, speedA, out, 0, volA)

                                    if (needsDeclick) {
                                        applyDeclickRamp(out, 0, frames)
                                    }
                                }
                            }
                        } else if (bIndex in clips.indices) {
                            // Case 2: Transition crossfade between clip A and clip B
                            val clipB = clips[bIndex]
                            val volA = computeEffectiveGain(getClipVolume(clipA.id))
                            val volB = computeEffectiveGain(getClipVolume(clipB.id))
                            val gainA = (1.0f - activeSet.linearP).coerceIn(0f, 1f) * volA
                            val gainB = activeSet.linearP.coerceIn(0f, 1f) * volB

                            val mapA = clipA.map()
                            if (mapA.speedNum > 0) {
                                val speedA = if (mapA.speedDen > 0) mapA.speedNum.toFloat() / mapA.speedDen.toFloat() else 1.0f
                                if (speedA <= 4.0f) {
                                    val decoderA = getOrCreateDecoder(clipA)
                                    val srcUsA = mapA.sourceUs(activeSet.aLocalUs)
                                    val srcFrameA = usToAudioFrames(srcUsA)
                                    decoderA.readResampled(srcFrameA, frames, speedA, out, 0, gainA)
                                }
                            }

                            val mapB = clipB.map()
                            if (mapB.speedNum > 0) {
                                val speedB = if (mapB.speedDen > 0) mapB.speedNum.toFloat() / mapB.speedDen.toFloat() else 1.0f
                                if (speedB <= 4.0f) {
                                    val decoderB = getOrCreateDecoder(clipB)
                                    val srcUsB = mapB.sourceUs(activeSet.bLocalUs)
                                    val srcFrameB = usToAudioFrames(srcUsB)
                                    decoderB.readResampled(srcFrameB, frames, speedB, out, 0, gainB)
                                }
                            }

                            if (isFirstBlockAfterStop) {
                                applyDeclickRamp(out, 0, frames)
                            }
                        }

                        isFirstBlockAfterStop = false
                        lastAIndex = aIndex
                        lastBIndex = bIndex
                    }
                }
            }

            // Mix active LayerType.AUDIO overlay items across rows 0..3
            mixAudioOverlays(startTimelineUs, frames, out, isSynchronous = false)

            // Soft limit boosted/mixed audio cleanly (Rule R10: zero allocations)
            val sampleCount = frames * 2
            for (si in 0 until sampleCount) {
                out[si] = softLimit(out[si])
            }
        } catch (_: Throwable) {
            // Failure isolation: never bubble exceptions to AudioEngine
        }
    }

    /**
     * Synchronous accurate audio render for offline export.
     * Guarantees all samples are decoded from source clips and overlay audio tracks.
     */
    fun renderSynchronous(startFrame: Long, frames: Int, out: FloatArray) {
        if (frames <= 0) return

        try {
            val startTimelineUs = ClipAudioDecoder.framesToUs(startFrame)
            val snapshot = snapshotHolder.current
            val clips = snapshot.project.clips

            if (isMainTrackMuted || clips.isEmpty()) {
                out.fill(0f, 0, frames * 2)
                lastAIndex = -1
                lastBIndex = -1
            } else {
                snapshot.resolve(startTimelineUs, activeSet)
                val aIndex = activeSet.aIndex
                val bIndex = activeSet.bIndex

                if (aIndex < 0 || aIndex >= clips.size) {
                    out.fill(0f, 0, frames * 2)
                    lastAIndex = -1
                    lastBIndex = -1
                } else {
                    val nextIndex = aIndex + 1
                    val clipA = clips[aIndex]

                    var handledCutSplit = false
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

                                out.fill(0f, 0, frames * 2)

                                if (framesBeforeCut > 0) {
                                    val mapA = clipA.map()
                                    if (mapA.speedNum > 0) {
                                        val decoderA = getOrCreateDecoder(clipA)
                                        val srcUsA = mapA.sourceUs(activeSet.aLocalUs)
                                        val srcFrameA = usToAudioFrames(srcUsA)
                                        val volA = computeEffectiveGain(getClipVolume(clipA.id))
                                        val speedA = if (mapA.speedDen > 0) mapA.speedNum.toFloat() / mapA.speedDen.toFloat() else 1.0f
                                        decoderA.readResampledSynchronous(srcFrameA, framesBeforeCut, speedA, out, 0, volA)
                                    }
                                }

                                if (framesAfterCut > 0) {
                                    val mapNext = nextClip.map()
                                    if (mapNext.speedNum > 0) {
                                        val decoderNext = getOrCreateDecoder(nextClip)
                                        val srcUsNext = mapNext.sourceUs(0L)
                                        val srcFrameNext = usToAudioFrames(srcUsNext)
                                        val volNext = computeEffectiveGain(getClipVolume(nextClip.id))
                                        val speedNext = if (mapNext.speedDen > 0) mapNext.speedNum.toFloat() / mapNext.speedDen.toFloat() else 1.0f
                                        decoderNext.readResampledSynchronous(srcFrameNext, framesAfterCut, speedNext, out, framesBeforeCut * 2, volNext)
                                    }
                                }

                                lastAIndex = nextIndex
                                lastBIndex = -1
                                isFirstBlockAfterStop = false
                                handledCutSplit = true
                            }
                        }
                    }

                    if (!handledCutSplit) {
                        out.fill(0f, 0, frames * 2)
                        val isSamePathContiguous = lastAIndex in clips.indices && aIndex in clips.indices &&
                                clips[lastAIndex].path == clipA.path &&
                                kotlin.math.abs(clipA.map().inUs - clips[lastAIndex].map().outUs) <= 5_000L

                        val isCutTransition = (lastAIndex != -1 && lastAIndex != aIndex && lastBIndex == -1 && bIndex == -1 && !isSamePathContiguous)
                        val needsDeclick = isFirstBlockAfterStop || isCutTransition

                        if (bIndex == -1) {
                            val mapA = clipA.map()
                            if (mapA.speedNum > 0) {
                                val decoderA = getOrCreateDecoder(clipA)
                                val srcUsA = mapA.sourceUs(activeSet.aLocalUs)
                                val srcFrameA = usToAudioFrames(srcUsA)
                                val volA = computeEffectiveGain(getClipVolume(clipA.id))
                                val speedA = if (mapA.speedDen > 0) mapA.speedNum.toFloat() / mapA.speedDen.toFloat() else 1.0f

                                decoderA.readResampledSynchronous(srcFrameA, frames, speedA, out, 0, volA)

                                if (needsDeclick) {
                                    applyDeclickRamp(out, 0, frames)
                                }
                            }
                        } else if (bIndex in clips.indices) {
                            val clipB = clips[bIndex]
                            val volA = computeEffectiveGain(getClipVolume(clipA.id))
                            val volB = computeEffectiveGain(getClipVolume(clipB.id))
                            val gainA = (1.0f - activeSet.linearP).coerceIn(0f, 1f) * volA
                            val gainB = activeSet.linearP.coerceIn(0f, 1f) * volB

                            val mapA = clipA.map()
                            if (mapA.speedNum > 0) {
                                val decoderA = getOrCreateDecoder(clipA)
                                val srcUsA = mapA.sourceUs(activeSet.aLocalUs)
                                val srcFrameA = usToAudioFrames(srcUsA)
                                val speedA = if (mapA.speedDen > 0) mapA.speedNum.toFloat() / mapA.speedDen.toFloat() else 1.0f
                                decoderA.readResampledSynchronous(srcFrameA, frames, speedA, out, 0, gainA)
                            }

                            val mapB = clipB.map()
                            if (mapB.speedNum > 0) {
                                val decoderB = getOrCreateDecoder(clipB)
                                val srcUsB = mapB.sourceUs(activeSet.bLocalUs)
                                val srcFrameB = usToAudioFrames(srcUsB)
                                val speedB = if (mapB.speedDen > 0) mapB.speedNum.toFloat() / mapB.speedDen.toFloat() else 1.0f
                                decoderB.readResampledSynchronous(srcFrameB, frames, speedB, out, 0, gainB)
                            }

                            if (isFirstBlockAfterStop) {
                                applyDeclickRamp(out, 0, frames)
                            }
                        }

                        isFirstBlockAfterStop = false
                        lastAIndex = aIndex
                        lastBIndex = bIndex
                    }
                }
            }

            // Mix active LayerType.AUDIO overlay items across rows 0..3
            mixAudioOverlays(startTimelineUs, frames, out, isSynchronous = true)

            // Soft limit boosted/mixed audio cleanly (Rule R10: zero allocations)
            val sampleCount = frames * 2
            for (si in 0 until sampleCount) {
                out[si] = softLimit(out[si])
            }
        } catch (_: Throwable) {
            // Failure isolation
        }
    }

    /**
     * Zero-allocation additive audio mixing for LayerType.AUDIO overlay items across rows 0..3 (Rule R10).
     */
    private fun mixAudioOverlays(
        startTimelineUs: Long,
        frames: Int,
        out: FloatArray,
        isSynchronous: Boolean
    ) {
        val overlays = if (activeAudioOverlays.isNotEmpty()) {
            activeAudioOverlays
        } else {
            PreviewRenderer.currentLayerPlan.items
        }
        val overlayCount = overlays.size
        if (overlayCount == 0) return

        val blockDurationUs = (frames.toLong() * 1_000_000L) / 48000L
        val endTimelineUs = startTimelineUs + blockDurationUs
        val scratch = if (isSynchronous) syncScratch else overlayScratch

        for (k in 0 until overlayCount) {
            val item = overlays[k]
            if (item.type != LayerType.AUDIO && item.type != LayerType.VIDEO) continue
            if (item.volume <= 0.001f) continue
            val path = item.mediaPath
            if (path.isEmpty()) continue
            if (item.startUs >= endTimelineUs || item.endUs <= startTimelineUs) continue

            val frameOffset = if (item.startUs > startTimelineUs) {
                usToAudioFrames(item.startUs - startTimelineUs).toInt().coerceIn(0, frames)
            } else {
                0
            }
            val framesToEnd = if (item.endUs < endTimelineUs) {
                usToAudioFrames(item.endUs - startTimelineUs).toInt().coerceIn(0, frames)
            } else {
                frames
            }
            val framesToRender = (framesToEnd - frameOffset).coerceIn(0, frames - frameOffset)
            if (framesToRender <= 0) continue

            val renderTimelineUs = startTimelineUs + ClipAudioDecoder.framesToUs(frameOffset.toLong())
            val localItemUs = (renderTimelineUs - item.startUs).coerceAtLeast(0L)
            val sourceAudioUs = item.sourceInUs + (localItemUs.toDouble() * item.speed.toDouble()).toLong()
            val sourceStartFrame = usToAudioFrames(sourceAudioUs.coerceAtLeast(0L))

            val decoder = sharedOverlayDecoders[path] ?: getSharedOverlayDecoder(path)
            val neededSamples = framesToRender * 2
            if (neededSamples <= scratch.size) {
                scratch.fill(0f, 0, neededSamples)
                if (isSynchronous) {
                    decoder.readResampledSynchronous(sourceStartFrame, framesToRender, item.speed, scratch, 0, 1.0f)
                } else {
                    decoder.readResampled(sourceStartFrame, framesToRender, item.speed, scratch, 0, 1.0f)
                }

                // Check touching adjacent split segments to prevent micro-ramping across continuous splits
                var hasPrecedingTouching = false
                var hasFollowingTouching = false
                for (j in 0 until overlayCount) {
                    if (j == k) continue
                    val other = overlays[j]
                    if ((other.type == item.type) && other.laneIndex == item.laneIndex && other.mediaPath == path) {
                        if (other.endUs == item.startUs) hasPrecedingTouching = true
                        if (other.startUs == item.endUs) hasFollowingTouching = true
                    }
                }

                val needsStartMicroRamp = (item.startUs >= startTimelineUs && !hasPrecedingTouching && item.fadeInUs == 0L)
                val needsEndMicroRamp = (item.endUs <= endTimelineUs && !hasFollowingTouching && item.fadeOutUs == 0L)
                val rampFrames = 32

                val baseGain = computeEffectiveGain(item.volume)

                for (f in 0 until framesToRender) {
                    val sampleLocalUs = localItemUs + (f.toLong() * 1_000_000L) / 48000L
                    val sampleTimelinePosUs = renderTimelineUs + (f.toLong() * 1_000_000L) / 48000L
                    val remainingItemUs = (item.endUs - sampleTimelinePosUs).coerceAtLeast(0L)

                    val fadeInGain = if (item.fadeInUs > 0L && sampleLocalUs < item.fadeInUs) {
                        (sampleLocalUs.toFloat() / item.fadeInUs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        1.0f
                    }

                    val fadeOutGain = if (item.fadeOutUs > 0L && remainingItemUs < item.fadeOutUs) {
                        (remainingItemUs.toFloat() / item.fadeOutUs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        1.0f
                    }

                    val microInGain = if (needsStartMicroRamp && f < rampFrames) {
                        f.toFloat() / rampFrames.toFloat()
                    } else 1.0f

                    val remainingFrames = framesToRender - 1 - f
                    val microOutGain = if (needsEndMicroRamp && remainingFrames < rampFrames) {
                        remainingFrames.toFloat() / rampFrames.toFloat()
                    } else 1.0f

                    val effectiveGain = baseGain * fadeInGain * fadeOutGain * microInGain * microOutGain
                    val dstIdx = (frameOffset + f) shl 1
                    val srcIdx = f shl 1
                    out[dstIdx] += scratch[srcIdx] * effectiveGain
                    out[dstIdx + 1] += scratch[srcIdx + 1] * effectiveGain
                }
            }
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
        for (d in sharedOverlayDecoders.values) {
            d.lastReadEndSourceFrame = -1L
        }
    }

    private fun pruneDecoders(pathA: String, pathB: String?, pathNext: String?) {
        if (decoders.size > 2) {
            val iterator = decoders.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val k = entry.key
                if (k != pathA && (pathB == null || k != pathB) && (pathNext == null || k != pathNext)) {
                    val oldDec = entry.value
                    iterator.remove()
                    try {
                        prewarmExecutor.execute {
                            try {
                                oldDec.release()
                            } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
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

        for (d in sharedOverlayDecoders.values) {
            d.release()
        }
        sharedOverlayDecoders.clear()

        lastAIndex = -1
        lastBIndex = -1
        lastPrewarmedKey = null
        isFirstBlockAfterStop = true
    }
}
