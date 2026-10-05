package com.irio

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 只有模型放进 `background` 的命令会留在这次工具调用之后。
 * 登记表同时给输入框上方的列表和 linux_background 用。
 */
internal data class LinuxBackgroundCommand(
    val id: Long,
    val command: String,
    val output: String,
    val truncated: Boolean,
    val running: Boolean,
    val exitCode: Int?,
    val stopped: Boolean,
    val conversationId: String = "",
)

internal data class LinuxBackgroundRequest(
    val action: String,
    val id: String,
)

internal data class LinuxBackgroundStart(
    val id: Long? = null,
    val error: String? = null,
)

internal class LinuxBackgroundBoard(
    private val outputLimit: Int = 12_000,
    private val finishedLimit: Int = 20,
) {
    private val lock = Any()
    private var nextId = 1L
    private val running = LinkedHashMap<Long, Entry>()
    private val finished = ArrayDeque<Entry>()
    private val listeners = mutableListOf<() -> Unit>()

    fun add(command: String, conversationId: String = "", stop: () -> Unit): Long {
        val id = synchronized(lock) {
            val assigned = nextId++
            running[assigned] = Entry(assigned, command, conversationId, stop)
            assigned
        }
        notifyListeners()
        return id
    }

    fun append(id: Long, bytes: ByteArray, count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            val entry = running[id] ?: finished.firstOrNull { it.id == id } ?: return
            if (entry.truncated) return
            val room = outputLimit - entry.output.size()
            if (room <= 0) {
                entry.truncated = true
                return
            }
            val accepted = minOf(count, room)
            entry.output.write(bytes, 0, accepted)
            if (accepted < count) entry.truncated = true
        }
    }

    fun finish(id: Long, exitCode: Int) {
        val changed = synchronized(lock) {
            val live = running.remove(id)
            val entry = live ?: finished.firstOrNull { it.id == id } ?: return@synchronized false
            entry.running = false
            entry.exitCode = exitCode
            if (live != null) rememberFinished(entry)
            true
        }
        if (changed) notifyListeners()
    }

    fun stop(id: Long): Boolean {
        val stopper = synchronized(lock) {
            val entry = running.remove(id) ?: return false
            entry.running = false
            entry.stopped = true
            rememberFinished(entry)
            entry.stop
        }
        stopper()
        notifyListeners()
        return true
    }

    fun stopAll() {
        while (true) {
            val id = synchronized(lock) { running.keys.firstOrNull() } ?: return
            stop(id)
        }
    }

    fun running(): List<LinuxBackgroundCommand> = synchronized(lock) {
        running.values.map { it.toCommand() }
    }

    fun tracked(): List<LinuxBackgroundCommand> = synchronized(lock) {
        running.values.map { it.toCommand() } + finished.map { it.toCommand() }
    }

    fun find(idText: String): LinuxBackgroundCommand? {
        val id = idText.toLongOrNull() ?: return null
        return synchronized(lock) {
            (running[id] ?: finished.firstOrNull { it.id == id })?.toCommand()
        }
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        synchronized(lock) { listeners += listener }
        return { synchronized(lock) { listeners.remove(listener) } }
    }

    private fun rememberFinished(entry: Entry) {
        finished.addLast(entry)
        while (finished.size > finishedLimit) finished.removeAt(0)
    }

    private fun notifyListeners() {
        val current = synchronized(lock) { listeners.toList() }
        current.forEach { listener -> runCatching { listener() } }
    }

    private class Entry(
        val id: Long,
        val command: String,
        val conversationId: String,
        val stop: () -> Unit,
    ) {
        val output = ByteArrayOutputStream()
        var truncated = false
        var running = true
        var exitCode: Int? = null
        var stopped = false

        fun toCommand() = LinuxBackgroundCommand(
            id = id,
            command = command,
            output = output.toByteArray().toString(Charsets.UTF_8),
            truncated = truncated,
            running = running,
            exitCode = exitCode,
            stopped = stopped,
            conversationId = conversationId,
        )
    }
}

internal object LinuxBackgroundJobs {
    private val board = LinuxBackgroundBoard()

    fun track(command: String, conversationId: String = "", stop: () -> Unit): Long =
        board.add(command, conversationId, stop)

    fun append(id: Long, bytes: ByteArray, count: Int) = board.append(id, bytes, count)

    fun finish(id: Long, exitCode: Int) = board.finish(id, exitCode)

    fun stop(id: Long): Boolean = board.stop(id)

    fun stopAll() = board.stopAll()

    fun running(): List<LinuxBackgroundCommand> = board.running()

    fun find(id: String): LinuxBackgroundCommand? = board.find(id)

    fun handle(arguments: String): String = linuxBackgroundHandle(arguments, board)

    fun subscribe(listener: () -> Unit): () -> Unit = board.subscribe(listener)
}

internal fun linuxBackgroundParseRequest(arguments: String): LinuxBackgroundRequest {
    val parsed = runCatching { JSONObject(arguments) }.getOrNull()
        ?: return LinuxBackgroundRequest("list", "")
    val action = parsed.optString("action").trim().lowercase()
    return LinuxBackgroundRequest(
        action = if (action.isEmpty()) "list" else action,
        id = parsed.optString("id").trim(),
    )
}

internal fun linuxBackgroundHandle(arguments: String, board: LinuxBackgroundBoard): String {
    val request = linuxBackgroundParseRequest(arguments)
    return when (request.action) {
        "list" -> linuxBackgroundFormatList(board.tracked())
        "status" -> linuxBackgroundFormatStatus(board.find(request.id))
        "stop" -> {
            val found = board.find(request.id)
            when {
                found == null -> tr("没有这条后台命令。")
                !found.running -> tr("后台命令 %s 已经结束。").format(found.id)
                board.stop(found.id) -> tr("已停止后台命令 %s。").format(found.id)
                else -> tr("没有这条后台命令。")
            }
        }
        else -> tr("未知操作")
    }
}

internal fun linuxBackgroundFormatList(commands: List<LinuxBackgroundCommand>): String {
    if (commands.isEmpty()) return tr("没有后台命令。")
    val running = commands.filter { it.running }
    val done = commands.filter { !it.running }
    return buildString {
        append(tr("运行中 %s 条").format(running.size))
        running.forEach { command ->
            append('\n')
            append(linuxBackgroundBrief(command))
        }
        if (done.isNotEmpty()) {
            append("\n\n")
            append(tr("已结束 %s 条").format(done.size))
            done.forEach { command ->
                append('\n')
                append(linuxBackgroundBrief(command))
            }
        }
    }
}

internal fun linuxBackgroundFormatStatus(command: LinuxBackgroundCommand?): String {
    if (command == null) return tr("没有这条后台命令。")
    val state = when {
        command.running -> tr("运行中")
        command.stopped -> tr("已停止")
        else -> tr("已结束")
    }
    return buildString {
        append(tr("编号 %s").format(command.id))
        append('\n')
        append(tr("命令：%s").format(command.command))
        append('\n')
        append(tr("状态：%s").format(state))
        if (!command.running && command.exitCode != null) {
            append('\n')
            append(tr("退出码 %s").format(command.exitCode))
        }
        append('\n')
        append(tr("输出："))
        if (command.output.isNotEmpty()) {
            append('\n')
            append(command.output.trimEnd())
        }
        if (command.truncated) {
            append('\n')
            append(tr("输出已截断"))
        }
    }
}

private fun linuxBackgroundBrief(command: LinuxBackgroundCommand): String {
    val state = when {
        command.running -> tr("运行中")
        command.stopped -> tr("已停止")
        else -> tr("退出码 %s").format(command.exitCode ?: -1)
    }
    return "${command.id}  ${command.command}  $state"
}

internal object LinuxBackgroundTool {
    const val NAME = "linux_background"

    private const val DESCRIPTION =
        "Inspect or stop background commands started by linux_shell. " +
            "Pass {\"action\":\"list\",\"id\":\"\"} for how many are running and a short line for each tracked command. " +
            "Pass {\"action\":\"status\",\"id\":\"1\"} for one command's detail, including its output. " +
            "Pass {\"action\":\"stop\",\"id\":\"1\"} to stop that command. " +
            "Ids come from linux_shell when a background command starts."

    fun schemaFor(protocol: ProviderProtocol): JSONObject =
        functionSchema(protocol, parameters())

    private fun parameters(): JSONObject {
        val action = JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("list").put("status").put("stop"))
            .put(
                "description",
                "list: running count and one line per tracked command. status: one command's detail. stop: stop that command.",
            )
        val id = JSONObject()
            .put("type", "string")
            .put("description", "Background command id. Empty string when action is list.")
        return JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("action", action).put("id", id))
            .put("required", JSONArray().put("action").put("id"))
            .put("additionalProperties", false)
    }

    private fun functionSchema(protocol: ProviderProtocol, parameters: JSONObject): JSONObject = when (protocol) {
        ProviderProtocol.CHAT_COMPLETIONS -> JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject().put("name", NAME).put("description", DESCRIPTION).put("parameters", parameters),
            )
        ProviderProtocol.RESPONSES -> JSONObject()
            .put("type", "function")
            .put("name", NAME)
            .put("description", DESCRIPTION)
            .put("parameters", parameters)
            .put("strict", true)
        ProviderProtocol.ANTHROPIC -> JSONObject()
            .put("name", NAME)
            .put("description", DESCRIPTION)
            .put("input_schema", parameters)
    }
}
