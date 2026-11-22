package com.flowcut.app

import com.flowcut.app.audio.AudioFeature
import com.flowcut.app.core.feature.Feature
import com.flowcut.app.features.image.ImageFeature
import com.flowcut.app.features.mosaic.MosaicFeature
import com.flowcut.app.features.pip.PipFeature
import com.flowcut.app.features.sticker.StickerFeature
import com.flowcut.app.features.text.TextFeature

/**
 * Central feature registration list (Section 10 & 14).
 * Every feature packages itself into a Feature object and registers here as a single plug-in entry.
 * V2 Audio Engine, Text Plugin, Sticker Plugin, Image Plugin, Mosaic Plugin, and PiP Plugin registered.
 */
val ALL_FEATURES: List<Feature> = listOf(
    AudioFeature(),
    TextFeature,
    StickerFeature,
    ImageFeature,
    MosaicFeature,
    PipFeature
)
