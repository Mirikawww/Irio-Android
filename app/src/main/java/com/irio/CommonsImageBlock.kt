package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.request.ImageRequest
import coil3.network.httpHeaders
import com.composables.ui.theme.colors
import com.composables.ui.theme.panelColor
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Horizontal thumbnails, the same size rule as images on a user message. */
@Composable
internal fun CommonsImageBlock(content: String) {
    val images = remember(content) {
        val array = runCatching { JSONObject(content).getJSONArray("images") }.getOrNull()
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }.take(5)
    }
    if (images.isEmpty()) return
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val frames = remember(content) {
        imageStripFrames(images.map { it.optInt("width") to it.optInt("height") })
    }
    var opened by remember(content) { mutableStateOf<ChatAttachment?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEachIndexed { index, image -> key(image.optString("id")) {
            val title = image.optString("title")
            val preview = httpsUrl(image.optString("preview_url"))
            val source = httpsUrl(image.optString("source_url")) ?: httpsUrl(image.optString("original_url"))
            val local = remember(content, image.optString("id")) {
                val name = File(image.optString("file_path")).name
                File(context.filesDir, "attachments/$name").takeIf { name.startsWith("commons-") && it.isFile }
            }
            val size = frames[index]
            val model = local ?: preview?.let { url ->
                ImageRequest.Builder(context).data(url).httpHeaders(
                    NetworkHeaders.Builder().set("User-Agent", CommonsImageTools.userAgent(context)).build(),
                ).build()
            }
            Box(
                Modifier.size(size.width, size.height).clip(RoundedRectangle(12.dp))
                    .background(Theme[colors][panelColor])
                    .clickable(enabled = local != null || source != null) {
                        if (local != null) opened = ChatAttachment(title, image.optString("mime_type", "image/jpeg"), local.absolutePath)
                        else source?.let { runCatching { uri.openUri(it) } }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (model != null) AsyncImage(
                    model = model,
                    contentDescription = title,
                    modifier = Modifier.size(size.width, size.height),
                    contentScale = ContentScale.Crop,
                )
            }
        } }
    }
    opened?.let { PhotoViewer(it, onDismiss = { opened = null }) }
}

/** One square and one landscape rectangle for the whole strip, both the same height. */
internal fun imageStripFrames(dimensions: List<Pair<Int, Int>>): List<DpSize> {
    val aspects = dimensions.map { (width, height) ->
        width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1)
    }
    fun square(aspect: Float) = aspect in 0.9f..1.12f
    val ratios = aspects.filterNot(::square).map { maxOf(it, 1f / it) }.sorted()
    val ratio = if (ratios.isEmpty()) 4f / 3f else ratios[ratios.size / 2].coerceIn(1.2f, 1.85f)
    val edge = IMAGE_STRIP_EDGE.dp
    val squareSize = DpSize(edge, edge)
    val rectangle = DpSize((IMAGE_STRIP_EDGE * ratio).dp, edge)
    return aspects.map { if (square(it)) squareSize else rectangle }
}

private const val IMAGE_STRIP_EDGE = 192

private fun httpsUrl(raw: String) = raw.toHttpUrlOrNull()?.takeIf {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.port == 443
}?.toString()
