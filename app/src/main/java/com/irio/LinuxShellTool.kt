package com.irio

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal object LinuxShellTool {
    const val NAME = "linux_shell"

    fun descriptionFor(commandsPerCall: Int, backgroundEnabled: Boolean = false): String {
        val base = "Run one or more shell commands, in order, in one call inside the persistent Alpine Linux sandbox. " +
            "The guest home is /root and device shared storage is mounted at /sdcard. " +
            "Packages and files inside the sandbox persist across calls. " +
            "Pass {\"commands\":[\"first command\",\"second command\"]}."
        val background = if (!backgroundEnabled) {
            ""
        } else {
            " Commands in \"commands\" finish before this call returns. " +
                "To keep a command running after the call, pass \"background\":[\"command\"]. " +
                "Only commands you put in background keep running; do not background ordinary commands, and do not append &. " +
                "Use linux_background to list them, read one command's status, or stop one by id."
        }
        val limit = linuxSandboxClampCommandsPerCall(commandsPerCall)
        val cap = if (limit == LinuxSandboxStore.COMMANDS_UNLIMITED) {
            ""
        } else {
            " At most $limit commands run per call." +
                if (backgroundEnabled) " The limit counts commands and background together." else ""
        }
        return base + background + cap
    }

    fun schemaFor(
        protocol: ProviderProtocol,
        commandsPerCall: Int = LinuxSandboxStore.COMMANDS_UNLIMITED,
        backgroundEnabled: Boolean = false,
    ): JSONObject = functionSchema(protocol, parameters(backgroundEnabled), descriptionFor(commandsPerCall, backgroundEnabled))

    private fun parameters(backgroundEnabled: Boolean): JSONObject {
        val commands = JSONObject()
            .put("type", "array")
            .put(
                "description",
                if (backgroundEnabled) {
                    "Shell commands to run to completion, in order, before this call returns. These are not background."
                } else {
                    "Shell commands to run in order inside Alpine"
                },
            )
            .put("items", JSONObject().put("type", "string"))
        val properties = JSONObject().put("commands", commands)
        val required = JSONArray().put("commands")
        if (backgroundEnabled) {
            properties.put(
                "background",
                JSONObject()
                    .put("type", "array")
                    .put(
                        "description",
                        "Commands that keep running after this call returns. Pass [] when none should. Never put an ordinary command here, and do not append &.",
                    )
                    .put("items", JSONObject().put("type", "string")),
            )
            required.put("background")
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)
            .put("additionalProperties", false)
    }

    private fun functionSchema(
        protocol: ProviderProtocol,
        parameters: JSONObject,
        description: String,
    ): JSONObject = when (protocol) {
        ProviderProtocol.GEMINI -> GeminiProtocol.declaration(NAME, description, parameters)
        ProviderProtocol.GEMINI_INTERACTIONS -> GeminiInteractionsProtocol.declaration(NAME, description, parameters)
        ProviderProtocol.CHAT_COMPLETIONS -> JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject().put("name", NAME).put("description", description).put("parameters", parameters),
            )
        ProviderProtocol.RESPONSES -> JSONObject()
            .put("type", "function")
            .put("name", NAME)
            .put("description", description)
            .put("parameters", parameters)
            .put("strict", true)
        ProviderProtocol.ANTHROPIC -> JSONObject()
            .put("name", NAME)
            .put("description", description)
            .put("input_schema", parameters)
    }
}

internal class LinuxShellToolProvider(
    private val commandsPerCall: Int = LinuxSandboxStore.COMMANDS_UNLIMITED,
    private val backgroundEnabled: Boolean = false,
) : ToolProvider {
    override val id: String = "linux-shell"

    override fun definitions(protocol: ProviderProtocol): List<JSONObject> = buildList {
        add(LinuxShellTool.schemaFor(protocol, commandsPerCall, backgroundEnabled))
        if (backgroundEnabled) add(LinuxBackgroundTool.schemaFor(protocol))
    }

    override fun handles(name: String): Boolean =
        name == LinuxShellTool.NAME || (backgroundEnabled && name == LinuxBackgroundTool.NAME)

    override suspend fun execute(
        call: ChatToolCall,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        if (call.name == LinuxBackgroundTool.NAME) return executeBackground(call, context)
        val limit = context.appContext?.let {
            LinuxSandboxStore.loadCommandsPerCall(LinuxSandboxLayout.of(it.filesDir).stateFile)
        } ?: commandsPerCall
        val backgroundOn = context.appContext?.let {
            LinuxSandboxStore.loadBackgroundEnabled(LinuxSandboxLayout.of(it.filesDir).stateFile)
        } ?: backgroundEnabled
        val selection = linuxSandboxSelectCommands(call.arguments, limit, backgroundOn)
        val finished = mutableListOf<String>()
        val returned = mutableListOf<String>()
        var transcript = ""
        suspend fun publish(current: String? = null) {
            transcript = linuxSandboxJoinSections(finished + listOfNotNull(current))
            context.onUpdate(
                ChatStreamUpdate(
                    toolActive = true,
                    toolName = LinuxShellTool.NAME,
                    toolOutput = transcript,
                ),
            )
        }
        val first = selection.commands.firstOrNull() ?: selection.background.firstOrNull()
        publish(first?.let { linuxSandboxTerminalTranscript(it, "") })
        return try {
            fun refuse(message: String): ToolExecutionResult {
                val shown = selection.commands + selection.background
                transcript = if (shown.isEmpty()) {
                    message
                } else {
                    linuxSandboxJoinSections(
                        shown.map { linuxSandboxTerminalTranscript(it, message) },
                    )
                }
                return ToolExecutionResult(message)
            }
            val app = context.appContext ?: return refuse(tr("Linux 沙盒当前不可用。"))
            if (!LinuxSandboxPolicy.exposed(
                    LinuxSandboxStore.loadEnabled(LinuxSandboxLayout.of(app.filesDir).stateFile),
                    LinuxSandboxAccess.hasAllFiles(),
                )
            ) {
                return refuse(tr("Linux 沙盒已关闭。"))
            }
            if (selection.commands.isEmpty() && selection.background.isEmpty()) {
                val message = if (selection.backgroundIgnored > 0) {
                    tr("后台命令已关闭，未启动 %s 条。").format(selection.backgroundIgnored)
                } else {
                    tr("命令为空")
                }
                transcript = message
                return ToolExecutionResult(message)
            }
            for (command in selection.commands) {
                if (context.cancellation.isCancelled()) throw CancellationException()
                val normalized = linuxSandboxNormalizeCommand(command)
                if (normalized == null) {
                    val message = if (command.trim().isEmpty()) tr("命令为空") else tr("命令太长")
                    finished += linuxSandboxTerminalTranscript(command, message)
                    returned += message
                    publish()
                    continue
                }
                var block = linuxSandboxTerminalTranscript(command, "")
                var sawProgress = false
                val result = LinuxSandboxSession.runCommand(
                    app,
                    command,
                    context.cancellation,
                ) { progress ->
                    sawProgress = true
                    block = linuxSandboxTerminalTranscript(
                        command,
                        progress.output,
                        exitCode = progress.exitCode,
                        timedOut = progress.timedOut,
                        truncated = progress.truncated,
                    )
                    publish(block)
                }
                if (!sawProgress) block = linuxSandboxTerminalTranscript(command, result)
                finished += block
                returned += result
                publish()
            }
            for (command in selection.background) {
                if (context.cancellation.isCancelled()) throw CancellationException()
                val normalized = linuxSandboxNormalizeCommand(command)
                if (normalized == null) {
                    val message = if (command.trim().isEmpty()) tr("命令为空") else tr("命令太长")
                    finished += linuxSandboxTerminalTranscript(command, message)
                    returned += message
                    publish()
                    continue
                }
                publish(linuxSandboxTerminalTranscript(command, ""))
                val started = LinuxSandboxSession.startBackground(app, command, context.conversationId)
                val message = if (started.id != null) {
                    tr("已在后台运行，编号 %s").format(started.id)
                } else {
                    started.error ?: tr("无法启动后台命令。")
                }
                finished += linuxSandboxTerminalTranscript(command, message)
                returned += message
                publish()
            }
            if (selection.backgroundIgnored > 0) {
                val note = tr("后台命令已关闭，未启动 %s 条。").format(selection.backgroundIgnored)
                finished += note
                returned += note
                publish()
            }
            if (selection.skipped > 0) {
                val note = tr("已达到单次命令上限，其余 %s 条未运行。").format(selection.skipped)
                finished += note
                returned += note
                publish()
            }
            ToolExecutionResult(linuxSandboxJoinSections(returned))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            context.onUpdate(
                ChatStreamUpdate(
                    toolActive = false,
                    toolName = LinuxShellTool.NAME,
                    toolContent = transcript,
                ),
            )
        }
    }

    private suspend fun executeBackground(
        call: ChatToolCall,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = LinuxBackgroundTool.NAME))
        return try {
            val enabled = context.appContext?.let {
                LinuxSandboxStore.loadBackgroundEnabled(LinuxSandboxLayout.of(it.filesDir).stateFile)
            } ?: backgroundEnabled
            val message = if (!enabled) tr("后台命令已关闭。") else LinuxBackgroundJobs.handle(call.arguments)
            ToolExecutionResult(message)
        } finally {
            context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = LinuxBackgroundTool.NAME))
        }
    }
}
