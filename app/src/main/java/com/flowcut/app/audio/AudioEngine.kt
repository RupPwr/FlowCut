package com.flowcut.app.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import androidx.core.content.ContextCompat
import com.flowcut.app.core.feature.DefaultFeatureRegistry
import com.flowcut.app.core.playback.Clock
import com.flowcut.app.core.time.usToAudioFrames
import com.flowcut.app.engine.perf.ResourceTracker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance hardware audio playback engine & frame-accurate audio clock.
 *
 * Enforces:
 * - Rule R10: Zero heap allocations in the hot audio render and timeUsAt loops.
 * - Latency-compensated AudioTrack.getTimestamp tracking with monotonic bidirectional
 *   convergence so the playhead neither freezes across cuts nor drifts forward over long videos.
 * - Safe AudioTrack teardown on the audio thread + AudioFocus & becoming-noisy protection.
 */
class AudioEngine(
    private val featureRegistry: DefaultFeatureRegistry,
    context: Context? = null
) {
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BLOCK_FRAMES = 480 // 10 ms at 48 kHz
        const val BLOCK_SAMPLES = BLOCK_FRAMES * CHANNELS // 960 floats
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val US_PER_SECOND = 1_000_000L
    }

    private val appContext: Context? = context?.applicationContext
    private val audioManager: AudioManager? =
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    private val audioTrack: AudioTrack
    private val trackReleased = AtomicBoolean(false)

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
    var onAudioFocusLost: (() -> Unit)? = null

    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var noisyReceiverRegistered = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val cb = onAudioFocusLost
                if (cb != null) {
                    cb.invoke()
                } else {
                    clock.stop()
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val cb = onAudioFocusLost
                if (cb != null) {
                    cb.invoke()
                } else {
                    clock.stop()
                }
            }
        }
    }

    init {
        val rawMinBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val fallbackMinBuffer = BLOCK_SAMPLES * java.lang.Float.BYTES * 8
        val minBufferSize = if (rawMinBuffer > 0) rawMinBuffer else fallbackMinBuffer
        // Ensure at least 4 blocks (~40 ms) of low-latency buffer space
        val bufferSize = maxOf(minBufferSize, BLOCK_SAMPLES * java.lang.Float.BYTES * 4)

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .build()

        // Prefer low-latency path; gracefully fall back to standard mode on Android 11+ HALs/BT/USB DACs that reject it
        audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Throwable) {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(bufferSize, fallbackMinBuffer))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()
        }
        ResourceTracker.onCreated(ResourceTracker.ResourceType.AUDIO_TRACK, "AudioEngine")

        audioThread = Thread({ runAudioLoop() }, "FlowCut-Audio").apply {
            start()
        }
    }

    private fun acquireAudioFocusAndNoisyReceiver() {
        val am = audioManager ?: return
        try {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
                .also { focusRequest = it }
            am.requestAudioFocus(req)
        } catch (_: Throwable) {}

        val ctx = appContext ?: return
        if (!noisyReceiverRegistered) {
            try {
                ContextCompat.registerReceiver(
                    ctx,
                    noisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                noisyReceiverRegistered = true
            } catch (_: Throwable) {}
        }
    }

    private fun releaseAudioFocusAndNoisyReceiver() {
        if (noisyReceiverRegistered) {
            noisyReceiverRegistered = false
            try {
                appContext?.unregisterReceiver(noisyReceiver)
            } catch (_: Throwable) {}
        }
        val req = focusRequest
        if (req != null) {
            try {
                audioManager?.abandonAudioFocusRequest(req)
            } catch (_: Throwable) {}
        }
    }

    private fun releaseAudioTrackOnce() {
        if (trackReleased.compareAndSet(false, true)) {
            try {
                audioTrack.pause()
            } catch (_: Exception) {}
            try {
                audioTrack.flush()
            } catch (_: Exception) {}
            try {
                audioTrack.stop()
            } catch (_: Exception) {}
            try {
                audioTrack.release()
                ResourceTracker.onReleased(ResourceTracker.ResourceType.AUDIO_TRACK, "AudioEngine")
            } catch (_: Exception) {}
        }
    }

    private fun runAudioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        try {
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
                        try {
                            lock.wait()
                        } catch (_: InterruptedException) {
                            if (isReleased) break
                        }
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

                if (isReleased || !isPlaying) continue

                // Write blocking to AudioTrack; naturally paces the loop to audio rate (10 ms)
                val written = try {
                    audioTrack.write(outBlock, 0, BLOCK_SAMPLES, AudioTrack.WRITE_BLOCKING)
                } catch (_: Throwable) {
                    0
                }
                if (written > 0) {
                    currentTimelineFrame += BLOCK_FRAMES
                }
            }
        } finally {
            // Release AudioTrack on the same thread that calls write() to eliminate concurrent release races.
            releaseAudioTrackOnce()
        }
    }

    fun release() {
        synchronized(lock) {
            isReleased = true
            isPlaying = false
            pendingPlay = false
            pendingStop = false
            lock.notifyAll()
        }
        releaseAudioFocusAndNoisyReceiver()
        // Unblock any active WRITE_BLOCKING call immediately before joining audioThread
        try {
            audioTrack.pause()
            audioTrack.flush()
        } catch (_: Exception) {}
        try {
            audioThread.join(1000)
            if (audioThread.isAlive) {
                audioThread.interrupt()
                audioThread.join(500)
            }
        } catch (_: Exception) {}
        if (!audioThread.isAlive) {
            releaseAudioTrackOnce()
        }
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
            acquireAudioFocusAndNoisyReceiver()
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
            releaseAudioFocusAndNoisyReceiver()
            onPlaybackStopped?.invoke()
        }

        override fun timeUsAt(frameTimeNanos: Long): Long {
            if (!isPlaying || isReleased || trackReleased.get()) {
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

            // Require audioTimestamp.nanoTime >= startWallNanos to filter stale pre-pause/seek timestamps
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

                    // Bidirectional monotonic convergence:
                    // - If hardware leads vsync by > 20 ms, catch up smoothly (clamped to +25 ms/frame).
                    // - If vsync drifted ahead of hardware by > 20 ms, slow advancement gently (still >= lastReportedTimeUs).
                    val driftUs = vsyncExpectedTimeUs - rawHardwareTimeUs
                    val computedTimeUs = when {
                        driftUs < -20_000L -> maxOf(lastReportedTimeUs, minOf(rawHardwareTimeUs, vsyncExpectedTimeUs + 25_000L))
                        driftUs > 20_000L -> maxOf(lastReportedTimeUs, vsyncExpectedTimeUs - minOf(driftUs / 2L, 8_000L))
                        else -> maxOf(lastReportedTimeUs, vsyncExpectedTimeUs)
                    }
                    lastReportedTimeUs = computedTimeUs
                    hasValidHardwareTimestamp = true
                    return computedTimeUs
                }
            }

            // Warmup fallback: monotonic wall-clock extrapolation until AudioTrack stabilizes
            val elapsedWallNanos = maxOf(0L, frameTimeNanos - startWallNanos)
            val wallTimeUs = startMediaUs + (elapsedWallNanos / 1000L)
            val computedTimeUs = maxOf(lastReportedTimeUs, minOf(vsyncExpectedTimeUs + 16_000L, maxOf(vsyncExpectedTimeUs, wallTimeUs)))
            lastReportedTimeUs = computedTimeUs
            return computedTimeUs
        }
    }
}
