package com.flowcut.app.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Transition(
    val type: TransitionType = TransitionType.NONE,
    val durationMs: Long = 0L
)

@Serializable
enum class TransitionType(val glId: Int, val displayName: String) {
    NONE(0, "None"),
    SLIDE(1, "Slide"),
    CIRCLE(2, "Circle"),
    ZOOM(3, "Zoom"),
    FADE(4, "Fade")
}

