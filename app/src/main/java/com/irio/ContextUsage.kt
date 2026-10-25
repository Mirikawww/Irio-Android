package com.irio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 用户系统提示词和记忆，拼成请求里那一条 system。两边都走这里，圆环拆分才跟实际发送一致。 */
internal fun chatSystemPrompt(context: Context): String? = buildString {
    SystemPromptStore.load(context).trim().takeIf { it.isNotEmpty() }?.let { append(it) }
    MemoryStore.systemPrompt(context)?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (isNotEmpty()) append("\n\n")
        append(it)
    }
}.ifBlank { null }

/**
 * 生图工具要同时满足：对话模型自己不是生图模型，且设置里的生图模型真能画。
 * 否则不注入 generate_image。
 */
internal fun configuredImageGenModel(context: Context, chatModelId: String): String =
    ToolStore.loadImageGenModel(context)
        .takeIf { ModelCatalog.isImageModel(context, it) }
        .takeUnless { ModelCatalog.isImageModel(context, chatModelId) } ?: ""

/** 这一轮会放进请求的工具。GitHub 集成由调用方再注册，它要走挂起的读取。 */
internal fun chatToolRegistry(
    context: Context,
    webSearch: Boolean,
    imageGenModel: String,
    imageSearch: Boolean = webSearch && SearchStore.hasImageApiKey(context),
): ToolRegistry = ToolRegistry(
    buildList {
        if (webSearch) {
            add(WebSearchToolProvider())
            add(WebFetchToolProvider())
            if (imageSearch) add(CommonsImageToolProvider())
        }
        if (imageGenModel.isNotBlank()) add(ImageGenToolProvider())
        add(TimeToolProvider())
        add(LinguaLibreToolProvider())
        add(ReactionToolProvider())
        add(ColoredBubbleToolProvider())
        add(MermaidToolProvider())
        add(HistoryToolProvider())
        if (MemoryStore.loadEnabled(context)) add(MemoryToolProvider())
        if (ToolStore.loadSubagents(context)) add(SubagentToolProvider())
        val sandbox = LinuxSandboxLayout.of(context.filesDir).stateFile
        if (LinuxSandboxPolicy.exposed(
                LinuxSandboxStore.loadEnabled(sandbox),
                LinuxSandboxAccess.hasAllFiles(),
            )
        ) {
            add(
                LinuxShellToolProvider(
                    LinuxSandboxStore.loadCommandsPerCall(sandbox),
                    LinuxSandboxStore.loadBackgroundEnabled(sandbox),
                ),
            )
        }
    },
    smartToolHints = ToolStore.loadSmartToolHints(context),
    preferences = ToolPreferencesStore.load(context),
)

/** 系统提示和工具定义算在一起。定义在请求里是单独的 tools 字段，菜单归到系统提示。 */
internal fun estimateSystemPromptTokens(systemPrompt: String?, toolDefinitions: String): Int {
    val prompt = if (systemPrompt.isNullOrBlank()) 0 else 4 + TokenEstimator.count(systemPrompt)
    val definitions = if (toolDefinitions.isBlank()) 0 else TokenEstimator.count(toolDefinitions)
    return prompt + definitions
}

/**
 * 生图模型不走聊天请求，系统提示和工具都不占上下文。
 * GitHub 集成读失败时仍统计内置工具，避免圆环整个空白。
 */
internal suspend fun loadContextRequestOverhead(
    context: Context,
    protocol: ProviderProtocol,
    modelId: String,
): Int = withContext(Dispatchers.IO) {
    if (modelId.isNotBlank() && ModelCatalog.isImageModel(context, modelId)) return@withContext 0
    val registry = chatToolRegistry(
        context,
        SearchStore.isWebSearchActive(context),
        configuredImageGenModel(context, modelId),
    )
    runCatching {
        GitHubMcpStore.of(context).toolProvider(ChatRequestCancellation())?.let(registry::register)
    }
    runCatching {
        TodoistMcpStore.of(context).toolProvider(ChatRequestCancellation())?.let(registry::register)
    }
    val schemas = registry.schemas(protocol).joinToString("\n") { it.toString() }
    estimateSystemPromptTokens(registry.systemPrompt(chatSystemPrompt(context)), schemas)
}
