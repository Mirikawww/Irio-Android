package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderEndpointsTest {
    @Test
    fun originStripsChatPath() {
        assertEquals(
            "https://api.openai.com",
            ProviderEndpoints.origin("https://api.openai.com/v1/chat/completions"),
        )
    }

    @Test
    fun originKeepsCustomPort() {
        assertEquals(
            "http://127.0.0.1:11434",
            ProviderEndpoints.origin("http://127.0.0.1:11434/v1"),
        )
    }

    @Test
    fun originIgnoresUserInfoAndQuery() {
        assertEquals(
            "https://gateway.example.com",
            ProviderEndpoints.origin("https://gateway.example.com/v1?foo=1"),
        )
    }

    @Test
    fun chatUrlMatchesLegacyRequestUrl() {
        val p = ProviderConfig(
            id = "1", name = "x",
            endpointUrl = "https://api.openai.com/v1",
            apiKey = "k",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ProviderEndpoints.chatUrl(p),
        )
    }

    @Test
    fun anthropicMessagesUrl() {
        val p = ProviderConfig(
            id = "1", name = "x",
            endpointUrl = "https://api.anthropic.com",
            apiKey = "k",
            protocol = ProviderProtocol.ANTHROPIC,
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            ProviderEndpoints.chatUrl(p),
        )
    }

    @Test
    fun originMalformedDoesNotThrow() {
        val raw = "https://api.example.com/v1 path"
        assertEquals(raw, ProviderEndpoints.origin(raw))
    }
}
