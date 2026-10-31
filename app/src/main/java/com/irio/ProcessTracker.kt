package com.irio

internal class ProcessTracker {
    private val events = ArrayList<ProcessEvent>()
    private var nextId = 1L
    private var liveThinkingId: Long? = null
    private var liveSearchId: Long? = null
    private var liveToolId: Long? = null
    private var thinkingStartedAt: Long? = null
    private var outputStarted = false
    private var outputLength = 0

    fun snapshot(): List<ProcessEvent> = events.toList()

    data class Checkpoint(
        val events: List<ProcessEvent>, val nextId: Long,
        val thinkingId: Long?, val searchId: Long?, val toolId: Long?,
        val thinkingStartedAt: Long?, val outputStarted: Boolean, val outputLength: Int,
    )

    fun checkpoint() = Checkpoint(snapshot(), nextId, liveThinkingId, liveSearchId, liveToolId,
        thinkingStartedAt, outputStarted, outputLength)

    fun restore(checkpoint: Checkpoint) {
        events.clear()
        events.addAll(checkpoint.events)
        nextId = checkpoint.nextId
        liveThinkingId = checkpoint.thinkingId
        liveSearchId = checkpoint.searchId
        liveToolId = checkpoint.toolId
        thinkingStartedAt = checkpoint.thinkingStartedAt
        outputStarted = checkpoint.outputStarted
        outputLength = checkpoint.outputLength
    }

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
                textOffset = eventAnchor(),
            )
        } else {
            events.replaceAll { event ->
                if (event.id == existingId) event.copy(content = event.content + delta) else event
            }
        }
    }

    fun onOutputStarted(now: Long = System.currentTimeMillis(), textLength: Int = -1) {
        finishThinking(now)
        if (events.firstOrNull { it.id == liveToolId }?.imageGeneration?.active != true) finishLiveTool()
        outputStarted = true
        if (textLength >= 0) outputLength = textLength
    }

    fun onSearchStarted(query: String = "", displayText: String = "", arguments: String = "") {
        finishThinking()
        finishLiveTool()
        if (liveSearchId != null) return
        val id = nextId++
        liveSearchId = id
        events += ProcessEvent(
            id = id,
            kind = ProcessEventKind.SEARCH,
            live = true,
            searchQuery = query,
            toolArguments = arguments,
            displayText = displayText,
            belowText = outputStarted,
            textOffset = eventAnchor(),
        )
    }

    fun onSearchFinished(pages: Int, content: String = "", outcome: ToolOutcome? = null) {
        val id = liveSearchId ?: return
        events.replaceAll { event ->
            if (event.id == id) event.copy(pages = event.pages + pages, content = content.ifBlank { event.content }, live = false, toolOutcome = outcome) else event
        }
        liveSearchId = null
    }

    /** 本地工具调用开始：锚在当前正文长度上，后续正文排在它后面，而不是整段的最下面。 */
    fun onToolStarted(name: String = "", webDomain: String = "", displayText: String = "", arguments: String = "") {
        finishThinking()
        liveToolId?.let { id ->
            // toolOutput snapshots may carry toolActive=true as well. They update the
            // existing event; they must not create one completed row per output refresh.
            if (events.firstOrNull { it.id == id }?.toolName == name) return
            finishLiveTool()
        }
        val id = nextId++
        liveToolId = id
        events += ProcessEvent(
            id = id,
            kind = ProcessEventKind.TOOL,
            live = true,
            belowText = outputStarted,
            toolName = name,
            toolArguments = arguments,
            webDomain = webDomain,
            displayText = displayText,
            textOffset = eventAnchor(),
        )
    }

    fun onSubagent(json: String) {
        val snapshot = subagentSnapshot(json) ?: return
        val existing = events.indexOfFirst { event ->
            event.kind == ProcessEventKind.SUBAGENT && subagentSnapshot(event.content)?.id == snapshot.id
        }
        if (existing >= 0) {
            events[existing] = events[existing].copy(content = json)
            return
        }
        events += ProcessEvent(
            id = nextId++,
            kind = ProcessEventKind.SUBAGENT,
            content = json,
            belowText = outputStarted,
            textOffset = eventAnchor(),
        )
    }

    fun refreshSubagents(latest: Map<String, String>) {
        if (latest.isEmpty()) return
        events.replaceAll { event ->
            if (event.kind != ProcessEventKind.SUBAGENT) return@replaceAll event
            val id = subagentSnapshot(event.content)?.id ?: return@replaceAll event
            latest[id]?.let { event.copy(content = it) } ?: event
        }
    }

    fun onColoredBubbles(bubbles: List<ColoredBubble>) {
        finishThinking()
        finishLiveTool()
        bubbles.forEach { bubble ->
            events += ProcessEvent(
                id = nextId++, kind = ProcessEventKind.BUBBLE, content = bubble.text,
                bubbleColor = bubble.color, belowText = outputStarted, textOffset = eventAnchor(),
            )
        }
    }

    private fun eventAnchor(): Int = if (outputStarted) outputLength else 0

    /** Visible image output is anchored like a bubble, never a tool-status lifecycle. */
    fun onDisplayedImages(content: String) {
        if (content.isBlank()) return
        finishThinking()
        finishLiveTool()
        events += ProcessEvent(id = nextId++, kind = ProcessEventKind.TOOL,
            toolName = CommonsImageTools.SHOW, content = content, belowText = outputStarted, textOffset = eventAnchor())
    }

    fun onDisplayedMermaid(source: String) {
        if (source.isBlank()) return
        finishThinking()
        finishLiveTool()
        events += ProcessEvent(id = nextId++, kind = ProcessEventKind.TOOL,
            toolName = MermaidTool.NAME, content = source, belowText = outputStarted,
            textOffset = eventAnchor(), toolOutcome = ToolOutcome.SUCCESS)
    }

    fun onToolFinished(content: String = "", outcome: ToolOutcome? = null) {
        finishLiveTool(content, outcome)
    }

    fun onImageGeneration(progress: ImageGenerationProgress) {
        val existing = events.indexOfFirst { it.imageGeneration?.id == progress.id }
            .takeIf { it >= 0 } ?: events.indexOfFirst {
                it.id == liveToolId && it.toolName == ImageGenTool.NAME && it.imageGeneration == null
            }
        if (existing >= 0) {
            val event = events[existing]
            events[existing] = event.copy(
                imageGeneration = if (progress.modelText.isBlank() && event.displayText.isNotBlank()) {
                    progress.copy(modelText = event.displayText)
                } else progress,
                live = progress.active,
                content = if (progress.finalAttachment != null) ImageGenTool.STATUS_OK else event.content,
                toolOutcome = if (progress.finalAttachment != null) ToolOutcome.SUCCESS else event.toolOutcome,
            )
            if (!progress.active && liveToolId == event.id) liveToolId = null
        } else {
            finishThinking()
            val id = nextId++
            events += ProcessEvent(id = id, kind = ProcessEventKind.TOOL, toolName = ImageGenTool.NAME,
                live = progress.active, belowText = outputStarted, textOffset = eventAnchor(), imageGeneration = progress,
                content = if (progress.finalAttachment != null) ImageGenTool.STATUS_OK else "")
            if (progress.active) liveToolId = id
        }
    }

    /** 成功出图不留过程行，图片本身就是结果。 */
    fun dropLiveTool() {
        val id = liveToolId ?: return
        events.removeAll { it.id == id }
        liveToolId = null
    }

    /** 替换进行中工具的展示内容，保持 live。传完整快照，不是增量。 */
    fun onToolOutput(content: String) {
        val id = liveToolId ?: return
        events.replaceAll { event ->
            if (event.id == id) event.copy(content = content) else event
        }
    }

    /** 统计结束后仍在进行中的事件数量：调用方据此判断这批事件是否已经闭合。 */
    fun liveCount(): Int = listOfNotNull(liveThinkingId, liveSearchId, liveToolId).size

    fun finishAll(now: Long = System.currentTimeMillis()) {
        finishThinking(now)
        liveSearchId?.let { finishSearch(it) }
        finishLiveTool()
        events.replaceAll { event ->
            if (event.imageGeneration?.active == true) event.copy(live = false,
                imageGeneration = event.imageGeneration.copy(active = false, preview = null)) else event
        }
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
            if (event.id == id) event.copy(live = false, toolOutcome = ToolOutcome.STOPPED) else event
        }
        liveSearchId = null
    }

    private fun finishLiveTool(content: String = "", outcome: ToolOutcome? = ToolOutcome.STOPPED) {
        val id = liveToolId ?: return
        events.replaceAll { event ->
            if (event.id == id) event.copy(
                content = content.ifBlank { event.content },
                live = false,
                toolOutcome = outcome,
                imageGeneration = event.imageGeneration?.let {
                    it.copy(active = false, failed = outcome == ToolOutcome.FAILED, preview = null)
                },
            ) else event
        }
        liveToolId = null
    }
}
