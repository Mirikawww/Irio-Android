package com.irio

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SmartToolHintsTest {
    @Test
    fun serializedRequestsIncludePromptRulesOnlyWhenEnabledAcrossAllProtocols() = runBlocking {
        IrioHttp.resetForTests(IrioHttp.defaultClient())
        try {
            MockWebServer().use { server ->
                ProviderProtocol.entries.forEach { protocol ->
                    listOf(false, true).forEach { enabled ->
                        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""))
                        ChatApi.streamChat(
                            ProviderConfig("fixture", "Fixture", server.url("/v1beta").toString(), "fixture", protocol),
                            "model", listOf(ChatRequestMessage("user", "hi")), ReasoningEffort.OFF, false,
                            systemPrompt = "Existing system prompt",
                            toolRegistry = ToolRegistry(listOf(TimeToolProvider()), smartToolHints = enabled),
                            cancellation = ChatRequestCancellation(), onUpdate = {},
                        )
                        val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                        val prompt = when (protocol) {
                            ProviderProtocol.CHAT_COMPLETIONS -> body.getJSONArray("messages").getJSONObject(0).getString("content")
                            ProviderProtocol.RESPONSES -> body.getString("instructions")
                            ProviderProtocol.ANTHROPIC -> body.getString("system")
                            ProviderProtocol.GEMINI -> body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
                            ProviderProtocol.GEMINI_INTERACTIONS -> body.getString("system_instruction")
                        }
                        assertTrue(prompt.contains("Existing system prompt"))
                        assertEquals("$protocol prompt", enabled, prompt.contains(SmartToolHints.INSTRUCTIONS))
                        assertEquals("$protocol tool schema", enabled, body.getJSONArray("tools").toString().contains("_display_text"))
                    }
                }
            }
        } finally { IrioHttp.resetForTests() }
    }

    @Test
    fun streamedCallReachesTheTrackerAndRetainsItsOriginalHistory() = runBlocking {
        MockWebServer().use { server ->
            val args = JSONObject().put("_display_text", "查询当地时间").toString()
            val frame = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                .put("delta", JSONObject().put("tool_calls", org.json.JSONArray().put(JSONObject()
                    .put("index", 0).put("id", "time-1").put("function", JSONObject().put("name", TimeTool.NAME).put("arguments", args)))))
                .put("finish_reason", "tool_calls")))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $frame\n\ndata: [DONE]\n\n"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val tracker = ProcessTracker()
            val records = mutableListOf<ChatToolRecord>()
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                val result = ChatApi.streamChat(
                    ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture"),
                    "model", listOf(ChatRequestMessage("user", "time")), ReasoningEffort.OFF, false,
                    toolRegistry = ToolRegistry(listOf(TimeToolProvider()), smartToolHints = true),
                    cancellation = ChatRequestCancellation(), onUpdate = { update ->
                        if (update.toolActive == true) tracker.onToolStarted(update.toolName, update.webDomain, update.displayText)
                        if (update.toolActive == false) tracker.onToolFinished(update.toolContent)
                        update.toolRecord?.let(records::add)
                    },
                )
                assertNull(result.error)
                assertEquals("done", result.content)
                assertEquals("查询当地时间", tracker.snapshot().single().displayText)
                assertFalse(tracker.snapshot().single().live)
                assertEquals(args, records.single().call.arguments)
                server.takeRequest(2, TimeUnit.SECONDS)!!
                val continuation = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
                assertTrue(continuation.contains("查询当地时间"))
                assertTrue(continuation.contains("当前时间"))
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test
    fun trackerPreservesCustomTextOnFinishedEventsWithoutLeakingToTheNextCall() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(TimeTool.NAME, displayText = "查询当前时间")
        tracker.onToolOutput("output")
        tracker.onToolStarted(TimeTool.NAME, displayText = "查询当前时间")
        tracker.onToolFinished("time")
        assertEquals("查询当前时间", tracker.snapshot().single().displayText)
        assertFalse(tracker.snapshot().single().live)
        tracker.onToolStarted(TimeTool.NAME)
        tracker.onToolFinished()
        assertEquals("", tracker.snapshot().last().displayText)
        tracker.onSearchStarted("query", "查询最新资料")
        tracker.onSearchFinished(3, "search results")
        assertEquals("查询最新资料", tracker.snapshot().last().displayText)
        assertEquals("search results", tracker.snapshot().last().content)
    }

    @Test
    fun customTextSurvivesBothConversationJsonWritersAndLegacyDefaults() {
        val conversation = Conversation(id = "test", title = "Test", updatedAt = 0, messages = listOf(ChatMessage(
            1, "reply", false, process = listOf(ProcessEvent(1, ProcessEventKind.TOOL, displayText = "读取项目文件")),
        )))
        val restored = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(conversation).toString()))
        assertEquals("读取项目文件", restored.messages.single().process.single().displayText)
        val disk = ConversationStore.conversationJson(conversation)
        assertEquals("读取项目文件", processEventsFrom(disk.getJSONArray("messages").getJSONObject(0)).single().displayText)
        val old = processEventsFrom(JSONObject("""{"process":[{"id":1,"kind":"TOOL"}]}""")).single()
        assertEquals("", old.displayText)
    }

    @Test
    fun uiMetadataNeverReachesRemoteToolsEvenWhenHintsAreDisabled() = runBlocking {
        var received: JSONObject? = null
        val remote = GitHubMcpToolProvider(listOf(McpTool("echo", "Echo", JSONObject("""{"type":"object","properties":{}}""")))) { _, args ->
            received = args
            "result"
        }
        val registry = ToolRegistry(listOf(remote))
        val result = registry.execute(
            ChatToolCall("one", "github_mcp_echo", """{"value":1,"_display_text":"读取文件"}"""),
            ToolExecutionContext(ProviderConfig("test", "Test", "https://example.test", "fixture"), "model"),
        )
        assertEquals("result", result.text)
        val arguments = requireNotNull(received)
        assertEquals(1, arguments.getInt("value"))
        assertFalse("UI metadata leaked to MCP", arguments.has("_display_text"))
    }

    @Test
    fun eachCallCarriesItsOwnUnvalidatedDisplayTextOnProgressUpdates() = runBlocking {
        val registry = ToolRegistry(listOf(TimeToolProvider()), smartToolHints = true)
        val updates = mutableListOf<ChatStreamUpdate>()
        val context = ToolExecutionContext(
            ProviderConfig("test", "Test", "https://example.test", "fixture"), "model",
            onUpdate = { updates += it },
        )
        // Deliberately violates the prompt: the client must not enforce character rules.
        val text = "这是超过十个字符的文案！🙂"
        registry.execute(ChatToolCall("one", TimeTool.NAME, JSONObject().put("_display_text", text).toString()), context)
        registry.execute(ChatToolCall("two", TimeTool.NAME, "{}"), context)
        assertEquals(listOf(text, text, "", ""), updates.map { it.displayText })
        val disabledUpdates = mutableListOf<ChatStreamUpdate>()
        ToolRegistry(listOf(TimeToolProvider())).execute(
            ChatToolCall("three", TimeTool.NAME, """{"_display_text":"获取时间"}"""),
            context.copy(onUpdate = { disabledUpdates += it }),
        )
        assertTrue(disabledUpdates.all { it.displayText.isEmpty() })
    }

    @Test
    fun searchProgressAlsoUsesThePerCallDisplayText() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val fixture = object : ToolProvider {
            override val id = "search-fixture"
            override fun definitions(protocol: ProviderProtocol) = listOf(WebSearchTool.schemaFor(protocol))
            override fun handles(name: String) = name == WebSearchTool.NAME
            override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
                assertEquals("kotlin", JSONObject(call.arguments).getString("query"))
                assertFalse(JSONObject(call.arguments).has("_display_text"))
                context.onUpdate(ChatStreamUpdate(searching = true, searchQuery = "kotlin"))
                context.onUpdate(ChatStreamUpdate(searching = false, searchContent = "fixture"))
                return ToolExecutionResult("fixture")
            }
        }
        ToolRegistry(listOf(fixture), smartToolHints = true).execute(
            ChatToolCall("one", WebSearchTool.NAME, """{"query":"kotlin","_display_text":"查找语言文档"}"""),
            ToolExecutionContext(ProviderConfig("test", "Test", "https://example.test", "fixture"), "model", onUpdate = { updates += it }),
        )
        assertEquals(listOf("查找语言文档", "查找语言文档"), updates.map { it.displayText })
    }

    @Test
    fun enabledRegistryAdvertisesTheHintAcrossEveryProtocolWithoutChangingProviderSchemas() {
        val original = ToolRegistry.default().register(MemoryToolProvider())
            .register(LinuxShellToolProvider(backgroundEnabled = true))
            .register(GitHubMcpToolProvider(listOf(McpTool("echo", "Echo", JSONObject("""{"type":"object","properties":{}}""")))) { _, _ -> "fixture" })
        val registry = ToolRegistry(original.providers(), smartToolHints = true)
        ProviderProtocol.entries.forEach { protocol ->
            registry.schemas(protocol).forEach schemaLoop@{ schema ->
                val definition = schema.optJSONObject("function") ?: schema
                val parameters = definition.optJSONObject("input_schema")
                    ?: definition.optJSONObject("parametersJsonSchema")
                    ?: definition.getJSONObject("parameters")
                val hint = parameters.optJSONObject("properties")?.optJSONObject("_display_text")
                if (definition.optString("name") == CommonsImageTools.SHOW) {
                    assertNull("Silent image output must not advertise a tool status hint", hint)
                    return@schemaLoop
                }
                assertNotNull("Missing UI hint for $protocol: $schema", hint)
                assertEquals("string", hint!!.getString("type"))
                assertTrue(hint.getString("description").contains("10"))
                assertTrue(hint.getString("description").contains("each CJK character counts as one"))
                assertTrue(hint.getString("description").contains("Do not use punctuation"))
                assertFalse(hint.has("maxLength"))
                assertFalse(hint.has("pattern"))
                if (definition.optBoolean("strict")) {
                    assertTrue(parameters.getJSONArray("required").toString().contains("_display_text"))
                }
            }
            original.schemas(protocol).forEach { schema ->
                assertFalse("Disabled registry changed: $schema", schema.toString().contains("_display_text"))
            }
        }
    }
}
