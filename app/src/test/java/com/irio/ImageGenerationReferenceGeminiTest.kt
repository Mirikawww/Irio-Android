package com.irio

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ImageGenerationReferenceGeminiTest {
    @Test fun geminiProtocolsSendUploadedReferencesInTheCurrentUserTurn() {
        val image = File.createTempFile("gemini-reference-", ".png").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        try {
            for (protocol in listOf(ProviderProtocol.GEMINI, ProviderProtocol.GEMINI_INTERACTIONS)) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"local fixture"}}"""))
                    val reference = ChatAttachment("reference.png", "image/png", image.absolutePath)
                    val context = chatImageGenerationContext(listOf(ChatMessage(1, "Use this reference", true, attachments = listOf(reference))))
                    val result = ProviderApi.generateImage(
                        ProviderConfig("fixture", "Fixture", server.url("/v1beta").toString(), "fixture", protocol),
                        "gemini-3-pro-image", "Make it blue", context = context,
                    )
                    assertNotNull(result.error)
                    val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("No Gemini image request")
                    val json = JSONObject(request.body.readUtf8())
                    val turn = json.getJSONArray(if (protocol == ProviderProtocol.GEMINI) "contents" else "input").getJSONObject(0)
                    if (protocol == ProviderProtocol.GEMINI) assertEquals("user", turn.getString("role"))
                    else assertEquals("user_input", turn.getString("type"))
                    val parts = turn.getJSONArray(if (protocol == ProviderProtocol.GEMINI) "parts" else "content")
                    val inline = if (protocol == ProviderProtocol.GEMINI) {
                        parts.getJSONObject(1).getJSONObject("inlineData")
                    } else parts.getJSONObject(1)
                    assertEquals("image/png", inline.getString(if (protocol == ProviderProtocol.GEMINI) "mimeType" else "mime_type"))
                    assertArrayEquals(image.readBytes(), Base64.getDecoder().decode(inline.getString("data")))
                    assertEquals(1, server.requestCount)
                }
            }
        } finally { image.delete() }
    }
}
