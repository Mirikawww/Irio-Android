package com.irio

import android.os.Build
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal const val IRIO_RELEASES_URL = "https://irio.relithy.space/releases/"
internal const val IRIO_UPDATE_ENDPOINT = "https://irio.relithy.space/releases/api/android-updates"
internal const val MAX_UPDATE_BYTES = 2_147_483_648L

internal data class UpdateAsset(val abi: String, val url: String, val sha256: String, val size: Long, val packageName: String)

internal fun trustedUpdateUrl(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
    url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
    ((url.host == "irio.relithy.space" && (url.encodedPath.startsWith("/releases/download/") || url.encodedPath.startsWith("/release-files/"))) ||
        (url.host == "downloads.relithy.space" && url.encodedPath.startsWith("/release-files/")))

internal fun parseReleaseUpdates(json: String, abis: List<String>, channel: UpdateChannel): List<GitHubUpdate> {
    fun invalid(): Nothing = throw UpdateCheckException("更新信息无效")
    try {
        val root = JSONObject(json)
        if (root.getInt("schemaVersion") != 1) invalid()
        val releases = root.getJSONArray("releases")
        if (releases.length() > 1000) invalid()
        return (0 until releases.length()).mapNotNull { index ->
            val release = releases.getJSONObject(index)
            val versionName = release.getString("versionName")
            if (UpdateVersion.parse(versionName) == null) invalid()
            val code = release.getInt("versionCode")
            val kind = release.getString("channel")
            if (code <= 0 || kind !in setOf("iota", "gamma")) invalid()
            if (channel == UpdateChannel.RELEASE && kind != "iota") return@mapNotNull null
            val candidates = release.getJSONArray("assets")
            val assets = (0 until candidates.length()).map { i ->
                val item = candidates.getJSONObject(i)
                val url = item.getString("url").toHttpUrlOrNull() ?: invalid()
                val digest = item.getString("sha256")
                val size = item.getLong("size")
                val packageName = item.getString("packageName")
                val abi = item.getString("abi")
                if (!trustedUpdateUrl(url) || !digest.matches(Regex("[a-fA-F0-9]{64}")) ||
                    size !in 1..MAX_UPDATE_BYTES || packageName != "com.irio" ||
                    abi !in setOf("universal", "arm64-v8a", "armeabi-v7a")) invalid()
                UpdateAsset(abi, url.toString(), digest.lowercase(), size, packageName)
            }
            val asset = abis.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.abi == abi } }
                ?: assets.firstOrNull { it.abi == "universal" } ?: return@mapNotNull null
            GitHubUpdate(versionName, IRIO_RELEASES_URL, kind == "gamma", "", code, asset)
        }.sortedByDescending { it.versionCode }
    } catch (e: UpdateCheckException) {
        throw e
    } catch (_: Exception) {
        invalid()
    }
}

internal class ReleaseUpdateClient(
    private val endpoint: HttpUrl = IRIO_UPDATE_ENDPOINT.toHttpUrl(),
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList() ?: listOf("universal"),
    private val http: OkHttpClient = updateHttpClient,
) {
    suspend fun check(currentVersion: String, channel: UpdateChannel, currentCode: Int = 0): UpdateCheckResult {
        val current = UpdateVersion.parse(currentVersion.removeSuffix("-debug"))
            ?: throw UpdateCheckException("无法识别当前版本")
        val releases = load(channel)
        val newest = releases.firstOrNull() ?: return UpdateCheckResult.NoReleases
        val newer = if (currentCode > 0) newest.versionCode > currentCode else UpdateVersion.parse(newest.tag)!! > current
        return if (newer) UpdateCheckResult.Available(newest) else UpdateCheckResult.UpToDate
    }

    /** A Debug-only preview downloads an actual listed build without pretending it is newer. */
    suspend fun preview(channel: UpdateChannel): GitHubUpdate? = load(channel).firstOrNull()?.copy(testDownload = true)

    private suspend fun load(channel: UpdateChannel): List<GitHubUpdate> = withContext(Dispatchers.IO) {
        val body = http.newCall(Request.Builder().url(endpoint).header("Accept", "application/json")
            .header("User-Agent", "Irio-Android-Updater").build()).awaitUpdateResponse().use { response ->
            if (!response.isSuccessful) throw UpdateCheckException("检查更新失败，请稍后重试")
            val source = response.body.source()
            if (source.request(4_194_305)) throw UpdateCheckException("更新信息无效")
            source.readUtf8()
        }
        parseReleaseUpdates(body, supportedAbis, channel)
    }
}

internal val updateHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()

internal suspend fun Call.awaitUpdateResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}
