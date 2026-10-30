package com.irio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolCatalogTest {
    private val available = ToolFeatureState(
        searchEnabled = true,
        searchConfigured = true,
        imageSearchConfigured = true,
        imageConfigured = true,
        memoryEnabled = true,
        subagentsEnabled = true,
        linuxEnabled = true,
        storageAllowed = true,
        backgroundEnabled = true,
        githubUsable = true,
    )

    @Test
    fun catalogContainsEveryBuiltInEvenWhenAllFeaturesAreOff() {
        val expected = (ToolRegistry.default().providers() + listOf(
            MemoryToolProvider(), SubagentToolProvider(), LinuxShellToolProvider(backgroundEnabled = true),
        )).flatMap { it.toolNames() }.toSet() + ToolGuideSearchTool.NAME +
            GitHubMcpToolProvider.catalogTools().map { GitHubMcpToolProvider.PREFIX + it.name }
        val entries = ToolCatalog.entries(ToolFeatureState())
        assertEquals("The settings catalog must include all built-in tools, even unavailable ones", expected, entries.map { it.name }.toSet())
        assertEquals("A tool must appear exactly once", entries.size, entries.map { it.name }.toSet().size)
        assertTrue(entries.all { it.title.isNotBlank() && it.defaultDescription.isNotBlank() })
        val guides = entries.single { it.name == ToolGuideSearchTool.NAME }
        assertFalse("The local guide tool is not user-disableable", guides.canDisable)
        assertNull(guides.blockedReason)
    }

    @Test
    fun dependenciesExplainTheFirstMissingFeatureWithoutHidingTheRow() {
        fun blocked(name: String, state: ToolFeatureState) = ToolCatalog.entries(state).single { it.name == name }.blockedReason
        assertEquals(ToolBlockedReason.WEB_SEARCH, blocked(WebSearchTool.NAME, ToolFeatureState()))
        assertEquals(ToolBlockedReason.SEARCH_KEY, blocked(WebFetchTool.NAME, available.copy(searchConfigured = false)))
        assertEquals(ToolBlockedReason.WEB_SEARCH, blocked(CommonsImageTools.SEARCH, ToolFeatureState()))
        assertEquals(ToolBlockedReason.WEB_SEARCH, blocked(CommonsImageTools.VIEW, available.copy(searchEnabled = false)))
        assertEquals(ToolBlockedReason.IMAGE_SEARCH_KEY, blocked(CommonsImageTools.SHOW, available.copy(imageSearchConfigured = false)))
        assertEquals(ToolBlockedReason.IMAGE_MODEL, blocked(ImageGenTool.NAME, ToolFeatureState()))
        assertEquals(ToolBlockedReason.MEMORY, blocked(MemoryTool.UPDATE, ToolFeatureState()))
        for (name in SubagentTools.NAMES) assertEquals(ToolBlockedReason.SUBAGENTS, blocked(name, ToolFeatureState()))
        assertEquals(ToolBlockedReason.LINUX, blocked(LinuxBackgroundTool.NAME, ToolFeatureState()))
        assertEquals(ToolBlockedReason.STORAGE, blocked(LinuxShellTool.NAME, available.copy(storageAllowed = false)))
        assertEquals(ToolBlockedReason.LINUX_BACKGROUND, blocked(LinuxBackgroundTool.NAME, available.copy(backgroundEnabled = false)))
        assertNull(blocked(TimeTool.NAME, ToolFeatureState()))
        assertNull(blocked(ReactionTool.NAME, ToolFeatureState()))
        assertNull(blocked(ColoredBubbleTool.NAME, ToolFeatureState()))
        assertTrue(ToolCatalog.entries(available).all { it.blockedReason == null })
    }

    @Test
    fun descriptionsComeFromTheRealSchemasIncludingDynamicLinuxSettings() {
        val entries = ToolCatalog.entries(available, commandsPerCall = 3)
        assertEquals(WebSearchTool.DESCRIPTION, entries.single { it.name == WebSearchTool.NAME }.defaultDescription)
        assertEquals(LinuxShellTool.descriptionFor(3, true), entries.single { it.name == LinuxShellTool.NAME }.defaultDescription)
        val off = ToolCatalog.entries(available.copy(backgroundEnabled = false))
        assertEquals(LinuxShellTool.descriptionFor(0, false), off.single { it.name == LinuxShellTool.NAME }.defaultDescription)
    }

    @Test
    fun cachedMcpToolsStayListedWhenIntegrationIsDisabled() {
        val remote = McpTool("get_me", "Read profile from MCP", JSONObject().put("type", "object"))
        val entry = ToolCatalog.entries(available.copy(githubUsable = false), mcpTools = listOf(remote))
            .single { it.name == GitHubMcpToolProvider.PREFIX + remote.name }
        assertEquals(remote.description, entry.defaultDescription)
        assertEquals(ToolBlockedReason.GITHUB, entry.blockedReason)
        assertTrue(entry.title.isNotBlank())
        val enabled = ToolCatalog.entries(available, mcpTools = listOf(remote)).single { it.name == entry.name }
        assertNull(enabled.blockedReason)
    }

    @Test
    fun knownMcpToolsStayListedBeforeIntegrationIsConfigured() {
        val entries = ToolCatalog.entries(available.copy(githubUsable = false))
        val known = GitHubMcpToolProvider.catalogTools()
        assertTrue(known.isNotEmpty())
        assertTrue(known.all { tool ->
            entries.any {
                it.name == GitHubMcpToolProvider.PREFIX + tool.name &&
                    it.blockedReason == ToolBlockedReason.GITHUB
            }
        })
    }

    @Test
    fun discoveredTodoistToolsRemainVisibleAndExplainWhenIntegrationIsUnavailable() {
        val remote = McpTool("get_tasks", "List my Todoist tasks", JSONObject("""{"type":"object","properties":{}}"""))
        val entry = ToolCatalog.entries(ToolFeatureState(), todoistMcpTools = listOf(remote))
            .single { it.name == TodoistMcpToolProvider.PREFIX + "get_tasks" }
        assertEquals("读取 Todoist 任务列表", entry.title)
        assertEquals("List my Todoist tasks", entry.defaultDescription)
        assertEquals(ToolBlockedReason.TODOIST, entry.blockedReason)
        val enabled = ToolCatalog.entries(available.copy(todoistUsable = true), todoistMcpTools = listOf(remote))
            .single { it.name == entry.name }
        assertNull(enabled.blockedReason)
    }
}
