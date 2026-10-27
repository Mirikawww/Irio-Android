package com.irio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.components.TextField
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle

@Composable
internal fun ImageGenerationControls(
    options: ImageGenerationOptions,
    active: Boolean,
    supportsOpenAiOptions: Boolean = true,
    onChange: (ImageGenerationOptions) -> Unit,
    onAspectRatio: () -> Unit,
) {
    ImageOptionRow(tr("分辨率")) {
        ImageChoiceTabs(tr("分辨率"), ImageResolution.entries.map { it.label }, ImageResolution.entries.indexOf(options.resolution), active) {
            onChange(options.copy(resolution = ImageResolution.entries[it]))
        }
    }
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedRectangle(10.dp))
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, enabled = active,
                role = Role.Button, onClick = onAspectRatio)
            .semantics { contentDescription = tr("宽高比"); stateDescription = options.aspectRatio?.label ?: tr("不指定") }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(tr("宽高比"), Modifier.weight(1f), color = Theme[colors][onBackgroundColor], fontSize = 17.sp, lineHeight = 20.sp)
        Text(options.aspectRatio?.label ?: tr("不指定"), color = Theme[colors][onBackgroundColor], fontSize = 15.sp)
        Icon(IrioIcons.ChevronDown, contentDescription = null, tint = Theme[colors][mutedColor], modifier = Modifier.size(18.dp))
    }
    ImageOptionRow(tr("背景"), available = supportsOpenAiOptions) {
        ImageChoiceTabs(tr("背景"), ImageBackground.entries.map { tr(it.label) }, ImageBackground.entries.indexOf(options.background), active && supportsOpenAiOptions) {
            onChange(options.copy(background = ImageBackground.entries[it]))
        }
    }
    ImageOptionRow(tr("质量"), available = supportsOpenAiOptions) {
        ImageChoiceTabs(tr("质量"), ImageQuality.entries.map { tr(it.label) }, ImageQuality.entries.indexOf(options.quality), active && supportsOpenAiOptions) {
            onChange(options.copy(quality = ImageQuality.entries[it]))
        }
    }
}

@Composable
private fun ImageOptionRow(title: String, available: Boolean = true, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp)
        .semantics { if (!available) stateDescription = tr("当前协议不支持此参数") }
        .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), color = Theme[colors][onBackgroundColor], fontSize = 17.sp, lineHeight = 20.sp)
        control()
    }
}

/** Same spring-driven capsule indicator, spacing and type as Appearance's font selector. */
@Composable
internal fun ImageChoiceTabs(label: String, labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val progress by animateFloatAsState(selected.toFloat(), irioSpring(), label = "imageOptionTab")
    val selectedFill = Theme[colors][primaryColor]
    val selectedInk = Theme[colors][onPrimaryColor]
    val idleInk = Theme[colors][onBackgroundColor]
    Box(Modifier.widthIn(max = 172.dp).selectableGroup()
        .semantics { contentDescription = label }
        .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
        .clip(ContinuousCapsule).background(Theme[colors][secondaryColor], ContinuousCapsule).padding(4.dp)) {
        Layout(content = {
            Box(Modifier.fillMaxSize().clip(ContinuousCapsule).background(selectedFill))
            labels.forEachIndexed { index, text ->
                val ink by animateColorAsState(if (index == selected) selectedInk else idleInk, irioSpring(), label = "imageOptionInk")
                Box(Modifier.height(26.dp).clip(ContinuousCapsule)
                    .selectable(index == selected, enabled = enabled, role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onSelect(index) })
                    .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text(text, modifier = Modifier.fillMaxWidth(), color = ink, fontSize = 12.sp, lineHeight = 16.sp,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }) { measurables, constraints ->
            val count = labels.size
            val loose = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = constraints.maxWidth / count)
            val items = Array(count) { measurables[it + 1].measure(loose) }
            val segment = items.maxOf { it.width }
            val height = items.maxOf { it.height }
            val indicator = measurables[0].measure(Constraints.fixed(segment, height))
            layout(segment * count, height) {
                indicator.placeRelative((progress.coerceIn(0f, labels.lastIndex.toFloat()) * segment).toInt(), 0)
                items.forEachIndexed { index, item -> item.placeRelative(index * segment + (segment - item.width) / 2, 0) }
            }
        }
    }
}

@Composable
internal fun ImageAspectRatioList(
    options: ImageGenerationOptions,
    modifier: Modifier,
    opacity: State<Float>,
    active: Boolean,
    onSelect: (ImageAspectRatio?) -> Unit,
    onCustomChange: (ImageAspectRatio) -> Unit,
) {
    DismissComposerFocusWhenImeHides()
    // Own drafts above recycled holders so scrolling never resets the custom input.
    val width = rememberTextFieldState(options.customRatio.width.toString())
    val height = rememberTextFieldState(options.customRatio.height.toString())
    val choices = listOf("auto") + imageAspectRatioPresets + "custom"
    AndroidComposeRecyclerList(items = choices, key = { it }, modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp), cornerRadius = 12.dp, opacity = opacity, interactionEnabled = active) { choice ->
        if (choice == "custom") {
            ImageAspectRatioInput(width, height, active, options.useCustomAspectRatio,
                onSelect = { onCustomChange(ImageAspectRatio.parse("${width.text}:${height.text}") ?: options.customRatio) },
                onSave = onCustomChange)
        } else {
        val selected = when (choice) {
            "auto" -> options.aspectRatio == null
            else -> !options.useCustomAspectRatio && options.aspectRatio?.label == choice
        }
        val text = when (choice) {
            "auto" -> tr("不指定")
            else -> choice
        }
        Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedRectangle(8.dp))
            .selectable(selected, enabled = active, role = Role.RadioButton, onClick = {
                onSelect(if (choice == "auto") null else ImageAspectRatio.parse(choice))
            }).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = Theme[colors][onBackgroundColor], fontSize = 16.sp, lineHeight = 20.sp)
            if (selected) Icon(IrioIcons.Check, contentDescription = null, tint = Theme[colors][primaryColor], modifier = Modifier.size(18.dp))
        }
        }
    }
}

@Composable
private fun ImageAspectRatioInput(
    width: TextFieldState, height: TextFieldState, active: Boolean, selected: Boolean,
    onSelect: () -> Unit, onSave: (ImageAspectRatio) -> Unit,
) {
    val saveOnBlur by rememberUpdatedState<() -> Unit> {
        if (active && selected) ImageAspectRatio.parse("${width.text}:${height.text}")?.let(onSave)
    }
    var widthFocused by remember { mutableStateOf(false) }
    var heightFocused by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DismissComposerFocusWhenImeHides()
            Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedRectangle(8.dp))
                .selectable(selected, enabled = active, role = Role.RadioButton, onClick = onSelect),
                verticalAlignment = Alignment.CenterVertically) {
                Text(tr("自定义"), Modifier.weight(1f), color = Theme[colors][onBackgroundColor], fontSize = 16.sp, lineHeight = 20.sp)
                if (selected) Icon(IrioIcons.Check, contentDescription = null, tint = Theme[colors][primaryColor], modifier = Modifier.size(18.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(state = width, enabled = active && selected,
                    modifier = Modifier.weight(1f).then(rememberInputFieldBorder()).semantics { contentDescription = tr("宽度") }
                        .onFocusChanged { state ->
                            val lostFocus = widthFocused && !state.hasFocus
                            widthFocused = state.hasFocus
                            if (lostFocus) saveOnBlur()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), placeholder = { Text(tr("宽度"), color = Theme[colors][mutedColor]) })
                Text(":", color = Theme[colors][onBackgroundColor])
                TextField(state = height, enabled = active && selected,
                    modifier = Modifier.weight(1f).then(rememberInputFieldBorder()).semantics { contentDescription = tr("高度") }
                        .onFocusChanged { state ->
                            val lostFocus = heightFocused && !state.hasFocus
                            heightFocused = state.hasFocus
                            if (lostFocus) saveOnBlur()
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), placeholder = { Text(tr("高度"), color = Theme[colors][mutedColor]) })
            }
            Text(tr("宽高比范围为 1:3 至 3:1"), fontSize = 13.sp, lineHeight = 18.sp, color = Theme[colors][mutedColor])
        }
}
