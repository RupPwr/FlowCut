package com.flowcut.app.storage

import android.content.Context
import com.flowcut.app.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ProjectStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val projectFile: File
        get() = File(context.filesDir, "project.json")

    private val clipsDir: File
        get() = File(context.filesDir, "clips").apply { if (!exists()) mkdirs() }

    suspend fun save(project: Project): Unit = withContext(Dispatchers.IO) {
        val tempFile = File(context.filesDir, "project.json.tmp")
        val content = json.encodeToString(project)
        tempFile.writeText(content)
        if (tempFile.exists()) {
            tempFile.renameTo(projectFile)
        }
    }

    suspend fun load(): Project = withContext(Dispatchers.IO) {
        if (!projectFile.exists()) {
            return@withContext Project()
        }
        try {
            val content = projectFile.readText()
            json.decodeFromString<Project>(content)
        } catch (e: Exception) {
            Project()
        }
    }

    suspend fun cleanupOrphanClips(activeProject: Project): Unit = withContext(Dispatchers.IO) {
        val activePaths = activeProject.clips.map { it.path }.toSet()
        val allClips = clipsDir.listFiles() ?: return@withContext
        for (file in allClips) {
            if (file.isFile && !activePaths.contains(file.absolutePath)) {
                file.delete()
            }
        }
    }
}
