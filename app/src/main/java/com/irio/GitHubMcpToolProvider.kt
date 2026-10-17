package com.irio

import org.json.JSONObject
import kotlinx.coroutines.CancellationException

internal class GitHubMcpToolProvider(
    private val tools: List<McpTool>,
    private val invoke: suspend (String, JSONObject) -> String,
) : ToolProvider {
    override val id = "github-mcp"
    override fun definitions(protocol: ProviderProtocol): List<JSONObject> = tools.map { tool ->
        val name = PREFIX + tool.name
        val parameters = JSONObject(tool.inputSchema.toString())
        when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> JSONObject().put("type", "function").put("function",
                JSONObject().put("name", name).put("description", tool.description).put("parameters", parameters))
            // Remote schemas may have optional fields; do not opt into Responses' strict defaults.
            ProviderProtocol.RESPONSES -> JSONObject().put("type", "function").put("name", name)
                .put("description", tool.description).put("parameters", parameters).put("strict", false)
            ProviderProtocol.ANTHROPIC -> JSONObject().put("name", name).put("description", tool.description)
                .put("input_schema", parameters)
            ProviderProtocol.GEMINI -> GeminiProtocol.declaration(name, tool.description, parameters)
            ProviderProtocol.GEMINI_INTERACTIONS -> GeminiInteractionsProtocol.declaration(name, tool.description, parameters)
        }
    }

    override fun systemInstructions(): String? = systemInstructions(toolNames())

    override fun systemInstructions(enabledTools: Set<String>): String? =
        tools.filter { PREFIX + it.name in enabledTools }.takeIf { it.isNotEmpty() }?.let {
        "GitHub MCP tools are available in this request through Irio's configured GitHub integration. " +
            "Available tool names: " + it.joinToString(", ") { tool -> PREFIX + tool.name } + ". " +
            "These are callable tools, not instructions for the user to run. For relevant GitHub tasks, " +
            "use the provided tool definitions and call the appropriate tool before claiming you cannot " +
            "access GitHub or have no MCP tools. Irio handles authentication; do not ask the user to " +
            "provide a token in chat. Only use the tools actually listed in this request, and report " +
            "any permission or connection failure based on the tool result."
    }

    override fun handles(name: String): Boolean = tools.any { PREFIX + it.name == name }

    override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
        if (context.cancellation.isCancelled()) return ToolExecutionResult(tr("GitHub 工具已停止"))
        require(handles(call.name)) { "Unknown GitHub MCP tool" }
        val arguments = runCatching { JSONObject(call.arguments) }.getOrNull()
            ?: return ToolExecutionResult(tr("GitHub 工具参数无效"))
        context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = call.name))
        var result = ""
        try {
            result = invoke(call.name.removePrefix(PREFIX), arguments)
            return ToolExecutionResult(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not relay arbitrary transport/parser errors which might contain credentials.
            result = if (context.cancellation.isCancelled()) tr("GitHub 工具已停止")
                else tr("GitHub 工具执行失败，请检查登录和仓库权限")
            return ToolExecutionResult(result)
        } finally {
            context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = call.name, toolContent = result))
        }
    }

    companion object {
        const val PREFIX = "github_mcp_"

        fun catalogTools(): List<McpTool> = operations.keys.map { name ->
            McpTool(
                name = name,
                description = tr("配置 GitHub MCP 后可用"),
                inputSchema = JSONObject().put("type", "object").put("properties", JSONObject()),
            )
        }

        fun progressLabel(name: String, completed: Boolean): String {
            val remoteName = name.removePrefix(PREFIX)
            val operation = operations[remoteName]
                ?: return tr(if (completed) "已调用 GitHub 工具 %s" else "调用 GitHub 工具 %s").format(remoteName)
            return tr(if (completed) "已$operation" else operation)
        }

        private val operations = mapOf(
            "get_me" to "读取 GitHub 个人资料",
            "get_team_members" to "读取 GitHub 团队成员",
            "get_teams" to "读取 GitHub 团队列表",
            "search_users" to "搜索 GitHub 用户",
            "search_orgs" to "搜索 GitHub 组织",
            "search_repositories" to "搜索 GitHub 仓库",
            "create_repository" to "创建 GitHub 仓库",
            "delete_repository" to "删除 GitHub 仓库",
            "fork_repository" to "派生 GitHub 仓库",
            "get_file_contents" to "读取 GitHub 文件内容",
            "get_repository_tree" to "读取 GitHub 目录树",
            "create_or_update_file" to "创建或更新 GitHub 文件",
            "delete_file" to "删除 GitHub 文件",
            "push_files" to "推送 GitHub 文件",
            "search_code" to "搜索 GitHub 代码",
            "search_commits" to "搜索 GitHub 提交",
            "get_commit" to "读取 GitHub 提交详情",
            "list_commits" to "读取 GitHub 提交列表",
            "list_branches" to "读取 GitHub 分支列表",
            "create_branch" to "创建 GitHub 分支",
            "list_tags" to "读取 GitHub 标签列表",
            "get_tag" to "读取 GitHub 标签详情",
            "list_releases" to "读取 GitHub 发布列表",
            "get_latest_release" to "读取 GitHub 最新发布",
            "get_release_by_tag" to "按标签读取 GitHub 发布",
            "list_repository_collaborators" to "读取 GitHub 仓库协作者",
            "get_label" to "读取 GitHub 议题标签",
            "list_label" to "读取 GitHub 议题标签列表",
            "label_write" to "修改 GitHub 议题标签",
            "issue_read" to "读取 GitHub 议题详情",
            "issue_write" to "创建或更新 GitHub 议题",
            "list_issues" to "读取 GitHub 议题列表",
            "search_issues" to "搜索 GitHub 议题",
            "list_issue_fields" to "读取 GitHub 议题字段",
            "list_issue_types" to "读取 GitHub 议题类型",
            "sub_issue_write" to "修改 GitHub 子议题",
            "add_issue_comment" to "添加 GitHub 议题评论",
            "update_issue_comment" to "更新 GitHub 议题评论",
            "pull_request_read" to "读取 GitHub 拉取请求详情",
            "create_pull_request" to "创建 GitHub 拉取请求",
            "list_pull_requests" to "读取 GitHub 拉取请求列表",
            "search_pull_requests" to "搜索 GitHub 拉取请求",
            "merge_pull_request" to "合并 GitHub 拉取请求",
            "update_pull_request" to "更新 GitHub 拉取请求",
            "update_pull_request_branch" to "更新 GitHub 拉取请求分支",
            "pull_request_review_write" to "提交或修改 GitHub 代码审查",
            "add_comment_to_pending_review" to "添加 GitHub 待提交审查评论",
            "add_reply_to_pull_request_comment" to "回复 GitHub 拉取请求评论",
            "request_copilot_review" to "请求 GitHub Copilot 审查",
            "assign_copilot_to_issue" to "分配 GitHub 议题给 Copilot",
            "ui_get" to "读取 GitHub 界面数据",
            "run_secret_scanning" to "运行 GitHub 密钥扫描",
            "actions_get" to "读取 GitHub Actions 详情",
            "actions_list" to "读取 GitHub Actions 列表",
            "actions_run_trigger" to "触发 GitHub Actions 工作流",
            "get_job_logs" to "读取 GitHub 工作流日志",
        )
    }
}
