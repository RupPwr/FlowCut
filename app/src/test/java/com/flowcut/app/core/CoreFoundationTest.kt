package com.flowcut.app.core

import com.flowcut.app.core.edit.ValidationException
import com.flowcut.app.core.edit.ValidationResult
import com.flowcut.app.core.edit.Validator
import com.flowcut.app.core.model.Clip
import com.flowcut.app.core.model.Lane
import com.flowcut.app.core.model.LaneItem
import com.flowcut.app.core.model.Project
import com.flowcut.app.core.model.Transition
import com.flowcut.app.core.model.TransitionType
import com.flowcut.app.core.model.map
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.core.time.msToAudioFrames
import com.flowcut.app.core.time.msToUs
import com.flowcut.app.core.time.usToAudioFrames
import com.flowcut.app.core.time.usToMs
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.ComparisonCounter
import com.flowcut.app.core.timeline.LaneIndex
import com.flowcut.app.core.timeline.MainIndex
import com.flowcut.app.core.timeline.SnapshotHolder
import com.flowcut.app.core.timeline.TimelineSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreFoundationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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
        return Project(clips = clips, transitions = transitions)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Section 7.7 Worked Example Table (Microseconds resolution)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testSection7_7_Table_Microseconds() {
        val project = createSpecTestProject()
        val snapshot = TimelineSnapshot(version = 1L, project = project)

        // Check start, end, total
        assertEquals(0L, snapshot.main.startMs[0])
        assertEquals(4000L, snapshot.main.startMs[1])
        assertEquals(7200L, snapshot.main.startMs[2])

        assertEquals(5000L, snapshot.main.endMs[0])
        assertEquals(8000L, snapshot.main.endMs[1])
        assertEquals(13200L, snapshot.main.endMs[2])

        assertEquals(13200L, snapshot.totalMs)

        val active = ActiveSet(laneCount = snapshot.lanes.size)

        // t = 0 ms -> 0 us: A = clip0 @ 0 us, solo
        snapshot.resolve(0L, active)
        assertEquals(0, active.aIndex)
        assertEquals(0L, active.aLocalUs)
        assertEquals(-1, active.bIndex)
        assertEquals(TransitionType.NONE, active.type)
        assertEquals(0f, active.linearP, 0.001f)

        // t = 3999 ms -> 3,999,000 us: A = clip0 @ 3,999,000 us, solo
        snapshot.resolve(3_999_000L, active)
        assertEquals(0, active.aIndex)
        assertEquals(3_999_000L, active.aLocalUs)
        assertEquals(-1, active.bIndex)

        // t = 4000 ms -> 4,000,000 us: A = clip0 @ 4000 ms, B = clip1 @ 0 ms, p = 0.0, SLIDE
        snapshot.resolve(4_000_000L, active)
        assertEquals(0, active.aIndex)
        assertEquals(4_000_000L, active.aLocalUs)
        assertEquals(1, active.bIndex)
        assertEquals(0L, active.bLocalUs)
        assertEquals(TransitionType.SLIDE, active.type)
        assertEquals(0.0f, active.linearP, 0.001f)

        // t = 4500 ms -> 4,500,000 us: A = clip0 @ 4500 ms, B = clip1 @ 500 ms, p = 0.5, SLIDE
        snapshot.resolve(4_500_000L, active)
        assertEquals(0, active.aIndex)
        assertEquals(4_500_000L, active.aLocalUs)
        assertEquals(1, active.bIndex)
        assertEquals(500_000L, active.bLocalUs)
        assertEquals(TransitionType.SLIDE, active.type)
        assertEquals(0.5f, active.linearP, 0.001f)

        // t = 4999 ms -> 4,999,000 us: A = clip0 @ 4999 ms, B = clip1 @ 999 ms, p = 0.999
        snapshot.resolve(4_999_000L, active)
        assertEquals(0, active.aIndex)
        assertEquals(4_999_000L, active.aLocalUs)
        assertEquals(1, active.bIndex)
        assertEquals(999_000L, active.bLocalUs)
        assertEquals(0.999f, active.linearP, 0.001f)

        // t = 5000 ms -> 5,000,000 us: A = clip1 @ 1000 ms, solo
        snapshot.resolve(5_000_000L, active)
        assertEquals(1, active.aIndex)
        assertEquals(1_000_000L, active.aLocalUs)
        assertEquals(-1, active.bIndex)

        // t = 7200 ms -> 7,200,000 us: A = clip1 @ 3200 ms, B = clip2 @ 0 ms, p = 0.0, CIRCLE
        snapshot.resolve(7_200_000L, active)
        assertEquals(1, active.aIndex)
        assertEquals(3_200_000L, active.aLocalUs)
        assertEquals(2, active.bIndex)
        assertEquals(0L, active.bLocalUs)
        assertEquals(TransitionType.CIRCLE, active.type)
        assertEquals(0.0f, active.linearP, 0.001f)

        // t = 7600 ms -> 7,600,000 us: A = clip1 @ 3600 ms, B = clip2 @ 400 ms, p = 0.5
        snapshot.resolve(7_600_000L, active)
        assertEquals(1, active.aIndex)
        assertEquals(3_600_000L, active.aLocalUs)
        assertEquals(2, active.bIndex)
        assertEquals(400_000L, active.bLocalUs)
        assertEquals(0.5f, active.linearP, 0.001f)

        // t = 8000 ms -> 8,000,000 us: A = clip2 @ 800 ms, solo
        snapshot.resolve(8_000_000L, active)
        assertEquals(2, active.aIndex)
        assertEquals(800_000L, active.aLocalUs)
        assertEquals(-1, active.bIndex)

        // t = 13199 ms -> 13,199,000 us: A = clip2 @ 5999 ms, solo
        snapshot.resolve(13_199_000L, active)
        assertEquals(2, active.aIndex)
        assertEquals(5_999_000L, active.aLocalUs)
        assertEquals(-1, active.bIndex)

        // t = 99999 ms -> clamped to totalMs * 1000 - 1 (13,199,999 us)
        snapshot.resolve(99_999_000L, active)
        assertEquals(2, active.aIndex)
        assertEquals(5_999_999L, active.aLocalUs)
        assertEquals(-1, active.bIndex)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. LaneIndex Binary Search Complexity (1,000,000 items <= 25 comparisons)
    // ---------------------------------------------------------------------------------------------
    private class TestItem(
        override val id: String,
        override val startMs: Long,
        override val durationMs: Long
    ) : LaneItem

    @Test
    fun testLaneIndexBinarySearchComplexity() {
        val itemCount = 1_000_000
        val items = ArrayList<LaneItem>(itemCount)
        // Each item has duration 1000ms with a 1000ms gap: [0..1000), [2000..3000), [4000..5000), ...
        for (i in 0 until itemCount) {
            items.add(TestItem(id = "item_$i", startMs = i * 2000L, durationMs = 1000L))
        }
        val lane = Lane(id = "test_lane", kind = "test_kind", items = items)
        val laneIndex = LaneIndex(lane)

        assertEquals(itemCount, laneIndex.n)

        class CountingTracker : ComparisonCounter {
            var count = 0
            override fun inc() { count++ }
            fun reset() { count = 0 }
        }

        val tracker = CountingTracker()

        // 1. indexAt tests
        // Test hitting item 0
        tracker.reset()
        val idx0 = laneIndex.indexAt(500L, tracker)
        assertEquals(0, idx0)
        assertTrue("indexAt comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        // Test in gap between 0 and 1
        tracker.reset()
        val idxGap = laneIndex.indexAt(1500L, tracker)
        assertEquals(-1, idxGap)
        assertTrue("indexAt comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        // Test middle item
        tracker.reset()
        val midIdx = 500_000
        val midTime = midIdx * 2000L + 500L
        val idxMid = laneIndex.indexAt(midTime, tracker)
        assertEquals(midIdx, idxMid)
        assertTrue("indexAt comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        // Test last item
        tracker.reset()
        val lastIdx = itemCount - 1
        val lastTime = lastIdx * 2000L + 500L
        val idxLast = laneIndex.indexAt(lastTime, tracker)
        assertEquals(lastIdx, idxLast)
        assertTrue("indexAt comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        // Test before start
        tracker.reset()
        val idxBefore = laneIndex.indexAt(-100L, tracker)
        assertEquals(-1, idxBefore)
        assertTrue("indexAt comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        // Test after end
        tracker.reset()
        val idxAfter = laneIndex.indexAt(itemCount * 2000L + 1000L, tracker)
        assertEquals(-1, idxAfter)
        assertTrue("indexAt comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        // 2. firstEndingAfter tests
        tracker.reset()
        val firstEnd = laneIndex.firstEndingAfter(1500L, tracker)
        assertEquals(1, firstEnd)
        assertTrue("firstEndingAfter comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        tracker.reset()
        val firstEndMid = laneIndex.firstEndingAfter(midTime, tracker)
        assertEquals(midIdx, firstEndMid)
        assertTrue("firstEndingAfter comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        tracker.reset()
        val firstEndPast = laneIndex.firstEndingAfter(itemCount * 2000L + 5000L, tracker)
        assertEquals(itemCount, firstEndPast)
        assertTrue("firstEndingAfter comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        // 3. lastStartingBefore tests
        tracker.reset()
        val lastStart0 = laneIndex.lastStartingBefore(0L, tracker)
        assertEquals(-1, lastStart0)
        assertTrue("lastStartingBefore comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        tracker.reset()
        val lastStart2500 = laneIndex.lastStartingBefore(2500L, tracker)
        assertEquals(1, lastStart2500)
        assertTrue("lastStartingBefore comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        tracker.reset()
        val lastStartMid = laneIndex.lastStartingBefore(midTime + 1000L, tracker)
        assertEquals(midIdx, lastStartMid)
        assertTrue("lastStartingBefore comparisons ($tracker.count) must be <= 25", tracker.count <= 25)

        tracker.reset()
        val lastStartEnd = laneIndex.lastStartingBefore(itemCount * 2000L + 5000L, tracker)
        assertEquals(itemCount - 1, lastStartEnd)
        assertTrue("lastStartingBefore comparisons ($tracker.count) must be <= 25", tracker.count <= 25)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. TimelineSnapshot Immutability
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testTimelineSnapshotImmutability() {
        val initialProject = createSpecTestProject()
        val snapshot1 = TimelineSnapshot(version = 1L, project = initialProject)

        val holder = SnapshotHolder(snapshot1)
        assertEquals(1L, holder.current.version)
        assertEquals(13200L, holder.current.totalMs)

        // Simulate an edit producing a new project
        val updatedClips = initialProject.clips + Clip(
            id = "c3",
            path = "/clip3.mp4",
            durationMs = 3000L,
            width = 1080,
            height = 1920,
            rotation = 0
        )
        val updatedTransitions = initialProject.transitions + Transition(TransitionType.NONE, 0L)
        val project2 = initialProject.copy(clips = updatedClips, transitions = updatedTransitions)

        val snapshot2 = TimelineSnapshot(version = 2L, project = project2)

        // Swap holder
        holder.current = snapshot2

        // Verify snapshot1 was completely untouched
        assertEquals(1L, snapshot1.version)
        assertEquals(13200L, snapshot1.totalMs)
        assertEquals(3, snapshot1.project.clips.size)

        // Verify holder has snapshot2
        assertEquals(2L, holder.current.version)
        assertEquals(16200L, holder.current.totalMs)
        assertEquals(4, holder.current.project.clips.size)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Validator Invariants
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testValidatorValidProject() {
        val project = createSpecTestProject()
        assertEquals(ValidationResult.Valid, Validator.validate(project))
        Validator.check(project) // Should not throw
    }

    @Test(expected = ValidationException::class)
    fun testValidatorClipDurationUnder1s() {
        val invalidProject = Project(
            clips = listOf(
                Clip("c0", "/path/0.mp4", 999L, 1080, 1920, 0)
            ),
            transitions = emptyList()
        )
        Validator.check(invalidProject)
    }

    @Test(expected = ValidationException::class)
    fun testValidatorTotalDurationExceeds15Minutes() {
        val invalidProject = Project(
            clips = listOf(
                Clip("c0", "/path/0.mp4", 15 * 60 * 1000L + 1L, 1080, 1920, 0)
            ),
            transitions = emptyList()
        )
        Validator.check(invalidProject)
    }

    @Test(expected = ValidationException::class)
    fun testValidatorLaneItemsOverlap() {
        val overlappingItems = listOf(
            TestItem("item1", startMs = 1000L, durationMs = 2000L), // [1000..3000)
            TestItem("item2", startMs = 2500L, durationMs = 1000L)  // [2500..3500) overlaps!
        )
        val project = Project(
            clips = listOf(Clip("c0", "/path/0.mp4", 5000L, 1080, 1920, 0)),
            lanes = listOf(Lane(id = "l1", kind = "text", items = overlappingItems))
        )
        Validator.check(project)
    }

    @Test(expected = ValidationException::class)
    fun testValidatorLaneItemsUnsorted() {
        val unsortedItems = listOf(
            TestItem("item1", startMs = 3000L, durationMs = 1000L),
            TestItem("item2", startMs = 1000L, durationMs = 1000L)
        )
        val project = Project(
            clips = listOf(Clip("c0", "/path/0.mp4", 5000L, 1080, 1920, 0)),
            lanes = listOf(Lane(id = "l1", kind = "text", items = unsortedItems))
        )
        Validator.check(project)
    }

    @Test(expected = ValidationException::class)
    fun testValidatorNegativeStartMs() {
        val negativeItems = listOf(
            TestItem("item1", startMs = -500L, durationMs = 1000L)
        )
        val project = Project(
            clips = listOf(Clip("c0", "/path/0.mp4", 5000L, 1080, 1920, 0)),
            lanes = listOf(Lane(id = "l1", kind = "text", items = negativeItems))
        )
        Validator.check(project)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Time and TimeMap
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testTimeConversions() {
        assertEquals(1_000_000L, msToUs(1000L))
        assertEquals(1000L, usToMs(1_000_000L))
        assertEquals(48_000L, msToAudioFrames(1000L))
        assertEquals(48_000L, usToAudioFrames(1_000_000L))
    }

    @Test
    fun testTimeMapAndClipMapping() {
        val clip = Clip(id = "c0", path = "/c0.mp4", durationMs = 5000L, width = 1080, height = 1920, rotation = 0)
        val defaultMap = clip.map()
        assertEquals(0L, defaultMap.inUs)
        assertEquals(5_000_000L, defaultMap.outUs)
        assertEquals(5_000_000L, defaultMap.timelineDurationUs())
        assertEquals(2_000_000L, defaultMap.sourceUs(2_000_000L))

        // Custom timeMap with speed 2x (speedNum = 2, speedDen = 1)
        val fastMap = TimeMap(inUs = 1_000_000L, outUs = 5_000_000L, speedNum = 2, speedDen = 1)
        assertEquals(2_000_000L, fastMap.timelineDurationUs()) // (5s - 1s) / 2 = 2s
        assertEquals(1_000_000L + 500_000L * 2, fastMap.sourceUs(500_000L)) // 2_000_000 us source
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Backward Compatibility for Project JSON
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testExistingProjectJsonLoadsCleanly() {
        // Old json format containing only clips and transitions
        val oldJson = """
            {
                "clips": [
                    {
                        "id": "c1",
                        "path": "/storage/clip1.mp4",
                        "durationMs": 5000,
                        "width": 1080,
                        "height": 1920,
                        "rotation": 0,
                        "frameRate": 30
                    }
                ],
                "transitions": []
            }
        """.trimIndent()

        val decoded = json.decodeFromString<Project>(oldJson)
        assertEquals(1, decoded.clips.size)
        assertEquals("c1", decoded.clips[0].id)
        assertNull(decoded.clips[0].timeMap)
        assertEquals(0, decoded.transitions.size)
        assertEquals(0, decoded.lanes.size) // Default emptyList()

        // Encode back and decode
        val encoded = json.encodeToString(decoded)
        val reDecoded = json.decodeFromString<Project>(encoded)
        assertEquals(decoded, reDecoded)
    }
}

