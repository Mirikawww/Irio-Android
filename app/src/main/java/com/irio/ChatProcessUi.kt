package com.irio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irio.ScaledBottomSheet as BottomSheet
import com.composables.ui.components.BottomSheetState
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme
import java.util.Locale

@Composable
internal fun ProcessRun(
    events: List<ProcessEvent>,
    onOpen: (ProcessEvent) -> Unit,
    onQuote: (String) -> Unit,
) {
    transcriptRenderRows(events.map { TranscriptPiece.Event(it) }).forEach { row ->
        when (row) {
            is TranscriptRenderRow.Subagents ->
                key(row.events.joinToString("-") { it.id.toString() }) {
                    SubagentCapsuleRow(row.events)
                }
            is TranscriptRenderRow.Single -> {
                val event = (row.piece as TranscriptPiece.Event).event
                key("process-${event.id}") {
                    ProcessEventRow(event, onOpen = onOpen, onQuote = onQuote)
                }
            }
        }
    }
}

@Composable
internal fun ProcessEventRow(
    event: ProcessEvent,
    onOpen: (ProcessEvent) -> Unit,
    onQuote: (String) -> Unit = {},
    onOpenImage: (ChatAttachment) -> Unit = {},
) {
    event.imageGeneration?.let { progress ->
        if (progress.active || progress.finalAttachment != null) {
            key(progress.id) { ImageGenerationCard(progress, onOpenImage) }
            return
        }
    }
    if (isSilentPronunciationLookup(event.toolName, event.toolOutcome)) return
    if (event.toolName == MermaidTool.NAME && event.toolOutcome == ToolOutcome.SUCCESS) {
        MermaidMarkdownBlock(event.content)
        return
    }
    if (
        event.toolName == CommonsImageTools.SHOW &&
            event.toolOutcome != ToolOutcome.FAILED &&
            event.toolOutcome != ToolOutcome.STOPPED
    ) {
        CommonsImageBlock(event.content)
        return
    }
    if (event.kind == ProcessEventKind.SUBAGENT) {
        SubagentCapsuleRow(listOf(event), Modifier.padding(vertical = 0.dp))
        return
    }
    if (event.kind == ProcessEventKind.BUBBLE) {
        ColoredBubbleView(event, onQuote)
        return
    }
    if (
        !event.live &&
            event.toolName == ImageGenTool.NAME &&
            event.content == ImageGenTool.STATUS_OK
    ) {
        return
    }
    val thinkingMode = thinkingRowMode(event.kind, event.live, ThemeStore.thinkingBlock)
    if (thinkingMode == ThinkingRowMode.Hidden) return
    if (thinkingMode == ThinkingRowMode.StatusOnly) {
        StatusSweepText(tr("思考中"))
        return
    }
    val muted = Theme[colors][mutedColor]
    if (event.toolName == MERGED_TOOL_CALLS_NAME) {
        Text(
            event.content,
            color = muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        return
    }
    val terminal = event.kind == ProcessEventKind.TOOL && event.toolName == LinuxShellTool.NAME
    val openable =
        (event.toolName != ImageGenTool.NAME || event.toolOutcome == ToolOutcome.FAILED) &&
            (terminal || (!event.live && (event.content.isNotBlank() || event.toolOutcome == ToolOutcome.FAILED)))
    val label =
        when (event.kind) {
            ProcessEventKind.THINKING ->
                if (event.live) null else tr("已思考 %s").format(formatThinkingDuration(event.millis))
            ProcessEventKind.SEARCH,
            ProcessEventKind.TOOL -> if (event.live) null else event.completedLabel()
            ProcessEventKind.BUBBLE,
            ProcessEventKind.SUBAGENT -> null
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (event.live) {
            if (terminal) {
                Row(
                    modifier = Modifier.clickable { onOpen(event) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    StatusSweepText(
                        event.displayText.ifEmpty { MemoryTool.liveLabel(event.toolName) }
                    )
                    Icon(
                        imageVector = IrioIcons.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = muted,
                    )
                }
            } else {
                StatusSweepText(
                    event.displayText.ifEmpty {
                        when (event.kind) {
                            ProcessEventKind.THINKING -> tr("思考中")
                            ProcessEventKind.SEARCH -> searchStatusLabel(event.searchQuery)
                            ProcessEventKind.TOOL ->
                                MemoryTool.liveLabel(event.toolName, event.webDomain)
                            ProcessEventKind.BUBBLE,
                            ProcessEventKind.SUBAGENT -> ""
                        }
                    }
                )
                if (event.kind == ProcessEventKind.THINKING && event.content.isNotBlank()) {
                    val thinkingScrollState = rememberScrollState()
                    val thinkingFollowGate = remember { TranscriptScrollGate() }
                    val followThinkingBottom = remember { mutableStateOf(true) }
                    val thinkingFollowConnection =
                        remember(thinkingScrollState, thinkingFollowGate) {
                            object : NestedScrollConnection {
                                override fun onPostScroll(
                                    consumed: Offset,
                                    available: Offset,
                                    source: NestedScrollSource,
                                ): Offset {
                                    if (
                                        thinkingFollowGate.depth == 0 &&
                                            source == NestedScrollSource.UserInput
                                    ) {
                                        followThinkingBottom.value =
                                            thinkingScrollState.value >=
                                                thinkingScrollState.maxValue - 48
                                    }
                                    return Offset.Zero
                                }

                                override suspend fun onPostFling(
                                    consumed: Velocity,
                                    available: Velocity,
                                ): Velocity {
                                    if (thinkingFollowGate.depth == 0) {
                                        followThinkingBottom.value =
                                            thinkingScrollState.value >=
                                                thinkingScrollState.maxValue - 48
                                    }
                                    return Velocity.Zero
                                }
                            }
                        }
                    val hostView = LocalView.current
                    LaunchedEffect(followThinkingBottom.value) {
                        if (!followThinkingBottom.value) return@LaunchedEffect
                        thinkingScrollState.smoothFollowBottom(thinkingFollowGate) {
                            followThinkingBottom.value
                        }
                    }
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(96.dp)
                                // The row itself lives inside the transcript RecyclerView. Let this
                                // nested scroller keep the gesture instead of RecyclerView stealing
                                // it.
                                .pointerInteropFilter { event ->
                                    when (event.actionMasked) {
                                        android.view.MotionEvent.ACTION_DOWN -> {
                                            hostView.parent?.requestDisallowInterceptTouchEvent(
                                                true
                                            )
                                            false
                                        }
                                        android.view.MotionEvent.ACTION_UP,
                                        android.view.MotionEvent.ACTION_CANCEL -> {
                                            hostView.parent?.requestDisallowInterceptTouchEvent(
                                                false
                                            )
                                            false
                                        }
                                        else -> false
                                    }
                                }
                                .nestedScroll(thinkingFollowConnection)
                                .verticalScroll(thinkingScrollState)
                    ) {
                        Text(event.content, color = muted, fontSize = 15.sp)
                    }
                }
            }
        } else if (label != null) {
            Row(
                modifier =
                    Modifier.padding(bottom = 2.dp)
                        .then(
                            if (openable) {
                                Modifier.clickable { onOpen(event) }
                            } else {
                                Modifier
                            }
                        ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (videoGenerationPending(event)) {
                    // Task creation has ended, but the video is still being generated.
                    // Keep the native tool-call highlight until the background query settles.
                    StatusSweepText(event.displayText.ifEmpty { MemoryTool.liveLabel(event.toolName) })
                } else {
                    Text(
                        label,
                        color =
                            toolStatusTextColor(
                                event.toolOutcome,
                                LocalResolvedTheme.current.isDark,
                                muted,
                                irioErrorTextColor(),
                            ),
                        fontSize = 13.sp,
                    )
                }
                if (openable) {
                    Icon(
                        imageVector = IrioIcons.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = muted,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProcessEventSheet(
    event: ProcessEvent?,
    state: BottomSheetState,
    onDismiss: () -> Unit,
) {
    if (event != null && event.toolOutcome == ToolOutcome.FAILED &&
        (event.kind == ProcessEventKind.TOOL || event.kind == ProcessEventKind.SEARCH)) {
        ToolFailureSheet(event, state, onDismiss)
        return
    }
    if (event?.kind == ProcessEventKind.TOOL && event.toolName == LinuxShellTool.NAME) {
        TerminalCommandSheet(event, state, onDismiss)
        return
    }
    val content = Theme[colors][onPanelColor]
    BottomSheet(
        state = state,
        onDismissRequest = onDismiss,
        contentColor = content,
        contentPadding = PaddingValues(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 8.dp),
        scrollableBody = true,
    ) {
        val minSheetHeight =
            with(LocalDensity.current) {
                (LocalWindowInfo.current.containerSize.height * 0.32f).toDp()
            }
        val maxSheetHeight =
            with(LocalDensity.current) {
                (LocalWindowInfo.current.containerSize.height * 0.85f).toDp()
            }
        val body = event?.content.orEmpty()
        if (event?.kind == ProcessEventKind.SEARCH || event?.toolName == WebSearchTool.NAME) {
            SearchResultsSheetContent(
                body,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = minSheetHeight, max = maxSheetHeight),
            )
            return@BottomSheet
        }
        val chunks = remember(body) { splitSheetChunks(body) }
        val longContent = body.length > 800 || chunks.size > 1
        if (longContent) {
            LazyColumn(
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = minSheetHeight, max = maxSheetHeight),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = LocalBottomSheetBottomPadding.current),
            ) {
                items(chunks.size) { index ->
                    Text(chunks[index], fontSize = 16.sp, color = content)
                }
            }
        } else {
            Column(
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = minSheetHeight, max = maxSheetHeight)
                        .padding(bottom = LocalBottomSheetBottomPadding.current)
            ) {
                if (body.isNotBlank()) {
                    Text(body, fontSize = 16.sp, color = content)
                }
            }
        }
    }
}

@Composable
internal fun TerminalCommandSheet(
    event: ProcessEvent,
    state: BottomSheetState,
    onDismiss: () -> Unit,
) {
    val textColor = Color(0xFFE8E8E8)
    BottomSheet(
        state = state,
        onDismissRequest = onDismiss,
        backgroundColor = Color.Black,
        contentColor = textColor,
        contentPadding = PaddingValues(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 16.dp),
        scrollableBody = true,
    ) {
        val minSheetHeight =
            with(LocalDensity.current) {
                (LocalWindowInfo.current.containerSize.height * 0.32f).toDp()
            }
        val maxSheetHeight =
            with(LocalDensity.current) {
                (LocalWindowInfo.current.containerSize.height * 0.85f).toDp()
            }
        val scroll = rememberScrollState()
        val wasNearBottom = scroll.value >= scroll.maxValue - 48
        LaunchedEffect(event.content, event.live) {
            withFrameNanos {}
            if (wasNearBottom) scroll.scrollTo(scroll.maxValue)
        }
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = minSheetHeight, max = maxSheetHeight)
                    .verticalScroll(scroll)
                    .padding(bottom = LocalBottomSheetBottomPadding.current)
        ) {
            SelectionContainer {
                Text(
                    event.content.ifBlank { "$" },
                    color = textColor,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun formatThinkingDuration(millis: Long): String {
    val seconds = (millis / 1000.0)
    return if (seconds < 10) {
        String.format(Locale.CHINA, tr("%.1f 秒"), seconds)
    } else {
        tr("%s 秒").format(seconds.toInt())
    }
}

private fun splitSheetChunks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val paragraphs = text.split("\n\n")
    val chunks = mutableListOf<String>()
    val buffer = StringBuilder()
    fun flush() {
        if (buffer.isNotEmpty()) {
            chunks += buffer.toString()
            buffer.clear()
        }
    }
    for (paragraph in paragraphs) {
        if (buffer.length + paragraph.length > 800 && buffer.isNotEmpty()) flush()
        if (buffer.isNotEmpty()) buffer.append("\n\n")
        buffer.append(paragraph)
        if (buffer.length >= 800) flush()
    }
    flush()
    return chunks
}
