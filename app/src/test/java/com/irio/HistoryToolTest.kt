package com.irio

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HistoryToolTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context by lazy {
        val root = temp.newFolder("home")
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = root
        }
    }
    private val provider = ProviderConfig("test", "Test", "https://example.test", "")

    private fun save(id: String, title: String = id, updated: Long = 1, vararg messages: ChatMessage) {
        ConversationStore.save(context, Conversation(id, title, updated, messages.toList()))
    }

    private fun query(arguments: String, current: String = "active"): JSONObject = runBlocking {
        val result = ToolRegistry.default().execute(ChatToolCall("history", "query_history", arguments),
            ToolExecutionContext(provider, "model", appContext = context, conversationId = current))
        assertTrue("History must be a real callable tool, got: ${result.text.take(100)}", result.text.startsWith("{"))
        JSONObject(result.text)
    }

    @Test fun registeredForEveryProtocolAndListedWithEditablePreferences() {
        for (protocol in ProviderProtocol.entries) {
            val schema = ToolRegistry.default().definitions(protocol).singleOrNull {
                (it.optJSONObject("function") ?: it).optString("name") == "query_history"
            }
            assertNotNull("History tool missing in $protocol", schema)
            assertTrue(schema.toString().contains("offset"))
            assertTrue(schema.toString().contains("limit"))
        }
        val entry = ToolCatalog.entries(ToolFeatureState()).single { it.name == "query_history" }
        assertEquals("查询历史会话", entry.title)
        assertNull(entry.blockedReason)
        val disabled = ToolRegistry(ToolRegistry.default().providers(), preferences = ToolPreferences(setOf("query_history")))
        assertFalse(disabled.handles("query_history"))
        assertFalse(disabled.systemPrompt(null).orEmpty().contains("query_history"))
        val edited = ToolRegistry(ToolRegistry.default().providers(), preferences = ToolPreferences(descriptions = mapOf("query_history" to "My history description")))
        val definition = edited.definitions(ProviderProtocol.RESPONSES).single { it.optString("name") == "query_history" }
        assertEquals("My history description", definition.getString("description"))
    }

    @Test fun searchesChineseAndEnglishAndReturnsReadableLineLocations() {
        save("trip", "旅行计划", 3, ChatMessage(1, "我们讨论了台北旅行\n预算是五千元。", true))
        save("active", "正在聊天", 4, ChatMessage(2, "台北旅行", true))
        save("code", "代码", 2, ChatMessage(3, "Kotlin Coroutines cancellation", false))
        val result = query("""{"action":"search","query":"台北 旅行"}""")
        val hit = result.getJSONArray("results").getJSONObject(0)
        assertEquals(1, result.getJSONArray("results").length())
        assertEquals("trip", hit.getString("conversation_id"))
        assertEquals("1", hit.getString("message_id"))
        assertTrue(hit.getString("snippet").contains("台北"))
        val read = query(JSONObject().put("action", "read").put("conversation_id", "trip")
            .put("offset", hit.getInt("start_line")).put("limit", hit.getInt("end_line") - hit.getInt("start_line") + 1).toString())
        assertTrue(read.toString().contains("预算是五千元"))
        assertTrue(read.getBoolean("untrusted_content"))
        assertEquals("code", query("""{"action":"search","query":"kOTLIN"}""")
            .getJSONArray("results").getJSONObject(0).getString("conversation_id"))
    }

    @Test fun listAndSearchHaveModelSelectedResultRanges() {
        for (i in 1..5) save("c$i", "历史 $i", i.toLong(), ChatMessage(i.toLong(), "needle $i", true))
        val listed = query("""{"action":"list","offset":2,"limit":2}""")
        assertEquals(listOf("c4", "c3"), listed.getJSONArray("results").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("conversation_id") }
        })
        assertTrue(listed.getBoolean("has_more"))
        assertEquals(4, listed.getInt("next_offset"))
        val searched = query("""{"action":"search","query":"needle","offset":4,"limit":2}""")
        assertEquals("c2", searched.getJSONArray("results").getJSONObject(0).getString("conversation_id"))
        assertEquals("c1", searched.getJSONArray("results").getJSONObject(1).getString("conversation_id"))
        assertFalse(searched.getBoolean("has_more"))
    }

    @Test fun readUsesOneBasedLineRangesNotAFixedWindowAroundAMatch() {
        save("long", messages = arrayOf(ChatMessage(10, (1..30).joinToString("\n") { "正文 $it" }, true)))
        val full = query("""{"action":"read","conversation_id":"long","limit":100}""")
        val selected = query("""{"action":"read","conversation_id":"long","offset":17,"limit":3}""")
        val lines = selected.getJSONArray("lines")
        assertEquals(3, lines.length())
        for (i in 0..2) {
            assertEquals(17 + i, lines.getJSONObject(i).getInt("line"))
            assertEquals(full.getJSONArray("lines").getJSONObject(16 + i).getString("text"), lines.getJSONObject(i).getString("text"))
        }
        assertEquals(20, selected.getInt("next_offset"))
        assertEquals(0, query("""{"action":"read","conversation_id":"long","offset":999}""").getJSONArray("lines").length())
    }

    @Test fun excludesHiddenSupersededErrorsAndReasoningButKeepsOriginalsAndToolRecords() {
        save("versions", messages = arrayOf(
            ChatMessage(1, "old-private-version", true, selected = false),
            ChatMessage(2, "old-reply", false, responseToUserId = 1),
            ChatMessage(3, "visible user", true, quote = "quoted-visible-text"),
            ChatMessage(4, "hidden-private", false, hidden = true),
            ChatMessage(5, "failed-private", false, isError = true),
            ChatMessage(6, "summary-private", false, summaryState = SummaryState.SUCCESS),
            ChatMessage(7, "visible reply", false, process = listOf(ProcessEvent(1, ProcessEventKind.THINKING, "thinking-private")),
                toolRecords = listOf(ChatToolRecord(ChatToolCall("call", "some_tool", "arguments-visible"), "tool-visible"))),
        ))
        val read = query("""{"action":"read","conversation_id":"versions"}""").toString()
        for (hidden in listOf("old-private-version", "old-reply", "hidden-private", "failed-private", "summary-private", "thinking-private")) {
            assertFalse("History leaked $hidden", read.contains(hidden))
            assertEquals(0, query(JSONObject().put("action", "search").put("query", hidden).toString()).getJSONArray("results").length())
        }
        assertTrue(read.contains("visible user"))
        assertTrue(read.contains("quoted-visible-text"))
        assertTrue(read.contains("visible reply"))
        assertTrue(read.contains("some_tool"))
        assertTrue(read.contains("arguments-visible"))
        assertTrue(read.contains("tool-visible"))
    }

    @Test fun recordedToolNamesArgumentsAndResultsAreSearchableAndRangeReadable() {
        val arguments = """{"filter":"unusual-query-phrase"}"""
        save("tools", messages = arrayOf(ChatMessage(11, "done", false, toolRecords = listOf(
            ChatToolRecord(ChatToolCall("tool-call", "todoist_mcp_find-tasks", arguments), "unique-result-payload"),
        ))))
        for (keyword in listOf("todoist_mcp_find-tasks", "unusual-query-phrase", "unique-result-payload")) {
            val hits = query(JSONObject().put("action", "search").put("query", keyword).toString()).getJSONArray("results")
            assertEquals("Missing searchable tool content: $keyword", 1, hits.length())
            assertEquals("11", hits.getJSONObject(0).getString("message_id"))
            assertTrue(hits.getJSONObject(0).getString("snippet").contains(keyword))
        }
        val read = query("""{"action":"read","conversation_id":"tools"}""")
        val lines = read.getJSONArray("lines")
        val toolLines = (0 until lines.length()).map { lines.getJSONObject(it) }.filter { it.getString("kind") == "tool" }
        assertTrue(toolLines.isNotEmpty())
        val toolText = toolLines.joinToString("\n") { it.getString("text") }
        assertTrue(toolText.contains("todoist_mcp_find-tasks"))
        assertTrue(toolText.contains(arguments))
        assertTrue(toolText.contains("unique-result-payload"))
        val offset = toolLines.first().getInt("line")
        val ranged = query(JSONObject().put("action", "read").put("conversation_id", "tools")
            .put("offset", offset).put("limit", toolLines.size).toString()).getJSONArray("lines")
        assertEquals(toolLines.size, ranged.length())
        assertEquals(toolText, (0 until ranged.length()).joinToString("\n") { ranged.getJSONObject(it).getString("text") })
    }

    @Test fun toolsAlreadyExecutedRemainQueryableWhenTheAssistantReplyLaterFailed() {
        save("failed-turn", messages = arrayOf(ChatMessage(12, "private transport error", false, isError = true,
            toolRecords = listOf(ChatToolRecord(ChatToolCall("done", "get_time", "{}"), "already-executed-result")))))
        val hit = query("""{"action":"search","query":"already-executed-result"}""").getJSONArray("results")
        assertEquals(1, hit.length())
        assertEquals("12", hit.getJSONObject(0).getString("message_id"))
        val read = query("""{"action":"read","conversation_id":"failed-turn"}""").toString()
        assertTrue(read.contains("already-executed-result"))
        assertTrue(read.contains("get_time"))
        assertFalse(read.contains("private transport error"))
        assertTrue(ConversationStore.load(context, "failed-turn")!!.messages.single().isError)
    }

    @Test fun legacyToolAndSearchRecordsAreIncludedWithoutDuplicatesOrInventedArguments() {
        save("legacy", messages = arrayOf(ChatMessage(13, "", false,
            process = listOf(
                ProcessEvent(1, ProcessEventKind.TOOL, "duplicate-result", toolName = "get_time"),
                ProcessEvent(2, ProcessEventKind.TOOL, "legacy-output", toolName = "legacy_tool"),
                ProcessEvent(3, ProcessEventKind.SEARCH, "legacy-search-result", searchQuery = "historic-query"),
                ProcessEvent(4, ProcessEventKind.THINKING, "do-not-expose-reasoning"),
            ),
            toolRecords = listOf(ChatToolRecord(ChatToolCall("clock", "get_time", "{}"), "duplicate-result")),
        )))
        val read = query("""{"action":"read","conversation_id":"legacy"}""").getJSONArray("lines")
        val text = (0 until read.length()).joinToString("\n") { read.getJSONObject(it).getString("text") }
        assertEquals(1, Regex(Regex.escape("[Tool execution record: get_time]")).findAll(text).count())
        assertEquals(1, Regex(Regex.escape("duplicate-result")).findAll(text).count())
        assertTrue(text.contains("[Tool execution record: legacy_tool]\nlegacy-output"))
        assertTrue(text.contains("[Tool execution record: web_search]\nQuery: historic-query\nlegacy-search-result"))
        assertEquals(1, Regex("Arguments:").findAll(text).count())
        assertFalse(text.contains("do-not-expose-reasoning"))
        assertEquals(1, query("""{"action":"search","query":"historic-query"}""").getJSONArray("results").length())
    }

    @Test fun longToolResultsRemainCompleteAcrossBoundedReadPages() {
        val message = ChatMessage(14, "", false, toolRecords = listOf(
            ChatToolRecord(ChatToolCall("large", "read_file", """{"path":"example.txt"}"""),
                "prefix" + "工具结果😀".repeat(12_000) + "suffix"),
        ))
        save("long-tools", messages = arrayOf(message))
        val collected = StringBuilder()
        var offset = 1
        var pages = 0
        while (true) {
            val response = query(JSONObject().put("action", "read").put("conversation_id", "long-tools")
                .put("offset", offset).put("limit", 2000).toString())
            assertTrue(response.toString().length <= 24_000)
            val lines = response.getJSONArray("lines")
            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                if (line.getString("kind") == "tool") collected.append(line.getString("text"))
            }
            pages++
            if (!response.getBoolean("has_more")) break
            val next = response.getInt("next_offset")
            assertTrue(next > offset)
            offset = next
            assertTrue(pages < 100)
        }
        assertTrue(pages > 1)
        assertEquals(message.toolContextText().replace("\n", ""), collected.toString())
    }

    @Test fun currentConversationRequiresExplicitScopeAndDeletedConversationsDisappear() {
        save("active", messages = arrayOf(ChatMessage(1, "older original text", true)))
        assertEquals(0, query("""{"action":"search","query":"older"}""").getJSONArray("results").length())
        assertTrue(query("""{"action":"read","conversation_id":"current"}""").toString().contains("older original text"))
        ConversationStore.delete(context, "active")
        assertEquals("not_found", query("""{"action":"read","conversation_id":"active"}""").getJSONObject("error").getString("code"))
    }

    @Test fun invalidRangesAndPathsAreRecoverableErrorsNotFilesystemReads() {
        for (arguments in listOf(
            """{"action":"read","conversation_id":"../../keystore"}""",
            """{"action":"read"}""", """{"action":"search","query":" "}""",
            """{"action":"list","offset":0}""", """{"action":"list","limit":0}""",
            """{"action":"list","limit":2001}""", """{"action":"list","limit":1.5}""",
            """{"action":"list","offset":"2"}""", """{"action":"delete"}""", "not json",
        )) {
            assertEquals(arguments, "invalid_arguments", query(arguments).getJSONObject("error").getString("code"))
        }
    }

    @Test fun outputIsBoundedAndEveryLongLineCanBeReadByFollowingNextOffset() {
        val original = "x".repeat(35_000) + "😀中文结尾" + "z".repeat(35_000)
        save("huge", messages = arrayOf(ChatMessage(1, original, true)))
        val before = File(context.filesDir, "conversations/huge.json").readBytes()
        val collected = StringBuilder()
        var offset = 1
        var pages = 0
        do {
            val response = query(JSONObject().put("action", "read").put("conversation_id", "huge").put("offset", offset).put("limit", 2000).toString())
            assertTrue(response.toString().length <= 24_000)
            val lines = response.getJSONArray("lines")
            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                if (line.getString("kind") == "text") collected.append(line.getString("text"))
            }
            pages++
            if (!response.getBoolean("has_more")) break
            val next = response.getInt("next_offset")
            assertTrue(next > offset)
            offset = next
            assertTrue("History pagination did not terminate", pages < 100)
        } while (true)
        assertTrue(pages > 1)
        assertEquals(original, collected.toString())
        assertArrayEquals(before, File(context.filesDir, "conversations/huge.json").readBytes())
    }

    @Test fun cancellationClosesProgressAndDoesNotChangeHistory() = runBlocking {
        save("one", messages = arrayOf(ChatMessage(1, "unchanged", true)))
        val cancellation = ChatRequestCancellation()
        val events = mutableListOf<ChatStreamUpdate>()
        val result = ToolRegistry.default().execute(ChatToolCall("cancel", "query_history", """{"action":"list"}"""),
            ToolExecutionContext(provider, "model", appContext = context, cancellation = cancellation, onUpdate = {
                events += it
                if (it.toolActive == true) cancellation.cancel()
            }))
        assertEquals("cancelled", JSONObject(result.text).getJSONObject("error").getString("code"))
        assertEquals(listOf(true, false), events.map { it.toolActive })
        assertEquals("unchanged", ConversationStore.load(context, "one")!!.messages.single().text)
    }

    @Test fun unreadableConversationsAreReportedAndRecoverAfterTheirFileIsRepaired() {
        save("broken", messages = arrayOf(ChatMessage(1, "needle", true)))
        val file = File(context.filesDir, "conversations/broken.json")
        val original = file.readBytes()
        file.writeText("partially written json")
        val failed = query("""{"action":"search","query":"needle"}""")
        assertEquals(0, failed.getJSONArray("results").length())
        assertEquals(1, failed.getInt("unavailable_conversations"))
        file.writeBytes(original)
        assertEquals(1, query("""{"action":"search","query":"needle"}""").getJSONArray("results").length())
    }

    @Test fun attachmentNamesAreSearchableButPathsAndContentsAreNotReturned() {
        val attachment = temp.newFile("attachment-private.txt").apply { writeText("file-content-secret") }
        save("file", messages = arrayOf(ChatMessage(1, "attached", true,
            attachments = listOf(ChatAttachment("合同.pdf", "application/pdf", attachment.absolutePath)))))
        assertEquals(1, query("""{"action":"search","query":"合同"}""").getJSONArray("results").length())
        val read = query("""{"action":"read","conversation_id":"file"}""").toString()
        assertTrue(read.contains("合同.pdf"))
        assertFalse(read.contains("file-content-secret"))
        assertFalse(read.contains("attachment-private"))
        assertEquals(0, query("""{"action":"search","query":"file-content-secret"}""").getJSONArray("results").length())
    }

    @Test fun modelCallsTheRealToolAndReceivesExactlyItsChosenReadRange() = runBlocking {
        org.robolectric.shadows.ShadowEnvironment.addExternalDir(context.cacheDir.resolve("external").absolutePath)
        save("stored", "旧会话", 1, ChatMessage(10, "不需要的第一行\n需要的第二行\n不需要的第三行", true))
        MockWebServer().use { server ->
            fun toolFrame(id: String, args: String): MockResponse {
                val frame = JSONObject("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"$id","function":{"name":"query_history"}}]},"finish_reason":"tool_calls"}]}""")
                frame.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls")
                    .getJSONObject(0).getJSONObject("function").put("arguments", args)
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $frame\n\ndata: [DONE]\n\n")
            }
            server.enqueue(toolFrame("list-call", """{"action":"list"}"""))
            server.enqueue(toolFrame("read-call", """{"action":"read","conversation_id":"stored","offset":3,"limit":1}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"找到了\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val updates = mutableListOf<ChatStreamUpdate>()
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                val result = ChatApi.streamChat(provider.copy(endpointUrl = server.url("/v1").toString()), "model",
                    listOf(ChatRequestMessage("user", "读以前的记录")), ReasoningEffort.OFF, false,
                    toolRegistry = chatToolRegistry(context, webSearch = false, imageGenModel = ""),
                    cancellation = ChatRequestCancellation(), onUpdate = { updates += it }, appContext = context, conversationId = "active")
                assertNull(result.error)
                assertEquals("找到了", result.content)
                assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8().contains("query_history"))
                val second = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                assertTrue(second.getJSONObject(second.length() - 1).getString("content").contains("旧会话"))
                val third = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                val read = JSONObject(third.getJSONObject(third.length() - 1).getString("content"))
                assertEquals(1, read.getJSONArray("lines").length())
                assertEquals("需要的第二行", read.getJSONArray("lines").getJSONObject(0).getString("text"))
                assertFalse(read.toString().contains("不需要的第一行"))
                val records = updates.mapNotNull { it.toolRecord }
                assertEquals(listOf("list-call", "read-call"), records.map { it.call.id })
                assertEquals(read.toString(), records.last().result)
                assertEquals(listOf(true, false, true, false), updates.mapNotNull { it.toolActive })
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test fun progressLabelsDistinguishSuccessFailureAndCancellation() {
        assertEquals("查询历史会话", MemoryTool.liveLabel("query_history"))
        assertEquals("已查询历史会话", MemoryTool.doneLabel("query_history", """{"results":[]}"""))
        assertEquals("历史会话查询失败", MemoryTool.doneLabel("query_history", """{"error":{"code":"read_failed"}}"""))
        assertEquals("历史会话查询已停止", MemoryTool.doneLabel("query_history", """{"error":{"code":"cancelled"}}"""))
        assertEquals("Queried conversation history", translate(AppLanguage.EN, "已查询历史会话"))
    }
}
