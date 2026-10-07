package com.irio

enum class ProcessEventKind {
    THINKING,
    SEARCH,
    TOOL,
}

data class ProcessEvent(
    val id: Long,
    val kind: ProcessEventKind,
    val content: String = "",
    val millis: Long = 0L,
    val pages: Int = 0,
    val live: Boolean = false,
    /** 本事件在模型正文开始输出后才启动。真正的插入点看 [textOffset]，不是整段正文的末尾。 */
    val belowText: Boolean = false,
    /** 本地工具名，如 get_time / get_memory / update_memory。 */
    val toolName: String = "",
    /**
     * 事件启动时助手正文的长度。大于 0 时插在这段正文和后续正文之间，
     * 这样「先说话 → 工具 → 再说话」时工具不会沉到整段回复的最下面。
     * 旧数据没有这个字段（0）且 [belowText] 为 true：仍渲染在整段正文之后。
     */
    val textOffset: Int = 0,
    val searchQuery: String = "",
)

internal fun searchStatusLabel(query: String, language: AppLanguage = ThemeStore.language): String {
    val status = translate(language, "网络搜索中")
    return if (query.isBlank()) status else "$status - $query"
}

internal sealed class TranscriptPiece {
    data class Text(val text: String) : TranscriptPiece()
    data class Event(val event: ProcessEvent) : TranscriptPiece()
}

/**
 * 按发生顺序把过程事件插回正文。正文前的事件在最上面；带锚点的事件卡在对应偏移；
 * 没有锚点的旧 belowText 事件留在整段正文之后。
 */
internal fun interleaveTranscript(text: String, process: List<ProcessEvent>): List<TranscriptPiece> {
    val pieces = mutableListOf<TranscriptPiece>()
    val anchored = ArrayList<ProcessEvent>()
    val legacyTail = ArrayList<ProcessEvent>()
    for (event in process) {
        when {
            !event.belowText -> pieces += TranscriptPiece.Event(event)
            event.textOffset > 0 -> anchored += event
            else -> legacyTail += event
        }
    }
    var cursor = 0
    val limit = text.length
    val groups = anchored.groupBy { it.textOffset.coerceIn(0, limit) }.toSortedMap()
    for ((offset, events) in groups) {
        if (offset > cursor) {
            pieces += TranscriptPiece.Text(text.substring(cursor, offset))
            cursor = offset
        }
        events.forEach { pieces += TranscriptPiece.Event(it) }
    }
    if (cursor < limit) pieces += TranscriptPiece.Text(text.substring(cursor))
    legacyTail.forEach { pieces += TranscriptPiece.Event(it) }
    return pieces
}

/** 新一轮正文接在上一轮后面，而不是粘成一段。已有空行则不重复插入。 */
internal fun appendAssistantRoundBreak(text: String): String {
    if (text.isEmpty()) return text
    val trimmed = text.trimEnd()
    return if (trimmed.endsWith("\n\n")) trimmed else "$trimmed\n\n"
}

/**
 * 落定正文必须用流式累积的全文。最后一轮的 content 只有工具之后的那一段，
 * 拿它覆盖会把前面的话丢掉，工具锚点也就落到整段回复的最下面。
 */
internal fun settledAssistantText(streamed: String, lastRound: String): String =
    streamed.ifBlank { lastRound }

/** 思考行的显示方式。关掉「思考块」后，进行中只剩「思考中」，结束后整行不显示（内容仍保存，重新打开开关即可看到）。 */
internal enum class ThinkingRowMode { Full, StatusOnly, Hidden }

internal fun thinkingRowMode(kind: ProcessEventKind, live: Boolean, blockEnabled: Boolean): ThinkingRowMode = when {
    kind != ProcessEventKind.THINKING || blockEnabled -> ThinkingRowMode.Full
    live -> ThinkingRowMode.StatusOnly
    else -> ThinkingRowMode.Hidden
}
