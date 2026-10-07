package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ToolContextApiTest {
    @Test
    fun allRegisteredToolsSurviveReopeningAndReachEveryProtocolsNextRequest() = runBlocking {
        val builtIns = ToolRegistry.default().register(MemoryToolProvider())
            .register(LinuxShellToolProvider(backgroundEnabled = true))
        val names = builtIns.providers().flatMap { it.toolNames() }.sorted()
        val calls = names.mapIndexed { index, name ->
            ChatToolCall("call-$index", name, JSONObject().put("fixture", "argument-$index").toString())
        }
        val fixtureProvider = object : ToolProvider {
            override val id = "context-fixture"
            override fun definitions(protocol: ProviderProtocol) = builtIns.definitions(protocol)
            override fun handles(name: String) = builtIns.handles(name)
            override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
                // 只测完整的客户端调用/存储链路，不调用真实搜索、生图或手机 Shell。
                if (call.name == LinuxBackgroundTool.NAME) error("fixture-tool-failure")
                return ToolExecutionResult("fixture-result:${call.name}")
            }
        }
        val frame = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("tool_calls", JSONArray().apply {
                calls.forEachIndexed { index, call ->
                    put(JSONObject().put("index", index).put("id", call.id).put("function",
                        JSONObject().put("name", call.name).put("arguments", call.arguments)))
                }
            }))
            .put("finish_reason", "tool_calls")))
        IrioHttp.resetForTests(IrioHttp.defaultClient())
        try {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: $frame\n\ndata: [DONE]\n\n"))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val provider = ProviderConfig("fixture", "fixture", server.url("/v1").toString(), "fixture-key")
                val records = mutableListOf<ChatToolRecord>()
                val result = ChatApi.streamChat(
                    provider, "model", listOf(ChatRequestMessage("user", "run")), ReasoningEffort.OFF, false,
                    maxToolRounds = 4, toolRegistry = ToolRegistry(listOf(fixtureProvider)),
                    cancellation = ChatRequestCancellation(), onUpdate = { it.toolRecord?.let(records::add) },
                )
                assertEquals(null, result.error)
                assertEquals(calls, records.map { it.call })
                assertTrue(records.single { it.call.name == LinuxBackgroundTool.NAME }.result.contains("fixture-tool-failure"))
                server.takeRequest(2, TimeUnit.SECONDS)!!
                val continued = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                records.forEachIndexed { index, record ->
                    assertEquals(record.result, continued.getJSONObject(continued.length() - records.size + index).getString("content"))
                }
                val original = Conversation("fixture", "fixture", 1L, listOf(
                    ChatMessage(1L, "run", true), ChatMessage(2L, "done", false, toolRecords = records),
                ))
                val local = ConversationStore.parseConversation(JSONObject(ConversationStore.conversationJson(original).toString()))
                val reopened = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(local).toString()))
                assertEquals(records, reopened.messages[1].toolRecords)
                val messages = modelRequestMessages(contextMessages(reopened.messages) + ChatMessage(3L, "what happened?", true))
                val historyText = messages[1].text
                records.forEach { assertTrue(historyText.contains(it.call.arguments)); assertTrue(historyText.contains(it.result)) }
                assertEquals(historyText, summaryTurns(planCompression(reopened.messages)!!)[1].text)
                ProviderProtocol.entries.forEach { protocol ->
                    server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""))
                    ChatApi.streamChat(
                        provider.copy(protocol = protocol), "model", messages, ReasoningEffort.OFF, false,
                        toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(), onUpdate = {},
                    )
                    val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                    val payload = when (protocol) {
                        ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.ANTHROPIC -> body.getJSONArray("messages")
                        ProviderProtocol.RESPONSES, ProviderProtocol.GEMINI_INTERACTIONS -> body.getJSONArray("input")
                        ProviderProtocol.GEMINI -> body.getJSONArray("contents")
                    }.toString()
                    records.forEach { record ->
                        assertTrue("$protocol omitted ${record.call.name}", payload.contains(record.call.name))
                        assertTrue("$protocol omitted the output", payload.contains(record.result))
                    }
                    assertFalse(body.toString().contains("toolRecords"))
                }
            }
        } finally {
            IrioHttp.resetForTests()
        }
    }
}
