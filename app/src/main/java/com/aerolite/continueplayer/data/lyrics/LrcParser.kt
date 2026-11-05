package com.aerolite.continueplayer.data.lyrics

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

object LrcParser {
    // Matches metadata like [ti:Title], [ar:Artist], [al:Album], [offset:1000]
    private val METADATA_PATTERN = Pattern.compile("\\[([a-zA-Z]+):(.*)\\]")

    // Matches timestamps like [mm:ss.xx] or [mm:ss.xxx] or [hh:mm:ss.xxx]
    private val TIME_PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{2,3}))?\\]")

    // Matches SRT timestamp lines: 00:01:20,000 --> 00:01:24,400
    private val SRT_TIME_PATTERN = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})")

    fun parse(inputStream: InputStream): LyricData {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        return parseReader(reader)
    }

    fun parseText(text: String): LyricData {
        val reader = BufferedReader(text.reader())
        return parseReader(reader)
    }

    private fun parseReader(reader: BufferedReader): LyricData {
        val lines = reader.readLines()
        
        // Detect if it's an SRT file
        if (isSrtFormat(lines)) {
            return parseSrtLines(lines)
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offsetMs = 0L
        val rawEntries = mutableListOf<LyricEntry>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // 1. Check metadata
            val metaMatcher = METADATA_PATTERN.matcher(trimmed)
            if (metaMatcher.matches() && !TIME_PATTERN.matcher(trimmed).find()) {
                val key = metaMatcher.group(1)?.lowercase()
                val value = metaMatcher.group(2)?.trim() ?: ""
                when (key) {
                    "ti" -> title = value
                    "ar" -> artist = value
                    "al" -> album = value
                    "offset" -> offsetMs = value.toLongOrNull() ?: 0L
                }
                continue
            }

            // 2. Extract timestamps and lyric text
            val timeMatcher = TIME_PATTERN.matcher(trimmed)
            val times = mutableListOf<Long>()
            var lastMatchEnd = 0

            while (timeMatcher.find()) {
                val minutesOrHours = timeMatcher.group(1)?.toLongOrNull() ?: 0L
                val seconds = timeMatcher.group(2)?.toLongOrNull() ?: 0L
                val millisString = timeMatcher.group(3) ?: "0"

                var millis = millisString.toLongOrNull() ?: 0L
                if (millisString.length == 2) {
                    millis *= 10
                }

                val timeMs = minutesOrHours * 60 * 1000 + seconds * 1000 + millis
                times.add(timeMs)
                lastMatchEnd = timeMatcher.end()
            }

            if (times.isNotEmpty()) {
                val lyricText = trimmed.substring(lastMatchEnd).trim()
                if (lyricText.isNotEmpty()) {
                    for (t in times) {
                        rawEntries.add(LyricEntry(t, lyricText))
                    }
                }
            }
        }

        val sortedEntries = rawEntries.sortedBy { it.timeMs }
        return LyricData(
            title = title,
            artist = artist,
            album = album,
            offsetMs = offsetMs,
            entries = sortedEntries
        )
    }

    private fun isSrtFormat(lines: List<String>): Boolean {
        for (line in lines.take(10)) {
            if (SRT_TIME_PATTERN.matcher(line.trim()).find()) {
                return true
            }
        }
        return false
    }

    private fun parseSrtLines(lines: List<String>): LyricData {
        val entries = mutableListOf<LyricEntry>()
        var currentStartTime: Long? = null
        val currentText = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (currentStartTime != null && currentText.isNotEmpty()) {
                    entries.add(LyricEntry(currentStartTime, currentText.toString().trim()))
                    currentStartTime = null
                    currentText.clear()
                }
                continue
            }

            val matcher = SRT_TIME_PATTERN.matcher(trimmed)
            if (matcher.find()) {
                if (currentStartTime != null && currentText.isNotEmpty()) {
                    entries.add(LyricEntry(currentStartTime, currentText.toString().trim()))
                    currentText.clear()
                }

                val hours = matcher.group(1)?.toLongOrNull() ?: 0L
                val minutes = matcher.group(2)?.toLongOrNull() ?: 0L
                val seconds = matcher.group(3)?.toLongOrNull() ?: 0L
                val millis = matcher.group(4)?.toLongOrNull() ?: 0L

                currentStartTime = hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
            } else if (currentStartTime != null && !trimmed.all { it.isDigit() }) {
                if (currentText.isNotEmpty()) currentText.append("\n")
                currentText.append(trimmed)
            }
        }

        if (currentStartTime != null && currentText.isNotEmpty()) {
            entries.add(LyricEntry(currentStartTime, currentText.toString().trim()))
        }

        return LyricData(entries = entries.sortedBy { it.timeMs })
    }
}
