package com.flowcut.app.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class ClipFilterParams(
    val filterPresetId: Int = 0,
    val filterIntensity: Float = 0.8f,
    val effectPresetId: Int = 0,
    val effectIntensity: Float = 0.7f
) {
    companion object {
        val DEFAULT = ClipFilterParams()
    }
}

@Immutable
@Serializable
data class ClipRenderParams(
    val filter: ClipFilterParams = ClipFilterParams.DEFAULT
) {
    companion object {
        val DEFAULT = ClipRenderParams()
    }
}
