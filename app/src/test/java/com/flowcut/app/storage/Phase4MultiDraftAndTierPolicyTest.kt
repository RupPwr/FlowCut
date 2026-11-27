package com.flowcut.app.storage

import com.flowcut.app.engine.perf.DeviceProfile
import com.flowcut.app.engine.perf.Tier
import com.flowcut.app.engine.perf.TierPolicy
import com.flowcut.app.model.Clip
import com.flowcut.app.model.Project
import com.flowcut.app.model.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4MultiDraftAndTierPolicyTest {

    @Test
    fun draftLibraryUtils_summarizesAndSortsDraftsNewestFirst() {
        val draftA = FullProjectDraft(
            draftId = "a",
            title = "Beach Vlog",
            updatedAtMs = 1_000L,
            aspectRatioModeName = "RATIO_16_9",
            project = Project(
                clips = listOf(
                    Clip(id = "c1", path = "/clips/1.mp4", durationMs = 4_000L, width = 1920, height = 1080, rotation = 0),
                    Clip(id = "c2", path = "/clips/2.mp4", durationMs = 6_000L, width = 1920, height = 1080, rotation = 0)
                ),
                transitions = listOf(Transition())
            )
        )
        val draftB = FullProjectDraft(
            draftId = "b",
            title = "",
            updatedAtMs = 5_000L,
            customCoverPath = "/covers/custom.jpg",
            aspectRatioModeName = "RATIO_9_16",
            project = Project(
                clips = listOf(Clip(id = "c3", path = "/clips/3.mp4", durationMs = 3_000L, width = 1080, height = 1920, rotation = 0))
            )
        )

        val sumA = DraftLibraryUtils.summarizeDraft(draftA, "a", "Project 1", nowMs = 2_000L)
        val sumB = DraftLibraryUtils.summarizeDraft(draftB, "b", "Project 2", nowMs = 6_000L)

        assertEquals("Beach Vlog", sumA.title)
        assertEquals(2, sumA.clipCount)
        assertEquals(10_000L, sumA.totalDurationMs)
        assertEquals("16:9", sumA.aspectRatioLabel)
        assertEquals("/clips/1.mp4", sumA.coverPath)

        assertEquals("Project 2", sumB.title)
        assertEquals("/covers/custom.jpg", sumB.coverPath)

        val list1 = DraftLibraryUtils.upsertSummary(emptyList(), sumA)
        val list2 = DraftLibraryUtils.upsertSummary(list1, sumB)
        assertEquals(listOf("b", "a"), list2.map { it.id })

        // Updating draft A to newer timestamp moves it to front without duplicating
        val updatedA = sumA.copy(updatedAtMs = 9_000L)
        val list3 = DraftLibraryUtils.upsertSummary(list2, updatedA)
        assertEquals(listOf("a", "b"), list3.map { it.id })
    }

    @Test
    fun draftLibraryUtils_generatesCollisionFreeTitlesAndCollectsSharedMedia() {
        val existing = listOf("Project 1", "project 2", "Travel Reel Copy")
        assertEquals("Project 3", DraftLibraryUtils.generateNextDefaultTitle(existing))
        assertEquals("Travel Reel Copy 2", DraftLibraryUtils.generateUniqueTitle("Travel Reel Copy", existing))
        assertEquals("My Edit", DraftLibraryUtils.generateUniqueTitle("My Edit", existing))

        val d1 = FullProjectDraft(
            project = Project(clips = listOf(Clip(id = "1", path = "/clips/shared.mp4", durationMs = 1000L, width = 1080, height = 1920, rotation = 0))),
            customCoverPath = "/clips/cover.jpg"
        )
        val d2 = FullProjectDraft(
            project = Project(clips = listOf(Clip(id = "2", path = "/clips/other.mp4", durationMs = 2000L, width = 1080, height = 1920, rotation = 0))),
            layerItems = listOf(
                OverlayItemDraft(
                    id = "o1",
                    type = "AUDIO",
                    laneIndex = 0,
                    zIndex = 1,
                    startUs = 0L,
                    endUs = 1_000_000L,
                    mediaPath = "/clips/bgm.wav"
                )
            )
        )
        val referenced = DraftLibraryUtils.collectReferencedMediaPaths(listOf(d1, d2))
        assertEquals(setOf("/clips/shared.mp4", "/clips/cover.jpg", "/clips/other.mp4", "/clips/bgm.wav"), referenced)
    }

    @Test
    fun draftLibraryUtils_formatsRelativeTimestampsCleanly() {
        val now = 1_700_000_000_000L
        assertEquals("Just now", DraftLibraryUtils.formatRelativeTime(now - 20_000L, now))
        assertEquals("5m ago", DraftLibraryUtils.formatRelativeTime(now - 5 * 60_000L, now))
        assertEquals("2h ago", DraftLibraryUtils.formatRelativeTime(now - 2 * 3_600_000L, now))
        assertEquals("3d ago", DraftLibraryUtils.formatRelativeTime(now - 3 * 86_400_000L, now))
    }

    @Test
    fun tierPolicy_autoTunesLowEndDecoderConstrainedDevicesAndBudgets() {
        // Phone with 4 GB RAM but only 2 hardware AVC decoder instances -> classified Tier.LOW
        val constrainedDecoderPhone = DeviceProfile(
            sdk = 31,
            totalRamMb = 4096L,
            isLowRam = false,
            mediaPerfClass = 0,
            hwAvcEnc1080p60 = true,
            hwAvcDecInstances = 2
        )
        val lowBudget = TierPolicy.budget(constrainedDecoderPhone)
        assertEquals(Tier.LOW, lowBudget.tier)
        assertEquals(96, lowBudget.thumbnailMemEntries)
        assertEquals(720, lowBudget.recommendedExportResolution)
        assertEquals(15, lowBudget.pipUpdateFps)
        assertTrue(lowBudget.autoProxy)

        val highPhone = DeviceProfile(
            sdk = 34,
            totalRamMb = 8192L,
            isLowRam = false,
            mediaPerfClass = 34,
            hwAvcEnc1080p60 = true,
            hwAvcDecInstances = 16
        )
        val highBudget = TierPolicy.budget(highPhone)
        assertEquals(Tier.HIGH, highBudget.tier)
        assertEquals(256, highBudget.thumbnailMemEntries)
        assertEquals(1080, highBudget.recommendedExportResolution)
    }
}
