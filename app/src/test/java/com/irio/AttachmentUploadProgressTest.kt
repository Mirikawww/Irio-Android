package com.irio

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AttachmentUploadProgressTest {
    @After fun resetClient() = IrioHttp.resetForTests()

    @Test fun requestBodyReportsWrittenBytesWithoutChangingPayload() {
        val payload = ByteArray(2 * 64 * 1024 + 7) { it.toByte() }
        val progress = mutableListOf<Pair<Long, Long>>()
        val body = UploadProgressRequestBody(payload) { sent, total -> progress += sent to total }
        val sink = Buffer()
        body.writeTo(sink)

        assertEquals(payload.size.toLong(), body.contentLength())
        assertArrayEquals(payload, sink.readByteArray())
        assertEquals(listOf(65_536L, 131_072L, payload.size.toLong()), progress.map { it.first })
        assertTrue(progress.all { it.second == payload.size.toLong() })
    }

    @Test fun trackerKeepsConversationsAndRetriesSeparate() {
        val tracker = AttachmentUploadTracker()
        tracker.prepare("one", 10, 11)
        tracker.prepare("two", 10, 21)
        tracker.advance("one", 10, 11, 50, 100)
        tracker.advance("one", 10, 99, 100, 100)
        assertEquals(50, tracker.progress("one", 10)?.percent)
        assertEquals(0, tracker.progress("two", 10)?.percent)

        tracker.prepare("one", 10, 12)
        tracker.finish("one", 10, 11)
        assertEquals(12L, tracker.progress("one", 10)?.requestId)
        tracker.advance("one", 10, 12, 100, 100)
        tracker.hideCompleted("one", 10, 12)
        assertNull(tracker.progress("one", 10))
        tracker.advance("one", 10, 12, 10, 100) // An HTTP/1 retry can start over.
        assertEquals(10, tracker.progress("one", 10)?.percent)
        tracker.finish("one", 10, 12)
        assertNull(tracker.progress("one", 10))
        assertEquals(21L, tracker.progress("two", 10)?.requestId)
    }

    @Test fun attachmentRequestReportsActualSerializedBodyWrite() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val file = Files.createTempFile("irio-upload-progress", ".pdf").toFile()
            try {
                file.writeBytes(ByteArray(100_000) { it.toByte() })
                val progress = mutableListOf<Pair<Long, Long>>()
                val result = ChatApi.streamChat(
                    provider = ProviderConfig("p", "Provider", server.url("/v1").toString(), "test"),
                    model = "m",
                    messages = listOf(ChatRequestMessage("user", "read",
                        attachments = listOf(ChatAttachment("document.pdf", "application/pdf", file.absolutePath)))),
                    reasoningEffort = ReasoningEffort.OFF, fastMode = false,
                    toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(),
                    onUpdate = {}, onUploadProgress = { sent, total -> progress += sent to total },
                )
                val request = server.takeRequest()
                val posted = request.body.readUtf8()
                assertEquals(null, result.error)
                assertEquals("ok", result.content)
                assertTrue(posted.contains("file_data"))
                assertTrue(progress.size > 1)
                assertTrue(progress.zipWithNext().all { (previous, next) -> next.first > previous.first })
                assertEquals(request.getHeader("Content-Length")?.toLong(), progress.last().second)
                assertEquals(progress.last().second, progress.last().first)
            } finally { file.delete() }
        }
    }
}
