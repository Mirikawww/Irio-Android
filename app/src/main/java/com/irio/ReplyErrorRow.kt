package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.components.Icon
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

@Composable
internal fun ReplyErrorRow(status: ChatRetryStatus) {
    val renderScope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(Theme[shapes][largeShape])
            .background(Theme[colors][secondaryColor])
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = replyErrorIcon(status),
            contentDescription = tr("重试状态"),
            modifier = Modifier.size(20.dp),
            tint = irioErrorTextColor(),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AssistantMarkdown(text = status.error, streaming = false, renderScope = renderScope)
            if (status.attempt > 0) {
                Text(chatRetryLine(status), color = Theme[colors][mutedColor], fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

internal fun replyErrorIcon(status: ChatRetryStatus): ImageVector = when {
    status.remainingSeconds != null -> IrioIcons.CloudAlert
    status.running -> IrioIcons.CloudBackup
    else -> IrioIcons.CloudOff
}
