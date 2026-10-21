package com.irio

import android.content.Context
import java.util.concurrent.TimeUnit
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
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TinyFishToolsTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context
    private val config = ProviderConfig("fixture", "Fixture", "https://model.test/v1", "model-key")

    @Test fun actualChatRegistryRoutesBothToolsThroughTheSelectedProvider() = runBlocking {
        ShadowEnvironment.addExternalDir(app.cacheDir.resolve("external").absolutePath)
        val registry = chatToolRegistry(app, webSearch = true, imageGenModel = "", imageSearch = false)
        for (protocol in ProviderProtocol.entries) {
            val names = registry.definitions(protocol).map { (it.optJSONObject("function") ?: it).getString("name") }
            assertTrue(WebSearchTool.NAME in names)
            assertTrue(WebFetchTool.NAME in names)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[{"title":"TinyFish result","url":"https://example.com/a/b","site_name":"Example","snippet":"A matching page","date":"2026-10-20"}]}"""))
            server.enqueue(MockResponse().setBody("""{"results":[{"url":"https://example.com/a/b","text":"# Full page\n\nPage body."}],"errors":[]}"""))
            val destinations = mutableListOf<String>()
            IrioHttp.resetForTests(IrioHttp.defaultClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals(if (request.url.host == "api.fetch.tinyfish.ai") 150_000 else 30_000, chain.readTimeoutMillis())
                destinations += request.url.host
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath).newBuilder()
                    .encodedQuery(request.url.encodedQuery).build()).build())
            }.build())
            try {
                val updates = mutableListOf<ChatStreamUpdate>()
                val execution = ToolExecutionContext(config, "model", webSearch = true,
                    searchProvider = SearchProvider.TINYFISH, searchApiKey = "tinyfish-test-key",
                    onUpdate = { updates += it })
                val search = registry.execute(ChatToolCall("s", WebSearchTool.NAME, """{"query":"Kotlin & Compose"}"""), execution)
                assertEquals(1, search.searchPages)
                assertTrue(search.text.contains("A matching page"))
                assertEquals(listOf(true, false), updates.mapNotNull { it.searching })
                val display = searchResultsContent(updates.last().searchContent).items.single()
                assertEquals("Example", display.siteName)
                assertEquals("2026-10-20", display.publishedAt)
                assertEquals("https://example.com/a/b", display.url)
                val searchRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("GET", searchRequest.method)
                assertEquals("Kotlin & Compose", searchRequest.requestUrl!!.queryParameter("query"))
                assertEquals(setOf("query"), searchRequest.requestUrl!!.queryParameterNames)
                assertEquals("tinyfish-test-key", searchRequest.getHeader("X-API-Key"))

                updates.clear()
                val fetched = registry.execute(ChatToolCall("f", WebFetchTool.NAME,
                    """{"url":"https://example.com/a/b"}"""), execution)
                assertEquals("# Full page\n\nPage body.", fetched.text)
                assertEquals(listOf(true, false), updates.mapNotNull { it.toolActive })
                assertEquals(fetched.text, updates.last().toolContent)
                val fetchRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("POST", fetchRequest.method)
                assertEquals("tinyfish-test-key", fetchRequest.getHeader("X-API-Key"))
                assertNull(fetchRequest.getHeader("Authorization"))
                val payload = JSONObject(fetchRequest.body.readUtf8())
                assertEquals("https://example.com/a/b", payload.getJSONArray("urls").getString(0))
                assertEquals("markdown", payload.getString("format"))
                assertEquals(listOf("api.search.tinyfish.ai", "api.fetch.tinyfish.ai"), destinations)

                for (name in listOf(WebSearchTool.NAME, WebFetchTool.NAME)) {
                    val unavailable = registry.execute(ChatToolCall("disabled", name, "{}"), execution.copy(webSearch = false))
                    assertTrue(unavailable.text.contains("不可用"))
                    val missingKey = registry.execute(ChatToolCall("missing", name, "{}"), execution.copy(searchApiKey = ""))
                    assertTrue(missingKey.text.contains("不可用"))
                }
                assertEquals("Unavailable tools must not make network requests", 2, server.requestCount)
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test fun selectedProviderAndIndependentLimitsSurviveSnapshots() {
        SearchStore.saveProvider(app, SearchProvider.TINYFISH)
        SearchStore.saveMaxResults(app, 7, SearchProvider.TINYFISH)
        SearchStore.saveMaxResults(app, 3, SearchProvider.TAVILY)
        SearchStore.saveMaxResults(app, 8, SearchProvider.EXA)
        assertEquals(SearchProvider.TINYFISH, SearchStore.loadProvider(app))
        val snapshot = SearchStore.snapshot(app)
        assertEquals("tinyfish", snapshot.getString("provider"))
        assertTrue(snapshot.has("tinyfishApiKey"))
        assertEquals(7, snapshot.getInt("tinyfishMaxResults"))
        SearchStore.saveMaxResults(app, 1, SearchProvider.TINYFISH)
        SearchStore.applySnapshot(app, snapshot, overwrite = true)
        assertEquals(7, SearchStore.loadMaxResults(app, SearchProvider.TINYFISH))
        assertEquals(3, SearchStore.loadMaxResults(app, SearchProvider.TAVILY))
        assertEquals(8, SearchStore.loadMaxResults(app, SearchProvider.EXA))
        assertEquals(SearchProvider.TINYFISH, SearchStore.loadProvider(app))
        // Credential encryption is existing AndroidKeyStore code, not mocked here.
        val keyName = SearchStore.javaClass.getDeclaredMethod("encryptedKeyName", SearchProvider::class.java)
            .apply { isAccessible = true }
        assertEquals("tinyfish_encrypted_key", keyName.invoke(SearchStore, SearchProvider.TINYFISH))
        assertEquals(3, SearchProvider.entries.map { keyName.invoke(SearchStore, it) }.toSet().size)
    }
}
