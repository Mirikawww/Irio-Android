package com.irio

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class UpdateDownloadProgress(val bytes: Long, val total: Long) {
    val fraction: Float get() = (bytes.toDouble() / total.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
}

internal class UpdateApkDownloader(
    private val http: OkHttpClient = updateHttpClient,
    private val allowUrl: (okhttp3.HttpUrl) -> Boolean = ::trustedUpdateUrl,
) {
    suspend fun download(asset: UpdateAsset, directory: File, onProgress: (UpdateDownloadProgress) -> Unit): File = withContext(Dispatchers.IO) {
        if (!asset.sha256.matches(Regex("[a-f0-9]{64}")) || asset.size !in 1..MAX_UPDATE_BYTES) throw IOException("更新信息无效")
        check(directory.mkdirs() || directory.isDirectory)
        val target = File(directory, "${asset.sha256}.apk")
        val partial = File.createTempFile("${asset.sha256}-", ".part", directory)
        try {
            var url = asset.url.toHttpUrl()
            var redirects = 0
            while (true) {
                if (!allowUrl(url)) throw IOException("更新下载地址不受信任")
                currentCoroutineContext().ensureActive()
                val call = http.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity")
                    .header("User-Agent", "Irio-Android-Updater").build())
                val cancelWatch = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                val response = call.awaitUpdateResponse()
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val next = response.header("Location")?.let(url::resolve)
                    response.close()
                    if (++redirects > 5 || next == null) throw IOException("更新下载重定向无效")
                    url = next
                    continue
                }
                response.use {
                    if (!it.isSuccessful) throw IOException("更新下载失败：HTTP ${it.code}")
                    val length = it.body.contentLength()
                    if (length >= 0 && length != asset.size) throw IOException("更新文件大小不匹配")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L

                    onProgress(UpdateDownloadProgress(0, asset.size))
                    it.body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                bytes += read
                                if (bytes > asset.size) throw IOException("更新文件大小不匹配")
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                // Publish each actual read: a byte-only batching threshold stalls
                                // progress on slow links even though data is arriving normally.
                                onProgress(UpdateDownloadProgress(bytes, asset.size))
                            }
                            output.fd.sync()
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                    if (bytes != asset.size || actual != asset.sha256) throw IOException("更新文件校验失败，请重新下载")
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return@withContext target
                } finally {
                    cancelWatch.cancel()
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } finally {
            partial.delete()
        }
    }
}
