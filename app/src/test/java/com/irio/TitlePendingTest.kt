package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlePendingTest {
    @Test
    fun newConversationMarksTitlePending() {
        val conversation = ConversationStore.newConversation()
        assertTrue(conversation.titlePending)
        assertEquals("新对话", conversation.title)
    }

    @Test
    fun generatedTitleClearsPendingFlag() {
        val conversation = ConversationStore.newConversation().copy(title = "AI 标题", titlePending = false)
        assertFalse(conversation.titlePending)
    }

    @Test
    fun legacyConversationWithoutFlagIsNotPending() {
        // 老数据 JSON 没有 titlePending 字段：optBoolean 默认 false，不会重新触发 AI 生成
        val json = JSONObject()
            .put("id", "c1")
            .put("title", "已手动命名")
            .put("updatedAt", 123L)
            .put("messages", org.json.JSONArray())
        val conversation = LinkConversationJson.decode(json)
        assertFalse(conversation.titlePending)
    }

    @Test
    fun linkRoundTripKeepsPendingFlag() {
        val conversation = ConversationStore.newConversation()
        val restored = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(conversation).toString()))
        assertTrue(restored.titlePending)
    }

    @Test
    fun pendingConversationWithoutTitleUsesTruncatedFirstMessage() {
        // titlePending 期间标题跟随首条用户消息（titleFrom 截断），不再依赖“新对话”字面量
        val user = ChatMessage(id = 1, text = "很长的第一条消息内容会被截断作为临时标题", fromUser = true)
        val title = ConversationStore.titleFrom(listOf(user))
        assertTrue(title.isNotBlank())
        assertTrue(title.length <= 24)
    }
}
