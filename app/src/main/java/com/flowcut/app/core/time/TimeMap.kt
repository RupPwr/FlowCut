package com.flowcut.app.core.time

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class TimeMap(
    val inUs: Long,                              // source in timestamp
    val outUs: Long,                             // source out timestamp
    val speedNum: Int = 1,                       // speed = speedNum / speedDen
    val speedDen: Int = 1                        // default 1/1. Reverse (negative) currently not allowed.
) {
    init {
        require(speedNum > 0) { "speedNum must be positive: $speedNum" }
        require(speedDen > 0) { "speedDen must be positive: $speedDen" }
        require(outUs >= inUs) { "outUs ($outUs) must be >= inUs ($inUs)" }
    }

    fun timelineDurationUs(): Long = (outUs - inUs) * speedDen / speedNum

    fun sourceUs(localTimelineUs: Long): Long = inUs + localTimelineUs * speedNum / speedDen
}

