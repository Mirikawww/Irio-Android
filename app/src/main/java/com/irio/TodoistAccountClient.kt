package com.irio

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class TodoistMcpAccount(val name: String, val avatarUrl: String, val email: String)

internal class TodoistAccountClient(
    private val token: String,
    private val baseUrl: HttpUrl = "https://api.todoist.com/".toHttpUrl(),
    private val cancellation: ChatRequestCancellation = ChatRequestCancellation(),
) {
    init { require(token.isNotBlank() && token.all { it.code in 33..126 }) }

    fun load(): TodoistMcpAccount {
        val call = client.newCall(Request.Builder().url(baseUrl.resolve("api/v1/user")!!)
            .header("Authorization", "Bearer $token").header("Accept", "application/json").get().build())
        cancellation.attach(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException(tr("无法读取 Todoist 账号（HTTP %d）").format(response.code))
                val source = response.body?.source() ?: throw IOException(tr("Todoist 账号响应无效"))
                if (source.request(65_537)) throw IOException(tr("Todoist 账号响应无效"))
                val profile = JSONObject(source.readUtf8())
                val name = profile.optString("full_name").takeUnless { it == "null" }.orEmpty()
                val email = profile.optString("email").takeUnless { it == "null" }.orEmpty()
                require((name.isNotBlank() || email.isNotBlank()) && !name.contains(token) && !email.contains(token)) {
                    tr("Todoist 账号响应无效")
                }
                val avatar = profile.optString("avatar_medium").toHttpUrlOrNull()?.takeIf {
                    it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() &&
                        !it.toString().contains(token) &&
                        (it.host == "todoist.com" || it.host.endsWith(".todoist.com") || it.host.endsWith(".cloudfront.net"))
                }?.toString().orEmpty()
                // Never retain the API's token field or the full profile response.
                return TodoistMcpAccount(name, avatar, email)
            }
        } finally { cancellation.detach(call) }
    }

    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
