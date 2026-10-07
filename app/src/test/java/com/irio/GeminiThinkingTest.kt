package com.irio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiThinkingTest {
    private fun config(model: String, effort: ReasoningEffort): JSONObject {
        val body = JSONObject()
        ChatProtocol.putReasoning(body, ProviderProtocol.GEMINI, effort, model)
        return body.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
    }

    @Test
    fun mapsThinkingDepthToModelSupportedLevelsAndBudgets() {
        assertEquals("HIGH", config("gemini-3-flash-preview", ReasoningEffort.XHIGH).getString("thinkingLevel"))
        assertEquals("MINIMAL", config("gemini-3-flash-preview", ReasoningEffort.OFF).getString("thinkingLevel"))
        assertEquals("LOW", config("gemini-3.8-flash", ReasoningEffort.OFF).getString("thinkingLevel"))
        assertEquals("LOW", config("gemini-3.1-pro-preview", ReasoningEffort.OFF).getString("thinkingLevel"))
        assertEquals("MEDIUM", config("gemini-3.1-pro-preview", ReasoningEffort.MEDIUM).getString("thinkingLevel"))
        assertEquals("HIGH", config("gemini-3-pro-preview", ReasoningEffort.MEDIUM).getString("thinkingLevel"))
        assertEquals("MINIMAL", config("gemini-3.1-flash-lite-image", ReasoningEffort.LOW).getString("thinkingLevel"))
        assertEquals(0, config("gemini-2.5-flash", ReasoningEffort.OFF).getInt("thinkingBudget"))
        assertEquals(128, config("gemini-2.5-pro", ReasoningEffort.OFF).getInt("thinkingBudget"))
        assertEquals(4096, config("gemini-2.5-flash", ReasoningEffort.MEDIUM).getInt("thinkingBudget"))
        assertEquals(24576, config("gemini-2.5-flash", ReasoningEffort.MAX).getInt("thinkingBudget"))
        assertEquals(32768, config("gemini-2.5-pro", ReasoningEffort.MAX).getInt("thinkingBudget"))
        assertTrue(config("gemini-3-flash-preview", ReasoningEffort.AUTO).getBoolean("includeThoughts"))
        assertFalse(config("gemini-3-flash-preview", ReasoningEffort.AUTO).has("thinkingLevel"))
        val older = JSONObject()
        ChatProtocol.putReasoning(older, ProviderProtocol.GEMINI, ReasoningEffort.HIGH, "gemini-2.0-flash")
        assertFalse(older.has("generationConfig"))
    }
}
