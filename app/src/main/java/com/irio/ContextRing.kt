package com.irio

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuPanel
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme

/** 用量来自接口 usage，窗口来自 OpenRouter `context_length`。点击弹出共计和已使用。压缩打开时才带压缩次数和压缩按钮。 */
@Composable
fun ContextUsageRing(
    usedTokens: Int,
    windowTokens: Int,
    compressionCount: Int,
    showCompression: Boolean,
    compressEnabled: Boolean,
    onCompress: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    strokeWidth: Dp = 2.dp,
) {
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier.wrapContentWidth(Alignment.End),
        alignment = DropdownMenuAlignment.End,
        panel = {
            DropdownMenuPanel(minWidth = 0.dp) {
                ContextRingMenu(
                    total = contextWindowLabel(windowTokens),
                    used = formatContextCount(usedTokens),
                    compressions = if (showCompression) formatContextCount(compressionCount) else null,
                    compressEnabled = compressEnabled,
                    onCompress = if (showCompression) {
                        {
                            onExpandedChange(false)
                            onCompress()
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        },
    ) {
        ContextRingCanvas(
            usedTokens = usedTokens,
            windowTokens = windowTokens,
            size = size,
            strokeWidth = strokeWidth,
            modifier = Modifier
                .clickable(role = Role.Button) { onExpandedChange(!expanded) }
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ContextRingCanvas(
    usedTokens: Int,
    windowTokens: Int,
    size: Dp,
    strokeWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val track = Theme[colors][mutedColor].copy(alpha = 0.28f)
    val fill = Theme[colors][primaryColor]
    val progress = if (windowTokens > 0) {
        (usedTokens.toFloat() / windowTokens.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "contextRingProgress",
    )
    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = if (windowTokens > 0) {
                    tr("上下文 %s / %s").format(formatContextCount(usedTokens), formatContextCount(windowTokens))
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
        if (animatedProgress > 0f) {
            drawArc(
                color = fill,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}

@Composable
private fun ContextRingMenu(
    total: String,
    used: String,
    compressions: String?,
    compressEnabled: Boolean,
    onCompress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val measurePolicy = remember { contextRingMenuMeasurePolicy() }
    Layout(
        content = {
            ContextStatTable(total = total, used = used, compressions = compressions)
            if (onCompress != null) {
                Button(
                    onClick = onCompress,
                    enabled = compressEnabled,
                    style = ButtonStyle.Primary,
                    buttonSize = ButtonSize.Small,
                ) {
                    Text(tr("压缩上下文"))
                }
            }
        },
        modifier = modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
        measurePolicy = measurePolicy,
    )
}

@Composable
private fun ContextStatTable(total: String, used: String, compressions: String?) {
    val measurePolicy = remember { contextStatMeasurePolicy() }
    Layout(
        content = {
            ContextStatText(tr("共计"))
            ContextStatText(total)
            ContextStatText(tr("已使用"))
            ContextStatText(used)
            if (compressions != null) {
                ContextStatText(tr("压缩次数"))
                ContextStatText(compressions)
            }
        },
        measurePolicy = measurePolicy,
    )
}

@Composable
private fun ContextStatText(text: String) {
    Text(
        text,
        color = Theme[colors][onPanelColor],
        fontSize = 15.sp,
        maxLines = 1,
    )
}

private fun contextRingMenuMeasurePolicy(): MeasurePolicy = object : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val gap = 10.dp.roundToPx()
        val statsWidth = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val buttonMeasurable = measurables.getOrNull(1)
        val buttonWidth = buttonMeasurable?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val contentWidth = maxOf(statsWidth, buttonWidth)
        val width = if (
            constraints.hasBoundedWidth &&
            constraints.maxWidth >= contentWidth &&
            constraints.maxWidth - contentWidth <= 2
        ) {
            constraints.maxWidth
        } else {
            contentWidth
        }.coerceAtLeast(constraints.minWidth).let { wanted ->
            if (constraints.hasBoundedWidth) wanted.coerceAtMost(constraints.maxWidth) else wanted
        }
        val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val exact = Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = maxHeight)
        val button = buttonMeasurable?.measure(exact)
        val actualGap = if (button == null) 0 else minOf(gap, (maxHeight - button.height).coerceAtLeast(0))
        val statsMaxHeight = if (constraints.hasBoundedHeight) {
            (maxHeight - (button?.height ?: 0) - actualGap).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        val stats = measurables[0].measure(exact.copy(maxHeight = statsMaxHeight))
        val height = constraints.constrainHeight(stats.height + actualGap + (button?.height ?: 0))
        return layout(width, height) {
            stats.placeRelative(0, 0)
            button?.placeRelative(0, stats.height + actualGap)
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int {
        val stats = measurables[0].maxIntrinsicWidth(height)
        val button = measurables.getOrNull(1)?.maxIntrinsicWidth(height) ?: 0
        return maxOf(stats, button)
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = maxIntrinsicWidth(measurables, height)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int {
        val stats = measurables[0].maxIntrinsicHeight(width)
        val button = measurables.getOrNull(1) ?: return stats
        val gap = 10.dp.roundToPx()
        return stats + gap + button.maxIntrinsicHeight(width)
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = maxIntrinsicHeight(measurables, width)
}

private fun contextStatMeasurePolicy(): MeasurePolicy = object : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val preferredGap = 16.dp.roundToPx()
        val intrinsicWidths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val contentWidth = statTableWidth(intrinsicWidths, preferredGap)
        val width = if (
            constraints.hasBoundedWidth &&
            constraints.maxWidth >= contentWidth &&
            constraints.maxWidth - contentWidth <= 2
        ) {
            constraints.maxWidth
        } else {
            contentWidth
        }.coerceAtLeast(constraints.minWidth).let { wanted ->
            if (constraints.hasBoundedWidth) wanted.coerceAtMost(constraints.maxWidth) else wanted
        }
        val rows = measurables.size / 2
        val gap = minOf(preferredGap, width)
        val rowGap = if (constraints.hasBoundedHeight && rows > 1) {
            minOf(8.dp.roundToPx(), constraints.maxHeight / (rows - 1))
        } else {
            8.dp.roundToPx()
        }
        val rowMaxHeight = if (constraints.hasBoundedHeight && rows > 0) {
            ((constraints.maxHeight - rowGap * (rows - 1)) / rows).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        val availableWidth = (width - gap).coerceAtLeast(0)
        val placeables = measurables.mapIndexed { index, measurable ->
            val labelIndex = index / 2 * 2
            val labelWidth = intrinsicWidths[labelIndex]
            val valueWidth = intrinsicWidths.getOrElse(labelIndex + 1) { 0 }
            // Keep both columns readable on narrow screens; never measure each
            // against the entire row width and overlap them during placement.
            val valueBudget = minOf(valueWidth, maxOf(availableWidth - labelWidth, availableWidth / 2))
            val columnWidth = if (index % 2 == 0) availableWidth - valueBudget else valueBudget
            measurable.measure(Constraints(maxWidth = columnWidth, maxHeight = rowMaxHeight))
        }
        val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
        val height = constraints.constrainHeight(if (rows == 0) 0 else rowHeight * rows + rowGap * (rows - 1))
        return layout(width, height) {
            for (index in 0 until rows) {
                val label = placeables[index * 2]
                val value = placeables[index * 2 + 1]
                val y = index * (rowHeight + rowGap)
                label.placeRelative(0, y + (rowHeight - label.height) / 2)
                value.placeRelative(width - value.width, y + (rowHeight - value.height) / 2)
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int {
        val gap = 16.dp.roundToPx()
        return statTableWidth(
            measurables.map { it.maxIntrinsicWidth(height) },
            gap,
        )
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = maxIntrinsicWidth(measurables, height)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int {
        val rowGap = 8.dp.roundToPx()
        val rows = measurables.size / 2
        if (rows == 0) return 0
        val rowHeight = measurables.maxOf { it.maxIntrinsicHeight(width) }
        return rowHeight * rows + rowGap * (rows - 1)
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = maxIntrinsicHeight(measurables, width)
}

private fun statTableWidth(widths: List<Int>, gap: Int): Int {
    var width = 0
    for (index in widths.indices step 2) {
        val label = widths[index]
        val value = widths.getOrNull(index + 1) ?: 0
        width = maxOf(width, label + gap + value)
    }
    return width
}

internal fun formatContextCount(count: Int): String {
    val sign = if (count < 0) "-" else ""
    val digits = kotlin.math.abs(count.toLong()).toString()
    return sign + digits.reversed().chunked(3).joinToString(",").reversed()
}

internal fun contextWindowLabel(windowTokens: Int): String =
    if (windowTokens > 0) formatContextCount(windowTokens) else "—"

/**
 * 压缩进行中不改数字。完成后、下一次接口 usage 到来前，用压缩结果的估算。
 * 估算不是压缩目标。真正在生成回复时才用流式估算，接口回报后再回到 apiUsed。
 */
internal fun contextRingUsedTokens(
    apiUsed: Int,
    liveEstimate: Int,
    generatingReply: Boolean,
    compressionEstimate: Int? = null,
): Int = when {
    generatingReply && liveEstimate > 0 -> liveEstimate
    compressionEstimate != null && compressionEstimate > 0 -> compressionEstimate
    else -> apiUsed
}
