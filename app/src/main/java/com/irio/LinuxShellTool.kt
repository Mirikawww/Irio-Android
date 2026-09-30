package com.irio

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal object LinuxShellTool {
    const val NAME = "linux_shell"
    const val DESCRIPTION =
        "Run a shell command in the persistent Alpine Linux sandbox. " +
            "The guest home is /root and device shared storage is mounted at /sdcard. " +
            "Packages and files inside the sandbox persist across calls. " +
            "Call with {\"command\":\"shell command\"}."

    fun schemaFor(protocol: ProviderProtocol): JSONObject = functionSchema(protocol, parameters())

    private fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "command",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Shell command to run inside Alpine"),
            ),
        )
        .put("required", JSONArray().put("command"))
        .put("additionalProperties", false)

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

internal class LinuxShellToolProvider : ToolProvider {
    override val id: String = "linux-shell"

    override fun definitions(protocol: ProviderProtocol): List<JSONObject> =
        listOf(LinuxShellTool.schemaFor(protocol))

    override fun handles(name: String): Boolean = name == LinuxShellTool.NAME

    override suspend fun execute(
        call: ChatToolCall,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val command = ToolArguments.value(call.arguments, "command")
        var transcript = linuxSandboxTerminalTranscript(command, "")
        context.onUpdate(
            ChatStreamUpdate(
                toolActive = true,
                toolName = LinuxShellTool.NAME,
                toolOutput = transcript,
            ),
        )
        return try {
            val app = context.appContext
                ?: return ToolExecutionResult(tr("Linux 沙盒当前不可用。")).also {
                    transcript = linuxSandboxTerminalTranscript(command, it.text)
                }
            if (!LinuxSandboxPolicy.exposed(
                    LinuxSandboxStore.loadEnabled(LinuxSandboxLayout.of(app.filesDir).stateFile),
                    LinuxSandboxAccess.hasAllFiles(),
                )
            ) {
                val message = tr("Linux 沙盒已关闭。")
                transcript = linuxSandboxTerminalTranscript(command, message)
                return ToolExecutionResult(message)
            }
            var sawProgress = false
            val result = LinuxSandboxSession.runCommand(
                app,
                command,
                context.cancellation,
            ) { progress ->
                sawProgress = true
                transcript = linuxSandboxTerminalTranscript(
                    command,
                    progress.output,
                    exitCode = progress.exitCode,
                    timedOut = progress.timedOut,
                    truncated = progress.truncated,
                )
                context.onUpdate(ChatStreamUpdate(toolOutput = transcript))
            }
            if (!sawProgress) transcript = linuxSandboxTerminalTranscript(command, result)
            ToolExecutionResult(result)
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
