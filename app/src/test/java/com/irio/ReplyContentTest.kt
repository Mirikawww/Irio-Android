package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyContentTest {
    @Test
    fun toolOnlyReplySurvivesStoppingBeforeAnyToolOutput() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(TimeTool.NAME)
        tracker.finishAll()
        val process = tracker.snapshot()
        assertFalse(process.single().live)
        assertTrue(hasReplyContent("", emptyList(), process))
    }

    @Test
    fun completedToolsWithoutResultTextStillCountAsReplyContent() {
        for (name in listOf(TimeTool.NAME, MemoryTool.GET, MemoryTool.UPDATE, LinuxShellTool.NAME, LinuxBackgroundTool.NAME, "")) {
            val tracker = ProcessTracker()
            tracker.onToolStarted(name)
            tracker.onToolFinished()
            assertTrue("Completed $name status must be retained", hasReplyContent("", emptyList(), tracker.snapshot()))
        }
    }

    @Test
    fun stoppedWebFetchKeepsItsDomainWithoutPageContent() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(WebFetchTool.NAME, "docs.example.com")
        tracker.finishAll()
        val event = tracker.snapshot().single()
        assertTrue(hasReplyContent("", emptyList(), listOf(event)))
        assertEquals("已浏览网页 - docs.example.com", MemoryTool.doneLabel(event.toolName, event.content, event.webDomain))
    }

    @Test
    fun stoppedSearchWithoutResultsKeepsItsQuery() {
        val tracker = ProcessTracker()
        tracker.onSearchStarted("Kotlin Compose")
        tracker.finishAll()
        val event = tracker.snapshot().single()
        assertFalse(event.live)
        assertTrue(hasReplyContent("", emptyList(), listOf(event)))
        assertEquals("搜索了 0 个网页 - Kotlin Compose", searchCompletedLabel(event.pages, event.searchQuery, AppLanguage.ZH_CN))
    }

    @Test
    fun zeroResultSearchWithoutQueryStillKeepsItsStatus() {
        val tracker = ProcessTracker()
        tracker.onSearchStarted()
        tracker.onSearchFinished(0)
        assertTrue(hasReplyContent("", emptyList(), tracker.snapshot()))
    }

    @Test
    fun trulyEmptyPlaceholderDoesNotCountAsReplyContent() {
        assertFalse(hasReplyContent("", emptyList(), emptyList()))
        assertFalse(hasReplyContent("  \n", emptyList(), emptyList()))
        assertFalse(hasReplyContent("", emptyList(), listOf(ProcessEvent(1, ProcessEventKind.THINKING))))
    }

    @Test
    fun bodyOrAttachmentsStillCountAsReplyContent() {
        assertTrue(hasReplyContent("已有正文", emptyList(), emptyList()))
        assertTrue(hasReplyContent("", listOf(ChatAttachment("image.png", "image/png", "image.png")), emptyList()))
    }

    @Test
    fun stoppedThinkingKeepsItsElapsedTime() {
        val tracker = ProcessTracker()
        tracker.onThinkingDelta("plan", now = 1_000)
        tracker.finishAll(now = 3_400)
        assertEquals(2_400L, tracker.snapshot().single().millis)
        assertTrue(hasReplyContent("", emptyList(), tracker.snapshot()))
        assertTrue(hasReplyContent("", emptyList(), listOf(ProcessEvent(1, ProcessEventKind.THINKING, millis = 1))))
    }

    @Test
    fun imageGenerationOnlyDropsTheSuccessfulHiddenStatus() {
        val event = ProcessEvent(1, ProcessEventKind.TOOL, toolName = ImageGenTool.NAME)
        assertTrue(hasReplyContent("", emptyList(), listOf(event)))
        assertTrue(hasReplyContent("", emptyList(), listOf(event.copy(content = ImageGenTool.STATUS_FAILED))))
        assertFalse(hasReplyContent("", emptyList(), listOf(event.copy(content = ImageGenTool.STATUS_OK))))
    }

    @Test
    fun stoppedToolOnlyReplySurvivesConversationRoundTrip() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(WebFetchTool.NAME, "docs.example.com")
        tracker.onToolFinished()
        tracker.onSearchStarted("Kotlin Compose")
        tracker.finishAll()
        val message = ChatMessage(1, "", fromUser = false, process = tracker.snapshot())
        val conversation = Conversation("test", "Test", 0, listOf(message))
        val restored = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(conversation).toString()))
        val reply = restored.messages.single()
        assertTrue(hasReplyContent(reply.text, reply.attachments, reply.process))
        assertEquals(message.process, reply.process)
        assertFalse(reply.isPending)
        assertFalse(reply.isStreaming)
        assertTrue(reply.process.none { it.live })
    }
}
