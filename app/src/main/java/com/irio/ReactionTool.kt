package com.irio

import org.json.JSONArray
import org.json.JSONObject

internal object ReactionTool {
    const val NAME = "react_to_message"
    private const val DESCRIPTION =
        "Optionally react to a user's message with one emoji, like an iMessage tapback. " +
            "Use the exact numeric string from that user's [Irio message_id: ...] marker. " +
            "Each user message can have at most ONE assistant reaction. " +
            "Setting a different emoji replaces yours; an empty string removes yours. " +
            "Use Unicode 17.0 or earlier emoji with default-yellow skin tone. " +
            "Only react to user messages, not assistant replies. " +
            "Do not narrate the reaction or mention tool use; the emoji appearing on the message is the response."

    fun schemaFor(protocol: ProviderProtocol): JSONObject {
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("message_id", JSONObject().put("type", "string")
                    .put("description", "Exact user message id from the Irio marker"))
                .put("emoji", JSONObject().put("type", "string")
                    .put("description", "One emoji, or empty string to remove the existing reaction")))
            .put("required", JSONArray().put("message_id").put("emoji"))
            .put("additionalProperties", false)
        return when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MINIMAX_VIDEO -> JSONObject().put("type", "function").put(
                "function", JSONObject().put("name", NAME).put("description", DESCRIPTION).put("parameters", parameters),
            )
            ProviderProtocol.RESPONSES -> JSONObject().put("type", "function").put("name", NAME)
                .put("description", DESCRIPTION).put("parameters", parameters).put("strict", true)
            ProviderProtocol.ANTHROPIC -> JSONObject().put("name", NAME).put("description", DESCRIPTION)
                .put("input_schema", parameters)
            ProviderProtocol.GEMINI -> GeminiProtocol.declaration(NAME, DESCRIPTION, parameters)
            ProviderProtocol.GEMINI_INTERACTIONS -> GeminiInteractionsProtocol.declaration(NAME, DESCRIPTION, parameters)
        }
    }
}

internal class ReactionToolProvider : ToolProvider {
    override val id: String = "message-reactions"
    override fun definitions(protocol: ProviderProtocol): List<JSONObject> = listOf(ReactionTool.schemaFor(protocol))
    override fun handles(name: String): Boolean = name == ReactionTool.NAME

    override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
        if (context.cancellation.isCancelled()) return ToolExecutionResult("Reaction cancelled", outcome = ToolOutcome.STOPPED)
        val args = runCatching { JSONObject(call.arguments) }.getOrNull()
            ?: return ToolExecutionResult("Invalid reaction arguments", outcome = ToolOutcome.FAILED)
        val rawId = args.opt("message_id") as? String
            ?: return ToolExecutionResult("message_id must be the exact user message id string", outcome = ToolOutcome.FAILED)
        val messageId = rawId.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.toLongOrNull()
            ?: return ToolExecutionResult("Invalid message_id", outcome = ToolOutcome.FAILED)
        if (context.history.none { it.role == "user" && it.reactionTargetId == messageId }) {
            return ToolExecutionResult("Reaction target is not a user message in this conversation", outcome = ToolOutcome.FAILED)
        }
        val rawEmoji = args.opt("emoji") as? String
            ?: return ToolExecutionResult("emoji must be one emoji string, or empty to remove", outcome = ToolOutcome.FAILED)
        val emoji = if (rawEmoji.isEmpty()) "" else ReactionEmojiCatalog.normalize(rawEmoji)
            ?: return ToolExecutionResult("Only one standard emoji is allowed", outcome = ToolOutcome.FAILED)
        if (context.cancellation.isCancelled() || !context.onReaction(messageId, emoji)) {
            return ToolExecutionResult("Reaction target is no longer available", outcome = ToolOutcome.FAILED)
        }
        // 对话里不出现过程行。后台通知仍要显示这一步。
        context.onUpdate(ChatStreamUpdate(notifyTool = ReactionTool.NAME))
        // Deliberately no toolActive/searching/ProcessEvent updates: reactions are silent UI metadata.
        return ToolExecutionResult(JSONObject().put("message_id", rawId).put("emoji", emoji).toString())
    }
}
