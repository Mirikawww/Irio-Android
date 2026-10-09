package com.irio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GitHubAccountClientTest {
    @Test
    fun missingEmailPermissionsDoNotPreventTheAccountFromLoadingOrInventAnEmail() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"login":"octocat","avatar_url":"https://avatars.githubusercontent.com/u/1","email":null}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Resource not accessible by personal access token"}"""))
            val account = GitHubAccountClient("account-token-fixture", baseUrl = server.url("/")).load()
            assertEquals("octocat", account.login)
            assertEquals("https://avatars.githubusercontent.com/u/1", account.avatarUrl)
            assertEquals("", account.email)
        }
    }

    @Test
    fun primaryEmailCanAppearAfterTheFirstPage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"login":"octocat","email":null}"""))
            val first = org.json.JSONArray((1..100).map { i -> org.json.JSONObject()
                .put("email", "secondary$i@example.test").put("primary", false).put("verified", true) })
            server.enqueue(MockResponse().setBody(first.toString()))
            server.enqueue(MockResponse().setBody("""[{"email":"primary@example.test","primary":true,"verified":true}]"""))
            val account = GitHubAccountClient("account-token-fixture", baseUrl = server.url("/")).load()
            assertEquals("primary@example.test", account.email)
            repeat(2) { server.takeRequest() }
            assertEquals("/user/emails?per_page=100&page=2", server.takeRequest().path)
        }
    }

    @Test
    fun apiRedirectsAreNotFollowedAndErrorBodiesCannotExposeCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://untrusted.example.test/steal")
                .setBody("account-token-fixture"))
            val error = runCatching { GitHubAccountClient("account-token-fixture", baseUrl = server.url("/")).load() }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
            assertFalse(error!!.message.orEmpty().contains("account-token-fixture"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun unsupportedAvatarHostsNeverBecomeImageRequestsAndCancelledWorkDoesNotSendTheToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"login":"octocat","avatar_url":"https://untrusted.example.test/image","email":"octocat@example.test"}"""))
            assertEquals("", GitHubAccountClient("account-token-fixture", baseUrl = server.url("/")).load().avatarUrl)
            val cancellation = ChatRequestCancellation().apply { cancel() }
            val error = runCatching { GitHubAccountClient("account-token-fixture", baseUrl = server.url("/"), cancellation = cancellation).load() }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun privateEmailComesFromThePrimaryAuthenticatedEmailNotAnotherAddress() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"login":"octocat","avatar_url":"https://avatars.githubusercontent.com/u/1","email":null}"""))
            server.enqueue(MockResponse().setBody("""[{"email":"secondary@example.test","primary":false,"verified":true},{"email":"primary@example.test","primary":true,"verified":true}]"""))
            val account = GitHubAccountClient("account-token-fixture", baseUrl = server.url("/")).load()
            assertEquals("primary@example.test", account.email)
            assertEquals("octocat", account.login)
            server.takeRequest()
            val emails = server.takeRequest()
            assertEquals("/user/emails?per_page=100&page=1", emails.path)
            assertEquals("Bearer account-token-fixture", emails.getHeader("Authorization"))
        }
    }

    @Test
    fun readsTheAuthenticatedAccountAndPublicEmailWithoutSendingTheTokenToTheAvatar() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"login":"octocat","avatar_url":"https://avatars.githubusercontent.com/u/1?v=4","email":"octocat@example.test"}"""))
            val account = GitHubAccountClient("account-token-fixture", baseUrl = server.url("/")).load()
            assertEquals("octocat", account.login)
            assertEquals("octocat@example.test", account.email)
            assertEquals("https://avatars.githubusercontent.com/u/1?v=4", account.avatarUrl)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/user", request.path)
            assertEquals("Bearer account-token-fixture", request.getHeader("Authorization"))
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
            assertEquals("2026-03-10", request.getHeader("X-GitHub-Api-Version"))
            assertEquals(1, server.requestCount)
            assertFalse(account.avatarUrl.contains("account-token-fixture"))
        }
    }
}
