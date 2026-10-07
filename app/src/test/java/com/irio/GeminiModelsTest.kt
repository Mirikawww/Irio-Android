package com.irio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GeminiModelsTest {
    @Test
    fun listsAllPagesAndFiltersNonChatModels() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-b","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"a+b/="}"""))
            server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-b","supportedGenerationMethods":["generateContent"]}]}"""))
            val provider = ProviderConfig("p", "Google", server.url("/proxy").toString(), "fixture", ProviderProtocol.GEMINI)
            val result = ProviderApi.fetchModels(provider)
            assertNull(result.error)
            assertEquals(listOf("gemini-a", "gemini-b"), result.models)
            val first = server.takeRequest()
            assertEquals("/proxy/v1beta/models", first.path)
            assertEquals("fixture", first.getHeader("x-goog-api-key"))
            assertNull(first.getHeader("Authorization"))
            val second = server.takeRequest()
            assertEquals("a+b/=", second.requestUrl!!.queryParameter("pageToken"))
        }
    }

    @Test
    fun interactionsModelListingAcceptsInteractionMethods() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/interaction-model","supportedGenerationMethods":["interactions"]},{"name":"models/no-methods"},{"name":"models/embedding","supportedGenerationMethods":["embedContent"]}]}"""))
            val protocol = ProviderProtocol.valueOf("GEMINI_INTERACTIONS")
            val provider = ProviderConfig("p", "Google", server.url("/v1beta").toString(), "fixture", protocol)
            val result = ProviderApi.fetchModels(provider)
            assertNull(result.error)
            assertEquals(listOf("interaction-model", "no-methods"), result.models)
        }
    }
}
