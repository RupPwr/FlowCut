package com.flowcut.app.storage

import android.content.Context
import com.flowcut.app.core.time.TimeMap
import com.flowcut.app.engine.AspectRatioMode
import com.flowcut.app.engine.BackgroundType
import com.flowcut.app.engine.ClipBackground
import com.flowcut.app.engine.ClipColorAdjust
import com.flowcut.app.engine.ClipFilterState
import com.flowcut.app.engine.ClipTransform
import com.flowcut.app.engine.LayerType
import com.flowcut.app.engine.OverlayItem
import com.flowcut.app.engine.OverlayKeyframe
import com.flowcut.app.model.CURRENT_PROJECT_SCHEMA
import com.flowcut.app.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class NewerProjectException(val found: Int) : Exception("Project schema $found > $CURRENT_PROJECT_SCHEMA")

val projectJson: Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}

fun decodeProject(text: String): Project {
    val p = projectJson.decodeFromString<Project>(text)
    if (p.schemaVersion > CURRENT_PROJECT_SCHEMA) {
        throw NewerProjectException(p.schemaVersion)
    }
    return p
}

fun decodeFullDraft(text: String): FullProjectDraft {
    val isDraft = text.contains("\"project\"")
    val draft = if (isDraft) {
        try {
            projectJson.decodeFromString<FullProjectDraft>(text)
        } catch (e: Exception) {
            if (e is NewerProjectException) throw e
            val legacyProject = decodeProject(text)
            FullProjectDraft(project = legacyProject)
        }
    } else {
        val legacyProject = decodeProject(text)
        FullProjectDraft(project = legacyProject)
    }

    if (draft.project.schemaVersion > CURRENT_PROJECT_SCHEMA) {
        throw NewerProjectException(draft.project.schemaVersion)
    }
    return draft
}

@Serializable
data class TimeMapDraft(
    val inUs: Long = 0L,
    val outUs: Long = 0L,
    val speedNum: Int = 1,
    val speedDen: Int = 1
)

@Serializable
data class ClipTransformDraft(
    val rotationDeg: Float = 0f,
    val isFlippedH: Boolean = false,
    val scale: Float = 1.0f,
    val panX: Float = 0.0f,
    val panY: Float = 0.0f,
    val opacity: Float = 1.0f
)

@Serializable
data class ClipColorAdjustDraft(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpness: Float = 0f,
    val vignette: Float = 0f,
    val clarity: Float = 0f,
    val sharpPlus: Float = 0f
)

@Serializable
data class ClipBackgroundDraft(
    val type: String = "NONE",
    val colorArgb: Int = 0xFF000000.toInt(),
    val blurLevel: Float = 0.5f,
    val gradientIndex: Int = -1
)

@Serializable
data class ClipFilterStateDraft(
    val filterPresetId: Int = 0,
    val filterIntensity: Float = 0.8f,
    val effectPresetId: Int = 0,
    val effectIntensity: Float = 0.7f
)

@Serializable
data class JunctionTransitionDraft(
    val styleMode: Int = 0,
    val durationMs: Long = 0L
)

@Serializable
data class OverlayKeyframeDraft(
    val offsetUs: Long,
    val panX: Float,
    val panY: Float,
    val scale: Float,
    val rotationDeg: Float,
    val opacity: Float = 1.0f
)

@Serializable
data class OverlayItemDraft(
    val id: String,
    val type: String,
    val laneIndex: Int,
    val zIndex: Int,
    val startUs: Long,
    val endUs: Long,
    val transform: ClipTransformDraft = ClipTransformDraft(),
    val colorAdjust: ClipColorAdjustDraft = ClipColorAdjustDraft(),
    val text: String = "",
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val bgStyle: Int = 0,
    val fontIndex: Int = 0,
    val textAlpha: Float = 1.0f,
    val labelColorArgb: Int = 0,
    val labelAlpha: Float = 0.0f,
    val borderColorArgb: Int = 0xFF000000.toInt(),
    val borderAlpha: Float = 0.0f,
    val shadowColorArgb: Int = 0xFF000000.toInt(),
    val shadowAlpha: Float = 0.6f,
    val fontName: String = "Roboto-Medium",
    val customFontPath: String = "",
    val textAlign: Int = 1,
    val letterSpacing: Float = 0.0f,
    val lineSpacing: Float = 1.0f,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isUppercase: Boolean = false,
    val animInType: Int = 0,
    val animInDurationUs: Long = 500_000L,
    val animOutType: Int = 0,
    val animOutDurationUs: Long = 500_000L,
    val animLoopType: Int = 0,
    val mediaPath: String = "",
    val intrinsicWidth: Int = 0,
    val intrinsicHeight: Int = 0,
    val filterPresetId: Int = 0,
    val filterIntensity: Float = 1.0f,
    val effectPresetId: Int = 0,
    val effectIntensity: Float = 1.0f,
    val audioSubType: Int = 0,
    val volume: Float = 1.0f,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val speed: Float = 1.0f,
    val sourceInUs: Long = 0L,
    val naturalDurationUs: Long = 0L,
    val keyframes: List<OverlayKeyframeDraft> = emptyList(),
    val maskType: Int = 0,
    val maskFeather: Float = 0.05f,
    val maskInvert: Boolean = false,
    val cropLeft: Float = 0.0f,
    val cropTop: Float = 0.0f,
    val cropRight: Float = 1.0f,
    val cropBottom: Float = 1.0f,
    val chromaEnabled: Boolean = false,
    val chromaColorArgb: Int = 0xFF00FF00.toInt(),
    val chromaThreshold: Float = 0.25f,
    val chromaBlend: Float = 0.10f,
    val adjustBrightness: Float = 0.0f,
    val adjustContrast: Float = 1.0f,
    val adjustSaturation: Float = 1.0f,
    val mosaicStyle: Int = 0,
    val mosaicIntensity: Float = 0.5f,
    val mosaicFeather: Float = 0.05f
)

@Serializable
data class FullProjectDraft(
    val version: Int = 2,
    val draftId: String = "",
    val title: String = "",
    val updatedAtMs: Long = 0L,
    val project: Project = Project(),
    val aspectRatioModeName: String = "RATIO_9_16",
    val isMainTrackMuted: Boolean = false,
    val coverTimeUs: Long = 0L,
    val customCoverPath: String? = null,
    val canvasBgMode: Int = 0,
    val canvasBlurLevel: Float = 0.5f,
    val canvasColorArgb: Int = 0xFF000000.toInt(),
    val canvasGradientId: Int = -1,
    val clipTimeMaps: Map<String, TimeMapDraft> = emptyMap(),
    val clipVolumes: Map<String, Float> = emptyMap(),
    val savedPreMuteVolumes: Map<String, Float> = emptyMap(),
    val clipTransforms: Map<String, ClipTransformDraft> = emptyMap(),
    val clipColorAdjusts: Map<String, ClipColorAdjustDraft> = emptyMap(),
    val clipBackgrounds: Map<String, ClipBackgroundDraft> = emptyMap(),
    val clipFilterStates: Map<String, ClipFilterStateDraft> = emptyMap(),
    val junctionTransitions: Map<Int, JunctionTransitionDraft> = emptyMap(),
    val layerItems: List<OverlayItemDraft> = emptyList()
)

@Serializable
data class ProjectDraftSummary(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
    val clipCount: Int,
    val totalDurationMs: Long,
    val aspectRatioLabel: String,
    val coverPath: String? = null,
    val coverTimeUs: Long = 0L
)

@Serializable
data class ProjectDraftIndex(
    val activeDraftId: String? = null,
    val drafts: List<ProjectDraftSummary> = emptyList()
)

/**
 * Pure-Kotlin multi-draft library utilities (zero android.* dependencies for JVM testing).
 */
object DraftLibraryUtils {

    fun summarizeDraft(
        draft: FullProjectDraft,
        id: String,
        fallbackTitle: String,
        nowMs: Long
    ): ProjectDraftSummary {
        val resolvedTitle = draft.title.trim().ifEmpty { fallbackTitle }
        val aspectLabel = AspectRatioMode.entries
            .firstOrNull { it.name == draft.aspectRatioModeName }
            ?.label ?: "9:16"
        val coverPath = draft.customCoverPath?.takeIf { it.isNotBlank() }
            ?: draft.project.clips.firstOrNull()?.path
        val totalDurationMs = draft.project.clips.sumOf { it.durationMs.coerceAtLeast(0L) }
        val updatedAt = if (draft.updatedAtMs > 0L) draft.updatedAtMs else nowMs
        return ProjectDraftSummary(
            id = id,
            title = resolvedTitle,
            updatedAtMs = updatedAt,
            clipCount = draft.project.clips.size,
            totalDurationMs = totalDurationMs,
            aspectRatioLabel = aspectLabel,
            coverPath = coverPath,
            coverTimeUs = draft.coverTimeUs
        )
    }

    fun upsertSummary(
        existing: List<ProjectDraftSummary>,
        updated: ProjectDraftSummary
    ): List<ProjectDraftSummary> {
        val filtered = existing.filterNot { it.id == updated.id }
        return (filtered + updated).sortedByDescending { it.updatedAtMs }
    }

    fun generateUniqueTitle(
        baseTitle: String,
        existingTitles: Collection<String>
    ): String {
        val normalized = existingTitles.map { it.trim().lowercase() }.toSet()
        val cleanBase = baseTitle.trim().ifEmpty { "Project" }
        if (cleanBase.lowercase() !in normalized) return cleanBase
        var n = 2
        while (true) {
            val candidate = "$cleanBase $n"
            if (candidate.lowercase() !in normalized) return candidate
            n++
        }
    }

    fun generateNextDefaultTitle(existingTitles: Collection<String>): String {
        val normalized = existingTitles.map { it.trim().lowercase() }.toSet()
        var n = 1
        while (true) {
            val candidate = "Project $n"
            if (candidate.lowercase() !in normalized) return candidate
            n++
        }
    }

    fun collectReferencedMediaPaths(drafts: Collection<FullProjectDraft>): Set<String> {
        val paths = HashSet<String>()
        for (draft in drafts) {
            for (clip in draft.project.clips) {
                if (clip.path.isNotBlank()) paths.add(clip.path)
            }
            draft.customCoverPath?.takeIf { it.isNotBlank() }?.let { paths.add(it) }
            for (item in draft.layerItems) {
                if (item.mediaPath.isNotBlank()) paths.add(item.mediaPath)
            }
        }
        return paths
    }

    fun formatRelativeTime(updatedAtMs: Long, nowMs: Long): String {
        if (updatedAtMs <= 0L) return "Recently"
        val deltaSec = ((nowMs - updatedAtMs).coerceAtLeast(0L)) / 1000L
        return when {
            deltaSec < 60L -> "Just now"
            deltaSec < 3600L -> "${deltaSec / 60L}m ago"
            deltaSec < 86_400L -> "${deltaSec / 3600L}h ago"
            deltaSec < 604_800L -> "${deltaSec / 86_400L}d ago"
            else -> "${deltaSec / 604_800L}w ago"
        }
    }
}

// Converter functions
fun TimeMapDraft.toDomain(): TimeMap = TimeMap(
    inUs = inUs,
    outUs = outUs,
    speedNum = speedNum,
    speedDen = speedDen
)

fun TimeMap.toDraft(): TimeMapDraft = TimeMapDraft(
    inUs = inUs,
    outUs = outUs,
    speedNum = speedNum,
    speedDen = speedDen
)

fun ClipTransformDraft.toDomain(): ClipTransform = ClipTransform(
    rotationDeg = rotationDeg,
    isFlippedH = isFlippedH,
    scale = scale,
    panX = panX,
    panY = panY,
    opacity = opacity
)

fun ClipTransform.toDraft(): ClipTransformDraft = ClipTransformDraft(
    rotationDeg = rotationDeg,
    isFlippedH = isFlippedH,
    scale = scale,
    panX = panX,
    panY = panY,
    opacity = opacity
)

fun ClipColorAdjustDraft.toDomain(): ClipColorAdjust = ClipColorAdjust(
    brightness = brightness,
    contrast = contrast,
    saturation = saturation,
    exposure = exposure,
    warmth = warmth,
    tint = tint,
    highlights = highlights,
    shadows = shadows,
    sharpness = sharpness,
    vignette = vignette,
    clarity = clarity,
    sharpPlus = sharpPlus
)

fun ClipColorAdjust.toDraft(): ClipColorAdjustDraft = ClipColorAdjustDraft(
    brightness = brightness,
    contrast = contrast,
    saturation = saturation,
    exposure = exposure,
    warmth = warmth,
    tint = tint,
    highlights = highlights,
    shadows = shadows,
    sharpness = sharpness,
    vignette = vignette,
    clarity = clarity,
    sharpPlus = sharpPlus
)

fun ClipBackgroundDraft.toDomain(): ClipBackground = ClipBackground(
    type = BackgroundType.entries.firstOrNull { it.name == this.type } ?: BackgroundType.NONE,
    colorArgb = colorArgb,
    blurLevel = blurLevel,
    gradientIndex = gradientIndex
)

fun ClipBackground.toDraft(): ClipBackgroundDraft = ClipBackgroundDraft(
    type = type.name,
    colorArgb = colorArgb,
    blurLevel = blurLevel,
    gradientIndex = gradientIndex
)

fun ClipFilterStateDraft.toDomain(): ClipFilterState = ClipFilterState(
    filterPresetId = filterPresetId,
    filterIntensity = filterIntensity,
    effectPresetId = effectPresetId,
    effectIntensity = effectIntensity
)

fun ClipFilterState.toDraft(): ClipFilterStateDraft = ClipFilterStateDraft(
    filterPresetId = filterPresetId,
    filterIntensity = filterIntensity,
    effectPresetId = effectPresetId,
    effectIntensity = effectIntensity
)

fun OverlayKeyframeDraft.toDomain(): OverlayKeyframe = OverlayKeyframe(
    offsetUs = offsetUs,
    panX = panX,
    panY = panY,
    scale = scale,
    rotationDeg = rotationDeg,
    opacity = opacity
)

fun OverlayKeyframe.toDraft(): OverlayKeyframeDraft = OverlayKeyframeDraft(
    offsetUs = offsetUs,
    panX = panX,
    panY = panY,
    scale = scale,
    rotationDeg = rotationDeg,
    opacity = opacity
)

fun OverlayItemDraft.toDomain(): OverlayItem {
    val resolvedType = LayerType.entries.firstOrNull { it.name == this.type } ?: LayerType.TEXT
    return OverlayItem(
        id = id,
        type = resolvedType,
        laneIndex = laneIndex,
        zIndex = zIndex,
        startUs = startUs,
        endUs = endUs,
        transform = transform.toDomain(),
        colorAdjust = colorAdjust.toDomain(),
        text = text,
        textColorArgb = textColorArgb,
        bgStyle = bgStyle,
        fontIndex = fontIndex,
        textAlpha = textAlpha,
        labelColorArgb = labelColorArgb,
        labelAlpha = labelAlpha,
        borderColorArgb = borderColorArgb,
        borderAlpha = borderAlpha,
        shadowColorArgb = shadowColorArgb,
        shadowAlpha = shadowAlpha,
        fontName = fontName,
        customFontPath = customFontPath,
        textAlign = textAlign,
        letterSpacing = letterSpacing,
        lineSpacing = lineSpacing,
        isBold = isBold,
        isItalic = isItalic,
        isUnderline = isUnderline,
        isUppercase = isUppercase,
        animInType = animInType,
        animInDurationUs = animInDurationUs,
        animOutType = animOutType,
        animOutDurationUs = animOutDurationUs,
        animLoopType = animLoopType,
        mediaPath = mediaPath,
        intrinsicWidth = intrinsicWidth,
        intrinsicHeight = intrinsicHeight,
        filterPresetId = filterPresetId,
        filterIntensity = filterIntensity,
        effectPresetId = effectPresetId,
        effectIntensity = effectIntensity,
        audioSubType = audioSubType,
        volume = volume,
        fadeInUs = fadeInUs,
        fadeOutUs = fadeOutUs,
        speed = speed,
        sourceInUs = sourceInUs,
        naturalDurationUs = naturalDurationUs,
        keyframes = keyframes.map { it.toDomain() },
        maskType = maskType,
        maskFeather = maskFeather,
        maskInvert = maskInvert,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRight = cropRight,
        cropBottom = cropBottom,
        chromaEnabled = chromaEnabled,
        chromaColorArgb = chromaColorArgb,
        chromaThreshold = chromaThreshold,
        chromaBlend = chromaBlend,
        adjustBrightness = adjustBrightness,
        adjustContrast = adjustContrast,
        adjustSaturation = adjustSaturation,
        mosaicStyle = mosaicStyle,
        mosaicIntensity = mosaicIntensity,
        mosaicFeather = mosaicFeather
    )
}

fun OverlayItem.toDraft(): OverlayItemDraft = OverlayItemDraft(
    id = id,
    type = type.name,
    laneIndex = laneIndex,
    zIndex = zIndex,
    startUs = startUs,
    endUs = endUs,
    transform = transform.toDraft(),
    colorAdjust = colorAdjust.toDraft(),
    text = text,
    textColorArgb = textColorArgb,
    bgStyle = bgStyle,
    fontIndex = fontIndex,
    textAlpha = textAlpha,
    labelColorArgb = labelColorArgb,
    labelAlpha = labelAlpha,
    borderColorArgb = borderColorArgb,
    borderAlpha = borderAlpha,
    shadowColorArgb = shadowColorArgb,
    shadowAlpha = shadowAlpha,
    fontName = fontName,
    customFontPath = customFontPath,
    textAlign = textAlign,
    letterSpacing = letterSpacing,
    lineSpacing = lineSpacing,
    isBold = isBold,
    isItalic = isItalic,
    isUnderline = isUnderline,
    isUppercase = isUppercase,
    animInType = animInType,
    animInDurationUs = animInDurationUs,
    animOutType = animOutType,
    animOutDurationUs = animOutDurationUs,
    animLoopType = animLoopType,
    mediaPath = mediaPath,
    intrinsicWidth = intrinsicWidth,
    intrinsicHeight = intrinsicHeight,
    filterPresetId = filterPresetId,
    filterIntensity = filterIntensity,
    effectPresetId = effectPresetId,
    effectIntensity = effectIntensity,
    audioSubType = audioSubType,
    volume = volume,
    fadeInUs = fadeInUs,
    fadeOutUs = fadeOutUs,
    speed = speed,
    sourceInUs = sourceInUs,
    naturalDurationUs = naturalDurationUs,
    keyframes = keyframes.map { it.toDraft() },
    maskType = maskType,
    maskFeather = maskFeather,
    maskInvert = maskInvert,
    cropLeft = cropLeft,
    cropTop = cropTop,
    cropRight = cropRight,
    cropBottom = cropBottom,
    chromaEnabled = chromaEnabled,
    chromaColorArgb = chromaColorArgb,
    chromaThreshold = chromaThreshold,
    chromaBlend = chromaBlend,
    adjustBrightness = adjustBrightness,
    adjustContrast = adjustContrast,
    adjustSaturation = adjustSaturation,
    mosaicStyle = mosaicStyle,
    mosaicIntensity = mosaicIntensity,
    mosaicFeather = mosaicFeather
)

class ProjectStore(private val context: Context) {
    private val projectFile: File
        get() = File(context.filesDir, "project.json")

    private val draftsDir: File
        get() = File(context.filesDir, "drafts").apply { if (!exists()) mkdirs() }

    private val indexFile: File
        get() = File(draftsDir, "index.json")

    private val clipsDir: File
        get() = File(context.filesDir, "clips").apply { if (!exists()) mkdirs() }

    private fun draftFileFor(draftId: String): File {
        val safeId = draftId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(draftsDir, "$safeId.json")
    }

    private fun atomicWriteText(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tempFile = File(parent, "${target.name}.tmp")
        tempFile.writeText(content)
        if (tempFile.exists()) {
            if (!tempFile.renameTo(target)) {
                target.writeText(content)
                tempFile.delete()
            }
        }
    }

    private fun readIndexSync(): ProjectDraftIndex {
        if (!indexFile.exists()) return ProjectDraftIndex()
        return try {
            projectJson.decodeFromString<ProjectDraftIndex>(indexFile.readText())
        } catch (_: Exception) {
            ProjectDraftIndex()
        }
    }

    private fun writeIndexSync(index: ProjectDraftIndex) {
        atomicWriteText(indexFile, projectJson.encodeToString(index))
    }

    private fun ensureMigratedSync(): ProjectDraftIndex {
        val current = readIndexSync()
        if (current.drafts.isNotEmpty() || !projectFile.exists()) {
            return current
        }
        return try {
            val legacy = decodeFullDraft(projectFile.readText())
            if (legacy.project.clips.isEmpty()) {
                current
            } else {
                val now = System.currentTimeMillis()
                val id = legacy.draftId.ifBlank { UUID.randomUUID().toString() }
                val stamped = legacy.copy(
                    draftId = id,
                    title = legacy.title.ifBlank { "Project 1" },
                    updatedAtMs = if (legacy.updatedAtMs > 0L) legacy.updatedAtMs else now,
                    project = legacy.project.copy(schemaVersion = CURRENT_PROJECT_SCHEMA)
                )
                atomicWriteText(draftFileFor(id), projectJson.encodeToString(stamped))
                val summary = DraftLibraryUtils.summarizeDraft(stamped, id, "Project 1", now)
                val migratedIndex = ProjectDraftIndex(activeDraftId = id, drafts = listOf(summary))
                writeIndexSync(migratedIndex)
                migratedIndex
            }
        } catch (e: NewerProjectException) {
            throw e
        } catch (_: Exception) {
            current
        }
    }

    suspend fun listDrafts(): List<ProjectDraftSummary> = withContext(Dispatchers.IO) {
        val index = ensureMigratedSync()
        val validDrafts = index.drafts
            .filter { draftFileFor(it.id).exists() }
            .sortedByDescending { it.updatedAtMs }
        if (validDrafts.size != index.drafts.size) {
            writeIndexSync(index.copy(drafts = validDrafts))
        }
        validDrafts
    }

    suspend fun saveDraft(draft: FullProjectDraft): ProjectDraftSummary = withContext(Dispatchers.IO) {
        val index = ensureMigratedSync()
        val now = System.currentTimeMillis()
        val id = draft.draftId.ifBlank { UUID.randomUUID().toString() }
        val existingTitle = index.drafts.firstOrNull { it.id == id }?.title
        val resolvedTitle = draft.title.trim()
            .ifEmpty { existingTitle ?: DraftLibraryUtils.generateNextDefaultTitle(index.drafts.map { it.title }) }

        val stampedDraft = draft.copy(
            draftId = id,
            title = resolvedTitle,
            updatedAtMs = now,
            project = draft.project.copy(schemaVersion = CURRENT_PROJECT_SCHEMA)
        )
        val encoded = projectJson.encodeToString(stampedDraft)
        atomicWriteText(draftFileFor(id), encoded)
        // Mirror active draft into legacy project.json for full backward compatibility
        atomicWriteText(projectFile, encoded)

        val summary = DraftLibraryUtils.summarizeDraft(stampedDraft, id, resolvedTitle, now)
        val updatedDrafts = DraftLibraryUtils.upsertSummary(index.drafts, summary)
        writeIndexSync(ProjectDraftIndex(activeDraftId = id, drafts = updatedDrafts))
        summary
    }

    suspend fun loadDraft(draftId: String): FullProjectDraft? = withContext(Dispatchers.IO) {
        val file = draftFileFor(draftId)
        if (!file.exists()) return@withContext null
        try {
            val draft = decodeFullDraft(file.readText())
            val index = readIndexSync()
            if (index.activeDraftId != draftId) {
                writeIndexSync(index.copy(activeDraftId = draftId))
            }
            draft
        } catch (e: NewerProjectException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun renameDraft(draftId: String, newTitle: String): List<ProjectDraftSummary> = withContext(Dispatchers.IO) {
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isEmpty()) return@withContext listDrafts()
        val index = ensureMigratedSync()
        val file = draftFileFor(draftId)
        if (file.exists()) {
            try {
                val existing = decodeFullDraft(file.readText())
                val updated = existing.copy(title = cleanTitle)
                atomicWriteText(file, projectJson.encodeToString(updated))
            } catch (_: Exception) {}
        }
        val updatedList = index.drafts.map { s ->
            if (s.id == draftId) s.copy(title = cleanTitle) else s
        }.sortedByDescending { it.updatedAtMs }
        writeIndexSync(index.copy(drafts = updatedList))
        updatedList
    }

    suspend fun duplicateDraft(draftId: String): Pair<ProjectDraftSummary, List<ProjectDraftSummary>>? = withContext(Dispatchers.IO) {
        val index = ensureMigratedSync()
        val sourceFile = draftFileFor(draftId)
        if (!sourceFile.exists()) return@withContext null
        val source = try {
            decodeFullDraft(sourceFile.readText())
        } catch (_: Exception) {
            return@withContext null
        }
        val now = System.currentTimeMillis()
        val newId = UUID.randomUUID().toString()
        val baseTitle = source.title.trim().ifEmpty { "Project" }
        val newTitle = DraftLibraryUtils.generateUniqueTitle("$baseTitle Copy", index.drafts.map { it.title })
        val duplicated = source.copy(
            draftId = newId,
            title = newTitle,
            updatedAtMs = now,
            project = source.project.copy(schemaVersion = CURRENT_PROJECT_SCHEMA)
        )
        atomicWriteText(draftFileFor(newId), projectJson.encodeToString(duplicated))
        val summary = DraftLibraryUtils.summarizeDraft(duplicated, newId, newTitle, now)
        val updatedList = DraftLibraryUtils.upsertSummary(index.drafts, summary)
        writeIndexSync(index.copy(drafts = updatedList))
        Pair(summary, updatedList)
    }

    suspend fun deleteDraft(draftId: String): List<ProjectDraftSummary> = withContext(Dispatchers.IO) {
        val index = ensureMigratedSync()
        val file = draftFileFor(draftId)
        if (file.exists()) file.delete()
        val remaining = index.drafts.filterNot { it.id == draftId }.sortedByDescending { it.updatedAtMs }
        val nextActive = if (index.activeDraftId == draftId) remaining.firstOrNull()?.id else index.activeDraftId
        writeIndexSync(ProjectDraftIndex(activeDraftId = nextActive, drafts = remaining))
        if (remaining.isEmpty() && projectFile.exists()) {
            projectFile.delete()
        }
        cleanupOrphanClipsAcrossAllDrafts()
        remaining
    }

    suspend fun saveFull(draft: FullProjectDraft): Unit = withContext(Dispatchers.IO) {
        if (draft.project.clips.isNotEmpty() || draft.draftId.isNotBlank()) {
            saveDraft(draft)
        } else {
            val stampedDraft = draft.copy(
                project = draft.project.copy(schemaVersion = CURRENT_PROJECT_SCHEMA)
            )
            atomicWriteText(projectFile, projectJson.encodeToString(stampedDraft))
        }
    }

    suspend fun save(project: Project): Unit = withContext(Dispatchers.IO) {
        val stamped = project.copy(schemaVersion = CURRENT_PROJECT_SCHEMA)
        val existing = loadFull()
        saveFull(existing.copy(project = stamped))
    }

    suspend fun loadFull(): FullProjectDraft = withContext(Dispatchers.IO) {
        val index = ensureMigratedSync()
        val activeId = index.activeDraftId ?: index.drafts.firstOrNull()?.id
        if (activeId != null) {
            val loaded = loadDraft(activeId)
            if (loaded != null) return@withContext loaded
        }
        if (!projectFile.exists()) {
            return@withContext FullProjectDraft()
        }
        try {
            val content = projectFile.readText()
            decodeFullDraft(content)
        } catch (e: NewerProjectException) {
            throw e
        } catch (_: Exception) {
            FullProjectDraft()
        }
    }

    suspend fun load(): Project = withContext(Dispatchers.IO) {
        loadFull().project
    }

    suspend fun cleanupOrphanClipsAcrossAllDrafts(extraActiveProject: Project? = null): Unit = withContext(Dispatchers.IO) {
        val index = ensureMigratedSync()
        val loadedDrafts = ArrayList<FullProjectDraft>(index.drafts.size + 1)
        for (summary in index.drafts) {
            val file = draftFileFor(summary.id)
            if (file.exists()) {
                try {
                    loadedDrafts.add(decodeFullDraft(file.readText()))
                } catch (_: Exception) {}
            }
        }
        if (extraActiveProject != null) {
            loadedDrafts.add(FullProjectDraft(project = extraActiveProject))
        }
        val activePaths = DraftLibraryUtils.collectReferencedMediaPaths(loadedDrafts)
        val allClips = clipsDir.listFiles() ?: return@withContext
        for (file in allClips) {
            if (file.isFile && !activePaths.contains(file.absolutePath)) {
                file.delete()
            }
        }
    }

    suspend fun cleanupOrphanClips(activeProject: Project): Unit =
        cleanupOrphanClipsAcrossAllDrafts(extraActiveProject = activeProject)

    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        if (projectFile.exists()) {
            projectFile.delete()
        }
        val tempFile = File(context.filesDir, "project.json.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        draftsDir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        val allClips = clipsDir.listFiles() ?: return@withContext
        for (file in allClips) {
            if (file.isFile) {
                file.delete()
            }
        }
    }
}
