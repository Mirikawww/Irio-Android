package com.irio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolPreferencesRegistryTest {
    @Test fun childRegistryPreservesPreferencesAndHintsWhileRemovingSubagentTools() = kotlinx.coroutines.runBlocking {
        val preferences = ToolPreferences(setOf(TimeTool.NAME), mapOf(ReactionTool.NAME to "Child reaction"))
        val parent = ToolRegistry(listOf(TimeToolProvider(), ReactionToolProvider(), SubagentToolProvider()),
            smartToolHints = true, preferences = preferences)
        val child = parent.withoutSubagents()
        assertEquals(preferences, child.preferences)
        assertTrue(child.smartToolHints)
        assertFalse(child.providers().any { it.id == SubagentToolProvider.ID })
        for (protocol in ProviderProtocol.entries) {
            val schemas = child.definitions(protocol)
            assertEquals(listOf(ReactionTool.NAME), schemas.map(::name))
            val definition = schemas.single().optJSONObject("function") ?: schemas.single()
            assertEquals("Child reaction", definition.getString("description"))
            assertTrue(schemas.single().toString().contains(SmartToolHints.ARGUMENT))
        }
        val result = child.execute(ChatToolCall("stale", TimeTool.NAME, "{}"),
            ToolExecutionContext(ProviderConfig("fixture", "Fixture", "https://example.test", ""), "model"))
        assertTrue(result.text.contains("disabled", ignoreCase = true) || result.text.contains("禁用"))
        assertTrue(parent.handles(SubagentTools.CREATE))
    }

    @Test fun disabledCallsAreRejectedBeforeProviderOrProgressSideEffects() = kotlinx.coroutines.runBlocking {
        var executions = 0
        val updates = mutableListOf<ChatStreamUpdate>()
        val remote = GitHubMcpToolProvider(listOf(
            McpTool("disabled", "disabled", JSONObject("""{"type":"object","properties":{}}""")),
            McpTool("enabled", "enabled", JSONObject("""{"type":"object","properties":{}}""")),
        )) { name, _ -> executions++; name }
        val registry = ToolRegistry(preferences = ToolPreferences(setOf("github_mcp_disabled", "unregistered_disabled")))
            .register(remote)
        val context = ToolExecutionContext(ProviderConfig("test", "Test", "https://example.test", ""), "test", onUpdate = { updates += it })
        for (name in listOf("github_mcp_disabled", "unregistered_disabled")) {
            val result = registry.execute(ChatToolCall("stale", name, "{}"), context)
            assertTrue(result.text, result.text.contains("disabled", ignoreCase = true) || result.text.contains("禁用"))
            assertTrue(result.text.contains(name))
            assertEquals(0, executions)
            assertTrue(updates.isEmpty())
            assertFalse(registry.handles(name))
            assertNull(registry.providerFor(name))
        }
        assertSame(remote, registry.providerFor("github_mcp_enabled"))
        assertTrue(registry.handles("github_mcp_enabled"))
        assertEquals("enabled", registry.execute(ChatToolCall("fresh", "github_mcp_enabled", "{}"), context).text)
        assertEquals(1, executions)
        assertEquals(listOf(true, false), updates.map { it.toolActive })
    }

    @Test fun whollyDisabledProviderDoesNotAdvertiseCapabilities() {
        val provider = object : ToolProvider {
            override val id = "capability"
            override fun definitions(protocol: ProviderProtocol) = listOf(TimeTool.schemaFor(protocol))
            override fun handles(name: String) = name == TimeTool.NAME
            override fun systemInstructions() = "Time capability is available"
            override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext) = ToolExecutionResult("unused")
        }
        val registry = ToolRegistry(listOf(provider), preferences = ToolPreferences(setOf(TimeTool.NAME)))
        assertEquals("base", registry.systemPrompt("base"))
        assertNull(registry.systemPrompt(null))
        assertTrue(ToolRegistry(listOf(provider)).systemPrompt("base")!!.contains("Time capability is available"))
    }

    @Test fun definitionsFilterIndividualToolsAndCopyOnlyDescriptionBeforeHintsAcrossProtocols() {
        for (protocol in ProviderProtocol.entries) {
            for (hints in listOf(false, true)) {
                val provider = CachedTools()
                val originals = provider.definitions(protocol).map { it.toString() }
                val registry = ToolRegistry(listOf(provider), hints, ToolPreferences(
                    disabledTools = setOf("disabled"),
                    descriptions = mapOf("edited" to "  用户描述\n ", "empty" to ""),
                ))
                val actual = registry.definitions(protocol)
                assertEquals("$protocol hints=$hints", listOf("edited", "empty", "default"), actual.map(::name))
                actual.forEach { schema ->
                    val expected = JSONObject(provider.definitions(protocol).first { name(it) == name(schema) }.toString())
                    val definition = expected.optJSONObject("function") ?: expected
                    when (name(schema)) {
                        "edited" -> definition.put("description", "  用户描述\n ")
                        "empty" -> definition.put("description", "")
                    }
                    val decorated = if (hints) SmartToolHints.decorate(expected) else expected
                    assertEquals("All metadata/parameters must survive: $protocol", canonical(decorated), canonical(schema))
                    // Returned objects must not share nested schema state with providers.
                    (schema.optJSONObject("function") ?: schema).put("description", "mutated by caller")
                }
                assertEquals(originals, provider.definitions(protocol).map { it.toString() })
            }
        }
    }


    private fun canonical(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { canonical(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
        else -> value
    }

    private fun name(schema: JSONObject): String = (schema.optJSONObject("function") ?: schema).getString("name")

    private class CachedTools : ToolProvider {
        override val id = "cached"
        private val schemas = ProviderProtocol.entries.associateWith { protocol ->
            listOf("edited", "disabled", "empty", "default").map { name ->
                SubagentTools.schema(protocol, name, "Original $name", JSONObject("""{"type":"object","properties":{"value":{"type":"string","description":"unchanged"}},"required":["value"],"additionalProperties":false}"""))
                    .put("provider_metadata", JSONObject().put("keep", true))
            }
        }
        override fun definitions(protocol: ProviderProtocol) = schemas.getValue(protocol)
        override fun handles(name: String) = name in setOf("edited", "disabled", "empty", "default")
        override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext) = ToolExecutionResult("executed")
    }
}
