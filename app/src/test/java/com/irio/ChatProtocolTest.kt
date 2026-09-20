package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatProtocolTest {
    @Test
    fun chatCompletionsOffIsExplicit() {
        val body = JSONObject()
        ChatProtocol.putReasoning(body, ProviderProtocol.CHAT_COMPLETIONS, ReasoningEffort.OFF)
        assertEquals("none", body.getString("reasoning_effort"))
        assertFalse(body.has("reasoning"))
    }

    @Test
    fun responsesOffAndAutomaticAreDistinct() {
        val off = JSONObject()
        ChatProtocol.putReasoning(off, ProviderProtocol.RESPONSES, ReasoningEffort.OFF)
        assertEquals("none", off.getJSONObject("reasoning").getString("effort"))

        val automatic = JSONObject()
        ChatProtocol.putReasoning(automatic, ProviderProtocol.RESPONSES, ReasoningEffort.AUTO)
        assertFalse(automatic.has("reasoning"))
    }

    @Test
    fun anthropicOffIsDisabledAndAutomaticIsOmitted() {
        val off = JSONObject()
        ChatProtocol.putReasoning(off, ProviderProtocol.ANTHROPIC, ReasoningEffort.OFF)
        assertEquals("disabled", off.getJSONObject("thinking").getString("type"))

        val automatic = JSONObject()
        ChatProtocol.putReasoning(automatic, ProviderProtocol.ANTHROPIC, ReasoningEffort.AUTO)
        assertFalse(automatic.has("thinking"))
    }

    @Test
    fun chatCompletionsParsesUsageFromStreamChunk() {
        val update = ChatProtocol.parseChatCompletions(
            """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":40,"total_tokens":160}}""",
        )!!
        assertEquals(120, update.usage?.promptTokens)
        assertEquals(40, update.usage?.completionTokens)
        assertEquals(160, update.usage?.totalTokens)
        assertEquals(160, update.usage?.usedTokens)
    }

    @Test
    fun parseUsageAcceptsAnthropicInputOutput() {
        val usage = ChatProtocol.parseUsage(
            JSONObject("""{"input_tokens":80,"output_tokens":20}"""),
        )
        assertEquals(80, usage?.promptTokens)
        assertEquals(20, usage?.completionTokens)
        assertEquals(100, usage?.totalTokens)
    }
}
