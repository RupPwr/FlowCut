package com.flowcut.app.audio

import com.flowcut.app.core.feature.AudioContributor
import com.flowcut.app.core.feature.Feature
import com.flowcut.app.core.feature.FeatureRegistry

/**
 * Feature plug-in descriptor for FlowCut's hardware audio engine (Section 10 & 14).
 * Registers audio contributor into FeatureRegistry with zero core modifications.
 */
class AudioFeature(
    var contributor: AudioContributor? = null
) : Feature {
    override val id: String = "audio"

    override fun register(r: FeatureRegistry) {
        contributor?.let { r.audioContributor(it) }
    }
}

