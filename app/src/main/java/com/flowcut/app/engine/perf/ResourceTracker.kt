package com.flowcut.app.engine.perf

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks creation, release, and alive instances of media resources across the system (Step 16, Step A.4).
 */
object ResourceTracker {
    enum class ResourceType {
        SURFACE,
        SURFACE_TEXTURE,
        MEDIA_CODEC,
        MEDIA_EXTRACTOR,
        AUDIO_TRACK,
        MEDIA_METADATA_RETRIEVER
    }

    private val createdCounters = Array(ResourceType.values().size) { AtomicInteger(0) }
    private val releasedCounters = Array(ResourceType.values().size) { AtomicInteger(0) }

    fun onCreated(type: ResourceType, tag: String? = null) {
        val created = createdCounters[type.ordinal].incrementAndGet()
        val released = releasedCounters[type.ordinal].get()
        val alive = created - released
        PreviewEventLog.log(
            eventType = PreviewEventType.RESOURCE_CREATED,
            arg0 = alive.toLong(),
            arg1 = created.toLong(),
            reason = "${type.name} (alive=$alive, tag=${tag ?: "none"})"
        )
    }

    fun onReleased(type: ResourceType, tag: String? = null) {
        val released = releasedCounters[type.ordinal].incrementAndGet()
        val created = createdCounters[type.ordinal].get()
        val alive = created - released
        PreviewEventLog.log(
            eventType = PreviewEventType.RESOURCE_RELEASED,
            arg0 = alive.toLong(),
            arg1 = released.toLong(),
            reason = "${type.name} (alive=$alive, tag=${tag ?: "none"})"
        )
    }

    fun getAliveCount(type: ResourceType): Int {
        return createdCounters[type.ordinal].get() - releasedCounters[type.ordinal].get()
    }

    fun getCreatedCount(type: ResourceType): Int = createdCounters[type.ordinal].get()

    fun getReleasedCount(type: ResourceType): Int = releasedCounters[type.ordinal].get()

    fun logAliveCounts(caller: String) {
        val s = "Alive resources [$caller]: " + ResourceType.values().joinToString(", ") {
            "${it.name}=${getAliveCount(it)}"
        }
        try {
            Log.i("PreviewEvents", s)
        } catch (_: Throwable) {
            println("PreviewEvents: $s")
        }
    }

    fun resetForTest() {
        ResourceType.values().forEach {
            createdCounters[it.ordinal].set(0)
            releasedCounters[it.ordinal].set(0)
        }
    }
}
