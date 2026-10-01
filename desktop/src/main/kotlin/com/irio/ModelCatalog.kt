package com.irio

import org.json.JSONObject

/**
 * Models.dev 目录（desktop）：图片生成模型判定（resources/model-image.json）。
 *
 * 文件由 scripts 的目录生成流程从 https://models.dev/api.json 离线生成：
 *   model-image.json   id 集合（modalities.output 含 image 的模型）
 * 命中规则与 app 端 ModelCatalog.isImageModel 一致：精确 id → 全小写 → 去 vendor 前缀的尾段。
 */
object ModelCatalog {

    private val prettifyOverrides = mapOf(
        "gpt-5.6" to "GPT-5.6",
        "gpt-5.3-codex-spark" to "GPT-5.3-Codex-Spark",
    )

    @Volatile
    private var imageModels: Set<String>? = null

    @Volatile
    private var contextWindows: Map<String, Int>? = null

    private fun load(): Set<String> {
        return imageModels ?: synchronized(this) {
            imageModels ?: run {
                val loaded = runCatching {
                    val stream = ModelCatalog::class.java.classLoader
                        ?.getResourceAsStream("model-image.json")
                    stream?.bufferedReader()?.use { it.readText() }?.let { text ->
                        JSONObject(text).keys().asSequence().toSet()
                    } ?: emptySet()
                }.getOrDefault(emptySet())
                imageModels = loaded
                loaded
            }
        }
    }

    private fun loadContextWindows(): Map<String, Int> {
        return contextWindows ?: synchronized(this) {
            contextWindows ?: run {
                val loaded = runCatching {
                    val stream = ModelCatalog::class.java.classLoader
                        ?.getResourceAsStream("model-context.json")
                    stream?.bufferedReader()?.use { it.readText() }?.let { text ->
                        val root = JSONObject(text)
                        buildMap {
                            for (key in root.keys()) {
                                val value = root.optInt(key, 0)
                                if (value > 0) put(key, value)
                            }
                        }
                    } ?: emptyMap()
                }.getOrDefault(emptyMap())
                contextWindows = loaded
                loaded
            }
        }
    }

    /** models.dev `limit.context`；目录未收录返回 0。 */
    fun contextWindow(modelId: String): Int {
        if (modelId.isBlank()) return 0
        val catalog = loadContextWindows()
        val tail = modelId.substringAfterLast('/')
        return catalog[modelId]
            ?: catalog[modelId.lowercase()]
            ?: if (tail != modelId) catalog[tail] ?: catalog[tail.lowercase()] ?: 0 else 0
    }

    fun isImageModel(modelId: String): Boolean {
        if (modelId.isBlank()) return false
        val catalog = load()
        val tail = modelId.substringAfterLast('/')
        return modelId in catalog ||
            modelId.lowercase() in catalog ||
            (tail != modelId && (tail in catalog || tail.lowercase() in catalog))
    }

    fun displayName(modelId: String): String {
        val cleaned = modelId.substringAfterLast('/')
        prettifyOverrides[cleaned.lowercase()]?.let { return it }
        return cleaned.replace('-', ' ').replace('_', ' ')
    }
}
