package com.irio

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import com.kyant.shapes.RoundedRectangle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenuPanel
import com.composables.ui.components.DropdownMenuScope
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Switch
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import kotlin.math.roundToInt

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive


/** The explicit overload also supports legacy indications supplied by nested themes. */
@Composable
private fun Modifier.modelSwitcherClickable(onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = LocalIndication.current,
    role = Role.Button,
    onClick = onClick,
)

private val MaxThinkingGold = Color(0xFFFFB400)
private val UltraThinkingPurple = Color(0xFF9254E8)

/**
 * 展开后的列表高度贴合内容：模型少时缩回去，不用最小高度硬撑出底部留白。
 * 模型多时按屏幕高度 55% 封顶（264.dp..420.dp）。
 */
internal fun modelSwitcherListHeight(naturalHeight: Dp, screenHeightDp: Int): Dp =
    naturalHeight.coerceAtMost((screenHeightDp * 0.55f).dp.coerceIn(264.dp, 420.dp))

/** The entire surface changes to the list during the initial fast extension. */
@Composable
internal fun DropdownMenuScope.ModelSwitcherPanel(
    selectedDisplay: String,
    selectedModel: String,
    activeProviderId: String,
    rows: List<ModelPickerRow>,
    reasoningEffort: ReasoningEffort,
    supportsThinking: Boolean,
    reasoningLevels: List<ReasoningEffort>,
    fastMode: Boolean,
    webSearchAvailable: Boolean,
    webSearchEnabled: Boolean,
    autoRead: Boolean,
    onSelectedModelChange: (String, String) -> Unit,
    onReasoningChange: (ReasoningEffort) -> Unit,
    onFastModeChange: (Boolean) -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
    onAutoReadChange: (Boolean) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val width = (configuration.screenWidthDp.dp - 32.dp).coerceAtMost(280.dp)
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val accent = Theme[colors][primaryColor]
    var modelsExpanded by remember { mutableStateOf(false) }
    val phase = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(modelsExpanded) {
        phase.animateTo(if (modelsExpanded) 1f else 0f, irioSpring<Float>(stiffness = if (modelsExpanded) 900f else 1150f))
    }
    androidx.activity.compose.BackHandler(enabled = modelsExpanded) { modelsExpanded = false }
    val compactHeight = 220.dp
    val naturalHeight = rows.sumOf {
        when (it) {
            is ModelPickerRow.Model -> 36
            is ModelPickerRow.GroupLabel -> 26
            is ModelPickerRow.Gap -> 6
        }
    }.dp + 8.dp
    val listHeight = modelSwitcherListHeight(naturalHeight, configuration.screenHeightDp)
    val fastBackground by animateColorAsState(if (fastMode) accent.copy(alpha = 0.16f) else content.copy(alpha = 0.08f), irioSpring(), label = "fast background")
    val listOpacity = { modelSwitcherListOpacity(phase.value) }
    // Prepared in the same composition before the user starts expanding the surface.
    val listOpacityState = remember { androidx.compose.runtime.derivedStateOf { modelSwitcherListOpacity(phase.value) } }
    DropdownMenuPanel(modifier = Modifier.width(width)) {
        Box(Modifier.width(width).padding(horizontal = 8.dp, vertical = 6.dp)
            .layout { measurable, constraints ->
                // 测量上限必须同时容下收起态主面板和展开列表，否则列表矮时会把主面板一起压扁。
                val measureCap = maxOf(listHeight, compactHeight)
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = measureCap.roundToPx()))
                val height = compactHeight.roundToPx() + ((listHeight - compactHeight).toPx() * phase.value).roundToInt()
                layout(placeable.width, height) { placeable.placeRelative(0, 0) }
            }.clipToBounds()) {
            Column(Modifier.fillMaxWidth().height(compactHeight)
                .graphicsLayer { alpha = 1f - listOpacity() }
                .semantics { if (listOpacity() >= 0.5f) invisibleToUser() }) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).height(44.dp).clip(RoundedRectangle(12.dp))
                        .modelSwitcherClickable { modelsExpanded = !modelsExpanded }
                        .semantics {
                            contentDescription = tr("选择模型")
                            stateDescription = tr(if (modelsExpanded) "收起模型" else "展开模型")
                        }.padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(selectedDisplay.ifBlank { tr("选择模型") }, modifier = Modifier.weight(1f, fill = false),
                        color = content, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = muted,
                        modifier = Modifier.padding(start = 4.dp).size(18.dp).graphicsLayer { rotationZ = phase.value * 180f })
                }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = { onFastModeChange(!fastMode) }, style = ButtonStyle.Ghost,
                    buttonSize = ButtonSize.Small, shape = ContinuousCapsule,
                    modifier = Modifier.size(36.dp).background(fastBackground, ContinuousCapsule)
                        .semantics { selected = fastMode; stateDescription = tr(if (fastMode) "开启" else "关闭") },
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = tr("快速"), tint = if (fastMode) accent else content, modifier = Modifier.size(21.dp))
                }
            }
                Spacer(Modifier.height(3.dp))
                ThinkingDepthSlider(reasoningEffort, supportsThinking, reasoningLevels, onReasoningChange, active = !modelsExpanded)
                Spacer(Modifier.height(2.dp))
                ModelSwitcherToggle(tr("网络搜索"), webSearchAvailable && webSearchEnabled, webSearchAvailable, onWebSearchChange)
                ModelSwitcherToggle(tr("自动朗读"), autoRead, onCheckedChange = onAutoReadChange)
            }
                AndroidComposeRecyclerList(
                    items = rows, key = { it.key },
                    modifier = Modifier.fillMaxWidth().height(listHeight)
                        .semantics { if (listOpacity() < 0.5f) invisibleToUser() },
                    contentPadding = PaddingValues(vertical = 4.dp),
                    cornerRadius = 12.dp,
                    opacity = listOpacityState,
                    interactionEnabled = modelsExpanded,
                ) { row ->
                    when (row) {
                        is ModelPickerRow.Gap -> Spacer(Modifier.height(6.dp))
                        is ModelPickerRow.GroupLabel -> Box(
                            Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(row.text, color = muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        is ModelPickerRow.Model -> {
                            val selected = row.providerId == activeProviderId && selectedModel in row.variants
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 36.dp)
                                    .clip(RoundedRectangle(8.dp))
                                    .modelSwitcherClickable {
                                        if (!selected) onSelectedModelChange(row.target, row.providerId)
                                        modelsExpanded = false
                                    }.semantics { this.selected = selected }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(row.display, modifier = Modifier.weight(1f), color = content, fontSize = 16.sp, singleLine = false)
                                if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = accent, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun ModelSwitcherToggle(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedRectangle(10.dp))
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (enabled) content else muted, fontSize = 17.5.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** Every language rolls glyph by glyph, each starting a beat after the previous one. */
private const val RollStagger = 0.5f
/** Stiffness matched to the previous 180ms fast-out roll. */
private const val RollSpringStiffness = 760f
private const val RollBlurMaxDp = 5f
private const val RollBleedXDp = 12
private const val RollBleedYDp = 6
private const val AscenderEm = 0.76f
/** One draw clock rolls natural-width glyphs together, preserving visible positions on reversal. */
@Composable
private fun RollingThinkingLabel(effort: ReasoningEffort, enabled: Boolean, color: Color, direction: Int, animate: Boolean) {
    RollingGlyphLabel(
        text = tr(if (enabled) effort.label else "不支持思考"),
        color = color,
        direction = direction,
        animate = animate,
        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, lineHeight = 20.sp),
    )
}

@Composable
internal fun RollingGlyphLabel(
    text: String,
    color: Color,
    direction: Int,
    animate: Boolean,
    style: TextStyle,
    enterFromEmpty: Boolean = false,
    wrapContent: Boolean = false,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    val layout = measurer.measure(AnnotatedString(text), style, maxLines = 1, softWrap = false)
    val targets = remember(text, layout) {
        text.indices.map { index -> ThinkingGlyphFrame(index, text[index].toString(), layout.getBoundingBox(index).left) }
    }
    val isLatin = ThemeStore.language == AppLanguage.EN
    val fontPx = with(density) { style.fontSize.toPx() }
    // The line box reserves descender room, so Latin ink (ascender..baseline) sits above its centre and
    // reads as misaligned with the icon. Nudge it down to centre that band; CJK fills the box evenly.
    val opticalShift = remember(layout, isLatin, density, fontPx) {
        if (!isLatin) 0f
        else layout.size.height / 2f - (layout.firstBaseline - AscenderEm / 2f * fontPx)
    }
    val progress = remember { mutableFloatStateOf(1f) }
    // Do not key this on text. Replacing the previous glyphs with the destination before the
    // effect runs skips the jump, and a settled label keeps the haze blur at zero.
    var runs by remember {
        mutableStateOf(if (enterFromEmpty) emptyList() else targets.map { ThinkingGlyphRun(it, it) })
    }
    LaunchedEffect(targets, animate) {
        if (!animate) {
            runs = targets.map { ThinkingGlyphRun(it, it) }
            progress.floatValue = 1f
            return@LaunchedEffect
        }
        val visible = runs.map { it.frame(progress.floatValue) }
        runs = thinkingGlyphRuns(visible, targets, direction, RollStagger)
        progress.floatValue = 0f
        Animatable(0f).animateTo(1f, irioSpring(stiffness = RollSpringStiffness)) {
            progress.floatValue = value
        }
        runs = targets.map { ThinkingGlyphRun(it, it) }
        progress.floatValue = 1f
    }
    val glyphLayouts = remember(runs, measurer, density.fontScale, density.density) {
        runs.map { it.start.character }.distinct().associateWith {
            measurer.measure(AnnotatedString(it), style, maxLines = 1, softWrap = false)
        }
    }
    // Haze styles are immutable, so the radius is quantized to half-dp steps to keep restyling cheap.
    val blurRadius by remember {
        derivedStateOf { (thinkingBlurFraction(runs, progress.floatValue) * RollBlurMaxDp * 2f).roundToInt() / 2f }
    }
    val blurStyle = remember(blurRadius) {
        HazeBlurStyle {
            blurEnabled(blurRadius > 0f)
            blurRadius(blurRadius.dp)
            noiseFactor(0f)
            colorEffects(emptyList())
            fallbackColorEffect(HazeColorEffect.tint(Color.Transparent))
        }
    }
    // The blur only renders inside its node, so the canvas is laid out larger than the label and the
    // surplus fades out through a mask. No hard clip edge is left for the blurred glyphs to run into.
    val bleedX = with(density) { RollBleedXDp.dp.roundToPx() }
    val bleedY = with(density) { RollBleedYDp.dp.roundToPx() }
    val textHeight = layout.size.height
    val textWidth = layout.size.width
    Canvas(Modifier
        .layout { measurable, constraints ->
            val width = if (wrapContent) textWidth else constraints.maxWidth
            val placeable = measurable.measure(Constraints.fixed(width + 2 * bleedX, textHeight + 2 * bleedY))
            layout(width, textHeight) { placeable.place(-bleedX, -bleedY) }
        }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadeX = bleedX / size.width
            val fadeY = bleedY / size.height
            drawRect(Brush.horizontalGradient(0f to Color.Transparent, fadeX to Color.Black, 1f - fadeX to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn)
            drawRect(Brush.verticalGradient(0f to Color.Transparent, fadeY to Color.Black, 1f - fadeY to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn)
        }
        .hazeBlur(input = HazeInput.Content, style = blurStyle)
        .clearAndSetSemantics { contentDescription = text }) {
        translate(bleedX.toFloat(), bleedY.toFloat()) {
            val phase = progress.floatValue
            runs.forEach { run ->
                val glyph = run.frame(phase)
                if (glyph.alpha > 0.01f) {
                    drawText(glyphLayouts.getValue(glyph.character), color = color,
                        topLeft = Offset(glyph.x, glyph.y * textHeight + opticalShift), alpha = glyph.alpha)
                }
            }
        }
    }
}

@Composable
private fun ThinkingDepthSlider(effort: ReasoningEffort, enabled: Boolean, supportedLevels: List<ReasoningEffort>, onEffortChange: (ReasoningEffort) -> Unit, active: Boolean = true) {
    val levels = supportedLevels.ifEmpty { listOf(ReasoningEffort.OFF) }
    val adjustable = enabled && levels.size > 1
    val selectedIndex = levels.indexOf(effort).coerceAtLeast(0)
    val muted = Theme[colors][mutedColor]
    val accent = Theme[colors][primaryColor]
    val trackColor = Theme[colors][secondaryColor]
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var sliderWidth by remember { mutableIntStateOf(0) }
    var indicatorWidth by remember { mutableIntStateOf(0) }
    var value by remember(levels) { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragging by remember(levels) { mutableStateOf(false) }
    var preview by remember(levels) { mutableStateOf(ThinkingRollState(selectedIndex)) }
    LaunchedEffect(selectedIndex, levels) {
        if (!dragging) {
            value = selectedIndex.toFloat()
            preview = preview.moveTo(selectedIndex)
        }
    }
    val displayEffort = levels[preview.index]
    val showIntelligence = enabled && displayEffort != ReasoningEffort.OFF
    val iconReveal by animateFloatAsState(if (showIntelligence) 1f else 0f, irioSpring<Float>(), label = "thinking icon reveal")
    val motionAllowed = rememberSwitcherMotionAllowed()
    val ultra = enabled && displayEffort == ReasoningEffort.ULTRA
    val clock = rememberParticleClock(ultra && motionAllowed && active)
    val trackParticles = remember { ThinkingParticleField(System.nanoTime().toInt(), 36) }
    val badgeParticles = remember { ThinkingParticleField(System.nanoTime().toInt(), 10) }
    val fill by animateColorAsState(
        when {
            !enabled || displayEffort == ReasoningEffort.OFF -> muted
            displayEffort == ReasoningEffort.MAX -> MaxThinkingGold
            ultra -> UltraThinkingPurple
            else -> accent
        }, irioSpring(), label = "thinking color",
    )
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val labels = if (enabled) levels.map { tr(it.label) } else listOf(tr("不支持思考"))
    val labelWidths = remember(labels, measurer, density.fontScale, density.density) {
        labels.associateWith {
            measurer.measure(AnnotatedString(it), TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp), maxLines = 1, softWrap = false).size.width
        }
    }
    val currentLabel = tr(if (enabled) displayEffort.label else "不支持思考")
    val labelWidth by animateDpAsState(
        with(density) { labelWidths.getValue(currentLabel).toDp() },
        irioSpring(), label = "thinking label width",
    )
    // Icon stays anchored to the leading inset; the same animated width moves the bubble.
    val badgeWidth = labelWidth + 16.dp + 19.dp * iconReveal
    val position by animateFloatAsState(value, if (dragging) snap() else irioSpring(dampingRatio = 0.9f, stiffness = 700f), label = "thinking position")
    val radius = with(density) { 12.dp.toPx() }
    val applySelection by rememberUpdatedState<(Int) -> Unit> { requested ->
        val next = preview.moveTo(requested.coerceIn(0, levels.lastIndex))
        if (next != preview) {
            preview = next
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val commitSelection by rememberUpdatedState<() -> Unit> {
        if (levels[preview.index] != effort) onEffortChange(levels[preview.index])
    }
    val fraction = { if (levels.size == 1) 0.5f else (position / levels.lastIndex).coerceIn(0f, 1f) }
    val geometry = { thinkingSliderGeometry(sliderWidth.toFloat(), radius, fraction(), rtl) }
    Box(Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 6.dp).onSizeChanged { sliderWidth = it.width }) {
        Canvas(
            Modifier.fillMaxWidth().height(44.dp)
                .focusable(adjustable)
                .onKeyEvent { event ->
                    if (!adjustable || event.type != KeyEventType.KeyDown) false else {
                        val index = when (event.key) {
                            Key.DirectionRight -> preview.index + if (rtl) -1 else 1
                            Key.DirectionLeft -> preview.index + if (rtl) 1 else -1
                            Key.MoveHome -> 0
                            Key.MoveEnd -> levels.lastIndex
                            else -> return@onKeyEvent false
                        }
                        applySelection(index)
                        value = preview.index.toFloat()
                        commitSelection()
                        true
                    }
                }
                .semantics {
                    contentDescription = tr("思考强度")
                    stateDescription = tr(if (enabled) displayEffort.label else "不支持思考")
                    progressBarRangeInfo = ProgressBarRangeInfo(preview.index.toFloat(), 0f..levels.lastIndex.toFloat(), (levels.size - 2).coerceAtLeast(0))
                    if (!adjustable) disabled()
                    setProgress { target ->
                        if (!adjustable) false else {
                            applySelection(target.roundToInt())
                            value = preview.index.toFloat()
                            commitSelection()
                            true
                        }
                    }
                }
                .pointerInput(adjustable, levels, rtl) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!adjustable) return@awaitEachGesture
                        dragging = true
                        fun moveTo(x: Float) {
                            val travel = (size.width - radius * 2f).coerceAtLeast(1f)
                            val physical = ((x - radius) / travel).coerceIn(0f, 1f)
                            val logical = if (rtl) 1f - physical else physical
                            value = (logical * levels.lastIndex).coerceIn(0f, levels.lastIndex.toFloat())
                            applySelection(value.roundToInt())
                        }
                        try {
                            down.consume()
                            moveTo(down.position.x)
                            do {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (change.pressed) moveTo(change.position.x)
                                change.consume()
                            } while (change.pressed)
                        } finally {
                            value = preview.index.toFloat()
                            dragging = false
                            commitSelection()
                        }
                    }
                },
        ) {
            val g = thinkingSliderGeometry(size.width, radius, fraction(), rtl)
            val trackHeight = 20.dp.toPx()
            val top = (size.height - trackHeight) / 2f
            val trackSize = Size(size.width, trackHeight)
            val trackPath = ContinuousCapsule.continuousPath(trackSize, layoutDirection, this).apply {
                translate(Offset(0f, top))
            }
            drawPath(trackPath, trackColor)
            if (g.fillWidth > 0f) {
                clipPath(trackPath) {
                    drawRect(fill, Offset(g.fillStart, top), Size(g.fillWidth, trackHeight))
                    if (ultra && motionAllowed) {
                        clipRect(g.fillStart, top, g.fillStart + g.fillWidth, top + trackHeight) {
                            drawThinkingParticles(clock.value, trackParticles, Offset(g.fillStart, top), Size(g.fillWidth, trackHeight), Color.White)
                        }
                    }
                }
            }
            repeat(levels.size) { index ->
                val point = thinkingSliderGeometry(size.width, radius, if (levels.size == 1) 0.5f else index.toFloat() / levels.lastIndex, rtl)
                drawCircle(if (index <= preview.index) Color.White.copy(alpha = 0.65f) else muted.copy(alpha = 0.42f),
                    radius = 1.5.dp.toPx(), center = Offset(point.thumbX, size.height / 2f))
            }
        }
        Box(
            Modifier.offset { IntOffset((geometry().thumbX - radius).roundToInt(), with(density) { 10.dp.roundToPx() }) }
                .size(24.dp).shadow(2.dp, ContinuousCapsule).background(Color.White, ContinuousCapsule),
        )
        val indicatorInk = if (fill.luminance() > 0.45f) {
            if (displayEffort == ReasoningEffort.OFF) Color.Black else Color(0xFF392500)
        } else Color.White
        Box(
            Modifier.offset {
                val left = (geometry().thumbX - indicatorWidth / 2f).coerceIn(0f, (sliderWidth - indicatorWidth).coerceAtLeast(0).toFloat())
                IntOffset(left.roundToInt(), with(density) { 44.dp.roundToPx() })
            }.width(badgeWidth).onSizeChanged { indicatorWidth = it.width },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val left = (geometry().thumbX - indicatorWidth / 2f).coerceIn(0f, (sliderWidth - indicatorWidth).coerceAtLeast(0).toFloat())
                val tipHeight = 6.dp.toPx()
                val bubble = thinkingIndicatorPath(size, geometry().thumbX - left, 5.dp.toPx(), tipHeight, 5.dp.toPx())
                drawPath(bubble, fill)
                if (ultra && motionAllowed) clipPath(bubble) {
                    drawThinkingParticles(clock.value, badgeParticles, Offset(0f, 5.dp.toPx()), Size(size.width, size.height - 5.dp.toPx()), indicatorInk)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                Box(Modifier.width(19.dp * iconReveal).height(15.dp).clipToBounds()) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = indicatorInk,
                        modifier = Modifier.size(15.dp).graphicsLayer { alpha = iconReveal })
                }
                Box(Modifier.width(labelWidth), contentAlignment = Alignment.CenterStart) {
                    RollingThinkingLabel(displayEffort, enabled, indicatorInk, preview.direction, motionAllowed && active)
                }
            }
        }
    }
}

private fun DrawScope.thinkingIndicatorPath(size: Size, tipX: Float, bodyTop: Float, tipHeight: Float, shoulder: Float): Path {
    val g = thinkingIndicatorGeometry(size.width, size.height, tipX, bodyTop, shoulder)
    val bodyHeight = (size.height - g.bodyTop).coerceAtLeast(0f)
    val capsule = ContinuousCapsule.continuousPath(Size(size.width, bodyHeight), layoutDirection, this).apply {
        translate(Offset(0f, g.bodyTop))
    }
    val pointer = Path().apply {
        moveTo(g.tipX, 0f)
        lineTo(g.rootEnd, g.rootBottom)
        lineTo(g.rootStart, g.rootBottom)
        close()
    }
    return Path.combine(PathOperation.Union, capsule, pointer)
}

/** An unbounded clock drives draw only; independent particle births never replay a shared loop. */
@Composable
private fun rememberParticleClock(running: Boolean): State<Float> {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (running) {
            val elapsed = clock.floatValue
            var firstFrame = 0L
            while (isActive) withFrameNanos { nanos ->
                if (firstFrame == 0L) firstFrame = nanos
                clock.floatValue = elapsed + (nanos - firstFrame) / 1_000_000_000f
            }
        }
    }
    return clock
}

private fun DrawScope.drawThinkingParticles(seconds: Float, field: ThinkingParticleField, origin: Offset, area: Size, ink: Color) {
    repeat(field.count) { index ->
        val frame = field.sample(index, seconds)
        val x = origin.x + frame.x * area.width
        val y = origin.y + frame.y * area.height
        val radius = (if (frame.star) 2f else 1f).dp.toPx() * frame.scale
        val color = ink.copy(alpha = frame.opacity)
        if (frame.scale > 1f) drawLine(color.copy(alpha = color.alpha * 0.38f), Offset(x - frame.trail.dp.toPx(), y), Offset(x, y), 0.8.dp.toPx(), StrokeCap.Round)
        if (frame.star) {
            val star = Path().apply {
                moveTo(x, y - radius)
                quadraticTo(x + radius * 0.2f, y - radius * 0.2f, x + radius, y)
                quadraticTo(x + radius * 0.2f, y + radius * 0.2f, x, y + radius)
                quadraticTo(x - radius * 0.2f, y + radius * 0.2f, x - radius, y)
                quadraticTo(x - radius * 0.2f, y - radius * 0.2f, x, y - radius)
                close()
            }
            drawPath(star, color)
        } else drawCircle(color, radius, Offset(x, y))
    }
}

@Composable
private fun rememberSwitcherMotionAllowed(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var animationsEnabled by remember {
        mutableStateOf(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
    }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(context, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animationsEnabled = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return animationsEnabled && resumed
}
