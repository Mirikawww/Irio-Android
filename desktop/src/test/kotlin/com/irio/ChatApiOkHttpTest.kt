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
