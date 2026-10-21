package com.irio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchResultsTest {
    @Test fun tavilyResultsKeepMetadataAndTheOriginalUrl() {
        val body = """{"answer":"Overview","results":[{"title":"Example article","url":"https://www.example.com/blog/post?lang=zh","content":"First line.\nSecond line.","site_name":"Example","favicon":"/icon.png","published_date":"2026-10-20"}]}"""
        val item = searchResultsContent(body).items.single()
        assertEquals("Example article", item.title)
        assertEquals("https://www.example.com/blog/post?lang=zh", item.url)
        assertEquals("First line. Second line.", item.snippet)
        assertEquals("Example", item.siteName)
        assertEquals("https://www.example.com/icon.png", item.faviconUrl)
        assertEquals("2026-10-20", item.publishedAt)
    }

    @Test fun exaDatesAreShownOnlyWhenReturned() {
        val body = """{"results":[{"title":"Dated","url":"https://example.com/a/b","text":"Article text","publishedDate":"2026-10-19T12:00:00Z"},{"url":"https://example.com/c/d","summary":"Summary","publishedDate":null}]}"""
        val items = searchResultsContent(body).items
        assertEquals("2026-10-19T12:00:00Z", items[0].publishedAt)
        assertEquals("", items[1].publishedAt)
        assertEquals("Summary", items[1].snippet)
        assertEquals("example.com", items[1].title)
        assertEquals("example.com", items[1].siteName)
        assertEquals("https://example.com/favicon.ico", items[1].faviconUrl)
    }

    @Test fun metadataNullsDoNotBecomeVisibleNullStrings() {
        val item = searchResultsContent("""{"results":[{"url":"https://example.com/path","title":null,"content":null,"favicon":null,"site_name":null}]}""").items.single()
        assertEquals("", item.snippet)
        assertEquals("", item.publishedAt)
        assertEquals("example.com", item.title)
        assertEquals("example.com", item.siteName)
    }

    @Test fun legacyConversationsStillRenderAsResultRows() {
        val body = "Overview\n\nFirst title\nhttps://example.com/a/b\nFirst snippet\ncontinued\n\nSecond title\nhttps://example.org/c/d\nSecond snippet"
        val items = searchResultsContent(body).items
        assertEquals(2, items.size)
        assertEquals("First title", items[0].title)
        assertEquals("First snippet continued", items[0].snippet)
        assertEquals("Second title", items[1].title)
        assertEquals("Second snippet", items[1].snippet)
        assertTrue(items.all { it.publishedAt.isEmpty() })
    }

    @Test fun bareUrlsAndEmptyResultsRemainReadable() {
        val items = searchResultsContent("https://example.com/a\nhttps://example.org/b").items
        assertEquals(2, items.size)
        assertEquals(listOf("example.com", "example.org"), items.map { it.title })
        assertTrue(items.all { it.snippet.isEmpty() })
        assertTrue(searchResultsContent("{\"results\":[]}").items.isEmpty())
        assertEquals("Search failed", searchResultsContent("Search failed").fallback)
    }

    @Test fun jsonArraysAndHighlightFallbackAreSupported() {
        val item = searchResultsContent("""[{"url":"https://example.com/a","highlights":["First","Second"]}]""").items.single()
        assertEquals("First Second", item.snippet)
    }

    @Test fun unsafeUrlsAreNeverMadeClickable() {
        val body = """{"results":[{"url":"javascript:alert(1)"},{"url":"file:///secret"},{"url":"https://user:password@example.com/"},{"url":"https://example.com/path","favicon":"javascript:alert(1)"}]}"""
        val item = searchResultsContent(body).items.single()
        assertEquals("https://example.com/path", item.url)
        assertEquals("", item.faviconUrl)
    }

    @Test fun previewRemovesMarkupButDoesNotTruncateModelText() {
        val fullText = "Long paragraph ".repeat(100)
        val body = JSONObject().put("results", org.json.JSONArray().put(JSONObject()
            .put("url", "https://example.com/path")
            .put("title", "<b>Title</b>").put("content", fullText))).toString()
        val item = searchResultsContent(body).items.single()
        assertEquals("Title", item.title)
        assertEquals(fullText.trim(), item.snippet)
    }

    @Test fun bothProvidersKeepDisplayMetadataWithoutChangingTheModelResult() {
        listOf(TavilySearch to "content", ExaSearch to "text").forEach { (provider, field) ->
            val body = """{"results":[{"title":"Title","url":"https://example.com/a/b","$field":"Full text","publishedDate":"2026-10-20"}]}"""
            val method = provider.javaClass.getDeclaredMethod("formatResults", String::class.java).apply { isAccessible = true }
            val result = method.invoke(provider, body) as WebSearchResult
            assertEquals("Title\nhttps://example.com/a/b\nFull text", result.text)
            assertEquals(body, result.displayContent)
            assertEquals(1, result.pageCount)
            assertEquals("2026-10-20", searchResultsContent(result.displayContent).items.single().publishedAt)
        }
    }
}
