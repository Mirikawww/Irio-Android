package com.irio

import org.json.JSONArray
import org.json.JSONObject

/** Visible assistant output; deliberately separate from tool lifecycle/status events. */
data class ColoredBubble(val text: String, val color: String)

internal object ColoredBubbleTool {
    const val NAME = "send_colored_bubbles"
    private const val DESCRIPTION =
        "Send one or more separate colored chat bubbles to the user, in the supplied order. " +
            "Use when the user asks for colored bubbles or a series of short playful messages. " +
            "Each bubble has plain text and any RGB color you choose, written as #RRGGBB (for example #17BEBB). " +
            "Irio displays a pale background with matching readable text. " +
            "You may call this tool repeatedly to continue the sequence. The bubbles are the actual reply: " +
            "do not repeat their text in your normal response or narrate tool use."

    fun schemaFor(protocol: ProviderProtocol): JSONObject {
        val bubble = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("text", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 1000)
                .put("description", "Visible plain text, including emoji and line breaks if desired"))
            .put("color", JSONObject().put("type", "string")
                .put("description", "Any RGB color chosen by you as #RRGGBB or #RGB; there is no preset palette")))
            .put("required", JSONArray(listOf("text", "color"))).put("additionalProperties", false)
        val parameters = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("bubbles", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 12).put("items", bubble)))
            .put("required", JSONArray(listOf("bubbles"))).put("additionalProperties", false)
        return when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> JSONObject().put("type", "function").put("function",
                JSONObject().put("name", NAME).put("description", DESCRIPTION).put("parameters", parameters))
            ProviderProtocol.RESPONSES -> JSONObject().put("type", "function").put("name", NAME)
                .put("description", DESCRIPTION).put("parameters", parameters).put("strict", true)
            ProviderProtocol.ANTHROPIC -> JSONObject().put("name", NAME).put("description", DESCRIPTION).put("input_schema", parameters)
            ProviderProtocol.GEMINI -> GeminiProtocol.declaration(NAME, DESCRIPTION, parameters)
            ProviderProtocol.GEMINI_INTERACTIONS -> GeminiInteractionsProtocol.declaration(NAME, DESCRIPTION, parameters)
        }
    }
}

internal class ColoredBubbleToolProvider : ToolProvider {
    override val id = "colored-bubbles"
    override fun definitions(protocol: ProviderProtocol) = listOf(ColoredBubbleTool.schemaFor(protocol))
    override fun systemInstructions(): String =
        "Colored bubble output is available in this request. When the user asks for colored bubbles, " +
            "call send_colored_bubbles with the bubbles in display order and freely choose each color as #RRGGBB; you may call it repeatedly to " +
            "continue the sequence. The bubbles are the visible answer, so do not repeat their text or " +
            "show a normal tool status message."
    override fun handles(name: String) = name == ColoredBubbleTool.NAME
    override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
        if (context.cancellation.isCancelled()) return ToolExecutionResult("Colored bubbles cancelled", outcome = ToolOutcome.STOPPED)
        val bubbles = runCatching {
            val array = JSONObject(call.arguments).getJSONArray("bubbles")
            require(array.length() in 1..12)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val text = (item.get("text") as String).trim()
                val color = normalizeColoredBubbleColor(item.get("color") as String)
                    ?: error("Invalid RGB color")
                require(text.isNotBlank() && text.length <= 1000)
                ColoredBubble(text, color)
            }
        }.getOrNull() ?: return ToolExecutionResult("Invalid bubbles: provide 1 to 12 bubbles, each with nonblank text (up to 1000 characters) and an RGB color in #RRGGBB or #RGB format", outcome = ToolOutcome.FAILED)
        if (context.cancellation.isCancelled()) return ToolExecutionResult("Colored bubbles cancelled", outcome = ToolOutcome.STOPPED)
        context.onUpdate(ChatStreamUpdate(coloredBubbles = bubbles))
        return ToolExecutionResult("Colored bubbles displayed. Do not repeat their text or narrate tool use.")
    }
}
