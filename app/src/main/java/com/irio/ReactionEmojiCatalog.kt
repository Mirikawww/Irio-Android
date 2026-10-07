package com.irio

import org.json.JSONObject

internal data class ReactionEmojiCategory(val name: String, val emojis: List<String>)

/** Offline Unicode catalog, shared by the picker and reaction validation. */
internal object ReactionEmojiCatalog {
    private val bundledJson: String by lazy {
        checkNotNull(ReactionEmojiCatalog::class.java.getResourceAsStream("/emoji-reactions.json")) {
            "Bundled reaction emoji catalog is missing"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    val categories: List<ReactionEmojiCategory> by lazy { parse(bundledJson) }

    private val canonicalByCodePoints: Map<String, String> by lazy {
        val canonical = categories.flatMap { it.emojis }.associateBy(::defaultYellowKey)
        // Some official mixed-tone sequences use expanded ZWJ forms, whereas their
        // yellow counterparts are legacy single-code-point emoji (e.g. handshake).
        val aliases = JSONObject(bundledJson).getJSONObject("defaultYellowAliases")
        canonical.toMutableMap().apply {
            val keys = aliases.keys()
            while (keys.hasNext()) {
                val alias = keys.next()
                val target = aliases.getString(alias)
                check(canonical[defaultYellowKey(target)] == target) {
                    "Default-yellow alias must resolve to a catalog emoji"
                }
                put(defaultYellowKey(alias), target)
            }
        }
    }

    /** Accept one catalog emoji; restore emoji presentation and remove skin tones. */
    fun normalize(emoji: String): String? = canonicalByCodePoints[defaultYellowKey(emoji)]

    private fun defaultYellowKey(emoji: String): String = buildString {
        var offset = 0
        while (offset < emoji.length) {
            val codePoint = Character.codePointAt(emoji, offset)
            if (codePoint != 0xFE0F && codePoint !in 0x1F3FB..0x1F3FF) {
                appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
    }

    fun parse(json: String): List<ReactionEmojiCategory> {
        val categories = JSONObject(json).getJSONArray("categories")
        return List(categories.length()) { categoryIndex ->
            val category = categories.getJSONObject(categoryIndex)
            val emojis = category.getJSONArray("emojis")
            ReactionEmojiCategory(
                name = category.getString("name"),
                emojis = List(emojis.length()) { emojis.getString(it) }
            )
        }
    }
}
