package com.irio

internal data class TranscriptPresentation(
    val conversationId: String,
    val visible: List<ChatMessage>,
    val retryGroups: Map<Long, List<ChatMessage>>,
    val editGroups: Map<Long, List<ChatMessage>>,
    val context: List<ChatMessage> = emptyList(),
)

internal fun buildTranscriptPresentation(
    conversationId: String,
    messages: List<ChatMessage>,
): TranscriptPresentation {
    val retryGroups = retryVersionsForSelectedUsers(messages)
    val editGroups = editVersionsByGroup(messages)
    val selected = messages.filter { message ->
        if (message.summaryState != SummaryState.NONE) {
            message.selected
        } else if (message.fromUser) {
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
    val visible = selected.filter { !it.hidden }
    return TranscriptPresentation(conversationId, visible, retryGroups, editGroups, selected)
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
        if (!message.fromUser && message.summaryState == SummaryState.NONE) {
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

/** 发给模型的历史。和界面同一套版本选择，但保留用户看不见的消息。 */
internal fun contextMessages(messages: List<ChatMessage>): List<ChatMessage> {
    return buildTranscriptPresentation("", messages).context.map { message ->
        // 工具已执行后，后续模型请求仍可能失败。只忽略本地错误正文，不丢已发生的执行记录。
        val hasTools = message.toolRecords.isNotEmpty() || message.process.any {
            !it.live && it.kind != ProcessEventKind.THINKING && it.content.isNotBlank()
        }
        if (!message.fromUser && message.isError && hasTools) {
            message.copy(text = "", isError = false)
        } else message
    }
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

internal data class CommittedEdit(
    val messages: List<ChatMessage>,
    val history: List<ChatMessage>,
    val reply: ChatMessage,
)

/**
 * 编辑版本插回原位，并挂上一条待生成的回复。
 * 立即停止会丢掉还没内容的占位回复，这时没有回复组，也要新开一条，否则重发不会发请求。
 */
internal fun commitEditedTurn(
    messages: List<ChatMessage>,
    target: ChatMessage,
    targetIndex: Int,
    versionId: Long,
    replyId: Long,
    text: String,
    attachments: List<ChatAttachment>,
    quote: String = target.quote,
): CommittedEdit {
    val version = target.copy(
        id = versionId,
        text = text,
        attachments = attachments,
        quote = quote,
        modelReaction = "",
        userReaction = "",
        process = emptyList(),
        isError = false,
        isPending = false,
        isStreaming = false,
        editGroup = target.userGroupId,
        selected = true,
    )
    val replyGroupId = replyGroupForUserVersion(messages, target)
    val reply = ChatMessage(
        id = replyId,
        text = "",
        fromUser = false,
        isPending = true,
        retryGroup = replyGroupId ?: 0L,
        selected = true,
        responseToUserId = version.id,
    )
    val history = buildList {
        addAll(contextMessages(messages.subList(0, targetIndex)).filterNot { it.isError || it.isPending })
        add(version)
    }
    val updated = buildList {
        messages.forEachIndexed { index, message ->
            when {
                message.fromUser && message.userGroupId == target.userGroupId -> add(message.copy(selected = false))
                replyGroupId != null && !message.fromUser && message.groupId == replyGroupId ->
                    add(message.copy(selected = false))
                else -> add(message)
            }
            if (index == targetIndex) add(version)
        }
        add(reply)
    }
    return CommittedEdit(updated, history, reply)
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
