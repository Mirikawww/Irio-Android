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
    val retryGroups = retryVersionsForSelectedUsers(messages)
    val editGroups = editVersionsByGroup(messages)
    val visible = messages.filter { message ->
        if (message.fromUser) {
            message.selected
        } else {
            val ownerId = responseOwnerId(messages, message)
            val owner = messages.firstOrNull { it.id == ownerId }
            if (owner != null && !owner.selected) {
                false
            } else {
                message.selected
            }
        }
    }
    return TranscriptPresentation(conversationId, visible, retryGroups, editGroups)
}

private fun responseOwnerId(messages: List<ChatMessage>, reply: ChatMessage): Long? {
    reply.responseToUserId?.let { return it }
    val replyIndex = messages.indexOfFirst { it.id == reply.id }
    if (replyIndex < 0) return null
    return messages.take(replyIndex).lastOrNull { it.fromUser }?.id
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

private fun retryVersionsForSelectedUsers(messages: List<ChatMessage>): Map<Long, List<ChatMessage>> {
    return retryVersionsByGroup(messages).mapValues { (_, replies) ->
        val ownerIds = replies.mapNotNull { responseOwnerId(messages, it) }.distinct()
        val selectedOwnerId = ownerIds.firstOrNull { ownerId ->
            messages.firstOrNull { it.id == ownerId }?.selected == true
        }
        if (selectedOwnerId == null) replies else replies.filter { responseOwnerId(messages, it) == selectedOwnerId }
    }
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
    return retryVersionsForSelectedUsers(messages)[message.groupId].orEmpty()
}

internal fun editVersions(messages: List<ChatMessage>, message: ChatMessage): List<ChatMessage> {
    return editVersionsByGroup(messages)[message.userGroupId].orEmpty()
}

/** 找到某个编辑版本对应的回复组；显式归属优先，旧数据再按消息顺序兜底。 */
internal fun replyGroupForUserVersion(messages: List<ChatMessage>, user: ChatMessage): Long? {
    messages.firstOrNull { !it.fromUser && it.responseToUserId == user.id }?.let { return it.groupId }
    val index = messages.indexOfFirst { it.id == user.id }
    if (index < 0) return null
    return messages.drop(index + 1)
        .takeWhile { !it.fromUser }
        .firstOrNull { !it.isError }
        ?.groupId
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
