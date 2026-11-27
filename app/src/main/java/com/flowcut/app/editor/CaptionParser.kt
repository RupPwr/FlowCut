package com.flowcut.app.editor

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Pure-Kotlin timed caption cue (timestamps in timeline microseconds).
 */
data class CaptionCue(
    val text: String,
    val startUs: Long,
    val endUs: Long
) {
    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(0L)
}

/**
 * Pure-Kotlin subtitle & script caption parser.
 * Supports:
 * - Standard SubRip (.srt) and WebVTT (.vtt) timestamped captions.
 * - Multi-line scripts or pasted paragraphs with automatic sentence/word-chunk splitting
 *   and proportional word-weighted timing across a timeline span.
 */
object CaptionParser {

    const val DEFAULT_MIN_CUE_DURATION_US: Long = 200_000L
    const val DEFAULT_MAX_WORDS_PER_CUE: Int = 8

    private val TIMING_LINE_REGEX = Regex(
        """^\s*(\d{1,2}:)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{2})[,.](\d{1,3}).*$"""
    )

    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val SENTENCE_SPLIT_REGEX = Regex("""(?<=[.!?।])\s+""")

    /**
     * Returns true if the text looks like an SRT or WebVTT file containing timestamp arrow lines.
     */
    fun containsSrtTimestamps(input: String): Boolean {
        if (input.isBlank()) return false
        return input.lineSequence().any { TIMING_LINE_REGEX.matches(it) }
    }

    /**
     * Parses SubRip (.srt) or WebVTT (.vtt) text into sorted [CaptionCue]s.
     * Applies [offsetUs] to every cue timestamp and guarantees each cue lasts at least [minDurationUs].
     */
    fun parseSrt(
        input: String,
        offsetUs: Long = 0L,
        minDurationUs: Long = DEFAULT_MIN_CUE_DURATION_US
    ): List<CaptionCue> {
        if (input.isBlank()) return emptyList()
        val lines = input.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<CaptionCue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val match = TIMING_LINE_REGEX.matchEntire(line)
            if (match == null) {
                i++
                continue
            }

            val rawStartUs = parseTimestampUs(
                hoursGroup = match.groupValues[1],
                minutesGroup = match.groupValues[2],
                secondsGroup = match.groupValues[3],
                millisGroup = match.groupValues[4]
            )
            val rawEndUs = parseTimestampUs(
                hoursGroup = match.groupValues[5],
                minutesGroup = match.groupValues[6],
                secondsGroup = match.groupValues[7],
                millisGroup = match.groupValues[8]
            )

            i++
            val textLines = ArrayList<String>()
            while (i < lines.size) {
                val raw = lines[i]
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) break
                if (TIMING_LINE_REGEX.matches(trimmed)) break
                val cleaned = cleanCaptionLine(trimmed)
                if (cleaned.isNotEmpty()) {
                    textLines.add(cleaned)
                }
                i++
            }

            if (textLines.isNotEmpty()) {
                val startUs = (rawStartUs + offsetUs).coerceAtLeast(0L)
                val endUs = max(rawEndUs + offsetUs, startUs + minDurationUs)
                cues.add(
                    CaptionCue(
                        text = textLines.joinToString("\n"),
                        startUs = startUs,
                        endUs = endUs
                    )
                )
            }
        }
        cues.sortBy { it.startUs }
        return cues
    }

    /**
     * Splits raw script/paragraph text into clean caption lines (<= [maxWordsPerCue] words per line
     * for single long paragraphs, while preserving intentional line breaks).
     */
    fun splitScriptIntoLines(
        script: String,
        maxWordsPerCue: Int = DEFAULT_MAX_WORDS_PER_CUE
    ): List<String> {
        if (script.isBlank()) return emptyList()
        val limit = maxWordsPerCue.coerceIn(3, 20)
        val result = ArrayList<String>()

        val rawLines = script.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (line in rawLines) {
            val sentences = line.split(SENTENCE_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
            for (sentence in sentences) {
                val words = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.size <= limit) {
                    result.add(words.joinToString(" "))
                } else {
                    var idx = 0
                    while (idx < words.size) {
                        val remaining = words.size - idx
                        val take = if (remaining > limit && remaining <= limit + 2) {
                            // Avoid dangling 1-2 word orphan cue at end of sentence
                            remaining / 2
                        } else {
                            minOf(limit, remaining)
                        }
                        result.add(words.subList(idx, idx + take).joinToString(" "))
                        idx += take
                    }
                }
            }
        }
        return result
    }

    /**
     * Converts either SRT/VTT content or plain script text into timed [CaptionCue]s.
     * - If SRT/VTT timestamps are present, preserves exact timestamps shifted by [startUs].
     * - Otherwise distributes captions across `[startUs, endUs]` proportionally to phrase length
     *   (so short lines move faster and longer lines stay readable).
     */
    fun parseInputToTimedCues(
        input: String,
        startUs: Long,
        endUs: Long,
        minDurationUs: Long = DEFAULT_MIN_CUE_DURATION_US
    ): List<CaptionCue> {
        if (input.isBlank()) return emptyList()
        if (containsSrtTimestamps(input)) {
            return parseSrt(input, offsetUs = startUs.coerceAtLeast(0L), minDurationUs = minDurationUs)
        }

        val lines = input.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map { cleanCaptionLine(it) }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        return distributePhrasesToTimedCues(lines, startUs, endUs, minDurationUs)
    }

    /**
     * Distributes pre-split [phrases] across `[startUs, endUs]` with duration proportional
     * to each phrase's readable weight (character count + floor), guaranteeing every cue
     * meets [minDurationUs].
     */
    fun distributePhrasesToTimedCues(
        phrases: List<String>,
        startUs: Long,
        endUs: Long,
        minDurationUs: Long = DEFAULT_MIN_CUE_DURATION_US
    ): List<CaptionCue> {
        val valid = phrases.map { cleanCaptionLine(it) }.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return emptyList()

        val effStart = startUs.coerceAtLeast(0L)
        val minTotal = valid.size.toLong() * minDurationUs
        val effEnd = max(endUs, effStart + max(1_000_000L, minTotal))
        val totalSpanUs = effEnd - effStart

        // Weight each phrase by readable characters with a minimum floor so very short words remain legible.
        val weights = DoubleArray(valid.size) { idx ->
            valid[idx].length.coerceAtLeast(4).toDouble()
        }
        val weightSum = weights.sum().coerceAtLeast(1.0)
        val distributableUs = (totalSpanUs - minTotal).coerceAtLeast(0L)

        val cues = ArrayList<CaptionCue>(valid.size)
        var cursorUs = effStart
        for (i in valid.indices) {
            val cueStart = cursorUs
            val cueEnd = if (i == valid.lastIndex) {
                effEnd
            } else {
                val extra = ((distributableUs.toDouble() * weights[i]) / weightSum).roundToLong()
                (cueStart + minDurationUs + extra).coerceAtMost(effEnd - (valid.size - 1 - i) * minDurationUs)
            }
            if (cueEnd - cueStart < minDurationUs) break
            cues.add(CaptionCue(text = valid[i], startUs = cueStart, endUs = cueEnd))
            cursorUs = cueEnd
        }
        return cues
    }

    private fun parseTimestampUs(
        hoursGroup: String,
        minutesGroup: String,
        secondsGroup: String,
        millisGroup: String
    ): Long {
        val hours = hoursGroup.removeSuffix(":").toLongOrNull() ?: 0L
        val minutes = minutesGroup.toLongOrNull() ?: 0L
        val seconds = secondsGroup.toLongOrNull() ?: 0L
        val normalizedMs = when (millisGroup.length) {
            1 -> (millisGroup.toLongOrNull() ?: 0L) * 100L
            2 -> (millisGroup.toLongOrNull() ?: 0L) * 10L
            else -> millisGroup.take(3).toLongOrNull() ?: 0L
        }
        val totalMs = ((hours * 3600L + minutes * 60L + seconds) * 1000L) + normalizedMs
        return totalMs * 1000L
    }

    private fun cleanCaptionLine(raw: String): String {
        return HTML_TAG_REGEX.replace(raw, "").trim()
    }
}
