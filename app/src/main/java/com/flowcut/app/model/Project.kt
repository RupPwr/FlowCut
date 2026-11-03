package com.flowcut.app.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val clips: List<Clip> = emptyList(),
    val transitions: List<Transition> = emptyList()
) {
    init {
        val expectedTransitions = if (clips.isEmpty()) 0 else clips.size - 1
        require(transitions.size == expectedTransitions) {
            "Invariant violation: transitions count ${transitions.size} does not match clips count ${clips.size}"
        }
    }
}

@Serializable
data class Clip(
    val id: String,
    val path: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int
) {
    val displayWidth: Int
        get() = if (rotation == 90 || rotation == 270) height else width

    val displayHeight: Int
        get() = if (rotation == 90 || rotation == 270) width else height
}

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

