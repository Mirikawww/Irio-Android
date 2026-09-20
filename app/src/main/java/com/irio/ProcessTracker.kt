package com.irio

internal class ProcessTracker {
    private val events = ArrayList<ProcessEvent>()
    private var nextId = 1L
    private var liveThinkingId: Long? = null
    private var liveSearchId: Long? = null
    private var liveToolId: Long? = null
    private var thinkingStartedAt: Long? = null
    private var outputStarted = false

    fun snapshot(): List<ProcessEvent> = events.toList()

    fun onThinkingDelta(delta: String, now: Long = System.currentTimeMillis()) {
        if (delta.isEmpty()) return
        liveSearchId?.let { finishSearch(it) }
        finishLiveTool()
        val existingId = liveThinkingId
        if (existingId == null) {
            val id = nextId++
            liveThinkingId = id
            thinkingStartedAt = now
            events += ProcessEvent(
                id = id,
                kind = ProcessEventKind.THINKING,
                content = delta,
                live = true,
                belowText = outputStarted,
            )
        } else {
            events.replaceAll { event ->
                if (event.id == existingId) event.copy(content = event.content + delta) else event
            }
        }
    }

    fun onOutputStarted(now: Long = System.currentTimeMillis()) {
        finishThinking(now)
        finishLiveTool()
        outputStarted = true
    }

    fun onSearchStarted() {
        finishThinking()
        finishLiveTool()
        if (liveSearchId != null) return
        val id = nextId++
        liveSearchId = id
        events += ProcessEvent(
            id = id,
            kind = ProcessEventKind.SEARCH,
            live = true,
            belowText = outputStarted,
        )
    }

    fun onSearchFinished(pages: Int, content: String = "") {
        val id = liveSearchId ?: return
        events.replaceAll { event ->
            if (event.id == id) event.copy(pages = event.pages + pages, content = content.ifBlank { event.content }, live = false) else event
        }
        liveSearchId = null
    }

    /** 本地工具调用开始：正文开始后启动的渲染在文本下方。 */
    fun onToolStarted(name: String = "") {
        finishThinking()
        finishLiveTool()
        if (liveToolId != null) return
        val id = nextId++
        liveToolId = id
        events += ProcessEvent(
            id = id,
            kind = ProcessEventKind.TOOL,
            live = true,
            belowText = outputStarted,
            toolName = name,
        )
    }

    fun onToolFinished() {
        finishLiveTool()
    }

    /** 统计结束后仍在进行中的事件数量：调用方据此判断这批事件是否已经闭合。 */
    fun liveCount(): Int = listOfNotNull(liveThinkingId, liveSearchId, liveToolId).size

    fun finishAll(now: Long = System.currentTimeMillis()) {
        finishThinking(now)
        liveSearchId?.let { finishSearch(it) }
        finishLiveTool()
    }

    private fun finishThinking(now: Long = System.currentTimeMillis()) {
        val id = liveThinkingId ?: return
        val started = thinkingStartedAt
        val elapsed = if (started != null) (now - started).coerceAtLeast(0L) else 0L
        events.replaceAll { event ->
            if (event.id == id) event.copy(millis = elapsed, live = false) else event
        }
        liveThinkingId = null
        thinkingStartedAt = null
    }

    private fun finishSearch(id: Long) {
        events.replaceAll { event ->
            if (event.id == id) event.copy(live = false) else event
        }
        liveSearchId = null
    }

    private fun finishLiveTool() {
        val id = liveToolId ?: return
        events.replaceAll { event ->
            if (event.id == id) event.copy(live = false) else event
        }
        liveToolId = null
    }
}
