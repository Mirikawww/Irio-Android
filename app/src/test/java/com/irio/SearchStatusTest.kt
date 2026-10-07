package com.irio

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SearchStatusTest {
    @Test
    fun searchStartPublishesTheActualToolQueryBeforeNetworkAccess() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val stopBeforeNetwork = IllegalStateException("stop before network")
        val query = "Kotlin \"Compose\" 中文搜索"
        val result = runCatching {
            WebSearchToolProvider().execute(
                ChatToolCall("search", WebSearchTool.NAME, JSONObject().put("query", query).toString()),
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
        assertEquals(true, updates.single().searching)
        assertEquals(query, updates.single().searchQuery)
    }

    @Test
    fun trackerKeepsQuerySeparateFromSearchResults() {
        val tracker = ProcessTracker()
        tracker.onSearchStarted("Kotlin Compose")
        assertEquals("Kotlin Compose", tracker.snapshot().single().searchQuery)
        assertEquals("", tracker.snapshot().single().content)
        tracker.onSearchFinished(3, "网页结果")
        val event = tracker.snapshot().single()
        assertEquals("Kotlin Compose", event.searchQuery)
        assertEquals("网页结果", event.content)
        assertEquals(3, event.pages)
        assertFalse(event.live)
    }

    @Test
    fun consecutiveSearchesKeepTheirOwnQueries() {
        val tracker = ProcessTracker()
        tracker.onSearchStarted("first")
        tracker.onSearchFinished(1)
        tracker.onSearchStarted("second")
        assertEquals(listOf("first", "second"), tracker.snapshot().map { it.searchQuery })
    }

    @Test
    fun liveLabelUsesTheRequestedSeparatorAndLocalizesOnlyTheStatus() {
        assertEquals("网络搜索中 - Kotlin 中文", searchStatusLabel("Kotlin 中文", AppLanguage.ZH_CN))
        assertEquals("Searching the web - Kotlin 中文", searchStatusLabel("Kotlin 中文", AppLanguage.EN))
        assertEquals("网络搜索中", searchStatusLabel("", AppLanguage.ZH_CN))
    }

    @Test
    fun completedLabelKeepsPageCountAndQuery() {
        val tracker = ProcessTracker()
        tracker.onSearchStarted("Kotlin \"Compose\" 中文")
        tracker.onSearchFinished(3, "网页结果")
        val event = tracker.snapshot().single()
        assertEquals(
            "搜索了 3 个网页 - Kotlin \"Compose\" 中文",
            searchCompletedLabel(event.pages, event.searchQuery, AppLanguage.ZH_CN),
        )
    }

    @Test
    fun completedLabelLocalizesOnlyTheStatus() {
        assertEquals("Searched 3 pages - Kotlin 中文", searchCompletedLabel(3, "Kotlin 中文", AppLanguage.EN))
        assertEquals("搜索了 0 个网页 - no results", searchCompletedLabel(0, "no results", AppLanguage.ZH_CN))
    }

    @Test
    fun completedLabelWithoutQueryKeepsTheLegacyLabel() {
        assertEquals("搜索了 3 个网页", searchCompletedLabel(3, "", AppLanguage.ZH_CN))
        assertEquals("Searched 0 pages", searchCompletedLabel(0, "  ", AppLanguage.EN))
    }

    @Test
    fun querySurvivesConversationJsonRoundTrip() {
        val conversation = Conversation(
            id = "test", title = "Test", updatedAt = 0,
            messages = listOf(ChatMessage(
                id = 1, text = "reply", fromUser = false,
                process = listOf(ProcessEvent(1, ProcessEventKind.SEARCH, searchQuery = "Kotlin Compose", pages = 3)),
            )),
        )
        val restored = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(conversation).toString()))
        val event = restored.messages.single().process.single()
        assertEquals("Kotlin Compose", event.searchQuery)
        assertEquals("搜索了 3 个网页 - Kotlin Compose", searchCompletedLabel(event.pages, event.searchQuery, AppLanguage.ZH_CN))
    }

    @Test
    fun legacySearchEventsDefaultToAnEmptyQuery() {
        val json = JSONObject("""{"process":[{"id":1,"kind":"SEARCH","pages":3}]}""")
        val event = processEventsFrom(json).single()
        assertEquals("", event.searchQuery)
        assertEquals("搜索了 3 个网页", searchCompletedLabel(event.pages, event.searchQuery, AppLanguage.ZH_CN))
    }
}
