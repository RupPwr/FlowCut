package com.flowcut.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.flowcut.app.engine.proxy.ProxyStore
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Holder for a baked OpenGL GL_TEXTURE_2D overlay texture.
 * Mutable fields allow zero-allocation cache hits on 60/120fps render loops (Rule R10).
 */
class CachedOverlayTex(
    var texId: Int = 0,
    var contentHash: Int = 0,
    var width: Int = 0,
    var height: Int = 0,
    var lastUsedNs: Long = 0L
) {
    val byteSize: Long get() = width.toLong() * height.toLong() * 4L
}

/**
 * One-time GPU Texture Baker & Cache for multi-layer overlays (TEXT, STICKER, IMAGE).
 * Instantiated per GL context (PreviewRenderer and ExportEngine) and freed on release().
 */
class OverlayTextureCache(
    @Volatile var proxyStore: ProxyStore? = null,
    pipDecodeThreads: Int = defaultPipDecodeThreads()
) {

    @Volatile
    var maxByteBudget: Long = defaultMaxByteBudget

    @Volatile
    var pipUpdateFps: Int = defaultPipUpdateFps

    var bakeScale: Float = 1.0f

    private val cache = HashMap<String, CachedOverlayTex>()
    private val videoStates = HashMap<String, VideoPipState>()
    private val tempTexIds = IntArray(1)
    private val retrieverPool = RetrieverPool(maxEntries = 4)

    /**
     * Dedicated background executor for non-blocking preview PIP video frame decoding.
     */
    private val pipDecodeExecutor = Executors.newFixedThreadPool(pipDecodeThreads.coerceIn(1, 4)) { runnable ->
        Thread(runnable, "FlowCut-PipDecode")
    }

    /**
     * Callback invoked whenever a new video overlay frame has been decoded on the background thread.
     */
    var onFrameReady: (() -> Unit)? = null

    /**
     * Computes deterministic content hash for an overlay item.
     * Scale, Pan, Rotation, and Opacity transforms are strictly excluded so dynamic
     * 60fps animations and preview drags never trigger unnecessary texture re-baking!
     */
    fun computeContentHash(item: OverlayItem): Int {
        var h = item.type.ordinal
        h = 31 * h + item.text.hashCode()
        h = 31 * h + item.textColorArgb
        h = 31 * h + item.bgStyle
        h = 31 * h + item.mediaPath.hashCode()
        h = 31 * h + item.fontIndex
        h = 31 * h + item.textAlpha.toBits()
        h = 31 * h + item.labelColorArgb
        h = 31 * h + item.labelAlpha.toBits()
        h = 31 * h + item.borderColorArgb
        h = 31 * h + item.borderAlpha.toBits()
        h = 31 * h + item.shadowColorArgb
        h = 31 * h + item.shadowAlpha.toBits()
        h = 31 * h + item.fontName.hashCode()
        h = 31 * h + item.customFontPath.hashCode()
        h = 31 * h + item.textAlign
        h = 31 * h + item.letterSpacing.toBits()
        h = 31 * h + item.lineSpacing.toBits()
        h = 31 * h + if (item.isBold) 1 else 0
        h = 31 * h + if (item.isItalic) 1 else 0
        h = 31 * h + if (item.isUnderline) 1 else 0
        h = 31 * h + if (item.isUppercase) 1 else 0
        h = 31 * h + item.wrapWidth.toBits()
        h = 31 * h + (bakeScale.coerceIn(1.0f, 2.5f) * 100).toInt()
        return h
    }

    /**
     * Fast retrieval of baked texture.
     * Returns cached (texId, width, height) in < 0.001 ms with ZERO heap allocations
     * during playback (Rule R10).
     */
    fun getOrBakeTexture(item: OverlayItem): CachedOverlayTex? {
        if (item.type == LayerType.VIDEO) {
            return getOrUpdateVideoTexture(item, item.startUs, isSynchronous = false)
        }
        val targetHash = computeContentHash(item)
        val existing = cache[item.id]
        if (existing != null && existing.contentHash == targetHash && existing.texId > 0) {
            existing.lastUsedNs = System.nanoTime()
            return existing
        }

        // Texture needs rasterization and GL upload
        val bitmap = when (item.type) {
            LayerType.TEXT -> bakeText(item)
            LayerType.STICKER -> bakeSticker(item)
            LayerType.IMAGE -> bakeImage(item)
            else -> null
        } ?: return null

        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) {
            bitmap.recycle()
            return null
        }

        val oldTexId = existing?.texId ?: 0
        val targetTexId = if (oldTexId > 0) {
            oldTexId
        } else {
            GLES20.glGenTextures(1, tempTexIds, 0)
            tempTexIds[0]
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targetTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // Immediately recycle the temporary CPU bitmap after GPU upload
        bitmap.recycle()

        val entry = existing ?: CachedOverlayTex().also { cache[item.id] = it }
        entry.texId = targetTexId
        entry.contentHash = targetHash
        entry.width = w
        entry.height = h
        entry.lastUsedNs = System.nanoTime()

        if (item.type == LayerType.TEXT) {
            TransitionRenderer.registerTextTexture(targetTexId)
        } else {
            TransitionRenderer.unregisterTextTexture(targetTexId)
        }

        pruneToBudget(setOf(item.id))
        return entry
    }

    /**
     * Computes total VRAM bytes currently occupied by cached textures.
     */
    fun currentTotalBytes(): Long {
        var total = 0L
        for (entry in cache.values) {
            if (entry.texId > 0) total += entry.byteSize
        }
        for (v in videoStates.values) {
            if (v.texEntry.texId > 0) total += v.texEntry.byteSize
        }
        return total
    }

    /**
     * LRU Cache Pruning (Task T5.4):
     * Evicts oldest unreferenced textures via glDeleteTextures when total bytes exceed maxByteBudget.
     * Protected items (currently visible/active in frame) are NEVER evicted.
     */
    fun pruneToBudget(protectedItemIds: Set<String> = emptySet()) {
        var total = currentTotalBytes()
        if (total <= maxByteBudget) return

        val candidates = cache.entries
            .filter { it.value.texId > 0 && it.key !in protectedItemIds }
            .sortedBy { it.value.lastUsedNs }

        val deleteIds = IntArray(1)
        for ((id, entry) in candidates) {
            if (total <= maxByteBudget) break
            if (entry.texId > 0) {
                deleteIds[0] = entry.texId
                TransitionRenderer.unregisterTextTexture(entry.texId)
                GLES20.glDeleteTextures(1, deleteIds, 0)
                entry.texId = 0
            }
            total -= entry.byteSize
            cache.remove(id)
        }
    }

    private fun resolveTypeface(fontName: String, customFontPath: String, isBold: Boolean, isItalic: Boolean): Typeface {
        val style = when {
            isBold && isItalic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

        if (customFontPath.isNotEmpty()) {
            val file = java.io.File(customFontPath)
            if (file.exists()) {
                try {
                    val tf = Typeface.createFromFile(file)
                    return Typeface.create(tf, style)
                } catch (_: Throwable) {}
            }
        }

        return when (fontName.lowercase()) {
            "permanentmarker", "marker", "casual" -> Typeface.create("casual", style)
            "bebas", "condensed" -> Typeface.create("sans-serif-condensed", if (isBold) Typeface.BOLD else style)
            "aileron" -> Typeface.create("sans-serif", style)
            "roboto-thin", "thin" -> Typeface.create("sans-serif-light", style)
            "serif-bold", "serif" -> Typeface.create(Typeface.SERIF, if (isBold) Typeface.BOLD else style)
            "monospace", "mono" -> Typeface.create(Typeface.MONOSPACE, style)
            else -> Typeface.create("sans-serif-medium", style)
        }
    }

    private fun bakeText(item: OverlayItem): Bitmap? {
        val displayText = if (item.text.isBlank()) "Your Text" else item.text
        val rawText = if (item.isUppercase) displayText.uppercase() else displayText
        if (rawText.isEmpty()) return null

        val effScale = bakeScale.coerceIn(1.0f, 2.5f)
        val textA = (item.textAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val fillTextColor = (item.textColorArgb and 0x00FFFFFF) or (textA shl 24)

        val typeface = resolveTypeface(item.fontName, item.customFontPath, item.isBold, item.isItalic)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillTextColor
            textSize = 68f * effScale
            this.typeface = typeface
            letterSpacing = item.letterSpacing.coerceIn(-0.08f, 1.40f)
            isUnderlineText = item.isUnderline
            isFakeBoldText = item.isBold
        }

        val alignment = when (item.textAlign) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }

        val lineSpacingMultiplier = item.lineSpacing.coerceIn(0.55f, 2.80f)

        // Measure text using full desired width or wrapWidth so multi-line text wraps cleanly
        val desiredWidth = ceil(StaticLayout.getDesiredWidth(rawText, textPaint)).toInt().coerceAtLeast(1)
        val maxTextW = (1920 * effScale).toInt().coerceIn(1920, 4096)
        val textW = if (item.wrapWidth > 0.0f) {
            val targetWrapPx = (item.wrapWidth * 1080f * effScale).roundToInt()
            targetWrapPx.coerceIn((96 * effScale).toInt(), maxTextW)
        } else {
            desiredWidth.coerceIn(1, maxTextW)
        }

        val layout = StaticLayout.Builder.obtain(rawText, 0, rawText.length, textPaint, textW)
            .setAlignment(alignment)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setIncludePad(true)
            .build()

        val textH = layout.height.coerceAtLeast(1)

        val hasLabel = item.labelAlpha > 0.01f || item.bgStyle in 1..2
        val strokeOffset = ((item.borderAlpha.coerceIn(0f, 1f) * 20f).toInt() + 16) * effScale
        val padX = if (hasLabel) (64f * effScale).toInt() else (40f * effScale + strokeOffset).toInt()
        val padY = if (hasLabel) (44f * effScale).toInt() else (28f * effScale + strokeOffset).toInt()

        val bmpW = (textW + padX * 2).coerceIn(1, 4096)
        val bmpH = (textH + padY * 2).coerceIn(1, 4096)
        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Label / Background Pill
        if (item.labelAlpha > 0.01f) {
            val labelA = (item.labelAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
            val labelColor = (item.labelColorArgb and 0x00FFFFFF) or (labelA shl 24)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = labelColor
                style = Paint.Style.FILL
            }
            val rect = RectF(12f * effScale, 10f * effScale, bmpW - 12f * effScale, bmpH - 10f * effScale)
            val cornerRadius = 32f * effScale
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        } else if (item.bgStyle == 1) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCC000000.toInt()
                style = Paint.Style.FILL
            }
            val rect = RectF(12f * effScale, 10f * effScale, bmpW - 12f * effScale, bmpH - 10f * effScale)
            val cornerRadius = 32f * effScale
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        } else if (item.bgStyle == 2) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
            }
            val rect = RectF(12f * effScale, 10f * effScale, bmpW - 12f * effScale, bmpH - 10f * effScale)
            val cornerRadius = 32f * effScale
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        }

        // 2. Border / Stroke Outline with dynamic stroke width up to 20px
        if (item.borderAlpha > 0.01f) {
            val borderA = (item.borderAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
            val borderColor = (item.borderColorArgb and 0x00FFFFFF) or (borderA shl 24)
            val dynamicStrokeWidth = (item.borderAlpha.coerceIn(0f, 1f) * 20f).coerceAtLeast(1.5f) * effScale
            val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = borderColor
                textSize = textPaint.textSize
                this.typeface = textPaint.typeface
                letterSpacing = textPaint.letterSpacing
                isFakeBoldText = textPaint.isFakeBoldText
                style = Paint.Style.STROKE
                strokeWidth = dynamicStrokeWidth
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            val strokeLayout = StaticLayout.Builder.obtain(rawText, 0, rawText.length, strokePaint, textW)
                .setAlignment(alignment)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setIncludePad(true)
                .build()

            canvas.save()
            canvas.translate(padX.toFloat(), padY.toFloat())
            strokeLayout.draw(canvas)
            canvas.restore()
        } else if (item.bgStyle == 0 && item.labelAlpha <= 0.01f && item.shadowAlpha <= 0.01f) {
            val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xEE000000.toInt()
                textSize = textPaint.textSize
                this.typeface = textPaint.typeface
                letterSpacing = textPaint.letterSpacing
                style = Paint.Style.STROKE
                strokeWidth = 10f * effScale
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(8f * effScale, 2f * effScale, 4f * effScale, 0x88000000.toInt())
            }
            val strokeLayout = StaticLayout.Builder.obtain(rawText, 0, rawText.length, strokePaint, textW)
                .setAlignment(alignment)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setIncludePad(true)
                .build()

            canvas.save()
            canvas.translate(padX.toFloat(), padY.toFloat())
            strokeLayout.draw(canvas)
            canvas.restore()
        }

        // 3. Drop Shadow with dynamic radius up to 22f and (8f, 8f) offset
        if (item.shadowAlpha > 0.01f) {
            val shadowA = (item.shadowAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
            val shadowColor = (item.shadowColorArgb and 0x00FFFFFF) or (shadowA shl 24)
            val shadowRadius = (item.shadowAlpha.coerceIn(0f, 1f) * 22f).coerceAtLeast(1f) * effScale
            textPaint.setShadowLayer(shadowRadius, 8f * effScale, 8f * effScale, shadowColor)
        }

        // 4. Draw primary text fill
        canvas.save()
        canvas.translate(padX.toFloat(), padY.toFloat())
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    private fun bakeSticker(item: OverlayItem): Bitmap? {
        val glyph = item.text
        if (glyph.isEmpty()) return null

        val effScale = bakeScale.coerceIn(1.0f, 2.5f)
        val size = (256 * effScale).roundToInt().coerceIn(256, 1024)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 170f * effScale
            textAlign = Paint.Align.CENTER
            setShadowLayer(14f * effScale, 0f, 6f * effScale, 0x55000000.toInt())
        }

        val fontMetrics = paint.fontMetrics
        val center = size / 2f
        val baseline = center - (fontMetrics.descent + fontMetrics.ascent) / 2f
        canvas.drawText(glyph, center, baseline, paint)
        return bitmap
    }

    private fun bakeImage(item: OverlayItem): Bitmap? {
        val path = item.mediaPath
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null

        val boundsOpts = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(path, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        // Cap max dimension to 1024px using power-of-two inSampleSize
        var sampleSize = 1
        val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        while (maxDim / (sampleSize * 2) >= 1024) {
            sampleSize *= 2
        }

        val decodeOpts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = android.graphics.BitmapFactory.decodeFile(path, decodeOpts) ?: return null

        // Respect EXIF orientation
        val orientation = try {
            val exif = ExifInterface(path)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }

        val oriented = if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            if (rotated != rawBitmap) {
                rawBitmap.recycle()
            }
            rotated
        } else {
            rawBitmap
        }

        // Clamp final dimension <= 1024px if still slightly over
        val curMax = maxOf(oriented.width, oriented.height)
        if (curMax > 1024) {
            val scale = 1024f / curMax.toFloat()
            val finalW = (oriented.width * scale).roundToInt().coerceAtLeast(1)
            val finalH = (oriented.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(oriented, finalW, finalH, true)
            if (scaled != oriented) {
                oriented.recycle()
            }
            return scaled
        }
        return oriented
    }

    /**
     * Computes target dimensions for PIP video frame extraction capped at max 640px.
     */
    private fun computePipTargetDimensions(item: OverlayItem): Pair<Int, Int> {
        val inW = if (item.intrinsicWidth > 0) item.intrinsicWidth else 640
        val inH = if (item.intrinsicHeight > 0) item.intrinsicHeight else 360
        val maxDim = 640
        val targetW: Int
        val targetH: Int
        if (inW >= inH) {
            targetW = maxDim
            targetH = ((maxDim.toLong() * inH.toLong()) / inW.toLong()).toInt().coerceAtLeast(16)
        } else {
            targetH = maxDim
            targetW = ((maxDim.toLong() * inW.toLong()) / inH.toLong()).toInt().coerceAtLeast(16)
        }
        return Pair((targetW / 2) * 2, (targetH / 2) * 2)
    }

    /**
     * Extracts a scaled video frame from the bounded retriever pool.
     * Prefers 640px all-intra proxy when available, falling back to original media.
     */
    private fun extractScaledVideoFrame(
        path: String,
        targetSourceUs: Long,
        targetW: Int,
        targetH: Int
    ): Bitmap? {
        if (path.isEmpty()) return null
        val store = proxyStore ?: defaultProxyStore
        val proxyPath = store?.getReadyProxyPath(path)
        val effectivePath = proxyPath ?: path

        fun decodeFrom(targetPath: String): Bitmap? {
            val retriever = retrieverPool.getRetriever(targetPath) ?: return null
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        targetSourceUs.coerceAtLeast(0L),
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        targetW,
                        targetH
                    ) ?: retriever.getFrameAtTime(
                        targetSourceUs.coerceAtLeast(0L),
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                } else {
                    retriever.getFrameAtTime(
                        targetSourceUs.coerceAtLeast(0L),
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                }
            } catch (_: Throwable) {
                null
            }
        }

        var bmp = decodeFrom(effectivePath)
        if (bmp == null && effectivePath != path) {
            // Seamless fallback to original source media if proxy failed to decode
            bmp = decodeFrom(path)
        }
        return bmp
    }

    /**
     * Dynamic frame-accurate video overlay texture retrieval.
     * - Quantizes to 30fps buckets (33_333 us) so texture only updates when frame advances.
     * - Offline Export: Synchronously extracts frame at targetSourceUs and updates GL texture.
     * - Live Preview: Non-blocking background decode on pipDecodeExecutor with zero GL thread allocations (Rule R10).
     */
    fun getOrUpdateVideoTexture(
        item: OverlayItem,
        timelineUs: Long,
        isSynchronous: Boolean = false
    ): CachedOverlayTex? {
        if (item.mediaPath.isEmpty()) return null
        val localUs = (timelineUs - item.startUs).coerceAtLeast(0L)
        val speedVal = if (item.speed > 0f) item.speed.toDouble() else 1.0
        val targetSourceUs = item.sourceInUs + (localUs.toDouble() * speedVal).toLong()
        val effectiveFps = pipUpdateFps.coerceIn(10, 30)
        val bucketStepUs = 1_000_000L / effectiveFps.toLong()
        val frameBucket = targetSourceUs / bucketStepUs

        if (isSynchronous) {
            // Offline Export: frame-accurate synchronous decode
            val state = videoStates.getOrPut(item.id) { VideoPipState(item.id) }
            if (state.currentBucket != frameBucket || state.texEntry.texId == 0) {
                val (targetW, targetH) = computePipTargetDimensions(item)
                val bmp = extractScaledVideoFrame(item.mediaPath, targetSourceUs, targetW, targetH)
                if (bmp != null && !bmp.isRecycled) {
                    val w = bmp.width
                    val h = bmp.height
                    val targetTexId = if (state.texEntry.texId > 0) {
                        state.texEntry.texId
                    } else {
                        GLES20.glGenTextures(1, tempTexIds, 0)
                        tempTexIds[0]
                    }
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targetTexId)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                    bmp.recycle()

                    state.texEntry.texId = targetTexId
                    state.texEntry.width = w
                    state.texEntry.height = h
                    state.currentBucket = frameBucket
                }
            }
            if (state.texEntry.texId > 0) {
                state.texEntry.lastUsedNs = System.nanoTime()
                return state.texEntry
            }
            return null
        }

        // Live Preview: Non-blocking asynchronous decoding
        var state = videoStates[item.id]
        if (state == null) {
            val newState = VideoPipState(item.id)
            newState.decodeTask = Runnable {
                val (dstW, dstH) = computePipTargetDimensions(item)
                val decoded = extractScaledVideoFrame(item.mediaPath, newState.targetSourceUs, dstW, dstH)
                if (decoded != null && !decoded.isRecycled) {
                    synchronized(newState.lock) {
                        val old = newState.backBitmap
                        newState.backBitmap = decoded
                        newState.hasDirtyBitmap = true
                        if (old != null && old != decoded && !old.isRecycled) {
                            old.recycle()
                        }
                    }
                    onFrameReady?.invoke()
                }
                newState.isDecoding = false
            }
            videoStates[item.id] = newState
            state = newState
        }

        // Upload ready decoded frame on GL thread
        if (state.hasDirtyBitmap) {
            var bmpToUpload: Bitmap? = null
            synchronized(state.lock) {
                bmpToUpload = state.backBitmap
                state.backBitmap = null
                state.hasDirtyBitmap = false
            }
            if (bmpToUpload != null && !bmpToUpload!!.isRecycled) {
                val w = bmpToUpload!!.width
                val h = bmpToUpload!!.height
                val targetTexId = if (state.texEntry.texId > 0) {
                    state.texEntry.texId
                } else {
                    GLES20.glGenTextures(1, tempTexIds, 0)
                    tempTexIds[0]
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targetTexId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmpToUpload, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                bmpToUpload!!.recycle()

                state.texEntry.texId = targetTexId
                state.texEntry.width = w
                state.texEntry.height = h
                state.currentBucket = state.targetBucket
            }
        }

        // Trigger background decoding if timestamp bucket advanced
        if (state.currentBucket != frameBucket && !state.isDecoding) {
            state.targetBucket = frameBucket
            state.targetSourceUs = targetSourceUs
            state.isDecoding = true
            try {
                pipDecodeExecutor.execute(state.decodeTask)
            } catch (_: Throwable) {
                state.isDecoding = false
            }
        }

        if (state.texEntry.texId > 0) {
            state.texEntry.lastUsedNs = System.nanoTime()
            return state.texEntry
        }
        return null
    }

    fun remove(itemId: String) {
        val cached = cache.remove(itemId)
        if (cached != null && cached.texId > 0) {
            GLES20.glDeleteTextures(1, intArrayOf(cached.texId), 0)
            cached.texId = 0
        }
        val vState = videoStates.remove(itemId)
        if (vState != null) {
            if (vState.texEntry.texId > 0) {
                GLES20.glDeleteTextures(1, intArrayOf(vState.texEntry.texId), 0)
                vState.texEntry.texId = 0
            }
            synchronized(vState.lock) {
                vState.backBitmap?.recycle()
                vState.backBitmap = null
            }
        }
    }

    fun clear() {
        for (entry in cache.values) {
            if (entry.texId > 0) {
                GLES20.glDeleteTextures(1, intArrayOf(entry.texId), 0)
                entry.texId = 0
            }
        }
        cache.clear()

        for (vState in videoStates.values) {
            if (vState.texEntry.texId > 0) {
                GLES20.glDeleteTextures(1, intArrayOf(vState.texEntry.texId), 0)
                vState.texEntry.texId = 0
            }
            synchronized(vState.lock) {
                vState.backBitmap?.recycle()
                vState.backBitmap = null
            }
        }
        videoStates.clear()
    }

    fun release() {
        clear()
        retrieverPool.release()
        try {
            pipDecodeExecutor.shutdownNow()
        } catch (_: Throwable) {}
    }

    companion object {
        @Volatile
        var defaultProxyStore: ProxyStore? = null

        @Volatile
        var defaultMaxByteBudget: Long = 128L * 1024L * 1024L

        @Volatile
        var defaultPipUpdateFps: Int = 30

        fun defaultPipDecodeThreads(): Int =
            if (Runtime.getRuntime().availableProcessors() >= 4) 2 else 1

        fun pruneDefaultCache() {
            PreviewRenderer.activeInstance?.overlayTextureCache?.pruneToBudget()
        }
    }
}

private class VideoPipState(
    val itemId: String,
    val texEntry: CachedOverlayTex = CachedOverlayTex(),
    var currentBucket: Long = -1L,
    @Volatile var targetBucket: Long = -1L,
    @Volatile var targetSourceUs: Long = 0L,
    @Volatile var isDecoding: Boolean = false,
    @Volatile var hasDirtyBitmap: Boolean = false,
    var backBitmap: Bitmap? = null,
    val lock: Any = Any()
) {
    var decodeTask: Runnable? = null
}

private class RetrieverPool(private val maxEntries: Int = 4) {
    private val entries = ArrayList<RetrieverEntry>(maxEntries)

    class RetrieverEntry(
        var path: String = "",
        val retriever: MediaMetadataRetriever = MediaMetadataRetriever(),
        var lastUsedTime: Long = 0L
    )

    @Synchronized
    fun getRetriever(path: String): MediaMetadataRetriever? {
        if (path.isEmpty()) return null
        val now = System.currentTimeMillis()
        val existing = entries.firstOrNull { it.path == path }
        if (existing != null) {
            existing.lastUsedTime = now
            return existing.retriever
        }
        if (entries.size < maxEntries) {
            val entry = RetrieverEntry(path, MediaMetadataRetriever(), now)
            try {
                entry.retriever.setDataSource(path)
                entries.add(entry)
                return entry.retriever
            } catch (_: Throwable) {
                try { entry.retriever.release() } catch (_: Throwable) {}
                return null
            }
        }
        val lru = entries.minByOrNull { it.lastUsedTime } ?: entries[0]
        try {
            lru.retriever.setDataSource(path)
            lru.path = path
            lru.lastUsedTime = now
            return lru.retriever
        } catch (_: Throwable) {
            return null
        }
    }

    @Synchronized
    fun release() {
        for (e in entries) {
            try {
                e.retriever.release()
            } catch (_: Throwable) {}
        }
        entries.clear()
    }
}

