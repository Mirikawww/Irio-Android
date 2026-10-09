package com.irio

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class GitHubMcpAccount(val login: String, val avatarUrl: String, val email: String)

internal class GitHubAccountClient(
    private val token: String,
    private val baseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val cancellation: ChatRequestCancellation = ChatRequestCancellation(),
) {
    init { require(token.isNotBlank() && token.all { it.code in 33..126 }) }

    fun load(): GitHubMcpAccount {
        val profile = JSONObject(get("user") ?: throw IOException(tr("GitHub 账号响应无效")))
        val login = profile.getString("login")
        require(login.isNotBlank() && !login.contains(token))
        val avatar = profile.optString("avatar_url").toHttpUrlOrNull()?.takeIf {
            it.scheme == "https" && it.host in setOf("avatars.githubusercontent.com", "github.com") &&
                it.username.isEmpty() && it.password.isEmpty() && !it.toString().contains(token)
        }?.toString().orEmpty()
        val email = profile.optString("email").takeUnless { it == "null" }.orEmpty().ifBlank { privateEmail() }
        require(!email.contains(token))
        return GitHubMcpAccount(login, avatar, email)
    }

    private fun privateEmail(): String {
        var fallback = ""
        var page = 1
        while (true) {
            val body = get("user/emails?per_page=100&page=$page", allowMissingPermission = true) ?: return fallback
            val emails = JSONArray(body)
            for (i in 0 until emails.length()) {
                val item = emails.getJSONObject(i)
                val email = item.optString("email").takeUnless { it == "null" }.orEmpty()
                if (item.optBoolean("primary") && email.isNotBlank()) return email
                if (fallback.isBlank() && item.optBoolean("verified")) fallback = email
            }
            if (emails.length() < 100) return fallback
            page++
        }
    }

    private fun get(path: String, allowMissingPermission: Boolean = false): String? {
        val call = client.newCall(Request.Builder().url(baseUrl.resolve(path)!!)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10").get().build())
        cancellation.attach(call)
        try {
            call.execute().use { response ->
                if (allowMissingPermission && response.code in setOf(403, 404)) return null
                if (!response.isSuccessful) throw IOException(tr("无法读取 GitHub 账号（HTTP %d）").format(response.code))
                val source = response.body?.source()
                    ?: throw IOException(tr("GitHub 账号响应无效"))
                if (source.request(65_537)) throw IOException(tr("GitHub 账号响应无效"))
                return source.readUtf8()
            }
        } finally { cancellation.detach(call) }
    }

    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
