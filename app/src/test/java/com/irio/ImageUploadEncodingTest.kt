package com.irio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageUploadEncodingTest {
    @Test fun defaultConversionProducesWebpAndPreservesTransparency() {
        val file = File.createTempFile("upload-webp-", ".png")
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            bitmap.setPixel(40, 30, Color.RED)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val original = file.readBytes()
            val encoded = ImageUploadEncoding(ImageUploadOptions()).prepare(ChatAttachment("transparent.png", "image/png", file.absolutePath))
            assertEquals("image/webp", encoded.mimeType)
            assertEquals("RIFF", String(encoded.bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WEBP", String(encoded.bytes, 8, 4, Charsets.US_ASCII))
            val sent = BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size)!!
            try {
                assertEquals(80, sent.width); assertEquals(60, sent.height)
                assertEquals("WebP must keep alpha rather than flatten to white", 0, Color.alpha(sent.getPixel(0, 0)))
            } finally { sent.recycle() }
            assertArrayEquals(original, file.readBytes())
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun imageEditingMultipartUsesSelectedMimeAndFilenameAndLeavesReferenceUntouched() {
        val file = File.createTempFile("upload-edit-", ".png")
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        val context = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(context)
        val previous = ThemeStore.imageUploadOptions()
        try {
            ThemeStore.saveImageUploadCompression(context, true)
            bitmap.eraseColor(Color.RED)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val before = file.readBytes()
            for (format in ImageUploadFormat.entries) MockWebServer().use { server ->
                ThemeStore.saveImageUploadFormat(context, format)
                server.enqueue(MockResponse().setResponseCode(503).setBody("local fixture"))
                val reference = ChatAttachment("reference.png", "image/png", file.absolutePath)
                ProviderApi.generateImage(ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture"),
                    "gpt-image-2", "Edit", context = ProviderApi.ImageGenContext("", emptyList(), listOf(reference)))
                val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("No edits request")
                assertTrue(request.path!!.endsWith("/images/edits"))
                val bytes = request.body.readByteArray()
                val body = bytes.toString(Charsets.ISO_8859_1)
                assertTrue(body.contains("filename=\"reference.${format.extension}\""))
                assertTrue(body.contains("Content-Type: ${format.mimeType}\r\n\r\n"))
                val payload = body.substringAfter("Content-Type: ${format.mimeType}\r\n\r\n").substringBefore("\r\n--")
                    .toByteArray(Charsets.ISO_8859_1)
                val sent = BitmapFactory.decodeByteArray(payload, 0, payload.size)!!
                try { assertEquals(80, sent.width); assertEquals(60, sent.height) } finally { sent.recycle() }
                assertArrayEquals(before, file.readBytes())
            }
        } finally {
            bitmap.recycle(); file.delete()
            ThemeStore.saveImageUploadCompression(context, previous.enabled)
            ThemeStore.saveImageUploadFormat(context, previous.format)
        }
    }

    @Test fun qualityControlsActualJpegAndDisabledModePreservesExactBytes() {
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(300 * 200) { i ->
            val n = java.util.Random(i.toLong()).nextInt()
            Color.rgb(n and 255, (n shr 8) and 255, (n shr 16) and 255)
        }
        bitmap.setPixels(pixels, 0, 300, 0, 0, 300, 200)
        val file = File.createTempFile("upload-noise-", ".png")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val source = ChatAttachment("noise.png", "image/png", file.absolutePath)
            val raw = file.readBytes()
            for (format in ImageUploadFormat.entries) {
            val low = ImageUploadEncoding(ImageUploadOptions(quality = 40, format = format)).prepare(source)
            val high = ImageUploadEncoding(ImageUploadOptions(quality = 100, format = format)).prepare(source)
            assertEquals(format.mimeType, low.mimeType)
            assertTrue("Quality setting must affect encoded bytes", low.bytes.size < high.bytes.size)
            assertTrue("$format compression should reduce this PNG fixture", low.bytes.size < raw.size)
            }
            val disabled = ImageUploadEncoding(ImageUploadOptions(enabled = false)).prepare(source)
            assertEquals("image/png", disabled.mimeType)
            assertArrayEquals(raw, disabled.bytes)
            assertArrayEquals(raw, file.readBytes())
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun transparencyUsesWhiteAndNoMaximumEdgeIsApplied() {
        val bitmap = Bitmap.createBitmap(10_010, 2, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("upload-wide-", ".png")
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val prepared = ImageUploadEncoding(ImageUploadOptions(format = ImageUploadFormat.JPEG)).prepare(ChatAttachment("wide.png", "image/png", file.absolutePath))
            val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)!!
            try {
                assertEquals("Do not copy RikkaHub's 10,000px cap", 10_010, decoded.width)
                assertEquals(2, decoded.height)
                val color = decoded.getPixel(5000, 0)
                assertTrue(Color.red(color) > 245 && Color.green(color) > 245 && Color.blue(color) > 245)
            } finally { decoded.recycle() }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun gifAnimatedWebpInvalidImagesAndDocumentsKeepTheirExactBytes() {
        val gif = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val webp = ByteArray(32).also {
            "RIFF".toByteArray().copyInto(it); "WEBPVP8X".toByteArray().copyInto(it, 8); it[20] = 2
        }
        for ((mime, bytes) in listOf("image/gif" to gif, "image/webp" to webp,
            "image/png" to byteArrayOf(1, 2, 3), "application/pdf" to "%PDF fixture".toByteArray())) {
            val file = File.createTempFile("upload-original-", ".bin").apply { writeBytes(bytes) }
            try {
                val result = ImageUploadEncoding().prepare(ChatAttachment("original", mime, file.absolutePath))
                assertEquals(mime, result.mimeType)
                assertArrayEquals(bytes, result.bytes)
            } finally { file.delete() }
        }
    }

    @Test fun exifOrientationIsNormalizedWithoutLosingImageContent() {
        val file = File.createTempFile("upload-oriented-", ".jpg")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            android.media.ExifInterface(file.absolutePath).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val result = ImageUploadEncoding().prepare(ChatAttachment("rotated.jpg", "image/jpeg", file.absolutePath))
            val sent = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)!!
            try { assertEquals(40, sent.width); assertEquals(80, sent.height) } finally { sent.recycle() }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun requestLocalCacheReusesResultsButInvalidatesChangedFiles() {
        val file = File.createTempFile("upload-cache-", ".png")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val encoder = ImageUploadEncoding(ImageUploadOptions(quality = 90))
            val attachment = ChatAttachment("same.png", "image/png", file.absolutePath)
            val first = encoder.encode(attachment)
            assertSame(first, encoder.encode(attachment))
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val changed = encoder.encode(attachment)
            assertNotSame(first, changed)
            assertEquals("image/png", changed.mimeType)
            assertArrayEquals(file.readBytes(), Base64.getDecoder().decode(changed.base64))
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun allChatProtocolsSendSelectedFormatWithoutChangingTheOriginalImage() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(context)
        val original = File.createTempFile("upload-fixture-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val before = original.readBytes()
        val previous = ThemeStore.imageUploadOptions()
        try {
            ThemeStore.saveImageUploadQuality(context, 90)
            for (format in ImageUploadFormat.entries) for (enabled in listOf(true, false)) for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.MINIMAX_VIDEO }) MockWebServer().use { server ->
                ThemeStore.saveImageUploadFormat(context, format)
                ThemeStore.saveImageUploadCompression(context, enabled)
                IrioHttp.resetForTests()
                server.enqueue(MockResponse().setResponseCode(503).setBody("local fixture"))
                val result = ChatApi.streamChat(
                    ProviderConfig("fixture", "Fixture", server.url("/v1beta").toString(), "fixture", protocol),
                    "fixture-model", listOf(ChatRequestMessage("user", "describe", listOf(
                        ChatAttachment("original.png", "image/png", original.absolutePath)))),
                    ReasoningEffort.OFF, false, toolRegistry = ToolRegistry(),
                    cancellation = ChatRequestCancellation(), onUpdate = {},
                )
                assertNotNull(result.error)
                val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("No request for $protocol")
                val (mime, bytes) = imageData(protocol, JSONObject(request.body.readUtf8()))
                assertEquals("$protocol must respect the saved switch and format", if (enabled) format.mimeType else "image/png", mime)
                if (enabled && format == ImageUploadFormat.JPEG) {
                    assertEquals(0xff, bytes[0].toInt() and 255)
                    assertEquals(0xd8, bytes[1].toInt() and 255)
                } else if (enabled) {
                    assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
                    assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
                } else assertArrayEquals("Disabled sends the exact original bytes", before, bytes)
                val sent = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
                try {
                    assertEquals(80, sent.width)
                    assertEquals(60, sent.height)
                } finally { sent.recycle() }
                assertArrayEquals("Conversion must not overwrite the original", before, original.readBytes())
            }
        } finally {
            original.delete(); IrioHttp.resetForTests()
            ThemeStore.saveImageUploadCompression(context, previous.enabled)
            ThemeStore.saveImageUploadQuality(context, previous.quality)
            ThemeStore.saveImageUploadFormat(context, previous.format)
        }
    }

    private fun imageData(protocol: ProviderProtocol, json: JSONObject): Pair<String, ByteArray> {
        return when (protocol) {
            ProviderProtocol.MINIMAX_VIDEO -> error("Video protocol is not chat")
            ProviderProtocol.CHAT_COMPLETIONS -> decodeUrl(json.getJSONArray("messages").getJSONObject(0)
                .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"))
            ProviderProtocol.RESPONSES -> decodeUrl(json.getJSONArray("input").getJSONObject(0)
                .getJSONArray("content").getJSONObject(1).getString("image_url"))
            ProviderProtocol.ANTHROPIC -> json.getJSONArray("messages").getJSONObject(0)
                .getJSONArray("content").getJSONObject(1).getJSONObject("source").let {
                    it.getString("media_type") to Base64.getDecoder().decode(it.getString("data"))
                }
            ProviderProtocol.GEMINI -> json.getJSONArray("contents").getJSONObject(0)
                .getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData").let {
                    it.getString("mimeType") to Base64.getDecoder().decode(it.getString("data"))
                }
            ProviderProtocol.GEMINI_INTERACTIONS -> json.getJSONArray("input").getJSONObject(0)
                .getJSONArray("content").getJSONObject(1).let {
                    it.getString("mime_type") to Base64.getDecoder().decode(it.getString("data"))
                }
        }
    }
    private fun decodeUrl(url: String) = url.substringAfter("data:").substringBefore(';') to
        Base64.getDecoder().decode(url.substringAfter(','))
}
