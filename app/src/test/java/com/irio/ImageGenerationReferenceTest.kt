package com.irio

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ImageGenerationReferenceTest {
    @Test fun directChatCollectsAllCurrentImagesButNotDocumentsOrHiddenMessages() {
        val images = (1..4).map { ChatAttachment("ref-$it.png", "image/png", "/fixture/ref-$it.png") }
        val document = ChatAttachment("notes.txt", "text/plain", "/fixture/notes.txt")
        val hiddenImage = ChatAttachment("hidden.png", "image/png", "/fixture/hidden.png")
        val context = chatImageGenerationContext(listOf(
            ChatMessage(1, "My references", true, attachments = images + document),
            ChatMessage(2, "Internal notice", true, attachments = listOf(hiddenImage), hidden = true),
            ChatMessage(3, "", false, isPending = true),
        ))!!
        assertEquals(images, context.referenceImages)
        assertEquals(images, context.inputImages)
        assertTrue(context.previousImages.isEmpty())
    }

    @Test fun uploadedReferencesAndPreviousGeneratedImagesAreRetainedWithoutDuplicates() {
        val generated = ChatAttachment("generated.png", "image/png", "/fixture/generated.png")
        val reference = ChatAttachment("reference.png", "image/png", "/fixture/reference.png")
        val history = listOf(
            ChatRequestMessage("user", "Previous prompt"),
            ChatRequestMessage("assistant", attachments = listOf(generated)),
            ChatRequestMessage("user", "Current prompt", attachments = listOf(reference, reference)),
            ChatRequestMessage("assistant", toolCalls = listOf(ChatToolCall("image", ImageGenTool.NAME, "{}"))),
        )
        val context = imageGenerationContext(history)!!
        assertEquals("Previous prompt", context.previousPrompt)
        assertEquals(listOf(reference), context.referenceImages)
        assertEquals(listOf(generated), context.previousImages)
        assertEquals(listOf(reference, generated), context.inputImages)
        val oldContext = imageGenerationContext(history.take(3).map {
            if (it.role == "user" && it.text == "Current prompt") it.copy(attachments = emptyList()) else it
        })!!
        assertTrue(oldContext.referenceImages.isEmpty())
        assertEquals(listOf(generated), oldContext.inputImages)
        assertNull(imageGenerationContext(listOf(ChatRequestMessage("user", "No reference"))))
        assertNull(chatImageGenerationContext(emptyList()))
    }

    @Test fun directImageRequestsUploadExactReferenceBytesWithStreamingOnOrOff() {
        val images = (1..2).map { index ->
            File.createTempFile("direct-reference-$index-", ".png").apply { writeText("reference-$index-actual-bytes") }
        }
        try {
            val attachments = images.mapIndexed { index, file -> ChatAttachment("ref-$index.png", "image/png", file.absolutePath) }
            for (streaming in listOf(false, true)) MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"local fixture"}}"""))
                val result = ProviderApi.generateImage(
                    ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture"),
                    "gpt-image-2", "Turn the references blue",
                    context = chatImageGenerationContext(listOf(ChatMessage(1, "My reference", true, attachments = attachments))),
                    streaming = streaming,
                )
                assertNotNull(result.error)
                val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("No image request")
                assertEquals("/v1/images/edits", request.path)
                val multipart = request.body.readUtf8()
                attachments.forEachIndexed { index, image ->
                    assertTrue(multipart.contains("name=\"image[]\"; filename=\"${image.name}\""))
                    assertTrue(multipart.contains("Content-Type: image/png\r\n\r\nreference-${index + 1}-actual-bytes\r\n"))
                }
                assertTrue(multipart.contains("name=\"stream\"\r\n\r\n$streaming\r\n"))
                assertEquals(streaming, multipart.contains("name=\"partial_images\""))
                assertEquals(1, server.requestCount)
            }
        } finally { images.forEach(File::delete) }
    }

    @Test fun missingExplicitReferenceFailsBeforeSendingAnyRequest() {
        MockWebServer().use { server ->
            val missing = File.createTempFile("missing-reference-", ".png").apply { delete() }
            val attachment = ChatAttachment("missing.png", "image/png", missing.absolutePath)
            val result = ProviderApi.generateImage(
                ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture"), "gpt-image-2", "fixture",
                context = chatImageGenerationContext(listOf(ChatMessage(1, "Reference", true, attachments = listOf(attachment)))),
            )
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("missing.png"))
            assertNull(result.attachment)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun unsupportedReferenceEndpointDoesNotFallBackToTextOnlyGeneration() = runBlocking {
        val image = File.createTempFile("unsupported-reference-", ".png").apply { writeText("fixture") }
        try {
            for (status in listOf(404, 405, 415)) MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":{"message":"edits unsupported"}}"""))
                server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"unexpected text-only fallback"}}"""))
                val provider = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture")
                val result = ImageGenToolProvider().execute(
                    ChatToolCall("image", ImageGenTool.NAME, """{"prompt":"Edit the reference"}"""),
                    ToolExecutionContext(provider, "chat-model", history = listOf(ChatRequestMessage(
                        "user", "My reference", attachments = listOf(ChatAttachment("ref.png", "image/png", image.absolutePath)),
                    )), imageGenModel = "gpt-image-2"),
                )
                assertEquals(ToolOutcome.FAILED, result.outcome)
                assertEquals("Must report unsupported edits instead of silently discarding the reference", 1, server.requestCount)
                assertEquals("/v1/images/edits", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            }
        } finally { image.delete() }
    }

    @Test fun modelToolUploadsTheCurrentUserReferenceImagesOnTheFirstTurn() = runBlocking {
        val image = File.createTempFile("image-reference-", ".png").apply { writeText("reference-image-fixture-bytes") }
        val document = File.createTempFile("not-image-reference-", ".txt").apply { writeText("document-must-not-be-uploaded") }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"local fixture"}}"""))
                val provider = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture")
                val history = listOf(ChatRequestMessage("user", "Use this reference", attachments = listOf(
                    ChatAttachment("reference.png", "image/png", image.absolutePath),
                    ChatAttachment("notes.txt", "text/plain", document.absolutePath),
                )))
                val result = ImageGenToolProvider().execute(
                    ChatToolCall("image", ImageGenTool.NAME, """{"prompt":"Paint the reference blue"}"""),
                    ToolExecutionContext(provider, "chat-model", history = history, imageGenModel = "gpt-image-2"),
                )
                assertEquals(ToolOutcome.FAILED, result.outcome) // Deliberate fixture, not a paid generation.
                val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("No image request")
                assertEquals("Uploaded references must use the image edits endpoint", "/v1/images/edits", request.path)
                assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
                val multipart = request.body.readUtf8()
                assertTrue(multipart.contains("name=\"image[]\"; filename=\"reference.png\""))
                assertTrue(multipart.contains("Content-Type: image/png\r\n\r\nreference-image-fixture-bytes\r\n"))
                assertTrue(multipart.contains("name=\"prompt\"\r\n\r\nPaint the reference blue\r\n"))
                assertFalse(multipart.contains("document-must-not-be-uploaded"))
                assertEquals(1, server.requestCount)
            }
        } finally { image.delete(); document.delete() }
    }
}
