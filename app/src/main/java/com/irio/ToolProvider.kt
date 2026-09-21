package com.irio

import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val imageGenModel: String = "",
    val cancellation: ChatRequestCancellation = ChatRequestCancellation(),
    val onUpdate: suspend (ChatStreamUpdate) -> Unit = {},
    val appContext: android.content.Context? = null,
)

/** Provider-neutral result that can be appended to the next model turn. */
data class ToolExecutionResult(
    val text: String,
    val searchPages: Int = 0,
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
class ToolRegistry(providers: Iterable<ToolProvider> = emptyList()) {
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
        }
    }

    /** Alias that makes call sites read naturally when building a request body. */
    fun schemas(protocol: ProviderProtocol): List<JSONObject> = definitions(protocol)

    fun providerFor(name: String): ToolProvider? =
        registered.values.firstOrNull { it.handles(name) }

    fun handles(name: String): Boolean = providerFor(name) != null

    suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val provider = providerFor(call.name)
            ?: return ToolExecutionResult("未知工具 ${call.name}")
        return try {
            provider.execute(call, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult(
                text = "工具 ${call.name} 执行失败：${error.message ?: "未知错误"}",
            )
        }
    }

    companion object {
        /** Built-ins in the same stable order as the previously advertised tool surface. */
        fun default(): ToolRegistry = ToolRegistry(
            listOf(
                WebSearchToolProvider(),
                WebFetchToolProvider(),
                ImageGenToolProvider(),
                TimeToolProvider(),
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
        if (!context.webSearch || context.searchApiKey.isBlank()) {
            return@withContext ToolExecutionResult("搜索工具当前不可用：请先启用网页搜索并配置密钥。")
        }
        context.onUpdate(ChatStreamUpdate(searching = true))
        val result = WebSearch.search(
            provider = context.searchProvider,
            apiKey = context.searchApiKey,
            query = ToolArguments.value(call.arguments, "query"),
            cancellation = context.cancellation,
            maxResults = context.searchMaxResults,
        )
        if (context.cancellation.isCancelled()) {
            context.onUpdate(ChatStreamUpdate(searching = false))
            return@withContext ToolExecutionResult("搜索已停止")
        }
        context.onUpdate(
            ChatStreamUpdate(
                searching = false,
                searchPagesDelta = result.pageCount,
                searchContent = result.text,
            )
        )
        ToolExecutionResult(text = result.text, searchPages = result.pageCount)
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
        if (!context.webSearch || context.searchApiKey.isBlank()) {
            return@withContext ToolExecutionResult("网页读取工具当前不可用：请先启用网页搜索并配置密钥。")
        }
        context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = WebFetchTool.NAME))
        var resultText = ""
        return@withContext try {
            val result = WebFetch.fetch(
                provider = context.searchProvider,
                apiKey = context.searchApiKey,
                url = ToolArguments.value(call.arguments, "url"),
                cancellation = context.cancellation,
            )
            resultText = result.text
            ToolExecutionResult(result.text)
        } finally {
            context.onUpdate(
                ChatStreamUpdate(
                    toolActive = false,
                    toolName = WebFetchTool.NAME,
                    toolContent = resultText,
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
            return@withContext ToolExecutionResult("图片生成工具当前不可用：请先配置图片生成模型。")
        }
        if (context.cancellation.isCancelled()) return@withContext ToolExecutionResult("图片生成已停止")
        val prompt = ToolArguments.value(call.arguments, "prompt")
        // Images API 无状态：把最近一轮生成图和 prompt 作为编辑参考传回端点。
        val imageContext = imageContext(context.history)
        val result = ProviderApi.generateImage(
            config = context.provider,
            model = context.imageGenModel,
            prompt = prompt,
            context = imageContext,
            cancellation = context.cancellation,
        )
        if (result.attachment != null) {
            context.onUpdate(ChatStreamUpdate(attachments = listOf(result.attachment)))
            val message = if (result.revisedPrompt.isNotBlank()) {
                "图片已生成并发送给用户（改写后的提示词：${result.revisedPrompt}）。不要重复描述图片内容，直接继续回答。"
            } else {
                "图片已生成并发送给用户。不要重复描述图片内容，直接继续回答。"
            }
            ToolExecutionResult(message)
        } else {
            ToolExecutionResult("图片生成失败：${result.error ?: "未知错误"}")
        }
    }

    private fun imageContext(history: List<ChatRequestMessage>): ProviderApi.ImageGenContext? {
        val lastUserIndex = history.indexOfLast { it.role == "user" }
        if (lastUserIndex <= 0) return null
        val previousUser = history.subList(0, lastUserIndex)
            .lastOrNull { it.role == "user" }
            ?: return null
        val previousImages = history.subList(0, lastUserIndex)
            .filter { it.role == "assistant" }
            .flatMap { message -> message.attachments.filter(ChatAttachment::isImage) }
        return previousImages.takeIf { it.isNotEmpty() }?.let {
            ProviderApi.ImageGenContext(previousUser.text, it.takeLast(3))
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
        return try {
            ToolExecutionResult(TimeTool.now())
        } finally {
            context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = TimeTool.NAME))
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
        return try {
            when (call.name) {
                MemoryTool.GET -> ToolExecutionResult(MemoryTool.read(context.appContext))
                MemoryTool.UPDATE -> ToolExecutionResult(
                    MemoryTool.write(context.appContext, ToolArguments.value(call.arguments, "content")),
                )
                else -> ToolExecutionResult("未知工具 ${call.name}")
            }
        } finally {
            context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = call.name))
        }
    }
}

internal object ToolArguments {
    fun value(arguments: String, key: String): String {
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return arguments.trim()
        return parsed.optString(key).ifBlank { arguments.trim() }
    }
}
