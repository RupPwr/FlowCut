package com.flowcut.app

import com.flowcut.app.model.Clip
import com.flowcut.app.model.Project
import com.flowcut.app.model.Transition
import com.flowcut.app.model.TransitionType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testEmptyProjectSerialization() {
        val project = Project()
        val str = json.encodeToString(project)
        val decoded = json.decodeFromString<Project>(str)
        assertEquals(0, decoded.clips.size)
        assertEquals(0, decoded.transitions.size)
    }

    @Test
    fun testProjectWithClipsAndTransitions() {
        val clips = listOf(
            Clip("c1", "/path/1.mp4", 5000L, 1080, 1920, 0),
            Clip("c2", "/path/2.mp4", 4000L, 1920, 1080, 90)
        )
        val transitions = listOf(
            Transition(TransitionType.ZOOM, 800L)
        )
        val project = Project(clips, transitions)
        val str = json.encodeToString(project)
        val decoded = json.decodeFromString<Project>(str)

        assertEquals(2, decoded.clips.size)
        assertEquals("c1", decoded.clips[0].id)
        assertEquals(5000L, decoded.clips[0].durationMs)
        assertEquals(1, decoded.transitions.size)
        assertEquals(TransitionType.ZOOM, decoded.transitions[0].type)
        assertEquals(800L, decoded.transitions[0].durationMs)
    }
}

