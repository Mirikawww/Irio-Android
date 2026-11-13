package com.irio

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import com.kyant.shapes.RoundedRectangle
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer


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
import androidx.compose.ui.platform.LocalFontFamilyResolver

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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import com.irio.ScaledDropdownMenuPanel as DropdownMenuPanel
import com.composables.ui.components.DropdownMenuScope
import com.composables.ui.components.Icon

import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import com.composeunstyled.LocalTextStyle
import kotlin.math.roundToInt

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive


/** The explicit overload also supports legacy indications supplied by nested themes. */
@Composable
private fun Modifier.modelSwitcherClickable(onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = LocalIndication.current,
    role = Role.Button,
    onClick = onClick,
)



private val UltraThinkingPurple = Color(0xFF9254E8)

/**
 * 展开后的列表高度贴合内容：模型少时缩回去，不用最小高度硬撑出底部留白。
 * 模型多时按屏幕高度 55% 封顶（264.dp..420.dp）。
 */
internal fun modelSwitcherListHeight(naturalHeight: Dp, screenHeightDp: Int): Dp =
    naturalHeight.coerceAtMost((screenHeightDp * 0.55f).dp.coerceIn(264.dp, 420.dp))

/** Fading a panel out does not remove it from hit testing. Keep it inert until fully restored. */
internal fun modelSwitcherCompactInteractive(modelsExpanded: Boolean, phase: Float): Boolean =
    !modelsExpanded && phase <= 0.001f

internal fun modelSwitcherListComposed(listPrepared: Boolean, modelsExpanded: Boolean): Boolean =
    listPrepared || modelsExpanded

internal fun modelSwitcherCompactHeight(imageModel: Boolean, videoModel: Boolean = false): Dp = when {
    imageModel -> 396.dp
    videoModel -> 352.dp
    else -> 208.dp
}

/** The entire surface changes to the list during the initial fast extension. */
@Composable
internal fun DropdownMenuScope.ModelSwitcherPanel(
    selectedDisplay: String,
    selectedModel: String,
    activeProviderId: String,
    rows: List<ModelPickerRow>,
    reasoningEffort: ReasoningEffort,
    supportsThinking: Boolean,
    keepIme: Boolean = false,
    reasoningLevels: List<ReasoningEffort>,
    fastMode: Boolean,
    webSearchAvailable: Boolean,
    webSearchEnabled: Boolean,
    autoRead: Boolean,
    providerProtocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS,
    onSelectedModelChange: (String, String) -> Unit,
    onReasoningChange: (ReasoningEffort) -> Unit,
    onFastModeChange: (Boolean) -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
    onAutoReadChange: (Boolean) -> Unit,
    onWebSearchUnavailable: () -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val width = (configuration.screenWidthDp.dp - 32.dp).coerceAtMost(280.dp)
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val accent = Theme[colors][primaryColor]
    val context = LocalContext.current
    val imageModel = remember(selectedModel) { ModelCatalog.isImageModel(context, selectedModel) }
    val videoModel = remember(selectedModel) { ModelCatalog.isVideoModel(context, selectedModel) }
    var imageOptions by remember(activeProviderId, selectedModel) {
        mutableStateOf(ImageGenerationStore.load(context, activeProviderId, selectedModel))
    }
    fun saveImageOptions(options: ImageGenerationOptions) {
        imageOptions = options
        ImageGenerationStore.save(context, activeProviderId, selectedModel, options)
    }
    var videoOptions by remember(activeProviderId, selectedModel) {
        mutableStateOf(VideoGenerationStore.load(context, activeProviderId, selectedModel))
    }
    fun saveVideoOptions(options: VideoGenerationOptions) {
        videoOptions = options
        VideoGenerationStore.save(context, activeProviderId, selectedModel, options)
    }
    var aspectRatioPage by remember(activeProviderId, selectedModel) { mutableStateOf(false) }
    var videoChoicePage by remember(activeProviderId, selectedModel) { mutableStateOf<VideoGenerationChoice?>(null) }
    var modelsExpanded by remember { mutableStateOf(false) }
    var listPrepared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // The collapsed popup does not draw the list; build its RecyclerView after the native menu entrance.
        delay(160)
        listPrepared = true
    }
    val motion = remember { ModelSwitcherTransition() }
    val phase = motion.phase
    val compactInteractive by remember { derivedStateOf { modelSwitcherCompactInteractive(modelsExpanded, phase.value) } }
    LaunchedEffect(modelsExpanded) {
        motion.animateTo(modelsExpanded)
    }
    androidx.activity.compose.BackHandler(enabled = modelsExpanded) { modelsExpanded = false }
    val compactHeight = modelSwitcherCompactHeight(imageModel, videoModel) +
        (modelSwitcherQuickActionsLayout(width - 16.dp).height - 88.dp) - 26.dp
    val videoChoice = videoChoicePage
    val naturalHeight = if (aspectRatioPage && imageModel) ((imageAspectRatioPresets.size + 1) * 36 + 152).dp
    else if (videoChoice != null && videoModel) (videoGenerationChoiceValues(videoChoice, selectedModel).size * 36 + 8).dp
    else rows.sumOf {
        when (it) {
            is ModelPickerRow.Model -> 36
            is ModelPickerRow.GroupLabel -> 26
            is ModelPickerRow.Gap -> 6
        }
    }.dp + 8.dp
    val listHeight = modelSwitcherListHeight(naturalHeight, configuration.screenHeightDp)
    val thinkingLevels = reasoningLevels.ifEmpty { listOf(ReasoningEffort.OFF) }
    var thinkingPreview by remember(selectedModel, thinkingLevels) {
        mutableStateOf(ThinkingRollState(thinkingLevels.indexOf(reasoningEffort).coerceAtLeast(0)))
    }
    val displayEffort = thinkingLevels[thinkingPreview.index.coerceIn(thinkingLevels.indices)]
    val listOpacity = { motion.listOpacity }
    val listOpacityState = remember { androidx.compose.runtime.derivedStateOf { motion.listOpacity } }
    DropdownMenuPanel(modifier = Modifier.width(width), keepIme = keepIme && !(imageModel && aspectRatioPage)) {
        Box(Modifier.width(width).padding(horizontal = 8.dp, vertical = 6.dp)
            .layout { measurable, constraints ->
                // 测量上限必须同时容下收起态主面板和展开列表，否则列表矮时会把主面板一起压扁。
                val measureCap = maxOf(listHeight, compactHeight)
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = measureCap.roundToPx()))
                val height = compactHeight.roundToPx() + ((listHeight - compactHeight).toPx() * phase.value).roundToInt()
                layout(placeable.width, height) { placeable.placeRelative(0, 0) }
            }.clipToBounds()) {
            Column(Modifier.fillMaxWidth().height(compactHeight)
                .graphicsLayer { alpha = modelSwitcherCompactOpacity(phase.value) }
                .semantics { if (modelSwitcherCompactOpacity(phase.value) < 0.5f) invisibleToUser() }) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).height(44.dp).clip(RoundedRectangle(12.dp))
                        .modelSwitcherClickable {
                            if (compactInteractive) {
                                aspectRatioPage = false
                                videoChoicePage = null
                                modelsExpanded = true
                            }
                        }
                        .semantics {
                            contentDescription = tr("选择模型")
                            stateDescription = tr(if (modelsExpanded) "收起模型" else "展开模型")
                        }.padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModelSwitcherModelName(selectedDisplay.ifBlank { tr("选择模型") },
                        modifier = Modifier.weight(1f, fill = false))
                    Icon(IrioIcons.ChevronDown, contentDescription = null, tint = muted,
                        modifier = Modifier.padding(start = 4.dp).size(18.dp).graphicsLayer { rotationZ = phase.value * 180f })
                }
                ModelSwitcherThinkingReadout(displayEffort, supportsThinking && !videoModel)
            }
                Spacer(Modifier.height(3.dp))
                ModelSwitcherParameterControls(
                    imageModel = imageModel,
                    imageOptions = imageOptions,
                    compactInteractive = compactInteractive,
                    supportsOpenAiOptions = !providerProtocol.isGoogleGemini,
                    reasoningEffort = reasoningEffort,
                    supportsThinking = supportsThinking,
                    reasoningLevels = reasoningLevels,
                    webSearchAvailable = webSearchAvailable,
                    webSearchEnabled = webSearchEnabled,
                    autoRead = autoRead,
                    onReasoningChange = onReasoningChange,
                    onThinkingPreviewChange = { thinkingPreview = it },
                    fastMode = fastMode,
                    onFastModeChange = onFastModeChange,
                    onWebSearchChange = onWebSearchChange,
                    onAutoReadChange = onAutoReadChange,
                    onImageOptionsChange = ::saveImageOptions,
                    onWebSearchUnavailable = onWebSearchUnavailable,
                    videoModel = videoModel,
                    videoOptions = videoOptions,
                    selectedModel = selectedModel,
                    onVideoOptionsChange = ::saveVideoOptions,
                    onVideoChoice = {
                        videoChoicePage = it
                        modelsExpanded = true
                    },
                    onAspectRatio = {
                        aspectRatioPage = true
                        modelsExpanded = true
                    },
                )
            }
            if (modelSwitcherListComposed(listPrepared, modelsExpanded)) {
                if (aspectRatioPage && imageModel) {
                    ImageAspectRatioList(
                        options = imageOptions,
                        modifier = Modifier.fillMaxWidth().height(listHeight)
                            .semantics { if (listOpacity() < 0.5f) invisibleToUser() },
                        opacity = listOpacityState,
                        active = modelsExpanded,
                        onSelect = {
                            saveImageOptions(imageOptions.copy(aspectRatio = it, useCustomAspectRatio = false))
                            modelsExpanded = false
                        },
                        onCustomChange = {
                            saveImageOptions(imageOptions.copy(aspectRatio = it, useCustomAspectRatio = true, customRatio = it))
                        },
                    )
                } else if (videoChoice != null && videoModel) {
                    VideoGenerationChoiceList(
                        choice = videoChoice,
                        model = selectedModel,
                        options = videoOptions,
                        modifier = Modifier.fillMaxWidth().height(listHeight)
                            .semantics { if (listOpacity() < 0.5f) invisibleToUser() },
                        opacity = listOpacityState,
                        active = modelsExpanded,
                        onSelect = {
                            saveVideoOptions(it)
                            modelsExpanded = false
                        },
                    )
                } else {
                AndroidComposeRecyclerList(
                    items = rows, key = { it.key },
                    modifier = Modifier.fillMaxWidth().height(listHeight)
                        .semantics { if (listOpacity() < 0.5f) invisibleToUser() },
                    contentPadding = PaddingValues(vertical = 4.dp),
                    cornerRadius = 12.dp,
                    opacity = listOpacityState,
                    interactionEnabled = modelsExpanded,
                    scrollToItemKey = if (modelsExpanded) rows.filterIsInstance<ModelPickerRow.Model>()
                        .firstOrNull { it.providerId == activeProviderId && selectedModel in it.variants }?.key else null,
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
                                if (selected) Icon(IrioIcons.Check, contentDescription = null, tint = accent, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
internal fun ModelSwitcherParameterControls(
    imageModel: Boolean,
    imageOptions: ImageGenerationOptions,
    compactInteractive: Boolean,
    supportsOpenAiOptions: Boolean,
    reasoningEffort: ReasoningEffort,
    supportsThinking: Boolean,
    reasoningLevels: List<ReasoningEffort>,
    webSearchAvailable: Boolean,
    webSearchEnabled: Boolean,
    autoRead: Boolean,
    onReasoningChange: (ReasoningEffort) -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
    onAutoReadChange: (Boolean) -> Unit,
    onImageOptionsChange: (ImageGenerationOptions) -> Unit,
    onAspectRatio: () -> Unit,
    videoModel: Boolean = false,
    videoOptions: VideoGenerationOptions = VideoGenerationOptions(),
    selectedModel: String = "MiniMax-H3",
    onVideoOptionsChange: (VideoGenerationOptions) -> Unit = {},
    onVideoChoice: (VideoGenerationChoice) -> Unit = {},
    fastMode: Boolean = false,
    onFastModeChange: (Boolean) -> Unit = {},
    onThinkingPreviewChange: (ThinkingRollState) -> Unit = {},
    onWebSearchUnavailable: () -> Unit = {},
) {
    ThinkingDepthSlider(reasoningEffort, supportsThinking && !videoModel, reasoningLevels, onReasoningChange,
        active = compactInteractive, onPreviewChange = onThinkingPreviewChange)
    if (imageModel) {
        ImageGenerationControls(imageOptions, compactInteractive, supportsOpenAiOptions, onImageOptionsChange, onAspectRatio)
    } else if (videoModel) {
        VideoGenerationControls(videoOptions, compactInteractive, selectedModel, onVideoOptionsChange, onVideoChoice)
    }
    // Only generation parameters need separation from the action row below them.
    if (imageModel || videoModel) Spacer(Modifier.height(12.dp))
    ModelSwitcherQuickActions(
        fastMode = fastMode,
        webSearchAvailable = webSearchAvailable,
        webSearchEnabled = webSearchEnabled,
        autoRead = autoRead,
        active = compactInteractive,
        onFastModeChange = onFastModeChange,
        onWebSearchChange = onWebSearchChange,
        onAutoReadChange = onAutoReadChange,
        onWebSearchUnavailable = onWebSearchUnavailable,
    )
}

/** Match actual glyph ink rather than centering incompatible font line boxes. */
@Composable
private fun rememberHeaderInkShift(text: String, style: TextStyle, layout: androidx.compose.ui.text.TextLayoutResult): Float {
    val density = LocalDensity.current
    val typeface = LocalFontFamilyResolver.current.resolve(style.fontFamily,
        style.fontWeight ?: FontWeight.Normal, style.fontStyle ?: FontStyle.Normal,
        style.fontSynthesis ?: FontSynthesis.All).value as android.graphics.Typeface
    val fontPx = with(density) { style.fontSize.toPx() }
    return remember(text, style, layout, typeface, fontPx) {
        val ink = android.graphics.Rect()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = fontPx
        }
        paint.getTextBounds(text, 0, text.length, ink)
        if (ink.isEmpty) 0f else layout.size.height / 2f - layout.firstBaseline - ink.exactCenterY()
    }
}

@Composable
internal fun ModelSwitcherModelName(text: String, modifier: Modifier = Modifier) {
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold))
    val layout = rememberTextMeasurer().measure(AnnotatedString(text), style, maxLines = 1, softWrap = false)
    val shift = rememberHeaderInkShift(text, style, layout)
    Text(text, modifier = modifier.graphicsLayer { translationY = shift }, color = Theme[colors][onBackgroundColor],
        fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Show the current thinking depth in the header without a text transition. */
@Composable
internal fun ModelSwitcherThinkingReadout(effort: ReasoningEffort, enabled: Boolean) {
    val color = when {
        !enabled || effort == ReasoningEffort.OFF -> Theme[colors][mutedColor]
        effort == ReasoningEffort.ULTRA -> UltraThinkingPurple
        else -> Theme[colors][primaryColor]
    }
    val text = tr(if (enabled) effort.label else "不支持思考")
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal))
    val layout = rememberTextMeasurer().measure(AnnotatedString(text), style, maxLines = 1, softWrap = false)
    val shift = rememberHeaderInkShift(text, style, layout)
    Row(Modifier.height(44.dp).semantics {
        contentDescription = tr("思考强度")
        stateDescription = tr(if (enabled) effort.label else "不支持思考")
    }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.graphicsLayer { translationY = shift }
            .semantics { contentDescription = text }, color = color,
            fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, maxLines = 1)
    }
}

private const val RollStagger = 0.5f
/** Stiffness matched to the previous 180ms fast-out roll. */
private const val RollSpringStiffness = 760f
private const val RollBlurMaxDp = 5f
private const val RollBleedXDp = 12
private const val RollBleedYDp = 6
private const val AscenderEm = 0.76f
@Composable
internal fun RollingGlyphLabel(
    text: String,
    color: Color,
    direction: Int,
    animate: Boolean,
    style: TextStyle,
    enterFromEmpty: Boolean = false,
    wrapContent: Boolean = false,
    centerHeaderInk: Boolean = false,
    hazeEnabled: Boolean = true,
    rightAligned: Boolean = false,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    val resolvedStyle = if (centerHeaderInk) LocalTextStyle.current.merge(style) else style
    val layout = measurer.measure(AnnotatedString(text), resolvedStyle, maxLines = 1, softWrap = false)
    val targets = remember(text, layout, rightAligned) {
        text.indices.map { index -> ThinkingGlyphFrame(index, text[index].toString(),
            layout.getBoundingBox(index).left - if (rightAligned) layout.size.width else 0) }
    }
    val isLatin = ThemeStore.language == AppLanguage.EN
    val fontPx = with(density) { style.fontSize.toPx() }
    // The line box reserves descender room, so Latin ink (ascender..baseline) sits above its centre and
    // reads as misaligned with the icon. Nudge it down to centre that band; CJK fills the box evenly.
    val opticalShift = if (centerHeaderInk) rememberHeaderInkShift(text, resolvedStyle, layout) else remember(layout, isLatin, density, fontPx) {
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
    // Drawing and glyph caches must use the same applied frame, even if an effect updates runs.
    val renderedRuns = runs
    val glyphLayouts = remember(renderedRuns, resolvedStyle, measurer, density.fontScale, density.density) {
        renderedRuns.map { it.start.character }.distinct().associateWith {
            measurer.measure(AnnotatedString(it), resolvedStyle, maxLines = 1, softWrap = false)
        }
    }
    val glyphBaselineOffsets = remember(layout, glyphLayouts) {
        glyphLayouts.mapValues { (_, glyphLayout) ->
            layout.firstBaseline - glyphLayout.firstBaseline
        }
    }
    val hazeModifier = if (hazeEnabled) {
        // Haze styles are immutable; quantize the radius to keep restyling cheap.
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
        Modifier.hazeBlur(input = HazeInput.Content, style = blurStyle)
    } else Modifier
    // Keep the original vertical roll travel and soft edge even when Haze is disabled.
    // Right-aligned headers retain outgoing width instead of painting horizontal blur bleed.
    val bleedX = if (rightAligned) 0 else with(density) { RollBleedXDp.dp.roundToPx() }
    val bleedY = with(density) { RollBleedYDp.dp.roundToPx() }
    val textHeight = layout.size.height
    val outgoingWidth = if (rightAligned)
        kotlin.math.ceil(-(renderedRuns.minOfOrNull { minOf(it.start.x, it.end.x) } ?: 0f)).toInt() else 0
    val textWidth = maxOf(layout.size.width, outgoingWidth)
    Canvas(Modifier
        .layout { measurable, constraints ->
            val width = if (wrapContent) constraints.constrainWidth(textWidth) else constraints.maxWidth
            val placeable = measurable.measure(Constraints.fixed(width + 2 * bleedX, textHeight + 2 * bleedY))
            layout(width, textHeight) { placeable.place(-bleedX, -bleedY) }
        }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadeX = bleedX / size.width
            val fadeY = bleedY / size.height
            if (bleedX > 0) {
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, fadeX to Color.Black, 1f - fadeX to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn)
            }
            drawRect(Brush.verticalGradient(0f to Color.Transparent, fadeY to Color.Black, 1f - fadeY to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn)
        }
        .then(hazeModifier)
        .clearAndSetSemantics { contentDescription = text }) {
        translate(bleedX.toFloat() + if (rightAligned) size.width - 2 * bleedX else 0f, bleedY.toFloat()) {
            val phase = progress.floatValue
            renderedRuns.forEach { run ->
                val glyph = run.frame(phase)
                if (glyph.alpha > 0.01f) {
                    drawText(
                        glyphLayouts.getValue(glyph.character),
                        color = color,
                        topLeft = Offset(
                            glyph.x,
                            glyph.y * textHeight + opticalShift + glyphBaselineOffsets.getValue(glyph.character),
                        ),
                        alpha = glyph.alpha,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingDepthSlider(effort: ReasoningEffort, enabled: Boolean, supportedLevels: List<ReasoningEffort>,
    onEffortChange: (ReasoningEffort) -> Unit, active: Boolean = true, onPreviewChange: (ThinkingRollState) -> Unit = {}) {
    val levels = supportedLevels.ifEmpty { listOf(ReasoningEffort.OFF) }
    val adjustable = active && enabled && levels.size > 1
    val selectedIndex = levels.indexOf(effort).coerceAtLeast(0)
    val muted = Theme[colors][mutedColor]
    val accent = Theme[colors][primaryColor]
    val trackColor = Theme[colors][secondaryColor]
    val density = LocalDensity.current
    val hapticContext = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var sliderWidth by remember { mutableIntStateOf(0) }
    var value by remember(levels) { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragging by remember(levels) { mutableStateOf(false) }
    var preview by remember(levels) { mutableStateOf(ThinkingRollState(selectedIndex)) }
    LaunchedEffect(selectedIndex, levels) {
        if (!dragging) {
            value = selectedIndex.toFloat()
            preview = preview.moveTo(selectedIndex)
            onPreviewChange(preview)
        }
    }
    val displayEffort = levels[preview.index]
    val motionAllowed = rememberSwitcherMotionAllowed()
    val ultra = enabled && displayEffort == ReasoningEffort.ULTRA
    val clock = rememberParticleClock(ultra && motionAllowed && active)
    val trackParticles = remember { ThinkingParticleField(System.nanoTime().toInt(), 36) }
    val fill by animateColorAsState(
        when {
            !enabled || displayEffort == ReasoningEffort.OFF -> muted
            ultra -> UltraThinkingPurple
            else -> accent
        }, irioSpring(), label = "thinking color",
    )
    val position by animateFloatAsState(value, if (dragging) snap() else irioSpring(dampingRatio = 0.9f, stiffness = 700f), label = "thinking position")
    val radius = with(density) { 12.dp.toPx() }
    val applySelection by rememberUpdatedState<(Int) -> Unit> { requested ->
        val next = preview.moveTo(requested.coerceIn(0, levels.lastIndex))
        if (next != preview) {
            preview = next
            onPreviewChange(next)
            AppHaptics.perform(hapticContext, HapticEvent.THINKING_LEVEL)
        }
    }
    val commitSelection by rememberUpdatedState<() -> Unit> {
        if (active && levels[preview.index] != effort) onEffortChange(levels[preview.index])
    }
    val fraction = { if (levels.size == 1) 0.5f else (position / levels.lastIndex).coerceIn(0f, 1f) }
    val geometry = { thinkingSliderGeometry(sliderWidth.toFloat(), radius, fraction(), rtl) }
    Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp).onSizeChanged { sliderWidth = it.width }) {
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
                        down.consume()
                        var draggingGesture = false
                        var released = false
                        fun moveTo(x: Float) {
                            val travel = (size.width - radius * 2f).coerceAtLeast(1f)
                            val physical = ((x - radius) / travel).coerceIn(0f, 1f)
                            val logical = if (rtl) 1f - physical else physical
                            value = (logical * levels.lastIndex).coerceIn(0f, levels.lastIndex.toFloat())
                            applySelection(value.roundToInt())
                        }
                        try {
                            do {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (change.pressed) {
                                    val distance = (change.position - down.position).getDistance()
                                    if (!draggingGesture && distance > viewConfiguration.touchSlop) {
                                        draggingGesture = true
                                        dragging = true
                                    }
                                    if (draggingGesture) {
                                        moveTo(change.position.x)
                                        change.consume()
                                    }
                                } else {
                                    if (!draggingGesture) moveTo(down.position.x)
                                    released = true
                                }
                            } while (!released)
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
    }
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
