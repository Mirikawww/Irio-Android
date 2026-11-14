package com.irio

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ToolGuideLanguageTest {
    @Test fun paginationAndRankingStayStableWhenLanguageChangesBetweenPages() {
        val app = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(app)
        val originalLanguage = ThemeStore.language
        val index = ToolGuideIndex.load(app)
        try {
            ThemeStore.saveLanguage(app, AppLanguage.EN)
            val first = JSONObject(ToolGuideIndex.search(index, "tool", limit = 1))
            assertTrue(first.getBoolean("hasMore"))
            val next = first.getInt("nextOffset")
            val englishSecond = JSONObject(ToolGuideIndex.search(index, "tool", offset = next, limit = 1))
            ThemeStore.saveLanguage(app, AppLanguage.ZH_CN)
            val chineseFirst = JSONObject(ToolGuideIndex.search(index, "tool", limit = 1))
            val chineseSecond = JSONObject(ToolGuideIndex.search(index, "tool", offset = next, limit = 1))
            assertEquals(first.getInt("total"), chineseFirst.getInt("total"))
            assertEquals(next, chineseFirst.getInt("nextOffset"))
            for ((en, zh) in listOf(first to chineseFirst, englishSecond to chineseSecond)) {
                val a = en.getJSONArray("results").getJSONObject(0)
                val b = zh.getJSONArray("results").getJSONObject(0)
                assertEquals(a.getString("guideId"), b.getString("guideId"))
                assertEquals(a.getInt("score"), b.getInt("score"))
                assertNotEquals(a.getString("content"), b.getString("content"))
            }
        } finally { ThemeStore.saveLanguage(app, originalLanguage) }
    }

    @Test fun sameIndexAndProviderReturnEveryTutorialInTheLiveAppLanguage() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(app)
        val originalLanguage = ThemeStore.language
        val index = ToolGuideIndex.load(app)
        val provider = ToolGuideProvider(index)
        val han = Regex("[\\p{IsHan}]")
        val englishPages = mutableMapOf<String, String>()
        try {
            for (language in listOf(AppLanguage.EN, AppLanguage.ZH_CN, AppLanguage.EN)) {
                ThemeStore.saveLanguage(app, language)
                for (guide in index.guides) {
                    val response = provider.execute(ChatToolCall("guide", ToolGuideSearchTool.NAME,
                        JSONObject().put("query", guide.tool).toString()),
                        ToolExecutionContext(ProviderConfig("fixture", "Fixture", "https://example.test", ""), "fixture"))
                    assertEquals(ToolOutcome.SUCCESS, response.outcome)
                    val result = JSONObject(response.text).getJSONArray("results").getJSONObject(0)
                    assertEquals(guide.id, result.getString("guideId"))
                    assertEquals(guide.tool, result.getString("tool"))
                    assertEquals(guide.pages.size, result.getInt("pages"))
                    assertEquals(language == AppLanguage.ZH_CN, han.containsMatchIn(result.getString("title")))
                    assertEquals(language == AppLanguage.ZH_CN, han.containsMatchIn(result.getString("content")))
                    assertEquals(tr(guide.title), result.getString("title"))
                    assertEquals(guide.pages.joinToString("\n\n") { tr(it.content) }, result.getString("content"))
                    assertTrue(result.getString("content").length <= ToolGuideIndex.MAX_CHUNK_CHARS)
                    if (language == AppLanguage.EN) {
                        englishPages[guide.id]?.let { assertEquals(it, result.getString("content")) }
                        englishPages[guide.id] = result.getString("content")
                    }
                }
                for ((query, tool) in listOf("查资料" to "web_search", "用户位置" to "get_location",
                    "current location" to "get_location", "Generate video" to "generate_video")) {
                    assertEquals(tool, JSONObject(ToolGuideIndex.search(index, query))
                        .getJSONArray("results").getJSONObject(0).getString("tool"))
                }
            }
        } finally { ThemeStore.saveLanguage(app, originalLanguage) }
    }
}
