package com.irio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/** Pending and sent attachments share a viewport with gutters inside the scrolling content. */
@Composable
internal fun ChatAttachmentList(
    attachments: List<ChatAttachment>,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    startOutset: Dp = 0.dp,
    endOutset: Dp = startOutset,
    contentPadding: PaddingValues = PaddingValues(start = startOutset, end = endOutset),
    loadingFiles: Set<String> = emptySet(),
    uploadProgress: AttachmentUploadProgress? = null,
    onOpen: ((ChatAttachment) -> Unit)? = null,
    onRemove: ((ChatAttachment) -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .layout { measurable, constraints ->
                val start = startOutset.roundToPx()
                val horizontal = start + endOutset.roundToPx()
                val strip = measurable.measure(constraints.offset(horizontal = horizontal))
                layout(constraints.constrainWidth(strip.width - horizontal), strip.height) {
                    strip.placeRelative(-start, 0)
                }
            }
            .horizontalScroll(rememberScrollState(), reverseScrolling = alignEnd)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        attachments.forEachIndexed { index, attachment ->
            AttachmentCard(
                attachment = attachment,
                loading = attachment.filePath in loadingFiles,
                uploadProgress =
                    if (alignEnd && index == attachments.lastIndex) uploadProgress else null,
                onOpen = onOpen?.let { open -> { open(attachment) } },
                onRemove = onRemove?.let { remove -> { remove(attachment) } },
            )
        }
    }
}
