package com.irio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class TodoistAccountClientTest {
    @Test fun loadsCurrentUserWithBearerAndOnlyKeepsDisplayFields() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"full_name":"Todoist User","email":"user@example.com","avatar_medium":"https://images.cloudfront.net/avatar.jpg","token":"not-for-display"}"""))
            val account = TodoistAccountClient("fixture-secret", server.url("/")).load()
            assertEquals(TodoistMcpAccount("Todoist User", "https://images.cloudfront.net/avatar.jpg", "user@example.com"), account)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/user", request.path)
            assertEquals("GET", request.method)
            assertEquals("Bearer fixture-secret", request.getHeader("Authorization"))
        }
    }

    @Test fun missingEmailFallsBackToNameAndUntrustedAvatarsAreDiscarded() {
        MockWebServer().use { server ->
            for (avatar in listOf("null", "http://images.cloudfront.net/avatar.jpg", "https://127.0.0.1/avatar.jpg",
                "https://images.cloudfront.net.attacker.test/avatar.jpg", "https://user:pass@images.cloudfront.net/avatar.jpg",
                "https://images.cloudfront.net/avatar.jpg?token=fixture-secret")) {
                server.enqueue(MockResponse().setBody("""{"full_name":"Todoist User","email":null,"avatar_medium":"$avatar"}"""))
                val account = TodoistAccountClient("fixture-secret", server.url("/")).load()
                assertEquals("Todoist User", account.name)
                assertEquals("", account.email)
                assertEquals("", account.avatarUrl)
            }
        }
    }

    @Test fun rejectsErrorsRedirectsOversizedAndEmptyProfiles() {
        MockWebServer().use { server ->
            for (response in listOf(MockResponse().setResponseCode(401),
                MockResponse().setResponseCode(302).setHeader("Location", "https://example.com"),
                MockResponse().setBody("x".repeat(65_537)))) {
                server.enqueue(response)
                assertThrows(IOException::class.java) { TodoistAccountClient("fixture-secret", server.url("/")).load() }
            }
            server.enqueue(MockResponse().setBody("{}"))
            assertThrows(IllegalArgumentException::class.java) { TodoistAccountClient("fixture-secret", server.url("/")).load() }
            server.enqueue(MockResponse().setBody("""{"full_name":"fixture-secret","email":"user@example.com"}"""))
            assertThrows(IllegalArgumentException::class.java) { TodoistAccountClient("fixture-secret", server.url("/")).load() }
        }
    }
}
