package com.irio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class EnglishToolGuidesTest {
    @Test fun titlesAndPagesAreEnglishWithoutTruncationAndChineseAliasesRemainSearchable() {
        val bundled = ToolGuideIndex.load(RuntimeEnvironment.getApplication())
        val han = Regex("[\\p{IsHan}]")
        assertEquals(23, bundled.guides.size)
        assertEquals(41, bundled.guides.sumOf { it.pages.size })
        for (guide in bundled.guides) {
            assertFalse("Tutorial title must be English: ${guide.id}", han.containsMatchIn(guide.title))
            for (page in guide.pages) {
                assertFalse("Tutorial body must be English: ${guide.id}", han.containsMatchIn(page.content))
            }
            val content = guide.pages.joinToString("\n\n") { it.content }
            assertTrue("Tutorial must fit the existing retrieval budget: ${guide.id}",
                content.length <= ToolGuideIndex.MAX_CHUNK_CHARS)
            val result = JSONObject(ToolGuideIndex.search(bundled, guide.tool)).getJSONArray("results")
            assertEquals(guide.tool, result.getJSONObject(0).getString("tool"))
            assertEquals(content, result.getJSONObject(0).getString("content"))
        }
        assertEquals("web_search", JSONObject(ToolGuideIndex.search(bundled, "查资料"))
            .getJSONArray("results").getJSONObject(0).getString("tool"))
        assertEquals("get_location", JSONObject(ToolGuideIndex.search(bundled, "用户位置"))
            .getJSONArray("results").getJSONObject(0).getString("tool"))
    }
}
