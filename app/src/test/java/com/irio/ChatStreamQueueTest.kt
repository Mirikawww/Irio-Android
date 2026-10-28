package com.irio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ChatStreamQueueTest {
    @After fun resetClient() { IrioHttp.resetForTests() }

    @Test(timeout = 10_000)
    fun aCancelledConsumerCannotSilentlyLoseQueuedUpdatesOrRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(textFrame("first") + "data: [DONE]\n\n"))
            val failure = runCatching {
                ChatApi.streamChat(
                    provider = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), ""),
                    model = "m", messages = listOf(ChatRequestMessage("user", "hello")),
                    reasoningEffort = ReasoningEffort.OFF, fastMode = false,
                    toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(),
                    onUpdate = { throw CancellationException("consumer cancelled") },
                )
            }.exceptionOrNull()
            assertTrue("Consumer cancellation must propagate, not produce a successful reply", failure is CancellationException)
            assertEquals("consumer cancelled", failure?.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test(timeout = 30_000)
    fun slowUpdatesDoNotBlockNetworkReadsAndTheResultWaitsForDelivery() = runBlocking {
        for (protocol in ProviderProtocol.entries) verifySlowConsumer(protocol)
    }

    @Test(timeout = 15_000)
    fun stoppingWhileDrainingTheQueueCannotReturnASuccessfulReply() = runBlocking {
        verifySlowConsumer(ProviderProtocol.CHAT_COMPLETIONS, stopAfterRead = true)
    }

    @Test(timeout = 10_000)
    fun callbackFailureAbortsAnOpenNetworkReadInsteadOfRetrying() = runBlocking {
        MockWebServer().use { server ->
            val body = textFrame("first")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(body).setHeader("Content-Length", body.toByteArray().size + 1_000))
            val failure = runCatching {
                ChatApi.streamChat(
                    provider = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), ""),
                    model = "m", messages = listOf(ChatRequestMessage("user", "hello")),
                    reasoningEffort = ReasoningEffort.OFF, fastMode = false,
                    toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(),
                    retryOptions = ChatRetryOptions(cooldownSeconds = 0),
                    onUpdate = { if (it.delta.isNotEmpty()) throw java.io.IOException("callback failed") },
                )
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals("callback failed", failure?.message)
            assertEquals(1, server.requestCount)
        }
    }

    private suspend fun verifySlowConsumer(protocol: ProviderProtocol, stopAfterRead: Boolean = false) = coroutineScope {
        MockWebServer().use { server ->
            val receivedBody = CompletableDeferred<Unit>()
            IrioHttp.resetForTests(IrioHttp.defaultClient().newBuilder()
                .eventListener(object : EventListener() {
                    override fun responseBodyEnd(call: Call, byteCount: Long) {
                        receivedBody.complete(Unit)
                    }
                }).build())
            val tail = "tail".repeat(16_384)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                textFrame("first", protocol) + textFrame(tail, protocol) + endFrames(protocol),
            ))
            val enteredUpdate = CompletableDeferred<Unit>()
            val releaseUpdate = CompletableDeferred<Unit>()
            val updates = mutableListOf<ChatStreamUpdate>()
            val cancellation = ChatRequestCancellation()
            val request = async(Dispatchers.IO) {
                ChatApi.streamChat(
                    provider = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "", protocol = protocol),
                    model = "m", messages = listOf(ChatRequestMessage("user", "hello")),
                    reasoningEffort = ReasoningEffort.OFF, fastMode = false,
                    toolRegistry = ToolRegistry(), cancellation = cancellation,
                    onUpdate = { update ->
                        if (update.delta == "first") {
                            enteredUpdate.complete(Unit)
                            releaseUpdate.await()
                        }
                        updates += update
                    },
                )
            }
            try {
                withTimeout(5_000) { enteredUpdate.await() }
                assertNotNull("$protocol: network reads must continue while message processing is suspended",
                    withTimeoutOrNull(2_000) { receivedBody.await() })
                assertFalse("Completion must wait for all queued updates", request.isCompleted)
                if (stopAfterRead) cancellation.cancel()
                releaseUpdate.complete(Unit)
                val result = withTimeout(5_000) { request.await() }
                assertNull(result.error)
                if (stopAfterRead) {
                    assertTrue(result.stopped)
                    return@use
                }
                assertEquals("first$tail", result.content)
                assertEquals(listOf("first", tail), updates.map { it.delta }.filter { it.isNotEmpty() })
                assertEquals(TokenUsage(10, 20, 30), updates.last().usage)
                assertTrue(updates.last().done)
                assertEquals(1, updates.count { it.done })
            } finally {
                releaseUpdate.complete(Unit)
                cancellation.cancel()
                request.cancelAndJoin()
            }
        }
    }

    private fun textFrame(text: String, protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS): String {
        val quoted = JSONObject.quote(text)
        val payload = when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> "{\"choices\":[{\"delta\":{\"content\":$quoted}}]}"
            ProviderProtocol.RESPONSES -> "{\"type\":\"response.output_text.delta\",\"delta\":$quoted}"
            ProviderProtocol.ANTHROPIC -> "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":$quoted}}"
            ProviderProtocol.GEMINI -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":$quoted}]}}]}"
            ProviderProtocol.GEMINI_INTERACTIONS -> "{\"event_type\":\"step.delta\",\"delta\":{\"type\":\"text\",\"text\":$quoted}}"
        }
        return "data: $payload\n\n"
    }

    private fun endFrames(protocol: ProviderProtocol) = when (protocol) {
        ProviderProtocol.CHAT_COMPLETIONS ->
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}\n\n" +
                "data: [DONE]\n\n"
        ProviderProtocol.RESPONSES ->
            "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}}}\n\n"
        ProviderProtocol.ANTHROPIC ->
            "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}\n\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n"
        ProviderProtocol.GEMINI ->
            "data: {\"candidates\":[{\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":20,\"totalTokenCount\":30}}\n\n"
        ProviderProtocol.GEMINI_INTERACTIONS ->
            "data: {\"event_type\":\"interaction.completed\",\"interaction\":{\"usage\":{\"total_input_tokens\":10,\"total_output_tokens\":20,\"total_tokens\":30}}}\n\n"
    }
}
