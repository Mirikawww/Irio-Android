package com.irio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiImageGenTest {
    @Test
    fun generatesImagesWithNativeEndpointAndPriorImageContext() {
        MockWebServer().use { server ->
            server.start()
            val image = java.nio.file.Files.createTempFile("irio-reference", ".png").toFile()
            image.writeBytes(byteArrayOf(1, 2, 3))
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"BAUG"}}]},"finishReason":"STOP"}]}"""))
            try {
                val provider = ProviderConfig("p", "Google", server.url("/v1beta").toString(), "fixture", ProviderProtocol.GEMINI)
                val result = ProviderApi.generateImage(provider, "gemini-2.5-flash-image", "make it blue",
                    ProviderApi.ImageGenContext("a red cat", listOf(ChatAttachment("ref.png", "image/png", image.absolutePath))))
                try {
                    assertNull(result.error)
                    assertNotNull(result.attachment)
                    assertArrayEquals(byteArrayOf(4, 5, 6), result.attachment!!.bytes())
                    val request = server.takeRequest()
                    assertEquals("/v1beta/models/gemini-2.5-flash-image:generateContent", request.path)
                    assertEquals("fixture", request.getHeader("x-goog-api-key"))
                    val body = JSONObject(request.body.readUtf8())
                    assertEquals("IMAGE", body.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(1))
                    assertTrue(body.toString().contains("AQID"))
                    assertFalse(body.has("model"))
                } finally { result.attachment?.let { java.io.File(it.filePath).delete() } }
            } finally { image.delete() }
        }
    }

    @Test
    fun interactionsImageGenerationUsesItsOwnWireFormat() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"model":"gemini-3-pro-image-preview","steps":[{"type":"model_output","content":[{"type":"image","mime_type":"image/png","data":"BAUG"}]}]}"""))
            val protocol = ProviderProtocol.valueOf("GEMINI_INTERACTIONS")
            val provider = ProviderConfig("p", "Google", server.url("/v1beta").toString(), "fixture", protocol)
            val result = ProviderApi.generateImage(provider, "gemini-3-pro-image-preview", "a cat")
            try {
                assertNull(result.error)
                assertArrayEquals(byteArrayOf(4, 5, 6), result.attachment!!.bytes())
                val request = server.takeRequest()
                assertEquals("/v1beta/interactions", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals("gemini-3-pro-image-preview", body.getString("model"))
                assertEquals("IMAGE", body.getJSONArray("response_modalities").getString(0))
                assertFalse(body.has("contents"))
            } finally { result.attachment?.let { java.io.File(it.filePath).delete() } }
        }
    }
}
