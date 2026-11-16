package com.irio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandHintTest {
    private fun shell(arguments: String) = ProcessEvent(1, ProcessEventKind.TOOL,
        toolName = LinuxShellTool.NAME, toolArguments = arguments)

    @Test fun queuedCommandsStayHiddenUntilTheCallFinishes() {
        val commands = listOf("printf 'hello  world'", "printf 'a\\nb'", "echo 中文")
        val args = JSONObject().put("commands", JSONArray(commands)).put("background", JSONArray().put("server start"))
        assertEquals("运行中", shell(args.toString()).copy(live = true).withCommandHint("运行中"))
        assertEquals("Ran a command - ${commands.joinToString("; ")}", shell(args.toString()).withCommandHint("Ran a command"))
    }

    @Test fun emptyLegacyAndBackgroundManagementEventsKeepTheirOriginalStatus() {
        for (arguments in listOf("", "{}", "{\"commands\":[]}")) {
            assertEquals("status", shell(arguments).withCommandHint("status"))
        }
        assertEquals("status", shell("{\"commands\":[\"echo ok\"]}")
            .copy(toolName = LinuxBackgroundTool.NAME).withCommandHint("status"))
    }

    @Test fun backgroundCreationUsesItsOwnStatusWithoutMaskingFailures() {
        val event = shell("""{"commands":[],"background":["server start"]}""")
        assertEquals("创建中", event.copy(live = true).withCommandHint("运行中"))
        assertEquals("已创建后台命令", event.copy(toolOutcome = ToolOutcome.SUCCESS).withCommandHint("已运行命令"))
        assertEquals("运行命令失败", event.copy(toolOutcome = ToolOutcome.FAILED).withCommandHint("运行命令失败"))
        assertEquals("运行中已停止", event.copy(toolOutcome = ToolOutcome.STOPPED).withCommandHint("运行中已停止"))
        assertEquals("Creating", translate(AppLanguage.EN, "创建中"))
        assertEquals("Created background commands", translate(AppLanguage.EN, "已创建后台命令"))
    }

    @Test fun currentCommandChangesInPlaceAndClearsWhenExecutionFinishes() {
        val turn = SubagentTurn()
        val arguments = """{"commands":["printf 'hello  world'","echo next"]}"""
        fun update(command: String, output: String) {
            turn.apply(ChatStreamUpdate(toolActive = true, toolName = LinuxShellTool.NAME,
                toolArguments = arguments, toolOutput = output, runningCommand = command))
        }
        update("printf 'hello  world'", "first output")
        val id = turn.process().single().id
        assertEquals("status - printf 'hello  world'", turn.process().single().withCommandHint("status"))
        update("echo next", "first output\nsecond output")
        assertEquals(id, turn.process().single().id)
        assertEquals("status - echo next", turn.process().single().withCommandHint("status"))
        assertEquals(arguments, turn.process().single().toolArguments)
        update("", "full transcript")
        assertEquals("status", turn.process().single().withCommandHint("status"))
        turn.apply(ChatStreamUpdate(toolActive = false, toolName = LinuxShellTool.NAME,
            toolContent = "full transcript", toolOutcome = ToolOutcome.SUCCESS))
        assertEquals("", turn.process().single().runningCommand)
        assertEquals("full transcript", turn.process().single().content)
        assertEquals("done - printf 'hello  world'; echo next", turn.process().single().withCommandHint("done"))
    }
}
