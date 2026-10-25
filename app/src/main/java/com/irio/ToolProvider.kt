package com.irio

import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * Immutable request-scoped values available to a tool provider.
 *
 * The context is deliberately assembled before the first model request and then reused for all
 * tool rounds. Providers may inspect the conversation history, but they do not own the model
 * loop or protocol-specific message serialization.
 */
data class ToolExecutionContext(
    val provider: ProviderConfig,
    val model: String,
    val history: List<ChatRequestMessage> = emptyList(),
    val webSearch: Boolean = false,
    val searchProvider: SearchProvider = SearchProvider.TAVILY,
    val searchApiKey: String = "",
    val searchMaxResults: Int = searchProvider.maxResultsDefault,
    val imageSearchApiKey: String = "",
    val imageSearchMaxResults: Int = SearchStore.IMAGE_RESULTS_DEFAULT,
    val imageGenModel: String = "",
    val cancellation: ChatRequestCancellation = ChatRequestCancellation(),
    val onUpdate: suspend (ChatStreamUpdate) -> Unit = {},
    val appContext: android.content.Context? = null,
    val conversationId: String = "",
    /** 静默更新发起这次请求的对话；返回 false 表示目标已经不可用。 */
    val onReaction: suspend (Long, String) -> Boolean = { _, _ -> false },
    val registry: ToolRegistry? = null,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val fastMode: Boolean = false,
)

/** Provider-neutral result that can be appended to the next model turn. */
data class ToolExecutionResult(
    val text: String,
    val searchPages: Int = 0,
    /** 本轮工具执行完就结束请求，不再开下一轮模型思考。 */
    val endTurn: Boolean = false,
    /** Explicit visual inspection only; display-only images must never populate this. */
    val modelAttachments: List<ChatAttachment> = emptyList(),
    val outcome: ToolOutcome = ToolOutcome.SUCCESS,
)

/**
 * A registered source of one or more model-visible tools.
 *
 * Definitions and execution intentionally live together. This prevents a tool from being
 * advertised by one code path while its dispatcher is forgotten in another `when` expression.
 */
interface ToolProvider {
    /** Stable provider key, useful for diagnostics and future settings. */
    val id: String

    /** Model-facing definitions for the selected wire protocol. */
    fun definitions(protocol: ProviderProtocol): List<JSONObject>

    /** Request-scoped instructions describing capabilities supplied by this provider. */
    fun systemInstructions(): String? = null

    /** Names actually exposed in this request, for providers that enumerate their capabilities. */
    fun systemInstructions(enabledTools: Set<String>): String? = systemInstructions()

    /** Stable names owned by this provider; derived from definitions by default. */
    fun toolNames(): Set<String> = ProviderProtocol.entries
        .asSequence()
        .flatMap { definitions(it).asSequence() }
        .mapNotNull { schemaToolName(it) }
        .toSet()

    /** Whether this provider owns [name]. */
    fun handles(name: String): Boolean

    /** Execute a completed model tool call. */
    suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult
}

/**
 * Ordered tool registry used by the chat loop.
 *
 * Registration order is the wire order of tool definitions. A name may only be owned by one
 * provider, which keeps the model-facing surface deterministic and makes a missing registration
 * fail at startup rather than being selected nondeterministically at execution time.
 */
class ToolRegistry(
    providers: Iterable<ToolProvider> = emptyList(),
    val smartToolHints: Boolean = false,
    val preferences: ToolPreferences = ToolPreferences(),
) {
    private val registered = LinkedHashMap<String, ToolProvider>()
    private val owners = LinkedHashMap<String, String>()

    init {
        providers.forEach(::register)
    }

    fun register(provider: ToolProvider): ToolRegistry {
        require(provider.id.isNotBlank()) { "Tool provider id must not be blank" }
        require(provider.id !in registered) { "Tool provider already registered: ${provider.id}" }
        val toolNames = provider.toolNames()
        toolNames.forEach { name ->
            require(name.isNotBlank()) { "Tool provider ${provider.id} declares a blank tool name" }
            require(name !in owners) {
                "Tool name already registered by ${owners[name]}: $name"
            }
        }
        registered[provider.id] = provider
        toolNames.forEach { owners[it] = provider.id }
        return this
    }

    fun providers(): List<ToolProvider> = registered.values.toList()

    /** Stable, protocol-specific definitions assembled from the registered providers. */
    fun definitions(protocol: ProviderProtocol): List<JSONObject> {
        val names = HashSet<String>()
        return registered.values.flatMap { it.definitions(protocol) }.onEach { schema ->
            val name = schemaToolName(schema)
                ?: error("Tool definition from registry has no name")
            require(names.add(name)) { "Tool name is duplicated in ${protocol.name}: $name" }
        }.filter { schema -> preferences.isEnabled(schemaToolName(schema)!!) }.map { schema ->
            val name = schemaToolName(schema)
            // A provider may reuse schemas; neither user descriptions nor hints may mutate them.
            val copy = JSONObject(schema.toString())
            preferences.descriptions[name]?.let { description ->
                (copy.optJSONObject("function") ?: copy).put("description", description)
            }
            if (smartToolHints && name !in SubagentTools.QUIET && name !in setOf(CommonsImageTools.SHOW, LinguaLibreTool.NAME, MermaidTool.NAME)) SmartToolHints.decorate(copy) else copy
        }
    }

    /** Derive capability instructions from the same providers that supply wire definitions. */
    fun systemPrompt(base: String?): String? = buildList {
        base?.takeIf { it.isNotBlank() }?.let(::add)
        registered.values.mapNotNull { provider ->
            val enabled = provider.toolNames().filterTo(linkedSetOf(), preferences::isEnabled)
            if (enabled.isEmpty()) null else provider.systemInstructions(enabled)?.takeIf(String::isNotBlank)
        }.forEach(::add)
        if (smartToolHints) add(SmartToolHints.INSTRUCTIONS)
    }.joinToString("\n\n").takeIf { it.isNotBlank() }

    /** Alias that makes call sites read naturally when building a request body. */
    fun schemas(protocol: ProviderProtocol): List<JSONObject> = definitions(protocol)

    fun providerFor(name: String): ToolProvider? =
        if (preferences.isEnabled(name)) registered.values.firstOrNull { it.handles(name) } else null

    fun handles(name: String): Boolean = providerFor(name) != null

    suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val displayText = if (smartToolHints) SmartToolHints.displayText(call.arguments) else ""
        val search = call.name == WebSearchTool.NAME
        val query = if (search) runCatching { JSONObject(call.arguments).opt("query") as? String }.getOrNull().orEmpty() else ""
        var started = false
        var terminal: ChatStreamUpdate? = null
        var latestOutput = ""
        suspend fun publish(update: ChatStreamUpdate) {
            context.onUpdate(update.copy(displayText = displayText))
        }
        suspend fun start() {
            if (started) return
            started = true
            publish(if (search) ChatStreamUpdate(searching = true, searchQuery = query)
            else ChatStreamUpdate(toolActive = true, toolName = call.name,
                webDomain = if (call.name == WebFetchTool.NAME) webPageDomain(ToolArguments.value(call.arguments, "url")) else ""))
        }
        suspend fun finish(result: ToolExecutionResult) {
            // Successful visual/metadata tools stay silent. Their failures are never silent.
            if (!started && result.outcome == ToolOutcome.SUCCESS && terminal == null) return
            start()
            val update = terminal ?: if (search) ChatStreamUpdate(searching = false) else
                ChatStreamUpdate(toolActive = false, toolName = call.name)
            val providerContent = if (search) update.searchContent else update.toolContent.ifBlank { latestOutput }
            val content = if (result.outcome == ToolOutcome.SUCCESS) {
                providerContent.ifBlank { result.text }
            } else if (call.name == LinuxShellTool.NAME && providerContent.isNotBlank() && providerContent != result.text) {
                "$providerContent\n\n${result.text}"
            } else result.text
            publish(if (search) update.copy(searching = false, searchContent = content,
                searchQuery = query, toolOutcome = result.outcome)
            else update.copy(toolActive = false, toolName = call.name, toolContent = content, toolOutcome = result.outcome))
        }
        val executionContext = context.copy(onUpdate = { update ->
            update.toolOutput?.let { latestOutput = it }
            when {
                update.searching == false || update.toolActive == false -> terminal = update
                update.searching == true || update.toolActive == true -> {
                    // Repeated shell output snapshots update one event; ordinary starts occur once.
                    if (!started || update.toolOutput != null) publish(update)
                    started = true
                }
                else -> publish(update)
            }
        })
        val result = try {
            // Reject stale calls before invoking any provider or its side effects.
            when {
                !preferences.isEnabled(call.name) -> ToolExecutionResult(
                    "Tool ${call.name} is disabled by user preferences.", outcome = ToolOutcome.FAILED)
                context.cancellation.isCancelled() -> ToolExecutionResult(tr("工具已停止"), outcome = ToolOutcome.STOPPED)
                else -> providerFor(call.name)?.execute(SmartToolHints.executionCall(call), executionContext)
                    ?: ToolExecutionResult(tr("未知工具 %s").format(call.name), outcome = ToolOutcome.FAILED)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { finish(ToolExecutionResult(tr("工具已停止"), outcome = ToolOutcome.STOPPED)) }
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult(
                text = tr("工具 %s 执行失败：%s").format(call.name, error.message ?: tr("未知错误")),
                outcome = ToolOutcome.FAILED,
            )
        }
        val settled = if (context.cancellation.isCancelled()) result.copy(outcome = ToolOutcome.STOPPED) else result
        finish(settled)
        return settled
    }

    companion object {
        /** Built-ins in the same stable order as the previously advertised tool surface. */
        fun default(): ToolRegistry = ToolRegistry(
            listOf(
                WebSearchToolProvider(),
                WebFetchToolProvider(),
                CommonsImageToolProvider(),
                ImageGenToolProvider(),
                TimeToolProvider(),
                LinguaLibreToolProvider(),
                ReactionToolProvider(),
                ColoredBubbleToolProvider(),
                MermaidToolProvider(),
                HistoryToolProvider(),
            )
        )
    }
}

private fun schemaToolName(schema: JSONObject): String? = schema.optJSONObject("function")
    ?.optString("name")
    ?.takeIf { it.isNotBlank() }
    ?: schema.optString("name").takeIf { it.isNotBlank() }

internal class WebSearchToolProvider : ToolProvider {
    override val id: String = "web-search"

    override fun definitions(protocol: ProviderProtocol): List<JSONObject> =
        listOf(WebSearchTool.schemaFor(protocol))

    override fun handles(name: String): Boolean = name == WebSearchTool.NAME

    override suspend fun execute(
        call: ChatToolCall,
        context: ToolExecutionContext,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = runCatching { JSONObject(call.arguments).opt("query") as? String }.getOrNull().orEmpty()
        context.onUpdate(ChatStreamUpdate(searching = true, searchQuery = query))
        if (!context.webSearch || context.searchApiKey.isBlank()) {
            val text = tr("搜索工具当前不可用：请先启用网页搜索并配置密钥")
            context.onUpdate(ChatStreamUpdate(searching = false, searchQuery = query, searchContent = text, toolOutcome = ToolOutcome.FAILED))
            return@withContext ToolExecutionResult(text, outcome = ToolOutcome.FAILED)
        }
        val result = WebSearch.search(
            provider = context.searchProvider,
            apiKey = context.searchApiKey,
            query = query,
            cancellation = context.cancellation,
            maxResults = context.searchMaxResults,
        )
        if (context.cancellation.isCancelled()) {
            val text = tr("搜索已停止")
            context.onUpdate(ChatStreamUpdate(searching = false, searchQuery = query, searchContent = text, toolOutcome = ToolOutcome.STOPPED))
            return@withContext ToolExecutionResult(text, outcome = ToolOutcome.STOPPED)
        }
        context.onUpdate(
            ChatStreamUpdate(
                searching = false,
                searchPagesDelta = result.pageCount,
                searchContent = result.displayContent,
                toolOutcome = if (result.succeeded) ToolOutcome.SUCCESS else ToolOutcome.FAILED,
            )
        )
        ToolExecutionResult(text = result.text, searchPages = result.pageCount,
            outcome = if (result.succeeded) ToolOutcome.SUCCESS else ToolOutcome.FAILED)
    }
}

internal class WebFetchToolProvider : ToolProvider {
    override val id: String = "web-fetch"

    override fun definitions(protocol: ProviderProtocol): List<JSONObject> =
        listOf(WebFetchTool.schemaFor(protocol))

    override fun handles(name: String): Boolean = name == WebFetchTool.NAME

    override suspend fun execute(
        call: ChatToolCall,
        context: ToolExecutionContext,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = runCatching { JSONObject(call.arguments).opt("url") as? String }.getOrNull().orEmpty()
        context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = WebFetchTool.NAME, webDomain = webPageDomain(url)))
        if (!context.webSearch || context.searchApiKey.isBlank()) {
            val text = tr("网页读取工具当前不可用：请先启用网页搜索并配置密钥")
            context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = WebFetchTool.NAME, webDomain = webPageDomain(url), toolContent = text, toolOutcome = ToolOutcome.FAILED))
            return@withContext ToolExecutionResult(text, outcome = ToolOutcome.FAILED)
        }
        var resultText = ""
        var outcome = ToolOutcome.FAILED
        return@withContext try {
            val result = WebFetch.fetch(
                provider = context.searchProvider,
                apiKey = context.searchApiKey,
                url = url,
                cancellation = context.cancellation,
            )
            resultText = result.text
            outcome = if (context.cancellation.isCancelled()) ToolOutcome.STOPPED
                else if (result.succeeded) ToolOutcome.SUCCESS else ToolOutcome.FAILED
            ToolExecutionResult(result.text, outcome = outcome)
        } finally {
            context.onUpdate(
                ChatStreamUpdate(
                    toolActive = false,
                    toolName = WebFetchTool.NAME,
                    toolContent = resultText,
                    toolOutcome = if (context.cancellation.isCancelled()) ToolOutcome.STOPPED else outcome,
                )
            )
        }
    }
}

internal class ImageGenToolProvider : ToolProvider {
    override val id: String = "image-generation"

    override fun definitions(protocol: ProviderProtocol): List<JSONObject> =
        listOf(ImageGenTool.schemaFor(protocol))

    override fun handles(name: String): Boolean = name == ImageGenTool.NAME

    override suspend fun execute(
        call: ChatToolCall,
        context: ToolExecutionContext,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (context.imageGenModel.isBlank()) {
            context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = ImageGenTool.NAME))
            context.onUpdate(
                ChatStreamUpdate(
                    toolActive = false,
                    toolName = ImageGenTool.NAME,
                    toolContent = ImageGenTool.STATUS_FAILED,
                    toolOutcome = ToolOutcome.FAILED,
                ),
            )
            return@withContext ToolExecutionResult(tr("图片生成工具当前不可用：请先配置图片生成模型"), outcome = ToolOutcome.FAILED)
        }
        if (context.cancellation.isCancelled()) return@withContext ToolExecutionResult(tr("图片生成已停止"), outcome = ToolOutcome.STOPPED)
        context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = ImageGenTool.NAME))
        var status = ImageGenTool.STATUS_FAILED
        return@withContext try {
            val prompt = ToolArguments.value(call.arguments, "prompt")
            require(prompt.isNotBlank()) { "prompt must not be empty" }
            val options = ImageGenerationOptions.fromToolArguments(call.arguments)
            // 当前用户参考图和历史生成图都必须进入实际的生图请求。
            val imageContext = imageGenerationContext(context.history)
            val result = ProviderApi.generateImage(
                config = context.provider,
                model = context.imageGenModel,
                prompt = prompt,
                context = imageContext,
                cancellation = context.cancellation,
                options = options,
                onProgress = { progress ->
                    kotlinx.coroutines.runBlocking {
                        context.onUpdate(ChatStreamUpdate(imageGeneration = listOf(progress)))
                    }
                },
            )
            val stopped = context.cancellation.isCancelled() || result.error == tr("图片生成已停止")
            if (result.attachment != null) {
                status = ImageGenTool.STATUS_OK
                context.onUpdate(ChatStreamUpdate(attachments = listOf(result.attachment)))
                val message = if (result.revisedPrompt.isNotBlank()) {
                    tr("图片已生成并发送给用户（改写后的提示词：%s）。不要重复描述图片内容，直接继续回答。").format(result.revisedPrompt)
                } else {
                    tr("图片已生成并发送给用户。不要重复描述图片内容，直接继续回答。")
                }
                ToolExecutionResult(message)
            } else if (stopped) {
                status = ""
                ToolExecutionResult(tr("图片生成已停止"), outcome = ToolOutcome.STOPPED)
            } else {
                status = ImageGenTool.STATUS_FAILED
                ToolExecutionResult(tr("图片生成失败：%s").format(result.error ?: tr("未知错误")), outcome = ToolOutcome.FAILED)
            }
        } finally {
            context.onUpdate(
                ChatStreamUpdate(
                    toolActive = false,
                    toolName = ImageGenTool.NAME,
                    toolContent = status,
                    toolOutcome = when (status) {
                        ImageGenTool.STATUS_OK -> ToolOutcome.SUCCESS
                        ImageGenTool.STATUS_FAILED -> ToolOutcome.FAILED
                        else -> ToolOutcome.STOPPED
                    },
                ),
            )
        }
    }
}

internal class TimeToolProvider : ToolProvider {
    override val id: String = "time"

    override fun definitions(protocol: ProviderProtocol): List<JSONObject> =
        listOf(TimeTool.schemaFor(protocol))

    override fun handles(name: String): Boolean = name == TimeTool.NAME

    override suspend fun execute(
        call: ChatToolCall,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = TimeTool.NAME))
        var outcome = ToolOutcome.FAILED
        var content = ""
        return try {
            content = TimeTool.now()
            outcome = ToolOutcome.SUCCESS
            ToolExecutionResult(content)
        } finally {
            context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = TimeTool.NAME,
                toolContent = content, toolOutcome = outcome))
        }
    }
}

internal class MemoryToolProvider : ToolProvider {
    override val id: String = "memory"

    override fun definitions(protocol: ProviderProtocol): List<JSONObject> =
        listOf(MemoryTool.getSchema(protocol), MemoryTool.updateSchema(protocol))

    override fun handles(name: String): Boolean =
        name == MemoryTool.GET || name == MemoryTool.UPDATE

    override suspend fun execute(
        call: ChatToolCall,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = call.name))
        var outcome = ToolOutcome.FAILED
        var content = ""
        return try {
            val app = context.appContext
            val addition = runCatching { JSONObject(call.arguments).opt("content") as? String }.getOrNull().orEmpty()
            val available = app != null && MemoryStore.loadEnabled(app)
            content = when (call.name) {
                MemoryTool.GET -> MemoryTool.read(app)
                MemoryTool.UPDATE -> MemoryTool.write(app, addition)
                else -> tr("未知工具 %s").format(call.name)
            }
            outcome = if (available && (call.name == MemoryTool.GET ||
                    call.name == MemoryTool.UPDATE && addition.isNotBlank())) ToolOutcome.SUCCESS else ToolOutcome.FAILED
            ToolExecutionResult(content, outcome = outcome)
        } finally {
            context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = call.name, toolContent = content, toolOutcome = outcome))
        }
    }
}

internal fun webPageDomain(url: String): String = url.trim().toHttpUrlOrNull()?.host.orEmpty()

internal object ToolArguments {
    fun value(arguments: String, key: String): String {
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return arguments.trim()
        return parsed.optString(key).ifBlank { arguments.trim() }
    }
}
