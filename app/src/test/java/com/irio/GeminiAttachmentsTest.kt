package com.irio

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiAttachmentsTest {
    @Test
    fun serializesMediaAndParsesInlineResponse() {
        val file = Files.createTempFile("irio-gemini-test", ".pdf").toFile()
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            val body = GeminiProtocol.requestBody(listOf(ChatRequestMessage("user", "read", listOf(ChatAttachment("file.pdf", "application/pdf", file.absolutePath)))), null)
            val data = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData")
            assertEquals("application/pdf", data.getString("mimeType"))
            assertEquals("AQID", data.getString("data"))
            val update = GeminiProtocol.parse("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AQID"}}]},"finishReason":"STOP"}]}""")!!
            try {
                assertEquals(1, update.attachments.size)
                assertEquals("image/png", update.attachments.single().mimeType)
                assertArrayEquals(byteArrayOf(1, 2, 3), update.attachments.single().bytes())
            } finally { update.attachments.forEach { java.io.File(it.filePath).delete() } }
        } finally { file.delete() }
    }

    @Test
    fun rejectsLargeInlineRequestsBeforeLoadingFiles() {
        val file = Files.createTempFile("irio-gemini-large", ".bin").toFile()
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(101L * 1024 * 1024) }
            assertThrows(IllegalArgumentException::class.java) {
                GeminiProtocol.requestBody(listOf(ChatRequestMessage("user", attachments = listOf(ChatAttachment("large", "application/pdf", file.absolutePath)))), null)
            }
        } finally { file.delete() }
    }
}
