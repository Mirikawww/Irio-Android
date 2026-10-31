package com.irio

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Public URLs, mm_file:// IDs and data URIs; media content is never silently discarded. */
data class VideoGenerationMedia(val type: String, val url: String, val role: String)

data class VideoGenerationUsage(
    val totalSeconds: Int? = null,
    val inputSeconds: Int? = null,
    val outputSeconds: Int? = null,
    val inputImageCount: Int? = null,
    val inputAudioSeconds: Int? = null,
)

data class VideoGenerationResult(
    val attachment: ChatAttachment? = null,
    val error: String? = null,
    val taskId: String = "",
    val status: String = "",
    val usage: TokenUsage? = null,
    val stopped: Boolean = false,
    val videoUsage: VideoGenerationUsage? = null,
    /** Safe to retry this query; creation is never retried automatically. */
    val retryable: Boolean = false,
)

/**
 * MiniMax V2 create + query, with no callback server and no automatic POST retry.
 * All methods block: callers must use an IO dispatcher. Stopping only stops the local wait;
 * an accepted upstream task can still complete and incur charges. Retain taskId to resume it.
 * Output URLs are time-limited; query the saved task again if a URL expires (within seven days).
 */
object VideoGenerationApi {
    private const val MAX_REQUEST_BYTES = 64L * 1024 * 1024
    private val taskIdPattern = Regex("[A-Za-z0-9_-]{1,256}")
    private val statuses = setOf("queued", "running", "succeeded", "failed", "cancelled")
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }

    fun create(
        config: ProviderConfig,
        model: String,
        prompt: String,
        options: VideoGenerationOptions = VideoGenerationOptions(),
        media: List<VideoGenerationMedia> = emptyList(),
        cancellation: ChatRequestCancellation? = null,
    ): VideoGenerationResult {
        if (cancellation?.isCancelled() == true) return stopped()
        return try {
            val body = requestBody(model, prompt, options, media).toString().toByteArray(Charsets.UTF_8)
            require(body.size.toLong() <= MAX_REQUEST_BYTES) { "Video request exceeds 64 MB" }
            val root = request(config, endpoint(config), body, cancellation)
            // Require a real string, never coerce malformed/null task IDs into path segments.
            val id = root.opt("task_id") as? String ?: error("Video create response has no task_id")
            require(taskIdPattern.matches(id)) { "Video create response has an invalid task_id" }
            VideoGenerationResult(taskId = id, status = "queued")
        } catch (error: Exception) {
            if (cancellation?.isCancelled() == true) stopped()
            else VideoGenerationResult(error = error.message ?: "Video creation failed")
        }
    }

    fun query(config: ProviderConfig, taskId: String, cancellation: ChatRequestCancellation? = null): VideoGenerationResult {
        if (cancellation?.isCancelled() == true) return stopped(taskId)
        return try {
            require(taskIdPattern.matches(taskId)) { "Invalid video task_id" }
            val url = endpoint(config, query = true).newBuilder().addPathSegment(taskId).build()
            val root = request(config, url, null, cancellation)
            parseTask(root, taskId)
        } catch (error: Exception) {
            if (cancellation?.isCancelled() == true) stopped(taskId)
            else VideoGenerationResult(taskId = taskId, error = error.message ?: "Video query failed",
                retryable = error is IOException || error is VideoHttpException && error.retryable)
        }
    }

    fun await(
        config: ProviderConfig,
        taskId: String,
        cancellation: ChatRequestCancellation? = null,
        pollIntervalMillis: Long = 5_000,
        maxPolls: Int = Int.MAX_VALUE,
        pollingDelay: (Long) -> Unit = { Thread.sleep(it) },
        onProgress: (VideoGenerationResult) -> Unit = {},
    ): VideoGenerationResult {
        if (pollIntervalMillis < 0 || maxPolls <= 0) {
            return VideoGenerationResult(taskId = taskId, error = "Invalid video polling options")
        }
        var nextDelay = pollIntervalMillis
        var consecutiveFailures = 0
        repeat(maxPolls) { index ->
            if (cancellation?.isCancelled() == true) return stopped(taskId)
            if (index > 0) {
                // Short slices make a normal Thread.sleep wait promptly cancellable.
                var remaining = nextDelay
                try {
                    while (remaining > 0) {
                        if (cancellation?.isCancelled() == true) return stopped(taskId)
                        val slice = minOf(remaining, 100L)
                        pollingDelay(slice)
                        remaining -= slice
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return stopped(taskId)
                } catch (error: Exception) {
                    return VideoGenerationResult(taskId = taskId, error = error.message ?: "Video polling delay failed")
                }
            }
            val result = query(config, taskId, cancellation)
            if (result.retryable && !result.stopped) {
                consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(6)
                nextDelay = (maxOf(pollIntervalMillis, 1_000L).coerceAtMost(60_000L) *
                    (1L shl (consecutiveFailures - 1))).coerceAtMost(60_000L)
                return@repeat
            }
            consecutiveFailures = 0
            nextDelay = pollIntervalMillis
            onProgress(result)
            if (result.error != null || result.status !in setOf("queued", "running")) return result
        }
        return VideoGenerationResult(taskId = taskId, error = "Video polling timed out; resume querying this task_id instead of creating another task")
    }

    fun generate(
        config: ProviderConfig,
        model: String,
        prompt: String,
        options: VideoGenerationOptions = VideoGenerationOptions(),
        media: List<VideoGenerationMedia> = emptyList(),
        cancellation: ChatRequestCancellation? = null,
        pollIntervalMillis: Long = 5_000,
        maxPolls: Int = Int.MAX_VALUE,
        pollingDelay: (Long) -> Unit = { Thread.sleep(it) },
        onProgress: (VideoGenerationResult) -> Unit = {},
    ): VideoGenerationResult {
        if (pollIntervalMillis < 0 || maxPolls <= 0) return VideoGenerationResult(error = "Invalid video polling options")
        val created = create(config, model, prompt, options, media, cancellation)
        if (created.error != null || created.taskId.isEmpty()) return created
        onProgress(created)
        return await(config, created.taskId, cancellation, pollIntervalMillis, maxPolls, pollingDelay, onProgress)
    }

    fun mediaFromAttachments(attachments: List<ChatAttachment>, role: String = "reference_image"): List<VideoGenerationMedia> =
        attachments.map { attachment ->
            val type = when {
                attachment.mimeType.startsWith("image/") -> "image_url"
                attachment.mimeType.startsWith("video/") -> "video_url"
                attachment.mimeType.startsWith("audio/") -> "audio_url"
                else -> throw IllegalArgumentException("Unsupported video input MIME type")
            }
            val url = attachment.remoteUrl.ifEmpty {
                val file = File(attachment.filePath)
                require(file.isFile && file.canRead() && file.length() > 0) { "Cannot read video input attachment" }
                require(file.length() <= mediaByteLimit(type)) { "Video input attachment is too large" }
                "data:${attachment.mimeType};base64," + Base64.getEncoder().encodeToString(file.readBytes())
            }
            VideoGenerationMedia(type, url, role).also(::validateMedia)
        }

    internal fun requestBody(model: String, prompt: String, options: VideoGenerationOptions,
                             media: List<VideoGenerationMedia>): JSONObject {
        options.validationError(model)?.let { throw IllegalArgumentException(it) }
        require(prompt.isNotBlank() && prompt.codePointCount(0, prompt.length) <= 7_000) { "Video prompt must contain 1–7000 characters" }
        require(media.size <= 15) { "Too many video input media items" }
        media.forEach(::validateMedia)
        val counts = media.groupingBy { it.role }.eachCount()
        require((counts["first_frame"] ?: 0) <= 1 && (counts["last_frame"] ?: 0) <= 1) { "Only one first and one last frame are allowed" }
        require((counts["reference_image"] ?: 0) <= 9 && (counts["reference_video"] ?: 0) <= 3 &&
            (counts["reference_audio"] ?: 0) <= 3) { "Reference media count exceeds MiniMax limits" }
        val frames = media.any { it.role == "first_frame" || it.role == "last_frame" }
        require(!frames || media.none { it.role.startsWith("reference_") }) { "Frame and reference media cannot be mixed" }
        require(media.isNotEmpty() || options.ratio != "adaptive") { "Text-to-video requires a concrete ratio" }
        require(frames || VideoGenerationOptions.capabilityModel(model) != "MiniMax-H3-Lite" || options.ratio != "adaptive") { "MiniMax-H3-Lite reference video requires a concrete ratio" }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        media.forEach { content.put(JSONObject().put("type", it.type).put(it.type, JSONObject().put("url", it.url)).put("role", it.role)) }
        return JSONObject().put("model", model).put("content", content).put("resolution", options.resolution)
            .put("duration", options.duration).put("ratio", if (frames) "adaptive" else options.ratio)
    }

    private fun validateMedia(media: VideoGenerationMedia) {
        val roles = when (media.type) {
            "image_url" -> setOf("first_frame", "last_frame", "reference_image")
            "video_url" -> setOf("reference_video")
            "audio_url" -> setOf("reference_audio")
            else -> throw IllegalArgumentException("Unsupported video input type")
        }
        require(media.role in roles) { "Video media role does not match its type" }
        val url = media.url
        require(url.isNotBlank() && url == url.trim() && url.none { it.isISOControl() }) { "Invalid video media URL" }
        when {
            url.startsWith("data:") -> {
                val comma = url.indexOf(',')
                require(comma in 1..100) { "Invalid video media data URI" }
                val mime = url.substring(5, comma).removeSuffix(";base64")
                val allowed = when (media.type) {
                    "image_url" -> setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic", "image/heif")
                    "video_url" -> setOf("video/mp4", "video/quicktime")
                    else -> setOf("audio/wav", "audio/mp3", "audio/mpeg")
                }
                require(url.substring(5, comma) == "$mime;base64" && mime in allowed) { "Unsupported video media data MIME type" }
                val encoded = url.substring(comma + 1)
                require(encoded.isNotEmpty() && encoded.length.toLong() <= (mediaByteLimit(media.type) + 2) / 3 * 4) { "Video input media is too large or empty" }
                val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid video media Base64")
                }
                require(bytes.isNotEmpty() && bytes.size.toLong() <= mediaByteLimit(media.type)) { "Video input media is too large or empty" }
            }
            url.startsWith("mm_file://") -> require(taskIdPattern.matches(url.removePrefix("mm_file://"))) { "Invalid MiniMax file ID" }
            else -> requireValidHttpUrl(url)
        }
    }

    private fun mediaByteLimit(type: String): Long = when (type) {
        "image_url" -> 30L * 1024 * 1024
        "video_url" -> 50L * 1024 * 1024
        else -> 15L * 1024 * 1024
    }

    private fun endpoint(config: ProviderConfig, query: Boolean = false): HttpUrl {
        require(config.protocol == ProviderProtocol.MINIMAX_VIDEO) { "Provider must use MiniMax Video Generation V2" }
        val parsed = requireValidHttpUrl(config.endpointUrl)
        require(parsed.query == null && parsed.fragment == null) { "Video endpoint must not include query or fragment" }
        var base = parsed.encodedPath.trimEnd('/')
        base = base.removeSuffix("/video_generation").removeSuffix("/query").removeSuffix("/v2").removeSuffix("/v1")
        return parsed.newBuilder().encodedPath(base + if (query) "/v2/query/video_generation" else "/v2/video_generation").build()
    }

    private fun requireValidHttpUrl(value: String): HttpUrl {
        require(value == value.trim() && value.none { it.isISOControl() || it.isWhitespace() }) { "Invalid video URL" }
        val parsed = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid video URL")
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Video URL must not contain credentials" }
        return parsed
    }

    private fun request(config: ProviderConfig, url: HttpUrl, body: ByteArray?, cancellation: ChatRequestCancellation?): JSONObject {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", "application/json").post(object : okhttp3.RequestBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength(): Long = body.size.toLong()
            // Also blocks OkHttp's HTTP follow-ups (e.g. 503 + Retry-After: 0), not just I/O retry.
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: okio.BufferedSink) { sink.write(body) }
        })
        else builder.get()
        applyAuth(builder, config) // Custom headers override defaults, including Content-Type, last.
        val call = client.newCall(builder.build())
        cancellation?.attach(call)
        try {
            check(cancellation?.isCancelled() != true) { "Video generation stopped" }
            call.execute().use { response ->
                val text = response.body.string()
                check(cancellation?.isCancelled() != true) { "Video generation stopped" }
                val root = runCatching { JSONObject(text) }.getOrNull()
                val error = root?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: (root?.opt("error") as? String)?.takeIf { it.isNotBlank() }
                if (!response.isSuccessful || error != null || root?.optString("type") == "error") {
                    throw VideoHttpException(error ?: "Video API failed (HTTP ${response.code})",
                        response.code == 408 || response.code == 429 || response.code in 500..599)
                }
                return root ?: throw IllegalStateException("Video API returned invalid JSON")
            }
        } finally { cancellation?.detach(call) }
    }

    private fun parseTask(root: JSONObject, taskId: String): VideoGenerationResult {
        val task = root.optJSONObject("task") ?: error("Video query response has no task")
        val id = task.opt("id") as? String
        require(id == null || id == taskId) { "Video query returned a different task_id" }
        val status = task.opt("status") as? String ?: error("Video query response has no status")
        require(status in statuses) { "Unknown video task status" }
        val usageJson = task.optJSONObject("usage")
        fun number(name: String): Int? = (usageJson?.opt(name) as? Number)?.let {
            val value = it.toLong()
            value.takeIf { n -> n in 0..Int.MAX_VALUE.toLong() && n.toDouble() == it.toDouble() }?.toInt()
        }
        val usage = TokenUsage(number("prompt_tokens"), number("completion_tokens"), number("total_tokens"))
            .takeIf { it.promptTokens != null || it.completionTokens != null || it.totalTokens != null }
        val videoUsage = VideoGenerationUsage(number("total_seconds"), number("input_seconds"), number("output_seconds"),
            number("input_image_count"), number("input_audio_seconds")).takeIf {
                it.totalSeconds != null || it.inputSeconds != null || it.outputSeconds != null || it.inputImageCount != null || it.inputAudioSeconds != null
            }
        val taskError = task.optJSONObject("error")?.let {
            listOf(it.optString("code"), it.optString("message")).filter(String::isNotBlank).joinToString(": ")
        }?.takeIf(String::isNotBlank)
        val error = when (status) {
            "failed" -> taskError ?: "Video generation failed"
            "cancelled" -> taskError ?: "Video generation cancelled by provider"
            else -> null
        }
        val attachment = if (status == "succeeded") {
            val url = task.optJSONObject("content")?.opt("url") as? String ?: error("Succeeded video task has no output URL")
            requireValidHttpUrl(url)
            ChatAttachment(name = "video-$taskId.mp4", mimeType = "video/mp4", filePath = "", remoteUrl = url)
        } else null
        return VideoGenerationResult(attachment, error, taskId, status, usage, status == "cancelled", videoUsage)
    }

    private fun stopped(taskId: String = "") = VideoGenerationResult(taskId = taskId, error = "Video generation stopped locally; an accepted provider task may still run", stopped = true)

    private class VideoHttpException(message: String, val retryable: Boolean) : Exception(message)
}
