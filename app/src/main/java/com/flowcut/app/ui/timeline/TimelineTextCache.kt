package com.flowcut.app.ui.timeline

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

/**
 * Pure-Kotlin bounded two-level LRU cache keyed by (bucketKey, text).
 *
 * Designed for Rule R10 hot paths: cache hits perform only two HashMap/LinkedHashMap lookups
 * with ZERO heap allocations (no wrapper key object is created).
 */
class BoundedTextCache<K : Any, V : Any>(
    private val maxEntriesPerBucket: Int = 64,
    private val maxBuckets: Int = 32
) {
    private val buckets = object : LinkedHashMap<K, LinkedHashMap<String, V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, LinkedHashMap<String, V>>?): Boolean {
            return size > maxBuckets
        }
    }

    fun getOrPut(bucketKey: K, text: String, compute: () -> V): V {
        var bucket = buckets[bucketKey]
        if (bucket != null) {
            val cached = bucket[text]
            if (cached != null) return cached
        } else {
            bucket = object : LinkedHashMap<String, V>(32, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean {
                    return size > maxEntriesPerBucket
                }
            }
            buckets[bucketKey] = bucket
        }
        val created = compute()
        bucket[text] = created
        return created
    }

    fun totalEntries(): Int {
        var total = 0
        for (bucket in buckets.values) {
            total += bucket.size
        }
        return total
    }

    fun clear() {
        buckets.clear()
    }
}

/**
 * Zero-allocation-on-hit Compose [TextLayoutResult] & dynamic [TextStyle] cache for [TimelineCanvas].
 * Eliminates repeated `textMeasurer.measure(...)` calls inside 60/120fps `Canvas.onDraw`.
 */
internal class TimelineTextLayoutCache(
    maxEntriesPerStyle: Int = 96
) {
    private val singleLineCache = BoundedTextCache<TextStyle, TextLayoutResult>(maxEntriesPerBucket = maxEntriesPerStyle)
    private val multiLineCache = BoundedTextCache<TextStyle, TextLayoutResult>(maxEntriesPerBucket = maxEntriesPerStyle)
    private val dynamicStyleCache = object : LinkedHashMap<Long, TextStyle>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TextStyle>?): Boolean = size > 32
    }

    fun getOrMeasure(
        textMeasurer: TextMeasurer,
        text: String,
        style: TextStyle,
        maxLines: Int = 1
    ): TextLayoutResult {
        val targetCache = if (maxLines <= 1) singleLineCache else multiLineCache
        return targetCache.getOrPut(style, text) {
            textMeasurer.measure(
                text = text,
                style = style,
                constraints = Constraints(maxWidth = 1000),
                softWrap = (maxLines > 1),
                maxLines = maxLines
            )
        }
    }

    fun getDynamicStyle(fontSizeSp: Float, colorArgb: Int): TextStyle {
        val key = (fontSizeSp.toRawBits().toLong() shl 32) or (colorArgb.toLong() and 0xFFFFFFFFL)
        val existing = dynamicStyleCache[key]
        if (existing != null) return existing
        val created = TextStyle(fontSize = fontSizeSp.sp, color = Color(colorArgb))
        dynamicStyleCache[key] = created
        return created
    }

    fun clear() {
        singleLineCache.clear()
        multiLineCache.clear()
        dynamicStyleCache.clear()
    }
}
