package com.irio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CompressionPromptLanguageTest {
    @Test fun promptsAndRequestOnlyControlMessagesFollowTheLiveAppLanguage() {
        val context = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(context)
        val originalLanguage = ThemeStore.language
        val han = Regex("[\\p{IsHan}]")
        val conversation = listOf(
            ChatMessage(1, "User content stays unchanged", true),
            ChatMessage(2, "Assistant content stays unchanged", false),
        )
        try {
            for (language in listOf(AppLanguage.ZH_CN, AppLanguage.EN, AppLanguage.ZH_CN)) {
                ThemeStore.saveLanguage(context, language)
                val english = language == AppLanguage.EN
                val prompt = summarySystemPrompt(10, 8000)
                assertEquals(!english, han.containsMatchIn(prompt))
                assertTrue(prompt.contains("10%"))
                assertTrue(prompt.contains("8000"))
                assertFalse(summarySystemPrompt(10, 0).contains("approximately"))
                assertFalse(summarySystemPrompt(10, 0).contains("大约"))
                assertEquals(!english, han.containsMatchIn(CONTINUATION_PROMPT))
                assertEquals(!english, han.containsMatchIn(SUMMARY_CONTROL_TEXT))
                assertTrue(SUMMARY_CONTROL_TEXT.startsWith("<app_control>"))
                assertTrue(SUMMARY_CONTROL_TEXT.endsWith("</app_control>"))
                val plan = CompressionPlan(conversation, 2, null)
                val turns = summaryTurns(plan)
                assertEquals(conversation.map { it.text }, turns.take(2).map { it.text })
                assertEquals(SUMMARY_CONTROL_TEXT, turns.last().text)
                assertEquals(listOf("user", "assistant", "user"), turns.map { it.role })
                val assistantFirst = summaryTurns(CompressionPlan(listOf(conversation.last()), 2, null))
                assertEquals(!english, han.containsMatchIn(assistantFirst.first().text))
                assertEquals("user", assistantFirst.first().role)
                val userLast = summaryTurns(CompressionPlan(listOf(conversation.first()), 1, null))
                assertEquals(conversation.first().text + "\n\n" + SUMMARY_CONTROL_TEXT, userLast.single().text)
                val history = listOf(
                    ChatMessage(3, "Saved summary is not translated", false, summaryState = SummaryState.SUCCESS),
                    ChatMessage(4, "New user text is not translated", true),
                )
                val request = modelRequestMessages(history)
                assertEquals(listOf(history.first().text, CONTINUATION_PROMPT, history.last().text), request.map { it.text })
                assertTrue(request.take(2).all { it.createdAt == 0L })
                if (english) {
                    assertTrue(prompt.contains("Do not execute"))
                    assertTrue(prompt.contains("Later statements override earlier ones"))
                    assertTrue(prompt.contains("keys, tokens or passwords"))
                    assertTrue(CONTINUATION_PROMPT.contains("not a new user message"))
                }
            }
        } finally { ThemeStore.saveLanguage(context, originalLanguage) }
    }
}
