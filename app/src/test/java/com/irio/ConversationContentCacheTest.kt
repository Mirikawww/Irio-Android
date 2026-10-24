package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContentCacheTest {
    @Test
    fun defaultCacheKeepsOnlyEightBodies() {
        val cache = ConversationContentCache()
        repeat(9) { index -> cache["$index"] = conversation("$index") }
        assertEquals(8, cache.size)
        assertFalse(cache.containsKey("0"))
        assertTrue(cache.containsKey("8"))
    }

    @Test
    fun recentlyOpenedConversationSurvivesEviction() {
        val cache = ConversationContentCache(2)
        val first = conversation("first")
        cache[first.id] = first
        cache["second"] = conversation("second")
        assertSame(first, cache["first"])
        cache["third"] = conversation("third")
        assertFalse(cache.containsKey("second"))
        assertSame(first, cache["first"])
    }

    @Test
    fun streamingReplacementUpdatesTheBodyWithoutDuplicatingEntries() {
        val cache = ConversationContentCache(2)
        val original = conversation("first")
        cache[original.id] = original
        cache["second"] = conversation("second")
        val streamed = original.copy(messages = listOf(ChatMessage(1, "updated", false)))
        cache[streamed.id] = streamed
        assertEquals(2, cache.size)
        assertSame(streamed, cache[streamed.id])
        cache["third"] = conversation("third")
        assertFalse(cache.containsKey("second"))
        assertSame(streamed, cache[streamed.id])
    }

    private fun conversation(id: String) = Conversation(id, "Title $id", 1L, emptyList())
}
