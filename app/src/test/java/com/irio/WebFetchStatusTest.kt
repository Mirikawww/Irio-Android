package com.irio

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebFetchStatusTest {
    @Test
    fun fetchStartPublishesTheTargetDomainBeforeNetworkAccess() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val stopBeforeNetwork = IllegalStateException("stop before network")
        val result = runCatching {
            WebFetchToolProvider().execute(
                ChatToolCall("fetch", WebFetchTool.NAME, """{"url":"https://docs.example.com:8443/guide?token=private#part"}"""),
                ToolExecutionContext(
                    provider = ProviderConfig(id = "test", name = "Test", endpointUrl = "https://example.test/v1", apiKey = "key"),
                    model = "model",
                    webSearch = true,
                    searchApiKey = "test-key",
                    onUpdate = {
                        updates += it
                        throw stopBeforeNetwork
                    },
                ),
            )
        }
        assertEquals(stopBeforeNetwork.javaClass, result.exceptionOrNull()?.javaClass)
        assertEquals(stopBeforeNetwork.message, result.exceptionOrNull()?.message)
        assertEquals(true, updates.single().toolActive)
        assertEquals(WebFetchTool.NAME, updates.single().toolName)
        assertEquals("docs.example.com", updates.single().webDomain)
    }

    @Test
    fun domainExcludesCredentialsPortPathQueryAndFragment() {
        assertEquals("www.example.com", webPageDomain("https://user:password@www.example.com:8443/path?token=private#part"))
        assertEquals("docs.example.com", webPageDomain("  http://docs.example.com/page  "))
        assertEquals("xn--bcher-kva.de", webPageDomain("https://bücher.de/page"))
    }

    @Test
    fun invalidOrNonWebUrlsDoNotShowAnInventedDomain() {
        listOf("", "not a url", "https://", "https://bad host/page", "file:///example.com", "javascript:example.com").forEach {
            assertEquals("", webPageDomain(it))
        }
    }

    @Test
    fun liveLabelShowsTheDomainOnlyForWebFetch() {
        assertEquals("浏览网页中 - docs.example.com", MemoryTool.liveLabel(WebFetchTool.NAME, "docs.example.com"))
        assertEquals("浏览网页中", MemoryTool.liveLabel(WebFetchTool.NAME))
        assertEquals("获取时间", MemoryTool.liveLabel(TimeTool.NAME, "docs.example.com"))
        assertEquals("已浏览网页", MemoryTool.doneLabel(WebFetchTool.NAME))
    }

    @Test
    fun completedLabelKeepsDomainSeparateFromPageContent() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(WebFetchTool.NAME, "docs.example.com")
        tracker.onToolFinished("网页正文")
        val event = tracker.snapshot().single()
        assertEquals("已浏览网页 - docs.example.com", MemoryTool.doneLabel(event.toolName, event.content, event.webDomain))
    }

    @Test
    fun completedLabelWithoutDomainKeepsTheLegacyLabel() {
        assertEquals("已浏览网页", MemoryTool.doneLabel(WebFetchTool.NAME, "网页正文"))
        assertEquals("已浏览网页", MemoryTool.doneLabel(WebFetchTool.NAME, "网页正文", "  "))
    }

    @Test
    fun completedLabelUsesDomainOnlyForWebFetch() {
        assertEquals("已获取时间", MemoryTool.doneLabel(TimeTool.NAME, webDomain = "docs.example.com"))
        assertEquals("已读取记忆", MemoryTool.doneLabel(MemoryTool.GET, webDomain = "docs.example.com"))
        assertEquals(
            "生成图片失败",
            MemoryTool.doneLabel(ImageGenTool.NAME, ImageGenTool.STATUS_FAILED, "docs.example.com"),
        )
    }

    @Test
    fun trackerKeepsDomainSeparateFromPageContentAndDoesNotLeakItToOtherTools() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(WebFetchTool.NAME, "docs.example.com")
        assertEquals("docs.example.com", tracker.snapshot().single().webDomain)
        assertEquals("", tracker.snapshot().single().content)
        tracker.onToolFinished("网页正文")
        val finished = tracker.snapshot().single()
        assertFalse(finished.live)
        assertEquals("网页正文", finished.content)
        assertEquals("docs.example.com", finished.webDomain)
        tracker.onToolStarted(TimeTool.NAME)
        assertEquals("", tracker.snapshot().last().webDomain)
    }

    @Test
    fun domainSurvivesConversationJsonRoundTrip() {
        val conversation = Conversation(
            id = "test", title = "Test", updatedAt = 0,
            messages = listOf(ChatMessage(
                id = 1, text = "reply", fromUser = false,
                process = listOf(ProcessEvent(1, ProcessEventKind.TOOL, toolName = WebFetchTool.NAME, webDomain = "docs.example.com")),
            )),
        )
        val restored = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(conversation).toString()))
        val event = restored.messages.single().process.single()
        assertEquals("docs.example.com", event.webDomain)
        assertEquals("已浏览网页 - docs.example.com", MemoryTool.doneLabel(event.toolName, event.content, event.webDomain))
    }

    @Test
    fun legacyToolEventsDefaultToAnEmptyDomain() {
        val json = JSONObject("""{"process":[{"id":1,"kind":"TOOL","toolName":"web_fetch"}]}""")
        val event = processEventsFrom(json).single()
        assertEquals("", event.webDomain)
        assertEquals("已浏览网页", MemoryTool.doneLabel(event.toolName, event.content, event.webDomain))
    }
}
