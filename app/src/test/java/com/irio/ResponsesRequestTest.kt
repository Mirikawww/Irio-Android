package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ResponsesRequestTest {
    private val server = MockWebServer()

    @Before fun setUp() {
        server.start()
        IrioHttp.resetForTests(IrioHttp.defaultClient())
    }

    @After fun tearDown() {
        server.shutdown()
        IrioHttp.resetForTests()
    }

    @Test
    fun assistantHistoryUsesOutputTextWithSmartHintsEnabledOrDisabled() = runBlocking {
        server.dispatcher = validatingDispatcher()
        for (enabled in listOf(false, true)) {
            val result = ChatApi.streamChat(
                provider(), "fixture-model", history(), ReasoningEffort.LOW, false,
                toolRegistry = ToolRegistry(listOf(TimeToolProvider()), smartToolHints = enabled),
                cancellation = ChatRequestCancellation(), onUpdate = {},
            )
            assertNull(result.error)
            assertEquals("done", result.content)
            val body = requestBody()
            assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
            assertHistory(body.getJSONArray("input"))
            assertEquals(enabled, body.optString("instructions").contains(SmartToolHints.INSTRUCTIONS))
        }
    }

    @Test
    fun contextSummaryUsesTheSameRoleAwareContentTypes() = runBlocking {
        server.dispatcher = validatingDispatcher()
        val result = ChatApi.summarizeContext(provider(), "fixture-model", "Summarize", history())
        assertEquals("summary", result)
        val body = requestBody()
        assertFalse(body.getBoolean("stream"))
        assertHistory(body.getJSONArray("input"))
    }

    @Test
    fun smartHintToolContinuationRetainsValidHistoryAndLinkedToolResults() = runBlocking {
        val arguments = JSONObject().put("_display_text", "查询当地时间").toString()
        val call = JSONObject().put("type", "function_call").put("id", "item-time")
            .put("call_id", "call-time").put("name", TimeTool.NAME).put("arguments", arguments)
        val event = JSONObject().put("type", "response.output_item.added").put("item", call)
        server.dispatcher = validatingDispatcher(firstStream = "data: $event\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n")
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = ChatApi.streamChat(
            provider(), "fixture-model", history(), ReasoningEffort.LOW, false,
            toolRegistry = ToolRegistry(listOf(TimeToolProvider()), smartToolHints = true),
            cancellation = ChatRequestCancellation(), onUpdate = { updates += it },
        )
        assertNull(result.error)
        assertEquals("done", result.content)
        assertHistory(requestBody().getJSONArray("input"))
        val continuation = requestBody()
        assertHistory(continuation.getJSONArray("input"))
        assertEquals("low", continuation.getJSONObject("reasoning").getString("effort"))
        assertTrue(continuation.getString("instructions").contains(SmartToolHints.INSTRUCTIONS))
        val input = continuation.getJSONArray("input")
        val forwardedCall = input.getJSONObject(input.length() - 2)
        assertEquals("function_call", forwardedCall.getString("type"))
        assertEquals("call-time", forwardedCall.getString("call_id"))
        assertEquals(arguments, forwardedCall.getString("arguments"))
        val output = input.getJSONObject(input.length() - 1)
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call-time", output.getString("call_id"))
        val record = updates.mapNotNull { it.toolRecord }.single()
        assertEquals(arguments, record.call.arguments)
        assertEquals(record.result, output.getString("output"))
        assertTrue(updates.any { it.toolActive == true && it.displayText == "查询当地时间" })
    }

    @Test
    fun toolContinuationKeepsPreCallAssistantText() = runBlocking {
        val call = JSONObject().put("type", "function_call").put("id", "item-time")
            .put("call_id", "call-time").put("name", TimeTool.NAME).put("arguments", "{}")
        val firstStream = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n" +
            "data: {\"type\":\"response.output_item.added\",\"item\":$call}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{}}\n\n"
        server.dispatcher = validatingDispatcher(firstStream = firstStream)
        val result = ChatApi.streamChat(
            provider(), "fixture-model", listOf(ChatRequestMessage("user", "now")), ReasoningEffort.OFF, false,
            toolRegistry = ToolRegistry(listOf(TimeToolProvider())),
            cancellation = ChatRequestCancellation(), onUpdate = {},
        )
        assertNull(result.error)
        server.takeRequest(2, TimeUnit.SECONDS)!!
        val continuation = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("input")
        val texts = buildList {
            for (index in 0 until continuation.length()) {
                val item = continuation.getJSONObject(index)
                if (item.optString("role") != "assistant") continue
                val content = item.optJSONArray("content") ?: continue
                for (partIndex in 0 until content.length()) {
                    add(content.getJSONObject(partIndex).optString("text"))
                }
            }
        }
        assertTrue("pre-tool assistant text must reach the continuation round", texts.any { it.contains("partial") })
    }

    private fun provider() = ProviderConfig(
        "fixture", "Fixture", server.url("/v1").toString(), "fixture-key", ProviderProtocol.RESPONSES,
    )

    private fun history() = listOf(
        ChatRequestMessage("system", "system context"),
        ChatRequestMessage("developer", "developer context"),
        ChatRequestMessage("user", "previous question"),
        ChatRequestMessage("assistant", "previous answer"),
        ChatRequestMessage("assistant", ""),
        ChatRequestMessage("user", "current question"),
    )

    private fun requestBody(): JSONObject {
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/v1/responses", request.path)
        return JSONObject(request.body.readUtf8())
    }

    private fun assertHistory(input: JSONArray) {
        history().forEachIndexed { index, message ->
            val item = input.getJSONObject(index)
            assertEquals(message.role, item.getString("role"))
            val content = item.getJSONArray("content")
            assertEquals(1, content.length())
            assertEquals(if (message.role == "assistant") "output_text" else "input_text", content.getJSONObject(0).getString("type"))
            assertEquals(message.text, content.getJSONObject(0).getString("text"))
        }
    }

    private fun validatingDispatcher(firstStream: String? = null) = object : Dispatcher() {
        private var streamCount = 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = JSONObject(request.body.clone().readUtf8())
            val input = body.getJSONArray("input")
            for (index in 0 until input.length()) {
                val item = input.getJSONObject(index)
                if (item.optString("role") != "assistant") continue
                val content = item.getJSONArray("content")
                for (partIndex in 0 until content.length()) {
                    val type = content.getJSONObject(partIndex).getString("type")
                    if (type !in listOf("output_text", "refusal")) {
                        val message = "Invalid value: '$type'. Supported values are: 'output_text' and 'refusal'"
                        return MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                            .setBody(JSONObject().put("error", JSONObject().put("message", message)).toString())
                    }
                }
            }
            if (!body.getBoolean("stream")) {
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"output":[{"type":"message","content":[{"type":"output_text","text":"summary"}]}]}""")
            }
            val stream = if (streamCount++ == 0 && firstStream != null) firstStream else
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"done\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n"
            return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream)
        }
    }
}
