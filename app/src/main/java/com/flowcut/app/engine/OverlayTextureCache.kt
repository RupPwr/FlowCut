package com.flowcut.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
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
    var height: Int = 0
)

/**
 * One-time GPU Texture Baker & Cache for multi-layer overlays (TEXT, STICKER, IMAGE).
 * Instantiated per GL context (PreviewRenderer and ExportEngine) and freed on release().
 */
class OverlayTextureCache {

    private val cache = HashMap<String, CachedOverlayTex>()
    private val tempTexIds = IntArray(1)

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
        return h
    }

    /**
     * Fast retrieval of baked texture.
     * Returns cached (texId, width, height) in < 0.001 ms with ZERO heap allocations
     * during playback (Rule R10).
     */
    fun getOrBakeTexture(item: OverlayItem): CachedOverlayTex? {
        val targetHash = computeContentHash(item)
        val existing = cache[item.id]
        if (existing != null && existing.contentHash == targetHash && existing.texId > 0) {
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
        return entry
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

        val textA = (item.textAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val fillTextColor = (item.textColorArgb and 0x00FFFFFF) or (textA shl 24)

        val typeface = resolveTypeface(item.fontName, item.customFontPath, item.isBold, item.isItalic)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillTextColor
            textSize = 68f
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

        // Measure text using full desired width so wide character spacing never clips
        val desiredWidth = ceil(StaticLayout.getDesiredWidth(rawText, textPaint)).toInt().coerceAtLeast(1)
        val textW = desiredWidth.coerceIn(1, 1920)

        val layout = StaticLayout.Builder.obtain(rawText, 0, rawText.length, textPaint, textW)
            .setAlignment(alignment)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setIncludePad(true)
            .build()

        val textH = layout.height.coerceAtLeast(1)

        val hasLabel = item.labelAlpha > 0.01f || item.bgStyle in 1..2
        val strokeOffset = (item.borderAlpha.coerceIn(0f, 1f) * 20f).toInt() + 16
        val padX = if (hasLabel) 64 else (40 + strokeOffset)
        val padY = if (hasLabel) 44 else (28 + strokeOffset)

        val bmpW = (textW + padX * 2).coerceAtMost(2048)
        val bmpH = (textH + padY * 2).coerceAtMost(2048)
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
            val rect = RectF(12f, 10f, bmpW - 12f, bmpH - 10f)
            val cornerRadius = 32f
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        } else if (item.bgStyle == 1) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCC000000.toInt()
                style = Paint.Style.FILL
            }
            val rect = RectF(12f, 10f, bmpW - 12f, bmpH - 10f)
            val cornerRadius = 32f
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        } else if (item.bgStyle == 2) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
            }
            val rect = RectF(12f, 10f, bmpW - 12f, bmpH - 10f)
            val cornerRadius = 32f
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        }

        // 2. Border / Stroke Outline with dynamic stroke width up to 20px
        if (item.borderAlpha > 0.01f) {
            val borderA = (item.borderAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
            val borderColor = (item.borderColorArgb and 0x00FFFFFF) or (borderA shl 24)
            val dynamicStrokeWidth = (item.borderAlpha.coerceIn(0f, 1f) * 20f).coerceAtLeast(1.5f)
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
                strokeWidth = 10f
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(8f, 2f, 4f, 0x88000000.toInt())
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
            val shadowRadius = (item.shadowAlpha.coerceIn(0f, 1f) * 22f).coerceAtLeast(1f)
            textPaint.setShadowLayer(shadowRadius, 8f, 8f, shadowColor)
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

        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 170f
            textAlign = Paint.Align.CENTER
            setShadowLayer(14f, 0f, 6f, 0x55000000.toInt())
        }

        val fontMetrics = paint.fontMetrics
        val baseline = 128f - (fontMetrics.descent + fontMetrics.ascent) / 2f
        canvas.drawText(glyph, 128f, baseline, paint)
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

    fun remove(itemId: String) {
        val cached = cache.remove(itemId) ?: return
        if (cached.texId > 0) {
            GLES20.glDeleteTextures(1, intArrayOf(cached.texId), 0)
            cached.texId = 0
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
    }

    fun release() {
        clear()
    }
}

