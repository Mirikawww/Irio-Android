package com.irio

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolFailureStatusTest {
    private val config = ProviderConfig("fixture", "Fixture", "https://example.test", "fixture")

    private suspend fun runCall(provider: ToolProvider, name: String, args: String = "{}",
        configure: (ToolExecutionContext) -> ToolExecutionContext = { it }): Pair<ToolExecutionResult, List<ProcessEvent>> {
        val turn = SubagentTurn()
        val result = ToolRegistry(listOf(provider), smartToolHints = true).execute(
            ChatToolCall("call", name, args),
            configure(ToolExecutionContext(config, "model", onUpdate = { turn.apply(it) })),
        )
        return result to turn.process()
    }

    @Test fun unavailableToolsAndMalformedMcpCallsProduceOneFailedRow() = runBlocking {
        val tool = McpTool("test", "Test", JSONObject("""{"type":"object"}"""))
        val cases = listOf(
            WebSearchToolProvider() to "web_search",
            WebFetchToolProvider() to "web_fetch",
            CommonsImageToolProvider() to "image_search",
            CommonsImageToolProvider() to "view_images",
            ImageGenToolProvider() to "generate_image",
            GitHubMcpToolProvider(listOf(tool)) { _, _ -> error("must not invoke") } to "github_mcp_test",
            TodoistMcpToolProvider(listOf(tool)) { _, _ -> error("must not invoke") } to "todoist_mcp_test",
        )
        cases.forEach { (provider, name) ->
            val (result, events) = runCall(provider, name, "not-json")
            assertEquals(name, ToolOutcome.FAILED, result.outcome)
            assertEquals(name, 1, events.size)
            assertEquals(name, ToolOutcome.FAILED, events.single().toolOutcome)
            assertFalse(events.single().live)
            assertTrue(name, events.single().content.isNotBlank())
        }
    }

    @Test fun unavailableMemoryNeverClaimsItWasReadOrWritten() = runBlocking {
        for (name in listOf(MemoryTool.GET, MemoryTool.UPDATE)) {
            val (result, events) = runCall(MemoryToolProvider(), name)
            assertEquals(ToolOutcome.FAILED, result.outcome)
            assertEquals(ToolOutcome.FAILED, events.single().toolOutcome)
            assertTrue(events.single().content.contains("不可用"))
        }
    }

    @Test fun unexpectedExceptionAfterAProviderFinallyStillClosesAsFailed() = runBlocking {
        val provider = object : ToolProvider {
            override val id = "throwing"
            override fun handles(name: String) = name == "get_time"
            override fun definitions(protocol: ProviderProtocol) = listOf(TimeTool.schemaFor(protocol))
            override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
                context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = call.name))
                try { error("fixture failure") } finally {
                    context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = call.name))
                }
            }
        }
        val (result, events) = runCall(provider, "get_time", """{"_display_text":"读取成功"}""")
        assertEquals(ToolOutcome.FAILED, result.outcome)
        assertEquals(1, events.size)
        assertEquals(ToolOutcome.FAILED, events.single().toolOutcome)
        assertTrue(events.single().content.contains("fixture failure"))
    }

    @Test fun stoppedMcpAndMissingSubagentNamesHaveExplicitTerminalStates() = runBlocking {
        val tool = McpTool("test", "Test", JSONObject("""{"type":"object"}"""))
        val (result, events) = runCall(GitHubMcpToolProvider(listOf(tool)) { _, _ -> "ok" }, "github_mcp_test") {
            it.copy(cancellation = ChatRequestCancellation().apply { cancel() })
        }
        assertEquals(ToolOutcome.STOPPED, result.outcome)
        assertEquals(ToolOutcome.STOPPED, events.single().toolOutcome)
        for (name in SubagentTools.NAMES) {
            val (missing, rows) = runCall(SubagentToolProvider(), name, """{"name":""}""")
            assertEquals(name, ToolOutcome.FAILED, missing.outcome)
            assertEquals(name, ToolOutcome.FAILED, rows.single().toolOutcome)
        }
    }

    @Test fun mergedModePreservesFailuresAndImageGenerationRows() {
        val failed = ProcessEvent(1, ProcessEventKind.TOOL, "permission denied", toolName = "web_fetch", toolOutcome = ToolOutcome.FAILED)
        val ok = ProcessEvent(2, ProcessEventKind.TOOL, toolName = "get_time", toolOutcome = ToolOutcome.SUCCESS)
        val image = ProcessEvent(3, ProcessEventKind.TOOL, "image failed", toolName = "generate_image", toolOutcome = ToolOutcome.FAILED)
        val rendered = renderedProcessEvents(listOf(failed, ok, image), true)
        assertTrue(rendered.any { it == image })
        assertTrue(rendered.any { it == failed })
        assertFalse(rendered.first { it.toolName == MERGED_TOOL_CALLS_NAME }.content.contains("网页"))
        assertTrue(rendered.first { it.toolName == MERGED_TOOL_CALLS_NAME }.content.contains("时间"))
    }

    @Test fun failureStateSurvivesDiskLinkAndSubagentRoundTrips() {
        val event = ProcessEvent(1, ProcessEventKind.TOOL, "denied", toolName = "update_memory", toolOutcome = ToolOutcome.FAILED)
        val conversation = Conversation("fixture", "Fixture", 1, listOf(ChatMessage(1, "", false, process = listOf(event))))
        val disk = ConversationStore.parseConversation(JSONObject(ConversationStore.conversationJson(conversation).toString()))
        val linked = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(disk).toString()))
        assertEquals(event, linked.messages.single().process.single())
        assertEquals(event, subagentProcessFrom(subagentProcessJson(listOf(event))).single())
    }

    @Test fun failureAndStopOverrideSmartLabelsInRowsAndNotificationsButSuccessKeepsThem() {
        val failed = ProcessEvent(1, ProcessEventKind.TOOL, "denied", toolName = MemoryTool.UPDATE,
            displayText = "写入成功", toolOutcome = ToolOutcome.FAILED)
        assertEquals("写入记忆失败", failed.completedLabel())
        val update = ChatStreamUpdate(toolActive = false, toolName = MemoryTool.UPDATE,
            displayText = "写入成功", toolOutcome = ToolOutcome.FAILED)
        assertEquals("写入记忆失败", advanceReplyNotice("", update))
        assertTrue(failed.copy(toolOutcome = ToolOutcome.STOPPED).completedLabel().contains("已停止"))
        assertEquals("写入成功", failed.copy(toolOutcome = ToolOutcome.SUCCESS).completedLabel())
        val search = failed.copy(kind = ProcessEventKind.SEARCH)
        assertEquals("搜索失败", search.completedLabel())
        assertEquals("搜索已停止", search.copy(toolOutcome = ToolOutcome.STOPPED).completedLabel())
        assertEquals("搜索失败", advanceReplyNotice("", ChatStreamUpdate(searching = false,
            displayText = "已搜索", toolOutcome = ToolOutcome.FAILED)))
    }

    @Test fun remoteMcpErrorsKeepDetailsAndNeverClaimSuccess() = runBlocking {
        for (github in listOf(true, false)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}}"""))
                server.enqueue(MockResponse().setResponseCode(202))
                server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[{"type":"text","text":"permission denied fixture-secret"}]}}"""))
                val tool = McpTool("create_task", "Create", JSONObject("""{"type":"object"}"""))
                val provider = if (github) {
                    val client = GitHubMcpClient("fixture-secret", endpoint = server.url("/mcp"))
                    GitHubMcpToolProvider(listOf(tool)) { name, args -> client.callTool(name, args) }
                } else {
                    val client = TodoistMcpClient("fixture-secret", endpoint = server.url("/mcp"))
                    TodoistMcpToolProvider(listOf(tool)) { name, args -> client.callTool(name, args) }
                }
                val name = (if (github) GitHubMcpToolProvider.PREFIX else TodoistMcpToolProvider.PREFIX) + tool.name
                val (result, events) = runCall(provider, name)
                assertEquals(ToolOutcome.FAILED, result.outcome)
                assertEquals(ToolOutcome.FAILED, events.single().toolOutcome)
                assertTrue(events.single().content.contains("permission denied"))
                assertFalse(events.single().content.contains("fixture-secret"))
                assertEquals(MemoryTool.liveLabel(name) + "失败", events.single().completedLabel())
            }
        }
    }

    @Test fun structuredMcpBatchFailuresAreFailuresButOrdinaryErrorWordsAreNot() {
        assertTrue(mcpToolFailed(JSONObject("""{"structuredContent":{"failureCount":1,"successCount":2}}""")))
        assertTrue(mcpToolFailed(JSONObject("""{"content":[{"type":"text","text":"{\"failures\":[{\"error\":\"denied\"}]}"}]}""")))
        assertFalse(mcpToolFailed(JSONObject("""{"content":[{"type":"text","text":"The document discusses failures and errors"}]}""")))
        assertFalse(mcpToolFailed(JSONObject("""{"structuredContent":{"failures":[],"failureCount":0}}""")))
    }

    @Test fun mcpStatusLabelsAppendTheOutcomeWithoutAColon() {
        assertEquals("创建 Todoist 任务失败", toolFailureLabel("todoist_mcp_add-tasks"))
        assertEquals("创建 GitHub 仓库失败", toolFailureLabel("github_mcp_create_repository"))
        assertEquals("创建 Todoist 任务已停止", toolStoppedLabel("todoist_mcp_add-tasks"))
        assertEquals("创建 GitHub 仓库已停止", toolStoppedLabel("github_mcp_create_repository"))
    }

    @Test fun invalidUrlAndMissingQueryFailWithoutExternalNetworkRequests() = runBlocking {
        val (fetched, fetchRows) = runCall(WebFetchToolProvider(), "web_fetch", """{"url":"not-a-url"}""") {
            it.copy(webSearch = true, searchApiKey = "fixture")
        }
        assertEquals(ToolOutcome.FAILED, fetched.outcome)
        assertEquals("浏览网页失败", fetchRows.single().completedLabel())
        for (searchProvider in SearchProvider.entries) {
            val (searched, rows) = runCall(WebSearchToolProvider(), "web_search", "") {
                it.copy(webSearch = true, searchApiKey = "fixture", searchProvider = searchProvider)
            }
            assertEquals(ToolOutcome.FAILED, searched.outcome)
            assertEquals("搜索失败", rows.single().completedLabel())
        }
    }

    @Test fun quietToolsAreSilentOnSuccessButTheirValidationErrorsAreVisible() = runBlocking {
        val (bubbles, bubbleRows) = runCall(ColoredBubbleToolProvider(), ColoredBubbleTool.NAME,
            """{"bubbles":[{"text":"hello","color":"#fff"}]}""")
        assertEquals(ToolOutcome.SUCCESS, bubbles.outcome)
        assertEquals(ProcessEventKind.BUBBLE, bubbleRows.single().kind)
        for ((provider, name) in listOf(ReactionToolProvider() to ReactionTool.NAME,
            ColoredBubbleToolProvider() to ColoredBubbleTool.NAME,
            CommonsImageToolProvider() to CommonsImageTools.SHOW)) {
            val (result, events) = runCall(provider, name, "invalid")
            assertEquals(ToolOutcome.FAILED, result.outcome)
            assertEquals(ToolOutcome.FAILED, events.single().toolOutcome)
        }
    }

    @Test fun coroutineCancellationClosesTheExistingRowWithoutAddingDuplicates() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val provider = object : ToolProvider {
            override val id = "cancel"
            override fun definitions(protocol: ProviderProtocol) = listOf(LinuxShellTool.schemaFor(protocol))
            override fun handles(name: String) = name == LinuxShellTool.NAME
            override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
                context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = call.name, toolOutput = "partial shell output"))
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val turn = SubagentTurn()
        val job = launch {
            ToolRegistry(listOf(provider)).execute(ChatToolCall("cancel", LinuxShellTool.NAME, "{}"),
                ToolExecutionContext(config, "model", onUpdate = { turn.apply(it) }))
        }
        started.await()
        job.cancel()
        job.join()
        assertEquals(ToolOutcome.STOPPED, turn.process().single().toolOutcome)
        assertTrue(turn.process().single().content.contains("partial shell output"))
        assertFalse(turn.process().single().live)
    }

    @Test fun unavailableShellAndBackgroundErrorsKeepExpandableContent() = runBlocking {
        val (shell, shellRows) = runCall(LinuxShellToolProvider(), LinuxShellTool.NAME, """{"commands":["false"]}""")
        assertEquals(ToolOutcome.FAILED, shell.outcome)
        assertTrue(shellRows.single().content.contains("不可用"))
        val (background, rows) = runCall(LinuxShellToolProvider(backgroundEnabled = true), LinuxBackgroundTool.NAME,
            """{"action":"status","id":"missing"}""")
        assertEquals(ToolOutcome.FAILED, background.outcome)
        assertTrue(rows.single().content.contains("没有这条"))
    }

    @Test fun repeatedCallsRemainSeparateAndUnexpectedShellFailuresKeepPartialOutput() = runBlocking {
        val partial = "$ long-command\npartial output"
        val provider = object : ToolProvider {
            override val id = "shell-failure"
            override fun definitions(protocol: ProviderProtocol) = listOf(LinuxShellTool.schemaFor(protocol))
            override fun handles(name: String) = name == LinuxShellTool.NAME
            override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
                context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = call.name, toolOutput = partial))
                try { error("fixture failure") } finally {
                    context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = call.name, toolContent = partial))
                }
            }
        }
        val registry = ToolRegistry(listOf(provider))
        val turn = SubagentTurn()
        repeat(2) { index ->
            registry.execute(ChatToolCall("$index", LinuxShellTool.NAME, "{}"),
                ToolExecutionContext(config, "model", onUpdate = { turn.apply(it) }))
        }
        assertEquals(2, turn.process().size)
        assertTrue(turn.process().all { it.toolOutcome == ToolOutcome.FAILED &&
            it.content.contains(partial) && it.content.contains("fixture failure") })
    }
}
