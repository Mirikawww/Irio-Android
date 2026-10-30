package com.irio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.recyclerview.widget.RecyclerView
import com.composables.ui.components.AlertDialog
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ChatScreenLayout(state: ChatScreenState) =
    with(state) {
        val composerBarHeight = with(LocalDensity.current) { composerBarHeightPx.toDp() }
        val toolbarHeight = with(LocalDensity.current) { toolbarHeightPx.toDp() }
        val toolbarContentPaddingPx =
            with(LocalDensity.current) { (toolbarHeight + 16.dp).toPx().roundToInt() }
        val composerContentPaddingPx =
            with(LocalDensity.current) { (composerBarHeight + 16.dp).toPx().roundToInt() }
        val background = Theme[colors][backgroundColor]
        val content = Theme[colors][onBackgroundColor]
        val density = LocalDensity.current
        val transcriptBottomSlackPx = with(density) { 24.dp.toPx().roundToInt() }
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        val drawerOpenProgress by
            animateFloatAsState(
                targetValue = drawerProgress,
                animationSpec = if (drawerDragging) snap() else irioSpring<Float>(stiffness = 360f),
                label = "drawerOpenProgress",
            )
        val pageDim = 0.28f * drawerOpenProgress
        val drawerProgressState = rememberUpdatedState(drawerProgress)
        val renderContentArgb = content.toArgb()
        val renderPxPerPt = density.density

        val messageCallbacks = rememberChatMessageCallbacks(state)

        CompositionLocalProvider(LocalChatHazeState provides chatHazeState) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier.fillMaxSize().background(background).drawWithContent {
                            drawContent()
                            if (pageDim > 0.001f) {
                                drawRect(Color.Black.copy(alpha = pageDim))
                            }
                        }
                )
                ChatConversationDrawer(state, drawerOpenProgress)

                Box(modifier = Modifier.fillMaxSize().zIndex(0f)) {
                    Scaffold(
                        modifier =
                            Modifier.fillMaxSize()
                                .drawWithContent {
                                    drawContent()
                                    if (pageDim > 0.001f) {
                                        drawRect(Color.Black.copy(alpha = pageDim))
                                    }
                                }
                                .drawerSwipe(
                                    drawerWidthPx = drawerWidthPx,
                                    openDirection = true,
                                    allowTapToClose = false,
                                    progress = { drawerProgressState.value },
                                    onStart = {
                                        drawerDragging = true
                                        drawerStartProgress = drawerProgressState.value
                                    },
                                    onDrag = { drawerProgress = it },
                                    onSettle = { settleDrawer(drawerProgressState.value) },
                                    onTapClose = {},
                                ),
                        containerColor = background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    ) { paddingValues ->
                        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                            // 背景图固定铺满聊天页，不跟键盘一起重新缩放，避免出现黑边。
                            Box(modifier = Modifier.fillMaxSize().background(background)) {
                                ChatBackgroundImage()
                            }
                            BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
                                AndroidMessageList(
                                    messages = shownTranscript,
                                    controller = messageRecyclerController,
                                    modifier =
                                        Modifier.fillMaxSize()
                                            .hazeSource(chatHazeState)
                                            .then(
                                                if (ChatBackgroundEnabled) Modifier
                                                else Modifier.background(background)
                                            ),
                                    contentPaddingTop = toolbarContentPaddingPx,
                                    contentPaddingBottom = composerContentPaddingPx,
                                    callbacks = messageCallbacks,
                                    onVisibleRangeChanged = { first, last ->
                                        val snapshot = shownTranscript
                                        val conversationId = currentConversation.id
                                        val candidates =
                                            listOf(first - 1, last + 1)
                                                .filter { it in snapshot.indices }
                                                .distinct()
                                        transcriptPrewarmJob.value?.cancel()
                                        transcriptPrewarmJob.value =
                                            if (candidates.isEmpty()) {
                                                null
                                            } else {
                                                scope.launch {
                                                    // 先让当前可见行完成首帧和自身渲染，再预热邻居。
                                                    withFrameNanos {}
                                                    delay(180)
                                                    if (currentConversation.id != conversationId)
                                                        return@launch
                                                    candidates.forEach { index ->
                                                        prepareFinishedMessage(
                                                            snapshot[index],
                                                            renderContentArgb,
                                                            renderPxPerPt,
                                                            renderTexScale,
                                                            scope,
                                                        )
                                                    }
                                                }
                                            }
                                    },
                                    onScrollStateChanged = { state ->
                                        val scrolling = state != RecyclerView.SCROLL_STATE_IDLE
                                        recyclerScrolling = scrolling
                                        if (scrolling && generatingIds.isNotEmpty()) {
                                            streamingDeferActive.set(true)
                                        }
                                    },
                                    onUserScrollSample = { scrollDeltaY ->
                                        if (!messageRecyclerController.isProgrammaticScroll()) {
                                            val pinned =
                                                if (scrollDeltaY > 0) {
                                                    messageRecyclerController.isPinnedToBottom(
                                                        transcriptBottomSlackPx
                                                    )
                                                } else {
                                                    null
                                                }
                                            stickToBottom =
                                                transcriptBottomLockAfterUserScroll(
                                                    stickToBottom,
                                                    scrollDeltaY,
                                                    pinned,
                                                )
                                        }
                                    },
                                )

                                ChatComposerBar(state, maxHeight)

                                ScrollToBottomButton(
                                    visible = !messageRecyclerController.scrollButtonHidden &&
                                        shownTranscript.isNotEmpty() && !composerFullscreen &&
                                        pendingConversationScroll != currentConversation.id,
                                    responding = busyThisChat,
                                    bottomInset = composerBarHeight,
                                    onClick = {
                                        stickToBottom = true
                                        messageRecyclerController.revealTail()
                                    },
                                )

                                ChatToolbar(state)
                                IrioSnackbar(
                                    visible = composerStatus != null,
                                    message = composerStatus.orEmpty(),
                                    bottomInset = composerBarHeight + 16.dp,
                                    onDismiss = { composerStatus = null },
                                    restartKey = composerStatusNonce,
                                )
                            }
                        }
                    }

                    if (drawerOpenProgress > 0.01f) {
                        Box(
                            modifier =
                                Modifier.matchParentSize()
                                    .drawerSwipe(
                                        drawerWidthPx = drawerWidthPx,
                                        openDirection = false,
                                        allowTapToClose = true,
                                        progress = { drawerProgressState.value },
                                        onStart = {
                                            drawerDragging = true
                                            drawerStartProgress = drawerProgressState.value
                                        },
                                        onDrag = { drawerProgress = it },
                                        onSettle = { settleDrawer(drawerProgressState.value) },
                                        onTapClose = { drawerProgress = 0f },
                                    )
                        )
                    }
                }
                Box(Modifier.fillMaxSize().zIndex(2f)) {
                    ChatNoticeSnackbar(
                        titleNotice = titleGeneration.notice,
                        titleNoticeNonce = titleGeneration.noticeNonce,
                        bottomInset =
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                        onDismissTitle = { titleGeneration.notice = null },
                    )
                }
                ClearHistoryDialog(
                    visible = clearHistoryDialogOpen,
                    onDismiss = { clearHistoryDialogOpen = false },
                    onConfirm = {
                        clearHistoryDialogOpen = false
                        clearAllHistory()
                    },
                )
                viewingPhoto?.let { photo ->
                    PhotoViewer(attachment = photo, onDismiss = { viewingPhoto = null })
                }
                ProcessEventSheet(
                    event =
                        messages
                            .firstOrNull { it.id == processSheetMessageId }
                            ?.process
                            ?.firstOrNull { it.id == processSheetEventId },
                    state = processSheetState,
                    onDismiss = { processSheetState.targetDetent = BottomSheetDetent.Hidden },
                )
                ReactionEmojiSheet(
                    state = reactionSheetState,
                    onDismiss = { reactionSheetState.targetDetent = BottomSheetDetent.Hidden },
                    onSelect = { emoji ->
                        reactionTarget?.let { (conversationId, messageId) ->
                            updateMessageReaction(
                                conversationId,
                                messageId,
                                emoji,
                                fromModel = false,
                            )
                        }
                        reactionSheetState.targetDetent = BottomSheetDetent.Hidden
                    },
                )
                TerminalCommandSheet(
                    event =
                        backgroundSheetEvent
                            ?: ProcessEvent(
                                id = 0,
                                kind = ProcessEventKind.TOOL,
                                toolName = LinuxShellTool.NAME,
                                content = "$",
                            ),
                    state = backgroundSheetState,
                    onDismiss = { backgroundSheetState.targetDetent = BottomSheetDetent.Hidden },
                )
            }
        }
    }

@Composable
private fun ClearHistoryDialog(visible: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        backgroundColor = Theme[colors][panelColor],
        contentColor = Theme[colors][onPanelColor],
    ) {
        ProvideAppTextScale {
        Column {
            DialogTitle(tr("删除全部历史"))
            Spacer(Modifier.height(12.dp))
            Text(tr("确定要删除全部历史聊天记录吗？此操作无法撤销。"), color = Theme[colors][onPanelColor])
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = tr("删除"),
                onConfirm = onConfirm,
                onCancel = onDismiss,
                confirmStyle = ButtonStyle.Destructive,
            )
        }
        }
    }
}
