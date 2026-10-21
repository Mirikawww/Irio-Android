package com.irio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** The entire serialized request is written, including history, not just the new file bytes. */
internal class UploadProgressRequestBody(
    private val bytes: ByteArray,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(64 * 1024, bytes.size - offset)
            sink.write(bytes, offset, count)
            offset += count
            if (offset == bytes.size) sink.emit()
            onProgress(offset.toLong(), bytes.size.toLong())
        }
    }
}

internal data class AttachmentUploadProgress(
    val requestId: Long,
    val sentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val visible: Boolean = true,
) {
    val percent: Int get() = if (totalBytes == 0L) 0 else
        (sentBytes * 100L / totalBytes).toInt().coerceIn(0, 100)
}

/** UI-only state; the request ID prevents an old retry from clearing a newer one. */
internal class AttachmentUploadTracker {
    private val states = mutableStateMapOf<Pair<String, Long>, AttachmentUploadProgress>()

    fun progress(conversationId: String, messageId: Long): AttachmentUploadProgress? =
        states[conversationId to messageId]?.takeIf { it.visible }

    fun prepare(conversationId: String, messageId: Long, requestId: Long) {
        states[conversationId to messageId] = AttachmentUploadProgress(requestId)
    }

    fun advance(conversationId: String, messageId: Long, requestId: Long, sent: Long, total: Long) {
        val key = conversationId to messageId
        val current = states[key] ?: return
        if (current.requestId != requestId || total <= 0L) return
        states[key] = current.copy(sentBytes = sent.coerceIn(0L, total), totalBytes = total, visible = true)
    }

    fun hideCompleted(conversationId: String, messageId: Long, requestId: Long) {
        val key = conversationId to messageId
        val current = states[key] ?: return
        if (current.requestId == requestId && current.totalBytes > 0L && current.sentBytes == current.totalBytes) {
            states[key] = current.copy(visible = false)
        }
    }

    fun finish(conversationId: String, messageId: Long, requestId: Long) {
        val key = conversationId to messageId
        if (states[key]?.requestId == requestId) states.remove(key)
    }
}

@Composable
internal fun CircularAttachmentUploadIndicator(progress: AttachmentUploadProgress) {
    val preparing = progress.totalBytes == 0L
    val fraction = if (preparing) 0f else progress.sentBytes.toFloat() / progress.totalBytes.toFloat()
    val rotation = if (preparing) {
        val transition = rememberInfiniteTransition(label = "attachment preparation")
        val animated by transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Restart),
            label = "attachment preparation rotation",
        )
        animated
    } else 0f
    Canvas(Modifier.size(24.dp).semantics {
        contentDescription = if (preparing) tr("正在准备附件") else tr("附件请求发送中 %d%%").format(progress.percent)
        progressBarRangeInfo = if (preparing) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction, 0f..1f)
    }) {
        val stroke = 2.5.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.3f), radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
        if (preparing || fraction > 0f) {
            drawArc(
                color = Color.White,
                startAngle = if (preparing) rotation - 90f else -90f,
                sweepAngle = if (preparing) 100f else fraction * 360f,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
