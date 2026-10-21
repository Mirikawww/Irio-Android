package com.irio

import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TinyFishSearchTest {
    @Test fun searchUsesTheDocumentedGetEndpointAndCapsReturnedResultsLocally() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(searchResponse))
            server.start()
            val result = TinyFishSearch.search(
                apiKey = "tinyfish-secret",
                query = "Kotlin & Compose",
                maxResults = 2,
                endpoint = server.url("/").toString().removeSuffix("/"),
            )
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/", request.requestUrl!!.encodedPath)
            assertEquals("Kotlin & Compose", request.requestUrl!!.queryParameter("query"))
            assertEquals("tinyfish-secret", request.getHeader("X-API-Key"))
            assertNull(request.getHeader("Authorization"))
            assertTrue("TinyFish must reuse the app's shared HTTP transport", request.getHeader("User-Agent")!!.startsWith("okhttp/"))
            assertEquals(2, result.pageCount)
            assertTrue(result.text.contains("First result"))
            assertTrue(result.text.contains("Second result"))
            assertFalse(result.text.contains("Third result"))
            assertEquals(2, searchResultsContent(result.displayContent).items.size)
            assertEquals("2026-10-20", searchResultsContent(result.displayContent).items.first().publishedAt)
        }
    }

    @Test fun searchPreservesServerErrorsAndDoesNotSendBlankQueries() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}"""))
            server.start()
            val failed = TinyFishSearch.search("key", "query", endpoint = server.url("/").toString().removeSuffix("/"))
            assertEquals("Invalid API key", failed.text)
            assertEquals(1, server.requestCount)
            val missing = TinyFishSearch.search("key", "  ", endpoint = server.url("/").toString().removeSuffix("/"))
            assertEquals(1, server.requestCount)
            assertFalse(missing.text.isBlank())
        }
    }

    @Test fun fetchUsesMarkdownAndReturnsExtractedText() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[{"url":"https://example.com/a","final_url":"https://example.com/article","title":"Example","format":"markdown","text":"# Example\n\nFetched page content."}],"errors":[]}"""))
            server.start()
            val result = TinyFishFetch.fetch(
                apiKey = "tinyfish-secret",
                url = "https://example.com/a",
                endpoint = server.url("/").toString().removeSuffix("/"),
            )
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            val payload = JSONObject(request.body.readUtf8())
            assertEquals("POST", request.method)
            assertEquals("tinyfish-secret", request.getHeader("X-API-Key"))
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            assertEquals("https://example.com/a", payload.getJSONArray("urls").getString(0))
            assertEquals("markdown", payload.getString("format"))
            assertEquals("# Example\n\nFetched page content.", result.text)
        }
    }

    @Test fun fetchReportsPerUrlFailuresFromSuccessfulHttpResponses() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[],"errors":[{"url":"https://example.com","error":"bot_blocked"}]}"""))
            server.start()
            val result = TinyFishFetch.fetch("key", "https://example.com", endpoint = server.url("/").toString().removeSuffix("/"))
            assertTrue(result.text.contains("bot_blocked"))
        }
    }

    @Test fun neitherEndpointFollowsRedirectsWithTheApiKey() {
        for (fetch in listOf(false, true)) MockWebServer().use { server ->
            server.start()
            val endpoint = server.url("/").toString().removeSuffix("/")
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/not-tinyfish")))
            server.enqueue(MockResponse().setBody("{}"))
            if (!fetch) TinyFishSearch.search("key", "query", endpoint = endpoint)
            else TinyFishFetch.fetch("key", "https://example.com", endpoint = endpoint)
            assertEquals("API credentials must never follow an HTTP redirect", 1, server.requestCount)
        }
    }

    @Test fun tinyfishProviderHasItsOwnPersistableLimitAndIdentifier() {
        assertEquals("tinyfish", SearchProvider.TINYFISH.id)
        assertEquals(SearchProvider.TINYFISH, SearchProvider.fromId("TinyFish"))
        assertEquals(1, SearchProvider.TINYFISH.clampMaxResults(Int.MIN_VALUE))
        assertEquals(100, SearchProvider.TINYFISH.clampMaxResults(Int.MAX_VALUE))
    }

    @Test fun invalidInputsAndAlreadyCancelledCallsNeverReachTheNetwork() {
        MockWebServer().use { server ->
            server.start()
            val endpoint = server.url("/").toString()
            val cancelled = ChatRequestCancellation().apply { cancel() }
            assertEquals(tr("搜索已停止"), TinyFishSearch.search("key", "query", cancelled, endpoint = endpoint).text)
            assertEquals(tr("网页读取已停止"), TinyFishFetch.fetch("key", "https://example.com", cancelled, endpoint = endpoint).text)
            assertEquals(tr("未配置 TinyFish 密钥"), TinyFishSearch.search("", "query", endpoint = endpoint).text)
            assertEquals(tr("未配置 TinyFish 密钥"), TinyFishFetch.fetch("", "https://example.com", endpoint = endpoint).text)
            for (url in listOf("file:///secret", "javascript:alert(1)", "https://user:pass@example.com")) {
                assertEquals(tr("网页地址必须以 http:// 或 https:// 开头"), TinyFishFetch.fetch("key", url, endpoint = endpoint).text)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun stoppingEitherToolImmediatelyAbortsItsInFlightRequest() {
        for (fetch in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val endpoint = server.url("/").toString()
            val client = IrioHttp.defaultClient()
            IrioHttp.resetForTests(client)
            val executor = Executors.newSingleThreadExecutor()
            val cancellation = ChatRequestCancellation()
            try {
                val future = executor.submit<WebSearchResult> {
                    if (fetch) TinyFishFetch.fetch("key", "https://example.com", cancellation, endpoint)
                    else TinyFishSearch.search("key", "query", cancellation, endpoint = endpoint)
                }
                assertNotNull("The request must be in flight before stopping", server.takeRequest(2, TimeUnit.SECONDS))
                cancellation.cancel()
                assertEquals(tr(if (fetch) "网页读取已停止" else "搜索已停止"), future.get(2, TimeUnit.SECONDS).text)
            } finally {
                cancellation.cancel()
                executor.shutdownNow()
                client.dispatcher.executorService.shutdownNow()
                IrioHttp.resetForTests()
            }
        }
    }

    @Test fun missingPageTextAndAbsentSearchDatesStayAbsent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[{"url":"https://example.com","text":null}],"errors":[]}"""))
            server.enqueue(MockResponse().setBody("""{"results":[{"url":"https://example.com","title":null,"snippet":null,"date":null}]}"""))
            val endpoint = server.url("/").toString()
            assertEquals(tr("网页没有可读取的正文"), TinyFishFetch.fetch("key", "https://example.com", endpoint = endpoint).text)
            val search = TinyFishSearch.search("key", "query", endpoint = endpoint)
            assertFalse(search.text.contains("null"))
            assertEquals("", searchResultsContent(search.displayContent).items.single().publishedAt)
        }
    }

    @Test fun malformedCredentialsAreNeverEchoedInToolErrors() {
        val key = "private-secret\ninvalid"
        val search = TinyFishSearch.search(key, "query")
        val fetch = TinyFishFetch.fetch(key, "https://example.com")
        for (result in listOf(search, fetch)) {
            assertFalse(result.text.contains("private-secret"))
            assertFalse(result.text.contains(key))
        }
    }

    private val searchResponse = """{"query":"Kotlin","total_results":3,"page":0,"results":[{"position":1,"site_name":"example.com","title":"First result","snippet":"First snippet","url":"https://example.com/first","date":"2026-10-20"},{"position":2,"site_name":"example.org","title":"Second result","snippet":"Second snippet","url":"https://example.org/second"},{"position":3,"site_name":"example.net","title":"Third result","snippet":"Third snippet","url":"https://example.net/third"}]}"""
}
