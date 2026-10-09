package com.irio

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal class GitHubCredential(val accessToken: String, val expiresAt: Long = 0)
internal class GitHubDeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresIn: Long, val interval: Long)
internal class GitHubDeviceAuth(private val baseUrl: HttpUrl = "https://github.com/".toHttpUrl()) {
    val cancellation = ChatRequestCancellation()

    suspend fun requestCode(): GitHubDeviceCode {
        val result = post("login/device/code", mapOf("client_id" to CLIENT_ID, "scope" to "repo read:org user:email"))
        if (result.has("error")) throw authError(result.optString("error"))
        val uri = result.getString("verification_uri")
        require(uri == "https://github.com/login/device") { tr("GitHub 授权地址无效") }
        return GitHubDeviceCode(result.getString("device_code"), result.getString("user_code"), uri,
            result.getLong("expires_in").coerceIn(1, 1800), result.getLong("interval").coerceIn(1, 60))
    }

    suspend fun awaitToken(code: GitHubDeviceCode): GitHubCredential {
        var interval = code.interval
        var waited = 0L
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(code.expiresIn)
        while (waited + interval < code.expiresIn && System.nanoTime() < deadline) {
            delay(interval * 1000)
            waited += interval
            coroutineContext.ensureActive()
            val result = post("login/oauth/access_token", mapOf("client_id" to CLIENT_ID,
                "device_code" to code.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            when (val error = result.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = maxOf(interval + 5, result.optLong("interval", interval + 5))
                "" -> {
                    val token = result.getString("access_token")
                    require(token.isNotBlank() && result.optString("token_type").equals("bearer", true)) {
                        tr("GitHub 登录响应无效")
                    }
                    val expires = result.optLong("expires_in", 0)
                    return GitHubCredential(token, if (expires > 0) System.currentTimeMillis() + expires * 1000 else 0)
                }
                else -> throw authError(error)
            }
        }
        throw authError("expired_token")
    }

    private suspend fun post(path: String, values: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { values.forEach { (key, value) -> add(key, value) } }.build()
        val call = client.newCall(Request.Builder().url(baseUrl.resolve(path)!!)
            .header("Accept", "application/json").post(form).build())
        cancellation.attach(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException(tr("GitHub 登录失败（HTTP %d）").format(response.code))
                val bytes = response.body?.byteStream()?.use { it.readNBytes(65_537) }
                    ?: throw IOException(tr("GitHub 登录响应无效"))
                if (bytes.size > 65_536) throw IOException(tr("GitHub 登录响应无效"))
                JSONObject(bytes.toString(Charsets.UTF_8))
            }
        } finally {
            cancellation.detach(call)
        }
    }

    private fun authError(code: String): IOException = IOException(when (code) {
        "access_denied" -> tr("GitHub 授权已取消，可重新登录")
        "expired_token", "token_expired" -> tr("GitHub 授权码已过期，请重试")
        "device_flow_disabled" -> tr("请在 Irio 的 GitHub 应用设置中启用 Device Flow")
        "incorrect_client_credentials" -> tr("GitHub Client ID 无效")
        else -> tr("GitHub 登录失败，请重试")
    })

    companion object {
        const val CLIENT_ID = "Ov23liyvVnA5MQa4yO4T"
        private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
