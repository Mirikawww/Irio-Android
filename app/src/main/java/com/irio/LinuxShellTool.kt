package com.irio

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal object LinuxShellTool {
    const val NAME = "linux_shell"

    fun descriptionFor(commandsPerCall: Int): String {
        val base = "Run one or more shell commands, in order, in one call inside the persistent Alpine Linux sandbox. " +
            "The guest home is /root and device shared storage is mounted at /sdcard. " +
            "Packages and files inside the sandbox persist across calls. " +
            "Pass {\"commands\":[\"first command\",\"second command\"]}."
        val limit = linuxSandboxClampCommandsPerCall(commandsPerCall)
        if (limit == LinuxSandboxStore.COMMANDS_UNLIMITED) return base
        return "$base At most $limit commands run per call."
    }

    fun schemaFor(
        protocol: ProviderProtocol,
        commandsPerCall: Int = LinuxSandboxStore.COMMANDS_UNLIMITED,
    ): JSONObject = functionSchema(protocol, parameters(), descriptionFor(commandsPerCall))

    private fun parameters(): JSONObject {
        val commands = JSONObject()
            .put("type", "array")
            .put("description", "Shell commands to run in order inside Alpine")
            .put("items", JSONObject().put("type", "string"))
        return JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("commands", commands))
            .put("required", JSONArray().put("commands"))
            .put("additionalProperties", false)
    }

    private fun functionSchema(
        protocol: ProviderProtocol,
        parameters: JSONObject,
        description: String,
    ): JSONObject = when (protocol) {
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
) : ToolProvider {
    override val id: String = "linux-shell"

    override fun definitions(protocol: ProviderProtocol): List<JSONObject> =
        listOf(LinuxShellTool.schemaFor(protocol, commandsPerCall))

    override fun handles(name: String): Boolean = name == LinuxShellTool.NAME

    override suspend fun execute(
        call: ChatToolCall,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val limit = context.appContext?.let {
            LinuxSandboxStore.loadCommandsPerCall(LinuxSandboxLayout.of(it.filesDir).stateFile)
        } ?: commandsPerCall
        val selection = linuxSandboxSelectCommands(call.arguments, limit)
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
        publish(selection.commands.firstOrNull()?.let { linuxSandboxTerminalTranscript(it, "") })
        return try {
            fun refuse(message: String): ToolExecutionResult {
                transcript = if (selection.commands.isEmpty()) {
                    message
                } else {
                    linuxSandboxJoinSections(
                        selection.commands.map { linuxSandboxTerminalTranscript(it, message) },
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
            if (selection.commands.isEmpty()) {
                val message = tr("命令为空")
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
}
