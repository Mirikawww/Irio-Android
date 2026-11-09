package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProtocolTest {
    @Test fun chatCompletionsArgumentChunksPreserveStandaloneWhitespaceBeforeAccumulation() {
        for (fragment in listOf(" ", "  ", "\t", "\n", "\r\n")) {
            val call = JSONObject().put("index", 0).put("function", JSONObject().put("arguments", fragment))
            val deltas = ChatProtocol.parseChatCompletionsToolCalls(org.json.JSONArray().put(call))
            assertEquals("Whitespace is meaningful inside a streamed string", fragment, deltas.single().argumentsDelta)
        }
    }

    @Test fun foregroundAndBackgroundCommandsKeepEveryArgumentCharacterAcrossStreamBoundaries() {
        val command = "printf '%s' 'a  b'\n\tprintf '%s' 'c d'"
        val arguments = JSONObject().put("commands", org.json.JSONArray().put(command))
            .put("background", org.json.JSONArray().put(command)).toString()
        for (chatCompletions in listOf(false, true)) {
            val calls = linkedMapOf<String, MutableToolCall>()
            ChatProtocol.applyToolCallDeltas(calls, listOf(ToolCallDelta(index = 0, key = "c0", id = "fixture", name = LinuxShellTool.NAME)))
            // Every character may be its own network delta, including spaces inside JSON strings.
            arguments.forEach { character ->
                val fragment = character.toString()
                val deltas = if (chatCompletions) ChatProtocol.parseChatCompletionsToolCalls(org.json.JSONArray().put(
                    JSONObject().put("index", 0).put("function", JSONObject().put("arguments", fragment))))
                else listOf(ToolCallDelta(index = 0, key = "c0", argumentsDelta = fragment))
                ChatProtocol.applyToolCallDeltas(calls, deltas)
            }
            val completed = ChatProtocol.completedToolCalls(calls).single()
            assertEquals("Tool arguments must be byte-for-byte stable (chat=$chatCompletions)", arguments, completed.arguments)
            val selection = linuxSandboxSelectCommands(completed.arguments, 0, backgroundEnabled = true)
            for (actual in selection.commands + selection.background) {
                assertEquals(command, linuxSandboxNormalizeCommand(actual))
                assertEquals(command, linuxSandboxProotArguments("proot", "/rootfs", null, "/scratch", actual).last())
            }
            assertEquals(listOf(command), selection.commands)
            assertEquals(listOf(command), selection.background)
            val board = LinuxBackgroundBoard()
            val id = board.add(selection.background.single()) {}
            assertEquals(command, board.find(id.toString())!!.command)
        }
    }

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
    fun anthropicNewClaudeModelsUseAdaptiveThinking() {
        val high = JSONObject()
        ChatProtocol.putReasoning(high, ProviderProtocol.ANTHROPIC, ReasoningEffort.HIGH, "claude-sonnet-5-5")
        assertEquals("adaptive", high.getJSONObject("thinking").getString("type"))
        assertEquals("summarized", high.getJSONObject("thinking").getString("display"))
        assertFalse(high.getJSONObject("thinking").has("budget_tokens"))
        assertEquals("high", high.getJSONObject("output_config").getString("effort"))

        val off = JSONObject()
        ChatProtocol.putReasoning(off, ProviderProtocol.ANTHROPIC, ReasoningEffort.OFF, "claude-sonnet-5-5")
        assertFalse(off.has("thinking"))
        assertFalse(off.has("output_config"))

        val legacy = JSONObject()
        ChatProtocol.putReasoning(legacy, ProviderProtocol.ANTHROPIC, ReasoningEffort.HIGH, "claude-sonnet-4-5")
        assertEquals("enabled", legacy.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun ultraUsesExplicitEffortOrProviderMaximum() {
        val chat = JSONObject()
        ChatProtocol.putReasoning(chat, ProviderProtocol.CHAT_COMPLETIONS, ReasoningEffort.ULTRA)
        assertEquals("ultra", chat.getString("reasoning_effort"))

        val responses = JSONObject()
        ChatProtocol.putReasoning(responses, ProviderProtocol.RESPONSES, ReasoningEffort.ULTRA)
        assertEquals("ultra", responses.getJSONObject("reasoning").getString("effort"))

        val adaptive = JSONObject()
        ChatProtocol.putReasoning(adaptive, ProviderProtocol.ANTHROPIC, ReasoningEffort.ULTRA, "claude-sonnet-5-5")
        assertEquals("adaptive", adaptive.getJSONObject("thinking").getString("type"))
        assertEquals("max", adaptive.getJSONObject("output_config").getString("effort"))

        val legacy = JSONObject()
        ChatProtocol.putReasoning(legacy, ProviderProtocol.ANTHROPIC, ReasoningEffort.ULTRA, "claude-sonnet-4-5")
        assertEquals(16_000, legacy.getJSONObject("thinking").getInt("budget_tokens"))
    }

    @Test
    fun adaptiveThinkingModelDetection() {
        listOf(
            "claude-sonnet-5-5", "claude-sonnet-5", "claude-opus-5", "anthropic.claude-opus-5-5",
            "us.anthropic.claude-sonnet-5", "claude-opus-4-7", "claude-opus-4.8", "claude-fable-5-1",
        ).forEach { assertTrue(it, ChatProtocol.requiresAdaptiveThinking(it)) }
        listOf(
            "claude-sonnet-4-5", "claude-sonnet-4-6", "claude-opus-4-6", "claude-opus-4-1-20250805",
            "claude-haiku-4-5-20251001", "claude-3-7-sonnet", "gpt-5.5", "",
        ).forEach { assertFalse(it, ChatProtocol.requiresAdaptiveThinking(it)) }
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

    @Test
    fun usageUpdatesReplaceCumulativeCountsRatherThanAddingThem() {
        val start = TokenUsage(promptTokens = 80, completionTokens = 0, totalTokens = 80)
        val first = ChatProtocol.mergeUsage(start, TokenUsage(completionTokens = 10))
        val last = ChatProtocol.mergeUsage(first, TokenUsage(completionTokens = 20))
        assertEquals(TokenUsage(80, 20, 100), last)
    }

    @Test
    fun usageMergePreservesAnExplicitProviderTotal() {
        assertEquals(
            TokenUsage(80, 20, 120),
            ChatProtocol.mergeUsage(TokenUsage(promptTokens = 80), TokenUsage(completionTokens = 20, totalTokens = 120)),
        )
    }
}
