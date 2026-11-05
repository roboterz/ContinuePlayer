package com.aerolite.continueplayer.data.lyrics

import org.junit.Assert.*
import org.junit.Test

class LrcParserTest {

    @Test
    fun testParseStandardLrc() {
        val lrcContent = """
            [ti:Test Title]
            [ar:Test Artist]
            [offset:500]
            [00:12.50]First line of lyrics
            [00:18.00]Second line of lyrics
            [01:05.200]Third line of lyrics
        """.trimIndent()

        val data = LrcParser.parseText(lrcContent)

        assertEquals("Test Title", data.title)
        assertEquals("Test Artist", data.artist)
        assertEquals(500L, data.offsetMs)
        assertEquals(3, data.entries.size)

        assertEquals(12500L, data.entries[0].timeMs)
        assertEquals("First line of lyrics", data.entries[0].text)

        assertEquals(18000L, data.entries[1].timeMs)
        assertEquals("Second line of lyrics", data.entries[1].text)

        assertEquals(65200L, data.entries[2].timeMs)
        assertEquals("Third line of lyrics", data.entries[2].text)
    }

    @Test
    fun testParseMultipleTimestampsLine() {
        val lrcContent = """
            [00:10.00][00:20.00]Repeated chorus line
        """.trimIndent()

        val data = LrcParser.parseText(lrcContent)

        assertEquals(2, data.entries.size)
        assertEquals(10000L, data.entries[0].timeMs)
        assertEquals("Repeated chorus line", data.entries[0].text)
        assertEquals(20000L, data.entries[1].timeMs)
        assertEquals("Repeated chorus line", data.entries[1].text)
    }

    @Test
    fun testParseSrtFormat() {
        val srtContent = """
            1
            00:00:05,000 --> 00:00:10,000
            Hello world subtitle

            2
            00:00:12,500 --> 00:00:15,000
            Second subtitle line
        """.trimIndent()

        val data = LrcParser.parseText(srtContent)

        assertEquals(2, data.entries.size)
        assertEquals(5000L, data.entries[0].timeMs)
        assertEquals("Hello world subtitle", data.entries[0].text)
        assertEquals(12500L, data.entries[1].timeMs)
        assertEquals("Second subtitle line", data.entries[1].text)
    }

    @Test
    fun testFindCurrentIndex() {
        val entries = listOf(
            LyricEntry(10000L, "Line 1"),
            LyricEntry(20000L, "Line 2"),
            LyricEntry(30000L, "Line 3")
        )
        val data = LyricData(entries = entries)

        assertEquals(-1, data.findCurrentIndex(5000L))
        assertEquals(0, data.findCurrentIndex(10000L))
        assertEquals(0, data.findCurrentIndex(15000L))
        assertEquals(1, data.findCurrentIndex(20000L))
        assertEquals(2, data.findCurrentIndex(35000L))
    }
}
