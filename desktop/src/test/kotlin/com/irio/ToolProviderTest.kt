package com.irio

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProviderTest {
    private val provider = ProviderConfig(
        id = "test",
        name = "Test",
        endpointUrl = "https://example.test/v1",
        apiKey = "key",
    )

    @Test
    fun registryKeepsProviderAndDefinitionOrder() {
        val registry = ToolRegistry(listOf(
            StubToolProvider("first", "one"),
            StubToolProvider("second", "two"),
        ))

        assertEquals(listOf("first", "second"), registry.providers().map { it.id })
        assertEquals(
            listOf("one", "two"),
            registry.definitions(ProviderProtocol.CHAT_COMPLETIONS)
                .map { it.getJSONObject("function").getString("name") },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateProviderIdsAreRejected() {
        ToolRegistry(listOf(
            StubToolProvider("same", "one"),
            StubToolProvider("same", "two"),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateToolNamesAreRejected() {
        ToolRegistry(listOf(
            StubToolProvider("first", "same"),
            StubToolProvider("second", "same"),
        ))
    }

    @Test
    fun executeRoutesByToolNameAndPreservesContext() = runBlocking {
        val stub = StubToolProvider("stub", "echo")
        val registry = ToolRegistry(listOf(stub))
        val context = ToolExecutionContext(provider = provider, model = "model")

        val result = registry.execute(
            ChatToolCall(id = "call-1", name = "echo", arguments = "{\"value\":1}"),
            context,
        )

        assertEquals("echo:{\"value\":1}", result.text)
        assertEquals("model", stub.lastContext?.model)
        assertEquals("call-1", stub.lastCall?.id)
    }

    @Test
    fun unknownToolsReturnRecoverableResult() = runBlocking {
        val result = ToolRegistry().execute(
            ChatToolCall(id = "call-1", name = "missing", arguments = "{}"),
            ToolExecutionContext(provider = provider, model = "model"),
        )

        assertEquals("未知工具 missing", result.text)
    }

    @Test
    fun defaultRegistryExposesStableBuiltInsAndRuntimeGatesExecution() = runBlocking {
        val registry = ToolRegistry.default()
        val names = registry.definitions(ProviderProtocol.RESPONSES).map { it.optString("name") }
        assertEquals(listOf("web_search", "web_fetch", "generate_image", "get_time"), names)

        val disabledSearch = registry.execute(
            ChatToolCall("search", WebSearchTool.NAME, "{\"query\":\"kotlin\"}"),
            ToolExecutionContext(provider = provider, model = "model"),
        )
        assertTrue(disabledSearch.text.contains("当前不可用"))

        val disabledImage = registry.execute(
            ChatToolCall("image", ImageGenTool.NAME, "{\"prompt\":\"cat\"}"),
            ToolExecutionContext(provider = provider, model = "model"),
        )
        assertTrue(disabledImage.text.contains("当前不可用"))
    }

    @Test
    fun timeProviderClosesLifecycleEventWhenItCompletes() = runBlocking {
        val events = mutableListOf<ChatStreamUpdate>()
        val result = ToolRegistry.default().execute(
            ChatToolCall("time", TimeTool.NAME, "{}"),
            ToolExecutionContext(
                provider = provider,
                model = "model",
                onUpdate = { events += it },
            ),
        )

        assertTrue(result.text.contains("当前时间："))
        assertEquals(listOf(true, false), events.mapNotNull { it.toolActive })
        assertEquals(listOf("get_time", "get_time"), events.map { it.toolName })
        assertFalse(events.any { it.searching == true })
    }

    @Test
    fun memoryRegistryExposesGetAndUpdate() {
        val names = MemoryToolProvider()
            .definitions(ProviderProtocol.RESPONSES)
            .map { it.optString("name") }
        assertEquals(listOf("get_memory", "update_memory"), names)
        assertTrue(MemoryToolProvider().handles(MemoryTool.GET))
        assertTrue(MemoryToolProvider().handles(MemoryTool.UPDATE))
        assertFalse(MemoryToolProvider().handles(TimeTool.NAME))
    }

    private class StubToolProvider(
        override val id: String,
        private val toolName: String,
    ) : ToolProvider {
        var lastCall: ChatToolCall? = null
        var lastContext: ToolExecutionContext? = null

        override fun definitions(protocol: ProviderProtocol): List<JSONObject> = listOf(
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", toolName)
                        .put("description", "test")
                        .put("parameters", JSONObject().put("type", "object")),
                ),
        )

        override fun handles(name: String): Boolean = name == toolName

        override suspend fun execute(
            call: ChatToolCall,
            context: ToolExecutionContext,
        ): ToolExecutionResult {
            lastCall = call
            lastContext = context
            return ToolExecutionResult("$toolName:${call.arguments}")
        }
    }
}
