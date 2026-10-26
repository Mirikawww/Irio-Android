package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.kyant.shapes.RoundedRectangle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onSecondaryColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme

@Composable
internal fun MessageQuotePreview(
    quote: String,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .then(if (onCancel != null) Modifier.fillMaxWidth() else Modifier)
            .background(Theme[colors][secondaryColor], RoundedRectangle(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = IrioIcons.Quote,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Theme[colors][mutedColor],
        )
        Column(Modifier.weight(1f, fill = onCancel != null), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr("引用"), color = Theme[colors][mutedColor], fontSize = 12.sp)
            Text(
                quote,
                color = Theme[colors][onSecondaryColor],
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onCancel != null) {
            IconButton(
                onClick = onCancel,
                style = ButtonStyle.Ghost,
                buttonSize = ButtonSize.Small,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = IrioIcons.X,
                    contentDescription = tr("取消引用"),
                    modifier = Modifier.size(16.dp),
                    tint = Theme[colors][mutedColor],
                )
            }
        }
    }
}
