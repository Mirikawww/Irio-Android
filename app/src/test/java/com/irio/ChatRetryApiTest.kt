package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatRetryApiTest {
    private val server = MockWebServer()
    @Before fun setup() { server.start(); IrioHttp.resetForTests() }
    @After fun cleanup() { server.shutdown(); IrioHttp.resetForTests() }
    private fun provider() = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture")
    private fun sse(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

    @Test fun serverFailureRetriesCurrentRoundWithoutReexecutingTools() = runBlocking {
        var executions = 0
        val fixture = object : ToolProvider {
            override val id = "fixture"
            override fun definitions(protocol: ProviderProtocol) = emptyList<JSONObject>()
            override fun handles(name: String) = name == "fixture"
            override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
                executions++
                return ToolExecutionResult("executed once")
            }
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"t1\",\"function\":{\"name\":\"fixture\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"busy\"}}"))
        server.enqueue(sse("done"))
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = ChatApi.streamChat(provider(), "m", listOf(ChatRequestMessage("user", "run")), ReasoningEffort.OFF, false,
            toolRegistry = ToolRegistry(listOf(fixture)), cancellation = ChatRequestCancellation(),
            retryOptions = ChatRetryOptions(cooldownSeconds = 0), onUpdate = { updates += it })
        assertNull(result.error)
        assertEquals("done", result.content)
        assertEquals(1, executions)
        assertEquals(3, server.requestCount)
        server.takeRequest()
        val failed = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").toString()
        val retried = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").toString()
        assertEquals(failed, retried)
        assertTrue(retried.contains("executed once"))
        assertEquals(listOf(1), updates.mapNotNull { it.retry }.filter { it.running }.map { it.attempt })
        assertTrue(updates.any { it.retryCleared })
    }

    @Test fun partialStreamErrorContinuesWithoutClearingThePartialAttempt() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: {\"error\":{\"message\":\"temporary upstream failure\"}}\n\n"))
        server.enqueue(sse("replacement"))
        val turn = SubagentTurn()
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = ChatApi.streamChat(provider(), "m", listOf(ChatRequestMessage("user", "hi")), ReasoningEffort.OFF, false,
            toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(),
            retryOptions = ChatRetryOptions(cooldownSeconds = 0), onUpdate = { updates += it; turn.apply(it) })
        assertNull(result.error)
        assertEquals("partialreplacement", turn.text())
        assertTrue(updates.any { it.delta == "partial" })
        assertTrue(updates.any { it.retry?.running == true })
        assertTrue(updates.none { it.resetAttempt })
    }

    @Test fun repeatedPartialStreamFailuresAppendHiddenContinuationsAndPreserveTheWholeReply() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"first part\"}}]}\n\ndata: {\"error\":{\"message\":\"temporary upstream failure\"}}\n\n"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"second part\"}}]}\n\ndata: {\"error\":{\"message\":\"temporary upstream failure again\"}}\n\n"))
        server.enqueue(sse("last part"))
        val original = listOf(ChatRequestMessage(role = "user", text = "hi"))
        val result = ChatApi.streamChat(
            provider(), "m", original, ReasoningEffort.OFF, false,
            cancellation = ChatRequestCancellation(),
            retryOptions = ChatRetryOptions(maxRetries = 3, cooldownSeconds = 0),
            onUpdate = {},
        )
        assertNull(result.error)
        assertEquals("first partsecond partlast part", result.content)
        assertEquals(3, server.requestCount)
        val firstBody = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val secondBody = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val thirdBody = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val prompt = STREAM_CONTINUATION_PROMPT
        fun messages(body: org.json.JSONArray) = (0 until body.length()).map { body.getJSONObject(it) }
        fun atPrompt(body: org.json.JSONArray) = messages(body).indexOfFirst { message ->
            message.optString("content") == prompt || message.optJSONArray("content")?.let { parts ->
                (0 until parts.length()).any { parts.getJSONObject(it).optString("text") == prompt }
            } == true
        }
        val firstPromptIndex = atPrompt(firstBody)
        val secondPromptIndex = atPrompt(secondBody)
        val thirdPromptIndex = atPrompt(thirdBody)
        assertEquals(-1, firstPromptIndex)
        assertTrue("Continuation prompt missing from first retry: $secondBody", secondPromptIndex >= 0)
        assertEquals(secondPromptIndex, thirdPromptIndex)
        fun assistantText(body: org.json.JSONArray): String {
            val assistant = messages(body).last { it.optString("role") == "assistant" }
            return when (val content = assistant.opt("content")) {
                is String -> content
                is org.json.JSONArray -> (0 until content.length()).joinToString("") { content.getJSONObject(it).optString("text") }
                null -> ""
                else -> ""
            }
        }
        assertFalse(messages(firstBody).any { it.optString("role") == "assistant" })
        assertEquals("first part", assistantText(secondBody))
        assertEquals("second part", assistantText(thirdBody))
        assertEquals(2, messages(thirdBody).count { message ->
            message.optString("content") == prompt || message.optJSONArray("content")?.let { parts ->
                (0 until parts.length()).any { parts.getJSONObject(it).optString("text") == prompt }
            } == true
        })
    }

    @Test fun emptyHiddenRequestFailureResendsIdenticalMessagesAndNeverResetsVisibleText() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello, how can\"}}]}\n\ndata: {\"error\":{\"message\":\"upstream lost\"}}\n\n"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"busy\"}}"))
        server.enqueue(sse(" I help?"))
        val turn = SubagentTurn()
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = ChatApi.streamChat(provider(), "m", listOf(ChatRequestMessage("user", "hi")), ReasoningEffort.OFF, false,
            toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(),
            retryOptions = ChatRetryOptions(cooldownSeconds = 0), onUpdate = { updates += it; turn.apply(it) })
        assertNull(result.error)
        assertEquals("Hello, how can I help?", result.content)
        assertEquals(result.content, turn.text())
        assertTrue(updates.none { it.resetAttempt })
        server.takeRequest()
        val hidden = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val retried = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals(hidden.toString(), retried.toString())
        assertEquals(STREAM_CONTINUATION_PROMPT, hidden.getJSONObject(hidden.length() - 1).getString("content"))
    }

    @Test fun thinkingOnlyOrIncompleteToolOutputStillGetsAHiddenContinuationAndIsNotExecuted() = runBlocking {
        val fragments = listOf(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"working it out\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"unfinished\",\"function\":{\"name\":\"fixture\",\"arguments\":\"{\\\"x\\\":\"}}]}}]}\n\n",
        )
        var executions = 0
        val tools = ToolRegistry(listOf(object : ToolProvider {
            override val id = "fixture"
            override fun definitions(protocol: ProviderProtocol) = emptyList<JSONObject>()
            override fun handles(name: String) = name == "fixture"
            override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
                executions++
                return ToolExecutionResult("unexpected")
            }
        }))
        for (fragment in fragments) {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                fragment + "data: {\"error\":{\"message\":\"connection dropped\"}}\n\n"))
            server.enqueue(sse("finished"))
            val updates = mutableListOf<ChatStreamUpdate>()
            val result = ChatApi.streamChat(provider(), "m", listOf(ChatRequestMessage("user", "hi")), ReasoningEffort.OFF, false,
                toolRegistry = tools, cancellation = ChatRequestCancellation(), retryOptions = ChatRetryOptions(cooldownSeconds = 0),
                onUpdate = { updates += it })
            assertNull(result.error)
            assertEquals("finished", result.content)
            assertTrue(updates.none { it.resetAttempt })
            server.takeRequest()
            val continued = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals(STREAM_CONTINUATION_PROMPT, continued.getJSONObject(continued.length() - 1).getString("content"))
            assertFalse("Incomplete tool arguments must not be replayed as a completed call", continued.toString().contains("unfinished"))
            assertFalse("Partial thoughts stay in the UI, not an unsigned thinking block in provider input", continued.toString().contains("working it out"))
        }
        assertEquals(0, executions)
    }

    @Test fun everyChatProtocolRetainsTextAndResendsAnEmptyHiddenRequestWithoutAnotherNudge() = runBlocking {
        for (protocol in ProviderProtocol.entries) {
            val partial = when (protocol) {
                ProviderProtocol.CHAT_COMPLETIONS -> "data: {\"choices\":[{\"delta\":{\"content\":\"Hello, how can\"}}]}\n\n"
                ProviderProtocol.RESPONSES -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello, how can\"}\n\n"
                ProviderProtocol.ANTHROPIC -> "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello, how can\"}}\n\n"
                ProviderProtocol.GEMINI -> "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello, how can\"}]}}]}\n\n"
                ProviderProtocol.GEMINI_INTERACTIONS -> "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"text\",\"text\":\"Hello, how can\"}}\n\n"
            }
            val error = when (protocol) {
                ProviderProtocol.RESPONSES -> "data: {\"type\":\"error\",\"message\":\"temporary connection drop\"}\n\n"
                ProviderProtocol.GEMINI_INTERACTIONS -> "data: {\"event_type\":\"error\",\"error\":{\"message\":\"temporary connection drop\"}}\n\n"
                else -> "event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"temporary connection drop\"}}\n\n"
            }
            val final = when (protocol) {
                ProviderProtocol.CHAT_COMPLETIONS -> "data: {\"choices\":[{\"delta\":{\"content\":\" I help?\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                ProviderProtocol.RESPONSES -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\" I help?\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n"
                ProviderProtocol.ANTHROPIC -> "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\" I help?\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                ProviderProtocol.GEMINI -> "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" I help?\"}]},\"finishReason\":\"STOP\"}]}\n\n"
                ProviderProtocol.GEMINI_INTERACTIONS -> "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"text\",\"text\":\" I help?\"}}\n\ndata: {\"event_type\":\"interaction.completed\",\"interaction\":{}}\n\n"
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(partial + error))
            server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"busy\"}}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(final))
            val original = listOf(ChatRequestMessage("user", "hello"))
            val turn = SubagentTurn()
            val result = ChatApi.streamChat(provider().copy(protocol = protocol), "m", original, ReasoningEffort.OFF, false,
                toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(),
                retryOptions = ChatRetryOptions(cooldownSeconds = 0), onUpdate = { turn.apply(it) })
            assertNull("$protocol: ${result.error}", result.error)
            assertEquals(protocol.name, "Hello, how can I help?", result.content)
            assertEquals(protocol.name, result.content, turn.text())
            server.takeRequest()
            val hidden = server.takeRequest().body.readUtf8()
            assertTrue(protocol.name, hidden.contains(STREAM_CONTINUATION_PROMPT))
            assertEquals(protocol.name, hidden, server.takeRequest().body.readUtf8())
            assertEquals(listOf(ChatRequestMessage("user", "hello")), original)
        }
    }

    @Test fun cleanEofWithoutACompletionMarkerContinuesRatherThanAcceptingTruncatedText() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello, how can\"}}]}\n\n"))
        server.enqueue(sse(" I help?"))
        val result = ChatApi.streamChat(provider(), "m", listOf(ChatRequestMessage("user", "hello")), ReasoningEffort.OFF, false,
            toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(),
            retryOptions = ChatRetryOptions(cooldownSeconds = 0), onUpdate = {})
        assertNull(result.error)
        assertEquals("Hello, how can I help?", result.content)
        assertEquals(2, server.requestCount)
    }

    @Test fun disablingAutoRetryDoesNotSendAContinuationRequest() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: {\"error\":{\"message\":\"temporary upstream failure\"}}\n\n"))
        val result = ChatApi.streamChat(
            provider(), "m", listOf(ChatRequestMessage(role = "user", text = "hi")),
            ReasoningEffort.OFF, false, cancellation = ChatRequestCancellation(),
            retryOptions = ChatRetryOptions(enabled = false), onUpdate = {},
        )
        assertEquals("partial", result.partialStreamText)
        assertEquals(1, server.requestCount)
    }

    @Test fun disabledOptionAndAuthenticationFailureNeverReplayTheRequest() = runBlocking {
        listOf(503 to false, 401 to true).forEach { (status, enabled) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"error\":{\"message\":\"failure\"}}"))
            val result = ChatApi.streamChat(provider(), "m", listOf(ChatRequestMessage("user", "hi")), ReasoningEffort.OFF, false,
                cancellation = ChatRequestCancellation(), retryOptions = ChatRetryOptions(enabled = enabled, cooldownSeconds = 0), onUpdate = {})
            assertEquals("failure", result.error)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun protocolDowngradeIsVisibleOnceAndUsesTheSameRetryBudget() = runBlocking {
        var attempts = 0
        IrioHttp.resetForTests(IrioHttp.defaultClient().newBuilder().addInterceptor { chain ->
            attempts++
            if (attempts <= 2) throw java.io.IOException("stream was reset: PROTOCOL_ERROR")
            chain.proceed(chain.request())
        }.build())
        server.enqueue(sse("done"))
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = ChatApi.streamChat(provider(), "m", listOf(ChatRequestMessage("user", "hi")), ReasoningEffort.OFF, false,
            connectionScope = "retry-downgrade", cancellation = ChatRequestCancellation(),
            retryOptions = ChatRetryOptions(maxRetries = 2, cooldownSeconds = 0), onUpdate = { updates += it })
        assertNull(result.error)
        assertEquals(3, attempts)
        assertEquals(listOf(true, false), updates.mapNotNull { it.retry }.filter { it.running }.map { it.downgradedToHttp1 })
        assertEquals(listOf(okhttp3.Protocol.HTTP_1_1), IrioHttp.clientFor("retry-downgrade").protocols)
    }

    @Test fun imageRequestsAlsoDistinguishTransientServerFailuresFromBadCredentials() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"busy\"}}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key\"}}"))
        assertTrue(ProviderApi.generateImage(provider(), "fixture-image", "fixture prompt").retryable)
        assertFalse(ProviderApi.generateImage(provider(), "fixture-image", "fixture prompt").retryable)
        assertEquals(2, server.requestCount)
    }
}
