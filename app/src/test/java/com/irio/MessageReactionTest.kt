package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReactionTest {
    @Test
    fun userFeedbackIsVisibleToModelOnTheExactAssistantMessage() {
        val conversation = ConversationStore.parseConversation(JSONObject("""
            {"id":"feedback","messages":[
                {"id":1,"fromUser":true,"text":"你好"},
                {"id":2,"fromUser":false,"text":"你好呀","userReaction":"❤️"},
                {"id":3,"fromUser":false,"text":"别的版本"}
            ]}
        """.trimIndent()))
        val request = modelRequestMessages(conversation.messages)
        assertTrue(request[1].text.contains("❤️"))
        assertTrue(request[1].text.contains("user_reaction"))
        assertEquals("别的版本", request[2].text)
    }

    @Test
    fun editingCreatesAnUnreactedVersionWithoutChangingTheOriginalReaction() {
        val user = ChatMessage(1L, "原文", true, modelReaction = "❤️")
        val edited = commitEditedTurn(listOf(user), user, 0, 2L, 3L, "改写", emptyList())
        assertEquals("❤️", edited.messages.first { it.id == 1L }.modelReaction)
        assertEquals("", edited.history.last().modelReaction)
        val original = selectEditVersion(edited.messages, user)
        assertEquals("❤️", contextMessages(original).first().modelReaction)
    }

    @Test
    fun modelReactionReplacesAndCanBeRemovedWithoutChangingMessageText() {
        val user = ChatMessage(1L, "原文", true)
        val first = setModelReaction(user, "👍🏽")!!
        assertEquals("👍", first.modelReaction)
        val replaced = setModelReaction(first, "❤")!!
        assertEquals("❤️", replaced.modelReaction)
        assertEquals("", replaced.userReaction)
        val removed = setModelReaction(replaced, "")!!
        assertEquals(user, removed)
    }

    @Test
    fun userReactionReplacesAndCanBeRemovedFromTheExactReplyVersion() {
        val first = ChatMessage(2L, "版本一", false, retryGroup = 2L, selected = false, userReaction = "❤️")
        val second = ChatMessage(3L, "版本二", false, retryGroup = 2L)
        val reacted = setUserReaction(second, "👍🏿")!!
        assertEquals("👍", reacted.userReaction)
        assertEquals("❤️", first.userReaction)
        assertEquals(second, setUserReaction(reacted, ""))
        assertEquals("❤️", contextMessages(selectRetryVersion(listOf(first, reacted), first)).single().userReaction)
    }

    @Test
    fun wrongRolesInternalMessagesAndIncompleteRepliesCannotReceiveReactions() {
        val user = ChatMessage(1L, "用户", true)
        val assistant = ChatMessage(2L, "回答", false)
        assertNull(setModelReaction(assistant, "❤️"))
        assertNull(setUserReaction(user, "❤️"))
        assertNull(setModelReaction(user.copy(hidden = true), "❤️"))
        assertNull(setModelReaction(user.copy(summaryState = SummaryState.SUCCESS), "❤️"))
        assertNull(setUserReaction(assistant.copy(hidden = true), "❤️"))
        assertNull(setUserReaction(assistant.copy(isPending = true), "❤️"))
        assertNull(setUserReaction(assistant.copy(isStreaming = true), "❤️"))
        listOf("普通文本", "❤️👍", "🫪🫪").forEach {
            assertNull(setModelReaction(user, it))
            assertNull(setUserReaction(assistant, it))
        }
    }

    @Test
    fun bothReactionsSurviveLocalAndLinkRoundTripsAndRemoval() {
        val user = ChatMessage(1L, "用户", true, retryGroup = 1L, modelReaction = "🫪")
        val assistant = ChatMessage(2L, "回答", false, retryGroup = 2L, userReaction = "🧑‍🩰")
        val original = Conversation("reactions", "测试", 1L, listOf(user, assistant))
        val local = ConversationStore.parseConversation(JSONObject(ConversationStore.conversationJson(original).toString()))
        val linked = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(local).toString()))
        assertEquals(original, local)
        assertEquals(original.id, linked.id)
        assertEquals(original.messages.map { it.id }, linked.messages.map { it.id })
        assertEquals(original.messages.map { it.text }, linked.messages.map { it.text })
        assertEquals(listOf("🫪", ""), linked.messages.map { it.modelReaction })
        assertEquals(listOf("", "🧑‍🩰"), linked.messages.map { it.userReaction })
        val removed = linked.copy(messages = listOf(setModelReaction(user, "")!!, setUserReaction(assistant, "")!!))
        val encoded = ConversationStore.conversationJson(removed)
        assertFalse(encoded.getJSONArray("messages").getJSONObject(0).has("modelReaction"))
        assertFalse(encoded.getJSONArray("messages").getJSONObject(1).has("userReaction"))
        val reloaded = LinkConversationJson.decode(LinkConversationJson.encode(removed))
        assertTrue(reloaded.messages.all { it.modelReaction.isEmpty() && it.userReaction.isEmpty() })
        assertEquals(listOf(1L, 2L), reloaded.messages.map { it.id })
    }

    @Test
    fun invalidAndWrongRoleStoredReactionsAreDiscardedButUnicode17IsRetained() {
        val json = JSONObject("""{"id":"legacy","messages":[
            {"id":1,"text":"旧消息","fromUser":true},
            {"id":2,"text":"用户","fromUser":true,"modelReaction":"👍🏿","userReaction":"❤️"},
            {"id":3,"text":"回答","fromUser":false,"modelReaction":"❤️","userReaction":"❤"},
            {"id":4,"text":"非法","fromUser":false,"userReaction":"❤️👍"},
            {"id":5,"text":"新版","fromUser":true,"modelReaction":"🫪"}
        ]}""")
        listOf(ConversationStore.parseConversation(json), LinkConversationJson.decode(json)).forEach { conversation ->
            assertEquals(listOf("", "👍", "", "", "🫪"), conversation.messages.map { it.modelReaction })
            assertEquals(listOf("", "", "❤️", "", ""), conversation.messages.map { it.userReaction })
        }
    }

    @Test
    fun requestTargetIdsAreOnlyAssignedToRealUserTurnsAfterCompression() {
        val user = ChatMessage(1L, "之前", true)
        val summary = ChatMessage(2L, "压缩记录", false, summaryState = SummaryState.SUCCESS)
        val later = ChatMessage(3L, "之后", true)
        val hidden = ChatMessage(4L, "内部通知", true, hidden = true)
        val assistant = ChatMessage(5L, "回答", false)
        val request = modelRequestMessages(listOf(user, summary, later, hidden, assistant))
        assertEquals(listOf(null, null, 3L, null, null), request.map { it.reactionTargetId })
        val marked = reactionRequestMessages(request)
        assertTrue(marked[2].text.endsWith("[Irio message_id: 3]"))
        assertEquals(request[0].text, marked[0].text)
        assertEquals(request[1].text, marked[1].text)
        assertEquals(request[3].text, marked[3].text)
        assertEquals(request[4].text, marked[4].text)
    }

    @Test
    fun feedbackIsIncludedWithQuotesAndPreservedInCompression() {
        val user = ChatMessage(1L, "解释", true, quote = "引用", modelReaction = "👍")
        val assistant = ChatMessage(2L, "回答", false, userReaction = "❤️")
        assertTrue(user.requestText().startsWith("> 引用\n\n解释"))
        assertTrue(user.requestText().contains("assistant_reaction"))
        val turns = summaryTurns(planCompression(listOf(user, assistant))!!)
        assertEquals(user.requestText(), turns[0].text)
        assertTrue(turns[1].text.contains("user_reaction"))
        assertTrue(turns[1].text.contains("❤️"))
    }
}
