package com.irio

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ToolHistoryBoundaryTest {
    @Test fun reopenedShellResultsAreNeverSentAsAssistantProse() {
        val prose = "文件在共享存储里，我去定位两份配置再对比节点。"
        val arguments = """{"commands":["find /sdcard -name 'TAGMeta*.yaml'"],"_display_text":"定位配置文件"}"""
        val output = "$ find /sdcard -name 'TAGMeta*.yaml'\n/sdcard/TAGMeta.yaml\n/sdcard/TAGMeta1.yaml"
        val record = ChatToolRecord(ChatToolCall("shell-call", LinuxShellTool.NAME, arguments), output)
        val original = Conversation("tools", "Tools", 1L, listOf(
            ChatMessage(1L, "文件在 /storage/emulated/0/", true),
            ChatMessage(2L, prose, false, toolRecords = listOf(record), process = listOf(
                ProcessEvent(1L, ProcessEventKind.TOOL, content = output, toolName = LinuxShellTool.NAME),
            )),
        ))
        val local = ConversationStore.parseConversation(JSONObject(ConversationStore.conversationJson(original).toString()))
        val restored = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(local).toString()))
        val request = modelRequestMessages(restored.messages + ChatMessage(3L, "继续", true))
        assertEquals("History must keep tool results in their own messages", listOf("user", "assistant", "tool", "user"), request.map { it.role })
        val assistant = request.single { it.role == "assistant" }
        assertEquals(prose, assistant.text)
        assertFalse(assistant.text.contains("[Tool execution record:"))
        assertEquals(LinuxShellTool.NAME, assistant.toolCalls.single().name)
        assertFalse(assistant.toolCalls.single().arguments.contains(SmartToolHints.ARGUMENT))
        val tool = request.single { it.role == "tool" }
        assertEquals(assistant.toolCalls.single().id, tool.toolCallId)
        assertEquals(output, tool.text)
        assertEquals(prose, restored.messages[1].text)
        assertEquals(record, restored.messages[1].toolRecords.single())
    }

    @Test fun legacyAndInterruptedOutputsStayAvailableWithoutInventingToolCalls() {
        val finished = "$ echo first\nfirst"
        val partial = "$ long-command\npartial"
        val stored = ChatMessage(2L, "Original prose", false,
            toolRecords = listOf(ChatToolRecord(ChatToolCall("call", LinuxShellTool.NAME, "{}"), finished)),
            process = listOf(
                ProcessEvent(1L, ProcessEventKind.TOOL, finished, toolName = LinuxShellTool.NAME),
                ProcessEvent(2L, ProcessEventKind.TOOL, partial, toolName = LinuxShellTool.NAME),
                ProcessEvent(3L, ProcessEventKind.SEARCH, "legacy search", searchQuery = "old query"),
                ProcessEvent(4L, ProcessEventKind.THINKING, "private thought"),
                ProcessEvent(5L, ProcessEventKind.TOOL, "live output", live = true, toolName = "live_tool"),
            ))
        val request = modelRequestMessages(listOf(stored))
        assertEquals("Original prose", request.first().text)
        assertEquals(1, request.flatMap { it.toolCalls }.size)
        assertEquals(finished, request.single { it.role == "tool" }.text)
        val legacy = request.last()
        assertEquals("user", legacy.role)
        assertTrue(legacy.text.startsWith("<app_tool_context>"))
        assertTrue(legacy.text.contains(partial))
        assertTrue(legacy.text.contains("old query"))
        assertFalse(legacy.text.contains(finished))
        assertFalse(legacy.text.contains("private thought"))
        assertFalse(legacy.text.contains("live output"))
        assertTrue(request.filter { it.role == "assistant" }.all { !it.text.contains("[Tool execution record:") })
    }

    @Test fun repeatedProviderIdsAcrossSavedTurnsStillProduceUniquePairedRequestIds() {
        val messages = (1L..2L).map { id -> ChatMessage(id, "reply $id", false,
            toolRecords = listOf(ChatToolRecord(ChatToolCall("same-id", "get_time", "{}", "same-id"), "result $id"))) }
        val requests = modelRequestMessages(messages)
        val calls = requests.flatMap { it.toolCalls }
        assertEquals(2, calls.map { it.id }.toSet().size)
        assertEquals(calls.map { it.id }, requests.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals(calls.map { it.id }, calls.map { it.geminiFunctionId })
        assertTrue(messages.all { it.toolRecords.single().call.id == "same-id" })
    }

    @Test fun ordinaryJsonCodeAndLiteralMarkersAreNotFilteredFromMessages() {
        val text = "Explain this literal: `[Tool execution record: example]`\n```json\n{\"result\":42}\n```"
        val request = modelRequestMessages(listOf(ChatMessage(1L, text, true), ChatMessage(2L, text, false)))
        assertEquals(listOf(text, text), request.map { it.text })
    }

    @Test fun assistantAttachmentsAreNotDroppedByToolCallSerialization() {
        val attachment = ChatAttachment("generated.png", "image/png", "/fixture/generated.png")
        val message = ChatMessage(2L, "An attached result", false, attachments = listOf(attachment),
            toolRecords = listOf(ChatToolRecord(ChatToolCall("call", "generate_image", "{}"), "ok")))
        val request = modelRequestMessages(listOf(message))
        assertEquals(listOf(attachment), request.flatMap { it.attachments })
        assertEquals("An attached result", request.first().text)
        assertTrue("Call frames cannot carry attachments that some protocols would drop",
            request.filter { it.toolCalls.isNotEmpty() }.all { it.attachments.isEmpty() })
        assertEquals("ok", request.single { it.role == "tool" }.text)
    }

    @Test fun missingCallIdentifiersOrArgumentsUseLegacyDataWithoutFakeCalls() {
        val message = ChatMessage(2L, "Normal prose", false, toolRecords = listOf(
            ChatToolRecord(ChatToolCall("", "legacy_tool", "{}"), "output without id"),
            ChatToolRecord(ChatToolCall("id", "legacy_tool", ""), "output without arguments"),
        ))
        val request = modelRequestMessages(listOf(message))
        assertTrue(request.all { it.toolCalls.isEmpty() && it.role != "tool" })
        assertEquals("Normal prose", request.first().text)
        assertEquals("user", request.last().role)
        assertTrue(request.last().text.contains("output without id"))
        assertTrue(request.last().text.contains("output without arguments"))
    }

    @Test fun everyWireProtocolKeepsSavedToolOutputOutOfAssistantText() = runBlocking {
        val record = ChatToolRecord(ChatToolCall("saved", "linux_shell", """{"commands":["ls"],"_display_text":"定位配置"}""", "saved"), "TOOL_OUTPUT_ONLY")
        val history = modelRequestMessages(listOf(
            ChatMessage(1L, "Inspect files", true),
            ChatMessage(2L, "ASSISTANT_PROSE_ONLY", false, toolRecords = listOf(record)),
            ChatMessage(3L, "Continue", true),
        ))
        val callId = history.single { it.toolCalls.isNotEmpty() }.toolCalls.single().id
        IrioHttp.resetForTests()
        try {
            MockWebServer().use { server ->
                for (protocol in ProviderProtocol.entries) {
                    server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
                    ChatApi.streamChat(ProviderConfig("test", "Test", server.url("/v1beta").toString(), "key", protocol),
                        "model", history, ReasoningEffort.OFF, false, toolRegistry = ToolRegistry(),
                        cancellation = ChatRequestCancellation(), onUpdate = {})
                    val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                    assertFalse("$protocol must not send UI-only arguments", body.toString().contains(SmartToolHints.ARGUMENT))
                    assertFalse("$protocol must not put serialized records in model prose", body.toString().contains("[Tool execution record:"))
                    when (protocol) {
                        ProviderProtocol.CHAT_COMPLETIONS -> {
                            val messages = body.getJSONArray("messages")
                            val assistant = messages.getJSONObject(1)
                            assertEquals("ASSISTANT_PROSE_ONLY", assistant.getString("content"))
                            assertEquals(callId, assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
                            val output = messages.getJSONObject(2)
                            assertEquals("tool", output.getString("role"))
                            assertEquals(callId, output.getString("tool_call_id"))
                            assertEquals(record.result, output.getString("content"))
                        }
                        ProviderProtocol.RESPONSES -> {
                            val input = body.getJSONArray("input")
                            val call = (0 until input.length()).map { input.getJSONObject(it) }.single { it.optString("type") == "function_call" }
                            val output = (0 until input.length()).map { input.getJSONObject(it) }.single { it.optString("type") == "function_call_output" }
                            assertEquals(call.getString("call_id"), output.getString("call_id"))
                            assertEquals(record.result, output.getString("output"))
                        }
                        ProviderProtocol.ANTHROPIC -> {
                            val messages = body.getJSONArray("messages")
                            val assistant = messages.getJSONObject(1).getJSONArray("content")
                            val call = (0 until assistant.length()).map { assistant.getJSONObject(it) }.single { it.optString("type") == "tool_use" }
                            val text = (0 until assistant.length()).map { assistant.getJSONObject(it) }.single { it.optString("type") == "text" }
                            assertEquals("ASSISTANT_PROSE_ONLY", text.getString("text"))
                            val output = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
                            assertEquals("tool_result", output.getString("type"))
                            assertEquals(call.getString("id"), output.getString("tool_use_id"))
                            assertEquals(record.result, output.getString("content"))
                        }
                        ProviderProtocol.GEMINI -> {
                            val contents = body.getJSONArray("contents")
                            val parts = contents.getJSONObject(1).getJSONArray("parts")
                            val call = (0 until parts.length()).map { parts.getJSONObject(it) }.single { it.has("functionCall") }.getJSONObject("functionCall")
                            val output = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
                            assertEquals(call.getString("id"), output.getString("id"))
                            assertEquals(record.result, output.getJSONObject("response").getString("result"))
                        }
                        ProviderProtocol.GEMINI_INTERACTIONS -> {
                            val input = body.getJSONArray("input")
                            val call = (0 until input.length()).map { input.getJSONObject(it) }.single { it.optString("type") == "function_call" }
                            val output = (0 until input.length()).map { input.getJSONObject(it) }.single { it.optString("type") == "function_result" }
                            assertEquals(call.getString("id"), output.getString("call_id"))
                            assertEquals(record.result, output.getJSONArray("result").getJSONObject(0).getString("text"))
                        }
                    }
                }
            }
        } finally { IrioHttp.resetForTests() }
    }
}
