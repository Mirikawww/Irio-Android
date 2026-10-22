package com.irio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal val SettingsSearchHighlight = Color(0xFFFFE14A)

internal enum class SettingsSearchEdge { Flat, Top, Bottom, All }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.settingsSearchAnchor(
    id: String,
    horizontalOutset: Dp = 16.dp,
    edge: SettingsSearchEdge = SettingsSearchEdge.Flat,
    verticalOutset: Dp = 6.dp,
    cornerRadius: Dp = 16.dp,
): Modifier {
    val active = LocalContext.current.settingsSearchTarget() == id && id.isNotEmpty()
    val requester = remember(id) { BringIntoViewRequester() }
    val flash = remember(id) { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active) {
            flash.snapTo(0f)
            return@LaunchedEffect
        }
        delay(180)
        runCatching { requester.bringIntoView() }
        repeat(2) {
            flash.animateTo(1f, tween(150))
            flash.animateTo(0.25f, tween(150))
        }
        flash.animateTo(0f, tween(220))
    }
    return this
        .bringIntoViewRequester(requester)
        .drawBehind {
            val alpha = flash.value
            if (alpha <= 0f) return@drawBehind
            val extra = horizontalOutset.toPx()
            val topExtra = if (edge == SettingsSearchEdge.Top || edge == SettingsSearchEdge.All) verticalOutset.toPx() else 0f
            val bottomExtra = if (edge == SettingsSearchEdge.Bottom || edge == SettingsSearchEdge.All) verticalOutset.toPx() else 0f
            val rect = Rect(
                left = -extra,
                top = -topExtra,
                right = size.width + extra,
                bottom = size.height + bottomExtra,
            )
            val radius = cornerRadius.toPx()
            val roundTop = edge == SettingsSearchEdge.Top || edge == SettingsSearchEdge.All
            val roundBottom = edge == SettingsSearchEdge.Bottom || edge == SettingsSearchEdge.All
            val curve = CornerRadius(radius, radius)
            val sharp = CornerRadius.Zero
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = rect,
                        topLeft = if (roundTop) curve else sharp,
                        topRight = if (roundTop) curve else sharp,
                        bottomRight = if (roundBottom) curve else sharp,
                        bottomLeft = if (roundBottom) curve else sharp,
                    ),
                )
            }
            drawPath(path, SettingsSearchHighlight.copy(alpha = 0.42f * alpha))
        }
}

@Composable
internal fun SettingsSearchSlot(
    showField: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onExpanded: () -> Unit,
    focusRequester: FocusRequester,
    onBounds: (Rect) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) },
    ) {
        if (showField) {
            SettingsSearchField(
                query = query,
                onQueryChange = onQueryChange,
                onExpanded = onExpanded,
                focusRequester = focusRequester,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun SettingsSearchOverlay(
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    anchor: Rect,
    onExpanded: () -> Unit,
    onResult: (SettingsSearchHit) -> Unit,
    onDismiss: () -> Unit,
    focusRequester: FocusRequester,
) {
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(280),
        label = "settingsSearch",
    )
    if (!expanded && progress <= 0f) return
    if (anchor.width <= 0f) return
    LaunchedEffect(expanded) {
        if (expanded) focusRequester.requestFocus()
    }
    val density = LocalDensity.current
    var overlayOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
    val screenWidth = LocalWindowInfo.current.containerSize.width.toFloat()
    val margin = with(density) { 16.dp.toPx() }
    val targetTop = statusTop + with(density) { 8.dp.toPx() }
    val targetWidth = (screenWidth - margin * 2f).coerceAtLeast(anchor.width)
    val anchorLeft = anchor.left - overlayOrigin.x
    val anchorTop = anchor.top - overlayOrigin.y
    val left = lerp(anchorLeft, margin, progress)
    val top = lerp(anchorTop, targetTop, progress)
    val width = lerp(anchor.width, targetWidth, progress)
    val hits = remember(query, ThemeStore.language) { searchSettings(query) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.boundsInRoot().topLeft },
    ) {
        if (progress > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = with(density) { (top + with(density) { 48.dp.toPx() }).toDp() })
                    .imePadding()
                    .graphicsLayer { alpha = progress },
            ) {
                AndroidComposeRecyclerList(
                    items = hits,
                    key = { it.id },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) { hit ->
                    SettingsSearchResult(hit, divider = hit.id != hits.lastOrNull()?.id, onResult = onResult)
                }
            }
        }
        SettingsSearchField(
            query = query,
            onQueryChange = onQueryChange,
            onExpanded = onExpanded,
            focusRequester = focusRequester,
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .width(with(density) { width.toDp() })
                .height(40.dp),
        )
    }
}

@Composable
private fun SettingsSearchResult(
    hit: SettingsSearchHit,
    divider: Boolean,
    onResult: (SettingsSearchHit) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onResult(hit) },
            )
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        SettingsSearchTitle(hit.title, hit.ranges, Theme[colors][onBackgroundColor])
        Spacer(Modifier.height(2.dp))
        Text(
            hit.page,
            color = Theme[colors][mutedColor],
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Theme[colors][mutedColor].copy(alpha = 0.28f)),
            )
        }
    }
}

@Composable
private fun SettingsSearchTitle(title: String, ranges: List<IntRange>, color: Color) {
    val highlighted = buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            val start = range.first.coerceIn(0, title.length)
            val end = (range.last + 1).coerceIn(start, title.length)
            if (cursor < start) append(title.substring(cursor, start))
            withStyle(SpanStyle(background = SettingsSearchHighlight, color = Color(0xFF1A1A1A))) {
                append(title.substring(start, end))
            }
            cursor = end
        }
        if (cursor < title.length) append(title.substring(cursor))
    }
    Text(
        highlighted,
        color = color,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onExpanded: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onExpanded() }
            .background(Theme[colors][secondaryColor], ContinuousCapsule)
            .border(1.dp, Theme[colors][primaryColor].copy(alpha = 0.0f), ContinuousCapsule)
            .padding(horizontal = 14.dp),
        textStyle = TextStyle(color = content, fontSize = 15.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(Theme[colors][primaryColor]),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { inner ->
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = IrioIcons.Search,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(tr("搜索"), color = muted, fontSize = 15.sp, lineHeight = 20.sp)
                    }
                    inner()
                }
            }
        },
    )
}


