package com.flowcut.app.core.time

const val US_PER_MS = 1_000L
const val AUDIO_FRAMES_PER_MS = 48L

fun msToUs(ms: Long): Long = ms * US_PER_MS
fun usToMs(us: Long): Long = Math.floorDiv(us, US_PER_MS)
fun msToAudioFrames(ms: Long): Long = ms * AUDIO_FRAMES_PER_MS
fun usToAudioFrames(us: Long): Long = Math.floorDiv(us * AUDIO_FRAMES_PER_MS, US_PER_MS)

