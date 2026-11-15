package com.kaovilai.hotspotrelay.core

import com.kaovilai.hotspotrelay.data.IphoneTarget

/**
 * Chooses which iPhone the loop is currently working on.
 *
 * Identity is tracked by target id, not by index, so enabling/disabling or
 * reordering the list never makes the loop jump to an unrelated device.
 */
class TargetRotation {

    private var currentId: String? = null

    fun current(targets: List<IphoneTarget>): IphoneTarget? {
        if (targets.isEmpty()) {
            currentId = null
            return null
        }
        val existing = targets.firstOrNull { it.id == currentId }
        if (existing != null) return existing
        val first = targets.first()
        currentId = first.id
        return first
    }

    /** Force the rotation onto a specific target, e.g. the hotspot already joined. */
    fun select(target: IphoneTarget) {
        currentId = target.id
    }

    fun advance(targets: List<IphoneTarget>) {
        if (targets.isEmpty()) {
            currentId = null
            return
        }
        val index = targets.indexOfFirst { it.id == currentId }
        val next = if (index < 0) 0 else (index + 1) % targets.size
        currentId = targets[next].id
    }

    fun reset() {
        currentId = null
    }
}
