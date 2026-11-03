package com.flowcut.app

import com.flowcut.app.model.Clip
import com.flowcut.app.model.ProgramTimeline
import com.flowcut.app.model.Project
import com.flowcut.app.model.Transition
import com.flowcut.app.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    private fun createSpecTestProject(): Project {
        val clips = listOf(
            Clip(id = "c0", path = "/clip0.mp4", durationMs = 5000L, width = 1080, height = 1920, rotation = 0),
            Clip(id = "c1", path = "/clip1.mp4", durationMs = 4000L, width = 1080, height = 1920, rotation = 0),
            Clip(id = "c2", path = "/clip2.mp4", durationMs = 6000L, width = 1080, height = 1920, rotation = 0)
        )
        val transitions = listOf(
            Transition(type = TransitionType.SLIDE, durationMs = 1000L),
            Transition(type = TransitionType.CIRCLE, durationMs = 800L)
        )
        return Project(clips, transitions)
    }

    @Test
    fun testSection7_7_WorkedExample() {
        val project = createSpecTestProject()
        val timeline = ProgramTimeline(project)

        // Check starts, ends, total
        assertEquals(0L, timeline.start[0])
        assertEquals(4000L, timeline.start[1])
        assertEquals(7200L, timeline.start[2])

        assertEquals(5000L, timeline.end[0])
        assertEquals(8000L, timeline.end[1])
        assertEquals(13200L, timeline.end[2])

        assertEquals(13200L, timeline.total)

        // t = 0: A = clip0 @ 0, solo
        val s0 = timeline.resolve(0L)
        assertEquals(0, s0.aIndex)
        assertEquals(0L, s0.aLocalMs)
        assertNull(s0.bIndex)
        assertEquals(TransitionType.NONE, s0.type)
        assertEquals(0f, s0.linearP, 0.001f)

        // t = 3999: A = clip0 @ 3999, solo
        val s3999 = timeline.resolve(3999L)
        assertEquals(0, s3999.aIndex)
        assertEquals(3999L, s3999.aLocalMs)
        assertNull(s3999.bIndex)

        // t = 4000: A = clip0 @ 4000, B = clip1 @ 0, p = 0.0, SLIDE
        val s4000 = timeline.resolve(4000L)
        assertEquals(0, s4000.aIndex)
        assertEquals(4000L, s4000.aLocalMs)
        assertEquals(1, s4000.bIndex)
        assertEquals(0L, s4000.bLocalMs)
        assertEquals(TransitionType.SLIDE, s4000.type)
        assertEquals(0.0f, s4000.linearP, 0.001f)

        // t = 4500: A = clip0 @ 4500, B = clip1 @ 500, p = 0.5
        val s4500 = timeline.resolve(4500L)
        assertEquals(0, s4500.aIndex)
        assertEquals(4500L, s4500.aLocalMs)
        assertEquals(1, s4500.bIndex)
        assertEquals(500L, s4500.bLocalMs)
        assertEquals(TransitionType.SLIDE, s4500.type)
        assertEquals(0.5f, s4500.linearP, 0.001f)

        // t = 4999: A = clip0 @ 4999, B = clip1 @ 999, p = 0.999
        val s4999 = timeline.resolve(4999L)
        assertEquals(0, s4999.aIndex)
        assertEquals(4999L, s4999.aLocalMs)
        assertEquals(1, s4999.bIndex)
        assertEquals(999L, s4999.bLocalMs)
        assertEquals(0.999f, s4999.linearP, 0.001f)

        // t = 5000: A = clip1 @ 1000, solo
        val s5000 = timeline.resolve(5000L)
        assertEquals(1, s5000.aIndex)
        assertEquals(1000L, s5000.aLocalMs)
        assertNull(s5000.bIndex)

        // t = 7200: A = clip1 @ 3200, B = clip2 @ 0, p = 0.0
        val s7200 = timeline.resolve(7200L)
        assertEquals(1, s7200.aIndex)
        assertEquals(3200L, s7200.aLocalMs)
        assertEquals(2, s7200.bIndex)
        assertEquals(0L, s7200.bLocalMs)
        assertEquals(TransitionType.CIRCLE, s7200.type)
        assertEquals(0.0f, s7200.linearP, 0.001f)

        // t = 7600: A = clip1 @ 3600, B = clip2 @ 400, p = 0.5
        val s7600 = timeline.resolve(7600L)
        assertEquals(1, s7600.aIndex)
        assertEquals(3600L, s7600.aLocalMs)
        assertEquals(2, s7600.bIndex)
        assertEquals(400L, s7600.bLocalMs)
        assertEquals(0.5f, s7600.linearP, 0.001f)

        // t = 8000: A = clip2 @ 800, solo
        val s8000 = timeline.resolve(8000L)
        assertEquals(2, s8000.aIndex)
        assertEquals(800L, s8000.aLocalMs)
        assertNull(s8000.bIndex)

        // t = 13199: A = clip2 @ 5999, solo
        val s13199 = timeline.resolve(13199L)
        assertEquals(2, s13199.aIndex)
        assertEquals(5999L, s13199.aLocalMs)
        assertNull(s13199.bIndex)

        // t = 99999: clamped to total - 1 (13199)
        val s99999 = timeline.resolve(99999L)
        assertEquals(2, s99999.aIndex)
        assertEquals(5999L, s99999.aLocalMs)

        // UI positions (7.3)
        // clip1 leftDp = (4000 + 500) / 1000 * 48 = 216.0
        // clip1 rightDp = (8000 - 400) / 1000 * 48 = 364.8
        val clip1Layout = timeline.getClipLayout(1)
        assertEquals(216.0f, clip1Layout.leftDp, 0.01f)
        assertEquals(364.8f, clip1Layout.rightDp, 0.01f)

        // Junction 0 node center x = (4000 + 500) / 1000 * 48 = 216.0
        val j0Layout = timeline.getJunctionLayout(0)
        assertEquals(216.0f, j0Layout.centerX, 0.01f)
        assertEquals(48.0f, j0Layout.bandWidth, 0.01f) // 1000/1000 * 48
    }

    @Test
    fun testEasing() {
        assertEquals(0.0f, ProgramTimeline.ease(0.0f), 0.0001f)
        assertEquals(0.5f, ProgramTimeline.ease(0.5f), 0.0001f)
        assertEquals(1.0f, ProgramTimeline.ease(1.0f), 0.0001f)
        // Quadratic curve test
        assertEquals(0.08f, ProgramTimeline.ease(0.2f), 0.0001f)
        assertEquals(0.92f, ProgramTimeline.ease(0.8f), 0.0001f)
    }

    @Test
    fun testDeleteRules() {
        val initial = createSpecTestProject()

        // 1. Delete middle clip (index 1)
        val afterDelMiddle = ProgramTimeline.deleteClip(initial, 1)
        assertEquals(2, afterDelMiddle.clips.size)
        assertEquals(1, afterDelMiddle.transitions.size)
        assertEquals("c0", afterDelMiddle.clips[0].id)
        assertEquals("c2", afterDelMiddle.clips[1].id)
        // Deleted middle creates new NONE transition between remaining neighbors
        assertEquals(TransitionType.NONE, afterDelMiddle.transitions[0].type)
        assertEquals(0L, afterDelMiddle.transitions[0].durationMs)

        // 2. Delete first clip (index 0)
        val afterDelFirst = ProgramTimeline.deleteClip(initial, 0)
        assertEquals(2, afterDelFirst.clips.size)
        assertEquals(1, afterDelFirst.transitions.size)
        assertEquals("c1", afterDelFirst.clips[0].id)
        assertEquals("c2", afterDelFirst.clips[1].id)
        // Remaining transition is old transition[1] (CIRCLE, 800)
        assertEquals(TransitionType.CIRCLE, afterDelFirst.transitions[0].type)
        assertEquals(800L, afterDelFirst.transitions[0].durationMs)

        // 3. Delete last clip (index 2)
        val afterDelLast = ProgramTimeline.deleteClip(initial, 2)
        assertEquals(2, afterDelLast.clips.size)
        assertEquals(1, afterDelLast.transitions.size)
        assertEquals("c0", afterDelLast.clips[0].id)
        assertEquals("c1", afterDelLast.clips[1].id)
        assertEquals(TransitionType.SLIDE, afterDelLast.transitions[0].type)
        assertEquals(1000L, afterDelLast.transitions[0].durationMs)

        // 4. Delete down to 1 clip
        val oneClipProj = ProgramTimeline.deleteClip(afterDelLast, 1)
        assertEquals(1, oneClipProj.clips.size)
        assertEquals(0, oneClipProj.transitions.size)

        // 5. Delete last remaining clip
        val emptyProj = ProgramTimeline.deleteClip(oneClipProj, 0)
        assertEquals(0, emptyProj.clips.size)
        assertEquals(0, emptyProj.transitions.size)
    }

    @Test
    fun testMaxDuration() {
        val project = createSpecTestProject()
        val timeline = ProgramTimeline(project)

        // j = 0:
        // dur[0] = 5000, dIn[0] = 0
        // dur[1] = 4000, dOut[1] = d[1] = 800
        // limit1 = 2000
        // limit2 = 5000 - 0 - 200 = 4800
        // limit3 = 4000 - 800 - 200 = 3000
        // min(2000, 4800, 3000) = 2000
        assertEquals(2000L, timeline.maxDuration(0))

        // Short clip test:
        // clip 0 = 1200 ms, clip 1 = 1200 ms
        val shortClips = listOf(
            Clip(id = "s0", path = "", durationMs = 1200L, width = 1080, height = 1920, rotation = 0),
            Clip(id = "s1", path = "", durationMs = 1200L, width = 1080, height = 1920, rotation = 0)
        )
        val shortProj = Project(shortClips, listOf(Transition(TransitionType.NONE, 0L)))
        val shortTimeline = ProgramTimeline(shortProj)
        // maxDuration = min(2000, 1200 - 0 - 200 = 1000, 1200 - 0 - 200 = 1000) = 1000
        assertEquals(1000L, shortTimeline.maxDuration(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testProjectInvariant() {
        val clips = listOf(
            Clip(id = "1", path = "", durationMs = 5000L, width = 1080, height = 1920, rotation = 0),
            Clip(id = "2", path = "", durationMs = 4000L, width = 1080, height = 1920, rotation = 0)
        )
        // Should throw because 2 clips require 1 transition, but emptyList provided
        Project(clips, emptyList())
    }
}
