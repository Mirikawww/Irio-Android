package com.irio

import androidx.compose.foundation.text.input.clearText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ChatScreenState.swapComposer(fromId: String, toId: String) {
    val restored =
        swapComposerDraft(
            composerDrafts,
            fromId,
            toId,
            ComposerDraft(
                text = message.text.toString(),
                attachments = pendingAttachments,
                editingMessageId = editingMessageId,
                quote = pendingQuote,
            ),
        )
    if (message.text.toString() != restored.text) {
        message.edit { replace(0, length, restored.text) }
    }
    pendingAttachments = restored.attachments
    editingMessageId = restored.editingMessageId
    pendingQuote = restored.quote
    composerStatus = null
    composerFullscreen = false
}

internal fun ChatScreenState.conversationContent(id: String): Conversation? {
    synchronized(conversationCache) { conversationCache[id] }
        ?.let {
            return it
        }
    val loaded = ConversationStore.load(context, id) ?: return null
    synchronized(conversationCache) { conversationCache[id] = loaded }
    return loaded
}

internal fun ChatScreenState.cacheConversationContent(conversation: Conversation) {
    synchronized(conversationCache) { conversationCache[conversation.id] = conversation }
}

internal fun ChatScreenState.settleDrawer(progress: Float) {
    drawerDragging = false
    drawerProgress =
        when {
            progress >= drawerStartProgress + 0.15f -> 1f
            progress <= drawerStartProgress - 0.15f -> 0f
            else -> if (drawerStartProgress >= 0.5f) 1f else 0f
        }
}

internal fun ChatScreenState.persistCurrent(
    updatedMessages: List<ChatMessage> = messages,
    touchUpdatedAt: Boolean = true,
) {
    if (clearingHistory) return
    if (updatedMessages.isEmpty() && currentConversation.messages.isEmpty()) return
    val stored =
        currentConversation.copy(
            // titlePending 期间用截断文本临时显示；AI 生成（或手动改名）之后不再被覆盖
            title =
                if (currentConversation.titlePending) {
                    ConversationStore.titleFrom(updatedMessages)
                } else {
                    currentConversation.title
                },
            updatedAt =
                if (touchUpdatedAt) System.currentTimeMillis() else currentConversation.updatedAt,
            messages =
                updatedMessages.filterNot {
                    it.isPending &&
                        it.toolRecords.isEmpty() &&
                        !hasReplyContent(it.text, it.attachments, it.process)
                },
            tokenUsage =
                currentConversation.tokenUsage
                    ?: updatedMessages.lastOrNull { !it.fromUser && it.usage != null }?.usage,
        )
    // Empty pending rows stay out of disk history, but must remain in the runtime snapshot.
    val live = stored.copy(messages = updatedMessages)
    currentConversation = live
    cacheConversationContent(live)
    // 摘要就地更新（filter/map 保留引用相等性，不打乱抽屉滚动位置）；
    // 排序只在顺序真变化时重写引用，避免无谓的整列表重组。
    conversations =
        conversations
            .filterNot { it.id == stored.id }
            .plus(stored.summary())
            .let { list ->
                val sorted =
                    list.sortedWith(
                        compareByDescending<ConversationSummary> { it.pinned }
                            .thenByDescending { it.updatedAt }
                    )
                if (sorted == list) list else sorted
            }
    scope.launch(Dispatchers.IO) {
        ConversationStore.save(context, stored)
        ConversationStore.saveLastOpenedId(context, stored.id)
    }
}

internal fun ChatScreenState.replaceInConversation(
    conversationId: String,
    replyId: Long,
    updated: ChatMessage,
    persist: Boolean = false,
) {
    if (clearingHistory) return
    if (currentConversation.id == conversationId) {
        messages =
            if (messages.any { it.id == replyId }) {
                messages.map { if (it.id == replyId) updated else it }
            } else {
                messages + updated
            }
        if (persist) {
            persistCurrent(messages)
        } else {
            cacheConversationContent(currentConversation.copy(messages = messages))
        }
    } else if (!persist) {
        // All live reply states are runtime-only, not just image-generation previews.
        val stored = conversationContent(conversationId) ?: return
        cacheConversationContent(stored.copy(
            messages = if (stored.messages.any { it.id == replyId })
                stored.messages.map { if (it.id == replyId) updated else it }
            else stored.messages + updated
        ))
    } else if (persist) {
        val stored = conversationContent(conversationId) ?: return
        val next =
            stored.copy(
                messages =
                    if (stored.messages.any { it.id == replyId }) {
                        stored.messages.map { if (it.id == replyId) updated else it }
                    } else {
                        stored.messages + updated
                    },
                updatedAt = System.currentTimeMillis(),
                // titlePending 期间用截断文本临时显示；已生成的标题不被覆盖
                title =
                    if (stored.titlePending) {
                        ConversationStore.titleFrom(
                            if (stored.messages.any { it.id == replyId }) {
                                stored.messages.map { if (it.id == replyId) updated else it }
                            } else {
                                stored.messages + updated
                            }
                        )
                    } else {
                        stored.title
                    },
            )
        cacheConversationContent(next)
        conversations =
            conversations
                .filterNot { it.id == next.id }
                .plus(next.summary())
                .let { list ->
                    val sorted =
                        list.sortedWith(
                            compareByDescending<ConversationSummary> { it.pinned }
                                .thenByDescending { it.updatedAt }
                        )
                    if (sorted == list) list else sorted
                }
        scope.launch(Dispatchers.IO) { ConversationStore.save(context, next) }
    }
}

internal fun ChatScreenState.updateMessageReaction(
    conversationId: String,
    messageId: Long,
    emoji: String,
    fromModel: Boolean,
): Boolean {
    if (clearingHistory) return false
    val stored =
        if (currentConversation.id == conversationId) {
            currentConversation.copy(messages = messages)
        } else conversationContent(conversationId) ?: return false
    val target = stored.messages.firstOrNull { it.id == messageId && it.selected } ?: return false
    val updated = if (fromModel) setModelReaction(target, emoji) else setUserReaction(target, emoji)
    if (updated == null) return false
    if (updated == target) return true
    reactionAnimations.record(
        messageId,
        if (fromModel) updated.modelReaction else updated.userReaction,
    )
    val next = stored.messages.map { if (it.id == messageId) updated else it }
    if (currentConversation.id == conversationId) {
        messages = next
        persistCurrent(messages, touchUpdatedAt = false)
    } else {
        val conversation = stored.copy(messages = next)
        cacheConversationContent(conversation)
        scope.launch(Dispatchers.IO) { ConversationStore.save(context, conversation) }
    }
    return true
}

internal fun ChatScreenState.openConversation(conversation: ConversationSummary) {
    if (conversation.id == currentConversation.id) {
        drawerProgress = 0f
        return
    }
    SpeechPlayer.stop()
    val requestToken = conversationLoadToken + 1
    conversationLoadToken = requestToken
    val previousId = currentConversation.id
    val keepPool = previousId in generatingIds
    // 先关抽屉；正文磁盘读取和 Markdown/TeX 预热都不能阻塞点击反馈。
    drawerProgress = 0f
    scope.launch(Dispatchers.IO) {
        val full = conversationContent(conversation.id) ?: return@launch
        val applied =
            withContext(Dispatchers.Main) {
                if (conversationLoadToken != requestToken) return@withContext false
                val previous = currentConversation
                val previousMessages = messages
                pendingConversationScroll = full.id
                swapComposer(previous.id, full.id)
                if (previousMessages.isEmpty() && previous.messages.isEmpty()) {
                    composerDrafts.remove(previous.id)
                }
                currentConversation = full
                messages = full.messages
                true
            }
        if (applied) {
            ConversationStore.saveLastOpenedId(context, conversation.id)
            if (!keepPool) IrioHttp.evictConversation(previousId)
        }
    }
}

internal fun ChatScreenState.newConversation(closeDrawer: Boolean = true) {
    SpeechPlayer.stop()
    val previous = currentConversation
    val previousMessages = messages
    val keepPool = previous.id in generatingIds
    conversationLoadToken++
    val created = ConversationStore.newConversation()
    swapComposer(previous.id, created.id)
    if (previousMessages.isEmpty() && previous.messages.isEmpty()) {
        composerDrafts.remove(previous.id)
    }
    currentConversation = created
    messages = emptyList()
    if (closeDrawer) drawerProgress = 0f
    if (!keepPool) IrioHttp.evictConversation(previous.id)
}

internal fun ChatScreenState.clearAllHistory() {
    clearingHistory = true
    titleGeneration.clear()
    generations.values.forEach { it.cancellation.cancel() }
    IrioHttp.evictAllScopes()
    ConversationStore.deleteAll(context)
    message.clearText()
    pendingAttachments = emptyList()
    editingMessageId = null
    composerDrafts.clear()
    pendingQuote = ""
    conversations = emptyList()
    newConversation(closeDrawer = false)
    val jobs = generations.values.map { it.job }
    if (jobs.isEmpty()) {
        clearingHistory = false
    } else {
        jobs.forEach { job ->
            job.invokeOnCompletion { if (generations.isEmpty()) clearingHistory = false }
        }
    }
}

internal fun ChatScreenState.togglePinned(conversation: ConversationSummary) {
    val nextPinned = !conversation.pinned
    conversations =
        conversations
            .map { if (it.id == conversation.id) it.copy(pinned = nextPinned) else it }
            .sortedWith(
                compareByDescending<ConversationSummary> { it.pinned }
                    .thenByDescending { it.updatedAt }
            )
    if (currentConversation.id == conversation.id) {
        currentConversation = currentConversation.copy(pinned = nextPinned)
    }
    scope.launch(Dispatchers.IO) {
        ConversationStore.load(context, conversation.id)?.let { stored ->
            ConversationStore.save(context, stored.copy(pinned = nextPinned))
        }
    }
}

internal fun ChatScreenState.deleteConversation(conversation: ConversationSummary) {
    titleGeneration.cancel(conversation.id)
    if (conversation.id in generatingIds) stopGeneration(conversation.id)
    IrioHttp.evictConversation(conversation.id)
    conversations = conversations.filterNot { it.id == conversation.id }
    if (currentConversation.id == conversation.id) {
        newConversation(closeDrawer = false)
    }
    composerDrafts.remove(conversation.id)
    synchronized(conversationCache) { conversationCache.remove(conversation.id) }
    scope.launch(Dispatchers.IO) { ConversationStore.delete(context, conversation.id) }
}

/** 首轮自动生成只处理 titlePending；长按菜单可以显式重新生成已有标题。 模型优先级：设置中的标题模型 → 当前对话模型（非图片）→ 上次对话模型。 */
internal fun ChatScreenState.maybeGenerateTitle(
    conversationId: String,
    userText: String,
    assistantText: String,
    regenerate: Boolean = false,
) {
    if (clearingHistory || userText.isBlank() || (!regenerate && assistantText.isBlank())) return
    val conversation = conversations.firstOrNull { it.id == conversationId } ?: return
    if (!conversation.titlePending && !regenerate) return
    val configured = ToolStore.loadTitleModel(context)
    val titleModel =
        when {
            configured.isNotBlank() -> configured
            !ModelCatalog.isImageModel(context, selectedModel) -> selectedModel
            else -> ProviderStore.loadLastChatModel(context)
        }
    if (titleModel.isBlank()) {
        if (regenerate) titleGeneration.showNotice(tr("请先选择标题生成模型或对话模型"))
        return
    }
    val token = titleGeneration.begin(conversationId) ?: return
    val request = ConversationTitleRequest(conversation)
    scope.launch {
        try {
            // 标题模型可能与当前 provider 不同：优先找拥有该模型的 provider
            val provider =
                ProviderStore.loadProviders(context).firstOrNull { titleModel in it.models }
                    ?: ProviderStore.loadProvider(context)
            if (provider == null) {
                if (regenerate) titleGeneration.showNotice(tr("请先选择标题生成模型或对话模型"))
                return@launch
            }
            val title =
                ChatApi.generateTitle(
                    provider,
                    titleModel,
                    userText,
                    assistantText,
                    connectionScope = "$conversationId#title",
                )
            if (!titleGeneration.isCurrent(conversationId, token) || clearingHistory) return@launch
            if (title.isNullOrBlank()) {
                if (regenerate) titleGeneration.showNotice(tr("标题生成失败，已保留原标题"))
                return@launch
            }
            // 仍要确认标题没被别的路径改掉（并发防抢占）
            val current = conversations.firstOrNull { it.id == conversationId } ?: return@launch
            if (!request.accepts(current)) return@launch
            conversations =
                conversations.map {
                    if (it.id == conversationId) it.copy(title = title, titlePending = false)
                    else it
                }
            if (currentConversation.id == conversationId) {
                currentConversation = currentConversation.copy(title = title, titlePending = false)
                cacheConversationContent(currentConversation.copy(messages = messages))
            } else {
                synchronized(conversationCache) {
                    conversationCache[conversationId]?.let {
                        conversationCache[conversationId] =
                            it.copy(title = title, titlePending = false)
                    }
                }
            }
            val saved =
                withContext(Dispatchers.IO) {
                    runCatching { ConversationStore.updateGeneratedTitle(context, request, title) }
                        .isSuccess
                }
            if (!saved && regenerate) titleGeneration.showNotice(tr("标题保存失败"))
        } finally {
            titleGeneration.finish(conversationId, token)
        }
    }
}

internal fun ChatScreenState.regenerateConversationTitle(conversationId: String) {
    if (clearingHistory || titleGeneration.isRunning(conversationId)) return
    scope.launch {
        val full =
            if (currentConversation.id == conversationId) {
                currentConversation.copy(messages = messages)
            } else withContext(Dispatchers.IO) { conversationContent(conversationId) }
        val input =
            full?.let { withContext(Dispatchers.Default) { conversationTitleInput(it.messages) } }
        if (input == null) {
            titleGeneration.showNotice(tr("没有可用于生成标题的消息"))
            return@launch
        }
        maybeGenerateTitle(conversationId, input.user, input.assistant, regenerate = true)
    }
}
