package com.irio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ToolBlockedReason {
    WEB_SEARCH, SEARCH_KEY, IMAGE_SEARCH_KEY, IMAGE_MODEL, MEMORY, SUBAGENTS, LINUX, STORAGE, LINUX_BACKGROUND, GITHUB, TODOIST;

    fun message(): String = tr(when (this) {
        WEB_SEARCH -> "请先在对话中开启「网络搜索」"
        SEARCH_KEY -> "请先在「搜索」中配置搜索提供商的 API 密钥"
        IMAGE_SEARCH_KEY -> "请先在「搜索」中配置 SerpAPI 的 API 密钥"
        IMAGE_MODEL -> "请先在「模型」中配置图片生成模型"
        MEMORY -> "请先在「记忆」中开启记忆"
        SUBAGENTS -> "请先在「模型」中开启「子智能体」"
        LINUX -> "请先在「Linux 沙盒」中开启「启用 Linux 沙盒」"
        STORAGE -> "请先在「Linux 沙盒」中授予所有文件访问权限"
        LINUX_BACKGROUND -> "请先在「Linux 沙盒」中开启「后台命令」"
        GITHUB -> "请先在「MCP」中登录 GitHub 并开启「在对话中启用」"
        TODOIST -> "请先在「MCP」中连接 Todoist 并开启「在对话中启用」"
    })
}

internal data class ToolFeatureState(
    val searchEnabled: Boolean = false,
    val searchConfigured: Boolean = false,
    val imageSearchConfigured: Boolean = false,
    val imageConfigured: Boolean = false,
    val memoryEnabled: Boolean = false,
    val subagentsEnabled: Boolean = false,
    val linuxEnabled: Boolean = false,
    val storageAllowed: Boolean = false,
    val backgroundEnabled: Boolean = false,
    val githubUsable: Boolean = false,
    val todoistUsable: Boolean = false,
)

internal data class ToolSettingsEntry(
    val name: String,
    val title: String,
    val defaultDescription: String,
    val blockedReason: ToolBlockedReason? = null,
)

internal object ToolCatalog {
    /** Local metadata only: opening settings never discovers tools over the network. */
    suspend fun load(context: Context): List<ToolSettingsEntry> = withContext(Dispatchers.IO) {
        val sandbox = LinuxSandboxLayout.of(context.filesDir).stateFile
        val github = GitHubMcpStore.of(context).load()
        val todoist = TodoistMcpStore.of(context).load()
        val features = ToolFeatureState(
            searchEnabled = SearchStore.loadWebSearchEnabled(context),
            searchConfigured = SearchStore.hasApiKey(context),
            imageSearchConfigured = SearchStore.hasImageApiKey(context),
            imageConfigured = ToolStore.loadImageGenModel(context).let { it.isNotBlank() && ModelCatalog.isImageModel(context, it) },
            memoryEnabled = MemoryStore.loadEnabled(context),
            subagentsEnabled = ToolStore.loadSubagents(context),
            linuxEnabled = LinuxSandboxStore.loadEnabled(sandbox),
            storageAllowed = LinuxSandboxAccess.hasAllFiles(),
            backgroundEnabled = LinuxSandboxStore.loadBackgroundEnabled(sandbox),
            githubUsable = github?.usable() == true,
            todoistUsable = todoist?.usable() == true,
        )
        entries(features, LinuxSandboxStore.loadCommandsPerCall(sandbox), github?.tools.orEmpty(), todoist?.tools.orEmpty())
    }

    fun entries(
        features: ToolFeatureState,
        commandsPerCall: Int = LinuxSandboxStore.COMMANDS_UNLIMITED,
        mcpTools: List<McpTool> = emptyList(),
        todoistMcpTools: List<McpTool> = emptyList(),
    ): List<ToolSettingsEntry> {
        val providers = ToolRegistry.default().providers() + listOf(
            MemoryToolProvider(),
            SubagentToolProvider(),
            LinuxShellToolProvider(commandsPerCall, features.backgroundEnabled),
        )
        val schemas = providers.flatMap { it.definitions(ProviderProtocol.CHAT_COMPLETIONS) } +
            // Keep the background tool visible even when its feature is off.
            if (features.backgroundEnabled) emptyList() else listOf(LinuxBackgroundTool.schemaFor(ProviderProtocol.CHAT_COMPLETIONS))
        val visibleMcpTools = mcpTools.ifEmpty { GitHubMcpToolProvider.catalogTools() }
        return schemas.map { schema ->
            val function = schema.getJSONObject("function")
            val name = function.getString("name")
            ToolSettingsEntry(name, title(name), function.getString("description"), blockedReason(name, features))
        } + visibleMcpTools.map { tool ->
            val name = GitHubMcpToolProvider.PREFIX + tool.name
            ToolSettingsEntry(
                name,
                GitHubMcpToolProvider.progressLabel(name, completed = false),
                tool.description,
                if (features.githubUsable) null else ToolBlockedReason.GITHUB,
            )
        } + todoistMcpTools.map { tool ->
            val name = TodoistMcpToolProvider.PREFIX + tool.name
            ToolSettingsEntry(
                name,
                TodoistMcpToolProvider.displayName(tool.name),
                tool.description,
                if (features.todoistUsable) null else ToolBlockedReason.TODOIST,
            )
        }
    }

    private fun title(name: String): String = tr(when (name) {
        WebSearchTool.NAME -> "网络搜索"
        WebFetchTool.NAME -> "读取网页"
        CommonsImageTools.SEARCH -> "搜索图片"
        CommonsImageTools.SHOW -> "展示图片"
        CommonsImageTools.VIEW -> "查看图片"
        ImageGenTool.NAME -> "生成图片"
        TimeTool.NAME -> "获取时间"
        LinguaLibreTool.NAME -> "检索发音"
        HistoryTool.NAME -> "查询历史会话"
        ReactionTool.NAME -> "表情回应"
        ColoredBubbleTool.NAME -> "发送彩色气泡"
        MermaidTool.NAME -> "渲染 Mermaid 图表"
        MemoryTool.GET -> "读取记忆"
        MemoryTool.UPDATE -> "写入记忆"
        SubagentTools.CREATE -> "创建子智能体"
        SubagentTools.MESSAGE -> "联系子智能体"
        SubagentTools.STOP -> "停止子智能体"
        SubagentTools.INSPECT -> "查看子智能体"
        LinuxShellTool.NAME -> "运行 Linux 命令"
        LinuxBackgroundTool.NAME -> "管理后台命令"
        else -> name
    })

    private fun blockedReason(name: String, features: ToolFeatureState): ToolBlockedReason? = when (name) {
        WebSearchTool.NAME, WebFetchTool.NAME -> when {
            !features.searchEnabled -> ToolBlockedReason.WEB_SEARCH
            !features.searchConfigured -> ToolBlockedReason.SEARCH_KEY
            else -> null
        }
        CommonsImageTools.SEARCH, CommonsImageTools.SHOW, CommonsImageTools.VIEW -> when {
            !features.searchEnabled -> ToolBlockedReason.WEB_SEARCH
            !features.imageSearchConfigured -> ToolBlockedReason.IMAGE_SEARCH_KEY
            else -> null
        }
        ImageGenTool.NAME -> if (features.imageConfigured) null else ToolBlockedReason.IMAGE_MODEL
        MemoryTool.GET, MemoryTool.UPDATE -> if (features.memoryEnabled) null else ToolBlockedReason.MEMORY
        in SubagentTools.NAMES -> if (features.subagentsEnabled) null else ToolBlockedReason.SUBAGENTS
        LinuxShellTool.NAME, LinuxBackgroundTool.NAME -> when {
            !features.linuxEnabled -> ToolBlockedReason.LINUX
            !features.storageAllowed -> ToolBlockedReason.STORAGE
            name == LinuxBackgroundTool.NAME && !features.backgroundEnabled -> ToolBlockedReason.LINUX_BACKGROUND
            else -> null
        }
        else -> null
    }
}
