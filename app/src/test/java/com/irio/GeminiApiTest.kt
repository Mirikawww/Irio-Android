package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiApiTest {
    private fun protocol() = ProviderProtocol.valueOf("GEMINI")

    @Test
    fun surfacesSafetyBlocksAndStructuredStreamErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                val provider = ProviderConfig("p", "Google", server.url("/v1beta").toString(), "fixture", protocol())
                for (payload in listOf(
                    """{"promptFeedback":{"blockReason":"SAFETY"}}""",
                    """{"candidates":[{"finishReason":"MALFORMED_FUNCTION_CALL"}]}""",
                    """{"error":{"code":429,"message":"fixture quota exhausted"}}""",
                )) {
                    server.enqueue(MockResponse().setBody("data: $payload\n\n"))
                    val result = ChatApi.streamChat(provider, "gemini-2.5-flash", listOf(ChatRequestMessage("user", "hi")),
                        ReasoningEffort.AUTO, false, cancellation = ChatRequestCancellation(), onUpdate = {})
                    assertNotNull(result.error)
                    val error = result.error.orEmpty()
                    assertTrue(error.contains("SAFETY") || error.contains("MALFORMED_FUNCTION_CALL") || error == "fixture quota exhausted")
                }
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test
    fun titleAndSummaryUseNonStreamingNativeEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"private thought","thought":true},{"text":"result"}]},"finishReason":"STOP"}]}""")) }
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                val provider = ProviderConfig("p", "Google", server.url("/v1beta/models").toString(), "fixture", protocol())
                assertEquals("result", ChatApi.generateTitle(provider, "models/gemini-2.5-flash", "hi", "hello", "chat#title"))
                assertEquals("result", ChatApi.summarizeContext(provider, "gemini-2.5-flash", "summarize", listOf(ChatRequestMessage("user", "hi")), "chat"))
                repeat(2) {
                    val request = server.takeRequest()
                    assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", request.path)
                    assertEquals("fixture", request.getHeader("x-goog-api-key"))
                    val body = JSONObject(request.body.readUtf8())
                    assertFalse(body.has("tools"))
                    assertFalse(body.has("prompt_cache_key"))
                }
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test
    fun executesParallelFunctionsAndReplaysOriginalSignedParts() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"checking\",\"thought\":true,\"thoughtSignature\":\"thought-sig\"},{\"functionCall\":{\"id\":\"wire-id\",\"name\":\"get_time\",\"args\":{}},\"thoughtSignature\":\"call-sig\"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"get_time\",\"args\":{}}}]},\"finishReason\":\"STOP\"}]}\n\n"))
            server.enqueue(MockResponse().setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]},\"finishReason\":\"STOP\"}]}\n\n"))
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                val provider = ProviderConfig("p", "Google", server.url("/v1beta").toString(), "fixture", protocol())
                val result = ChatApi.streamChat(provider, "gemini-3-flash-preview", listOf(ChatRequestMessage("user", "time")),
                    ReasoningEffort.HIGH, true, cancellation = ChatRequestCancellation(), onUpdate = {},
                    toolRegistry = ToolRegistry(listOf(TimeToolProvider(), MemoryToolProvider(), LinuxShellToolProvider(backgroundEnabled = true))))
                assertNull(result.error)
                assertEquals("done", result.content)
                val first = JSONObject(server.takeRequest().body.readUtf8())
                val declarations = first.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
                assertEquals(setOf(TimeTool.NAME, MemoryTool.GET, MemoryTool.UPDATE, LinuxShellTool.NAME, LinuxBackgroundTool.NAME),
                    (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }.toSet())
                assertTrue(declarations.getJSONObject(0).has("parametersJsonSchema"))
                assertFalse(first.has("service_tier"))
                val second = JSONObject(server.takeRequest().body.readUtf8())
                val contents = second.getJSONArray("contents")
                assertEquals(3, contents.length())
                val signed = contents.getJSONObject(1).getJSONArray("parts")
                assertEquals("thought-sig", signed.getJSONObject(0).getString("thoughtSignature"))
                assertEquals("call-sig", signed.getJSONObject(1).getString("thoughtSignature"))
                assertEquals(3, signed.length())
                val responses = contents.getJSONObject(2).getJSONArray("parts")
                assertEquals(2, responses.length())
                assertEquals("wire-id", responses.getJSONObject(0).getJSONObject("functionResponse").getString("id"))
                assertFalse(responses.getJSONObject(1).getJSONObject("functionResponse").has("id"))
                assertTrue(responses.getJSONObject(0).getJSONObject("functionResponse").getJSONObject("response").getString("result").isNotBlank())
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test
    fun streamsNativeContentWithSystemInstructionAndGoogleAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"thinking\",\"thought\":true},{\"text\":\"hello\"}]},\"finishReason\":\"STOP\"}],\"modelVersion\":\"gemini-fixture\",\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"thoughtsTokenCount\":4,\"totalTokenCount\":9}}\n\n"
            ))
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                val updates = mutableListOf<ChatStreamUpdate>()
                val result = ChatApi.streamChat(
                    provider = ProviderConfig("p", "Google", server.url("/v1beta").toString(), "fixture", protocol()),
                    model = "gemini-2.5-flash", messages = listOf(ChatRequestMessage("user", "hi")),
                    reasoningEffort = ReasoningEffort.AUTO, fastMode = false, systemPrompt = "be concise",
                    connectionScope = "fixture-conversation", cancellation = ChatRequestCancellation(), onUpdate = { updates += it },
                )
                assertNull(result.error)
                assertEquals("hello", result.content)
                assertEquals("thinking", result.thinking)
                assertEquals("gemini-fixture", result.model)
                assertEquals(TokenUsage(3, 6, 9), result.usage)
                val request = server.takeRequest()
                assertEquals("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse", request.path)
                assertEquals("fixture", request.getHeader("x-goog-api-key"))
                assertNull(request.getHeader("Authorization"))
                val body = JSONObject(request.body.readUtf8())
                assertEquals(ToolRegistry.default().systemPrompt("be concise"), body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
                assertEquals("user", body.getJSONArray("contents").getJSONObject(0).getString("role"))
                assertFalse(body.has("stream"))
                assertFalse(body.has("model"))
                assertFalse(body.has("prompt_cache_key"))
                assertTrue(updates.any { it.delta == "hello" })
            } finally { IrioHttp.resetForTests() }
        }
    }
}
