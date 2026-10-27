package com.irio

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.Switch
import com.composables.ui.components.Text
import com.composables.ui.components.TextField
import com.composables.ui.theme.alphas
import com.composables.ui.theme.disabledAlpha
import com.composables.ui.theme.ringColor
import com.composables.ui.theme.switchThumbColor
import com.composables.ui.theme.switchTrackColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import com.composeunstyled.LocalContentColor

@Composable
internal fun ToolSettingsRow(
    entry: ToolSettingsEntry,
    checked: Boolean,
    expanded: Boolean,
    description: TextFieldState,
    onExpandedChange: (Boolean) -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onBlocked: () -> Unit,
    descriptionScrollState: ScrollState = rememberScrollState(),
) {
    LocalResolvedTheme.current
    val available = entry.blockedReason == null
    val content = if (available) Theme[colors][onPanelColor] else Theme[colors][mutedColor]
    val arrow by animateFloatAsState(
        targetValue = if (expanded && available) 180f else 0f,
        animationSpec = irioSpring(),
        label = "tool expansion arrow",
    )
    val view = LocalView.current
    // A row requestLayout stops at the already-flagged AndroidView holder, so the
    // host never measures again and the expanded height stays on the last drawn frame.
    SideEffect { view.relayoutToRoot() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Theme[shapes][largeShape])
            .background(Theme[colors][secondaryColor]),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("tool-expand:${entry.name}")
                .clickable { if (available) onExpandedChange(!expanded) else onBlocked() }
                .semantics { stateDescription = if (expanded && available) tr("展开") else tr("收起") }
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.title, color = content, fontSize = 16.sp, lineHeight = 22.sp)
                Text(
                    entry.name,
                    color = Theme[colors][mutedColor],
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (available) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.testTag("tool-toggle:${entry.name}").semantics { contentDescription = entry.title },
                )
            } else {
                // A disabled toggleable still intercepts touches. This is only its
                // themed visual, so the header owns the entire disabled hit target.
                Box(
                    Modifier
                        .testTag("tool-toggle:${entry.name}")
                        .semantics {
                            contentDescription = entry.title
                            disabled()
                            role = androidx.compose.ui.semantics.Role.Switch
                            toggleableState = androidx.compose.ui.state.ToggleableState.Off
                        }
                        .alpha(Theme[alphas][disabledAlpha])
                        .size(width = 44.dp, height = 24.dp)
                        .background(Theme[colors][switchTrackColor], ContinuousCapsule)
                        .border(Dp.Hairline, Theme[colors][ringColor], ContinuousCapsule)
                        .padding(2.dp),
                ) {
                    Box(
                        Modifier.size(20.dp)
                            .background(Theme[colors][switchThumbColor], ContinuousCapsule)
                            .border(Dp.Hairline, Theme[colors][ringColor], ContinuousCapsule),
                    )
                }
            }
            Icon(
                imageVector = IrioIcons.ChevronDown,
                contentDescription = if (expanded && available) tr("收起") else tr("展开"),
                modifier = Modifier.padding(start = 8.dp).size(20.dp).rotate(arrow),
                tint = content,
            )
        }
        AnimatedVisibility(
            visible = expanded && available,
            enter = expandVertically(animationSpec = irioSpring(), expandFrom = Alignment.Top) + fadeIn(animationSpec = irioSpring()),
            exit = shrinkVertically(animationSpec = irioSpring(), shrinkTowards = Alignment.Top) + fadeOut(animationSpec = irioSpring()),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(tr("工具描述"), color = content, fontSize = 14.sp, lineHeight = 20.sp)
                TextField(
                    state = description,
                    scrollState = descriptionScrollState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(rememberInputFieldBorder())
                        .pointerInput(view, descriptionScrollState) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (descriptionScrollState.maxValue > 0) {
                                    // Reserve the native gesture before RecyclerView sees its
                                    // first MOVE, without consuming the text field's events.
                                    val parent = view.parent
                                    parent?.requestDisallowInterceptTouchEvent(true)
                                    try {
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                        } while (event.changes.any { it.pressed })
                                    } finally {
                                        parent?.requestDisallowInterceptTouchEvent(false)
                                    }
                                }
                            }
                        },
                    accessibilityLabel = "${entry.title} · ${tr("工具描述")}",
                    textStyle = FieldTextStyle,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    shape = Theme[shapes][fieldShape],
                    contentPadding = PaddingValues(12.dp),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 12),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onReset,
                        modifier = Modifier
                            .weight(1f)
                            // Keep the neutral fill visible against the card while
                            // matching the adjacent button's capsule silhouette.
                            .background(Theme[colors][panelColor], ContinuousCapsule)
                            .testTag("tool-reset:${entry.name}"),
                        style = ButtonStyle.Ghost,
                        buttonSize = ButtonSize.Small,
                        contentColor = Theme[colors][onPanelColor],
                    ) { Text(tr("恢复默认描述"), color = LocalContentColor.current) }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f).testTag("tool-save:${entry.name}"),
                        style = ButtonStyle.Primary,
                        buttonSize = ButtonSize.Small,
                    ) { Text(tr("保存"), color = LocalContentColor.current) }
                }
            }
        }
    }
}

/** Mark every ancestor dirty. A child requestLayout does not reach a clean host once an intermediate holder is already flagged. */
private fun View.relayoutToRoot() {
    val chain = ArrayList<View>(8)
    var current: View? = this
    while (current != null) {
        chain += current
        current = current.parent as? View
    }
    val root = chain.last()
    if (!root.isLayoutRequested) root.requestLayout()
    chain.forEach { it.forceLayout() }
}
