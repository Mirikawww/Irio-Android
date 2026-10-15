package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class MergedToolCallsTest {
    @Test
    fun summaryUsesOneLineCountsForSearchCommandsAndTime() {
        val events = listOf(
            ProcessEvent(1, ProcessEventKind.SEARCH, pages = 2),
            ProcessEvent(2, ProcessEventKind.TOOL, toolName = LinuxShellTool.NAME, content = "$ echo one\noutput\n$ echo two\noutput"),
            ProcessEvent(3, ProcessEventKind.TOOL, toolName = TimeTool.NAME),
        )

        assertEquals("搜索了 2 个网页，运行了 2 个命令，查看了 1 次时间", mergedToolSummary(events, AppLanguage.ZH_CN))
    }

    @Test
    fun emptySearchStillAppearsWhenSearchWasAttempted() {
        val events = listOf(ProcessEvent(1, ProcessEventKind.SEARCH, pages = 0))

        assertEquals("搜索了 0 个网页", mergedToolSummary(events, AppLanguage.ZH_CN))
    }
}
