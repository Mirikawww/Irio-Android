package com.irio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingGlyphFadeTest {
    @Test
    fun appendOnlyBirthsTheSuffix() {
        val tracker = StreamingTailFadeTracker()
        val first = tracker.update("Hello", nowMs = 0L)
        assertEquals(5, first.birthTimesMs.size)
        assertTrue(first.birthTimesMs.all { it == 0L })

        val second = tracker.update("Hello!", nowMs = 100L)
        assertEquals(6, second.birthTimesMs.size)
        assertEquals(0L, second.birthTimesMs[0])
        assertEquals(100L, second.birthTimesMs[5])
    }

    @Test
    fun settledPrefixDropsAndNewSuffixStays() {
        val tracker = StreamingTailFadeTracker()
        tracker.update("Hello", nowMs = 0L)
        tracker.update("Hello!", nowMs = 100L)
        val settled = tracker.update("Hello!", nowMs = 500L)
        assertEquals(listOf(100L), settled.birthTimesMs.toList())
    }

    @Test
    fun rewriteKeepsTheUnchangedPrefix() {
        val tracker = StreamingTailFadeTracker()
        tracker.update("Hello world", nowMs = 0L)
        val rewritten = tracker.update("Hello there", nowMs = 100L)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 100L, 100L, 100L, 100L, 100L), rewritten.birthTimesMs.toList())

        tracker.update("Hello there", nowMs = 600L)
        val afterSettle = tracker.update("Hello folks", nowMs = 700L)
        assertEquals(listOf(700L, 700L, 700L, 700L, 700L), afterSettle.birthTimesMs.toList())
    }

    @Test
    fun identicalTextDoesNotRebirth() {
        val tracker = StreamingTailFadeTracker()
        tracker.update("Hello", nowMs = 0L)
        val again = tracker.update("Hello", nowMs = 40L)
        assertTrue(again.birthTimesMs.all { it == 0L })
    }

    @Test
    fun annotatedStringFadesOnlyTheTail() {
        val faded = streamingTailAnnotatedString(
            text = AnnotatedString("Hello"),
            color = Color.Red,
            birthTimesMs = longArrayOf(0L, 0L),
            nowMs = 0L,
        )
        assertEquals("Hello", faded.text)
        val spans = faded.spanStyles
        assertEquals(1, spans.size)
        assertEquals(3, spans.single().start)
        assertEquals(5, spans.single().end)
        assertEquals(0f, spans.single().item.color.alpha, 0.001f)

        val halfway = streamingTailAnnotatedString(
            text = AnnotatedString("Hello"),
            color = Color.Red,
            birthTimesMs = longArrayOf(0L, 0L),
            nowMs = 250L,
        )
        assertEquals(0.5f, halfway.spanStyles.single().item.color.alpha, 0.01f)

        val settled = streamingTailAnnotatedString(
            text = AnnotatedString("Hello"),
            color = Color.Red,
            birthTimesMs = longArrayOf(0L, 0L),
            nowMs = 500L,
        )
        assertTrue(settled.spanStyles.isEmpty())
    }

    @Test
    fun emphasisSpansSurviveTheFade() {
        val source = AnnotatedString.Builder().apply {
            append("Hello")
            addStyle(SpanStyle(background = Color.Yellow), 3, 5)
        }.toAnnotatedString()
        val faded = streamingTailAnnotatedString(
            text = source,
            color = Color.Red,
            birthTimesMs = longArrayOf(0L, 0L),
            nowMs = 0L,
        )
        assertEquals(Color.Yellow, faded.spanStyles.first { it.item.background == Color.Yellow }.item.background)
    }

    @Test
    fun nodeFadeHitsOnlyTheOverlappingNode() {
        val source = "Hello world"
        val births = LongArray(5) { 10L }
        assertNull(nodeFade(source, nodeStart = 0, nodeEnd = 5, births))
        val world = nodeFade(source, nodeStart = 6, nodeEnd = source.length, births)
        assertEquals(5, world?.tailCodePoints)
        assertEquals(10L, world?.birthTimesMs?.first())
    }

    @Test
    fun surrogatePairIsOneCodePoint() {
        val tracker = StreamingTailFadeTracker()
        val first = tracker.update("A\uD83D\uDE00", nowMs = 0L)
        assertEquals(2, first.birthTimesMs.size)
        val second = tracker.update("A\uD83D\uDE00!", nowMs = 50L)
        assertEquals(3, second.birthTimesMs.size)
        assertEquals(50L, second.birthTimesMs.last())
    }
}
