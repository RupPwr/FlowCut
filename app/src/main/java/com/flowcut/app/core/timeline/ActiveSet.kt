package com.flowcut.app.core.timeline

import com.flowcut.app.core.model.TransitionType

class ActiveSet(laneCount: Int) {
    var aIndex: Int = 0
    var aLocalUs: Long = 0L
    var bIndex: Int = -1                          // bIndex == -1 means no transition active
    var bLocalUs: Long = 0L
    var type: TransitionType = TransitionType.NONE
    var linearP: Float = 0f                       // transition progress (0..1), not time
    val laneItem: IntArray = IntArray(laneCount) { -1 }

    fun reset() {
        aIndex = 0
        aLocalUs = 0L
        bIndex = -1
        bLocalUs = 0L
        type = TransitionType.NONE
        linearP = 0f
        laneItem.fill(-1)
    }
}

