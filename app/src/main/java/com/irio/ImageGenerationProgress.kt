package com.irio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedReader
import java.util.UUID
import kotlin.random.Random
import org.json.JSONObject

/** UI-only snapshots. Preview bitmaps are never attachments, history JSON, backups or model input. */
data class ImageGenerationProgress(
    val id: String,
    val width: Int = 1024,
    val height: Int = 1024,
    val partialIndex: Int = -1,
    val label: String = "图片生成中",
    val modelText: String = "",
    val preview: Bitmap? = null,
    val finalAttachment: ChatAttachment? = null,
    val active: Boolean = true,
    val failed: Boolean = false,
    val outputFormat: String = "",
    val quality: String = "",
    val background: String = "",
) {
    val aspectRatio: Float get() = width.toFloat() / height
    fun caption(): String = modelText.ifBlank {
        tr(if (active) label else if (failed) "图片生成失败" else "图片生成已停止")
    }
}

internal val imageGenerationPhrases = listOf(
    "让画面慢慢浮现", "让想象化作图像", "正在描绘这份灵感", "画面正在生长", "为画面注入细节",
)

/** Hide pending previews at the Stop tap, without waiting for network cancellation to unwind. */
internal fun ChatMessage.stopImageGenerationPreviews(): ChatMessage = copy(process = process.map { event ->
    val image = event.imageGeneration
    if (image?.active == true && image.finalAttachment == null) event.copy(
        live = false, toolOutcome = ToolOutcome.STOPPED,
        imageGeneration = image.copy(active = false, preview = null),
    ) else event
})

internal fun imageGenerationSize(value: String): Pair<Int, Int>? {
    val match = Regex("^(\\d{1,5})[xX](\\d{1,5})$").matchEntire(value.trim()) ?: return null
    val width = match.groupValues[1].toIntOrNull() ?: return null
    val height = match.groupValues[2].toIntOrNull() ?: return null
    if (width !in 1..16384 || height !in 1..16384 || width.toLong() > height * 3L || height.toLong() > width * 3L) return null
    return width to height
}

/** One deck per actual image; duplicates/out-of-order partials never draw another phrase. */
internal class ImageGenerationProgressState(id: String, random: Random = Random.Default) {
    private val phrases = imageGenerationPhrases.shuffled(random).iterator()
    var value = ImageGenerationProgress(id)
        private set

    fun update(size: String = "", index: Int = -1, preview: Bitmap? = null, text: String? = null,
        outputFormat: String = "", quality: String = "", background: String = ""): ImageGenerationProgress {
        if (!value.active) return value
        if (index >= 0 && index < value.partialIndex) return value
        val dimensions = imageGenerationSize(size)
        val advanced = index >= 0 && index > value.partialIndex
        value = value.copy(
            width = dimensions?.first ?: value.width,
            height = dimensions?.second ?: value.height,
            partialIndex = if (advanced) index else value.partialIndex,
            label = if (advanced) { if (phrases.hasNext()) phrases.next() else "图片生成中" } else value.label,
            preview = if (advanced && preview != null) preview else value.preview,
            modelText = text?.takeIf { it.isNotBlank() } ?: value.modelText,
            outputFormat = outputFormat.ifBlank { value.outputFormat },
            quality = quality.ifBlank { value.quality },
            background = background.ifBlank { value.background },
        )
        return value
    }

    fun complete(attachment: ChatAttachment, preview: Bitmap?, size: String = ""): ImageGenerationProgress {
        update(size = size.takeIf { imageGenerationSize(it) != null }
            ?: preview?.let { "${it.width}x${it.height}" }.orEmpty())
        // URL completions often omit size, or echo the request's old square size.
        // Read only the final file header off the UI thread; the actual image is authoritative.
        val actual = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(attachment.filePath, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
        }.getOrNull()
        // The final file is loaded by Coil; do not retain one bitmap per historical image in chat state.
        value = value.copy(active = false, preview = null, finalAttachment = attachment,
            width = actual?.first ?: value.width, height = actual?.second ?: value.height)
        return value
    }
}

internal data class ImageStreamUpdate(
    val progress: List<ImageGenerationProgress> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList(),
    val done: Boolean = false,
    val error: String? = null,
    val retryable: Boolean = false,
)

/** Both documented dialects, plus output-item metadata/final output from Responses. */
internal class ImageGenerationStream(
    private val onProgress: (ImageGenerationProgress) -> Unit = {},
    private val save: (String, String, String) -> ChatAttachment? = { b64, format, prompt ->
        runCatching {
            val bytes = java.util.Base64.getDecoder().decode(b64)
            ProviderApi.saveGeneratedImage(bytes, imageGenerationMime(format), prompt)?.attachment
        }.getOrNull()
    },
    private val cancelled: () -> Boolean = { false },
    private val previewDecoder: (String) -> Bitmap? = ::decodeImageGenerationPreview,
    private val download: (String, String) -> ImageGenResult = { _, _ -> ImageGenResult(error = tr("图片地址无法下载")) },
) {
    private val requestId = UUID.randomUUID().toString()
    private val states = linkedMapOf<String, ImageGenerationProgressState>()
    private val results = linkedMapOf<String, ChatAttachment>()
    private var modelText = ""
    private var revisedPrompt = ""
    val attachments: List<ChatAttachment> get() = results.values.toList()
    val text: String get() = modelText
    val prompt: String get() = revisedPrompt

    fun start(): ImageGenerationProgress = state("image").value.also(onProgress)
    private fun state(key: String): ImageGenerationProgressState {
        if (key != "image" && key !in states && states.size == 1 && "image" in states) {
            states[key] = states.remove("image")!!
        }
        return states.getOrPut(key) {
            ImageGenerationProgressState("$requestId:$key").also { it.update(text = modelText) }
        }
    }

    fun completeJson(result: ImageGenResult, size: String) {
        result.attachment?.let { attachment ->
            if (!cancelled()) {
                state("image").update(text = result.text)
                onProgress(state("image").complete(attachment, null, size))
            }
        }
    }

    fun accept(payload: String, eventName: String = ""): ImageStreamUpdate? {
        if (cancelled() || payload == "[DONE]") return null
        val event = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val type = event.optString("type").ifBlank { eventName }
        val updates = mutableListOf<ImageGenerationProgress>()
        val added = mutableListOf<ChatAttachment>()
        var imageFailure: ImageGenResult? = null
        fun result(done: Boolean = false) = ImageStreamUpdate(updates, added, done,
            error = imageFailure?.error, retryable = imageFailure?.retryable ?: false)
        fun publish(progress: ImageGenerationProgress) {
            if (!cancelled()) { updates += progress; onProgress(progress) }
        }
        fun image(item: JSONObject, key: String, final: Boolean = false, response: Boolean = false) {
            val current = state(key)
            if (!current.value.active) return
            val index = item.optInt("partial_image_index", -1)
            val b64 = (item.opt(if (response) "partial_image_b64" else "b64_json") as? String).orEmpty()
                .ifBlank { if (final) (item.opt("result") as? String).orEmpty() else "" }
            val url = (item.opt("url") as? String).orEmpty().ifBlank {
                item.optJSONObject("image_url")?.optString("url").orEmpty()
            }
            val source = b64.ifBlank { if (final) url else "" }.trim()
            val remote = source.startsWith("https://", true) || source.startsWith("http://", true) || source.startsWith("data:", true)
            val fresh = index > current.value.partialIndex
            val preview = if (source.isNotBlank() && !remote && (fresh || final)) previewDecoder(source) else null
            if (cancelled()) return
            val size = item.optString("size").takeIf { imageGenerationSize(it) != null }
                ?: preview?.let { "${it.width}x${it.height}" }.orEmpty()
            current.update(outputFormat = item.optString("output_format"), quality = item.optString("quality"),
                background = item.optString("background"))
            revisedPrompt = item.optString("revised_prompt").ifBlank { revisedPrompt }
            if (final && source.isNotBlank()) {
                val attachment = if (remote) {
                    val downloaded = download(source, revisedPrompt)
                    if (downloaded.attachment == null) {
                        imageFailure = downloaded.copy(error = downloaded.error ?: tr("图片地址无法下载"))
                        return
                    }
                    downloaded.attachment
                } else save(source, current.value.outputFormat, revisedPrompt) ?: return
                if (cancelled()) { java.io.File(attachment.filePath).delete(); return }
                results[key] = attachment
                added += attachment
                publish(current.complete(attachment, preview, size))
            } else {
                publish(current.update(size, index, preview, modelText))
            }
        }
        fun completedImages() {
            val data = event.optJSONArray("data")
            if (data == null) {
                image(event, "image", final = true)
                return
            }
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                // Preserve selected parameters carried on the enclosing completion event.
                val merged = JSONObject(event.toString()).apply { remove("data") }
                item.keys().forEach { merged.put(it, item.get(it)) }
                image(merged, if (index == 0) "image" else "image-$index", final = true)
            }
        }
        when {
            type == "response.output_text.delta" -> {
                modelText += event.optString("delta")
                states.values.filter { it.value.active }.forEach { publish(it.update(text = modelText)) }
            }
            type == "response.output_text.done" -> {
                if (modelText.isBlank()) modelText = event.optString("text")
                states.values.filter { it.value.active }.forEach { publish(it.update(text = modelText)) }
            }
            type == "response.image_generation_call.partial_image" ->
                image(event, event.optString("item_id").ifBlank { "image" }, response = true)
            type == "response.image_generation_call.in_progress" || type == "response.image_generation_call.generating" ->
                image(event, event.optString("item_id").ifBlank { "image" })
            type == "response.output_item.added" || type == "response.output_item.done" -> {
                val item = event.optJSONObject("item") ?: return null
                if (item.optString("type") != "image_generation_call") return null
                image(item, item.optString("id").ifBlank { event.optString("item_id").ifBlank { "image" } }, final = type.endsWith(".done"))
            }
            type == "response.completed" -> {
                val output = event.optJSONObject("response")?.optJSONArray("output")
                for (index in 0 until (output?.length() ?: 0)) {
                    val item = output?.optJSONObject(index) ?: continue
                    if (item.optString("type") == "image_generation_call") image(item, item.optString("id").ifBlank { "image" }, final = true)
                    if (item.optString("type") == "message" && modelText.isBlank()) {
                        val content = item.optJSONArray("content")
                        modelText = (0 until (content?.length() ?: 0)).joinToString("") {
                            content?.optJSONObject(it)?.optString("text").orEmpty()
                        }
                    }
                }
                return result(done = true)
            }
            type == "image_generation.partial_image" || type == "image_edit.partial_image" -> image(event, "image")
            type == "image_generation.completed" || type == "image_edit.completed" -> {
                completedImages()
                return result(done = true)
            }
            type.isBlank() && event.optJSONArray("data") != null -> {
                completedImages()
                return result(done = true)
            }
            type == "error" || type == "response.failed" || type == "response.incomplete" -> return ImageStreamUpdate(error =
                event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                    ?: event.optJSONObject("error")?.optString("message") ?: event.optString("message").ifBlank { tr("图片生成失败") },
                retryable = retryableProviderError(payload))
            else -> return null
        }
        return result()
    }
}

internal fun imageGenerationMime(format: String): String = when (format.lowercase()) {
    "jpeg", "jpg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}

/** Decode off the main thread and cap preview memory independently of 4K output size. */
internal fun decodeImageGenerationPreview(b64: String): Bitmap? = runCatching {
    if (b64.length > 64 * 1024 * 1024) return null
    decodeImageGenerationPreviewBytes(java.util.Base64.getDecoder().decode(b64))
}.getOrNull()

private fun decodeImageGenerationPreviewBytes(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options()
    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 1024) {
        options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** Blank-line SSE framing (including multiline data and event-only type names). */
internal fun readImageSse(reader: BufferedReader, cancelled: () -> Boolean, onEvent: (String, String) -> Boolean) {
    var name = ""
    val data = StringBuilder()
    fun flush(): Boolean {
        val payload = data.toString()
        data.setLength(0)
        val event = name
        name = ""
        return payload.isNotBlank() && onEvent(payload, event)
    }
    while (!cancelled()) {
        val line = reader.readLine() ?: break
        when {
            line.isEmpty() -> if (flush()) return
            line.startsWith("event:") -> name = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
            }
        }
    }
    if (!cancelled()) flush()
}
