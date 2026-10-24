package com.irio

import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageGenerationStreamTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGJkAAAAASUVORK5CYII="
    private fun partial(index: Int, type: String = "image_generation.partial_image", size: String = "1536x1024") =
        JSONObject().put("type", type).put("partial_image_index", index).put("size", size)
            .put(if (type.startsWith("response.")) "partial_image_b64" else "b64_json", png).toString()
    private fun final(type: String = "image_generation.completed") =
        JSONObject().put("type", type).put("b64_json", png).put("size", "1536x1024").put("output_format", "png").toString()
    private fun sse(vararg data: String) = data.joinToString("") { "data: $it\n\n" }

    @Test fun initialSquareUniquePhrasesIndexOnlyUpdatesAndGenericExhaustion() {
        val state = ImageGenerationProgressState("fixture", Random(42))
        assertEquals(1f, state.value.aspectRatio, 0f)
        assertEquals("图片生成中", state.value.label)
        val labels = (0 until imageGenerationPhrases.size).map { index ->
            val current = state.update(index = index)
            assertEquals(current, state.update(index = index))
            assertEquals(current, state.update(index = index - 1))
            current.label
        }
        assertEquals(imageGenerationPhrases.toSet(), labels.toSet())
        assertEquals(labels.size, labels.distinct().size)
        assertEquals("图片生成中", state.update(index = 100).label)
        assertEquals("图片生成中", state.update(index = 101).label)
    }

    @Test fun modelTextWinsPermanentlyAndBadOrOldSizesCannotBreakThePlaceholder() {
        val state = ImageGenerationProgressState("fixture")
        state.update(size = "1536x1024", index = 2, text = "I'm drawing a quiet forest.")
        assertEquals(1.5f, state.value.aspectRatio, 0f)
        assertEquals("I'm drawing a quiet forest.", state.value.caption())
        state.update(size = "1024x1536", index = 0)
        assertEquals(1.5f, state.value.aspectRatio, 0f)
        listOf("auto", "0x1024", "-1x2", "999999999x1", "1x99999", "4x1").forEach { size ->
            state.update(size = size, index = 3)
            assertEquals(1.5f, state.value.aspectRatio, 0f)
            assertEquals("I'm drawing a quiet forest.", state.value.caption())
        }
    }

    @Test fun responsesEventsHandleMetadataTextPartialsAndExactlyOneFinalFromOutput() {
        val saved = mutableListOf<String>()
        val stream = ImageGenerationStream(save = { data, format, _ ->
            saved += data
            assertEquals("png", format)
            ChatAttachment("final.png", "image/png", "final.png")
        }, previewDecoder = { null })
        val initial = stream.start()
        stream.accept("""{"type":"response.output_text.delta","delta":"Drawing a forest"}""")
        val metadata = stream.accept("""{"type":"response.output_item.added","item":{"id":"ig1","type":"image_generation_call","size":"1536x1024"}}""")!!
        assertEquals(initial.id, metadata.progress.single().id)
        assertEquals(1.5f, metadata.progress.single().aspectRatio, 0f)
        val preview = JSONObject(partial(0, "response.image_generation_call.partial_image")).put("item_id", "ig1")
        assertEquals("Drawing a forest", stream.accept(preview.toString())!!.progress.single().caption())
        assertTrue(saved.isEmpty())
        // This status event intentionally has no final bytes; it must not finish/save a preview.
        assertNull(stream.accept("""{"type":"response.image_generation_call.completed","item_id":"ig1"}"""))
        val item = JSONObject().put("id", "ig1").put("type", "image_generation_call").put("result", png).put("output_format", "png")
        assertEquals(1, stream.accept(JSONObject().put("type", "response.output_item.done").put("item", item).toString())!!.attachments.size)
        val completed = stream.accept(JSONObject().put("type", "response.completed").put("response",
            JSONObject().put("output", org.json.JSONArray().put(item))).toString())!!
        assertTrue(completed.done)
        assertTrue(completed.attachments.isEmpty())
        assertEquals(1, saved.size)
        assertEquals(1, stream.attachments.size)
    }

    @Test fun multipleImageItemsHaveIndependentPreviewIndicesAndPhraseDecks() {
        val stream = ImageGenerationStream(previewDecoder = { null })
        fun accept(id: String, index: Int) = stream.accept(JSONObject(partial(index, "response.image_generation_call.partial_image"))
            .put("item_id", id).toString())!!.progress.single()
        val first = accept("first", 1)
        val other = accept("other", 0)
        assertNotEquals(first.id, other.id)
        assertEquals(0, other.partialIndex)
        assertEquals(first.label, accept("first", 1).label)
        assertEquals(2, accept("first", 2).partialIndex)
    }

    @Test fun actualParametersAreRetainedWhenLaterEventsOmitThem() {
        val stream = ImageGenerationStream(previewDecoder = { null })
        val first = JSONObject(partial(0)).put("quality", "high").put("background", "transparent").put("output_format", "webp")
        val metadata = stream.accept(first.toString())!!.progress.single()
        assertEquals("high", metadata.quality)
        assertEquals("transparent", metadata.background)
        assertEquals("webp", metadata.outputFormat)
        val later = stream.accept(partial(1))!!.progress.single()
        assertEquals(metadata.quality, later.quality)
        assertEquals(metadata.outputFormat, later.outputFormat)
        assertEquals(metadata.background, later.background)
    }

    @Test fun streamingEditsAndEndpointFallbackKeepOneSessionAndStreamingFlags() {
        for (fallback in listOf(false, true)) {
            val source = File.createTempFile("image-edit-stream-", ".png").apply { writeBytes(java.util.Base64.getDecoder().decode(png)) }
            try {
                MockWebServer().use { server ->
                    if (fallback) server.enqueue(MockResponse().setResponseCode(404))
                    server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(
                        partial(0, if (fallback) "image_generation.partial_image" else "image_edit.partial_image"),
                        final(if (fallback) "image_generation.completed" else "image_edit.completed"),
                    )))
                    val updates = mutableListOf<ImageGenerationProgress>()
                    val result = ProviderApi.generateImage(ProviderConfig("p", "P", server.url("/v1").toString(), "fixture"),
                        "gpt-image-2", "fixture", ProviderApi.ImageGenContext("old", listOf(ChatAttachment("previous.png", "image/png", source.absolutePath))),
                        streaming = true, onProgress = { updates += it })
                    try {
                        assertNotNull(result.attachment)
                        assertEquals(1, updates.map { it.id }.distinct().size)
                        val edit = server.takeRequest()
                        assertEquals("/v1/images/edits", edit.path)
                        val multipart = edit.body.readUtf8()
                        assertTrue(multipart.contains("name=\"stream\"\r\n\r\ntrue\r\n"))
                        assertTrue(multipart.contains("name=\"partial_images\"\r\n\r\n3\r\n"))
                        assertEquals(if (fallback) 2 else 1, server.requestCount)
                    } finally { result.attachment?.let { File(it.filePath).delete() } }
                }
            } finally { source.delete() }
        }
    }

    @Test fun toolInvocationPublishesProgressWithoutTreatingPreviewAsAnAttachment() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(partial(0), final())))
            val updates = mutableListOf<ChatStreamUpdate>()
            val context = ToolExecutionContext(ProviderConfig("p", "P", server.url("/v1").toString(), "fixture"), "chat-model",
                imageGenModel = "gpt-image-2", onUpdate = { updates += it })
            val result = ImageGenToolProvider().execute(ChatToolCall("image", ImageGenTool.NAME, """{"prompt":"fixture"}"""), context)
            val attachments = updates.flatMap { it.attachments }
            try {
                assertEquals(ToolOutcome.SUCCESS, result.outcome)
                assertEquals(1, attachments.size)
                assertTrue(updates.flatMap { it.imageGeneration }.any { it.partialIndex == 0 && it.finalAttachment == null })
                assertEquals(attachments.single(), updates.flatMap { it.imageGeneration }.last().finalAttachment)
            } finally { attachments.forEach { File(it.filePath).delete() } }
        }
    }

    @Test fun sseHandlesCommentsMultilineEventNamesAndEofWithoutBlankLine() {
        val records = mutableListOf<Pair<String, String>>()
        val source = ": keepalive\r\nevent: image_edit.partial_image\r\ndata: {\r\ndata: \"partial_image_index\":0}\r\n\r\n" +
            "event: image_edit.completed\ndata: {\"b64_json\":\"fixture\"}"
        readImageSse(source.reader().buffered(), { false }) { payload, name -> records += name to payload; false }
        assertEquals(listOf("image_edit.partial_image", "image_edit.completed"), records.map { it.first })
        assertEquals(0, JSONObject(records.first().second).getInt("partial_image_index"))
        assertEquals("fixture", JSONObject(records.last().second).getString("b64_json"))
    }

    @Test fun realImagesTransportRequestsStreamingAndPublishesPreviewsBeforeFinalOnly() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(partial(0), partial(1), partial(2), final())))
            val updates = mutableListOf<ImageGenerationProgress>()
            val result = ProviderApi.generateImage(ProviderConfig("p", "P", server.url("/v1").toString(), "fixture"),
                "gpt-image-2", "fixture", streaming = true, onProgress = { updates += it })
            try {
                assertNull(result.error)
                assertNotNull(result.attachment)
                assertEquals(listOf(-1, 0, 1, 2, 2), updates.map { it.partialIndex })
                assertTrue(updates.dropLast(1).all { it.finalAttachment == null && it.active })
                assertFalse(updates.last().active)
                assertEquals(result.attachment, updates.last().finalAttachment)
                assertEquals(1, updates.map { it.id }.distinct().size)
                val request = server.takeRequest()
                val body = JSONObject(request.body.readUtf8())
                assertTrue(body.getBoolean("stream"))
                assertEquals(3, body.getInt("partial_images"))
                assertTrue(request.getHeader("Accept")!!.contains("text/event-stream"))
            } finally { result.attachment?.let { File(it.filePath).delete() } }
        }
    }

    @Test fun noPreviewFinalOnlyAndLegacyJsonStillCompleteSuccessfully() {
        for (json in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(if (json) """{"size":"1536x1024","data":[{"b64_json":"$png"}]}""" else sse(final())))
            val updates = mutableListOf<ImageGenerationProgress>()
            val result = ProviderApi.generateImage(ProviderConfig("p", "P", server.url("/v1").toString(), "fixture"),
                "gpt-image-2", "fixture", streaming = true, onProgress = { updates += it })
            try {
                assertNotNull(result.attachment)
                assertEquals(2, updates.size)
                assertFalse(updates.last().active)
                assertEquals(1.5f, updates.last().aspectRatio, 0f)
            } finally { result.attachment?.let { File(it.filePath).delete() } }
        }
    }

    @Test fun cancellationAndPrematureEofNeverPromotePreviewToFinal() {
        for (cancel in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(partial(0))))
            val cancellation = ChatRequestCancellation()
            val updates = mutableListOf<ImageGenerationProgress>()
            val result = ProviderApi.generateImage(ProviderConfig("p", "P", server.url("/v1").toString(), "fixture"),
                "gpt-image-2", "fixture", cancellation = cancellation, streaming = true, onProgress = {
                    updates += it
                    if (it.partialIndex == 0 && cancel) cancellation.cancel()
                })
            assertNull(result.attachment)
            assertNotNull(result.error)
            assertTrue(updates.all { it.finalAttachment == null })
        }
    }

    @Test fun nativeResponsesChatPublishesPreviewMetadataTextAndFinalAttachments() = runBlocking {
        MockWebServer().use { server ->
            val output = JSONObject().put("id", "ig1").put("type", "image_generation_call").put("result", png).put("size", "1536x1024")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(
                """{"type":"response.output_item.added","item":{"id":"ig1","type":"image_generation_call"}}""",
                """{"type":"response.output_text.delta","delta":"A forest is taking shape"}""",
                JSONObject(partial(0, "response.image_generation_call.partial_image")).put("item_id", "ig1").toString(),
                JSONObject().put("type", "response.completed").put("response", JSONObject().put("output", org.json.JSONArray().put(output))).toString(),
            )))
            val updates = mutableListOf<ChatStreamUpdate>()
            val result = ChatApi.streamChat(ProviderConfig("p", "P", server.url("/v1").toString(), "fixture", ProviderProtocol.RESPONSES),
                "chat-model", listOf(ChatRequestMessage("user", "draw a forest")), ReasoningEffort.OFF, false,
                toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(), onUpdate = { updates += it })
            try {
                assertNull(result.error)
                assertEquals("A forest is taking shape", result.content)
                assertEquals(1, result.attachments.size)
                val preview = updates.flatMap { it.imageGeneration }.first { it.partialIndex == 0 }
                assertEquals("A forest is taking shape", preview.caption())
                assertEquals(1.5f, preview.aspectRatio, 0f)
                assertTrue(updates.flatMap { it.imageGeneration }.any { it.finalAttachment != null })
            } finally { result.attachments.forEach { File(it.filePath).delete() } }
        }
    }

    @Test fun trackerKeepsTheSameCardThroughTextAndFinalAndNeverPersistsPreviews() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(ImageGenTool.NAME)
        tracker.onImageGeneration(ImageGenerationProgress("image", partialIndex = 0))
        val eventId = tracker.snapshot().single().id
        tracker.onOutputStarted(textLength = 10)
        assertTrue(tracker.snapshot().single().live)
        val file = File.createTempFile("image-progress-test-", ".png").apply { writeBytes(java.util.Base64.getDecoder().decode(png)) }
        val attachment = ChatAttachment("final.png", "image/png", file.absolutePath)
        tracker.onImageGeneration(ImageGenerationProgress("image", finalAttachment = attachment, active = false))
        tracker.dropLiveTool()
        tracker.finishAll()
        val event = tracker.snapshot().single()
        assertEquals(eventId, event.id)
        assertEquals(attachment, event.imageGeneration?.finalAttachment)
        assertFalse(event.live)
        val conversation = Conversation("fixture", "Fixture", 1, listOf(ChatMessage(1, "", false, process = tracker.snapshot(), attachments = listOf(attachment))))
        val json = ConversationStore.conversationJson(conversation)
        assertFalse(json.toString().contains("partialIndex"))
        assertFalse(json.toString().contains("imageGeneration"))
        val restored = ConversationStore.parseConversation(json).messages.single()
        assertEquals(listOf(attachment), restored.attachments)
        assertNull(restored.process.single().imageGeneration)
        file.delete()
    }

    @Test fun failureAndStopCloseThePlaceholderWhileSuccessfulImagesRemain() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(ImageGenTool.NAME)
        tracker.onImageGeneration(ImageGenerationProgress("failed"))
        tracker.onToolFinished(ImageGenTool.STATUS_FAILED, ToolOutcome.FAILED)
        val failed = tracker.snapshot().single()
        assertFalse(failed.imageGeneration!!.active)
        assertTrue(failed.imageGeneration.failed)
        assertEquals(ToolOutcome.FAILED, failed.toolOutcome)

        val completed = ImageGenerationProgress("done", active = false,
            finalAttachment = ChatAttachment("done.png", "image/png", "/done.png"))
        val message = ChatMessage(1, "", fromUser = false, process = listOf(
            ProcessEvent(1, ProcessEventKind.TOOL, imageGeneration = completed),
            ProcessEvent(2, ProcessEventKind.TOOL, live = true, imageGeneration = ImageGenerationProgress("pending")),
        ))
        val stopped = message.stopImageGenerationPreviews()
        assertEquals(completed, stopped.process.first().imageGeneration)
        assertFalse(stopped.process.last().imageGeneration!!.active)
        assertFalse(stopped.process.last().live)
    }
}
