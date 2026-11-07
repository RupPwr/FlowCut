package com.flowcut.app.core.timeline

class SnapshotHolder(initial: TimelineSnapshot = TimelineSnapshot.EMPTY) {
    @Volatile
    var current: TimelineSnapshot = initial
}

