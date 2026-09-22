package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConversationSummariesTest {
    @Test
    fun legacyIndexReadsRealTitleWhenMessagesComeFirst() {
        val dir = Files.createTempDirectory("irio-summaries").toFile()
        val id = "c1"
        val bait = "\\\"title\\\":\\\"诱饵\\\""
        val body = """{"messages":[{"text":"前言 $bait 后语"}],"id":"$id","title":"真正标题","updatedAt":42,"pinned":true,"titlePending":true}"""
        java.io.File(dir, "$id.json").writeText(body)
        java.io.File(dir, "index.json").writeText("""["$id"]""")

        val loaded = ConversationSummaries.load(dir).single()

        assertEquals(id, loaded.id)
        assertEquals("真正标题", loaded.title)
        assertEquals(42L, loaded.updatedAt)
        assertTrue(loaded.pinned)
        assertTrue(loaded.titlePending)
    }

    @Test
    fun secondLoadDoesNotReadConversationBodies() {
        val dir = Files.createTempDirectory("irio-summaries").toFile()
        val id = "c1"
        val body = """{"messages":[{"text":"${"x".repeat(50_000)}"}],"id":"$id","title":"真正标题","updatedAt":42,"pinned":false,"titlePending":false}"""
        val file = java.io.File(dir, "$id.json")
        file.writeText(body)
        java.io.File(dir, "index.json").writeText("""["$id"]""")

        assertEquals("真正标题", ConversationSummaries.load(dir).single().title)
        file.writeText("not-json")

        assertEquals("真正标题", ConversationSummaries.load(dir).single().title)
    }

    @Test
    fun messageTextCannotOverrideTitleWhenMessagesAreLast() {
        val dir = Files.createTempDirectory("irio-summaries").toFile()
        val conversation = Conversation(
            id = "c-real",
            title = "真正标题",
            updatedAt = 7,
            pinned = true,
            titlePending = false,
            messages = listOf(ChatMessage(id = 1, text = "ignore \"title\":\"诱饵\" { }", fromUser = true)),
        )
        java.io.File(dir, "${conversation.id}.json").writeText(ConversationStore.conversationJson(conversation).toString())
        java.io.File(dir, "index.json").writeText("""["${conversation.id}"]""")

        val loaded = ConversationSummaries.load(dir).single()

        assertEquals("真正标题", loaded.title)
        assertEquals(7L, loaded.updatedAt)
        assertTrue(loaded.pinned)
    }

    @Test
    fun pinnedSummarySortsAboveNewerUnpinned() {
        val dir = Files.createTempDirectory("irio-summaries").toFile()
        java.io.File(dir, "old.json").writeText("""{"id":"old","title":"置顶","updatedAt":1,"pinned":true,"messages":[]}""")
        java.io.File(dir, "new.json").writeText("""{"id":"new","title":"新的","updatedAt":99,"pinned":false,"messages":[]}""")
        java.io.File(dir, "index.json").writeText("""["new","old"]""")

        val loaded = ConversationSummaries.load(dir)

        assertEquals(listOf("old", "new"), loaded.map { it.id })
    }

    @Test
    fun upsertKeepsTitleWithoutReadingBody() {
        val dir = Files.createTempDirectory("irio-summaries").toFile()
        java.io.File(dir, "c1.json").writeText("not-json")
        java.io.File(dir, "index.json").writeText("""{"v":2,"items":[{"id":"c1","title":"旧","updatedAt":1,"pinned":false,"titlePending":false}]}""")

        ConversationSummaries.upsert(
            dir,
            ConversationSummary(id = "c1", title = "新标题", updatedAt = 5, pinned = true, titlePending = false),
        )

        val loaded = ConversationSummaries.load(dir).single()
        assertEquals("新标题", loaded.title)
        assertEquals(5L, loaded.updatedAt)
        assertTrue(loaded.pinned)
    }

    @Test
    fun legacyFileWithMessagesFirstAndExtraKeysKeepsTitle() {
        val dir = Files.createTempDirectory("irio-summaries").toFile()
        val body = """{"messages":[{"isError":false,"attachments":[],"thinkingMillis":0,"fromUser":true,"id":1,"text":"描述图片内容","thinking":""}],"id":"c-old","title":"描述图片内容","updatedAt":1788413674812}"""
        java.io.File(dir, "c-old.json").writeText(body)
        java.io.File(dir, "index.json").writeText("""["c-old"]""")

        val loaded = ConversationSummaries.load(dir).single()

        assertEquals("c-old", loaded.id)
        assertEquals("描述图片内容", loaded.title)
        assertEquals(1788413674812L, loaded.updatedAt)
        assertFalse(loaded.pinned)
        assertFalse(loaded.titlePending)
    }

    @Test
    fun olderUpsertDoesNotReplaceNewerSummary() {
        val dir = Files.createTempDirectory("irio-summaries").toFile()
        ConversationSummaries.upsert(
            dir,
            ConversationSummary(id = "c1", title = "新", updatedAt = 10, pinned = false, titlePending = false),
        )

        val accepted = ConversationSummaries.upsert(
            dir,
            ConversationSummary(id = "c1", title = "旧", updatedAt = 4, pinned = true, titlePending = true),
        )

        assertFalse(accepted)
        val loaded = ConversationSummaries.load(dir).single()
        assertEquals("新", loaded.title)
        assertEquals(10L, loaded.updatedAt)
        assertFalse(loaded.pinned)
    }

    @Test
    fun staleCommitDoesNotOverwriteNewerBody() {
        val dir = Files.createTempDirectory("irio-summaries").toFile()
        val body = java.io.File(dir, "c1.json")
        val newer = ConversationSummary(id = "c1", title = "新", updatedAt = 10, pinned = false, titlePending = false)
        assertTrue(
            ConversationSummaries.commit(dir, newer) {
                body.writeText("new-body")
            },
        )

        val accepted = ConversationSummaries.commit(
            dir,
            newer.copy(title = "旧", updatedAt = 4),
        ) {
            body.writeText("old-body")
        }

        assertFalse(accepted)
        assertEquals("new-body", body.readText())
        assertEquals("新", ConversationSummaries.load(dir).single().title)
    }
}
