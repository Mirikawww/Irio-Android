package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTest {
    @Test
    fun hidesUnselectedRetrySiblings() {
        val user = ChatMessage(id = 1, text = "hi", fromUser = true)
        val first = ChatMessage(id = 2, text = "a", fromUser = false, retryGroup = 2, selected = false)
        val second = ChatMessage(id = 3, text = "b", fromUser = false, retryGroup = 2, selected = true)
        val visible = visibleMessages(listOf(user, first, second))
        assertEquals(listOf(1L, 3L), visible.map { it.id })
    }

    @Test
    fun editedReplyKeepsOldThinkingHidden() {
        val user = ChatMessage(id = 1, text = "edited", fromUser = true)
        val oldReply = ChatMessage(
            id = 2,
            text = "old",
            fromUser = false,
            retryGroup = 2,
            selected = false,
            process = listOf(ProcessEvent(1, ProcessEventKind.THINKING, "old plan")),
        )
        val newReply = ChatMessage(
            id = 3,
            text = "new",
            fromUser = false,
            retryGroup = 2,
            selected = true,
        )
        val visible = visibleMessages(listOf(user, oldReply, newReply))
        assertEquals(listOf(1L, 3L), visible.map { it.id })
        assertEquals(emptyList<ProcessEvent>(), visible.last().process)
    }

    @Test
    fun groupsRetryVersionsInOnePass() {
        val first = ChatMessage(id = 2, text = "a", fromUser = false, retryGroup = 2, selected = false)
        val second = ChatMessage(id = 3, text = "b", fromUser = false, retryGroup = 2, selected = true)
        val other = ChatMessage(id = 4, text = "c", fromUser = false)
        val groups = retryVersionsByGroup(listOf(first, second, other))
        assertEquals(listOf(2L, 3L), groups[2L].orEmpty().map { it.id })
        assertEquals(listOf(4L), groups[4L].orEmpty().map { it.id })
    }
}
