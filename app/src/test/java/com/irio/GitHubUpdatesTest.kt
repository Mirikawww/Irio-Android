package com.irio

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GitHubUpdatesTest {
    @Test fun versionsUseSemanticOrderingIncludingNumericPrereleaseIdentifiers() {
        val tags = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.9", "1.0.10", "1.1.0", "2.0.0")
        tags.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", version(a) < version(b)) }
        assertEquals(0, version("v1.2.3").compareTo(version("1.2.3+build.42")))
        assertTrue(version("999999999999999999999.0.0") > version("9.0.0"))
        listOf("", "nightly", "1.0", "01.0.0", "1.0.0-01", "1.0.0-", "1.0.0+", "v1.2.3 trailing")
            .forEach { assertNull(it, UpdateVersion.parse(it)) }
    }

    @Test fun releaseChannelIgnoresPrereleasesDraftsAndNonVersionTags() = runBlocking {
        withServer { server, client ->
            server.enqueue(json(release("3.0.0", draft = true), release("2.0.0-beta.1", pre = true),
                release("9.0.0-beta.2"), release("nightly"), release("1.0.9"), release("1.0.10")))
            val result = client.check("1.0.9-debug", UpdateChannel.RELEASE) as UpdateCheckResult.Available
            assertEquals("1.0.10", result.release.tag)
            assertFalse(result.release.prerelease)
            val request = server.takeRequest()
            assertEquals("/repos/Mirikawww/Irio-Android/releases?per_page=100&page=1", request.path)
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
            assertEquals("2026-03-10", request.getHeader("X-GitHub-Api-Version"))
            assertNull("An update check must not leak account credentials", request.getHeader("Authorization"))
        }
    }

    @Test fun prereleaseChannelIncludesStableAndPreviewVersionsAndSelectsTheHighest() = runBlocking {
        withServer { server, client ->
            server.enqueue(json(release("1.1.0-beta.2", pre = true), release("1.1.0-beta.10", pre = true), release("1.0.0")))
            assertEquals("1.1.0-beta.10", (client.check("1.0.0", UpdateChannel.PRE_RELEASE) as UpdateCheckResult.Available).release.tag)
            server.enqueue(json(release("1.1.0-beta.10", pre = true), release("1.1.0")))
            assertEquals("1.1.0", (client.check("1.1.0-beta.10", UpdateChannel.PRE_RELEASE) as UpdateCheckResult.Available).release.tag)
        }
    }

    @Test fun sameVersionOlderReleasesAndDebugSuffixNeverCauseDowngrades() = runBlocking {
        withServer { server, client ->
            for (current in listOf("1.0.0", "1.0.0-debug", "v1.0.0+local", "1.0.1")) {
                server.enqueue(json(release("1.0.0"), release("0.9.6")))
                assertEquals(UpdateCheckResult.UpToDate, client.check(current, UpdateChannel.RELEASE))
            }
        }
    }

    @Test fun emptyChannelIsNotMisreportedAsUpToDate() = runBlocking {
        withServer { server, client ->
            server.enqueue(json())
            assertEquals(UpdateCheckResult.NoReleases, client.check("0.9.6", UpdateChannel.RELEASE))
            server.enqueue(json(release("1.0.0-beta.1", pre = true)))
            assertEquals(UpdateCheckResult.NoReleases, client.check("0.9.6", UpdateChannel.RELEASE))
        }
    }

    @Test fun selectedReleasePreservesMarkdownNotesAndHandlesMissingOrNullBodies() = runBlocking {
        withServer { server, client ->
            val markdown = "## What's new\n\n- **Bold** and `code`\n\n[Download](https://example.test/download)"
            server.enqueue(json(release("1.0.0").put("body", markdown), release("0.9.9").put("body", "old notes")))
            val update = (client.check("0.9.6", UpdateChannel.RELEASE) as UpdateCheckResult.Available).release
            assertEquals(markdown, update.notes)
            for (body in listOf<Any?>(null, JSONObject.NULL)) {
                server.enqueue(json(release("1.0.0").put("body", body)))
                assertEquals("", (client.check("0.9.6", UpdateChannel.RELEASE) as UpdateCheckResult.Available).release.notes)
            }
        }
    }

    @Test fun paginationFindsNewerVersionsOnLaterPages() = runBlocking {
        withServer { server, client ->
            server.enqueue(MockResponse().setBody(JSONArray(List(100) { release("0.9.0") }).toString()))
            server.enqueue(json(release("1.0.0")))
            assertEquals("1.0.0", (client.check("0.9.6", UpdateChannel.RELEASE) as UpdateCheckResult.Available).release.tag)
            server.takeRequest()
            assertTrue(server.takeRequest().path!!.endsWith("page=2"))
        }
    }

    @Test fun rateLimitMissingRepositoryAndServerErrorsAreNotSuccessfulChecks() = runBlocking {
        withServer { server, client ->
            for ((response, expected) in listOf(
                MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0") to "GitHub 请求过于频繁，请稍后重试",
                MockResponse().setResponseCode(429) to "GitHub 请求过于频繁，请稍后重试",
                MockResponse().setResponseCode(404) to "无法访问 GitHub 发布信息",
                MockResponse().setResponseCode(500) to "检查更新失败，请稍后重试",
            )) {
                server.enqueue(response)
                assertCheckFailure(client, expected)
            }
        }
    }

    @Test fun malformedResponsesAndUnsafeReleaseLinksAreRejected() = runBlocking {
        withServer { server, client ->
            for (body in listOf("not json", "{}", "[null]", JSONArray(listOf(release("1.0.0").put("html_url", "https://evil.test/update"))).toString(),
                JSONArray(listOf(release("1.0.0").put("html_url", "http://github.com/Mirikawww/Irio-Android/releases/tag/1.0.0"))).toString())) {
                server.enqueue(MockResponse().setBody(body))
                assertCheckFailure(client, "GitHub 更新响应无效")
            }
        }
    }

    @Test fun redirectsAreNotFollowed() = runBlocking {
        withServer { server, client ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/unexpected")))
            assertCheckFailure(client, "检查更新失败，请稍后重试")
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun leavingTheScreenCancelsTheNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = CountDownLatch(1)
            val http = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun canceled(call: okhttp3.Call) { cancelled.countDown() }
            }).build()
            try {
                val client = GitHubUpdateClient(http, server.url("/"))
                val job = launch { client.check("0.9.6", UpdateChannel.RELEASE) }
                withTimeout(5_000) {
                    while (server.requestCount == 0) kotlinx.coroutines.delay(10)
                }
                job.cancelAndJoin()
                assertTrue("Screen disposal must cancel the actual OkHttp call", cancelled.await(5, TimeUnit.SECONDS))
            } finally {
                http.dispatcher.executorService.shutdownNow()
                http.connectionPool.evictAll()
            }
        }
    }

    private fun version(value: String) = requireNotNull(UpdateVersion.parse(value))
    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false) = JSONObject()
        .put("tag_name", tag).put("prerelease", pre).put("draft", draft)
        .put("html_url", "https://github.com/Mirikawww/Irio-Android/releases/tag/$tag")
    private fun json(vararg releases: JSONObject) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONArray(releases.toList()).toString())
    private suspend fun assertCheckFailure(client: GitHubUpdateClient, expected: String) {
        try {
            client.check("0.9.6", UpdateChannel.RELEASE)
            fail("Expected a failed update check")
        } catch (e: UpdateCheckException) { assertEquals(expected, e.messageKey) }
    }
    private suspend fun withServer(block: suspend (MockWebServer, GitHubUpdateClient) -> Unit) {
        MockWebServer().use { server -> block(server, GitHubUpdateClient(baseUrl = server.url("/"))) }
    }
}
