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
    fun gatewayDataArrayInsideStreamIsNotDiscarded() {
        for (eventType in listOf("image_generation.completed", "image_edit.completed", "")) {
            MockWebServer().use { server ->
                val imageUrl = server.url("/gateway.png?signature=fixture").toString()
                val item = org.json.JSONObject().put("url", imageUrl)
                val payload = org.json.JSONObject().put("data", org.json.JSONArray().put(item)).put("size", "1024x1536")
                if (eventType.isNotEmpty()) payload.put("type", eventType)
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $payload\n\ndata: [DONE]\n\n"))
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("fixture image bytes"))
                val result = ProviderApi.generateImage(provider(server.url("/v1").toString()), "gpt-image-2", "mountains")
                try {
                    assertNull("Gateway data[] URL was discarded for '$eventType'", result.error)
                    assertEquals("fixture image bytes", File(result.attachment!!.filePath).readText())
                } finally { result.attachment?.let { File(it.filePath).delete() } }
            }
        }
    }

    @Test
    fun failedStreamedUrlDownloadReportsDownloadFailureNotMissingImage() {
        MockWebServer().use { server ->
            val url = server.url("/expired.png").toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"image_generation.completed\",\"url\":\"$url\"}\n\n"))
            server.enqueue(MockResponse().setResponseCode(403))
            val result = ProviderApi.generateImage(provider(server.url("/v1").toString()), "gpt-image-2", "mountains")
            assertNull(result.attachment)
            assertTrue(result.error.orEmpty().contains("无法下载"))
            assertTrue(!result.error.orEmpty().contains("没有返回图片"))
        }
    }

    @Test
    fun streamedCompletedUrlIsDownloadedInsteadOfReportedAsMissing() {
        MockWebServer().use { server ->
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
            val imageUrl = server.url("/generated.png?X-Amz-Signature=fixture").toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: image_generation.completed\ndata: {\"type\":\"image_generation.completed\",\"url\":\"$imageUrl\",\"size\":\"1536x1024\"}\n\n",
            ))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            val result = ProviderApi.generateImage(provider(server.url("/v1").toString()), "gpt-image-2", "mountains")
            try {
                assertNull("A completed event containing an image URL is a real image result", result.error)
                val attachment = result.attachment ?: throw AssertionError("The generated image must be downloaded")
                assertTrue(png.contentEquals(File(attachment.filePath).readBytes()))
                assertEquals(2, server.requestCount)
                assertEquals("POST", server.takeRequest().method)
                val downloaded = server.takeRequest()
                assertEquals("GET", downloaded.method)
                assertEquals("/generated.png?X-Amz-Signature=fixture", downloaded.path)
            } finally { result.attachment?.let { File(it.filePath).delete() } }
        }
    }

    @Test
    fun responsesOutputItemFinalUrlIsDownloadedAndKeptAsTheFinalAttachment() {
        MockWebServer().use { server ->
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
            val imageUrl = server.url("/response-image.png?signature=signed").toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            val updates = mutableListOf<ImageGenerationProgress>()
            val stream = ImageGenerationStream(
                onProgress = updates::add,
                download = { url, revised -> ProviderApi.imageFromRemoteUrl(url, revised, provider(server.url("/v1").toString()), null) },
                previewDecoder = { null },
            )
            try {
                val update = stream.accept("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"id\":\"image-1\",\"result\":\"$imageUrl\",\"size\":\"2048x1024\"}}")
                assertNull(update?.error)
                assertEquals(1, update?.attachments?.size)
                val attachment = stream.attachments.single()
                assertTrue(png.contentEquals(File(attachment.filePath).readBytes()))
                assertEquals(1, updates.count { it.finalAttachment != null })
                assertEquals(2f, updates.last().aspectRatio, 0f)
                val downloaded = server.takeRequest()
                assertEquals("/response-image.png?signature=signed", downloaded.path)
            } finally { stream.attachments.forEach { File(it.filePath).delete() } }
        }
    }

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
