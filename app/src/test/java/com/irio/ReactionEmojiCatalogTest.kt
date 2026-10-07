package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionEmojiCatalogTest {
    @Test
    fun normalizationUsesCanonicalDefaultYellowCatalogMembership() {
        assertEquals("❤️", ReactionEmojiCatalog.normalize("❤"))
        assertEquals("👍", ReactionEmojiCatalog.normalize("👍🏽"))
        assertEquals("👩‍⚕️", ReactionEmojiCatalog.normalize("👩🏿‍⚕"))
        assertEquals("👨‍👩‍👧‍👦", ReactionEmojiCatalog.normalize("👨‍👩‍👧‍👦"))
        assertEquals("🇨🇳", ReactionEmojiCatalog.normalize("🇨🇳"))
        assertEquals("1️⃣", ReactionEmojiCatalog.normalize("1⃣"))
        assertEquals("🏳️‍🌈", ReactionEmojiCatalog.normalize("🏳‍🌈"))
        listOf("", "hello", "😀😀", "❤❤", "hello 😀", " 😀", "😀 ", "🏽", "🦰", "🇨", "1", "\uFE0F").forEach {
            assertNull(it, ReactionEmojiCatalog.normalize(it))
        }
    }

    @Test
    fun mixedToneCompoundEmojiNormalizeToTheirOfficialYellowBase() {
        assertEquals("🤝", ReactionEmojiCatalog.normalize("🫱🏻‍🫲🏿"))
        assertEquals("👭", ReactionEmojiCatalog.normalize("👩🏻‍🤝‍👩🏿"))
        assertEquals("💏", ReactionEmojiCatalog.normalize("🧑🏻‍❤️‍💋‍🧑🏿"))
        assertEquals("💑", ReactionEmojiCatalog.normalize("🧑🏻‍❤️‍🧑🏿"))
    }

    @Test
    fun bundledCatalogLoadsWithoutAndroidContext() {
        assertEquals(9, ReactionEmojiCatalog.categories.size)
        assertEquals(1906, ReactionEmojiCatalog.categories.sumOf { it.emojis.size })
    }

    @Test
    fun bundledDataRecordsPinnedOfficialUnicodeProvenance() {
        val json = ReactionEmojiCatalog::class.java.getResourceAsStream("/emoji-reactions.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val data = JSONObject(json)
        assertEquals("16.0", data.getString("unicodeVersion"))
        assertEquals("https://www.unicode.org/Public/emoji/16.0/emoji-test.txt", data.getString("source"))
        assertEquals("24f0c534e86cf142e2496953e8f0e46a3e702392911eddcd29c6cced85139697", data.getString("sourceSha256"))
        assertEquals("https://www.unicode.org/license.txt", data.getString("license"))
        assertEquals(ReactionEmojiCatalog.categories, ReactionEmojiCatalog.parse(json))
    }

    @Test
    fun bundledCategoriesUseStandardUnicodeGroupOrderAndContainNoComponents() {
        assertEquals(
            listOf("Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink", "Travel & Places", "Activities", "Objects", "Symbols", "Flags"),
            ReactionEmojiCatalog.categories.map { it.name }
        )
        assertEquals(listOf(169, 386, 159, 131, 218, 85, 264, 224, 270), ReactionEmojiCatalog.categories.map { it.emojis.size })
        val emojis = ReactionEmojiCatalog.categories.flatMap { it.emojis }
        assertEquals(emojis.size, emojis.toSet().size)
        emojis.forEach { emoji ->
            assertFalse(emoji, emoji.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF })
            assertTrue(emoji, emoji.isNotEmpty())
        }
        listOf("🦰", "🦱", "🦳", "🦲", "🏻", "🏼", "🏽", "🏾", "🏿").forEach { assertFalse(it in emojis) }
    }

    @Test
    fun everyBundledEmojiAndItsUnqualifiedFormRoundTrip() {
        ReactionEmojiCatalog.categories.flatMap { it.emojis }.forEach { emoji ->
            assertEquals(emoji, emoji, ReactionEmojiCatalog.normalize(emoji))
            assertEquals(emoji, emoji, ReactionEmojiCatalog.normalize(emoji.replace("\uFE0F", "")))
        }
        val england = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        assertEquals(england, ReactionEmojiCatalog.normalize(england))
        assertEquals("*️⃣", ReactionEmojiCatalog.normalize("*⃣"))
        assertEquals("#️⃣", ReactionEmojiCatalog.normalize("#⃣"))
    }

    @Test
    fun everyExpandedDefaultYellowAliasResolvesToACatalogEmoji() {
        val json = ReactionEmojiCatalog::class.java.getResourceAsStream("/emoji-reactions.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val aliases = JSONObject(json).getJSONObject("defaultYellowAliases")
        val keys = aliases.keys()
        assertEquals(6, aliases.length())
        while (keys.hasNext()) {
            val key = keys.next()
            val expected = aliases.getString(key)
            assertEquals(expected, ReactionEmojiCatalog.normalize(key))
            assertEquals(expected, ReactionEmojiCatalog.normalize(expected))
        }
    }

    @Test
    fun parsePreservesCategoryAndEmojiOrder() {
        val json = """{"categories":[{"name":"Smileys & Emotion","emojis":["😀","❤️"]},{"name":"Flags","emojis":["🇨🇳"]}]}"""
        assertEquals(
            listOf(
                ReactionEmojiCategory("Smileys & Emotion", listOf("😀", "❤️")),
                ReactionEmojiCategory("Flags", listOf("🇨🇳"))
            ),
            ReactionEmojiCatalog.parse(json)
        )
    }

    @Test
    fun unicode17AdditionsAreUnavailableInPickerAndModelReactions() {
        val catalog = ReactionEmojiCatalog.categories.flatMap { it.emojis }
        listOf("🫪", "🫯", "🫈", "🧑‍🩰", "🫍", "🛘", "🪊", "🪎").forEach {
            assertFalse(it in catalog)
            assertNull(it, ReactionEmojiCatalog.normalize(it))
        }
        assertNull(ReactionEmojiCatalog.normalize("👨🏻‍🫯‍👨🏿"))
    }
}
