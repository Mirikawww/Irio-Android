package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatApiOkHttpTest {
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
    fun everyExecutedToolPublishesItsResultForPersistentContext() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"function\":{\"name\":\"unknown_tool\",\"arguments\":\"{\\\"command\\\":\\\"echo hello\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n",
        ))
        server.enqueue(sse("done"))
        val updates = mutableListOf<ChatStreamUpdate>()
        val provider = ProviderConfig("p", "t", server.url("/v1").toString(), "k", protocol = ProviderProtocol.CHAT_COMPLETIONS)
        val result = ChatApi.streamChat(
            provider = provider, model = "m", messages = listOf(ChatRequestMessage("user", "run")),
            reasoningEffort = ReasoningEffort.OFF, fastMode = false, toolRegistry = ToolRegistry(),
            cancellation = ChatRequestCancellation(), onUpdate = { updates += it },
        )
        assertEquals(null, result.error)
        server.takeRequest()
        val continued = org.json.JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val output = continued.getJSONObject(continued.length() - 1).getString("content")
        assertTrue(output.contains("unknown_tool"))
        assertTrue("Tool results must also leave the request-local loop", updates.any { it.toolContent == output })
    }

    @Test
    fun warmupThenStreamReusesClientAndDoesNotPostOnWarmup() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val provider = ProviderConfig(
            id = "p", name = "t",
            endpointUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "k",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
        )
        EndpointWarmup.warmBlocking(provider)
        val result = ChatApi.streamChat(
            provider = provider,
            model = "m",
            messages = listOf(ChatRequestMessage(role = "user", text = "hello")),
            reasoningEffort = ReasoningEffort.OFF,
            fastMode = false,
            cancellation = ChatRequestCancellation(),
            onUpdate = {},
        )
        val warm = server.takeRequest()
        val chat = server.takeRequest()
        assertEquals("HEAD", warm.method)
        assertEquals(0, warm.body.size)
        assertEquals("POST", chat.method)
        assertTrue(chat.path!!.endsWith("/chat/completions"))
        assertTrue(chat.getHeader("Authorization")!!.contains("k"))
        assertEquals(null, chat.getHeader("X-Grok-Conv-Id"))
        val posted = org.json.JSONObject(chat.body.readUtf8())
        assertEquals(false, posted.has("prompt_cache_key"))
        assertTrue(chat.getHeader("User-Agent").orEmpty().contains("okhttp"))
        assertEquals(null, result.error)
        assertEquals("hi", result.content)
    }

    @Test
    fun warmupReusesConnectionInsideConversationButNotAcrossConversations() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(sse("one"))
        server.enqueue(sse("two"))
        val acquired = mutableListOf<Int>()
        IrioHttp.resetForTests(
            IrioHttp.defaultClient().newBuilder()
                .eventListener(object : EventListener() {
                    override fun connectionAcquired(call: Call, connection: Connection) {
                        acquired += System.identityHashCode(connection)
                    }
                })
                .build(),
        )
        val provider = ProviderConfig(
            id = "p", name = "t",
            endpointUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "k",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
        )
        assertTrue(EndpointWarmup.warmBlocking(provider, "conv-a"))
        val first = chat(provider, "conv-a", "hello")
        val second = chat(provider, "conv-b", "next")
        assertEquals("one", first.content)
        assertEquals("two", second.content)
        assertEquals(3, acquired.size)
        assertEquals(acquired[0], acquired[1])
        assertNotEquals(acquired[1], acquired[2])
    }

    @Test
    fun responsesConversationScopeIsTheUpstreamCacheKey() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("")) }
        val provider = ProviderConfig(
            id = "p", name = "t",
            endpointUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "k",
            protocol = ProviderProtocol.RESPONSES,
        )
        chat(provider, "conv-a", "alpha-phrase")
        chat(provider, "conv-a", "still-alpha")
        chat(provider, "conv-b", "beta-phrase")
        val first = org.json.JSONObject(server.takeRequest().body.readUtf8())
        val secondRequest = server.takeRequest()
        val second = org.json.JSONObject(secondRequest.body.readUtf8())
        val third = org.json.JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("conv-a", first.getString("prompt_cache_key"))
        assertEquals("conv-a", second.getString("prompt_cache_key"))
        assertEquals("conv-a", secondRequest.getHeader("X-Grok-Conv-Id"))
        assertEquals("conv-b", third.getString("prompt_cache_key"))
        val thirdInput = third.getJSONArray("input").toString()
        assertTrue(thirdInput.contains("beta-phrase"))
        assertTrue(!thirdInput.contains("alpha-phrase"))
    }

    @Test
    fun http2StreamResetBeforeTokensRetriesOnHttp1() = runBlocking {
        server.enqueue(sse("hi"))
        var attempts = 0
        IrioHttp.resetForTests(
            IrioHttp.defaultClient().newBuilder()
                .addInterceptor { chain ->
                    attempts++
                    if (attempts == 1) throw java.io.IOException("stream was reset: PROTOCOL_ERROR")
                    chain.proceed(chain.request())
                }
                .build(),
        )
        val provider = ProviderConfig(
            id = "p", name = "t",
            endpointUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "k",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
        )
        val result = chat(provider, "conv-reset", "hello")
        assertEquals(null, result.error)
        assertEquals("hi", result.content)
        assertEquals(2, attempts)
        assertEquals(1, server.requestCount)
        assertEquals(listOf(okhttp3.Protocol.HTTP_1_1), IrioHttp.clientFor("conv-reset").protocols)
    }

    @Test
    fun otherIoFailureIsNotRetried() = runBlocking {
        server.enqueue(sse("hi"))
        var attempts = 0
        IrioHttp.resetForTests(
            IrioHttp.defaultClient().newBuilder()
                .addInterceptor { chain ->
                    attempts++
                    if (attempts == 1) throw java.io.IOException("connection reset")
                    chain.proceed(chain.request())
                }
                .build(),
        )
        val provider = ProviderConfig(
            id = "p", name = "t",
            endpointUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "k",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
        )
        val result = chat(provider, "conv-reset", "hello")
        assertEquals("connection reset", result.error)
        assertEquals(1, attempts)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun chatCompletionsReadsUsageAfterFinishReason() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":40,\"total_tokens\":160}}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val provider = ProviderConfig(
            id = "p", name = "t", endpointUrl = server.url("/v1").toString(), apiKey = "k",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
        )
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = ChatApi.streamChat(
            provider = provider,
            model = "m",
            messages = listOf(ChatRequestMessage(role = "user", text = "hello")),
            reasoningEffort = ReasoningEffort.OFF,
            fastMode = false,
            cancellation = ChatRequestCancellation(),
            onUpdate = { updates += it },
        )
        assertEquals(null, result.error)
        assertEquals("hi", result.content)
        assertEquals(TokenUsage(120, 40, 160), result.usage)
        assertEquals(TokenUsage(120, 40, 160), updates.last().usage)
        assertTrue(updates.last().done)
        assertEquals(1, updates.count { it.done })
    }

    @Test
    fun anthropicMergesInputUsageWithTheFinalOutputUsage() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":80,\"output_tokens\":0}}}\n\n" +
                    "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n" +
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}\n\n" +
                    "data: {\"type\":\"message_stop\"}\n\n",
            ),
        )
        val provider = ProviderConfig(
            id = "p", name = "t", endpointUrl = server.url("/v1").toString(), apiKey = "k",
            protocol = ProviderProtocol.ANTHROPIC,
        )
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = ChatApi.streamChat(
            provider = provider,
            model = "m",
            messages = listOf(ChatRequestMessage(role = "user", text = "hello")),
            reasoningEffort = ReasoningEffort.OFF,
            fastMode = false,
            cancellation = ChatRequestCancellation(),
            onUpdate = { updates += it },
        )
        assertEquals(null, result.error)
        assertEquals(TokenUsage(80, 20, 100), result.usage)
        assertEquals(TokenUsage(80, 20, 100), updates.last().usage)
    }

    @Test(timeout = 8_000)
    fun missingUsageTrailerOnAnOpenConnectionDoesNotDiscardTheReply() = runBlocking {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n"
        // 声明还有更多 body，但服务端不再发送，也不关闭连接。
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(body).setHeader("Content-Length", body.toByteArray().size + 100),
        )
        val provider = ProviderConfig(
            id = "p", name = "t", endpointUrl = server.url("/v1").toString(), apiKey = "k",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
        )
        val started = System.nanoTime()
        val result = chat(provider, "open-connection", "hello")
        assertEquals(null, result.error)
        assertEquals("hi", result.content)
        assertEquals(null, result.usage)
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
    }

    @Test
    fun toolRoundWithoutDoneDoesNotFinishTheReplyBeforeTheNextRound() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"function\":{\"name\":\"unknown_tool\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n",
            ),
        )
        server.enqueue(sse("hi"))
        val provider = ProviderConfig(
            id = "p", name = "t", endpointUrl = server.url("/v1").toString(), apiKey = "k",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
        )
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = ChatApi.streamChat(
            provider = provider,
            model = "m",
            messages = listOf(ChatRequestMessage(role = "user", text = "hello")),
            reasoningEffort = ReasoningEffort.OFF,
            fastMode = false,
            toolRegistry = ToolRegistry(),
            cancellation = ChatRequestCancellation(),
            onUpdate = { updates += it },
        )
        assertEquals(null, result.error)
        assertEquals("hi", result.content)
        assertTrue(updates.any { it.newRound })
        assertTrue(updates.takeWhile { !it.newRound }.none { it.done })
        assertEquals(1, updates.count { it.done })
    }

    @Test(timeout = 8_000)
    fun stoppingDuringAToolOnlyReplyKeepsTheToolStatus() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"function\":{\"name\":\"get_time\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val provider = ProviderConfig(
            id = "p", name = "t", endpointUrl = server.url("/v1").toString(), apiKey = "k",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
        )
        val cancellation = ChatRequestCancellation()
        val process = ProcessTracker()
        val result = ChatApi.streamChat(
            provider = provider,
            model = "m",
            messages = listOf(ChatRequestMessage(role = "user", text = "time")),
            reasoningEffort = ReasoningEffort.OFF,
            fastMode = false,
            toolRegistry = ToolRegistry(listOf(TimeToolProvider())),
            cancellation = cancellation,
            onUpdate = { update ->
                if (update.toolActive == true) {
                    process.onToolStarted(update.toolName)
                    cancellation.cancel()
                }
                if (update.toolActive == false) process.onToolFinished(update.toolContent)
            },
        )
        process.finishAll()
        val events = process.snapshot()
        assertTrue(result.stopped)
        assertEquals("", result.content)
        assertEquals(1, server.requestCount)
        assertEquals(TimeTool.NAME, events.single().toolName)
        assertEquals(false, events.single().live)
        assertTrue(hasReplyContent(result.content, result.attachments, events))
    }

    private fun sse(text: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        )

    private suspend fun chat(provider: ProviderConfig, scope: String, text: String) = ChatApi.streamChat(
        provider = provider,
        model = "m",
        messages = listOf(ChatRequestMessage(role = "user", text = text)),
        reasoningEffort = ReasoningEffort.OFF,
        fastMode = false,
        connectionScope = scope,
        cancellation = ChatRequestCancellation(),
        onUpdate = {},
    )
}
