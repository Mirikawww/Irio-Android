package com.irio

import android.content.Context
import android.util.Base64
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object VideoGenTool {
    const val NAME = "generate_video"
    const val DESCRIPTION = "Create a MiniMax video generation task. Returns immediately after task creation; " +
        "the app keeps polling in the background and sends one hidden completion message with the video URL. " +
        "Do not poll, wait, claim it has finished, or create a duplicate task. Continue chatting normally. " +
        "Choose resolution, duration and ratio from the user request. MiniMax-H3 supports 768P/2K and 4-15 seconds. " +
        "Use mode text, first_frame, last_frame, first_last_frame or reference; non-text modes use the latest user uploads. " +
        "Use the user's configured defaults when resolution, duration or ratio is omitted."

    fun schema(protocol: ProviderProtocol): JSONObject {
        val properties = JSONObject()
            .put("prompt", JSONObject().put("type", "string").put("description", "Detailed video description; required and non-empty."))
            .put("resolution", JSONObject().put("type", "string").put("description", "768P or 2K for H3; use an empty string for configured default."))
            .put("duration", JSONObject().put("type", "integer").put("description", "4-15 seconds; 0 uses the configured default."))
            .put("ratio", JSONObject().put("type", "string").put("description", "21:9, 16:9, 4:3, 1:1, 3:4 or 9:16; empty string uses default. Image-to-video uses adaptive."))
            .put("mode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("text", "first_frame", "last_frame", "first_last_frame", "reference"))))
        val parameters = JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(listOf("prompt", "resolution", "duration", "ratio", "mode")))
            .put("additionalProperties", false)
        return portableToolSchema(protocol, NAME, DESCRIPTION, parameters)
    }
}

/** Kept portable for every chat protocol; MiniMax itself does not receive tool schemas. */
internal fun portableToolSchema(protocol: ProviderProtocol, name: String, description: String, parameters: JSONObject): JSONObject = when (protocol) {
    ProviderProtocol.RESPONSES -> JSONObject().put("type", "function").put("name", name)
        .put("description", description).put("parameters", parameters).put("strict", true)
    ProviderProtocol.ANTHROPIC -> JSONObject().put("name", name).put("description", description).put("input_schema", parameters)
    ProviderProtocol.GEMINI -> GeminiProtocol.declaration(name, description, parameters)
    ProviderProtocol.GEMINI_INTERACTIONS -> GeminiInteractionsProtocol.declaration(name, description, parameters)
    else -> JSONObject().put("type", "function").put("function", JSONObject().put("name", name)
        .put("description", description).put("parameters", parameters))
}

internal class VideoGenToolProvider(private val configuredModel: String = "") : ToolProvider {
    override val id = "video-generation"
    override fun definitions(protocol: ProviderProtocol) = listOf(VideoGenTool.schema(protocol))
    override fun handles(name: String) = name == VideoGenTool.NAME
    override fun systemInstructions() = "Video generation is asynchronous: generate_video only creates a task. " +
        "Continue responding without waiting. Its completion will arrive as a hidden app message; " +
        "the app displays a downloadable video card automatically. Never pretend to have watched the video."

    override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult = withContext(Dispatchers.IO) {
        context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = VideoGenTool.NAME))
        val app = context.appContext ?: return@withContext ToolExecutionResult("Video generation requires an app context.", outcome = ToolOutcome.FAILED)
        require(context.conversationId.isNotBlank()) { "Missing originating conversation" }
        val model = configuredModel.ifBlank { configuredVideoGenModel(app) }
        val provider = ProviderStore.loadProviders(app).firstOrNull {
            it.enabled && it.protocol == ProviderProtocol.MINIMAX_VIDEO && model in it.enabledModels()
        } ?: return@withContext ToolExecutionResult(tr("请先在「模型」中配置视频生成模型"), outcome = ToolOutcome.FAILED)
        val args = JSONObject(call.arguments)
        val defaults = VideoGenerationStore.load(app, provider.id, model)
        val options = VideoGenerationOptions(
            resolution = args.optString("resolution").ifBlank { defaults.resolution },
            duration = if (args.optInt("duration") == 0) defaults.duration else args.getInt("duration"),
            ratio = args.optString("ratio").ifBlank { defaults.ratio },
        )
        val prompt = args.getString("prompt")
        val mode = args.optString("mode", "text")
        val attachments = context.history.lastOrNull { it.role == "user" }?.attachments.orEmpty()
        val media = videoGenerationMedia(attachments, mode)
        val created = VideoGenerationApi.create(provider, model, prompt, options = options, media = media, cancellation = context.cancellation)
        if (created.error != null || created.taskId.isBlank()) return@withContext ToolExecutionResult(
            created.error ?: tr("视频任务创建失败"), outcome = if (context.cancellation.isCancelled()) ToolOutcome.STOPPED else ToolOutcome.FAILED)
        VideoGenerationJobs.start(app, VideoGenerationJob(
            taskId = created.taskId, conversationId = context.conversationId, callId = call.id,
            videoProviderId = provider.id, model = model, chatProviderId = context.provider.id, chatModel = context.model,
        ), provider)
        val result = JSONObject().put("task_id", created.taskId).put("status", "running")
            .put("message", "Task created. Continue chatting; completion will be delivered automatically. Do not poll or resubmit.").toString()
        context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = VideoGenTool.NAME, toolContent = result))
        ToolExecutionResult(result)
    }
}

/** Explicit roles prevent silently discarding uploads or mixing first/last with references. */
internal fun videoGenerationMedia(attachments: List<ChatAttachment>, mode: String): List<VideoGenerationMedia> {
    require(mode in setOf("text", "first_frame", "last_frame", "first_last_frame", "reference")) { "Invalid video input mode" }
    if (mode == "text") return emptyList()
    if (mode != "reference") require(attachments.all { it.isImage }) { "Frame modes only accept images; use reference mode for other media" }
    val selected = when (mode) {
        "first_frame", "last_frame" -> attachments.filter { it.isImage }.also { require(it.size == 1) { "This mode requires exactly one image" } }
        "first_last_frame" -> attachments.filter { it.isImage }.also { require(it.size == 2) { "This mode requires exactly two images" } }
        else -> attachments.also { require(it.isNotEmpty()) { "Reference mode requires media" } }
    }
    return selected.mapIndexed { index, attachment ->
        val type = when {
            attachment.isImage -> "image_url"
            attachment.isVideo -> "video_url"
            attachment.mimeType.startsWith("audio/") -> "audio_url"
            else -> error("Unsupported video reference media")
        }
        val role = when (mode) {
            "first_last_frame" -> if (index == 0) "first_frame" else "last_frame"
            "reference" -> when (type) { "image_url" -> "reference_image"; "video_url" -> "reference_video"; else -> "reference_audio" }
            else -> mode
        }
        val url = attachment.remoteUrl.ifBlank {
            val file = File(attachment.filePath)
            val limit = when (type) { "image_url" -> 30L; "video_url" -> 50L; else -> 15L } * 1024 * 1024
            require(file.isFile && file.length() in 1..limit) { "Reference media is missing, empty or too large" }
            "data:${attachment.mimeType};base64,${Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)}"
        }
        VideoGenerationMedia(type, url, role)
    }
}
