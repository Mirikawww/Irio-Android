package com.irio

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal enum class SubagentState {
    RUNNING,
    COMPLETED,
    FAILED,
}

internal data class SubagentLine(
    val fromUser: Boolean,
    val text: String,
    val process: List<ProcessEvent> = emptyList(),
)

internal fun SubagentLine.visibleText(): String = visibleTranscriptText(text, process)

internal fun SubagentSnapshot.finalOutputText(): String =
    lines.lastOrNull { !it.fromUser }?.visibleText().orEmpty()

internal data class SubagentSnapshot(
    val id: String,
    val conversationId: String,
    val name: String,
    val createdAt: Long,
    val state: SubagentState,
    val lines: List<SubagentLine> = emptyList(),
    val liveText: String = "",
    val liveProcess: List<ProcessEvent> = emptyList(),
)

internal fun SubagentSnapshot.toJson(): String = JSONObject()
    .put("id", id)
    .put("conversationId", conversationId)
    .put("name", name)
    .put("createdAt", createdAt)
    .put("state", state.name)
    .put("liveText", liveText)
    .put("liveProcess", subagentProcessJson(liveProcess))
    .put("lines", JSONArray().apply {
        lines.forEach { line ->
            put(
                JSONObject()
                    .put("fromUser", line.fromUser)
                    .put("text", line.text)
                    .put("process", subagentProcessJson(line.process)),
            )
        }
    })
    .toString()

internal fun subagentSnapshot(json: String): SubagentSnapshot? {
    val parsed = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val id = parsed.optString("id")
    if (id.isBlank()) return null
    val linesJson = parsed.optJSONArray("lines") ?: JSONArray()
    val lines = buildList {
        for (index in 0 until linesJson.length()) {
            val line = linesJson.optJSONObject(index) ?: continue
            add(
                SubagentLine(
                    line.optBoolean("fromUser"),
                    line.optString("text"),
                    subagentProcessFrom(line.optJSONArray("process")),
                ),
            )
        }
    }
    val state = runCatching { SubagentState.valueOf(parsed.optString("state")) }.getOrDefault(SubagentState.FAILED)
    return SubagentSnapshot(
        id = id,
        conversationId = parsed.optString("conversationId"),
        name = parsed.optString("name"),
        createdAt = parsed.optLong("createdAt"),
        state = state,
        lines = lines,
        liveText = parsed.optString("liveText"),
        liveProcess = subagentProcessFrom(parsed.optJSONArray("liveProcess")),
    )
}

internal fun subagentProcessJson(events: List<ProcessEvent>): JSONArray = JSONArray().apply {
    events.forEach { event ->
        put(
            JSONObject().apply {
                put("id", event.id)
                put("kind", event.kind.name)
                put("content", event.content)
                put("millis", event.millis)
                put("pages", event.pages)
                if (event.belowText) put("belowText", true)
                if (event.textOffset > 0) put("textOffset", event.textOffset)
                if (event.toolName.isNotBlank()) put("toolName", event.toolName)
                if (event.searchQuery.isNotBlank()) put("searchQuery", event.searchQuery)
                if (event.webDomain.isNotBlank()) put("webDomain", event.webDomain)
                if (event.displayText.isNotEmpty()) put("displayText", event.displayText)
                if (event.bubbleColor.isNotEmpty()) put("bubbleColor", event.bubbleColor)
            },
        )
    }
}

internal fun subagentProcessFrom(array: JSONArray?): List<ProcessEvent> =
    processEventsFrom(JSONObject().put("process", array ?: JSONArray()))

/** 工具跑完但没有正文时也要留下过程行，否则子智能体页面看不到工具调用提示。 */
internal fun settledSubagentLine(text: String, process: List<ProcessEvent>): SubagentLine? {
    val closed = process.map { event -> if (event.live) event.copy(live = false) else event }
    if (text.isBlank() && closed.isEmpty()) return null
    return SubagentLine(fromUser = false, text = text, process = closed)
}

/** 子智能体一轮流式输出。工具、搜索和思考走与主对话相同的锚点，不把提示丢掉。 */
internal class SubagentTurn {
    private val tracker = ProcessTracker()
    private var body = ""
    private var roundBreakPending = false

    fun apply(update: ChatStreamUpdate): Boolean {
        var changed = false
        if (update.newRound && body.isNotEmpty()) roundBreakPending = true
        if (update.thinkingDelta.isNotEmpty()) {
            tracker.onThinkingDelta(update.thinkingDelta)
            changed = true
        }
        if (update.delta.isNotEmpty()) {
            if (roundBreakPending) {
                body = appendAssistantRoundBreak(body)
                roundBreakPending = false
            }
            body += update.delta
            tracker.onOutputStarted(textLength = body.length)
            changed = true
        }
        if (update.searching == true) {
            tracker.onSearchStarted(update.searchQuery, update.displayText)
            changed = true
        }
        if (update.searching == false) {
            tracker.onSearchFinished(update.searchPagesDelta, update.searchContent)
            changed = true
        }
        if (update.toolActive == true) {
            tracker.onToolStarted(update.toolName, update.webDomain, update.displayText)
            changed = true
        }
        if (update.toolOutput != null) {
            tracker.onToolOutput(update.toolOutput)
            changed = true
        }
        if (update.toolActive == false) {
            if (update.toolName == ImageGenTool.NAME && update.toolContent == ImageGenTool.STATUS_OK) {
                tracker.dropLiveTool()
            } else {
                tracker.onToolFinished(update.toolContent)
            }
            changed = true
        }
        if (update.coloredBubbles.isNotEmpty()) {
            tracker.onColoredBubbles(update.coloredBubbles)
            changed = true
        }
        if (update.done) {
            tracker.finishAll()
            changed = true
        }
        return changed
    }

    fun text(): String = body

    fun visibleText(): String = visibleTranscriptText(body, tracker.snapshot())

    fun process(): List<ProcessEvent> = tracker.snapshot()

    fun finish() {
        tracker.finishAll()
    }
}

internal class SubagentFinishQueue {
    private val pending = linkedMapOf<String, MutableList<SubagentSnapshot>>()
    private val seen = mutableSetOf<String>()

    /** 同一个子智能体只入队一次，避免完成通知触发两轮报告。 */
    fun add(snapshot: SubagentSnapshot): Boolean {
        if (snapshot.state == SubagentState.RUNNING || snapshot.conversationId.isBlank()) return false
        if (!seen.add(snapshot.id)) return false
        pending.getOrPut(snapshot.conversationId) { mutableListOf() }.add(snapshot)
        return true
    }

    fun take(conversationId: String): List<SubagentSnapshot> = pending.remove(conversationId).orEmpty()
}

internal fun subagentStatusLabel(state: SubagentState): String = when (state) {
    SubagentState.RUNNING -> tr("运行中")
    SubagentState.COMPLETED -> tr("已完成")
    SubagentState.FAILED -> tr("失败")
}

internal object SubagentTools {
    const val CREATE = "create_subagent"
    const val MESSAGE = "message_subagent"
    const val STOP = "stop_subagent"
    const val INSPECT = "inspect_subagent"
    val NAMES = setOf(CREATE, MESSAGE, STOP, INSPECT)
    val QUIET = setOf(CREATE, INSPECT)

    private const val CREATE_DESCRIPTION =
        "Create a named subagent and start it on a task. The subagent cannot create subagents. " +
            "You may create as many as you need, each with its own name. " +
            "Call with {\"name\":\"short name\",\"task\":\"what it should do\"}. " +
            "Create every subagent you need in this same response. " +
            "Do not wait, think, or call inspect_subagent to watch it. " +
            "When it finishes, its final output is delivered back to you once, automatically."
    private const val MESSAGE_DESCRIPTION =
        "Send a follow-up to a subagent that is still running. " +
            "Call with {\"name\":\"the name you gave it\",\"message\":\"what to tell it\"}."
    private const val STOP_DESCRIPTION =
        "Stop a running subagent. Call with {\"name\":\"the name you gave it\"}."
    private const val INSPECT_DESCRIPTION =
        "Read a subagent's live status and the text it has produced so far. " +
            "Call with {\"name\":\"the name you gave it\"}. Do not poll it; the final output is delivered automatically."

    fun schema(protocol: ProviderProtocol, name: String, description: String, parameters: JSONObject): JSONObject =
        when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> JSONObject()
                .put("type", "function")
                .put("function", JSONObject().put("name", name).put("description", description).put("parameters", parameters))
            ProviderProtocol.RESPONSES -> JSONObject()
                .put("type", "function")
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters)
                .put("strict", true)
            ProviderProtocol.ANTHROPIC -> JSONObject()
                .put("name", name)
                .put("description", description)
                .put("input_schema", parameters)
            ProviderProtocol.GEMINI -> GeminiProtocol.declaration(name, description, parameters)
            ProviderProtocol.GEMINI_INTERACTIONS -> GeminiInteractionsProtocol.declaration(name, description, parameters)
        }

    fun definitions(protocol: ProviderProtocol): List<JSONObject> = listOf(
        schema(protocol, CREATE, CREATE_DESCRIPTION, params("name", "task")),
        schema(protocol, MESSAGE, MESSAGE_DESCRIPTION, params("name", "message")),
        schema(protocol, STOP, STOP_DESCRIPTION, params("name")),
        schema(protocol, INSPECT, INSPECT_DESCRIPTION, params("name")),
    )

    private fun params(vararg required: String): JSONObject {
        val properties = JSONObject()
        required.forEach { key ->
            properties.put(key, JSONObject().put("type", "string"))
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray().apply { required.forEach { put(it) } })
            .put("additionalProperties", false)
    }
}

internal fun ToolRegistry.withoutSubagents(): ToolRegistry =
    ToolRegistry(
        providers().filter { it.id != SubagentToolProvider.ID },
        smartToolHints = smartToolHints,
        preferences = preferences,
    )

internal class SubagentToolProvider : ToolProvider {
    override val id: String = ID

    override fun definitions(protocol: ProviderProtocol): List<JSONObject> = SubagentTools.definitions(protocol)

    override fun handles(name: String): Boolean = name in SubagentTools.NAMES

    override fun systemInstructions(): String =
        "Subagents are separate assistants you can create, message while they are running, and stop. " +
            "Give every subagent a distinct name. A subagent cannot create further subagents. " +
            "After create_subagent, stop. Do not wait or poll inspect_subagent. " +
            "Its final output is sent back to you once when it finishes."

    override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val name = ToolArguments.value(call.arguments, "name").trim()
        if (name.isEmpty()) return ToolExecutionResult(tr("子智能体需要一个名称。"))
        return when (call.name) {
            SubagentTools.CREATE -> SubagentHub.create(name, ToolArguments.value(call.arguments, "task").trim(), context)
            SubagentTools.MESSAGE -> {
                context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = call.name))
                try {
                    SubagentHub.message(context.conversationId, name, ToolArguments.value(call.arguments, "message").trim())
                } finally {
                    context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = call.name))
                }
            }
            SubagentTools.STOP -> {
                context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = call.name))
                try {
                    SubagentHub.stop(context.conversationId, name)
                } finally {
                    context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = call.name))
                }
            }
            SubagentTools.INSPECT -> SubagentHub.inspect(context.conversationId, name)
            else -> ToolExecutionResult(tr("未知工具 %s").format(call.name))
        }
    }

    companion object {
        const val ID = "subagent"
    }
}

internal object SubagentHub {
    val sessions: SnapshotStateMap<String, SubagentSnapshot> = mutableStateMapOf()
    var onChanged: ((SubagentSnapshot) -> Unit)? = null
    var onFinished: ((SubagentSnapshot) -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = ConcurrentHashMap<String, MutableList<String>>()
    private val cancellations = ConcurrentHashMap<String, ChatRequestCancellation>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val fresh = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())

    fun get(id: String): SubagentSnapshot? = sessions[id]

    fun jsonById(): Map<String, String> = sessions.mapValues { it.value.toJson() }

    fun isFresh(id: String): Boolean = fresh.contains(id)

    fun settle(id: String) {
        fresh.remove(id)
    }

    fun hydrate(snapshot: SubagentSnapshot) {
        if (sessions[snapshot.id] == null) sessions[snapshot.id] = snapshot
    }

    suspend fun create(name: String, task: String, context: ToolExecutionContext): ToolExecutionResult {
        if (task.isEmpty()) return ToolExecutionResult(tr("子智能体需要一项任务。"))
        if (context.provider.id.isBlank()) return ToolExecutionResult(tr("请先在设置中添加提供商"))
        val conversationId = context.conversationId
        if (sessions.values.any { it.conversationId == conversationId && it.name == name }) {
            return ToolExecutionResult(tr("已经有名为「%s」的子智能体，请换一个名字。").format(name))
        }
        val appContext = context.appContext
        val model = appContext?.let { ToolStore.loadSubagentModel(it) }.orEmpty().ifBlank { context.model }
        if (model.isBlank()) return ToolExecutionResult(tr("没有可用的子智能体模型。"))
        val snapshot = SubagentSnapshot(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            name = name,
            createdAt = System.currentTimeMillis(),
            state = SubagentState.RUNNING,
            lines = listOf(SubagentLine(fromUser = true, text = task)),
        )
        val cancellation = ChatRequestCancellation()
        cancellations[snapshot.id] = cancellation
        locks[snapshot.id] = Mutex()
        fresh.add(snapshot.id)
        publish(snapshot, persist = true)
        context.onUpdate(ChatStreamUpdate(subagentJson = snapshot.toJson()))
        val registry = (context.registry ?: ToolRegistry.default()).withoutSubagents()
        scope.launch {
            run(snapshot.id, task, context, model, registry, cancellation)
        }
        return ToolExecutionResult(
            tr("子智能体「%s」已创建并开始运行。不要等待，完成后最终输出会自动交回。").format(name),
            endTurn = true,
        )
    }

    suspend fun message(conversationId: String, name: String, text: String): ToolExecutionResult {
        if (text.isEmpty()) return ToolExecutionResult(tr("发给子智能体的内容不能为空。"))
        val session = sessions.values.firstOrNull { it.conversationId == conversationId && it.name == name }
            ?: return ToolExecutionResult(tr("找不到名为「%s」的子智能体。").format(name))
        if (session.state != SubagentState.RUNNING) return ToolExecutionResult(tr("子智能体「%s」已经不在运行。").format(name))
        locks[session.id]?.withLock {
            inbox.getOrPut(session.id) { mutableListOf() }.add(text)
        }
        return ToolExecutionResult(tr("已发送给子智能体「%s」。").format(name))
    }

    fun stop(conversationId: String, name: String): ToolExecutionResult {
        val session = sessions.values.firstOrNull { it.conversationId == conversationId && it.name == name }
            ?: return ToolExecutionResult(tr("找不到名为「%s」的子智能体。").format(name))
        return stopById(session.id, name)
    }

    fun stopById(id: String): ToolExecutionResult {
        val session = sessions[id] ?: return ToolExecutionResult(tr("找不到这个子智能体。"))
        return stopById(id, session.name)
    }

    private fun stopById(id: String, name: String): ToolExecutionResult {
        cancellations[id]?.cancel()
        val current = sessions[id]
        if (current != null && current.state == SubagentState.RUNNING) {
            publish(current.copy(state = SubagentState.FAILED, liveText = ""), persist = true)
        }
        return ToolExecutionResult(tr("已停止子智能体「%s」。").format(name))
    }

    fun inspect(conversationId: String, name: String): ToolExecutionResult {
        val session = sessions.values.firstOrNull { it.conversationId == conversationId && it.name == name }
            ?: return ToolExecutionResult(tr("找不到名为「%s」的子智能体。").format(name))
        val finished = session.lines.filterNot { it.fromUser }.joinToString("\n\n") { it.visibleText() }
        val live = visibleTranscriptText(session.liveText, session.liveProcess)
        return ToolExecutionResult(
            buildString {
                append(tr("子智能体「%s」状态：%s").format(session.name, subagentStatusLabel(session.state)))
                if (finished.isNotBlank()) {
                    append("\n")
                    append(tr("已输出："))
                    append("\n")
                    append(finished)
                }
                if (live.isNotBlank()) {
                    append("\n")
                    append(tr("正在输出："))
                    append("\n")
                    append(live)
                }
            },
        )
    }

    private suspend fun run(
        id: String,
        task: String,
        context: ToolExecutionContext,
        model: String,
        registry: ToolRegistry,
        cancellation: ChatRequestCancellation,
    ) {
        val history = mutableListOf(ChatRequestMessage(role = "user", text = task))
        var pending = emptyList<String>()
        while (!cancellation.isCancelled()) {
            val turn = SubagentTurn()
            val result = try {
                ChatApi.streamChat(
                    provider = context.provider,
                    model = model,
                    messages = history.toList(),
                    reasoningEffort = context.reasoningEffort,
                    fastMode = context.fastMode,
                    webSearch = context.webSearch,
                    searchProvider = context.searchProvider,
                    searchApiKey = context.searchApiKey,
                    searchMaxResults = context.searchMaxResults,
                    systemPrompt = registry.systemPrompt(null),
                    imageGenModel = context.imageGenModel,
                    toolRegistry = registry,
                    connectionScope = "${context.conversationId}#subagent#$id",
                    conversationId = context.conversationId,
                    cancellation = cancellation,
                    appContext = context.appContext,
                    onUpdate = { update ->
                        if (turn.apply(update)) {
                            sessions[id]?.let {
                                publish(it.copy(liveText = turn.text(), liveProcess = turn.process()), persist = false)
                            }
                        }
                    },
                )
            } catch (_: Exception) {
                fail(id)
                return
            }
            turn.finish()
            val text = turn.text()
            val visibleText = turn.visibleText()
            val reply = settledSubagentLine(text, turn.process())
            val current = sessions[id] ?: return
            fun withReply(lines: List<SubagentLine>): List<SubagentLine> =
                if (reply == null) lines else lines + reply
            if (cancellation.isCancelled() || current.state == SubagentState.FAILED) {
                publish(
                    current.copy(state = SubagentState.FAILED, lines = withReply(current.lines), liveText = "", liveProcess = emptyList()),
                    persist = true,
                )
                return
            }
            if (result.error != null) {
                publish(
                    current.copy(state = SubagentState.FAILED, lines = withReply(current.lines), liveText = "", liveProcess = emptyList()),
                    persist = true,
                )
                return
            }
            if (visibleText.isNotBlank()) history += ChatRequestMessage(role = "assistant", text = visibleText)
            val replied = withReply(current.lines)
            pending = drain(id)
            if (pending.isEmpty()) {
                publish(
                    current.copy(state = SubagentState.COMPLETED, lines = replied, liveText = "", liveProcess = emptyList()),
                    persist = true,
                )
                return
            }
            pending.forEach { history += ChatRequestMessage(role = "user", text = it) }
            publish(
                current.copy(
                    state = SubagentState.RUNNING,
                    lines = replied + pending.map { SubagentLine(true, it) },
                    liveText = "",
                    liveProcess = emptyList(),
                ),
                persist = true,
            )
        }
        fail(id)
    }

    private fun fail(id: String) {
        val current = sessions[id] ?: return
        if (current.state == SubagentState.RUNNING) {
            publish(current.copy(state = SubagentState.FAILED, liveText = ""), persist = true)
        }
    }

    private suspend fun drain(id: String): List<String> {
        val lock = locks[id] ?: return emptyList()
        return lock.withLock {
            val queued = inbox[id].orEmpty().toList()
            inbox[id]?.clear()
            queued
        }
    }

    private fun publish(snapshot: SubagentSnapshot, persist: Boolean) {
        val deliver = {
            val previous = sessions[snapshot.id]
            sessions[snapshot.id] = snapshot
            if (persist) onChanged?.invoke(snapshot)
            if (persist && snapshot.state != SubagentState.RUNNING && previous?.state != snapshot.state) {
                onFinished?.invoke(snapshot)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver() else main.post(deliver)
    }
}
