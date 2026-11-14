package com.flowcut.app.engine.export

object ExportEstimator {

    /**
     * Estimates total export file size in bytes including audio (192 kbps)
     * and a 2% MP4 container/mux overhead.
     */
    fun sizeBytes(videoBps: Int, durationMs: Long): Long {
        val dur = durationMs.coerceAtLeast(0L)
        val totalBps = (videoBps.toLong() + BitrateTable.AUDIO_BPS).coerceAtLeast(0L)
        return totalBps * dur / 1000L / 8L * 102L / 100L
    }

    /**
     * Calculates total frame count for given timeline duration and export framerate.
     */
    fun totalFrames(durationMs: Long, fps: Int): Long {
        val dur = durationMs.coerceAtLeast(0L)
        val validFps = fps.coerceAtLeast(1)
        return (dur * validFps + 999L) / 1000L
    }

    /**
     * Estimated time remaining in milliseconds.
     * @param totalFrames Total frames to be rendered and encoded.
     * @param measuredFps Realtime render+encode fps from benchmark or running average.
     */
    fun etaMs(totalFrames: Long, measuredFps: Double): Long =
        if (measuredFps <= 0.0 || totalFrames <= 0L) -1L else (totalFrames / measuredFps * 1000.0).toLong()
}
