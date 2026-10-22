package com.irio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.components.rememberBottomSheetState
import com.composables.ui.components.BottomSheetDetent
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle

@Composable
internal fun SubagentCapsuleRow(events: List<ProcessEvent>, modifier: Modifier = Modifier) {
    val snapshots = events.mapNotNull { subagentSnapshot(it.content) }
    if (snapshots.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        snapshots.forEach { snapshot ->
            key(snapshot.id) { SubagentCapsule(snapshot) }
        }
    }
}

@Composable
private fun SubagentCapsule(snapshot: SubagentSnapshot) {
    val context = LocalContext.current
    val live = SubagentHub.get(snapshot.id) ?: snapshot
    val tint by animateColorAsState(subagentTint(live.state), irioSpring(), label = "subagent tint")
    val fresh = remember(snapshot.id) { SubagentHub.isFresh(snapshot.id) }
    val reveal = remember(snapshot.id) { Animatable(if (fresh) 0f else 1f) }
    val fraction by reveal.asState()
    val completionFill = remember(snapshot.id) { Animatable(if (live.state == SubagentState.RUNNING) 0f else 1f) }
    val completionScale = remember(snapshot.id) { Animatable(1f) }
    val completionAlpha = remember(snapshot.id) { Animatable(if (live.state == SubagentState.RUNNING) 1f else 0f) }
    val indicatorWidth = remember(snapshot.id) { Animatable(if (live.state == SubagentState.RUNNING) 8f else 0f) }
    val indicatorGap = remember(snapshot.id) { Animatable(if (live.state == SubagentState.RUNNING) 6f else 0f) }
    var previousState by remember(snapshot.id) { mutableStateOf(live.state) }
    LaunchedEffect(snapshot.id) {
        if (!fresh) return@LaunchedEffect
        reveal.animateTo(1f, irioSpring())
        SubagentHub.settle(snapshot.id)
    }
    LaunchedEffect(snapshot.id, live.state) {
        val wasRunning = previousState == SubagentState.RUNNING
        if (live.state == SubagentState.RUNNING) {
            completionFill.snapTo(0f)
            completionScale.snapTo(1f)
            completionAlpha.snapTo(1f)
            indicatorWidth.snapTo(8f)
            indicatorGap.snapTo(6f)
        } else if (wasRunning) {
            completionScale.animateTo(4f, tween(180, easing = IrioEaseOut))
            completionFill.animateTo(1f, tween(280, easing = IrioEaseOut))
            completionAlpha.animateTo(0f, tween(120, easing = IrioEaseInOut))
            indicatorWidth.animateTo(0f, tween(180, easing = IrioEaseInOut))
            indicatorGap.animateTo(0f, tween(180, easing = IrioEaseInOut))
        } else {
            completionFill.snapTo(1f)
            completionScale.snapTo(1f)
            completionAlpha.snapTo(0f)
            indicatorWidth.snapTo(0f)
            indicatorGap.snapTo(0f)
        }
        previousState = live.state
    }
    val breathe = if (live.state == SubagentState.RUNNING) {
        val transition = rememberInfiniteTransition(label = "subagent breathe")
        val scale by transition.animateFloat(
            initialValue = 0.62f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = IrioEaseInOut), RepeatMode.Reverse),
            label = "subagent breathe",
        )
        scale
    } else {
        1f
    }
    val indicatorScale = if (live.state == SubagentState.RUNNING) breathe else completionScale.value
    val labelColor by animateColorAsState(
        if (live.state == SubagentState.RUNNING) Theme[colors][onBackgroundColor]
        else Color.White,
        irioSpring(),
        label = "subagent label color",
    )
    val label = "${live.name}，${subagentStatusLabel(live.state)}"
    val nameStyle = remember {
        TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    }
    Row(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val width = (placeable.width * fraction).toInt().coerceIn(0, placeable.width)
                layout(width, placeable.height) {
                    placeable.placeRelativeWithLayer(0, 0) {
                        scaleX = fraction
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                }
            }
            .clip(ContinuousCapsule)
            .drawBehind {
                drawRect(tint.copy(alpha = 0.16f))
                if (completionFill.value > 0f) {
                    drawRect(
                        tint,
                        size = size.copy(width = size.width * completionFill.value.coerceIn(0f, 1f)),
                    )
                }
            }
            .clickable {
                SubagentHub.hydrate(live)
                context.startActivity(
                    Intent(context, SubagentActivity::class.java).putExtra(SubagentActivity.EXTRA_ID, live.id),
                )
            }
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(indicatorGap.value.dp),
    ) {
        Box(
            Modifier
                .width(indicatorWidth.value.dp)
                .height(8.dp)
                .graphicsLayer {
                    scaleX = indicatorScale
                    scaleY = indicatorScale
                    alpha = completionAlpha.value
                }
                .clip(CircleShape)
                .background(tint),
        )
        RollingGlyphLabel(
            text = live.name,
            color = labelColor,
            direction = 1,
            animate = fresh,
            style = nameStyle,
            enterFromEmpty = fresh,
            wrapContent = true,
        )
    }
}

@Composable
private fun subagentTint(state: SubagentState): Color = when (state) {
    SubagentState.RUNNING -> Theme[colors][primaryColor]
    SubagentState.COMPLETED -> Color(0xFF2E9B57)
    SubagentState.FAILED -> Color(0xFFD32F2F)
}

class SubagentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        setIrioContent { SubagentScreen(id, onBack = { finish() }) }
    }

    companion object {
        const val EXTRA_ID = "subagent_id"
    }
}

@Composable
private fun SubagentScreen(id: String, onBack: () -> Unit) {
    val session = SubagentHub.get(id)
    val background = Theme[colors][backgroundColor]
    val running = session?.state == SubagentState.RUNNING
    val renderScope = rememberCoroutineScope()
    val hazeState = rememberHazeState()
    val chromePx = remember { mutableIntStateOf(0) }
    val topInset = with(androidx.compose.ui.platform.LocalDensity.current) { chromePx.intValue.toDp() }
    val processSheetState = rememberBottomSheetState()
    var openedProcess by remember { mutableStateOf<ProcessEvent?>(null) }
    var pronunciationError by remember { mutableStateOf<String?>(null) }
    var pronunciationErrorNonce by remember { mutableIntStateOf(0) }
    ObservePronunciationPlayback { message -> pronunciationError = message; pronunciationErrorNonce++ }
    val sheetEvent = openedProcess?.let { selected ->
        if (!selected.live) selected
        else session?.liveProcess?.firstOrNull { it.id == selected.id && it.toolName == selected.toolName } ?: selected
    }
    BackHandler(enabled = processSheetState.targetDetent != BottomSheetDetent.Hidden) {
        processSheetState.targetDetent = BottomSheetDetent.Hidden
    }
    val onOpenProcess = { event: ProcessEvent ->
        openedProcess = event
        processSheetState.targetDetent = BottomSheetDetent.FullyExpanded
    }
    Box(Modifier.fillMaxSize().background(background)) {
        Column(
            Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(top = topInset)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bubble = currentUserBubbleColors(editing = false)
            session?.lines.orEmpty().forEach { line ->
                if (line.fromUser) {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            line.text,
                            modifier = Modifier
                                .widthIn(max = maxWidth * 0.85f)
                                .clip(RoundedRectangle(16.dp))
                                .background(bubble.background)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            color = bubble.content,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                        )
                    }
                } else {
                    SubagentTranscript(line.text, line.process, streaming = false, renderScope = renderScope, onOpen = onOpenProcess)
                }
            }
            val live = session?.liveText.orEmpty()
            val liveProcess = session?.liveProcess.orEmpty()
            if (live.isNotBlank() || liveProcess.isNotEmpty()) {
                SubagentTranscript(live, liveProcess, streaming = true, renderScope = renderScope, onOpen = onOpenProcess)
            }
        }
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .zIndex(1f)
                .hazeBlur(input = HazeInput.Sources(hazeState), style = chatToolbarHazeStyle()),
        ) {
            Column(Modifier.onSizeChanged { chromePx.intValue = it.height }) {
                SettingsTitleCapsule(title = session?.name ?: tr("子智能体"), onBack = onBack)
            }
            Spacer(Modifier.height(ChatToolbarHazeBleed))
        }
        val fabFill = if (running) Theme[colors][primaryColor] else Theme[colors][mutedColor].copy(alpha = 0.28f)
        val fabInk = if (running) Theme[colors][onPrimaryColor] else Theme[colors][mutedColor]
        Button(
            onClick = { SubagentHub.stopById(id) },
            enabled = running,
            style = ButtonStyle.Ghost,
            shape = ContinuousCapsule,
            buttonSize = ButtonSize.Large,
            contentPadding = PaddingValues(0.dp),
            contentColor = fabInk,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 16.dp)
                .size(56.dp)
                .shadow(6.dp, ContinuousCapsule)
                .background(fabFill, ContinuousCapsule),
        ) {
            Icon(
                Icons.Rounded.Pause,
                contentDescription = tr("暂停"),
                tint = fabInk,
                modifier = Modifier.size(24.dp),
            )
        }
        ProcessEventSheet(
            event = sheetEvent,
            state = processSheetState,
            onDismiss = { processSheetState.targetDetent = BottomSheetDetent.Hidden },
        )
        IrioSnackbar(
            visible = pronunciationError != null,
            message = pronunciationError.orEmpty(),
            bottomInset = with(androidx.compose.ui.platform.LocalDensity.current) {
                WindowInsets.systemBars.getBottom(this).toDp()
            } + 72.dp,
            onDismiss = { pronunciationError = null },
            restartKey = pronunciationErrorNonce,
        )
    }
}

@Composable
private fun SubagentTranscript(
    text: String,
    process: List<ProcessEvent>,
    streaming: Boolean,
    renderScope: kotlinx.coroutines.CoroutineScope,
    onOpen: (ProcessEvent) -> Unit,
) {
    val pieces = interleaveTranscript(text, renderedProcessEvents(process, ThemeStore.mergeToolCalls))
    val pronunciations = remember(process) {
        pronunciationRecordings(process.filter { it.toolName == LinguaLibreTool.NAME && it.toolOutcome != ToolOutcome.FAILED }
            .map { ChatToolRecord(ChatToolCall("stored", LinguaLibreTool.NAME, "{}"), it.content) })
    }
    val lastTextIndex = pieces.indexOfLast { it is TranscriptPiece.Text }
    var seenText = false
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pieces.forEachIndexed { index, piece ->
            when (piece) {
                is TranscriptPiece.Event -> key("process-${piece.event.textOffset}-${piece.event.id}-${piece.event.kind}") {
                    ProcessEventRow(piece.event, onOpen = onOpen)
                }
                is TranscriptPiece.Text -> {
                    val shown = if (seenText) piece.text.trimStart('\n') else piece.text
                    seenText = true
                    if (shown.isNotBlank()) {
                        key("body-$index") {
                            Box(Modifier.padding(vertical = 2.dp)) {
                                AssistantMarkdown(
                                    text = shown,
                                    streaming = streaming && index == lastTextIndex,
                                    renderScope = renderScope,
                                    pronunciations = pronunciations,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
