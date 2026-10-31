package com.irio

import java.net.URI

internal object ProviderEndpoints {
    fun origin(endpointUrl: String): String {
        val trimmed = endpointUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: return trimmed
        val port = uri.port
        return if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
    }

    fun apiBase(endpointUrl: String): String {
        val clean = endpointUrl.trim().trimEnd('/')
        return when {
            clean.endsWith("/chat/completions") -> clean.removeSuffix("/chat/completions")
            clean.endsWith("/responses") -> clean.removeSuffix("/responses")
            clean.endsWith("/messages") -> clean.removeSuffix("/messages")
            clean.endsWith("/models") -> clean.removeSuffix("/models")
            else -> clean
        }.trimEnd('/')
    }

    fun chatUrl(provider: ProviderConfig, model: String = "", streaming: Boolean = true): String {
        val base = if (provider.protocol.isGoogleGemini) geminiBase(provider.endpointUrl) else apiBase(provider.endpointUrl)
        return when (provider.protocol) {
            ProviderProtocol.MINIMAX_VIDEO -> throw IllegalArgumentException("MiniMax Video Generation V2 does not support chat requests")
            ProviderProtocol.GEMINI_INTERACTIONS -> "$base/interactions" + if (streaming) "?alt=sse" else ""
            ProviderProtocol.GEMINI -> {
                val id = model.removePrefix("models/")
                require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it in "-_." }) { "Invalid Gemini model ID" }
                val action = if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
                "$base/models/$id:$action"
            }
            ProviderProtocol.CHAT_COMPLETIONS -> "$base/chat/completions"
            ProviderProtocol.RESPONSES -> "$base/responses"
            ProviderProtocol.ANTHROPIC ->
                if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        }
    }

    fun geminiBase(endpointUrl: String): String {
        val clean = endpointUrl.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        val base = apiBase(clean.removeSuffix("/interactions").replace(Regex("/models/[^/]+:(?:streamGenerateContent|generateContent)$"), ""))
        return if (base.endsWith("/v1beta") || base.endsWith("/v1")) base else "$base/v1beta"
    }
}
