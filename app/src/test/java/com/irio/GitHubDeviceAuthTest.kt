package com.irio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubDeviceAuthTest {
    @Test fun denialAndExpiryStopPollingWithoutReturningCredentials() = runTest {
        for (error in listOf("access_denied", "expired_token")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"error":"$error","error_description":"never show this fixture-secret"}"""))
                val auth = GitHubDeviceAuth(server.url("/"))
                val result = runCatching { auth.awaitToken(GitHubDeviceCode("fixture", "ABCD", "https://github.com/login/device", 900, 5)) }
                org.junit.Assert.assertTrue(result.isFailure)
                assertFalse(result.exceptionOrNull()!!.message.orEmpty().contains("fixture-secret"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun closingLoginBeforeFirstPollSendsNoTokenRequests() = runTest {
        MockWebServer().use { server ->
            val auth = GitHubDeviceAuth(server.url("/"))
            val job = launch {
                auth.awaitToken(GitHubDeviceCode("fixture", "ABCD", "https://github.com/login/device", 900, 5))
            }
            testScheduler.runCurrent()
            job.cancel()
            testScheduler.advanceUntilIdle()
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun deviceFlowUsesExactClientIdAndHonorsPendingAndSlowDown() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_code":"device-fixture","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""))
            server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
            server.enqueue(MockResponse().setBody("""{"error":"slow_down","interval":10}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"oauth-fixture","expires_in":28800,"refresh_token":"refresh-fixture","refresh_token_expires_in":15897600,"token_type":"bearer"}"""))
            val auth = GitHubDeviceAuth(baseUrl = server.url("/"))
            val code = auth.requestCode()
            assertEquals("ABCD-EFGH", code.userCode)
            val started = testScheduler.currentTime
            val credential = auth.awaitToken(code)
            assertEquals("oauth-fixture", credential.accessToken)
            assertEquals("refresh-fixture", credential.refreshToken)
            assertTrue(credential.expiresAt > System.currentTimeMillis())
            assertTrue(credential.refreshTokenExpiresAt > credential.expiresAt)
            assertEquals(20_000L, testScheduler.currentTime - started)
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals("/login/device/code", request.path)
            assertFalse(body.contains("client_secret"))
            org.junit.Assert.assertTrue(body.contains("client_id=Ov23liyvVnA5MQa4yO4T"))
            val scopes = java.net.URLDecoder.decode(body.substringAfter("scope=").substringBefore("&"), "UTF-8")
            assertEquals("repo read:org user:email offline_access", scopes)
            repeat(3) { assertFalse(server.takeRequest().body.readUtf8().contains("client_secret")) }
        }
    }
}
