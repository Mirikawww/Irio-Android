package com.irio

import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
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
@Config(sdk = [35], manifest = Config.NONE)
class ImageGenerationProviderRoutingTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun missingDisabledOrRemovedImageProvidersNeverFallBackToChatCredentials() {
        MockWebServer().use { chatServer -> MockWebServer().use { imageServer ->
            val chat = ProviderConfig("chat", "Chat", chatServer.url("/chat").toString(), "fixture-chat-key", models = listOf("chat-model"))
            val image = ProviderConfig("images", "Images", imageServer.url("/images").toString(), "fixture-image-key", models = listOf("gpt-image-2"))
            for (configured in listOf(emptyList(), listOf(image.copy(enabled = false)),
                listOf(image.copy(disabledModels = setOf("gpt-image-2"))), listOf(image.copy(models = listOf("different-model"))))) {
                withProviders(listOf(chat) + configured) {
                    val updates = mutableListOf<ChatStreamUpdate>()
                    val result = runBlocking { ToolRegistry.default().execute(
                        ChatToolCall("missing-image", "generate_image", """{"prompt":"Never contact chat"}"""),
                        ToolExecutionContext(chat, "chat-model", imageGenModel = "gpt-image-2", appContext = app,
                            onUpdate = { updates += it })) }
                    assertEquals(ToolOutcome.FAILED, result.outcome)
                    assertTrue(result.text.contains(tr("图片生成工具当前不可用：找不到已启用的图片模型提供商，请检查模型设置")))
                    assertEquals(listOf(true, false), updates.mapNotNull { it.toolActive })
                    assertEquals(ToolOutcome.FAILED, updates.last().toolOutcome)
                }
            }
            assertEquals(0, chatServer.requestCount)
            assertEquals(0, imageServer.requestCount)
        } }
    }

    @Test fun referenceImageEditsUseTheImageProvidersCredentials() {
        MockWebServer().use { chatServer -> MockWebServer().use { imageServer ->
            imageServer.enqueue(MockResponse().setResponseCode(503).setBody("offline fixture"))
            val chat = ProviderConfig("chat", "Chat", chatServer.url("/v1").toString(), "fixture-chat-key", models = listOf("chat-model"))
            val image = ProviderConfig("images", "Images", imageServer.url("/v1").toString(), "fixture-image-key", models = listOf("gpt-image-2"))
            ThemeStore.ensureLoaded(app)
            val originalCompression = ThemeStore.imageUploadCompression
            ThemeStore.saveImageUploadCompression(app, false)
            val reference = java.io.File(app.cacheDir, "image-routing-reference.png").apply { writeText("offline-reference-bytes") }
            try {
                withProviders(listOf(chat, image)) {
                    runBlocking { ToolRegistry.default().execute(ChatToolCall("edit", "generate_image", """{"prompt":"Edit reference"}"""),
                        ToolExecutionContext(chat, "chat-model", imageGenModel = "gpt-image-2", appContext = app,
                            history = listOf(ChatRequestMessage("user", "Reference", attachments = listOf(ChatAttachment("reference.png", "image/png", reference.absolutePath)))))) }
                    val request = imageServer.takeRequest(2, TimeUnit.SECONDS)
                    assertNotNull(request)
                    assertEquals("/v1/images/edits", request!!.path)
                    assertEquals("Bearer fixture-image-key", request.getHeader("Authorization"))
                    assertTrue(request.body.readUtf8().contains("offline-reference-bytes"))
                    assertEquals(0, chatServer.requestCount)
                }
            } finally {
                reference.delete()
                ThemeStore.saveImageUploadCompression(app, originalCompression)
            }
        } }
    }

    @Test fun imageProvidersProtocolAndGeminiAuthenticationAreUsedTogether() {
        MockWebServer().use { chatServer -> MockWebServer().use { imageServer ->
            imageServer.enqueue(MockResponse().setResponseCode(503).setBody("offline fixture"))
            val chat = ProviderConfig("chat", "Chat", chatServer.url("/v1").toString(), "fixture-chat-key", models = listOf("chat-model"))
            val model = "gemini-3-pro-image-preview"
            val image = ProviderConfig("images", "Images", imageServer.url("/v1beta").toString(), "fixture-google-key", ProviderProtocol.GEMINI, models = listOf(model))
            withProviders(listOf(chat, image)) {
                runBlocking { ToolRegistry.default().execute(ChatToolCall("gemini-image", "generate_image", """{"prompt":"Offline fixture"}"""),
                    ToolExecutionContext(chat, "chat-model", imageGenModel = model, appContext = app)) }
                val request = imageServer.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("/v1beta/models/$model:generateContent", request!!.path)
                assertEquals("fixture-google-key", request.getHeader("x-goog-api-key"))
                assertNull(request.getHeader("Authorization"))
                assertTrue(JSONObject(request.body.readUtf8()).has("contents"))
                assertEquals(0, chatServer.requestCount)
            }
        } }
    }

    @Test fun sameProviderStillWorksAndDisabledDuplicateOwnersAreSkipped() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("offline fixture"))
            val shared = ProviderConfig("shared", "Shared", server.url("/v1").toString(), "fixture-shared-key", models = listOf("chat-model", "gpt-image-2"))
            withProviders(listOf(shared.copy(id = "disabled", apiKey = "fixture-disabled-key", enabled = false), shared)) {
                runBlocking { ToolRegistry.default().execute(ChatToolCall("shared-image", "generate_image", """{"prompt":"Offline fixture"}"""),
                    ToolExecutionContext(shared, "chat-model", imageGenModel = "gpt-image-2", appContext = app)) }
                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("Bearer fixture-shared-key", request!!.getHeader("Authorization"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    private fun <T> withProviders(providers: List<ProviderConfig>, block: () -> T): T {
        val preferences = ProviderStore.preferences(app)
        val original = preferences.all
        val field = ProviderStore::class.java.getDeclaredField("cachedSecretKey").apply { isAccessible = true }
        val previousKey = field.get(ProviderStore)
        try {
            preferences.edit().clear().commit()
            field.set(ProviderStore, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
            providers.forEach { ProviderStore.saveProvider(app, it) }
            return block()
        } finally {
            preferences.edit().clear().apply {
                original.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    else -> error("Unexpected fixture preference type")
                } }
            }.commit()
            field.set(ProviderStore, previousKey)
        }
    }

    @Test fun actualRegistryUsesImageProvidersEndpointKeyAndHeadersInsteadOfChatProviders() = runBlocking {
        MockWebServer().use { chatServer -> MockWebServer().use { imageServer ->
            chatServer.enqueue(MockResponse().setResponseCode(503).setBody("wrong provider"))
            imageServer.enqueue(MockResponse().setResponseCode(503).setBody("deliberate offline image response"))
            val chat = ProviderConfig("chat", "Chat", chatServer.url("/chat-api").toString(), "fixture-chat-key",
                models = listOf("chat-model"), customHeaders = listOf(ProviderHeader("X-Fixture-Owner", "chat")))
            val image = ProviderConfig("images", "Images", imageServer.url("/image-api").toString(), "fixture-image-key",
                models = listOf("gpt-image-2"), customHeaders = listOf(ProviderHeader("X-Fixture-Owner", "images")))
            withProviders(listOf(chat, image)) {
                val updates = mutableListOf<ChatStreamUpdate>()
                val result = runBlocking { ToolRegistry.default().execute(
                    ChatToolCall("image", "generate_image", """{"prompt":"Offline routing fixture"}"""),
                    ToolExecutionContext(chat, "chat-model", imageGenModel = "gpt-image-2", appContext = app,
                        onUpdate = { updates += it })) }
                assertEquals(ToolOutcome.FAILED, result.outcome)
                val request = imageServer.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull("Configured image provider must receive the request, never the chat provider", request)
                assertEquals("/image-api/images/generations", request!!.path)
                assertEquals("Bearer fixture-image-key", request.getHeader("Authorization"))
                assertEquals("images", request.getHeader("X-Fixture-Owner"))
                assertEquals("gpt-image-2", JSONObject(request.body.readUtf8()).getString("model"))
                assertEquals(0, chatServer.requestCount)
                assertEquals(listOf(true, false), updates.mapNotNull { it.toolActive })
            }
        } }
    }
}
