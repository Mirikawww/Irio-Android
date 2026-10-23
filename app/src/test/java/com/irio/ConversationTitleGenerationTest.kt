package com.irio

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ConversationTitleGenerationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun conversation(): Conversation = ConversationStore.newConversation().copy(
        title = "Original title", titlePending = false, updatedAt = 123,
        messages = listOf(
            ChatMessage(1, "How do rainbows form?", fromUser = true, retryGroup = 1),
            ChatMessage(2, "Light refracts through water droplets.", fromUser = false, retryGroup = 2),
        ),
    )

    @Test fun inputUsesSelectedVisibleTurnAndSkipsErrorsToolsAndCompression() {
        val input = conversationTitleInput(listOf(
            ChatMessage(1, "hidden tool prompt", fromUser = true, hidden = true),
            ChatMessage(2, "old edit", fromUser = true, selected = false, editGroup = 2),
            ChatMessage(3, "old reply", fromUser = false, responseToUserId = 2),
            ChatMessage(4, "selected user", fromUser = true, editGroup = 2),
            ChatMessage(5, "error", fromUser = false, isError = true),
            ChatMessage(6, "discarded reply", fromUser = false, selected = false, retryGroup = 6),
            ChatMessage(7, "selected reply", fromUser = false, retryGroup = 6),
            ChatMessage(8, "summary", fromUser = false, summaryState = SummaryState.SUCCESS),
        ))
        assertEquals(ConversationTitleInput("selected user", "selected reply"), input)
    }

    @Test fun inputSupportsAttachmentOnlyAndUnansweredConversations() {
        val image = ChatAttachment("rainbow.png", "image/png", "/fixture.png")
        assertEquals(ConversationTitleInput("rainbow.png", ""), conversationTitleInput(listOf(
            ChatMessage(1, "", fromUser = true, attachments = listOf(image)),
            ChatMessage(2, "incomplete", fromUser = false, isStreaming = true),
        )))
        assertNull(conversationTitleInput(emptyList()))
        assertNull(conversationTitleInput(listOf(ChatMessage(1, "", fromUser = true))))
    }

    @Test fun inputDoesNotBorrowAReplyFromADifferentUserTurn() {
        assertEquals(ConversationTitleInput("first unanswered question", ""), conversationTitleInput(listOf(
            ChatMessage(1, "first unanswered question", fromUser = true),
            ChatMessage(2, "later question", fromUser = true),
            ChatMessage(3, "later reply", fromUser = false),
        )))
    }

    @Test fun oneRequestPerConversationAndDeletionInvalidatesTheOldToken() {
        val state = ConversationTitleGenerationState()
        val first = state.begin("first")!!
        assertTrue(state.isRunning("first"))
        assertNull(state.begin("first"))
        assertNotNull(state.begin("other"))
        state.cancel("first")
        assertFalse(state.isCurrent("first", first))
        val newer = state.begin("first")!!
        state.finish("first", first)
        assertTrue(state.isCurrent("first", newer))
        state.finish("first", newer)
        assertFalse(state.isRunning("first"))
        state.clear()
        assertFalse(state.isRunning("other"))
    }

    @Test fun staleTitleResultsCannotReplaceAnotherTitleOrConversation() {
        val original = conversation().summary()
        val request = ConversationTitleRequest(original)
        assertTrue(request.accepts(original.copy(pinned = true, updatedAt = 999)))
        assertFalse(request.accepts(original.copy(title = "Newer manual title")))
        assertFalse(request.accepts(original.copy(id = "other")))
        assertFalse(request.accepts(original.copy(titlePending = true)))
        val pending = original.copy(titlePending = true)
        assertTrue(ConversationTitleRequest(pending).accepts(pending.copy(title = "Updated temporary preview")))
        assertFalse(ConversationTitleRequest(pending).accepts(pending.copy(titlePending = false)))
    }

    @Test fun generatedTitlePatchesLatestBodyWithoutChangingMessagesPinUsageOrOrder() {
        val original = conversation()
        ConversationStore.save(context, original)
        val newer = original.copy(
            pinned = true, updatedAt = 456,
            messages = original.messages + ChatMessage(3, "A new message arrived", fromUser = true, retryGroup = 3),
            tokenUsage = TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30),
        )
        ConversationStore.save(context, newer)
        ConversationStore.updateGeneratedTitle(context, ConversationTitleRequest(original.summary()), "Rainbow formation")
        val expected = newer.copy(title = "Rainbow formation", titlePending = false)
        assertEquals(expected, ConversationStore.load(context, original.id))
        assertEquals(expected.summary(), ConversationStore.loadAll(context).single { it.id == original.id })
    }

    @Test fun generatedTitleDoesNotReviveDeletedConversations() {
        val original = conversation()
        ConversationStore.save(context, original)
        ConversationStore.delete(context, original.id)
        ConversationStore.updateGeneratedTitle(context, ConversationTitleRequest(original.summary()), "Late result")
        assertNull(ConversationStore.load(context, original.id))
        assertFalse(ConversationStore.loadAll(context).any { it.id == original.id })
    }

    @Test fun blankOrStaleResultsKeepTheExistingTitleAndMessages() {
        val original = conversation()
        ConversationStore.save(context, original)
        val request = ConversationTitleRequest(original.summary())
        ConversationStore.updateGeneratedTitle(context, request, "   ")
        assertEquals(original, ConversationStore.load(context, original.id))
        val renamed = original.copy(title = "A newer title")
        ConversationStore.save(context, renamed)
        ConversationStore.updateGeneratedTitle(context, request, "Stale generated title")
        assertEquals(renamed, ConversationStore.load(context, original.id))
    }

    @Test fun regenerationUsesTheExistingTitleApiAndPersistsItsResult() = runBlocking {
        val original = conversation()
        ConversationStore.save(context, original)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Rainbow formation"}}]}"""))
            val provider = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture")
            val input = conversationTitleInput(original.messages)!!
            val title = ChatApi.generateTitle(provider, "title-model", input.user, input.assistant, "${original.id}#title")
            assertEquals("Rainbow formation", title)
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("title-model", body.getString("model"))
            assertFalse(body.getBoolean("stream"))
            assertFalse(body.has("tools"))
            val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(prompt.contains(input.user))
            assertTrue(prompt.contains(input.assistant))
            ConversationStore.updateGeneratedTitle(context, ConversationTitleRequest(original.summary()), title!!)
            assertEquals(original.copy(title = title), ConversationStore.load(context, original.id))
        }
    }

    @Test fun apiFailureKeepsTheOriginalTitle() = runBlocking {
        val original = conversation()
        ConversationStore.save(context, original)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"fixture"}}"""))
            val provider = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture")
            assertNull(ChatApi.generateTitle(provider, "title-model", "question", "reply"))
            assertEquals(original, ConversationStore.load(context, original.id))
        }
    }
}
