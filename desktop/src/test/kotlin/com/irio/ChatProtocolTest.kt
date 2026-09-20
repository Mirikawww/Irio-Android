package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProtocolTest {
    @Test
    fun missingReasoningContentDoesNotHideObjectReasoning() {
        val delta = JSONObject("""{"reasoning":{"content":"plan"}}""")
        assertEquals("plan", ChatProtocol.thinkingFrom(delta))
    }

    @Test
    fun chatCompletionsParsesReasoningObjectWhenReasoningContentMissing() {
        val chunk = """
            {"choices":[{"delta":{"reasoning":{"content":"step one"}},"finish_reason":null}]}
        """.trimIndent()
        val update = ChatProtocol.parseChatCompletions(chunk)!!
        assertEquals("step one", update.thinkingDelta)
        assertEquals("", update.finishReason)
    }

    @Test
    fun streamedToolCallsKeepIdNameAndArguments() {
        val start = ChatProtocol.parseChatCompletions(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":""}}]}}]}"""
        )!!
        val args = ChatProtocol.parseChatCompletions(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"kotlin\"}"}}]}}]}"""
        )!!
        val done = ChatProtocol.parseChatCompletions(
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        )!!
        val collected = linkedMapOf<String, MutableToolCall>()
        ChatProtocol.applyToolCallDeltas(collected, start.toolCallDeltas)
        ChatProtocol.applyToolCallDeltas(collected, args.toolCallDeltas)
        val calls = ChatProtocol.completedToolCalls(collected)
        assertEquals("tool_calls", done.finishReason)
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("web_search", calls[0].name)
        assertTrue(calls[0].arguments.contains("kotlin"))
        assertTrue(ChatProtocol.shouldEndStream(false, done.finishReason, calls.size))
        assertFalse(ChatProtocol.shouldEndStream(false, "tool_calls", 0))
        assertTrue(ChatProtocol.shouldEndStream(true, "", 0))
        assertTrue(ChatProtocol.shouldEndStream(false, "stop", 0))
        assertTrue(ChatProtocol.shouldEndStream(false, "length", 0))
        assertTrue(ChatProtocol.shouldEndStream(false, "end_turn", 0))
    }

    @Test
    fun toolStreamEndsOnFinishReasonOnlyAfterIdAndName() {
        val start = ChatProtocol.parseChatCompletions(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":""}}]}}]}"""
        )!!
        val collected = linkedMapOf<String, MutableToolCall>()
        ChatProtocol.applyToolCallDeltas(collected, start.toolCallDeltas)
        assertFalse(
            ChatProtocol.shouldEndStream(
                false,
                "",
                ChatProtocol.completedToolCalls(collected).size,
            )
        )
        val args = ChatProtocol.parseChatCompletions(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"now\"}"}}]}}]}"""
        )!!
        ChatProtocol.applyToolCallDeltas(collected, args.toolCallDeltas)
        val done = ChatProtocol.parseChatCompletions(
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        )!!
        assertTrue(
            ChatProtocol.shouldEndStream(
                false,
                done.finishReason,
                ChatProtocol.completedToolCalls(collected).size,
            )
        )
    }

    @Test
    fun chatCompletionsSendsTopLevelReasoningEffort() {
        val body = JSONObject()
        ChatProtocol.putReasoning(body, ProviderProtocol.CHAT_COMPLETIONS, ReasoningEffort.HIGH)
        assertEquals("high", body.getString("reasoning_effort"))
        assertFalse(body.has("reasoning"))
    }

    @Test
    fun automaticOmitsReasoning() {
        val body = JSONObject()
        ChatProtocol.putReasoning(body, ProviderProtocol.CHAT_COMPLETIONS, ReasoningEffort.AUTO)
        assertTrue(!body.has("reasoning_effort"))
        assertTrue(!body.has("reasoning"))
    }

    @Test
    fun chatCompletionsDisablesReasoningExplicitly() {
        val body = JSONObject()
        ChatProtocol.putReasoning(body, ProviderProtocol.CHAT_COMPLETIONS, ReasoningEffort.OFF)
        assertEquals("none", body.getString("reasoning_effort"))
        assertFalse(body.has("reasoning"))
    }

    @Test
    fun responsesReasoningUsesProtocolShape() {
        val off = JSONObject()
        ChatProtocol.putReasoning(off, ProviderProtocol.RESPONSES, ReasoningEffort.OFF)
        assertEquals("none", off.getJSONObject("reasoning").getString("effort"))

        val automatic = JSONObject()
        ChatProtocol.putReasoning(automatic, ProviderProtocol.RESPONSES, ReasoningEffort.AUTO)
        assertFalse(automatic.has("reasoning"))
    }

    @Test
    fun anthropicDistinguishesDisabledAutomaticAndEnabledThinking() {
        val off = JSONObject()
        ChatProtocol.putReasoning(off, ProviderProtocol.ANTHROPIC, ReasoningEffort.OFF)
        assertEquals("disabled", off.getJSONObject("thinking").getString("type"))
        assertFalse(off.getJSONObject("thinking").has("budget_tokens"))

        val automatic = JSONObject()
        ChatProtocol.putReasoning(automatic, ProviderProtocol.ANTHROPIC, ReasoningEffort.AUTO)
        assertFalse(automatic.has("thinking"))

        val high = JSONObject()
        ChatProtocol.putReasoning(high, ProviderProtocol.ANTHROPIC, ReasoningEffort.HIGH)
        assertEquals("enabled", high.getJSONObject("thinking").getString("type"))
        assertEquals(8192, high.getJSONObject("thinking").getInt("budget_tokens"))
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
