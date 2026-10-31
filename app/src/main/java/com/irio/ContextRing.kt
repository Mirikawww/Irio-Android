package com.irio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.irio.ScaledDropdownMenuPanel as DropdownMenuPanel
import com.composables.ui.components.Text
import com.composables.ui.theme.ColorScheme
import com.composables.ui.theme.LocalColorScheme
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme
import com.composeunstyled.LocalContentColor

/** 用量来自接口 usage，窗口来自 OpenRouter `context_length`。点击弹出分段用量。压缩打开时才带压缩次数和压缩按钮。 */
@Composable
internal fun ContextUsageRing(
    breakdown: ContextUsageBreakdown,
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
                    breakdown = breakdown,
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
            usedTokens = breakdown.usedTokens,
            windowTokens = breakdown.windowTokens,
            size = size,
            strokeWidth = strokeWidth,
            modifier = Modifier
                .clip(ToolbarCapsuleShape)
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
        animationSpec = irioSpring<Float>(),
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

private val ContextSystemColor = Color(0xFF2FBF71)
private val ContextToolsColor = Color(0xFF5B8DEF)
private val ContextMessagesColor = Color(0xFFE0B341)

private data class ContextLegendEntry(val label: String, val value: String, val dot: Color?)

@Composable
private fun ContextRingMenu(
    breakdown: ContextUsageBreakdown,
    compressions: String?,
    compressEnabled: Boolean,
    onCompress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val measurePolicy = remember { contextRingMenuMeasurePolicy() }
    val dark = LocalColorScheme.current == ColorScheme.Dark
    val freeColor = if (dark) Color(0xFF4A4A4A) else Color(0xFFD0D0D0)
    val reservedColor = if (dark) Color(0xFF8A8A8A) else Color(0xFFB5B5B5)
    val knownWindow = breakdown.windowTokens > 0
    val entries = buildList {
        add(ContextLegendEntry(tr("系统提示"), formatContextCompact(breakdown.systemTokens), ContextSystemColor))
        add(ContextLegendEntry(tr("工具"), formatContextCompact(breakdown.toolTokens), ContextToolsColor))
        add(ContextLegendEntry(tr("消息"), formatContextCompact(breakdown.messageTokens), ContextMessagesColor))
        add(ContextLegendEntry(tr("空闲"), if (knownWindow) formatContextCompact(breakdown.freeTokens) else "—", freeColor))
        add(ContextLegendEntry(tr("预留"), if (knownWindow) formatContextCompact(breakdown.reservedTokens) else "—", reservedColor))
        if (compressions != null) add(ContextLegendEntry(tr("压缩次数"), compressions, null))
    }
    Layout(
        content = {
            Column(
                modifier = Modifier.widthIn(min = 220.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContextStatText(
                        "${formatContextCompact(breakdown.usedTokens)} / ${
                            if (knownWindow) formatContextCompact(breakdown.windowTokens) else "—"
                        }",
                    )
                    breakdown.percent?.let { ContextStatText("$it%") }
                }
                ContextUsageBar(
                    segments = listOf(
                        ContextSystemColor to breakdown.systemTokens,
                        ContextToolsColor to breakdown.toolTokens,
                        ContextMessagesColor to breakdown.messageTokens,
                        freeColor to if (knownWindow) breakdown.freeTokens else 0,
                        reservedColor to if (knownWindow) breakdown.reservedTokens else 0,
                    ),
                )
                ContextStatTable(entries = entries, modifier = Modifier.fillMaxWidth())
            }
            if (onCompress != null) {
                Button(
                    onClick = onCompress,
                    enabled = compressEnabled,
                    style = ButtonStyle.Primary,
                    buttonSize = ButtonSize.Small,
                ) {
                    Text(tr("压缩上下文"), color = LocalContentColor.current)
                }
            }
        },
        modifier = modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
        measurePolicy = measurePolicy,
    )
}

@Composable
private fun ContextUsageBar(segments: List<Pair<Color, Int>>, modifier: Modifier = Modifier) {
    val total = segments.sumOf { it.second.toLong() }.coerceAtLeast(0L)
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Theme[colors][mutedColor].copy(alpha = 0.18f)),
    ) {
        if (total > 0L) {
            Row(Modifier.fillMaxSize()) {
                segments.forEach { (color, tokens) ->
                    if (tokens > 0) {
                        val share = (tokens.toDouble() / total.toDouble()).toFloat().coerceAtLeast(0.0001f)
                        Box(Modifier.weight(share).fillMaxHeight().background(color))
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextStatTable(entries: List<ContextLegendEntry>, modifier: Modifier = Modifier) {
    val measurePolicy = remember { contextStatMeasurePolicy() }
    Layout(
        content = {
            entries.forEach { entry ->
                ContextStatLabel(entry.label, entry.dot)
                ContextStatText(entry.value)
            }
        },
        modifier = modifier,
        measurePolicy = measurePolicy,
    )
}

@Composable
private fun ContextStatLabel(text: String, dot: Color?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(8.dp)
                    .background(dot, CircleShape),
            )
        }
        ContextStatText(text)
    }
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
            (constraints.hasFixedWidth && constraints.maxWidth >= contentWidth) ||
            (
                constraints.hasBoundedWidth &&
                    constraints.maxWidth >= contentWidth &&
                    constraints.maxWidth - contentWidth <= 2
                )
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
 * 估算不是压缩目标。生成时用流式估算；结束后优先接口用量，缺失时保留本地估算。
 * 接口用量已经含系统提示、工具定义和工具上下文。
 * 本地估算含消息正文和工具上下文，不含系统提示和工具定义。
 */
internal data class ContextRingOccupied(
    val tokens: Int,
    val includesOverhead: Boolean,
)

internal fun contextRingOccupied(
    apiUsed: Int,
    liveEstimate: Int,
    generatingReply: Boolean,
    compressionEstimate: Int? = null,
): ContextRingOccupied = when {
    generatingReply && liveEstimate > 0 -> ContextRingOccupied(liveEstimate, includesOverhead = false)
    compressionEstimate != null && compressionEstimate > 0 -> ContextRingOccupied(compressionEstimate, includesOverhead = false)
    apiUsed > 0 -> ContextRingOccupied(apiUsed, includesOverhead = true)
    else -> ContextRingOccupied(liveEstimate.coerceAtLeast(0), includesOverhead = false)
}

internal fun contextRingUsedTokens(
    apiUsed: Int,
    liveEstimate: Int,
    generatingReply: Boolean,
    compressionEstimate: Int? = null,
): Int = contextRingOccupied(apiUsed, liveEstimate, generatingReply, compressionEstimate).tokens

internal data class ContextUsageBreakdown(
    val systemTokens: Int,
    val toolTokens: Int,
    val messageTokens: Int,
    val freeTokens: Int,
    val reservedTokens: Int,
    val usedTokens: Int,
    val windowTokens: Int,
) {
    val percent: Int?
        get() = contextUsagePercent(usedTokens, windowTokens)
}

/** 窗口未知时没有百分比。超过窗口不截成 100，菜单要能看出已经溢出。 */
internal fun contextUsagePercent(usedTokens: Int, windowTokens: Int): Int? {
    if (windowTokens <= 0) return null
    val used = usedTokens.coerceAtLeast(0).toLong()
    return ((used * 100 + windowTokens / 2) / windowTokens).toInt()
}

/**
 * 压缩打开时，触发线以上的窗口是预留：自动压缩会在用满之前动手，这段不计入空闲。
 * 默认触发 90%，预留就是窗口的 10%。压缩关闭时没有预留。
 */
internal fun contextReservedBudget(
    windowTokens: Int,
    compressionEnabled: Boolean,
    triggerPercent: Int,
): Int {
    if (!compressionEnabled || windowTokens <= 0) return 0
    val headroom = (100 - triggerPercent.coerceIn(0, 100)).coerceAtLeast(0)
    return (windowTokens.toLong() * headroom / 100L).toInt()
}

/**
 * 分段加总等于圆环上的已用。系统提示含工具定义，在本地估算之外。
 * 工具是消息里的执行记录，已经包含在本地估算和接口用量里，不再另加。
 * 接口只有总量，没有分类；按本地三类估算的比例校准，不能先扣工具再让消息吃余数。
 * 预留从窗口尾部扣，不跟已用重叠。
 */
internal fun contextUsageBreakdown(
    windowTokens: Int,
    occupiedTokens: Int,
    occupiedIncludesOverhead: Boolean,
    systemTokens: Int,
    toolTokens: Int,
    messageTokens: Int,
    reservedBudget: Int,
): ContextUsageBreakdown {
    val window = windowTokens.coerceAtLeast(0)
    val system = systemTokens.coerceAtLeast(0)
    val tools = toolTokens.coerceAtLeast(0)
    val occupied = occupiedTokens.coerceAtLeast(0)
    val used = if (occupiedIncludesOverhead) {
        occupied
    } else {
        (occupied.toLong() + system).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    val estimatedMessages = messageTokens.coerceAtLeast(0).toLong()
    val estimatedContent = tools.toLong() + estimatedMessages
    val estimatedTotal = system.toLong() + estimatedContent
    val calibrate = occupiedIncludesOverhead && estimatedTotal > 0L
    val systemShown = if (calibrate) {
        (used.toLong() * system / estimatedTotal).toInt()
    } else {
        minOf(system, used)
    }
    val contentShown = used - systemShown
    val toolsShown = if (calibrate && estimatedContent > 0L) {
        (contentShown.toLong() * tools / estimatedContent).toInt()
    } else {
        minOf(tools, contentShown)
    }
    val messages = contentShown - toolsShown
    val budget = if (window == 0) 0 else reservedBudget.coerceIn(0, window)
    val tail = (window - used).coerceAtLeast(0)
    val reserved = minOf(budget, tail)
    val free = (tail - reserved).coerceAtLeast(0)
    return ContextUsageBreakdown(
        systemTokens = systemShown,
        toolTokens = toolsShown,
        messageTokens = messages,
        freeTokens = free,
        reservedTokens = reserved,
        usedTokens = used,
        windowTokens = window,
    )
}

/** 菜单里的紧凑数字：618、4.1K、1M。按 1000 进位，和千分位的 [formatContextCount] 分开。 */
internal fun formatContextCompact(count: Int): String {
    val negative = count < 0
    val body = compactMagnitude(kotlin.math.abs(count.toLong()))
    return if (negative) "-$body" else body
}

private fun compactMagnitude(magnitude: Long): String = when {
    magnitude < 1_000L -> magnitude.toString()
    magnitude < 1_000_000L -> compactUnit(magnitude, 1_000L, "K")
    else -> compactUnit(magnitude, 1_000_000L, "M")
}

private fun compactUnit(magnitude: Long, unit: Long, suffix: String): String {
    val scaled = (magnitude * 10 + unit / 2) / unit
    if (scaled >= 10_000L) {
        return if (suffix == "K") compactMagnitude(1_000_000L) else "${scaled / 10}$suffix"
    }
    val whole = scaled / 10
    val fraction = scaled % 10
    return if (fraction == 0L) "$whole$suffix" else "$whole.$fraction$suffix"
}
