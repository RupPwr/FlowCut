package com.flowcut.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import com.flowcut.app.core.feature.DefaultFeatureRegistry
import com.flowcut.app.core.playback.Clock
import com.flowcut.app.core.time.usToAudioFrames

/**
 * High-performance hardware audio playback engine & frame-accurate audio clock.
 *
 * Enforces:
 * - Rule R10: Zero heap allocations in the hot audio render and timeUsAt loops.
 * - Safeguard 2 & Fix 2: Latency-compensated AudioTrack.getTimestamp tracking with seamless
 *   vsync-continuous interpolation so the timeline playhead NEVER freezes across clip handovers.
 * - Safeguard 4: Clean pause/flush and lifecycle release.
 */
class AudioEngine(
    private val featureRegistry: DefaultFeatureRegistry
) {
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BLOCK_FRAMES = 480 // 10 ms at 48 kHz
        const val BLOCK_SAMPLES = BLOCK_FRAMES * CHANNELS // 960 floats
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val US_PER_SECOND = 1_000_000L
    }

    private val audioTrack: AudioTrack

    @Volatile private var isPlaying: Boolean = false
    @Volatile private var isReleased: Boolean = false
    @Volatile private var pendingPlay: Boolean = false
    @Volatile private var pendingStop: Boolean = false
    private val lock = java.lang.Object()

    private var currentTimelineFrame: Long = 0L

    // Pre-allocated render block
    private val outBlock = FloatArray(BLOCK_SAMPLES)

    // Dedicated high-priority audio thread
    private val audioThread: Thread

    // Inner Clock implementation exposed to PlaybackController
    val clock: Clock = AudioClockImpl()

    var onPlaybackStopped: (() -> Unit)? = null

    init {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        // Ensure at least 4 blocks (~40 ms) of low-latency buffer space
        val bufferSize = maxOf(minBufferSize, BLOCK_SAMPLES * java.lang.Float.BYTES * 4)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioThread = Thread({ runAudioLoop() }, "FlowCut-Audio").apply {
            start()
        }
    }

    private fun runAudioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        while (!isReleased) {
            if (pendingStop) {
                pendingStop = false
                try {
                    audioTrack.pause()
                    audioTrack.flush()
                } catch (_: Exception) {}
            }

            synchronized(lock) {
                while (!isPlaying && !pendingStop && !isReleased) {
                    lock.wait()
                }
            }
            if (isReleased) break

            if (pendingStop) {
                pendingStop = false
                try {
                    audioTrack.pause()
                    audioTrack.flush()
                } catch (_: Exception) {}
                continue
            }

            if (pendingPlay) {
                pendingPlay = false
                try {
                    audioTrack.play()
                } catch (_: Exception) {}
            }

            if (!isPlaying) continue

            // Zero-allocation: fill pre-allocated buffer with 0.0f
            outBlock.fill(0f)

            // Render all registered audio contributors with failure isolation
            val contributors = featureRegistry.getAudioContributors()
            val count = contributors.size
            for (i in 0 until count) {
                try {
                    contributors[i].render(currentTimelineFrame, BLOCK_FRAMES, outBlock)
                } catch (_: Throwable) {}
            }

            // Write blocking to AudioTrack; naturally paces the loop to audio rate (10 ms)
            val written = audioTrack.write(outBlock, 0, BLOCK_SAMPLES, AudioTrack.WRITE_BLOCKING)
            if (written > 0) {
                currentTimelineFrame += BLOCK_FRAMES
            }
        }

        try {
            audioTrack.pause()
            audioTrack.flush()
            audioTrack.stop()
        } catch (_: Exception) {}
    }

    fun release() {
        synchronized(lock) {
            isReleased = true
            isPlaying = false
            pendingPlay = false
            pendingStop = false
            lock.notifyAll()
        }
        try {
            audioThread.join(300)
        } catch (_: Exception) {}

        try {
            audioTrack.release()
        } catch (_: Exception) {}
    }

    /**
     * Hardware presentation clock implementing com.flowcut.app.core.playback.Clock.
     */
    private inner class AudioClockImpl : Clock {

        @Volatile private var startMediaUs: Long = 0L
        @Volatile private var startWallNanos: Long = 0L
        @Volatile private var lastReportedTimeUs: Long = 0L
        @Volatile private var lastFrameTimeNanos: Long = 0L
        @Volatile private var sessionBaseFramePos: Long = -1L
        @Volatile private var hasValidHardwareTimestamp: Boolean = false

        // Pre-allocated AudioTimestamp for zero allocations during vsync
        private val audioTimestamp = AudioTimestamp()

        override val isReady: Boolean
            get() = hasValidHardwareTimestamp || (System.nanoTime() - startWallNanos > 30_000_000L)

        override fun start(fromUs: Long) {
            synchronized(lock) {
                if (isReleased) return

                startMediaUs = fromUs
                startWallNanos = System.nanoTime()
                lastReportedTimeUs = fromUs
                lastFrameTimeNanos = 0L
                sessionBaseFramePos = -1L
                hasValidHardwareTimestamp = false

                currentTimelineFrame = usToAudioFrames(fromUs)

                pendingPlay = true
                pendingStop = false
                isPlaying = true
                lock.notifyAll()
            }
        }

        override fun stop() {
            synchronized(lock) {
                if (isReleased) return
                isPlaying = false
                pendingStop = true
                pendingPlay = false
                sessionBaseFramePos = -1L
                hasValidHardwareTimestamp = false
                lock.notifyAll()
            }
            onPlaybackStopped?.invoke()
        }

        override fun timeUsAt(frameTimeNanos: Long): Long {
            if (!isPlaying) {
                return lastReportedTimeUs
            }

            // Continuous monotonic vsync pacing
            val dtNanos = if (lastFrameTimeNanos > 0L) {
                (frameTimeNanos - lastFrameTimeNanos).coerceIn(0L, 50_000_000L)
            } else {
                16_666_667L
            }
            lastFrameTimeNanos = frameTimeNanos
            val vsyncExpectedTimeUs = lastReportedTimeUs + (dtNanos / 1000L)

            // Query hardware presentation timestamp
            val hasTs = try {
                audioTrack.getTimestamp(audioTimestamp)
            } catch (_: Exception) {
                false
            }

            // Fix 2A: Require audioTimestamp.nanoTime >= startWallNanos to filter stale pre-pause/seek timestamps
            if (hasTs && audioTimestamp.framePosition > 0L && audioTimestamp.nanoTime >= startWallNanos) {
                if (sessionBaseFramePos < 0L) {
                    sessionBaseFramePos = audioTimestamp.framePosition
                }
                val playedFrames = audioTimestamp.framePosition - sessionBaseFramePos
                if (playedFrames >= 0L) {
                    // Clamp delta to +/- 50 ms to reject hardware outliers
                    val elapsedNanosSinceTs = (frameTimeNanos - audioTimestamp.nanoTime)
                        .coerceIn(-50_000_000L, 50_000_000L)
                    val currentHardwareFrames = playedFrames + (elapsedNanosSinceTs * SAMPLE_RATE / NANOS_PER_SECOND)
                    val rawHardwareTimeUs = startMediaUs + (currentHardwareFrames * US_PER_SECOND / SAMPLE_RATE)
                    // Fix 2B: Clamp forward hardware clock jumps so the playback clock advances strictly at 1.0x real-time
                    val hardwareTimeUs = minOf(rawHardwareTimeUs, vsyncExpectedTimeUs + 50_000L)

                    // Fix 2: If AudioTrack momentarily waits or underruns at a cut boundary,
                    // keep advancing smoothly at vsync rate rather than freezing the playhead!
                    val computedTimeUs = if (hardwareTimeUs >= lastReportedTimeUs) {
                        maxOf(vsyncExpectedTimeUs, hardwareTimeUs)
                    } else {
                        vsyncExpectedTimeUs
                    }
                    lastReportedTimeUs = computedTimeUs
                    hasValidHardwareTimestamp = true
                    return computedTimeUs
                }
            }

            // Warmup fallback: monotonic wall-clock extrapolation until AudioTrack stabilizes
            val elapsedWallNanos = maxOf(0L, frameTimeNanos - startWallNanos)
            val wallTimeUs = startMediaUs + (elapsedWallNanos / 1000L)
            val computedTimeUs = maxOf(vsyncExpectedTimeUs, maxOf(lastReportedTimeUs, wallTimeUs))
            lastReportedTimeUs = computedTimeUs
            return computedTimeUs
        }
    }
}
