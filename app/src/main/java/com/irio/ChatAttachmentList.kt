package com.irio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/** Sent attachments scroll with their gutters inside the viewport. */
@Composable
internal fun ChatAttachmentList(
    attachments: List<ChatAttachment>,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    startOutset: Dp = 0.dp,
    endOutset: Dp = startOutset,
    contentPadding: PaddingValues = PaddingValues(start = startOutset, end = endOutset),
    uploadProgress: AttachmentUploadProgress? = null,
    onOpen: ((ChatAttachment) -> Unit)? = null,
) {
    Box(modifier) {
        Row(
            modifier = Modifier
                .padding(bottom = if (uploadProgress != null) 6.dp else 0.dp)
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
            attachments.forEach { attachment ->
                if (attachment.isGeneratedVideo) {
                    GeneratedVideoAttachmentCard(attachment, onOpen = onOpen)
                } else AttachmentChip(
                    attachment = attachment,
                    onOpen = onOpen?.let { open -> { open(attachment) } },
                )
            }
        }
        if (uploadProgress != null) {
            // Match the visible list width without widening a short row or scrolling with its chips.
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                LinearAttachmentUploadIndicator(uploadProgress)
            }
        }
    }
}
