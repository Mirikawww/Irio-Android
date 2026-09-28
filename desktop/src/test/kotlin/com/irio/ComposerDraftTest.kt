package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDraftTest {
    @Test
    fun switchingConversationDoesNotCarryUnsentText() {
        val drafts = mutableMapOf<String, ComposerDraft>()
        val entered = swapComposerDraft(
            drafts,
            fromId = "a",
            toId = "b",
            leaving = ComposerDraft(text = "还没发送"),
        )
        assertEquals("", entered.text)
        assertEquals("还没发送", drafts["a"]?.text)
    }

    @Test
    fun switchingBackRestoresTheUnsentDraft() {
        val drafts = mutableMapOf<String, ComposerDraft>()
        swapComposerDraft(drafts, "a", "b", ComposerDraft(text = "还没发送"))
        val back = swapComposerDraft(drafts, "b", "a", ComposerDraft())
        assertEquals("还没发送", back.text)
        assertFalse(drafts.containsKey("a"))
    }

    @Test
    fun eachConversationKeepsItsOwnDraft() {
        val drafts = mutableMapOf<String, ComposerDraft>()
        swapComposerDraft(drafts, "a", "b", ComposerDraft(text = "A 的草稿"))
        val backToA = swapComposerDraft(drafts, "b", "a", ComposerDraft(text = "B 的草稿"))
        assertEquals("A 的草稿", backToA.text)
        val backToB = swapComposerDraft(drafts, "a", "b", ComposerDraft(text = "A 改过"))
        assertEquals("B 的草稿", backToB.text)
        assertEquals("A 改过", drafts["a"]?.text)
    }

    @Test
    fun leavingAnEmptyComposerDropsTheStoredDraft() {
        val drafts = mutableMapOf<String, ComposerDraft>()
        swapComposerDraft(drafts, "a", "b", ComposerDraft(text = "临时"))
        swapComposerDraft(drafts, "b", "a", ComposerDraft())
        val entered = swapComposerDraft(drafts, "a", "b", ComposerDraft())
        assertEquals("", entered.text)
        assertFalse(drafts.containsKey("a"))
    }

    @Test
    fun attachmentsAndEditModeStayWithTheConversation() {
        val attachment = ChatAttachment("a.png", "image/png", "C:/tmp/a.png")
        val drafts = mutableMapOf<String, ComposerDraft>()
        val entered = swapComposerDraft(
            drafts,
            fromId = "a",
            toId = "b",
            leaving = ComposerDraft(
                text = "编辑中",
                attachments = listOf(attachment),
                editingMessageId = 7L,
            ),
        )
        assertTrue(entered.attachments.isEmpty())
        assertNull(entered.editingMessageId)
        val back = swapComposerDraft(drafts, "b", "a", ComposerDraft())
        assertEquals(listOf(attachment), back.attachments)
        assertEquals(7L, back.editingMessageId)
    }

    @Test
    fun sameConversationDoesNotClobberWhatIsBeingTyped() {
        val drafts = mutableMapOf("a" to ComposerDraft(text = "旧的"))
        val staying = swapComposerDraft(drafts, "a", "a", ComposerDraft(text = "正在输入"))
        assertEquals("正在输入", staying.text)
        assertEquals("旧的", drafts["a"]?.text)
    }
}
