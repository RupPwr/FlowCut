package com.flowcut.app.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Project(
    val clips: List<Clip> = emptyList(),
    val transitions: List<Transition> = emptyList(),
    val lanes: List<Lane> = emptyList()
) {
    init {
        val expectedTransitions = if (clips.isEmpty()) 0 else clips.size - 1
        require(transitions.size == expectedTransitions) {
            "Invariant violation: transitions count ${transitions.size} does not match clips count ${clips.size}"
        }
    }
}

