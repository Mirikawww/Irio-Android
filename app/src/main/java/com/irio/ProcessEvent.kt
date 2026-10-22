package com.irio

enum class ProcessEventKind {
    THINKING,
    SEARCH,
    TOOL,
    /** Visible assistant colored bubble, never a tool status label. */
    BUBBLE,
    /** 子智能体卡片。不是工具状态行。 */
    SUBAGENT,
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
    val webDomain: String = "",
    val displayText: String = "",
    val bubbleColor: String = "",
    val toolOutcome: ToolOutcome? = null,
)

internal const val MERGED_TOOL_CALLS_NAME = "__merged_tool_calls__"

internal fun isSilentPronunciationLookup(toolName: String, outcome: ToolOutcome?): Boolean =
    toolName == LinguaLibreTool.NAME && outcome != ToolOutcome.FAILED && outcome != ToolOutcome.STOPPED

internal fun searchStatusLabel(query: String, language: AppLanguage = ThemeStore.language): String {
    val status = translate(language, "网络搜索中")
    return if (query.isBlank()) status else "$status - $query"
}

/** 和主对话同一套：合并开关打开时，搜索和工具收成第一条工具锚点上的一行摘要。 */
internal fun renderedProcessEvents(events: List<ProcessEvent>, mergeToolCalls: Boolean): List<ProcessEvent> {
    // Successful/in-flight lookups stay silent; failures and stops use ordinary tool feedback.
    val visibleEvents = events.filterNot { isSilentPronunciationLookup(it.toolName, it.toolOutcome) }
    if (!mergeToolCalls) return visibleEvents
    val mergeableEvents = visibleEvents.filter {
        it.toolOutcome != ToolOutcome.FAILED && it.toolOutcome != ToolOutcome.STOPPED &&
            (it.kind == ProcessEventKind.SEARCH ||
                (it.kind == ProcessEventKind.TOOL && it.toolName !in setOf(ImageGenTool.NAME, CommonsImageTools.SHOW)))
    }
    if (mergeableEvents.isEmpty()) return visibleEvents
    val mergeableIds = mergeableEvents.mapTo(hashSetOf()) { it.id }
    val first = mergeableEvents.first()
    val summary = mergedToolSummary(mergeableEvents)
    val firstIndex = visibleEvents.indexOfFirst { it.id == first.id }
    return buildList {
        visibleEvents.forEachIndexed { index, event ->
            if (index == firstIndex) add(
                first.copy(
                    kind = ProcessEventKind.TOOL,
                    toolName = MERGED_TOOL_CALLS_NAME,
                    content = summary,
                    displayText = summary,
                    live = mergeableEvents.any { it.live },
                ),
            )
            if (event.id !in mergeableIds) {
                add(event)
            }
        }
    }
}

internal fun mergedToolSummary(
    events: List<ProcessEvent>,
    language: AppLanguage = ThemeStore.language,
): String = buildList {
    val searchEvents = events.filter { it.kind == ProcessEventKind.SEARCH }
    val pages = searchEvents.sumOf { it.pages }
    val commands = events.filter { it.kind == ProcessEventKind.TOOL && it.toolName == LinuxShellTool.NAME }
        .sumOf { event -> event.content.lineSequence().count { it.startsWith("$ ") }.coerceAtLeast(1) }
    val times = events.count { it.kind == ProcessEventKind.TOOL && it.toolName == TimeTool.NAME }
    val pagesRead = events.count { it.kind == ProcessEventKind.TOOL && it.toolName == WebFetchTool.NAME }
    val known = setOf(LinuxShellTool.NAME, TimeTool.NAME, WebFetchTool.NAME, ImageGenTool.NAME, CommonsImageTools.SHOW, LinguaLibreTool.NAME)
    val otherTools = events.count { it.kind == ProcessEventKind.TOOL && it.toolName !in known }
    if (searchEvents.isNotEmpty()) add(translate(language, "搜索了 %d 个网页").format(pages))
    if (commands > 0) add(translate(language, "运行了 %d 个命令").format(commands))
    if (times > 0) add(translate(language, "查看了 %d 次时间").format(times))
    if (pagesRead > 0) add(translate(language, "查看了 %d 个网页").format(pagesRead))
    if (otherTools > 0) add(translate(language, "使用了 %d 次工具").format(otherTools))
}.joinToString("，")


internal fun searchCompletedLabel(pages: Int, query: String, language: AppLanguage = ThemeStore.language): String {
    val status = translate(language, "搜索了 %s 个网页").format(pages)
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

internal sealed class TranscriptRenderRow {
    data class Single(val index: Int, val piece: TranscriptPiece) : TranscriptRenderRow()
    data class Subagents(val events: List<ProcessEvent>) : TranscriptRenderRow()
}

/** 紧挨着的子智能体合成一行，中间一旦插入正文或其他过程就断开。 */
internal fun transcriptRenderRows(pieces: List<TranscriptPiece>): List<TranscriptRenderRow> {
    val rows = mutableListOf<TranscriptRenderRow>()
    val pending = mutableListOf<ProcessEvent>()
    fun flush() {
        if (pending.isNotEmpty()) {
            rows += TranscriptRenderRow.Subagents(pending.toList())
            pending.clear()
        }
    }
    pieces.forEachIndexed { index, piece ->
        val event = (piece as? TranscriptPiece.Event)?.event
        if (event?.kind == ProcessEventKind.SUBAGENT) {
            pending += event
        } else {
            flush()
            rows += TranscriptRenderRow.Single(index, piece)
        }
    }
    flush()
    return rows
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

/** 思考行的显示方式。打开「默认折叠思考块」后，进行中只剩「思考中」，结束后整行收起（内容仍保存，关掉开关即可看到）。 */
internal enum class ThinkingRowMode { Full, StatusOnly, Hidden }

internal fun thinkingRowMode(kind: ProcessEventKind, live: Boolean, blockEnabled: Boolean): ThinkingRowMode = when {
    kind != ProcessEventKind.THINKING || blockEnabled -> ThinkingRowMode.Full
    live -> ThinkingRowMode.StatusOnly
    else -> ThinkingRowMode.Hidden
}
