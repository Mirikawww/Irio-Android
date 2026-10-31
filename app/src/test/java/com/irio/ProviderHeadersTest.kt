package com.irio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderHeadersTest {
    @Test
    fun chatDoesNotForwardCustomCredentialsOnRedirect() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            okhttp3.mockwebserver.MockWebServer().use { target ->
                server.start()
                target.start()
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(307).setHeader("Location", target.url("/chat/completions")))
                target.enqueue(okhttp3.mockwebserver.MockResponse().setBody(""))
                IrioHttp.resetForTests(IrioHttp.defaultClient())
                try {
                    ChatApi.streamChat(provider = ProviderConfig("p", "Fixture", server.url("/v1").toString(), "",
                        customHeaders = listOf(ProviderHeader("x-goog-api-key", "fixture-secret"))),
                        model = "m", messages = listOf(ChatRequestMessage("user", "hello")),
                        reasoningEffort = ReasoningEffort.OFF, fastMode = false,
                        cancellation = ChatRequestCancellation(), onUpdate = {})
                    assertEquals(0, target.requestCount)
                } finally { IrioHttp.resetForTests() }
            }
        }
    }

    @Test
    fun imageDownloadDoesNotSendHeadersToDifferentOrigin() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            okhttp3.mockwebserver.MockWebServer().use { cdn ->
                server.start()
                cdn.start()
                val url = cdn.url("/image.png")
                server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"data\":[{\"url\":\"$url\"}]}"))
                cdn.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "image/png").setBody("png"))
                val result = ProviderApi.generateImage(ProviderConfig("p", "Fixture", server.url("/v1").toString(), "",
                    customHeaders = listOf(ProviderHeader("x-goog-api-key", "fixture-secret"))), "img", "cat")
                try {
                    assertEquals(null, result.error)
                    assertEquals("fixture-secret", server.takeRequest().getHeader("x-goog-api-key"))
                    assertEquals(null, cdn.takeRequest().getHeader("x-goog-api-key"))
                } finally { result.attachment?.filePath?.let { java.io.File(it).delete() } }
            }
        }
    }

    @Test
    fun customHeadersOverrideDefaultsForEveryChatProtocol() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.MINIMAX_VIDEO }) {
                    server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(""))
                    val provider = ProviderConfig("p", "Fixture", server.url("/v1").toString(), "default-key",
                        protocol = protocol, customHeaders = listOf(
                            ProviderHeader("authorization", "Custom fixture"),
                            ProviderHeader("x-goog-api-key", "fixture-google"),
                            ProviderHeader("x-api-key", "fixture-anthropic"),
                            ProviderHeader("anthropic-version", "2024-01-01"),
                            ProviderHeader("Content-Type", "application/vnd.fixture+json"),
                        ))
                    ChatApi.streamChat(provider = provider, model = "m",
                        messages = listOf(ChatRequestMessage("user", "hello")),
                        reasoningEffort = ReasoningEffort.OFF, fastMode = false,
                        cancellation = ChatRequestCancellation(), onUpdate = {})
                    val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
                    assertEquals("Custom fixture", request.getHeader("Authorization"))
                    assertEquals(1, request.headers.values("Authorization").size)
                    assertEquals("fixture-google", request.getHeader("x-goog-api-key"))
                    assertEquals("fixture-anthropic", request.getHeader("x-api-key"))
                    assertEquals("2024-01-01", request.getHeader("anthropic-version"))
                    assertEquals("application/vnd.fixture+json", request.getHeader("Content-Type"))
                }
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test
    fun customAcceptOverridesModelFetchDefault() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"data\":[{\"id\":\"m\"}]}"))
            val provider = ProviderConfig("p", "Fixture", server.url("/v1").toString(), "",
                customHeaders = listOf(ProviderHeader("accept", "application/vnd.fixture+json")))
            ProviderApi.fetchModels(provider)
            assertEquals("application/vnd.fixture+json", server.takeRequest().getHeader("Accept"))
        }
    }

    @Test
    fun modelFetchDoesNotForwardCredentialsToRedirectTarget() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            okhttp3.mockwebserver.MockWebServer().use { target ->
                server.start()
                target.start()
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", target.url("/models")))
                target.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"data\":[{\"id\":\"m\"}]}"))
                ProviderApi.fetchModels(ProviderConfig("p", "Fixture", server.url("/v1").toString(), "",
                    customHeaders = listOf(ProviderHeader("x-goog-api-key", "fixture-secret"))))
                assertEquals(0, target.requestCount)
            }
        }
    }

    @Test
    fun snapshotParserPreservesHeadersAndDefaultsLegacyProvidersToEmpty() {
        val snapshot = JSONObject().put("providers", JSONArray().put(
            JSONObject().put("id", "custom").put("apiKey", "fixture")
                .put("customHeaders", JSONArray().put(JSONObject().put("name", "X-Key").put("value", "secret"))),
        ).put(JSONObject().put("id", "legacy").put("apiKey", "fixture")))
        val parser = ProviderStore::class.java.getDeclaredMethod("parseSnapshotProviders", JSONObject::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val providers = parser.invoke(ProviderStore, snapshot) as List<ProviderConfig>
        assertEquals(listOf(ProviderHeader("X-Key", "secret")), providers[0].customHeaders)
        assertEquals(emptyList<ProviderHeader>(), providers[1].customHeaders)
    }

    @Test
    fun validatesNamesValuesAndCaseInsensitiveDuplicatesWithoutEchoingSecrets() {
        assertEquals(null, validateProviderHeaders(listOf(ProviderHeader("x-goog-api-key", "fixture-key"))))
        assertEquals(null, validateProviderHeaders(listOf(ProviderHeader("Authorization", ""))))
        org.junit.Assert.assertNotNull(validateProviderHeaders(listOf(ProviderHeader("Bad Name", "secret"))))
        org.junit.Assert.assertNotNull(validateProviderHeaders(listOf(ProviderHeader("", "secret"))))
        val error = validateProviderHeaders(listOf(ProviderHeader("X-Key", "secret\r\nInjected: yes")))!!
        org.junit.Assert.assertFalse(error.contains("secret"))
        org.junit.Assert.assertNotNull(validateProviderHeaders(listOf(ProviderHeader("X-Key", "中文"))))
        org.junit.Assert.assertNotNull(validateProviderHeaders(listOf(ProviderHeader("X-Key", "a"), ProviderHeader("x-key", "b"))))
    }

    @Test
    fun modelFetchUsesHeadersBeforeProviderIsSaved() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"data\":[{\"id\":\"m\"}]}"))
            server.start()
            val provider = ProviderConfig("p", "Fixture", server.url("/v1").toString(), "",
                customHeaders = listOf(ProviderHeader("x-goog-api-key", "fixture-key"), ProviderHeader("User-Agent", "Irio-test")))
            val result = ProviderApi.fetchModels(provider)
            val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals(null, result.error)
            assertEquals(listOf("m"), result.models)
            assertEquals("fixture-key", request.getHeader("x-goog-api-key"))
            assertEquals("Irio-test", request.getHeader("User-Agent"))
            assertEquals(null, request.getHeader("Authorization"))
        }
    }

    @Test
    fun customHeaderOnlyProviderSurvivesSnapshotImport() {
        val snapshot = JSONObject().put("providers", JSONArray().put(
            JSONObject().put("id", "header-only").put("name", "Fixture")
                .put("endpoint", "https://example.invalid/v1").put("apiKey", "")
                .put("customHeaders", JSONArray().put(
                    JSONObject().put("name", "x-goog-api-key").put("value", "fixture-key"),
                )),
        ))
        val parser = ProviderStore::class.java.getDeclaredMethod("parseSnapshotProviders", JSONObject::class.java)
            .apply { isAccessible = true }
        val providers = parser.invoke(ProviderStore, snapshot) as List<*>
        assertEquals(1, providers.size)
    }
}
