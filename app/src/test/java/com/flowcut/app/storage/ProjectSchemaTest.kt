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
}
