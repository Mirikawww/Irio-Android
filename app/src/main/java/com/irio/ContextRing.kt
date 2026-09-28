package com.irio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme

/** 只读进度环：用量来自接口 usage，窗口来自 models.dev `limit.context`。暂不可点。 */
@Composable
fun ContextUsageRing(
    usedTokens: Int,
    windowTokens: Int,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    strokeWidth: Dp = 2.dp,
) {
    val track = Theme[colors][mutedColor].copy(alpha = 0.28f)
    val fill = Theme[colors][primaryColor]
    val progress = if (windowTokens > 0) {
        (usedTokens.toFloat() / windowTokens.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = if (windowTokens > 0) {
                    tr("上下文 %s / %s").format(usedTokens, windowTokens)
                } else {
                    tr("上下文用量未知")
                }
            },
    ) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        if (progress > 0f) {
            drawArc(
                color = fill,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}
