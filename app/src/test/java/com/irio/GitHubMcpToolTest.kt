package com.irio

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubMcpToolTest {
    private val tool = McpTool("get_me", "Read profile", JSONObject("""{"type":"object","properties":{"user":{"type":"string"}},"required":[]}"""))

    @Test
    fun progressLabelsIdentifyEachGitHubOperation() {
        val names = listOf("get_me", "search_repositories", "get_file_contents", "issue_read",
            "issue_write", "pull_request_read", "create_pull_request", "list_commits", "list_branches",
            "future_remote_tool").map { GitHubMcpToolProvider.PREFIX + it }
        assertEquals(names.size, names.map { MemoryTool.liveLabel(it) }.toSet().size)
        assertEquals(names.size, names.map { MemoryTool.doneLabel(it) }.toSet().size)
        assertTrue(MemoryTool.liveLabel(names.last()).contains("future_remote_tool"))
        assertTrue(MemoryTool.doneLabel(names.last()).contains("future_remote_tool"))
        for (name in names) assertNotEquals(MemoryTool.liveLabel(name), MemoryTool.doneLabel(name))
    }

    @Test
    fun progressUsesGitHubInsideTheActionAndOnlyAddsYiWhenDone() {
        val labels = mapOf(
            "get_me" to "读取 GitHub 个人资料",
            "search_repositories" to "搜索 GitHub 仓库",
            "get_file_contents" to "读取 GitHub 文件内容",
            "create_or_update_file" to "创建或更新 GitHub 文件",
            "get_release_by_tag" to "按标签读取 GitHub 发布",
            "assign_copilot_to_issue" to "分配 GitHub 议题给 Copilot",
            "list_issues" to "读取 GitHub 议题列表",
            "list_pull_requests" to "读取 GitHub 拉取请求列表",
            "ui_get" to "读取 GitHub 界面数据",
            "run_secret_scanning" to "运行 GitHub 密钥扫描",
        )
        for ((remoteName, live) in labels) {
            val name = GitHubMcpToolProvider.PREFIX + remoteName
            assertEquals(live, MemoryTool.liveLabel(name))
            assertEquals("已$live", MemoryTool.doneLabel(name))
        }
        val unknown = GitHubMcpToolProvider.PREFIX + "future_remote_tool"
        assertEquals("调用 GitHub 工具 future_remote_tool", MemoryTool.liveLabel(unknown))
        assertEquals("已调用 GitHub 工具 future_remote_tool", MemoryTool.doneLabel(unknown))
    }

    @Test
    fun discoveredToolsAreNamespacedAndPortableAcrossAllChatProtocols() {
        val registry = ToolRegistry.default().register(GitHubMcpToolProvider(listOf(tool)) { _, _ -> "fixture" })
        for (protocol in ProviderProtocol.entries) {
            val schemas = registry.definitions(protocol)
            val schema = schemas.last()
            val wire = schema.optJSONObject("function") ?: schema
            assertEquals("github_mcp_get_me", wire.getString("name"))
            val parameters = wire.optJSONObject("parameters") ?: wire.optJSONObject("parametersJsonSchema")
                ?: wire.getJSONObject("input_schema")
            assertTrue(parameters.getJSONObject("properties").has("user"))
        }
    }

    @Test
    fun executionMapsBackToRemoteNameAndEmitsActualProgress() = runBlocking {
        var called = ""
        val events = mutableListOf<ChatStreamUpdate>()
        val provider = GitHubMcpToolProvider(listOf(tool)) { name, args ->
            called = name + ":" + args.getString("user")
            "profile-result"
        }
        val result = provider.execute(ChatToolCall("call", "github_mcp_get_me", "{\"user\":\"octocat\"}"),
            ToolExecutionContext(provider = ProviderConfig("test", "Test", "https://example.test", ""), model = "test", onUpdate = { events += it }))
        assertEquals("get_me:octocat", called)
        assertEquals("profile-result", result.text)
        assertEquals(listOf(true, false), events.map { it.toolActive })
        assertTrue(events.last().toolContent.contains("profile-result"))
    }
}
