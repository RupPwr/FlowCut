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
import com.flowcut.app.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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
    val vignette: Float = 0f
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
    vignette = vignette
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
    vignette = vignette
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
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val projectFile: File
        get() = File(context.filesDir, "project.json")

    private val clipsDir: File
        get() = File(context.filesDir, "clips").apply { if (!exists()) mkdirs() }

    suspend fun saveFull(draft: FullProjectDraft): Unit = withContext(Dispatchers.IO) {
        val tempFile = File(context.filesDir, "project.json.tmp")
        val content = json.encodeToString(draft)
        tempFile.writeText(content)
        if (tempFile.exists()) {
            tempFile.renameTo(projectFile)
        }
    }

    suspend fun save(project: Project): Unit = withContext(Dispatchers.IO) {
        val existing = loadFull()
        saveFull(existing.copy(project = project))
    }

    suspend fun loadFull(): FullProjectDraft = withContext(Dispatchers.IO) {
        if (!projectFile.exists()) {
            return@withContext FullProjectDraft()
        }
        try {
            val content = projectFile.readText()
            try {
                json.decodeFromString<FullProjectDraft>(content)
            } catch (_: Exception) {
                // Fallback for legacy project.json that only contained Project
                val legacyProject = json.decodeFromString<Project>(content)
                FullProjectDraft(project = legacyProject)
            }
        } catch (_: Exception) {
            FullProjectDraft()
        }
    }

    suspend fun load(): Project = withContext(Dispatchers.IO) {
        loadFull().project
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

    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        if (projectFile.exists()) {
            projectFile.delete()
        }
        val tempFile = File(context.filesDir, "project.json.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        val allClips = clipsDir.listFiles() ?: return@withContext
        for (file in allClips) {
            if (file.isFile) {
                file.delete()
            }
        }
    }
}
