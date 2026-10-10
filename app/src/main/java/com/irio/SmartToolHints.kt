package com.irio

import org.json.JSONArray
import org.json.JSONObject

/** UI-only metadata; providers must never receive this argument. */
internal object SmartToolHints {
    const val ARGUMENT = "_display_text"
    const val INSTRUCTIONS = "For each tool call, you may set _display_text to a concise UI label describing this specific action in the user's language. Use at most 10 Unicode characters (each CJK character counts as one), with letters and digits only. Do not use punctuation, symbols, emoji, whitespace or line breaks. This label replaces the default tool-call text and is not a tool argument. Omit it or use an empty string to keep the default label."

    fun decorate(schema: JSONObject): JSONObject {
        // Providers can cache their definitions; never mutate the original schema.
        val copy = JSONObject(schema.toString())
        val definition = copy.optJSONObject("function") ?: copy
        val key = when {
            definition.has("input_schema") -> "input_schema"
            definition.has("parametersJsonSchema") -> "parametersJsonSchema"
            else -> "parameters"
        }
        val parameters = definition.optJSONObject(key) ?: JSONObject().put("type", "object").also {
            definition.put(key, it)
        }
        val properties = parameters.optJSONObject("properties") ?: JSONObject().also {
            parameters.put("properties", it)
        }
        // Length and character rules belong to the prompt, not schema validation or execution.
        properties.put(ARGUMENT, JSONObject().put("type", "string").put("description", INSTRUCTIONS))
        if (definition.optBoolean("strict")) {
            // Strict Responses schemas require all properties; the empty string means default UI.
            val required = parameters.optJSONArray("required") ?: JSONArray().also {
                parameters.put("required", it)
            }
            if ((0 until required.length()).none { required.optString(it) == ARGUMENT }) required.put(ARGUMENT)
        }
        return copy
    }

    fun displayText(arguments: String): String =
        (runCatching { JSONObject(arguments).opt(ARGUMENT) }.getOrNull() as? String).orEmpty()

    fun executionCall(call: ChatToolCall): ChatToolCall {
        val arguments = runCatching { JSONObject(call.arguments) }.getOrNull() ?: return call
        if (!arguments.has(ARGUMENT)) return call
        arguments.remove(ARGUMENT)
        return call.copy(arguments = arguments.toString())
    }
}
