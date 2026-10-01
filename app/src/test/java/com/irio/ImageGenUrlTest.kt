package com.irio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageGenUrlTest {
    @Test
    fun urlResponseIsDownloadedToALocalFile() {
        MockWebServer().use { server ->
            server.start()
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
            val imageUrl = server.url("/cat.png").toString()
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[{"url":"$imageUrl","revised_prompt":"a cat"}]}""",
                ),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(png)),
            )

            val result = ProviderApi.generateImage(
                config = provider(server.url("/v1").toString()),
                model = "img",
                prompt = "cat",
            )

            val file = File(result.attachment!!.filePath)
            try {
                assertEquals("a cat", result.revisedPrompt)
                assertNull(result.error)
                assertEquals("image/png", result.attachment.mimeType)
                assertEquals(png.size.toLong(), file.length())
                assertEquals(2, server.requestCount)
                val download = server.takeRequest().let { server.takeRequest() }
                assertEquals("/cat.png", download.path)
                assertEquals("Bearer key", download.getHeader("Authorization"))
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun failedUrlDownloadDoesNotAskForB64() {
        MockWebServer().use { server ->
            server.start()
            val imageUrl = server.url("/missing.png").toString()
            server.enqueue(MockResponse().setBody("""{"data":[{"url":"$imageUrl"}]}"""))
            server.enqueue(MockResponse().setResponseCode(404))

            val result = ProviderApi.generateImage(
                config = provider(server.url("/v1").toString()),
                model = "img",
                prompt = "cat",
            )

            assertNull(result.attachment)
            assertTrue(result.error.orEmpty().contains("无法下载"))
            assertTrue(!result.error.orEmpty().contains("b64_json"))
        }
    }

    private fun provider(endpoint: String) = ProviderConfig(
        id = "p",
        name = "P",
        endpointUrl = endpoint.trimEnd('/'),
        apiKey = "key",
    )
}
