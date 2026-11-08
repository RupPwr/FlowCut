package com.flowcut.app.core.edit

import com.flowcut.app.core.model.Project

class ValidationException(message: String) : IllegalArgumentException(message)

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

object Validator {
    const val MAX_PROJECT_DURATION_MS: Long = 15 * 60_000L       // 15 minutes = 900,000 ms
    const val MIN_CLIP_DURATION_MS: Long = 200L                 // 0.2 seconds = 200 ms

    fun validate(project: Project): ValidationResult {
        // 1. Transition count invariant
        val expectedTransitions = if (project.clips.isEmpty()) 0 else project.clips.size - 1
        if (project.transitions.size != expectedTransitions) {
            return ValidationResult.Invalid(
                "Transition count mismatch: expected $expectedTransitions for ${project.clips.size} clips, but found ${project.transitions.size}"
            )
        }

        // 2. Clip duration invariants: minimum 0.2 seconds (200 ms)
        for (i in project.clips.indices) {
            val clip = project.clips[i]
            if (clip.durationMs < MIN_CLIP_DURATION_MS) {
                return ValidationResult.Invalid(
                    "Clip '${clip.id}' duration ${clip.durationMs}ms is less than minimum ${MIN_CLIP_DURATION_MS}ms"
                )
            }
        }

        // 3. Sum of all clips duration <= 15 minutes
        var totalClipDurMs = 0L
        for (i in project.clips.indices) {
            totalClipDurMs += project.clips[i].durationMs
            if (totalClipDurMs > MAX_PROJECT_DURATION_MS) {
                return ValidationResult.Invalid(
                    "Total project duration exceeds 15 minutes ($MAX_PROJECT_DURATION_MS ms)"
                )
            }
        }

        // 4. Transitions non-negative duration and <= adjacent clips
        for (i in project.transitions.indices) {
            val trans = project.transitions[i]
            if (trans.durationMs < 0L) {
                return ValidationResult.Invalid(
                    "Transition at index $i has negative duration: ${trans.durationMs}ms"
                )
            }
            if (i < project.clips.size - 1) {
                val maxAllowed = minOf(project.clips[i].durationMs, project.clips[i + 1].durationMs)
                if (trans.durationMs > maxAllowed) {
                    return ValidationResult.Invalid(
                        "Transition at index $i duration ${trans.durationMs}ms exceeds adjacent clip duration ${maxAllowed}ms"
                    )
                }
            }
        }

        // 5. Lanes: items sorted and no-overlap
        for (laneIdx in project.lanes.indices) {
            val lane = project.lanes[laneIdx]
            val items = lane.items
            for (i in items.indices) {
                val item = items[i]
                if (item.startMs < 0L) {
                    return ValidationResult.Invalid(
                        "Lane '${lane.id}' item '${item.id}' has negative startMs: ${item.startMs}"
                    )
                }
                if (item.durationMs <= 0L) {
                    return ValidationResult.Invalid(
                        "Lane '${lane.id}' item '${item.id}' has non-positive duration: ${item.durationMs}"
                    )
                }
                if (i > 0) {
                    val prev = items[i - 1]
                    if (item.startMs < prev.startMs) {
                        return ValidationResult.Invalid(
                            "Lane '${lane.id}' items not sorted: item '${item.id}' startMs (${item.startMs}) < previous '${prev.id}' startMs (${prev.startMs})"
                        )
                    }
                    val prevEndMs = prev.startMs + prev.durationMs
                    if (item.startMs < prevEndMs) {
                        return ValidationResult.Invalid(
                            "Lane '${lane.id}' items overlap: item '${item.id}' startMs (${item.startMs}) < previous '${prev.id}' endMs ($prevEndMs)"
                        )
                    }
                }
            }
        }

        return ValidationResult.Valid
    }

    fun check(project: Project) {
        val result = validate(project)
        if (result is ValidationResult.Invalid) {
            throw ValidationException(result.reason)
        }
    }
}

