package com.irio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ModelSwitcherActionLayout(val restingWidth: Dp, val expandedWidth: Dp, val labelWidths: List<Dp>, val height: Dp)

private class ModelSwitcherActionReveal(private val scope: CoroutineScope) {
    var expanded by mutableStateOf<Int?>(null)
        private set
    private var timeout: Job? = null
    private var held: Int? = null
    fun show(index: Int) {
        timeout?.cancel()
        held = null
        expanded = index
        timeout = scope.launch { delay(1_500); expanded = null }
    }
    fun hold(index: Int) {
        timeout?.cancel()
        timeout = null
        held = index
        expanded = index
    }
    fun release(index: Int): Boolean {
        if (held != index || expanded != index) return false
        close()
        return true
    }
    fun close() {
        timeout?.cancel()
        timeout = null
        held = null
        expanded = null
    }
}

@Composable
internal fun modelSwitcherQuickActionsLayout(width: Dp): ModelSwitcherActionLayout {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labels = listOf(tr("快速"), tr("网络搜索"), tr("自动朗读"))
    val style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val available = (width - 20.dp).coerceAtLeast(0.dp)
    val restingWidth = available / 3
    // Leave a little travel beyond the resting target for the elastic overshoot.
    val expandedWidth = (available - 88.dp).coerceAtLeast(restingWidth)
    val labelWidths = labels.map { label ->
        with(density) { measurer.measure(AnnotatedString(label), style).size.width.toDp() }
            .coerceAtMost((expandedWidth - 42.dp).coerceAtLeast(1.dp))
    }
    val textHeight = labels.mapIndexed { index, label ->
        val textWidth = with(density) { labelWidths[index].roundToPx().coerceAtLeast(1) }
        measurer.measure(AnnotatedString(label), style, constraints = Constraints(maxWidth = textWidth)).size.height
    }.max()
    return ModelSwitcherActionLayout(restingWidth, expandedWidth, labelWidths,
        (with(density) { textHeight.toDp() } + 12.dp).coerceAtLeast(40.dp))
}

@Composable
internal fun ModelSwitcherQuickActions(
    fastMode: Boolean,
    webSearchAvailable: Boolean,
    webSearchEnabled: Boolean,
    autoRead: Boolean,
    active: Boolean,
    onFastModeChange: (Boolean) -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
    onAutoReadChange: (Boolean) -> Unit,
    onWebSearchUnavailable: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state = remember(scope) { ModelSwitcherActionReveal(scope) }
    fun longPress(index: Int, held: Boolean) {
        if (!active) return
        AppHaptics.perform(context, HapticEvent.QUICK_LONG_PRESS)
        if (held) state.hold(index) else state.show(index)
    }
    fun release(index: Int) {
        if (state.release(index) && active) AppHaptics.perform(context, HapticEvent.QUICK_LONG_RELEASE)
    }
    val expanded = state.expanded
    LaunchedEffect(active, webSearchAvailable) {
        if (!active || (expanded == 1 && !webSearchAvailable)) state.close()
    }
    val reveal = List(3) { index ->
        animateFloatAsState(if (expanded == index) 1f else 0f,
            irioSpring(dampingRatio = 0.58f, stiffness = 2800f), label = "action $index reveal").value
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = modelSwitcherQuickActionsLayout(maxWidth)
        // Preserve overshoot, but redistribute any width below the icon-safe minimum during reversals.
        val minimum = layout.restingWidth.coerceAtMost(40.dp)
        val surplus = List(3) { index ->
            (layout.restingWidth + (layout.expandedWidth - layout.restingWidth) *
                (1.5f * reveal[index] - 0.5f * reveal.sum()) - minimum).value.coerceAtLeast(0f)
        }
        val totalSurplus = surplus.sum()
        fun width(index: Int) = minimum + if (totalSurplus > 0f)
            (layout.restingWidth - minimum) * (3f * surplus[index] / totalSurplus) else 0.dp
        Row(
            Modifier.fillMaxWidth().height(layout.height).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelSwitcherActionTile(
                label = tr("快速"), icon = if (fastMode) IrioIcons.Zap else IrioIcons.ZapOff,
                iconTag = "model-fast-mode-icon", checked = fastMode, enabled = active,
                modifier = Modifier.width(width(0)), height = layout.height,
                labelWidth = layout.labelWidths[0], reveal = reveal[0], expanded = expanded == 0,
                onLongPress = { longPress(0, it) }, onRelease = { release(0) },
                onCheckedChange = { if (active) {
                    if (it != fastMode) AppHaptics.perform(context, if (it) HapticEvent.FAST_MODE_ON else HapticEvent.FAST_MODE_OFF)
                    onFastModeChange(it); state.show(0)
                } },
            )
            ModelSwitcherActionTile(
                label = tr("网络搜索"), icon = if (webSearchAvailable && webSearchEnabled) IrioIcons.Globe else IrioIcons.GlobeOff,
                iconTag = "model-web-search-icon",
                checked = webSearchAvailable && webSearchEnabled,
                enabled = webSearchAvailable && active,
                onUnavailableClick = if (active && !webSearchAvailable) onWebSearchUnavailable else null,
                modifier = Modifier.width(width(1)), height = layout.height,
                labelWidth = layout.labelWidths[1], reveal = reveal[1], expanded = expanded == 1,
                onLongPress = { longPress(1, it) }, onRelease = { release(1) },
                onCheckedChange = { if (active && webSearchAvailable) {
                    if (it != webSearchEnabled) AppHaptics.perform(context, if (it) HapticEvent.WEB_SEARCH_ON else HapticEvent.WEB_SEARCH_OFF)
                    onWebSearchChange(it); state.show(1)
                } },
            )
            ModelSwitcherActionTile(
                label = tr("自动朗读"), icon = if (autoRead) IrioIcons.Volume2 else IrioIcons.VolumeOff,
                iconTag = "model-auto-read-icon",
                checked = autoRead, enabled = active, modifier = Modifier.width(width(2)), height = layout.height,
                labelWidth = layout.labelWidths[2], reveal = reveal[2], expanded = expanded == 2,
                onLongPress = { longPress(2, it) }, onRelease = { release(2) },
                onCheckedChange = { if (active) {
                    if (it != autoRead) AppHaptics.perform(context, if (it) HapticEvent.AUTO_READ_ON else HapticEvent.AUTO_READ_OFF)
                    onAutoReadChange(it); state.show(2)
                } },
            )
        }
    }
}

@Composable
private fun ModelSwitcherActionTile(
    label: String,
    icon: ImageVector,
    iconTag: String,
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    height: Dp,
    labelWidth: Dp,
    reveal: Float,
    expanded: Boolean,
    onLongPress: (Boolean) -> Unit,
    onRelease: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onUnavailableClick: (() -> Unit)? = null,
) {
    val labelReveal = reveal.coerceIn(0f, 1f)
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val interactive = enabled || onUnavailableClick != null
    LaunchedEffect(pressed, interactive) { if (!pressed || !interactive) onRelease() }
    val accent = Theme[colors][primaryColor]
    val selected = checked && enabled
    val fill = if (selected) accent.copy(alpha = 0.12f) else Theme[colors][secondaryColor]
    val foreground = when {
        !enabled -> Theme[colors][mutedColor]
        selected -> accent
        else -> Theme[colors][onBackgroundColor]
    }
    Row(
        modifier.height(height)
            .clip(ContinuousCapsule)
            .background(fill)
            .combinedClickable(interactionSource = interactions, indication = LocalIndication.current,
                enabled = interactive, role = Role.Switch, hapticFeedbackEnabled = false,
                onClick = { if (enabled) onCheckedChange(!checked) else onUnavailableClick?.invoke() },
                onLongClick = { if (interactive) onLongPress(pressed) })
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
                stateDescription = tr(if (checked) "开启" else "关闭")
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp).testTag(iconTag))
        Box(Modifier.width((labelWidth + 4.dp) * labelReveal).clipToBounds()) {
            Text(label, modifier = Modifier.wrapContentWidth(unbounded = true, align = Alignment.Start)
                .width(labelWidth + 4.dp).padding(start = 4.dp).graphicsLayer { alpha = labelReveal }
                .semantics { if (!expanded) hideFromAccessibility() },
                color = foreground, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
        }
    }
}
