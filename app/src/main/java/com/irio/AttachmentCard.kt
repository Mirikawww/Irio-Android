package com.irio

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle
import java.io.File
import java.util.Locale

internal data class AttachmentCardName(val title: String, val extension: String)

internal fun attachmentCardName(name: String): AttachmentCardName {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return AttachmentCardName(name, "?")
    val extension = name.substring(dot + 1).lowercase(Locale.ROOT)
    return AttachmentCardName(
        title = name.substring(0, dot),
        extension = extension.takeIf { it.length <= 4 && it.all(Char::isLetterOrDigit) } ?: "?",
    )
}

/** One attachment surface for the composer, user messages and assistant messages. */
@Composable
internal fun AttachmentCard(
    attachment: ChatAttachment,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    uploadProgress: AttachmentUploadProgress? = null,
    onOpen: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // Share the entire card height, not only the inner extension tile.
    // Grow both surfaces together for accessibility font scaling.
    val cardHeight = with(LocalDensity.current) { (34.sp.toDp() + 18.dp).coerceAtLeast(56.dp) }
    val tileSize = cardHeight - 16.dp
    val name = remember(attachment.name) { attachmentCardName(attachment.name) }
    val badgeStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val measuredExtension = rememberTextMeasurer().measure(name.extension, badgeStyle, maxLines = 1)
    val extensionLabel = if (measuredExtension.size.width <= with(LocalDensity.current) { (tileSize - 8.dp).roundToPx() }) {
        name.extension
    } else {
        "?"
    }
    val size = remember(attachment.filePath, loading) {
        if (loading) null else attachment.size
    }
    // kyant Shapes RoundedRectangle uses G2 continuous corners, not a circular arc.
    val shape = remember { RoundedRectangle(8.dp) }
    val tileShape = remember { RoundedRectangle(6.dp) }
    val fill = Theme[colors][secondaryColor]
    val tileFill = Theme[colors][panelColor]
    val content = Theme[colors][onPanelColor]
    val muted = Theme[colors][mutedColor]
    // Reserve the badge's overhang in layout, so horizontal scrolling cannot clip it.
    val overhang = if (onRemove != null) 12.dp else 0.dp
    Box(modifier) {
        Box(Modifier.padding(top = overhang, end = overhang)) {
            val surface = Modifier
                .clip(shape)
                .background(fill)
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            if (attachment.isImage) {
                // Coil bounds decoding, applies image orientation and caches off the UI thread.
                Box {
                    AsyncImage(
                        model = File(attachment.filePath),
                        contentDescription = attachment.name,
                        contentScale = ContentScale.Crop,
                        modifier = surface.size(cardHeight),
                    )
                    if (uploadProgress != null) {
                        Box(
                            Modifier.size(cardHeight).clip(shape).background(Color.Black.copy(alpha = 0.48f)),
                            contentAlignment = Alignment.Center,
                        ) { CircularAttachmentUploadIndicator(uploadProgress) }
                    }
                }
            } else {
                Row(
                    modifier = surface.width(192.dp).height(cardHeight).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.size(tileSize).clip(tileShape).background(tileFill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            extensionLabel,
                            color = if (uploadProgress == null) content else content.copy(alpha = 0.28f),
                            style = badgeStyle,
                            maxLines = 1,
                        )
                        if (uploadProgress != null) {
                            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.48f), tileShape))
                            CircularAttachmentUploadIndicator(uploadProgress)
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            name.title,
                            color = content,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            size?.let { Formatter.formatShortFileSize(context, it) } ?: "…",
                            color = muted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                style = ButtonStyle.Secondary,
                buttonSize = ButtonSize.Small,
                shape = ContinuousCapsule,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .border(1.dp, Theme[colors][borderColor], ContinuousCapsule)
                    .semantics { contentDescription = tr("移除附件") },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = content,
                )
            }
        }
    }
}
