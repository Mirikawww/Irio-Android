package com.irio

import android.content.Context
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException

internal enum class UpdateChannel(val label: String) {
    RELEASE("Release"), PRE_RELEASE("Pre-release");

    companion object {
        fun load(context: Context): UpdateChannel =
            entries.firstOrNull { it.name == context.getSharedPreferences("updates", Context.MODE_PRIVATE)
                .getString("channel", null) } ?: RELEASE
    }

    fun save(context: Context) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("channel", name).apply()
    }
}

/** Semantic ordering, not lexical ordering or GitHub's publication date. */
internal data class UpdateVersion(
    private val numbers: List<BigInteger>,
    val prerelease: List<String>,
) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int {
        numbers.zip(other.numbers).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        prerelease.zip(other.prerelease).forEach { (a, b) ->
            val an = a.toBigIntegerOrNull()
            val bn = b.toBigIntegerOrNull()
            val result = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
        fun parse(raw: String): UpdateVersion? {
            val match = pattern.matchEntire(raw.trim()) ?: return null
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return UpdateVersion((1..3).map { match.groupValues[it].toBigInteger() }, pre)
        }
    }
}

internal data class GitHubUpdate(
    val tag: String,
    val url: String,
    val prerelease: Boolean,
    val notes: String = "",
)

internal sealed interface UpdateCheckResult {
    data class Available(val release: GitHubUpdate) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object NoReleases : UpdateCheckResult
}

internal class UpdateCheckException(val messageKey: String) : IOException(messageKey)

/** Public release metadata only. Never borrows the user's GitHub/MCP credentials. */
internal class GitHubUpdateClient(
    private val http: OkHttpClient = publicClient,
    private val baseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
) {
    suspend fun check(currentVersion: String, channel: UpdateChannel): UpdateCheckResult = withContext(Dispatchers.IO) {
        val current = UpdateVersion.parse(currentVersion.removeSuffix("-debug"))
            ?: throw UpdateCheckException("无法识别当前版本")
        var latest: Pair<UpdateVersion, GitHubUpdate>? = null
        for (page in 1..10) {
            val url = baseUrl.resolve("repos/Mirikawww/Irio-Android/releases")!!.newBuilder()
                .addQueryParameter("per_page", "100").addQueryParameter("page", page.toString()).build()
            val releases = try { JSONArray(get(url)) } catch (_: JSONException) {
                throw UpdateCheckException("GitHub 更新响应无效")
            }
            for (index in 0 until releases.length()) {
                val item = releases.optJSONObject(index) ?: throw UpdateCheckException("GitHub 更新响应无效")
                if (item.optBoolean("draft")) continue
                val tag = item.optString("tag_name")
                val version = UpdateVersion.parse(tag) ?: continue
                val pre = item.optBoolean("prerelease") || version.prerelease.isNotEmpty()
                if (channel == UpdateChannel.RELEASE && pre) continue
                val link = item.optString("html_url").toHttpUrlOrNull()?.takeIf {
                    it.isHttps && it.host == "github.com" && it.port == 443 &&
                        it.username.isEmpty() && it.password.isEmpty() &&
                        it.encodedPath.startsWith("/Mirikawww/Irio-Android/releases/tag/")
                } ?: throw UpdateCheckException("GitHub 更新响应无效")
                if (latest == null || version > latest.first) {
                    latest = version to GitHubUpdate(tag, link.toString(), pre, item.opt("body") as? String ?: "")
                }
            }
            if (releases.length() < 100) {
                val newest = latest ?: return@withContext UpdateCheckResult.NoReleases
                return@withContext if (newest.first > current) UpdateCheckResult.Available(newest.second)
                    else UpdateCheckResult.UpToDate
            }
        }
        // Never silently report up-to-date after examining an incomplete release list.
        throw UpdateCheckException("GitHub 更新列表过长，请前往项目发布页检查")
    }

    private suspend fun get(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "Irio-Android-UpdateChecker").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (it.code == 429 || (it.code == 403 &&
                                (it.header("X-RateLimit-Remaining") == "0" || it.header("Retry-After") != null))) {
                            throw UpdateCheckException("GitHub 请求过于频繁，请稍后重试")
                        }
                        if (it.code == 404) throw UpdateCheckException("无法访问 GitHub 发布信息")
                        if (!it.isSuccessful) throw UpdateCheckException("检查更新失败，请稍后重试")
                        val source = it.body.source()
                        if (source.request(4_194_305)) throw UpdateCheckException("GitHub 更新响应无效")
                        source.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    companion object {
        private val publicClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
}
