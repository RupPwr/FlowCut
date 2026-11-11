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

    private fun bakeText(item: OverlayItem): Bitmap? {
        val rawText = item.text
        if (rawText.isEmpty()) return null

        val fillTextColor = if (item.bgStyle == 2) {
            0xFF111111.toInt()
        } else {
            item.textColorArgb
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillTextColor
            textSize = 68f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Measure text with a 900px line limit to wrap naturally
        val measureLayout = StaticLayout.Builder.obtain(rawText, 0, rawText.length, textPaint, 900)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(4f, 1.15f)
            .setIncludePad(true)
            .build()

        var maxLineWidth = 0f
        for (i in 0 until measureLayout.lineCount) {
            maxLineWidth = maxOf(maxLineWidth, measureLayout.getLineWidth(i))
        }
        val textW = ceil(maxLineWidth).toInt().coerceAtLeast(1)

        val layout = StaticLayout.Builder.obtain(rawText, 0, rawText.length, textPaint, textW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(4f, 1.15f)
            .setIncludePad(true)
            .build()

        val textH = layout.height.coerceAtLeast(1)

        val padX = if (item.bgStyle == 0) 28 else 48
        val padY = if (item.bgStyle == 0) 20 else 32

        val bmpW = (textW + padX * 2).coerceAtMost(2048)
        val bmpH = (textH + padY * 2).coerceAtMost(2048)
        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background Style Rendering
        when (item.bgStyle) {
            1 -> {
                // Rounded translucent dark pill background 0xCC000000
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xCC000000.toInt()
                    style = Paint.Style.FILL
                }
                val rect = RectF(10f, 8f, bmpW - 10f, bmpH - 8f)
                val cornerRadius = 24f
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            }
            2 -> {
                // Rounded solid white pill background with contrasting text
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    style = Paint.Style.FILL
                }
                val rect = RectF(10f, 8f, bmpW - 10f, bmpH - 8f)
                val cornerRadius = 24f
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            }
            else -> {
                // Style 0: Crisp dark drop-shadow + stroke outline
                val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xEE000000.toInt()
                    textSize = textPaint.textSize
                    typeface = textPaint.typeface
                    style = Paint.Style.STROKE
                    strokeWidth = 10f
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                    setShadowLayer(8f, 2f, 4f, 0x88000000.toInt())
                }
                val strokeLayout = StaticLayout.Builder.obtain(rawText, 0, rawText.length, strokePaint, textW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(4f, 1.15f)
                    .setIncludePad(true)
                    .build()

                canvas.save()
                canvas.translate(padX.toFloat(), padY.toFloat())
                strokeLayout.draw(canvas)
                canvas.restore()
            }
        }

        // Draw primary text fill
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

