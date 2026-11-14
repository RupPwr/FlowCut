package com.flowcut.app.engine.export

import kotlin.math.roundToLong

object BitrateTable {
    const val AUDIO_BPS = 192_000

    /**
     * Calculates recommended video bitrate in bps.
     * Default baseline: 1080x1920@30 High AVC ~= 9.95 Mbps (~0.16 bpp).
     */
    fun videoBps(
        w: Int,
        h: Int,
        fps: Int,
        q: Quality,
        codec: OutCodec,
        minBps: Int = 200_000,
        maxBps: Int = Int.MAX_VALUE
    ): Int {
        val px = w.toLong() * h
        val resFactor = when {
            px <= 2_200_000L -> 1.0
            px <= 4_000_000L -> 0.8
            else -> 0.65
        }
        val fpsFactor = if (fps > 30) 0.8 else 1.0
        val codecFactor = if (codec == OutCodec.HEVC) 0.7 else 1.0
        val raw = px * fps * q.bitsPerPixel * resFactor * fpsFactor * codecFactor
        return raw.roundToLong().coerceIn(minBps.toLong(), maxBps.toLong()).toInt()
    }
}
