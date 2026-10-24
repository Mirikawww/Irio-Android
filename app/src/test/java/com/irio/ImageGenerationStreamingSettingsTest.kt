package com.irio

import android.content.Context
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ImageGenerationStreamingSettingsTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGJkAAAAASUVORK5CYII="
    private val application get() = RuntimeEnvironment.getApplication()

    private fun reload() {
        ThemeStore::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(ThemeStore, false)
        ThemeStore.ensureLoaded(application)
    }

    private fun withSavedSetting(check: () -> Unit) {
        val prefs = application.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val stored = if (prefs.contains("image_generation_streaming")) prefs.getBoolean("image_generation_streaming", false) else null
        try {
            prefs.edit().remove("image_generation_streaming").commit()
            reload()
            check()
        } finally {
            prefs.edit().apply {
                if (stored == null) remove("image_generation_streaming") else putBoolean("image_generation_streaming", stored)
            }.commit()
            reload()
        }
    }

    @Test fun defaultsOffAndPersistsBothDirectionsAcrossReloads() = withSavedSetting {
        assertFalse(ThemeStore.imageGenerationStreaming)
        for (enabled in listOf(true, false)) {
            ThemeStore.saveImageGenerationStreaming(application, enabled)
            assertEquals(enabled, ThemeStore.imageGenerationStreaming)
            reload()
            assertEquals(enabled, ThemeStore.imageGenerationStreaming)
        }
    }

    @Test fun normalGenerationAndModelToolUseTheSavedTransportSetting() = withSavedSetting {
        for (enabled in listOf(false, true)) for (tool in listOf(false, true)) {
            ThemeStore.saveImageGenerationStreaming(application, enabled)
            MockWebServer().use { server ->
                val body = if (enabled) {
                    "data: {\"type\":\"image_generation.partial_image\",\"partial_image_index\":0,\"b64_json\":\"$png\"}\n\n" +
                        "data: {\"type\":\"image_generation.completed\",\"b64_json\":\"$png\"}\n\n"
                } else """{"data":[{"b64_json":"$png"}]}"""
                server.enqueue(MockResponse().setHeader("Content-Type", if (enabled) "text/event-stream" else "application/json").setBody(body))
                val provider = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture")
                val progress = mutableListOf<ImageGenerationProgress>()
                val attachments = mutableListOf<ChatAttachment>()
                try {
                    if (tool) runBlocking {
                        val result = ImageGenToolProvider().execute(
                            ChatToolCall("image", ImageGenTool.NAME, """{"prompt":"fixture"}"""),
                            ToolExecutionContext(provider, "chat-model", imageGenModel = "gpt-image-2", onUpdate = {
                                progress += it.imageGeneration
                                attachments += it.attachments
                            }),
                        )
                        assertEquals(ToolOutcome.SUCCESS, result.outcome)
                    } else {
                        val result = ProviderApi.generateImage(provider, "gpt-image-2", "fixture", onProgress = { progress += it })
                        assertNull(result.error)
                        result.attachment?.let(attachments::add)
                    }
                    assertEquals(1, attachments.size)
                    val request = server.takeRequest()
                    val json = JSONObject(request.body.readUtf8())
                    assertEquals(enabled, json.getBoolean("stream"))
                    assertEquals(enabled, json.has("partial_images"))
                    if (enabled) assertEquals(3, json.getInt("partial_images"))
                    assertEquals(enabled, request.getHeader("Accept")!!.contains("text/event-stream"))
                    assertEquals(enabled, progress.any { it.partialIndex == 0 })
                    assertEquals(attachments.single(), progress.last().finalAttachment)
                } finally { attachments.forEach { File(it.filePath).delete() } }
            }
        }
    }

    @Test fun editsAndFallbackKeepTheOriginalSettingEvenIfItChangesInFlight() = withSavedSetting {
        val source = File.createTempFile("stream-setting-", ".png").apply { writeBytes(java.util.Base64.getDecoder().decode(png)) }
        try {
            for (enabled in listOf(false, true)) for (fallback in listOf(false, true)) {
                ThemeStore.saveImageGenerationStreaming(application, enabled)
                MockWebServer().use { server ->
                    if (fallback) server.enqueue(MockResponse().setResponseCode(404))
                    server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"$png"}]}"""))
                    val result = ProviderApi.generateImage(
                        ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture"), "gpt-image-2", "new prompt",
                        ProviderApi.ImageGenContext("old prompt", listOf(ChatAttachment("previous.png", "image/png", source.absolutePath))),
                        onProgress = { ThemeStore.saveImageGenerationStreaming(application, !enabled) },
                    )
                    try {
                        assertNull(result.error)
                        assertNotNull(result.attachment)
                        val edit = server.takeRequest()
                        val multipart = edit.body.readUtf8()
                        assertEquals("/v1/images/edits", edit.path)
                        assertTrue(multipart.contains("name=\"stream\"\r\n\r\n$enabled\r\n"))
                        assertEquals(enabled, multipart.contains("name=\"partial_images\""))
                        assertEquals(enabled, edit.getHeader("Accept")!!.contains("text/event-stream"))
                        if (fallback) {
                            val request = server.takeRequest()
                            assertEquals("/v1/images/generations", request.path)
                            val json = JSONObject(request.body.readUtf8())
                            assertEquals(enabled, json.getBoolean("stream"))
                            assertEquals(enabled, json.has("partial_images"))
                            assertTrue(json.getString("prompt").contains("old prompt"))
                        }
                    } finally { result.attachment?.let { File(it.filePath).delete() } }
                }
            }
        } finally { source.delete() }
    }

    @Test fun searchableInChineseAndEnglish() {
        val chinese = searchSettings("图片生成流式传输") { it }.single()
        assertEquals("appearance.imageStreaming", chinese.id)
        assertEquals(AppearanceSettingsActivity::class.java, chinese.destination)
        val english = searchSettings("Stream image generation") { translate(AppLanguage.EN, it) }.single()
        assertEquals(chinese.id, english.id)
    }
}
