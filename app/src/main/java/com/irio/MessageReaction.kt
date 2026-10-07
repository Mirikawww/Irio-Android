package com.irio

import org.json.JSONObject

/** Reactions are message metadata, never visible transcript prose. */
internal fun reactionFeedbackText(message: ChatMessage): String {
    val emoji = if (message.fromUser) message.modelReaction else message.userReaction
    if (emoji.isEmpty()) return ""
    val feedback = JSONObject()
        .put("message_id", message.id.toString())
        .put(if (message.fromUser) "assistant_reaction" else "user_reaction", emoji)
    return "\n\n<irio_reaction>${feedback}</irio_reaction>"
}

/** Only real, visible user turns receive target ids; summary/control/hidden turns do not. */
internal fun reactionRequestMessages(messages: List<ChatRequestMessage>): List<ChatRequestMessage> =
    messages.map { message ->
        val target = message.reactionTargetId
        if (message.role != "user" || target == null) message else {
            message.copy(text = message.text + "\n\n[Irio message_id: $target]")
        }
    }

internal fun modelReactionFrom(json: JSONObject): String =
    if (json.optBoolean("fromUser")) normalizedStoredReaction(json.optString("modelReaction")) else ""

internal fun userReactionFrom(json: JSONObject): String =
    if (!json.optBoolean("fromUser")) normalizedStoredReaction(json.optString("userReaction")) else ""

private fun normalizedStoredReaction(emoji: String): String =
    if (emoji.isEmpty()) "" else ReactionEmojiCatalog.normalize(emoji).orEmpty()

internal fun setModelReaction(message: ChatMessage, emoji: String): ChatMessage? {
    if (!message.fromUser || message.hidden || message.summaryState != SummaryState.NONE) return null
    val canonical = if (emoji.isEmpty()) "" else ReactionEmojiCatalog.normalize(emoji) ?: return null
    return message.copy(modelReaction = canonical)
}

internal fun setUserReaction(message: ChatMessage, emoji: String): ChatMessage? {
    if (message.fromUser || message.hidden || message.summaryState != SummaryState.NONE ||
        message.isPending || message.isStreaming
    ) return null
    val canonical = if (emoji.isEmpty()) "" else ReactionEmojiCatalog.normalize(emoji) ?: return null
    return message.copy(userReaction = canonical)
}
