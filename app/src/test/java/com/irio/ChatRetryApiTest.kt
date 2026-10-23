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

    @Test fun partialStreamErrorResetsOnlyItsAttemptBeforeRetry() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: {\"error\":{\"message\":\"temporary upstream failure\"}}\n\n"))
        server.enqueue(sse("replacement"))
        val turn = SubagentTurn()
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = ChatApi.streamChat(provider(), "m", listOf(ChatRequestMessage("user", "hi")), ReasoningEffort.OFF, false,
            toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(),
            retryOptions = ChatRetryOptions(cooldownSeconds = 0), onUpdate = { updates += it; turn.apply(it) })
        assertNull(result.error)
        assertEquals("replacement", turn.text())
        assertTrue(updates.any { it.delta == "partial" })
        assertTrue(updates.any { it.resetAttempt })
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
