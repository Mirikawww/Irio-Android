package com.irio

import org.json.JSONArray
import org.json.JSONObject

internal object ChatProtocol {
    fun parseChatCompletions(data: String): ChatStreamUpdate? = runCatching {
        val response = JSONObject(data)
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")
        val message = choice?.optJSONObject("message")
        val parsed = parseOpenAiText(delta?.opt("content") ?: message?.opt("content"))
        val thinking = thinkingFrom(delta).ifBlank { thinkingFrom(message) }
        val toolCalls = delta?.optJSONArray("tool_calls") ?: message?.optJSONArray("tool_calls")
        ChatStreamUpdate(
            delta = parsed,
            thinkingDelta = thinking,
            model = presentString(response.opt("model")),
            toolCallDeltas = parseChatCompletionsToolCalls(toolCalls),
            finishReason = presentString(choice?.opt("finish_reason")),
            usage = parseUsage(response.optJSONObject("usage")),
        )
    }.getOrNull()

    /** 三协议共用：OpenAI `prompt_tokens`/`completion_tokens`/`total_tokens`，Anthropic `input_tokens`/`output_tokens`。 */
    fun parseUsage(usage: JSONObject?): TokenUsage? {
        if (usage == null) return null
        val prompt = firstPositiveInt(usage, "prompt_tokens", "input_tokens", "input")
        val completion = firstPositiveInt(usage, "completion_tokens", "output_tokens", "output")
        val total = firstPositiveInt(usage, "total_tokens")
            ?: listOfNotNull(prompt, completion).takeIf { it.size == 2 }?.sum()
        if (prompt == null && completion == null && total == null) return null
        return TokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = total)
    }

    fun usageFromResponses(response: JSONObject?): TokenUsage? {
        if (response == null) return null
        return parseUsage(response.optJSONObject("usage"))
            ?: parseUsage(response.optJSONObject("response")?.optJSONObject("usage"))
    }

    private fun firstPositiveInt(json: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val value = json.optInt(key, -1)
            if (value >= 0) return value
        }
        return null
    }

    fun thinkingFrom(delta: JSONObject?): String {
        if (delta == null) return ""
        presentString(delta.opt("reasoning_content")).takeIf { it.isNotEmpty() }?.let { return it }
        val reasoning = delta.opt("reasoning")
        if (reasoning is JSONObject) {
            presentString(reasoning.opt("content")).takeIf { it.isNotEmpty() }?.let { return it }
            presentString(reasoning.opt("text")).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    fun parseChatCompletionsToolCalls(calls: JSONArray?): List<ToolCallDelta> {
        if (calls == null) return emptyList()
        return buildList {
            for (index in 0 until calls.length()) {
                val item = calls.optJSONObject(index) ?: continue
                val function = item.optJSONObject("function")
                val resolvedIndex = if (item.has("index")) item.optInt("index") else index
                add(
                    ToolCallDelta(
                        index = resolvedIndex,
                        key = "c$resolvedIndex",
                        id = presentString(item.opt("id")),
                        name = presentString(function?.opt("name")).ifBlank {
                            presentString(item.opt("name"))
                        },
                        argumentsDelta = presentString(function?.opt("arguments")).ifBlank {
                            presentString(item.opt("arguments"))
                        },
                    )
                )
            }
        }
    }

    fun applyToolCallDeltas(
        toolCalls: MutableMap<String, MutableToolCall>,
        deltas: List<ToolCallDelta>,
    ) {
        deltas.forEach { delta ->
            val key = delta.key.ifBlank { "i${delta.index}" }
            val existing = toolCalls[key] ?: MutableToolCall(index = delta.index).also { toolCalls[key] = it }
            if (delta.id.isNotBlank()) existing.id = delta.id
            if (delta.name.isNotBlank()) existing.name = delta.name
            if (delta.argumentsDelta.isNotBlank()) existing.arguments.append(delta.argumentsDelta)
        }
    }

    fun completedToolCalls(toolCalls: Map<String, MutableToolCall>): List<ChatToolCall> {
        return toolCalls.values
            .sortedBy { it.index }
            .mapNotNull { call ->
                val id = call.id.ifBlank { return@mapNotNull null }
                val name = call.name.ifBlank { return@mapNotNull null }
                ChatToolCall(id = id, name = name, arguments = call.arguments.toString())
            }
    }

    fun shouldEndStream(done: Boolean, finishReason: String, completedToolCount: Int): Boolean {
        if (done) return true
        return when (finishReason) {
            "tool_calls", "tool_use", "function_call" -> completedToolCount > 0
            "stop", "end_turn", "stop_sequence", "max_tokens", "length" -> true
            else -> false
        }
    }

    fun putReasoning(target: JSONObject, protocol: ProviderProtocol, effort: ReasoningEffort) {
        when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> {
                val value = effort.apiValue ?: return
                // Chat Completions and Responses have different reasoning shapes.
                target.put("reasoning_effort", value)
            }
            ProviderProtocol.RESPONSES -> {
                val value = effort.apiValue ?: return
                target.put("reasoning", JSONObject().put("effort", value))
            }
            ProviderProtocol.ANTHROPIC -> {
                when (effort) {
                    ReasoningEffort.OFF -> target.put("thinking", JSONObject().put("type", "disabled"))
                    ReasoningEffort.AUTO -> Unit
                    ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> target.put(
                        "thinking",
                        JSONObject().put("type", "enabled").put("budget_tokens", 1_024),
                    )
                    ReasoningEffort.MEDIUM -> target.put(
                        "thinking",
                        JSONObject().put("type", "enabled").put("budget_tokens", 4_096),
                    )
                    ReasoningEffort.HIGH -> target.put(
                        "thinking",
                        JSONObject().put("type", "enabled").put("budget_tokens", 8_192),
                    )
                    ReasoningEffort.XHIGH, ReasoningEffort.MAX -> target.put(
                        "thinking",
                        JSONObject().put("type", "enabled").put("budget_tokens", 16_000),
                    )
                }
            }
        }
    }

    fun presentString(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> if (value == "null") "" else value
        else -> value.toString()
    }

    private fun parseOpenAiText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val part = value.optJSONObject(index) ?: continue
                when (part.optString("type")) {
                    "text", "output_text", "input_text", "" -> append(presentString(part.opt("text")))
                }
            }
        }
        else -> ""
    }
}
