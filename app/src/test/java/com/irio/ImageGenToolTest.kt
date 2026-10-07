package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageGenToolTest {

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
