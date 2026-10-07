package com.irio

internal data class ComposerDraft(
    val text: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    val editingMessageId: Long? = null,
    val quote: String = "",
) {
    val isEmpty: Boolean
        get() = text.isEmpty() && attachments.isEmpty() && editingMessageId == null && quote.isEmpty()
}

/**
 * 离开 [fromId] 时把输入框草稿记到该会话名下，进入 [toId] 时取回它自己的草稿。
 * 当前会话的草稿只活在输入框里（取回即移出表），空草稿不占位，
 * 这样发送或清空之后再切走不会把旧内容填回来。同一会话不交换。
 */
internal fun swapComposerDraft(
    drafts: MutableMap<String, ComposerDraft>,
    fromId: String,
    toId: String,
    leaving: ComposerDraft,
): ComposerDraft {
    if (fromId == toId) return leaving
    if (leaving.isEmpty) drafts.remove(fromId) else drafts[fromId] = leaving
    return drafts.remove(toId) ?: ComposerDraft()
}
