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

    @Test
    fun editedUserVersionOnlyShowsItsOwnReplies() {
        val original = ChatMessage(id = 1, text = "one", fromUser = true, selected = false)
        val edited = ChatMessage(id = 4, text = "two", fromUser = true, editGroup = 1, selected = true)
        val replyOne = ChatMessage(
            id = 2,
            text = "reply 1",
            fromUser = false,
            retryGroup = 2,
            selected = true,
            responseToUserId = original.id,
        )
        val replyTwo = ChatMessage(
            id = 3,
            text = "reply 2",
            fromUser = false,
            retryGroup = 2,
            selected = false,
            responseToUserId = original.id,
        )
        val replyThree = ChatMessage(
            id = 5,
            text = "reply 3",
            fromUser = false,
            retryGroup = 2,
            responseToUserId = edited.id,
        )
        val allMessages = listOf(original, replyOne, replyTwo, edited, replyThree)

        assertEquals(listOf(edited.id, replyThree.id), visibleMessages(allMessages).map { it.id })
        assertEquals(listOf(replyThree.id), buildTranscriptPresentation("test", allMessages).retryGroups[2L].orEmpty().map { it.id })
        val originalMessages = selectEditVersion(allMessages, original)
        val originalReply = originalMessages.first { it.id == replyOne.id }
        assertEquals(
            listOf(replyOne.id, replyTwo.id),
            buildTranscriptPresentation("test", selectRetryVersion(originalMessages, originalReply))
                .retryGroups[2L].orEmpty().map { it.id },
        )
        assertEquals(
            listOf(original.id, replyOne.id),
            visibleMessages(selectRetryVersion(originalMessages, originalReply)).map { it.id },
        )
    }

    @Test
    fun legacyMessagesInferReplyOwnershipByPosition() {
        val original = ChatMessage(id = 1, text = "one", fromUser = true, selected = false)
        val edited = ChatMessage(id = 4, text = "two", fromUser = true, editGroup = 1, selected = true)
        val oldReplyOne = ChatMessage(id = 2, text = "old 1", fromUser = false, retryGroup = 2, selected = true)
        val oldReplyTwo = ChatMessage(id = 3, text = "old 2", fromUser = false, retryGroup = 2, selected = true)
        val newReply = ChatMessage(id = 5, text = "new", fromUser = false, selected = true)
        val allMessages = listOf(original, oldReplyOne, oldReplyTwo, edited, newReply)

        assertEquals(listOf(edited.id, newReply.id), visibleMessages(allMessages).map { it.id })
    }
}
