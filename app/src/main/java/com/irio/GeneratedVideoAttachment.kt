package com.irio

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downloads never receive provider credentials, including on CDN redirects. */
internal object GeneratedVideoDownloads {
    fun target(context: Context, attachment: ChatAttachment): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(attachment.remoteUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "attachments/video-$hash.mp4")
    }

    fun local(context: Context, attachment: ChatAttachment): ChatAttachment? {
        if (attachment.downloaded) return attachment
        val file = target(context, attachment)
        return if (file.isFile && file.length() > 0L) attachment.copy(filePath = file.absolutePath) else null
    }

    fun download(context: Context, attachment: ChatAttachment, cancellation: ChatRequestCancellation): ChatAttachment {
        local(context, attachment)?.let { return it }
        val url = URL(attachment.remoteUrl)
        require(url.protocol in setOf("https", "http") && url.userInfo == null) { tr("视频下载地址无效") }
        val target = target(context, attachment)
        target.parentFile?.mkdirs()
        val partial = File.createTempFile("video-", ".part", target.parentFile)
        val connection = url.openConnection() as HttpURLConnection
        cancellation.attach(connection)
        try {
            check(!cancellation.isCancelled()) { tr("下载已停止") }
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            require(connection.responseCode in 200..299) { tr("视频下载失败（HTTP %s）").format(connection.responseCode) }
            val mime = connection.contentType?.substringBefore(';').orEmpty()
            require(!mime.startsWith("text/") && mime != "application/json") { tr("下载地址未返回视频") }
            require(connection.contentLengthLong <= ChatApi.MAX_ATTACHMENT_BYTES_TOTAL) { tr("视频文件过大") }
            var count = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(!cancellation.isCancelled()) { tr("下载已停止") }
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= ChatApi.MAX_ATTACHMENT_BYTES_TOTAL) { tr("视频文件过大") }
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(count > 0L && (connection.contentLengthLong < 0 || count == connection.contentLengthLong)) {
                tr("视频下载不完整，请重试")
            }
            check(!cancellation.isCancelled()) { tr("下载已停止") }
            check(partial.renameTo(target)) { tr("无法保存视频") }
            return attachment.copy(filePath = target.absolutePath)
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
            partial.delete()
        }
    }
}

@Composable
internal fun GeneratedVideoAttachmentCard(
    attachment: ChatAttachment,
    modifier: Modifier = Modifier,
    onOpen: ((ChatAttachment) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var local by remember(attachment.remoteUrl, attachment.filePath) { mutableStateOf(GeneratedVideoDownloads.local(context, attachment)) }
    var downloading by remember(attachment.remoteUrl) { mutableStateOf(false) }
    var error by remember(attachment.remoteUrl) { mutableStateOf<String?>(null) }
    val cancellation = remember(attachment.remoteUrl) { ChatRequestCancellation() }
    DisposableEffect(cancellation) { onDispose { cancellation.cancel() } }
    AttachmentCard(
        attachment = local ?: attachment,
        modifier = modifier,
        fileCardWidth = 280.dp,
        onOpen = local?.let { ready -> onOpen?.let { { it(ready) } } },
        onDownload = if (downloading || local != null) null else ({
            downloading = true
            error = null
            scope.launch {
                try {
                    local = withContext(Dispatchers.IO) { GeneratedVideoDownloads.download(context, attachment, cancellation) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: tr("视频下载失败")
                } finally {
                    downloading = false
                }
            }
        }),
        downloadState = when { downloading -> tr("下载中"); local != null -> tr("已下载"); else -> tr("下载视频") },
        statusText = error ?: when { downloading -> tr("下载中"); local == null -> tr("点击右侧下载"); else -> null },
    )
}
