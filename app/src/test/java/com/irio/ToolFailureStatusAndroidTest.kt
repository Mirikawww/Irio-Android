package com.irio

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolFailureStatusAndroidTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context
    private val provider = ProviderConfig("fixture", "Fixture", "https://example.test", "fixture")

    @Test fun disabledMemoryAndEmptyWritesFailWhileNormalReadAndWriteSucceed() = runBlocking {
        val registry = ToolRegistry(listOf(MemoryToolProvider()))
        val turn = SubagentTurn()
        val context = ToolExecutionContext(provider, "model", appContext = app, onUpdate = { turn.apply(it) })
        MemoryStore.saveEnabled(app, false)
        assertEquals(ToolOutcome.FAILED, registry.execute(ChatToolCall("1", MemoryTool.GET, "{}"), context).outcome)
        assertEquals(ToolOutcome.FAILED, registry.execute(ChatToolCall("2", MemoryTool.UPDATE, """{"content":"fact"}"""), context).outcome)
        MemoryStore.saveEnabled(app, true)
        assertEquals(ToolOutcome.FAILED, registry.execute(ChatToolCall("3", MemoryTool.UPDATE, "{}"), context).outcome)
        assertEquals(ToolOutcome.SUCCESS, registry.execute(ChatToolCall("4", MemoryTool.UPDATE, """{"content":"fact"}"""), context).outcome)
        assertEquals(ToolOutcome.SUCCESS, registry.execute(ChatToolCall("5", MemoryTool.GET, "{}"), context).outcome)
        assertEquals("fact", MemoryStore.loadContent(app))
        assertEquals(listOf(ToolOutcome.FAILED, ToolOutcome.FAILED, ToolOutcome.FAILED, ToolOutcome.SUCCESS, ToolOutcome.SUCCESS),
            turn.process().map { it.toolOutcome })
        assertTrue(turn.process().all { !it.live && it.content.isNotBlank() })
    }

    @Test fun missingSubagentsAndEmptyCreateTasksAreVisibleFailures() = runBlocking {
        val registry = ToolRegistry(listOf(SubagentToolProvider()))
        val turn = SubagentTurn()
        val context = ToolExecutionContext(provider, "model", conversationId = "missing-fixture",
            onUpdate = { turn.apply(it) })
        for (name in SubagentTools.NAMES) {
            val result = registry.execute(ChatToolCall(name, name,
                """{"name":"not-created","message":"hello","task":""}"""), context)
            assertEquals(name, ToolOutcome.FAILED, result.outcome)
        }
        assertEquals(4, turn.process().size)
        assertTrue(turn.process().all { it.toolOutcome == ToolOutcome.FAILED && it.content.isNotBlank() && !it.live })
    }
}
