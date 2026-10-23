package com.irio

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln

enum class ImageResolution(val label: String, val longEdge: Int) {
    K1("1K", 1024), K2("2K", 2048), K4("4K", 3840),
}

enum class ImageBackground(val label: String, val wire: String) {
    AUTO("自动", "auto"), OPAQUE("不透明", "opaque"), TRANSPARENT("透明", "transparent"),
}

enum class ImageQuality(val label: String, val wire: String) {
    LOW("低", "low"), MEDIUM("中", "medium"), HIGH("高", "high"),
}

data class ImageAspectRatio(val width: Int, val height: Int) {
    init {
        require(width in 1..10_000 && height in 1..10_000)
        require(width.toLong() * 3 >= height && height.toLong() * 3 >= width)
    }

    val label: String get() {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val divisor = gcd(width, height)
        return "${width / divisor}:${height / divisor}"
    }

    companion object {
        fun parse(text: String): ImageAspectRatio? = runCatching {
            val parts = text.trim().split(Regex("[:：xX×/]"))
            require(parts.size == 2)
            ImageAspectRatio(parts[0].trim().toInt(), parts[1].trim().toInt())
        }.getOrNull()
    }
}

internal val imageAspectRatioPresets = listOf("1:3", "9:16", "2:3", "3:4", "1:1", "4:3", "3:2", "16:9", "3:1")

data class ImageGenerationOptions(
    val resolution: ImageResolution = ImageResolution.K1,
    val aspectRatio: ImageAspectRatio? = null,
    val background: ImageBackground = ImageBackground.AUTO,
    val quality: ImageQuality = ImageQuality.MEDIUM,
    val useCustomAspectRatio: Boolean = aspectRatio != null && aspectRatio.label !in imageAspectRatioPresets,
    val customRatio: ImageAspectRatio = aspectRatio ?: ImageAspectRatio(1, 1),
) {
    /** Images API has one size field: automatic framing must not be replaced by a square. */
    fun openAiSize(): String {
        val ratio = aspectRatio ?: return "auto"
        val targetRatio = ratio.width.toDouble() / ratio.height
        var bestWidth = 1024
        var bestHeight = 1024
        var bestScore = Double.POSITIVE_INFINITY
        // Follow the documented GPT Image custom-size limits, including non-square 1K/4K cases.
        for (width in 16..3840 step 16) for (height in 16..3840 step 16) {
            val pixels = width * height
            if (pixels !in 655_360..8_294_400 || width * 3 < height || height * 3 < width) continue
            val score = abs(ln(width.toDouble() / height / targetRatio)) * 100.0 +
                abs(ln(maxOf(width, height).toDouble() / resolution.longEdge))
            if (score < bestScore) {
                bestScore = score
                bestWidth = width
                bestHeight = height
            }
        }
        return "${bestWidth}x$bestHeight"
    }

    fun openAiFields(): Map<String, String> = linkedMapOf(
        "size" to openAiSize(), "background" to background.wire, "quality" to quality.wire,
    ).apply { if (background == ImageBackground.TRANSPARENT) put("output_format", "png") }

    internal fun toJson(): JSONObject = JSONObject()
        .put("resolution", resolution.name)
        .put("aspectRatio", aspectRatio?.label.orEmpty())
        .put("background", background.wire)
        .put("quality", quality.wire)
        .put("useCustomAspectRatio", useCustomAspectRatio)
        .put("customWidth", customRatio.width)
        .put("customHeight", customRatio.height)

    companion object {
        /** Tool calls own their options; never consult the user's per-model menu preferences here. */
        internal fun fromToolArguments(arguments: String): ImageGenerationOptions {
            val json = JSONObject(arguments)
            fun value(name: String, fallback: String): String {
                if (!json.has(name)) return fallback // Older prompt-only calls remain executable.
                return (json.opt(name) as? String)?.trim()
                    ?: throw IllegalArgumentException("$name must be a string")
            }
            val resolution = ImageResolution.entries.firstOrNull { it.label == value("resolution", "1K").uppercase() }
                ?: throw IllegalArgumentException("resolution must be 1K, 2K or 4K")
            val aspect = value("aspect_ratio", "auto")
            val ratio = if (aspect.equals("auto", ignoreCase = true)) null else ImageAspectRatio.parse(aspect)
                ?: throw IllegalArgumentException("aspect_ratio must be auto or width:height between 1:3 and 3:1")
            val background = ImageBackground.entries.firstOrNull { it.wire == value("background", "auto").lowercase() }
                ?: throw IllegalArgumentException("background must be auto, opaque or transparent")
            val quality = ImageQuality.entries.firstOrNull { it.wire == value("quality", "medium").lowercase() }
                ?: throw IllegalArgumentException("quality must be low, medium or high")
            return ImageGenerationOptions(resolution, ratio, background, quality)
        }

        internal fun fromJson(json: JSONObject) = ImageGenerationOptions(
            resolution = ImageResolution.entries.firstOrNull { it.name == json.optString("resolution") } ?: ImageResolution.K1,
            aspectRatio = ImageAspectRatio.parse(json.optString("aspectRatio")),
            background = ImageBackground.entries.firstOrNull { it.wire == json.optString("background") } ?: ImageBackground.AUTO,
            quality = ImageQuality.entries.firstOrNull { it.wire == json.optString("quality") } ?: ImageQuality.MEDIUM,
            useCustomAspectRatio = json.optBoolean("useCustomAspectRatio",
                ImageAspectRatio.parse(json.optString("aspectRatio"))?.label?.let { it !in imageAspectRatioPresets } ?: false),
            customRatio = ImageAspectRatio.parse("${json.optInt("customWidth")}:${json.optInt("customHeight")}")
                ?: ImageAspectRatio.parse(json.optString("aspectRatio")) ?: ImageAspectRatio(1, 1),
        )
    }
}

/** Saved per provider/model; switching conversations never changes an in-flight request snapshot. */
internal object ImageGenerationStore {
    private fun key(providerId: String, model: String) = "${providerId.length}:$providerId$model"

    fun load(context: Context, providerId: String, model: String): ImageGenerationOptions = runCatching {
        val text = context.getSharedPreferences("image_generation_settings", Context.MODE_PRIVATE)
            .getString(key(providerId, model), null) ?: return ImageGenerationOptions()
        ImageGenerationOptions.fromJson(JSONObject(text))
    }.getOrDefault(ImageGenerationOptions())

    fun save(context: Context, providerId: String, model: String, options: ImageGenerationOptions) {
        context.getSharedPreferences("image_generation_settings", Context.MODE_PRIVATE).edit()
            .putString(key(providerId, model), options.toJson().toString()).apply()
    }
}
