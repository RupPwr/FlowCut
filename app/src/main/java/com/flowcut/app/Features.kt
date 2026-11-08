package com.flowcut.app

import com.flowcut.app.audio.AudioFeature
import com.flowcut.app.core.feature.Feature

/**
 * Central feature registration list (Section 10 & 14).
 * Every feature packages itself into a Feature object and registers here as a single plug-in entry.
 * V2 Audio Engine registered.
 */
val ALL_FEATURES: List<Feature> = listOf(
    AudioFeature()
)
