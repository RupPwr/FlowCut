package com.flowcut.app.core.model

import androidx.compose.runtime.Immutable
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToUs
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Clip(
    val id: String,
    val path: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frameRate: Int = 30,
    val timeMap: TimeMap? = null,
    val renderParams: ClipRenderParams = ClipRenderParams.DEFAULT
) {
    val displayWidth: Int
        get() = if (rotation == 90 || rotation == 270) height else width

    val displayHeight: Int
        get() = if (rotation == 90 || rotation == 270) width else height
}

fun Clip.map(): TimeMap = timeMap ?: TimeMap(0L, msToUs(durationMs))

