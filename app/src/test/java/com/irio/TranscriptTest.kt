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

    @Test
    fun editAfterStoppedReplyWasDiscardedStillOpensARequest() {
        val user = ChatMessage(id = 1, text = "first", fromUser = true)
        val committed = commitEditedTurn(
            messages = listOf(user),
            target = user,
            targetIndex = 0,
            versionId = 4,
            replyId = 5,
            text = "second",
            attachments = emptyList(),
        )
        assertEquals(listOf(1L, 4L, 5L), committed.messages.map { it.id })
        assertEquals(listOf(4L), committed.history.map { it.id })
        assertEquals(true, committed.reply.isPending)
        assertEquals(4L, committed.reply.responseToUserId)
        assertEquals(listOf(4L, 5L), visibleMessages(committed.messages).map { it.id })
    }

    @Test
    fun editKeepsTheExistingReplyGroup() {
        val user = ChatMessage(id = 1, text = "first", fromUser = true)
        val reply = ChatMessage(id = 2, text = "partial", fromUser = false, responseToUserId = 1)
        val committed = commitEditedTurn(
            messages = listOf(user, reply),
            target = user,
            targetIndex = 0,
            versionId = 4,
            replyId = 5,
            text = "second",
            attachments = emptyList(),
        )
        assertEquals(2L, committed.reply.groupId)
        assertEquals(false, committed.messages.first { it.id == 2L }.selected)
        assertEquals(listOf(4L), committed.history.map { it.id })
    }

    @Test
    fun hiddenUserMessageStaysInContextButNotOnScreen() {
        val user = ChatMessage(id = 1, text = "hi", fromUser = true)
        val reply = ChatMessage(id = 2, text = "ok", fromUser = false, responseToUserId = 1)
        val notice = ChatMessage(id = 3, text = "done", fromUser = true, hidden = true)
        val follow = ChatMessage(id = 4, text = "noted", fromUser = false, responseToUserId = 3)
        val all = listOf(user, reply, notice, follow)
        assertEquals(listOf(1L, 2L, 4L), visibleMessages(all).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), contextMessages(all).map { it.id })
        assertEquals("hi", ConversationStore.titleFrom(all))
    }
}
