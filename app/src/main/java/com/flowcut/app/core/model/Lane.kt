package com.flowcut.app.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Lane(
    val id: String,
    val kind: String,                            // feature id, e.g. "text", "music"
    val items: List<LaneItem> = emptyList()
)

interface LaneItem {                             // each feature defines its own item
    val id: String
    val startMs: Long                            // absolute position on timeline in ms
    val durationMs: Long
}

