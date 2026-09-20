package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertTrue(chat.getHeader("User-Agent").orEmpty().contains("okhttp"))
        assertEquals(null, result.error)
        assertEquals("hi", result.content)
    }
}
