package com.irio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class VideoGenerationApiTest {
    private fun config(server: MockWebServer) = ProviderConfig("video-fixture", "Video", server.url("/").toString(),
        "fixture-key", protocol = ProviderProtocol.MINIMAX_VIDEO)
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun task(status: String, extra: String = "") = response("""{"task":{"id":"task-1","status":"$status"$extra}}""")
    private fun success() = task("succeeded", """, "content":{"url":"https://cdn.example.invalid/result.mp4"},"usage":{"output_seconds":5,"total_tokens":42,"prompt_tokens":0,"completion_tokens":42}""")

    @Test fun createAndPollSendExactWireFormatAndReturnRemoteOnlyMp4() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"task_id":"task-1"}"""))
            server.enqueue(task("queued")); server.enqueue(task("running")); server.enqueue(success())
            val statuses = mutableListOf<String>()
            val delays = mutableListOf<Long>()
            val result = VideoGenerationApi.generate(config(server), "MiniMax-H3", "A quiet sea",
                VideoGenerationOptions("2K", 15, "21:9"), pollIntervalMillis = 200, pollingDelay = { delays += it },
                onProgress = { statuses += it.status })
            assertNull(result.error)
            assertEquals("task-1", result.taskId)
            assertEquals("succeeded", result.status)
            assertEquals(listOf("queued", "queued", "running", "succeeded"), statuses)
            assertEquals(listOf(100L, 100L, 100L, 100L), delays)
            assertEquals("video/mp4", result.attachment!!.mimeType)
            assertEquals("video-task-1.mp4", result.attachment.name)
            assertEquals("", result.attachment.filePath)
            assertEquals("https://cdn.example.invalid/result.mp4", result.attachment.remoteUrl)
            assertEquals(TokenUsage(0, 42, 42), result.usage)
            assertEquals(5, result.videoUsage!!.outputSeconds)
            val create = server.takeRequest()
            assertEquals("POST", create.method); assertEquals("/v2/video_generation", create.path)
            assertEquals("Bearer fixture-key", create.getHeader("Authorization"))
            val json = JSONObject(create.body.readUtf8())
            assertEquals("MiniMax-H3", json.getString("model"))
            assertEquals("2K", json.getString("resolution")); assertEquals(15, json.getInt("duration"))
            assertEquals("21:9", json.getString("ratio")); assertFalse(json.has("callback_url"))
            assertEquals("A quiet sea", json.getJSONArray("content").getJSONObject(0).getString("text"))
            repeat(3) {
                val query = server.takeRequest()
                assertEquals("GET", query.method); assertEquals("/v2/query/video_generation/task-1", query.path)
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun firstAndLastFramesUseNestedImageUrlsAndAdaptiveRatio() {
        val media = listOf(VideoGenerationMedia("image_url", "https://example.invalid/first.png", "first_frame"),
            VideoGenerationMedia("image_url", "mm_file://last-123", "last_frame"))
        val json = VideoGenerationApi.requestBody("MiniMax-H3", "Move slowly", VideoGenerationOptions(), media)
        assertEquals("adaptive", json.getString("ratio"))
        val content = json.getJSONArray("content")
        assertEquals(3, content.length())
        assertEquals("first_frame", content.getJSONObject(1).getString("role"))
        assertEquals(media[0].url, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("last_frame", content.getJSONObject(2).getString("role"))
    }

    @Test fun referenceMediaCanMixTypesButNotFrameRoles() {
        val media = listOf(VideoGenerationMedia("image_url", "data:image/png;base64,AQID", "reference_image"),
            VideoGenerationMedia("video_url", "https://example.invalid/clip.mp4", "reference_video"),
            VideoGenerationMedia("audio_url", "mm_file://audio_1", "reference_audio"))
        val body = VideoGenerationApi.requestBody("MiniMax-H3", "Animate", VideoGenerationOptions(ratio = "adaptive"), media)
        assertEquals("adaptive", body.getString("ratio")); assertEquals(4, body.getJSONArray("content").length())
        assertThrows(IllegalArgumentException::class.java) {
            VideoGenerationApi.requestBody("MiniMax-H3", "Animate", VideoGenerationOptions(), media +
                VideoGenerationMedia("image_url", "https://example.invalid/f.png", "first_frame"))
        }
    }

    @Test fun badInputsAreRejectedBeforeNetworkOrPaidCreation() {
        MockWebServer().use { server ->
            val c = config(server)
            val cases = listOf(
                Triple("MiniMax-H3", " ", VideoGenerationOptions()),
                Triple("MiniMax-H3", "a".repeat(7001), VideoGenerationOptions()),
                Triple("unknown", "go", VideoGenerationOptions()),
                Triple("MiniMax-H3", "go", VideoGenerationOptions("480P")),
                Triple("MiniMax-H3-Lite", "go", VideoGenerationOptions("2K")),
                Triple("MiniMax-H3-Max", "go", VideoGenerationOptions(duration = 4)),
                Triple("MiniMax-H3", "go", VideoGenerationOptions(duration = 16)),
                Triple("MiniMax-H3", "go", VideoGenerationOptions(ratio = "2:1")),
                Triple("MiniMax-H3", "go", VideoGenerationOptions(ratio = "adaptive")),
            )
            cases.forEach { (model, prompt, options) ->
                assertNotNull(VideoGenerationApi.create(c, model, prompt, options).error)
            }
            assertNotNull(VideoGenerationApi.create(c.copy(protocol = ProviderProtocol.CHAT_COMPLETIONS), "MiniMax-H3", "go").error)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun invalidMediaRolesCountsUrlsAndDataAreRejected() {
        val valid = VideoGenerationMedia("image_url", "https://example.invalid/a.png", "reference_image")
        val cases = listOf(
            listOf(valid.copy(type = "file")), listOf(valid.copy(role = "reference_audio")),
            listOf(valid.copy(url = "file:///private/a.png")), listOf(valid.copy(url = "https://key:secret@example.invalid/a")),
            listOf(valid.copy(url = " data:image/png;base64,AQID")), listOf(valid.copy(url = "data:image/gif;base64,AQID")),
            listOf(valid.copy(url = "data:image/png;base64,%%%")), listOf(valid.copy(url = "mm_file://../secret")),
            List(10) { valid }, List(2) { valid.copy(role = "first_frame") },
            List(4) { VideoGenerationMedia("video_url", "https://example.invalid/v.mp4", "reference_video") },
        )
        cases.forEach { media -> assertThrows(IllegalArgumentException::class.java) {
            VideoGenerationApi.requestBody("MiniMax-H3", "go", VideoGenerationOptions(), media)
        } }
        assertThrows(IllegalArgumentException::class.java) {
            VideoGenerationApi.requestBody("MiniMax-H3-Lite", "go", VideoGenerationOptions(ratio = "adaptive"), listOf(valid))
        }
    }

    @Test fun pollingFailureRetainsAcceptedTaskAndNeverCreatesAgain() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"task_id":"task-1"}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"try query later"}}"""))
            val result = VideoGenerationApi.generate(config(server), "MiniMax-H3", "go", pollingDelay = {})
            assertEquals("try query later", result.error); assertEquals("task-1", result.taskId)
            assertEquals("POST", server.takeRequest().method); assertEquals("GET", server.takeRequest().method)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun transientGetFailuresBackOffAndContinueWithoutDuplicateCreation() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"task_id":"task-1"}"""))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(task("running")); server.enqueue(success())
            val delays = mutableListOf<Long>()
            val result = VideoGenerationApi.generate(config(server), "MiniMax-H3", "go", pollIntervalMillis = 0,
                maxPolls = 4, pollingDelay = { delays += it })
            assertNull(result.error); assertEquals("succeeded", result.status)
            assertEquals(3_000L, delays.sum())
            assertEquals("POST", server.takeRequest().method)
            repeat(4) { assertEquals("GET", server.takeRequest().method) }
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun failedAndCancelledTasksSurfaceProviderErrors() {
        MockWebServer().use { server ->
            server.enqueue(task("failed", """, "error":{"code":"1026","message":"sensitive content"}"""))
            server.enqueue(task("cancelled"))
            val failure = VideoGenerationApi.await(config(server), "task-1", pollingDelay = {})
            assertEquals("1026: sensitive content", failure.error); assertNull(failure.attachment)
            val cancelled = VideoGenerationApi.query(config(server), "task-1")
            assertTrue(cancelled.stopped); assertEquals("cancelled", cancelled.status); assertNotNull(cancelled.error)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun missingOrUnknownTaskStateNeverLooksLikeSuccess() {
        MockWebServer().use { server ->
            listOf("{}", """{"task":{"status":"mystery"}}""", """{"task":{"status":"succeeded"}}""",
                """{"task":{"status":"succeeded","content":{"url":"file:///private/video.mp4"}}}}""",
                """{"task":{"id":"wrong","status":"running"}}""").forEach { body ->
                server.enqueue(response(body))
                val result = VideoGenerationApi.query(config(server), "task-1")
                assertEquals("task-1", result.taskId); assertNotNull(body, result.error); assertNull(result.attachment)
            }
        }
    }

    @Test fun creationErrorsAndMissingTaskIdNeverPollOrRetry() {
        MockWebServer().use { server ->
            listOf(response("{}"), response("""{"task_id":null}"""), response("""{"task_id":123}"""),
                response("""{"task_id":"../oops"}"""), MockResponse().setResponseCode(402)
                    .setBody("""{"error":{"message":"insufficient balance"}}""")).forEach { reply ->
                server.enqueue(reply)
                val before = server.requestCount
                val result = VideoGenerationApi.generate(config(server), "MiniMax-H3", "go", pollingDelay = {})
                assertNotNull(result.error); assertEquals("", result.taskId)
                assertEquals(before + 1, server.requestCount); assertEquals("POST", server.takeRequest().method)
            }
        }
    }

    @Test fun retryAfterZeroDoesNotReplayPaidCreationPost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(response("""{"task_id":"must-not-create"}"""))
            val result = VideoGenerationApi.create(config(server), "MiniMax-H3", "go")
            assertNotNull(result.error); assertEquals("", result.taskId)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun customHeadersWinOnWireAndRedirectNeverForwardsCredentials() {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            target.start()
            origin.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/leaked")))
            val c = config(origin).copy(customHeaders = listOf(ProviderHeader("Authorization", "custom-key"),
                ProviderHeader("Content-Type", "application/vnd.fixture+json"), ProviderHeader("X-Fixture", "override")))
            val result = VideoGenerationApi.create(c, "MiniMax-H3", "go")
            assertNotNull(result.error)
            val request = origin.takeRequest()
            assertEquals("custom-key", request.getHeader("Authorization"))
            assertEquals("application/vnd.fixture+json", request.getHeader("Content-Type"))
            assertEquals("override", request.getHeader("X-Fixture"))
            assertEquals(0, target.requestCount); assertEquals(1, origin.requestCount)
        } }
    }

    @Test fun standardBearerCredentialsAlsoDisableRedirects() {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            target.start()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/leaked")))
            assertNotNull(VideoGenerationApi.query(config(origin), "task-1").error)
            assertEquals(1, origin.requestCount); assertEquals(0, target.requestCount)
        } }
    }

    @Test fun preCancelledCallsAndInvalidTaskIdsNeverReachNetwork() {
        MockWebServer().use { server ->
            val cancellation = ChatRequestCancellation().apply { cancel() }
            assertTrue(VideoGenerationApi.create(config(server), "MiniMax-H3", "go", cancellation = cancellation).stopped)
            assertTrue(VideoGenerationApi.await(config(server), "task-1", cancellation).stopped)
            listOf("", "../x", "x?token=secret", " task-1").forEach {
                assertNotNull(VideoGenerationApi.query(config(server), it).error)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun cancellationDuringDelayDoesNotQueryAgain() {
        MockWebServer().use { server ->
            server.enqueue(task("running"))
            val cancellation = ChatRequestCancellation()
            val result = VideoGenerationApi.await(config(server), "task-1", cancellation,
                pollIntervalMillis = 5_000, pollingDelay = { cancellation.cancel() })
            assertTrue(result.stopped); assertEquals("task-1", result.taskId); assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationDisconnectsActiveQuery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancellation = ChatRequestCancellation()
            val future = CompletableFuture.supplyAsync { VideoGenerationApi.query(config(server), "task-1", cancellation) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            cancellation.cancel()
            val result = future.get(5, TimeUnit.SECONDS)
            assertTrue(result.stopped); assertEquals("task-1", result.taskId)
        }
    }

    @Test fun pollLimitAndInvalidPollingSettingsDoNotCreateAnotherTask() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(task("running")) }
            val result = VideoGenerationApi.await(config(server), "task-1", maxPolls = 2, pollingDelay = {})
            assertTrue(result.error!!.contains("timed out")); assertEquals("task-1", result.taskId)
            assertNotNull(VideoGenerationApi.generate(config(server), "MiniMax-H3", "go", maxPolls = 0).error)
            assertNotNull(VideoGenerationApi.generate(config(server), "MiniMax-H3", "go", pollIntervalMillis = -1).error)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun baseEndpointVariantsPreserveProxyPrefixWithoutDuplicateV2() {
        MockWebServer().use { server ->
            listOf("/proxy", "/proxy/v1", "/proxy/v2", "/proxy/v2/video_generation").forEach { path ->
                server.enqueue(response("""{"task_id":"task-1"}"""))
                val result = VideoGenerationApi.create(config(server).copy(endpointUrl = server.url(path).toString()), "MiniMax-H3", "go")
                assertNull(result.error); assertEquals("/proxy/v2/video_generation", server.takeRequest().path)
            }
        }
    }

    @Test fun absentUsageRemainsAbsentAndOutputIsNotDownloaded() {
        MockWebServer().use { server ->
            server.enqueue(task("succeeded", """, "content":{"url":"https://cdn.example.invalid/output.mp4"},"usage":{}"""))
            val result = VideoGenerationApi.query(config(server), "task-1")
            assertNull(result.error); assertNull(result.usage); assertNull(result.videoUsage)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun knownVideoModelsDoNotPerformNonexistentDiscovery() {
        val c = ProviderConfig("fixture", "Fixture", "https://example.invalid", "fixture", ProviderProtocol.MINIMAX_VIDEO)
        assertEquals(VideoGenerationOptions.models, ProviderApi.fetchModels(c).models)
        assertNull(ProviderApi.fetchModels(c).error)
        assertThrows(IllegalArgumentException::class.java) { ProviderEndpoints.chatUrl(c, "MiniMax-H3") }
    }
}
