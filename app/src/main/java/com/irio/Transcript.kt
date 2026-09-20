package com.irio

internal data class TranscriptPresentation(
    val conversationId: String,
    val visible: List<ChatMessage>,
    val retryGroups: Map<Long, List<ChatMessage>>,
    val editGroups: Map<Long, List<ChatMessage>>,
)

internal fun buildTranscriptPresentation(
    conversationId: String,
    messages: List<ChatMessage>,
): TranscriptPresentation {
    val retryGroups = retryVersionsByGroup(messages)
    val editGroups = editVersionsByGroup(messages)
    val visible = messages.filter { message ->
        val group = if (message.fromUser) {
            editGroups[message.userGroupId].orEmpty()
        } else {
            retryGroups[message.groupId].orEmpty()
        }
        when {
            group.size <= 1 -> true
            message.selected -> true
            else -> group.none { it.selected } && message.id == group.last().id
        }
    }
    return TranscriptPresentation(conversationId, visible, retryGroups, editGroups)
}

internal fun retryVersionsByGroup(messages: List<ChatMessage>): Map<Long, List<ChatMessage>> {
    val groups = LinkedHashMap<Long, MutableList<ChatMessage>>()
    messages.forEach { message ->
        if (!message.fromUser) {
            groups.getOrPut(message.groupId) { mutableListOf() }.add(message)
        }
    }
    return groups
}

/** 用户消息的编辑版本组：同组 = 同一条消息的多个编辑版本。 */
internal fun editVersionsByGroup(messages: List<ChatMessage>): Map<Long, List<ChatMessage>> {
    val groups = LinkedHashMap<Long, MutableList<ChatMessage>>()
    messages.forEach { message ->
        if (message.fromUser) {
            groups.getOrPut(message.userGroupId) { mutableListOf() }.add(message)
        }
    }
    return groups
}

internal fun visibleMessages(messages: List<ChatMessage>): List<ChatMessage> {
    return buildTranscriptPresentation("", messages).visible
}

internal fun retryVersions(messages: List<ChatMessage>, message: ChatMessage): List<ChatMessage> {
    if (message.fromUser) return editVersions(messages, message)
    return retryVersionsByGroup(messages)[message.groupId].orEmpty()
}

internal fun editVersions(messages: List<ChatMessage>, message: ChatMessage): List<ChatMessage> {
    return editVersionsByGroup(messages)[message.userGroupId].orEmpty()
}

internal fun selectRetryVersion(messages: List<ChatMessage>, target: ChatMessage): List<ChatMessage> {
    val groupId = target.groupId
    return messages.map { message ->
        if (!message.fromUser && message.groupId == groupId) {
            message.copy(selected = message.id == target.id)
        } else {
            message
        }
    }
}

internal fun selectEditVersion(messages: List<ChatMessage>, target: ChatMessage): List<ChatMessage> {
    val groupId = target.userGroupId
    return messages.map { message ->
        if (message.fromUser && message.userGroupId == groupId) {
            message.copy(selected = message.id == target.id)
        } else {
            message
        }
    }
}
