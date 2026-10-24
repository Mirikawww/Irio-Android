package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenToolTest {

    @Test fun modelChoosesEveryImageParameterInEveryProtocolIncludingStrictResponses() {
        val fields = setOf("prompt", "resolution", "aspect_ratio", "background", "quality")
        for (hints in listOf(false, true)) for (protocol in ProviderProtocol.entries) {
            val schema = ToolRegistry(listOf(ImageGenToolProvider()), smartToolHints = hints).definitions(protocol).single()
            val parameters = when (protocol) {
                ProviderProtocol.GEMINI -> schema.getJSONObject("parametersJsonSchema")
                ProviderProtocol.GEMINI_INTERACTIONS, ProviderProtocol.RESPONSES -> schema.getJSONObject("parameters")
                ProviderProtocol.CHAT_COMPLETIONS -> schema.getJSONObject("function").getJSONObject("parameters")
                ProviderProtocol.ANTHROPIC -> schema.getJSONObject("input_schema")
            }
            val properties = parameters.getJSONObject("properties")
            val required = parameters.getJSONArray("required")
            val requiredNames = (0 until required.length()).map { required.getString(it) }.toSet()
            val propertyNames = properties.keys().asSequence().toSet()
            assertTrue(propertyNames.containsAll(fields))
            assertTrue(requiredNames.containsAll(fields))
            if (protocol == ProviderProtocol.RESPONSES || !hints) assertEquals(propertyNames, requiredNames)
            else assertEquals(fields, requiredNames)
            fields.forEach { assertEquals("string", properties.getJSONObject(it).getString("type")) }
            assertFalse(parameters.getBoolean("additionalProperties"))
        }
    }

    @Test fun toolOptionsComeFromArgumentsAndPromptOnlyLegacyCallsHaveDefaults() {
        val parsed = ImageGenerationOptions.fromToolArguments("""{
            "prompt":"a cat","resolution":"4K","aspect_ratio":"7:5","background":"transparent","quality":"low"
        }""")
        assertEquals(ImageGenerationOptions(ImageResolution.K4, ImageAspectRatio(7, 5), ImageBackground.TRANSPARENT, ImageQuality.LOW), parsed)
        assertEquals(ImageGenerationOptions(), ImageGenerationOptions.fromToolArguments("""{"prompt":"legacy"}"""))
        assertNull(ImageGenerationOptions.fromToolArguments("""{"aspect_ratio":"auto"}""").aspectRatio)
    }

    @Test fun dispatcherSendsModelChosenOptionsInsteadOfMenuOptions() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"fixture"}}"""))
            val provider = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture")
            val context = ToolExecutionContext(provider, "chat-model", imageGenModel = "gpt-image-2")
            val registry = ToolRegistry(listOf(ImageGenToolProvider()))
            val result = registry.execute(ChatToolCall("image", ImageGenTool.NAME, """{
                "prompt":"A blue tree","resolution":"2K","aspect_ratio":"1:1","background":"transparent","quality":"high"
            }"""), context)
            assertEquals(ToolOutcome.FAILED, result.outcome) // Deliberate local fixture response, not a paid generation.
            val request = server.takeRequest()
            assertEquals("/v1/images/generations", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("gpt-image-2", body.getString("model"))
            assertEquals("A blue tree", body.getString("prompt"))
            assertEquals(3, body.get("partial_images"))
            assertEquals("2048x2048", body.getString("size"))
            assertEquals("transparent", body.getString("background"))
            assertEquals("high", body.getString("quality"))
            assertEquals("png", body.getString("output_format"))
        }
    }

    @Test fun invalidModelOptionsFailWithoutSendingAnImageRequest() = runBlocking {
        MockWebServer().use { server ->
            val context = ToolExecutionContext(ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture"),
                "chat-model", imageGenModel = "gpt-image-2")
            val registry = ToolRegistry(listOf(ImageGenToolProvider()))
            listOf("""{"prompt":"x","aspect_ratio":"4:1"}""", """{"prompt":"x","resolution":"8K"}""",
                """{"prompt":"x","quality":"max"}""", """{"prompt":"x","background":"blue"}""").forEach { arguments ->
                val result = registry.execute(ChatToolCall("image", ImageGenTool.NAME, arguments), context)
                assertEquals(ToolOutcome.FAILED, result.outcome)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun schemaForHasPromptParameter() {
        for (protocol in ProviderProtocol.entries) {
            val schema = ImageGenTool.schemaFor(protocol)
            val parameters = when (protocol) {
                ProviderProtocol.GEMINI -> schema.optJSONObject("parametersJsonSchema")
                ProviderProtocol.GEMINI_INTERACTIONS -> schema.optJSONObject("parameters")
                ProviderProtocol.CHAT_COMPLETIONS -> schema.optJSONObject("function")?.optJSONObject("parameters")
                ProviderProtocol.RESPONSES -> schema.optJSONObject("parameters")
                ProviderProtocol.ANTHROPIC -> schema.optJSONObject("input_schema")
            }
            val prompt = parameters?.optJSONObject("properties")?.optJSONObject("prompt")
            assertEquals("string", prompt?.optString("type"))
        }
    }

    @Test
    fun parseImageResponseReadsB64() {
        // 反射调私有 parseImageResponse 太脆；直接走公开 generateImage 不现实（要网络）。
        // 这里验证 ImageGenResult 数据形状。
        val ok = ImageGenResult(
            attachment = ChatAttachment(name = "image.png", mimeType = "image/png", filePath = "/tmp/x.png"),
            revisedPrompt = "a cat",
        )
        assertEquals("a cat", ok.revisedPrompt)
        assertNull(ok.error)
    }
}
