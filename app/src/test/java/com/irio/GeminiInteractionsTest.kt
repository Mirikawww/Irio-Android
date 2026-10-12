package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiInteractionsTest {
    private fun protocol() = ProviderProtocol.valueOf("GEMINI_INTERACTIONS")

    @Test
    fun streamingUsesNativeRequestAndTerminalUsage() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                server.enqueue(MockResponse().setBody("""
                    data: {"event_type":"interaction.created","interaction":{"id":"i1","status":"in_progress","model":"actual-model"}}

                    data: {"event_type":"step.start","index":0,"step":{"type":"model_output"}}

                    data: {"event_type":"step.delta","index":0,"delta":{"type":"text","text":"Hello"}}

                    data: {"event_type":"step.stop","index":0}

                    data: {"event_type":"interaction.completed","interaction":{"id":"i1","status":"completed","usage":{"total_input_tokens":8,"total_output_tokens":2,"total_thought_tokens":3,"total_tokens":13}}}

                """.trimIndent()))
                val provider = ProviderConfig("p", "Google", server.url("/v1beta/interactions").toString(), "fixture", protocol())
                val updates = mutableListOf<ChatStreamUpdate>()
                val result = ChatApi.streamChat(provider, "gemini-3-flash-preview", listOf(ChatRequestMessage("user", "hi")),
                    ReasoningEffort.HIGH, false, systemPrompt = "system", cancellation = ChatRequestCancellation(), onUpdate = { updates += it })
                assertNull(result.error)
                assertEquals("Hello", result.content)
                assertEquals("actual-model", result.model)
                assertEquals(TokenUsage(8, 5, 13), result.usage)
                assertEquals("Hello", updates.joinToString("") { it.delta })
                val request = server.takeRequest()
                assertEquals("/v1beta/interactions?alt=sse", request.path)
                assertEquals("fixture", request.getHeader("x-goog-api-key"))
                assertNull(request.getHeader("Authorization"))
                val body = JSONObject(request.body.readUtf8())
                assertEquals("gemini-3-flash-preview", body.getString("model"))
                assertTrue(body.getBoolean("stream"))
                assertFalse(body.getBoolean("store"))
                assertEquals(ToolRegistry.default().systemPrompt("system"), body.getString("system_instruction"))
                assertEquals("user_input", body.getJSONArray("input").getJSONObject(0).getString("type"))
                assertEquals("high", body.getJSONObject("generation_config").getString("thinking_level"))
                val tools = body.getJSONArray("tools")
                assertEquals("function", tools.getJSONObject(0).getString("type"))
                assertTrue(tools.getJSONObject(0).has("parameters"))
                assertFalse(body.has("contents"))
                assertFalse(body.has("prompt_cache_key"))
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test
    fun protocolsAreSeparateAndEndpointsNormalize() {
        assertEquals("Google Gemini generateContent API", ProviderProtocol.GEMINI.label)
        assertEquals("Google Gemini Interactions API", protocol().label)
        assertEquals(ProviderProtocol.GEMINI, ProviderProtocol.valueOf("GEMINI"))
        for (suffix in listOf("", "/v1beta", "/v1beta/interactions?alt=sse", "/v1beta/models/gemini-3-flash-preview:generateContent")) {
            val provider = ProviderConfig("p", "Google", "https://proxy.example/gemini$suffix", "", protocol())
            assertEquals("https://proxy.example/gemini/v1beta/interactions?alt=sse", ProviderEndpoints.chatUrl(provider, "gemini-3-flash-preview"))
        }
        val provider = ProviderConfig("p", "Google", "https://proxy.example/gemini/v1beta", "", protocol())
        assertEquals("https://proxy.example/gemini/v1beta/interactions", ProviderEndpoints.chatUrl(provider, "gemini-3.1-pro-preview", streaming = false))
    }

    @Test
    fun streamingParsesFunctionCallLifecycle() {
        val parser = GeminiInteractionsProtocol.StreamParser()
        val calls = mutableMapOf<String, MutableToolCall>()
        val start = parser.parse("""{"event_type":"step.start","index":2,"step":{"type":"function_call","id":"call-1","name":"get_time","arguments":{}}}""")!!
        ChatProtocol.applyToolCallDeltas(calls, start.toolCallDeltas)
        val args = parser.parse("""{"event_type":"step.delta","index":2,"delta":{"type":"arguments_delta","arguments":"{}"}}""")!!
        ChatProtocol.applyToolCallDeltas(calls, args.toolCallDeltas)
        val stop = parser.parse("""{"event_type":"step.stop","index":2}""")!!
        assertEquals("function_call", stop.finishReason)
        assertEquals("call-1", ChatProtocol.completedToolCalls(calls).single().id)
        assertEquals("get_time", ChatProtocol.completedToolCalls(calls).single().name)
    }
}
