package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle
import java.io.File
import java.util.Locale

@Composable
internal fun AttachmentChip(
    attachment: ChatAttachment,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onOpen: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val ink = Theme[colors][onPanelColor]
    val thumbnailShape = remember { RoundedRectangle(6.dp) }
    val chipShape = remember { RoundedRectangle(11.dp) }
    Row(
        modifier.heightIn(min = 36.dp)
            .background(Theme[colors][panelColor], chipShape)
            .then(if (onOpen != null) Modifier.clip(chipShape).clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .padding(start = 10.dp, end = if (trailing != null) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).clip(thumbnailShape)
                .then(if (attachment.isImage && trailing != null) Modifier.background(Theme[colors][fieldColor]) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (attachment.isImage) {
                AsyncImage(
                    model = remember(attachment.filePath) { File(attachment.filePath) },
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                val extension = remember(attachment.name) { attachmentCardName(attachment.name).extension }
                val icon = attachmentFileIcon(extension, attachment.mimeType)
                if (icon != null) {
                    AttachmentFileGlyph(icon, extension, ink)
                } else {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Text(extension.uppercase(Locale.ROOT), fontSize = 9.sp, color = ink, maxLines = 1)
                    }
                }
            }
        }
        Text(
            attachment.name,
            modifier = Modifier.padding(start = 6.dp).widthIn(max = 156.dp),
            color = ink,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (loading) Text("…", modifier = Modifier.padding(start = 4.dp), color = ink)
        trailing?.invoke(this)
    }
}
