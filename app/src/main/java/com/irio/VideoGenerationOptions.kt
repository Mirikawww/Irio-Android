package com.irio

import android.content.Context
import org.json.JSONObject

/** Wire values stay strings so persisted options and model menus share the same vocabulary. */
data class VideoGenerationOptions(
    val resolution: String = "768P",
    val duration: Int = 5,
    val ratio: String = "16:9",
) {
    fun validationError(model: String): String? = when {
        capabilityModel(model) == null -> "Unsupported MiniMax video model"
        resolution !in resolutionsFor(model) -> "Unsupported resolution for $model"
        duration !in durationsFor(model) -> "Unsupported duration for $model"
        ratio !in ratios && ratio != "adaptive" -> "Unsupported video aspect ratio"
        else -> null
    }

    /** For stored/menu values only. Explicit API requests are validated, never silently repaired. */
    fun forModel(model: String): VideoGenerationOptions = copy(
        resolution = resolution.takeIf { it in resolutionsFor(model) } ?: "768P",
        duration = duration.coerceIn(durationsFor(model)),
        ratio = ratio.takeIf { it in ratios || it == "adaptive" && capabilityModel(model) != "MiniMax-H3-Lite" } ?: "16:9",
    )

    internal fun toJson(): JSONObject = JSONObject().put("resolution", resolution)
        .put("duration", duration).put("ratio", ratio)

    companion object {
        val models: List<String> = listOf("MiniMax-H3", "MiniMax-H3-Highspeed", "MiniMax-H3-Lite", "MiniMax-H3-Max")
        val ratios: List<String> = listOf("21:9", "16:9", "4:3", "1:1", "3:4", "9:16")
        /** Match metadata only; never rewrite the saved or on-wire model identifier. */
        fun capabilityModel(model: String): String? = models.firstOrNull {
            it.equals(model.substringAfterLast('/'), ignoreCase = true)
        }
        fun resolutionsFor(model: String): List<String> =
            if (capabilityModel(model) == "MiniMax-H3") listOf("768P", "2K") else listOf("480P", "768P")
        fun durationsFor(model: String): IntRange = if (capabilityModel(model) == "MiniMax-H3-Max") 5..15 else 4..15

        internal fun fromJson(json: JSONObject): VideoGenerationOptions = VideoGenerationOptions(
            resolution = json.opt("resolution") as? String ?: "768P",
            duration = (json.opt("duration") as? Number)?.let {
                it.toInt().takeIf { value -> value.toDouble() == it.toDouble() }
            } ?: 5,
            ratio = json.opt("ratio") as? String ?: "16:9",
        )
    }
}

/** Provider and model are length-delimited, avoiding collisions even with arbitrary IDs. */
internal object VideoGenerationStore {
    private fun key(providerId: String, model: String) = "${providerId.length}:$providerId$model"

    fun load(context: Context, providerId: String, model: String): VideoGenerationOptions = runCatching {
        val raw = context.getSharedPreferences("video_generation_settings", Context.MODE_PRIVATE)
            .getString(key(providerId, model), null) ?: return VideoGenerationOptions().forModel(model)
        VideoGenerationOptions.fromJson(JSONObject(raw)).forModel(model)
    }.getOrElse { VideoGenerationOptions().forModel(model) }

    fun save(context: Context, providerId: String, model: String, options: VideoGenerationOptions) {
        context.getSharedPreferences("video_generation_settings", Context.MODE_PRIVATE).edit()
            .putString(key(providerId, model), options.forModel(model).toJson().toString()).apply()
    }
}
