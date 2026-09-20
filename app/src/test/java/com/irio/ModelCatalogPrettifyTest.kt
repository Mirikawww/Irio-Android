package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method

class ModelCatalogPrettifyTest {

    private fun prettify(id: String): String {
        val m: Method = ModelCatalog::class.java.getDeclaredMethod("prettify", String::class.java)
        m.isAccessible = true
        return m.invoke(ModelCatalog, id) as String
    }

    @Test
    fun versionDotsPreserved() {
        assertEquals("Grok 4.20 0309", prettify("grok-4.20-0309"))
        assertEquals("Grok 4.20 0309 Asdasd", prettify("grok-4.20-0309-asdasd"))
        assertEquals("Grok 4.20 0309", prettify("grok-4-20-0309"))
        assertEquals("Grok 4.20 Beta 0309 Non Reasoning", prettify("grok-4-20-beta-0309-non-reasoning"))
        assertEquals("GPT 4o Mini", prettify("gpt-4o-mini"))
        assertEquals("Gemini 2.5 Flash", prettify("gemini-2.5-flash"))
        assertEquals("Llama 3.1 8b Instruct Fp8", prettify("llama-3.1-8b-instruct-fp8"))
    }

    @Test
    fun acronymsUppercased() {
        assertEquals("GPT Image 2.5 Flare", prettify("gpt-image-2.5-flare"))
        assertEquals("GPT Image 2.5 Sunburst", prettify("gpt-image-2.5-sunburst"))
        assertEquals("GPT Image 2.5", prettify("gpt-image-2.5"))
        assertEquals("GLM 4.5", prettify("glm-4.5"))
    }

    @Test
    fun brandNamesKeepFirstCapOnly() {
        assertEquals("Llama 3.1 8b Instruct Fp8", prettify("llama-3.1-8b-instruct-fp8"))
        assertEquals("Deepseek Chat", prettify("deepseek-chat"))
        assertEquals("Qwen 3 Max", prettify("qwen-3-max"))
    }
}
