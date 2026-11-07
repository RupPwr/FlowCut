package com.flowcut.app.core.timeline

import androidx.compose.runtime.Immutable
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.time.usToMs

@Immutable
class TimelineSnapshot(val version: Long, val project: Project) {
    val main: MainIndex = MainIndex(project)
    val lanes: Array<LaneIndex> = Array(project.lanes.size) { LaneIndex(project.lanes[it]) }
    val totalMs: Long get() = main.totalMs

    fun resolve(tUs: Long, out: ActiveSet) {
        main.resolve(tUs, out)
        val tMs = usToMs(tUs)
        val count = minOf(lanes.size, out.laneItem.size)
        for (i in 0 until count) {
            out.laneItem[i] = lanes[i].indexAt(tMs)
        }
        for (i in count until out.laneItem.size) {
            out.laneItem[i] = -1
        }
    }

    companion object {
        val EMPTY = TimelineSnapshot(0L, Project())
    }
}

