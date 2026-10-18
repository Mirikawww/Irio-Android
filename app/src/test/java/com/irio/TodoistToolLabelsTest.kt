package com.irio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TodoistToolLabelsTest {
    // Current hosted Todoist MCP tool names, not legacy singular REST operation names.
    private val labels = linkedMapOf(
        "add-comments" to "添加 Todoist 评论",
        "add-filters" to "创建 Todoist 筛选器",
        "add-labels" to "创建 Todoist 标签",
        "add-projects" to "创建 Todoist 项目",
        "add-reminders" to "添加 Todoist 提醒",
        "add-sections" to "创建 Todoist 分区",
        "add-tasks" to "创建 Todoist 任务",
        "analyze-project-health" to "分析 Todoist 项目健康状况",
        "complete-tasks" to "完成 Todoist 任务",
        "delete-object" to "删除 Todoist 对象",
        "export-project-template" to "导出 Todoist 项目模板",
        "fetch" to "读取 Todoist 任务或项目内容",
        "fetch-object" to "读取 Todoist 对象详情",
        "find-activity" to "查询 Todoist 活动记录",
        "find-comments" to "查询 Todoist 评论",
        "find-completed-tasks" to "查询 Todoist 已完成任务",
        "find-filters" to "查询 Todoist 筛选器",
        "find-labels" to "查询 Todoist 标签",
        "find-project-collaborators" to "查询 Todoist 项目协作者",
        "find-projects" to "搜索 Todoist 项目",
        "find-reminders" to "查询 Todoist 提醒",
        "find-sections" to "搜索 Todoist 分区",
        "find-tasks" to "搜索 Todoist 任务",
        "find-tasks-by-date" to "按日期查询 Todoist 任务",
        "get-overview" to "读取 Todoist 项目概览",
        "get-productivity-stats" to "读取 Todoist 效率统计",
        "get-project-activity-stats" to "读取 Todoist 项目活动统计",
        "get-project-health" to "读取 Todoist 项目健康状况",
        "get-workspace-insights" to "读取 Todoist 工作区概况",
        "import-project-template" to "导入 Todoist 项目模板",
        "list-workspaces" to "读取 Todoist 工作区列表",
        "manage-assignments" to "调整 Todoist 任务分配",
        "project-management" to "归档或恢复 Todoist 项目",
        "project-move" to "移动 Todoist 项目",
        "reorder-objects" to "调整 Todoist 项目或分区顺序",
        "reschedule-tasks" to "调整 Todoist 任务日期",
        "search" to "搜索 Todoist 任务和项目",
        "uncomplete-tasks" to "重新打开 Todoist 任务",
        "update-comments" to "更新 Todoist 评论",
        "update-filters" to "更新 Todoist 筛选器",
        "update-labels" to "更新 Todoist 标签",
        "update-projects" to "更新 Todoist 项目",
        "update-reminders" to "更新 Todoist 提醒",
        "update-sections" to "更新 Todoist 分区",
        "update-tasks" to "更新 Todoist 任务",
        "user-info" to "读取 Todoist 用户资料",
        "view-attachment" to "查看 Todoist 评论附件",
    )

    @Test fun allHostedToolsHaveSpecificActionsAndCompletedLabels() {
        for ((remote, action) in labels) {
            val wire = TodoistMcpToolProvider.PREFIX + remote
            assertEquals("Hosted tool $remote needs a specific action", action, MemoryTool.liveLabel(wire))
            assertEquals("已$action", MemoryTool.doneLabel(wire))
            assertEquals(action, TodoistMcpToolProvider.displayName(remote))
            assertEquals(action, TodoistMcpToolProvider.displayName(remote.replace('-', '_')))
        }
    }

    @Test fun modelToolSettingsUseTheSameLabelsWithoutChangingIdentifiersOrSchemas() {
        val remoteTools = labels.keys.map { McpTool(it, "Remote description for $it", JSONObject("""{"type":"object","properties":{"fixture":{"type":"string"}}}""")) }
        val entries = ToolCatalog.entries(ToolFeatureState(todoistUsable = true), todoistMcpTools = remoteTools)
            .filter { it.name.startsWith(TodoistMcpToolProvider.PREFIX) }
        assertEquals(labels.size, entries.size)
        for ((tool, entry) in remoteTools.zip(entries)) {
            assertEquals(TodoistMcpToolProvider.PREFIX + tool.name, entry.name)
            assertEquals(labels[tool.name], entry.title)
            assertEquals(tool.description, entry.defaultDescription)
            assertNull(entry.blockedReason)
        }
        for (protocol in ProviderProtocol.entries) {
            val definitions = TodoistMcpToolProvider(remoteTools) { _, _ -> "unused" }.definitions(protocol)
            for ((tool, definition) in remoteTools.zip(definitions)) {
                val function = definition.optJSONObject("function") ?: definition
                assertEquals(TodoistMcpToolProvider.PREFIX + tool.name, function.getString("name"))
                assertEquals(tool.description, function.getString("description"))
            }
        }
    }

    @Test fun englishHasOngoingAndCompletedFormsForEveryHostedTool() {
        for (action in labels.values) {
            val live = translate(AppLanguage.EN, action)
            val done = translate(AppLanguage.EN, "已$action")
            assertFalse("Missing English action: $action", live.any { it in '\u4e00'..'\u9fff' })
            assertFalse("Missing English completion: $action", done.any { it in '\u4e00'..'\u9fff' })
            assertNotEquals(live, done)
        }
        assertEquals("Creating Todoist tasks", translate(AppLanguage.EN, "创建 Todoist 任务"))
        assertEquals("Created Todoist tasks", translate(AppLanguage.EN, "已创建 Todoist 任务"))
        assertEquals("Rescheduling Todoist tasks", translate(AppLanguage.EN, "调整 Todoist 任务日期"))
        assertEquals("Rescheduled Todoist tasks", translate(AppLanguage.EN, "已调整 Todoist 任务日期"))
    }

    @Test fun unknownToolsPreserveTheExactRemoteNameLikeGitHub() {
        val unknown = "future-remote_tool"
        assertEquals("调用 Todoist 工具 $unknown", MemoryTool.liveLabel(TodoistMcpToolProvider.PREFIX + unknown))
        assertEquals("已调用 Todoist 工具 $unknown", MemoryTool.doneLabel(TodoistMcpToolProvider.PREFIX + unknown))
    }
}
