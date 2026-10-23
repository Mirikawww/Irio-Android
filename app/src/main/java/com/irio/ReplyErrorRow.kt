package com.irio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.components.Icon
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme

@Composable
internal fun ReplyErrorRow(status: ChatRetryStatus) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = replyErrorIcon(status),
            contentDescription = tr("重试状态"),
            modifier = Modifier.size(20.dp),
            tint = irioErrorTextColor(),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(status.error, color = Theme[colors][onBackgroundColor], fontSize = 17.sp, lineHeight = 26.sp)
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
