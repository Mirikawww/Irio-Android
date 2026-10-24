package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("👯", ReactionEmojiCatalog.normalize("🧑🏻‍🐰‍🧑🏿"))
        assertEquals("👯‍♂️", ReactionEmojiCatalog.normalize("👨🏻‍🐰‍👨🏿"))
        assertEquals("👯‍♀️", ReactionEmojiCatalog.normalize("👩🏻‍🐰‍👩🏿"))
        assertEquals("🤼", ReactionEmojiCatalog.normalize("🧑🏻‍🫯‍🧑🏿"))
        assertEquals("🤼‍♂️", ReactionEmojiCatalog.normalize("👨🏻‍🫯‍👨🏿"))
        assertEquals("🤼‍♀️", ReactionEmojiCatalog.normalize("👩🏻‍🫯‍👩🏿"))
        assertEquals("🧑‍🩰", ReactionEmojiCatalog.normalize("🧑🏿‍🩰"))
    }

    @Test
    fun bundledCatalogLoadsWithoutAndroidContext() {
        assertEquals(9, ReactionEmojiCatalog.categories.size)
        assertEquals(1914, ReactionEmojiCatalog.categories.sumOf { it.emojis.size })
    }

    @Test
    fun bundledDataRecordsPinnedOfficialUnicodeProvenance() {
        val json = ReactionEmojiCatalog::class.java.getResourceAsStream("/emoji-reactions.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val data = JSONObject(json)
        assertEquals("17.0", data.getString("unicodeVersion"))
        assertEquals("https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt", data.getString("source"))
        assertEquals("1d8a944f88d7952f7ef7c5167fef3c67995bcae24543949710231b03a201acda", data.getString("sourceSha256"))
        assertEquals("https://www.unicode.org/license.txt", data.getString("license"))
        assertEquals(ReactionEmojiCatalog.categories, ReactionEmojiCatalog.parse(json))
    }

    @Test
    fun bundledCategoriesUseStandardUnicodeGroupOrderAndContainNoComponents() {
        assertEquals(
            listOf("Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink", "Travel & Places", "Activities", "Objects", "Symbols", "Flags"),
            ReactionEmojiCatalog.categories.map { it.name }
        )
        assertEquals(listOf(171, 388, 160, 131, 219, 85, 266, 224, 270), ReactionEmojiCatalog.categories.map { it.emojis.size })
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
        assertEquals(12, aliases.length())
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
    fun unicode17AdditionsAreAvailableAndNonRgiSequencesRemainUnavailable() {
        val catalog = ReactionEmojiCatalog.categories.flatMap { it.emojis }
        val unicode17 = listOf(
            String(Character.toChars(0x1FAEA)), // distorted face
            String(Character.toChars(0x1FAEF)), // fight cloud
            String(Character.toChars(0x1FAC8)), // hairy creature
            String(Character.toChars(0x1F9D1)) + "\u200D" + String(Character.toChars(0x1FA70)), // ballet dancer
            String(Character.toChars(0x1FACD)), // orca
            String(Character.toChars(0x1F6D8)), // landslide
            String(Character.toChars(0x1FA8A)), // trombone
            String(Character.toChars(0x1FA8E)), // treasure chest
        )
        unicode17.forEach {
            assertTrue(it in catalog)
            assertEquals(it, ReactionEmojiCatalog.normalize(it))
        }
        val retained = listOf(0x1FAE8, 0x1FA77, 0x1FA75, 0x1FAF7, 0x1FAF8, 0x1FADC)
            .map { String(Character.toChars(it)) }
        retained.forEach {
            assertTrue(it in catalog)
            assertEquals(it, ReactionEmojiCatalog.normalize(it))
        }
        val distortedFace = String(Character.toChars(0x1FAEA))
        val nonRgi = listOf(distortedFace + distortedFace, distortedFace + "\u200D" + distortedFace)
        nonRgi.forEach {
            assertFalse(it in catalog)
            assertNull(it, ReactionEmojiCatalog.normalize(it))
        }
    }
}
