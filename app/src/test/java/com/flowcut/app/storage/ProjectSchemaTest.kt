package com.flowcut.app.storage

import com.flowcut.app.model.CURRENT_PROJECT_SCHEMA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class ProjectSchemaTest {

    @Test
    fun testLegacyProjectJsonWithoutSchemaVersionLoadsAsCurrentSchema() {
        // Legacy JSON only having clips and transitions without schemaVersion
        val legacyJson = """
            {
                "clips": [],
                "transitions": []
            }
        """.trimIndent()

        val project = decodeProject(legacyJson)
        assertEquals(CURRENT_PROJECT_SCHEMA, project.schemaVersion)
        assertEquals(0, project.clips.size)
        assertEquals(0, project.transitions.size)

        val draft = decodeFullDraft(legacyJson)
        assertEquals(CURRENT_PROJECT_SCHEMA, draft.project.schemaVersion)
    }

    @Test
    fun testProjectJsonWithCurrentSchemaVersionLoadsCleanly() {
        val currentJson = """
            {
                "clips": [],
                "transitions": [],
                "schemaVersion": 1
            }
        """.trimIndent()

        val project = decodeProject(currentJson)
        assertEquals(1, project.schemaVersion)

        val draft = decodeFullDraft(currentJson)
        assertEquals(1, draft.project.schemaVersion)
    }

    @Test
    fun testProjectWithNewerSchemaVersionThrowsNewerProjectException() {
        val futureJson = """
            {
                "clips": [],
                "transitions": [],
                "schemaVersion": 99
            }
        """.trimIndent()

        try {
            decodeProject(futureJson)
            fail("Expected NewerProjectException for schemaVersion 99")
        } catch (e: NewerProjectException) {
            assertEquals(99, e.found)
        }

        try {
            decodeFullDraft(futureJson)
            fail("Expected NewerProjectException for schemaVersion 99 in decodeFullDraft")
        } catch (e: NewerProjectException) {
            assertEquals(99, e.found)
        }
    }

    @Test
    fun testFullProjectDraftWithNewerSchemaVersionThrowsNewerProjectException() {
        val futureDraftJson = """
            {
                "version": 2,
                "project": {
                    "clips": [],
                    "transitions": [],
                    "schemaVersion": 99
                },
                "aspectRatioModeName": "RATIO_9_16"
            }
        """.trimIndent()

        try {
            decodeFullDraft(futureDraftJson)
            fail("Expected NewerProjectException for FullProjectDraft with schemaVersion 99")
        } catch (e: NewerProjectException) {
            assertEquals(99, e.found)
        }
    }

    @Test
    fun testFullProjectDraftWithCurrentSchemaVersionLoadsCleanly() {
        val validDraftJson = """
            {
                "version": 2,
                "project": {
                    "clips": [],
                    "transitions": [],
                    "schemaVersion": 1
                },
                "aspectRatioModeName": "RATIO_9_16"
            }
        """.trimIndent()

        val draft = decodeFullDraft(validDraftJson)
        assertNotNull(draft)
        assertEquals(1, draft.project.schemaVersion)
        assertEquals("RATIO_9_16", draft.aspectRatioModeName)
    }

    @Test
    fun testClipColorAdjustRoundTripPreservesClarityAndSharpPlus() {
        val original = com.flowcut.app.engine.ClipColorAdjust(
            brightness = 0.25f,
            contrast = -0.15f,
            saturation = 0.4f,
            exposure = 0.1f,
            warmth = -0.2f,
            tint = 0.05f,
            highlights = 0.3f,
            shadows = -0.35f,
            sharpness = 0.5f,
            vignette = 0.6f,
            clarity = 0.72f,
            sharpPlus = 0.88f
        )

        val draft = FullProjectDraft(
            clipColorAdjusts = mapOf("clip-1" to original.toDraft())
        )
        val encoded = projectJson.encodeToString(FullProjectDraft.serializer(), draft)
        val decoded = decodeFullDraft(encoded)
        val restored = decoded.clipColorAdjusts["clip-1"]?.toDomain()

        assertNotNull(restored)
        assertEquals(original, restored)
        assertEquals(0.72f, restored!!.clarity, 0.0001f)
        assertEquals(0.88f, restored.sharpPlus, 0.0001f)
    }

    @Test
    fun testLegacyClipColorAdjustDraftWithoutClarityAndSharpPlusDefaultsToZero() {
        val legacyDraftJson = """
            {
                "version": 2,
                "project": {
                    "clips": [],
                    "transitions": [],
                    "schemaVersion": 1
                },
                "clipColorAdjusts": {
                    "clip-legacy": {
                        "brightness": 0.2,
                        "contrast": 0.1,
                        "saturation": 0.0,
                        "exposure": 0.0,
                        "warmth": 0.0,
                        "tint": 0.0,
                        "highlights": 0.0,
                        "shadows": 0.0,
                        "sharpness": 0.3,
                        "vignette": 0.4
                    }
                }
            }
        """.trimIndent()

        val decoded = decodeFullDraft(legacyDraftJson)
        val restored = decoded.clipColorAdjusts["clip-legacy"]?.toDomain()

        assertNotNull(restored)
        assertEquals(0.2f, restored!!.brightness, 0.0001f)
        assertEquals(0.3f, restored.sharpness, 0.0001f)
        assertEquals(0.4f, restored.vignette, 0.0001f)
        assertEquals(0.0f, restored.clarity, 0.0001f)
        assertEquals(0.0f, restored.sharpPlus, 0.0001f)
    }
}
