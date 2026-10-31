package com.irio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme
import kotlin.math.roundToInt

/** Measured on the stable parent, not the moving/expanding track. */
private class VideoDurationTrack {
    var start = 0f
    var width = 1f
    var expandedWidth = 1f
    var radius = 1f
    fun fraction(x: Float) = ((x - start - radius) / (width - radius * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
}

/** Continuous numeric choices stay in the first menu; seconds remain anchored on the right. */
@Composable
internal fun VideoDurationSlider(
    duration: Int,
    range: IntRange,
    active: Boolean,
    onChange: (Int) -> Unit,
) {
    val current = duration.coerceIn(range)
    var preview by remember(range) { mutableFloatStateOf(current.toFloat()) }
    var dragging by remember(range) { mutableStateOf(false) }
    val expansion by animateFloatAsState(if (dragging) 1f else 0f, irioSpring(), label = "video duration expansion")
    val latestDuration by rememberUpdatedState(current)
    val commit by rememberUpdatedState(onChange)
    LaunchedEffect(current, dragging) { if (!dragging) preview = current.toFloat() }
    val seconds = preview.roundToInt().coerceIn(range)
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val accent = Theme[colors][primaryColor]
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val geometry = remember { VideoDurationTrack() }
    val span = (range.last - range.first).coerceAtLeast(1)
    fun select(value: Int) {
        preview = value.coerceIn(range).toFloat()
        if (preview.roundToInt() != current) onChange(preview.roundToInt())
    }
    Layout(
        modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp)
            .testTag("video-duration-row")
            .pointerInput(active, range, rtl) {
                fun logical(x: Float) = if (rtl) size.width - x else x
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!active) return@awaitEachGesture
                    val downX = logical(down.position.x)
                    if (downX !in geometry.start..(geometry.start + geometry.width)) return@awaitEachGesture
                    down.consume()
                    var started = false
                    var released = false
                    var lastX = downX
                    val initial = latestDuration
                    try {
                        do {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            val x = logical(change.position.x)
                            if (!change.pressed) {
                                if (!started) preview = range.first + geometry.fraction(downX) * span
                                released = true
                            } else {
                                if (!started && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    started = true
                                    dragging = true
                                }
                                if (started) {
                                    // Relative movement avoids a value jump when the track expands under the finger.
                                    val travel = (geometry.expandedWidth - geometry.radius * 2).coerceAtLeast(1f)
                                    preview = (preview + (x - lastX) / travel * span).coerceIn(range.first.toFloat(), range.last.toFloat())
                                    lastX = x
                                    change.consume()
                                }
                            }
                        } while (!released)
                    } finally {
                        val chosen = preview.roundToInt().coerceIn(range)
                        if (released && chosen != initial) commit(chosen)
                        preview = if (released) chosen.toFloat() else initial.toFloat()
                        dragging = false
                    }
                }
            },
        content = {
            Text(tr("视频时长"), fontSize = 17.sp, lineHeight = 20.sp, maxLines = 1,
                color = content, modifier = Modifier.testTag("video-duration-label")
                    .semantics { if (expansion >= 0.99f) hideFromAccessibility() }
                    .graphicsLayer { alpha = 1f - expansion })
            Canvas(Modifier.height(44.dp).testTag("video-duration-slider").focusable(active)
                .onKeyEvent { event ->
                    if (!active || event.type != KeyEventType.KeyDown) false else {
                        val next = when (event.key) {
                            Key.DirectionRight -> seconds + if (rtl) -1 else 1
                            Key.DirectionLeft -> seconds + if (rtl) 1 else -1
                            Key.MoveHome -> range.first
                            Key.MoveEnd -> range.last
                            else -> return@onKeyEvent false
                        }
                        select(next)
                        true
                    }
                }
                .semantics {
                    contentDescription = tr("视频时长")
                    stateDescription = tr("%s 秒").format(seconds)
                    progressBarRangeInfo = ProgressBarRangeInfo(seconds.toFloat(), range.first.toFloat()..range.last.toFloat(), (span - 1).coerceAtLeast(0))
                    if (!active) disabled()
                    setProgress { value ->
                        if (!active) false else { select(value.roundToInt()); true }
                    }
                }) {
                val radius = 8.dp.toPx()
                val progress = ((preview - range.first) / span).coerceIn(0f, 1f)
                val g = thinkingSliderGeometry(size.width, radius, progress, rtl)
                val y = size.height / 2f
                drawLine(muted.copy(alpha = 0.28f), Offset(radius, y), Offset(size.width - radius, y), 3.dp.toPx(), StrokeCap.Round)
                val start = if (rtl) size.width - radius else radius
                drawLine(if (active) accent else muted, Offset(start, y), Offset(g.thumbX, y), 3.dp.toPx(), StrokeCap.Round)
                // The same full-sized handle is always visible, including in the compact state.
                drawCircle(if (active) accent else muted, radius, Offset(g.thumbX, y))
            }
            Text(tr("%s 秒").format(seconds), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1,
                color = content, modifier = Modifier.testTag("video-duration-seconds"))
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val value = measurables[2].measure(Constraints(maxWidth = 52.dp.roundToPx(), maxHeight = height))
        val gap = 8.dp.roundToPx()
        val expandedWidth = (width - value.width - gap).coerceAtLeast(1)
        val compactWidth = 92.dp.roundToPx().coerceAtMost(expandedWidth)
        val labelSpace = ((expandedWidth - compactWidth) * (1f - expansion)).roundToInt().coerceAtLeast(0)
        val trackWidth = (expandedWidth - labelSpace).coerceAtLeast(1)
        val label = measurables[0].measure(Constraints(maxWidth = (expandedWidth - compactWidth - gap).coerceAtLeast(0), maxHeight = height))
        val slider = measurables[1].measure(Constraints.fixed(trackWidth, height))
        geometry.start = labelSpace.toFloat()
        geometry.width = trackWidth.toFloat()
        geometry.expandedWidth = expandedWidth.toFloat()
        geometry.radius = 8.dp.toPx()
        layout(width, height) {
            label.placeRelative(0, (height - label.height) / 2)
            slider.placeRelative(labelSpace, 0)
            value.placeRelative(width - value.width, (height - value.height) / 2)
        }
    }
}
