package com.irio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme

@Composable
internal fun UpdateDownloadButton(
    state: UpdateDownloadState,
    available: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
) {
    val downloading = state.phase == UpdateDownloadPhase.Downloading
    val fraction = if (downloading) state.progress.fraction else 1f
    val label = if (downloading) "${(fraction * 100).toInt()}%" else
        tr(if (state.phase == UpdateDownloadPhase.Ready) "安装" else "下载")
    val fill = Theme[colors][primaryColor]
    val track = Theme[colors][secondaryColor]
    Button(
        onClick = { if (downloading) onCancel() else onDownload() },
        enabled = available,
        style = ButtonStyle.Ghost,
        shape = ContinuousCapsule,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.clip(ContinuousCapsule).drawBehind {
            drawRect(track)
            drawRect(fill, size = Size(size.width * fraction, size.height))
        }.then(if (downloading) Modifier.testTag("update-download-progress").semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            contentDescription = tr("取消下载")
        } else Modifier),
    ) {
        // Two identically placed labels retain contrast on both sides of the fill edge.
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Theme[colors][onPanelColor])
            Box(Modifier.matchParentSize().clearAndSetSemantics {}.drawWithContent {
                clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() }
            }, contentAlignment = Alignment.Center) {
                Text(label, color = Theme[colors][onPrimaryColor], modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}
