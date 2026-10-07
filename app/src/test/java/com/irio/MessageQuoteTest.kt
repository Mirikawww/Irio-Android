package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import org.junit.Test

class MessageQuoteTest {
    @Test
    fun quoteMenuUsesCurrentSelectedExcerptWithoutClipboard() {
        var selected = "最初选中"
        var received = ""
        var closed = false
        val item = quoteMenuItem("引用", { selected }) { received = it }!!
        selected = "用户调整后的选中段落"
        item.onClick(object : TextContextMenuSession {
            override fun close() { closed = true }
        })
        assertEquals("用户调整后的选中段落", received)
        assertTrue(closed)
        assertNull(quoteMenuItem("引用", { "  " }) {})
    }

    @Test
    fun quoteOnlyDraftStaysWithItsConversation() {
        val drafts = mutableMapOf<String, ComposerDraft>()
        val entered = swapComposerDraft(drafts, "a", "b", ComposerDraft(quote = "引用片段"))
        assertTrue(entered.isEmpty)
        val restored = swapComposerDraft(drafts, "b", "a", entered)
        assertEquals("引用片段", restored.quote)
        assertTrue(!restored.isEmpty)
    }

    @Test
    fun selectedExcerptIsIncludedInModelRequest() {
        val conversation = LinkConversationJson.decode(JSONObject("""
            {"id":"quote","messages":[
                {"id":1,"fromUser":true,"text":"解释这段","quote":"第一行\n\n第二行"}
            ]}
        """.trimIndent()))

        assertEquals(
            "> 第一行\n> \n> 第二行\n\n解释这段",
            modelRequestMessages(conversation.messages).single().text,
        )
    }

    @Test
    fun quoteSurvivesLocalStorageAndLinkRoundTrips() {
        val message = ChatMessage(1L, "解释", true, quote = "精确选区\n保留换行 🐾")
        val original = Conversation("quote", "测试", 1L, listOf(message))
        val local = ConversationStore.parseConversation(
            JSONObject(ConversationStore.conversationJson(original).toString()),
        )
        val linked = LinkConversationJson.decode(
            JSONObject(LinkConversationJson.encode(local).toString()),
        )
        assertEquals(message.quote, local.messages.single().quote)
        assertEquals(message.quote, linked.messages.single().quote)
        assertEquals(message.text, linked.messages.single().text)
    }

    @Test
    fun editingCanReplaceOrRemoveQuoteWithoutChangingOldVersion() {
        val target = ChatMessage(1L, "旧正文", true, quote = "旧引用")
        val edited = commitEditedTurn(listOf(target), target, 0, 2L, 3L, "新正文", emptyList(), quote = "新引用")
        assertEquals("旧引用", edited.messages.first().quote)
        assertEquals("新引用", edited.history.last().quote)
        val removed = commitEditedTurn(listOf(target), target, 0, 4L, 5L, "去掉引用", emptyList(), quote = "")
        assertEquals("去掉引用", modelRequestMessages(removed.history).single().text)
    }

    @Test
    fun quoteIsIncludedInContextCompression() {
        val user = ChatMessage(1L, "解释", true, quote = "片段")
        val assistant = ChatMessage(2L, "回答", false)
        val plan = planCompression(listOf(user, assistant))!!
        assertEquals(user.requestText(), summaryTurns(plan).first().text)
    }

    @Test
    fun missingQuoteAndAssistantMessagesRemainUnchanged() {
        val legacy = LinkConversationJson.decode(JSONObject("""
            {"id":"legacy","messages":[{"id":1,"fromUser":true,"text":"正文"}]}
        """.trimIndent()))
        assertEquals("", legacy.messages.single().quote)
        assertEquals("正文", modelRequestMessages(legacy.messages).single().text)
        assertEquals("回答", ChatMessage(2L, "回答", false, quote = "不应加入").requestText())
        assertEquals("> 仅引用", ChatMessage(3L, "", true, quote = "仅引用").requestText())
    }

    @Test
    fun clearedQuoteDoesNotComeBackOnConversationSwitch() {
        val drafts = mutableMapOf<String, ComposerDraft>()
        swapComposerDraft(drafts, "a", "b", ComposerDraft(quote = "片段"))
        swapComposerDraft(drafts, "b", "a", ComposerDraft())
        swapComposerDraft(drafts, "a", "b", ComposerDraft())
        assertTrue(swapComposerDraft(drafts, "b", "a", ComposerDraft()).isEmpty)
    }
}
