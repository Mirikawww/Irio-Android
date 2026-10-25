package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MermaidToolTest {
    private val source = "flowchart LR\n  A[开始] --> B[结束]"

    @Test fun agentRegistryAdvertisesMermaidAcrossAllProtocols() {
        val registry = ToolRegistry.default()
        assertTrue("The agent must receive an executable render_mermaid tool, not just a Markdown renderer", registry.handles("render_mermaid"))
        assertTrue(registry.systemPrompt(null)!!.contains("render_mermaid"))
        for (protocol in ProviderProtocol.entries) {
            assertTrue("$protocol must advertise the Mermaid tool", registry.definitions(protocol).any {
                (it.optJSONObject("function") ?: it).optString("name") == "render_mermaid"
            })
        }
    }

    @Test fun successfulCallsEmitOnlyTheDiagramAndKeepSmartHintsOutOfTheSchema() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val registry = ToolRegistry(listOf(MermaidToolProvider()), smartToolHints = true)
        val context = context(updates)
        repeat(2) { index ->
            val result = registry.execute(ChatToolCall("diagram-$index", MermaidTool.NAME,
                JSONObject().put("source", source).toString()), context)
            assertEquals(ToolOutcome.SUCCESS, result.outcome)
            assertTrue(result.modelAttachments.isEmpty())
        }
        assertEquals(listOf(source, source), updates.map { it.displayedMermaid })
        assertTrue(updates.all { it.toolActive == null && it.searching == null })
        for (protocol in ProviderProtocol.entries) {
            assertFalse(registry.definitions(protocol).single().toString().contains("_display_text"))
        }
    }

    @Test fun invalidUnsupportedAndCancelledCallsNeverPublishADiagram() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val registry = ToolRegistry(listOf(MermaidToolProvider()))
        for (arguments in listOf("{}", "{broken", JSONObject().put("source", " ").toString(),
            JSONObject().put("source", "x".repeat(MermaidTool.MAX_SOURCE_LENGTH + 1)).toString(),
            JSONObject().put("source", "flowchart LR\n A[unfinished").toString(),
            JSONObject().put("source", "flowchart-elk LR\n A --> B").toString())) {
            val result = registry.execute(ChatToolCall("bad", MermaidTool.NAME, arguments), context(updates))
            assertEquals(arguments.take(60), ToolOutcome.FAILED, result.outcome)
        }
        val cancellation = ChatRequestCancellation().also { it.cancel() }
        val result = registry.execute(ChatToolCall("cancelled", MermaidTool.NAME,
            JSONObject().put("source", source).toString()), context(updates).copy(cancellation = cancellation))
        assertEquals(ToolOutcome.STOPPED, result.outcome)
        assertTrue(updates.none { it.displayedMermaid.isNotEmpty() })
        assertTrue(updates.any { it.toolOutcome == ToolOutcome.FAILED })
    }

    @Test fun diagramsStayAnchoredAndSurviveHistoryAndSubagentPersistence() {
        val tracker = ProcessTracker()
        tracker.onOutputStarted(textLength = 6)
        tracker.onDisplayedMermaid(source)
        tracker.finishAll()
        val event = tracker.snapshot().single()
        assertEquals(6, event.textOffset)
        assertEquals(ToolOutcome.SUCCESS, event.toolOutcome)
        assertEquals(listOf(TranscriptPiece.Text("Before"), TranscriptPiece.Event(event), TranscriptPiece.Text("After")),
            interleaveTranscript("BeforeAfter", listOf(event)))
        assertTrue(hasReplyContent("", emptyList(), listOf(event)))
        val merged = renderedProcessEvents(listOf(event, ProcessEvent(9, ProcessEventKind.TOOL,
            toolName = TimeTool.NAME, content = "time")), mergeToolCalls = true)
        assertEquals(event, merged.single { it.toolName == MermaidTool.NAME })

        val conversation = Conversation("mermaid", "Diagram", 1,
            listOf(ChatMessage(1, "BeforeAfter", false, process = listOf(event))))
        val restored = ConversationStore.parseConversation(JSONObject(ConversationStore.conversationJson(conversation).toString()))
        assertEquals(event, restored.messages.single().process.single())
        val turn = SubagentTurn()
        turn.apply(ChatStreamUpdate(delta = "Before"))
        assertTrue(turn.apply(ChatStreamUpdate(displayedMermaid = source)))
        turn.apply(ChatStreamUpdate(delta = "After"))
        turn.finish()
        assertEquals(event, turn.process().single())
        assertEquals(turn.process(), subagentProcessFrom(subagentProcessJson(turn.process())))
        assertTrue(ToolRegistry.default().withoutSubagents().handles(MermaidTool.NAME))
    }

    @Test fun disabledToolIsRemovedFromDefinitionsInstructionsAndExecution() = runBlocking {
        val registry = ToolRegistry(listOf(MermaidToolProvider()),
            preferences = ToolPreferences(disabledTools = setOf(MermaidTool.NAME)))
        assertFalse(registry.handles(MermaidTool.NAME))
        assertNull(registry.systemPrompt(null))
        assertTrue(ProviderProtocol.entries.all { registry.definitions(it).isEmpty() })
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = registry.execute(ChatToolCall("disabled", MermaidTool.NAME,
            JSONObject().put("source", source).toString()), context(updates))
        assertEquals(ToolOutcome.FAILED, result.outcome)
        assertTrue(updates.none { it.displayedMermaid.isNotEmpty() })
    }

    @Test fun actualModelToolCallCanFinishWithOnlyADiagramAndNoEmptyResponseError() = runBlocking {
        MockWebServer().use { server ->
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                val arguments = JSONObject().put("source", source)
                val delta = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("delta", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
                        .put("index", 0).put("id", "diagram-1").put("function", JSONObject()
                            .put("name", MermaidTool.NAME).put("arguments", arguments.toString())))))
                    .put("finish_reason", "tool_calls")))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: $delta\n\ndata: [DONE]\n\n"))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val updates = mutableListOf<ChatStreamUpdate>()
                val result = ChatApi.streamChat(ProviderConfig("p", "P", server.url("/v1").toString(), "fixture"),
                    "model", listOf(ChatRequestMessage("user", "画一个流程图")), ReasoningEffort.OFF, false,
                    toolRegistry = ToolRegistry(listOf(MermaidToolProvider())),
                    cancellation = ChatRequestCancellation(), onUpdate = { updates += it })
                assertNull(result.error)
                assertTrue(result.content.isEmpty())
                assertEquals(listOf(source), updates.map { it.displayedMermaid }.filter(String::isNotEmpty))
                assertTrue(updates.none { it.toolActive != null || it.searching != null })
                assertEquals(1, updates.count { it.toolRecord != null })
                val first = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals(MermaidTool.NAME, first.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
                assertTrue(first.toString().contains("Mermaid diagrams are available through render_mermaid"))
                val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                assertEquals("diagram-1", second.getJSONObject(second.length() - 1).getString("tool_call_id"))
            } finally { IrioHttp.resetForTests() }
        }
    }

    private fun context(updates: MutableList<ChatStreamUpdate>) = ToolExecutionContext(
        ProviderConfig("fixture", "Fixture", "https://example.test", ""), "model", onUpdate = { updates += it })
}
