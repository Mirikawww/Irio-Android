package com.irio

import android.content.Context
import org.json.JSONObject

/**
 * OpenRouter 目录：显示名与上下文窗口；思考能力仍来自离线能力数据。
 *
 * 文件由 scripts/generate-catalog.py 离线生成：
 *   model-names.json     id -> OpenRouter name（去掉 provider 前缀）
 *   model-context.json   id -> OpenRouter context_length
 *   model-reasoning.json id -> {supports, options(可省略)}
 * options 存在且含 "none" 表示思考可关闭；存在但为空数组表示支持思考且不可关闭；缺失即未知。
 */
object ModelCatalog {

    private val MERGE_SUFFIXES = listOf("-thinking", "-fast", "-reasoning", "-non-reasoning")

    /** 模型在 models.dev 里的思考能力。 */
    data class ReasoningInfo(
        /** 是否支持思考。 */
        val supports: Boolean,
        /** 能否关闭思考（reasoning_options 含 none）；拿不到 options 时为 null。 */
        val toggleable: Boolean?,
    )

    @Volatile
    private var names: Map<String, String>? = null

    @Volatile
    private var reasoning: Map<String, ReasoningInfo>? = null

    @Volatile
    private var imageModels: Set<String>? = null

    @Volatile
    private var contextWindows: Map<String, Int>? = null

    private fun loadNames(context: Context): Map<String, String> {
        return names ?: synchronized(this) {
            names ?: run {
                val loaded = runCatching {
                    val text = context.assets.open("model-names.json").bufferedReader().use { it.readText() }
                    val root = JSONObject(text)
                    buildMap {
                        for (key in root.keys()) {
                            val value = root.optString(key)
                            if (value.isNotBlank()) put(key, value)
                        }
                    }
                }.getOrDefault(emptyMap())
                names = loaded
                loaded
            }
        }
    }

    private fun loadReasoning(context: Context): Map<String, ReasoningInfo> {
        return reasoning ?: synchronized(this) {
            reasoning ?: run {
                val loaded = runCatching {
                    val text = context.assets.open("model-reasoning.json").bufferedReader().use { it.readText() }
                    parseReasoning(text)
                }.getOrDefault(emptyMap())
                reasoning = loaded
                loaded
            }
        }
    }

    private fun loadImageModels(context: Context): Set<String> {
        return imageModels ?: synchronized(this) {
            imageModels ?: run {
                val loaded = runCatching {
                    val text = context.assets.open("model-image.json").bufferedReader().use { it.readText() }
                    JSONObject(text).keys().asSequence().toSet()
                }.getOrDefault(emptySet())
                imageModels = loaded
                loaded
            }
        }
    }

    private fun loadContextWindows(context: Context): Map<String, Int> {
        return contextWindows ?: synchronized(this) {
            contextWindows ?: run {
                val loaded = runCatching {
                    val text = context.assets.open("model-context.json").bufferedReader().use { it.readText() }
                    val root = JSONObject(text)
                    buildMap {
                        for (key in root.keys()) {
                            val value = root.optInt(key, 0)
                            if (value > 0) put(key, value)
                        }
                    }
                }.getOrDefault(emptyMap())
                contextWindows = loaded
                loaded
            }
        }
    }

    /** OpenRouter `context_length`；目录未收录返回 0。 */
    fun contextWindow(context: Context, modelId: String): Int {
        if (modelId.isBlank()) return 0
        val catalog = loadContextWindows(context)
        return catalog[modelId]
            ?: catalog[modelId.lowercase()]
            ?: modelId.substringAfterLast('/').let { tail ->
                if (tail != modelId) catalog[tail] ?: catalog[tail.lowercase()] ?: 0 else 0
            }
    }

    fun isImageModel(context: Context, modelId: String): Boolean {
        if (modelId.isBlank()) return false
        val catalog = loadImageModels(context)
        return modelId in catalog ||
            modelId.lowercase() in catalog ||
            modelId.substringAfterLast('/').let { tail -> tail != modelId && (tail in catalog || tail.lowercase() in catalog) }
    }

    /** 从 json 文本解析；assets 不可用或出错时返回空表。 */
    fun parseReasoning(text: String): Map<String, ReasoningInfo> {
        return runCatching {
            val root = JSONObject(text)
            buildMap<String, ReasoningInfo> {
                for (key in root.keys()) {
                    val entry = root.optJSONObject(key) ?: continue
                    val supports = entry.optBoolean("supports", false)
                    val toggleable = if (!entry.has("options")) {
                        null
                    } else {
                        val options = entry.optJSONArray("options")
                        options != null && (0 until options.length()).any { options.optString(it) == "none" }
                    }
                    put(key, ReasoningInfo(supports, toggleable))
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** OpenRouter 显示名；开关关闭或没有数据时回退到格式化后的原始 id。 */
    fun displayName(context: Context, modelId: String, useCatalog: Boolean = true): String {
        if (modelId.isBlank()) return modelId
        if (useCatalog) {
            val catalog = loadNames(context)
            val hit = catalog[modelId]
                ?: catalog[modelId.lowercase()]
                ?: modelId.substringAfterLast('/').let { tail ->
                    if (tail != modelId) catalog[tail] ?: catalog[tail.lowercase()] else null
                }
            if (hit != null) return hit
        }
        return prettify(modelId)
    }

    /**
     * Models.dev 思考数据；查不到（目录无记录或开关关闭）返回 null。
     */
    fun reasoningInfo(context: Context, modelId: String, useCatalog: Boolean): ReasoningInfo? {
        if (!useCatalog || modelId.isBlank()) return null
        val catalog = loadReasoning(context)
        return catalog[modelId]
            ?: catalog[modelId.lowercase()]
            ?: modelId.substringAfterLast('/').let { tail ->
                if (tail != modelId) catalog[tail] ?: catalog[tail.lowercase()] else null
            }
    }

    // 只放真正的缩写（全大写官方形）；品牌驼峰名（Llama/DeepSeek/Qwen/Gemma…）不进表，prettify 保留首字母大写。
    private val prettifyAcronyms = listOf(
        "gpt", "oss", "glm", "moe", "lora", "sdxl", "abab",
    )

    private val prettifyOverrides = mapOf(
        "gpt-5.6" to "GPT-5.6",
        "gpt-5.3-codex-spark" to "GPT-5.3-Codex-Spark",
    )

    private fun prettify(modelId: String): String {
        val cleaned = modelId.substringAfterLast('/')
        prettifyOverrides[cleaned.lowercase()]?.let { return it }
        // 纯数字段之间的 '-' 视为版本号点（4-20 -> 4.20），右侧限 1~2 位（0309、8b 这类不算版本）
        val versionJoined = Regex("(?<=\\d)-(?=\\d\\d?(?!\\w))").replace(cleaned, ".")
        return versionJoined.split('-', '_').joinToString(" ") { part ->
            when {
                part.isEmpty() -> part
                part.all(Char::isDigit) -> part
                part.any(Char::isLetter) && part.any(Char::isDigit) && part.any(Char::isUpperCase) -> part
                part.length <= 2 || part.any(Char::isUpperCase) -> part
                prettifyAcronyms.contains(part.lowercase()) -> part.uppercase()
                part.contains('.') -> {
                    val head = part.substringBefore('.')
                    if (part.all { it.isDigit() || it == '.' } || head.any { it.isDigit() }) part
                    else part.replaceFirstChar { it.uppercase() }
                }
                else -> part.replaceFirstChar { it.uppercase() }
            }
        }
    }

    /** 组内变体的综合思考能力；任一变体支持返回支持，toggleable 按支持变体中最宽松的。 */
    fun mergedReasoning(infos: List<ReasoningInfo>): ReasoningInfo? {
        if (infos.isEmpty()) return null
        val supportsAny = infos.any { it.supports }
        // 所有支持变体均不可关闭 → 整体不可关闭；任一支持变体可关取 true；完全没有 supports 变体时按「不支持」处理
        val supportedInfos = infos.filter { it.supports }
        val toggleable: Boolean? = when {
            supportedInfos.isEmpty() -> null
            supportedInfos.any { it.toggleable == true } -> true
            supportedInfos.all { it.toggleable == false } -> false
            else -> null
        }
        return ReasoningInfo(supportsAny, toggleable)
    }

    /** 合并键：剥掉 -thinking / -fast / -reasoning / -non-reasoning 后缀；关闭合并时等于原始 id。 */
    fun mergeKey(modelId: String, mergeEnabled: Boolean): String {
        if (!mergeEnabled) return modelId
        var key = modelId
        // 长后缀优先：-non-reasoning 必须先于 -reasoning 剥离
        MERGE_SUFFIXES.sortedByDescending { it.length }.forEach { suffix ->
            if (key.endsWith(suffix)) key = key.removeSuffix(suffix)
        }
        return key
    }

    /** 合并组内被勾选/请求时实际使用的模型：优先 thinking/reasoning，其次 fast，再取首个。 */
    fun canonicalModel(variants: List<String>): String {
        return variants.firstOrNull { it.endsWith("-thinking") || it.endsWith("-reasoning") }
            ?: variants.firstOrNull { it.endsWith("-fast") }
            ?: variants.first()
    }
}
