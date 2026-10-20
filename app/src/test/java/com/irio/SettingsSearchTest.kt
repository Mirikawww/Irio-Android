package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test fun enterMatchesTheSendSettingAndHighlightsThatWord() {
        val hits = searchSettings("Enter") { it }
        val hit = hits.single { it.id == "appearance.enter" }
        assertEquals("按 Enter 发送", hit.title)
        assertEquals("外观与偏好", hit.page)
        assertEquals(listOf(2 until 7), hit.ranges)
        assertEquals(false, hit.fuzzy)
    }

    @Test fun singleLetterHighlightsEveryOccurrence() {
        val ranges = settingsSearchRanges("按 Enter 发送", "t")
        assertEquals(listOf(4 until 5), ranges)
        val repeated = settingsSearchRanges("Test text", "t")
        assertEquals(listOf(0 until 1, 3 until 4, 5 until 6, 8 until 9), repeated)
    }

    @Test fun fuzzyMatchHighlightsCharactersInOrder() {
        val ranges = settingsSearchRanges("按 Enter 发送", "按送")
        assertEquals(listOf(0..0, 9..9), ranges)
        assertNull(settingsSearchRanges("按 Enter 发送", "xyz"))
        assertNull(settingsSearchRanges("按 Enter 发送", "   "))
    }

    @Test fun blankQueryReturnsNothing() {
        assertTrue(searchSettings("   ") { it }.isEmpty())
    }

    @Test fun pinyinFindsGlassAndHighlightsThoseCharacters() {
        val hits = searchSettings("maoboli", ::glassPinyin) { it }
        val hit = hits.single()
        assertEquals("appearance.glass", hit.id)
        assertEquals("毛玻璃", hit.title)
        assertEquals("外观与偏好", hit.page)
        assertEquals(listOf(0..2), hit.ranges)
        assertEquals(true, hit.fuzzy)
        assertEquals(listOf(0..0), settingsSearchRanges("毛玻璃", "mao", ::glassPinyin))
        assertEquals(listOf(1..2), settingsSearchRanges("毛玻璃", "boli", ::glassPinyin))
        assertEquals(listOf(0..2), settingsSearchRanges("毛玻璃", "Mao Bo Li", ::glassPinyin))
    }

    @Test fun missingPinyinDoesNotMatch() {
        assertNull(settingsSearchRanges("毛玻璃", "xyz"))
    }

    @Test fun mixedChineseAndPinyinHighlightsEveryMatchedCharacter() {
        assertEquals(listOf(0..2), settingsSearchRanges("毛玻璃", "毛boli", ::glassPinyin))
        assertEquals(listOf(0..2), settingsSearchRanges("毛玻璃", "mao玻璃", ::glassPinyin))
        assertEquals(listOf(0..2), settingsSearchRanges("毛玻璃", "毛bo璃", ::glassPinyin))
        assertEquals(listOf(0..2), settingsSearchRanges("毛玻璃", "毛b璃", ::glassPinyin))
        assertEquals(listOf(1..2), settingsSearchRanges("毛玻璃", "玻li", ::glassPinyin))
        assertEquals(listOf(0..2), settingsSearchRanges("毛玻璃", "毛 boli", ::glassPinyin))
    }
}

private fun glassPinyin(ch: Char) = when (ch) {
    '毛' -> "mao"
    '玻' -> "bo"
    '璃' -> "li"
    else -> if (ch.isLetterOrDigit()) ch.lowercaseChar().toString() else ""
}
