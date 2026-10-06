package com.irio

import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import androidx.compose.material.icons.rounded.Download
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.composables.ui.components.BottomSheet
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.BottomSheetState
import dev.chrisbanes.haze.HazeInput


import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.composables.ui.components.Button

import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.composables.ui.components.DropdownMenuPanel
import com.composables.ui.components.DropdownMenuSeparator
import com.composables.ui.components.DropdownMenuSide
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.components.rememberBottomSheetState
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.destructiveColor
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.ProvideTextStyle
import com.composeunstyled.theme.Theme
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import java.awt.Color as AwtColor
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    var processSheetEvent by remember { mutableStateOf<ProcessEvent?>(null) }
    val processSheetState = rememberBottomSheetState()
    val message = rememberTextFieldState()
    var conversations by remember { mutableStateOf(emptyList<ConversationSummary>()) }
    var currentConversation by remember {
        mutableStateOf(ConversationStore.newConversation())
    }
    var messages by remember { mutableStateOf(currentConversation.messages) }
    var newUserMessageId by remember { mutableStateOf<Long?>(null) }
    var pendingAttachments by remember { mutableStateOf(emptyList<ChatAttachment>()) }
    var clearingHistory by remember { mutableStateOf(false) }
    var composerStatus by remember { mutableStateOf<String?>(null) }
    /** 正在编辑的用户消息 id；null = 普通发送模式。 */
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    // 未发送的输入按会话分开。当前会话的内容只在输入框里，切走才写入这张表。
    val composerDrafts = remember { mutableMapOf<String, ComposerDraft>() }
    fun swapComposer(fromId: String, toId: String) {
        val restored = swapComposerDraft(
            composerDrafts,
            fromId,
            toId,
            ComposerDraft(
                text = message.text.toString(),
                attachments = pendingAttachments,
                editingMessageId = editingMessageId,
            ),
        )
        if (message.text.toString() != restored.text) {
            message.edit { replace(0, length, restored.text) }
        }
        pendingAttachments = restored.attachments
        editingMessageId = restored.editingMessageId
        composerStatus = null
    }
    var selectedModel by remember {
        mutableStateOf(ProviderStore.loadProvider()?.selectedModel.orEmpty())
    }
    var providers by remember { mutableStateOf(ProviderStore.loadProviders()) }
    var activeProviderId by remember { mutableStateOf(ProviderStore.loadActiveProviderId()) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var contextRingOpen by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var reasoningEffort by remember { mutableStateOf(ProviderStore.loadReasoningEffort()) }
    var fastMode by remember { mutableStateOf(ProviderStore.loadFastMode()) }
    var mergeModels by remember { mutableStateOf(ProviderStore.loadMergeModels()) }
    var webSearchAvailable by remember { mutableStateOf(SearchStore.hasApiKey()) }
    var webSearchEnabled by remember { mutableStateOf(SearchStore.loadWebSearchEnabled()) }
    var autoRead by remember { mutableStateOf(SpeechStore.loadAutoRead()) }
    var visualizeContext by remember { mutableStateOf(ToolStore.loadVisualizeContext()) }
    var liveContextTokens by remember { mutableIntStateOf(0) }
    var clearHistoryDialogOpen by remember { mutableStateOf(false) }
    var conversationAction by remember { mutableStateOf<ConversationSummary?>(null) }
    var viewingPhoto by remember { mutableStateOf<ChatAttachment?>(null) }
    var generations by remember { mutableStateOf(emptyMap<Long, ActiveGeneration>()) }
    val currentConversationState = rememberUpdatedState(currentConversation)
    val generationsSnapshotState = rememberUpdatedState(generations)
    val listState = rememberLazyListState()
    val stickToBottomState = remember { mutableStateOf(true) }
    var stickToBottom by stickToBottomState
    val userDrivingTranscriptState = remember { mutableStateOf(false) }
    var userDrivingTranscript by userDrivingTranscriptState
    var seenTranscriptConversationId by remember { mutableStateOf<String?>(null) }
    var seenTranscriptTailId by remember { mutableStateOf<Long?>(null) }
    var seenSentUserMessageId by remember { mutableStateOf<Long?>(null) }
    val transcriptScrollGate = remember { TranscriptScrollGate() }
    val transcriptBottomSlackPx = with(LocalDensity.current) { 24.dp.toPx().roundToInt() }
    val transcriptFollowConnection = remember(listState, transcriptBottomSlackPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (transcriptScrollGate.depth == 0 && source == NestedScrollSource.UserInput) {
                    userDrivingTranscriptState.value = true
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (transcriptScrollGate.depth == 0 && source == NestedScrollSource.UserInput) {
                    lazyTranscriptPinnedToBottom(listState, transcriptBottomSlackPx)?.let {
                        stickToBottomState.value = it
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                userDrivingTranscriptState.value = false
                if (transcriptScrollGate.depth == 0) {
                    lazyTranscriptPinnedToBottom(listState, transcriptBottomSlackPx)?.let {
                        stickToBottomState.value = it
                    }
                }
                return Velocity.Zero
            }
        }
    }
    var composerBarHeightPx by remember { mutableIntStateOf(0) }
    val composerBarHeight = with(LocalDensity.current) { composerBarHeightPx.toDp() }
    var toolbarHeightPx by remember { mutableIntStateOf(0) }
    val toolbarHeight = with(LocalDensity.current) { toolbarHeightPx.toDp() }
    val chatHazeState = rememberHazeState()
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val drawerWidth = 280.dp
    val density = LocalDensity.current
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    var drawerProgress by remember { mutableStateOf(0f) }
    var drawerDragging by remember { mutableStateOf(false) }
    var drawerStartProgress by remember { mutableStateOf(0f) }
    val drawerOpenProgress by animateFloatAsState(
        targetValue = drawerProgress,
        animationSpec = if (drawerDragging) snap() else tween(420, easing = FastOutSlowInEasing),
        label = "drawerOpenProgress",
    )
    val pageOffset = drawerWidth * drawerOpenProgress
    val pageDim = 0.28f * drawerOpenProgress
    val drawerShadowPx = with(density) { 8.dp.toPx() }
    val drawerOpen = drawerProgress >= 0.5f
    var pendingConversationScroll by remember { mutableStateOf<String?>(null) }
    var conversationLoadToken by remember { mutableIntStateOf(0) }
    val groupedConversationRows = remember(conversations, ThemeStore.language) { groupedConversations(conversations) }
    val generatingIds = remember(generations) { generations.values.map { it.conversationId }.toSet() }
    val generatingThisChat = currentConversation.id in generatingIds
    var transcriptPresentation by remember {
        mutableStateOf(buildTranscriptPresentation(currentConversation.id, emptyList()))
    }
    LaunchedEffect(currentConversation.id, messages) {
        val conversationId = currentConversation.id
        val messageSnapshot = messages
        if (transcriptPresentation.conversationId != conversationId) {
            transcriptPresentation = buildTranscriptPresentation(conversationId, emptyList())
        }
        val next = withContext(Dispatchers.Default) {
            buildTranscriptPresentation(conversationId, messageSnapshot)
        }
        if (currentConversation.id == conversationId && messages === messageSnapshot) {
            transcriptPresentation = next
        }
    }
    val presentationReady = transcriptPresentation.conversationId == currentConversation.id
    val visibleTranscript = if (presentationReady) transcriptPresentation.visible else emptyList()
    // 压缩记录留在列表里，但只画状态行，不画摘要正文。
    val shownTranscript = visibleTranscript
    val retryGroups = if (presentationReady) transcriptPresentation.retryGroups else emptyMap()
    val editGroups = if (presentationReady) transcriptPresentation.editGroups else emptyMap()
    val renderContentArgb = content.toArgb()
    val renderPxPerPt = density.density
    val renderTexScale = remember { ProviderStore.loadTexScale() }

    LaunchedEffect(currentConversation.id, shownTranscript) {
        if (shownTranscript.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.firstOrNull()?.index to visible.lastOrNull()?.index
        }
            .distinctUntilChanged()
            .collect { (first, last) ->
                if (first == null || last == null) return@collect
                val start = (first - 2).coerceAtLeast(0)
                val end = (last + 2).coerceAtMost(shownTranscript.lastIndex)
                for (index in start..end) {
                    prepareFinishedMessage(
                        shownTranscript[index], renderContentArgb, renderPxPerPt, renderTexScale, scope,
                    )
                }
            }
    }
    fun settleDrawer(progress: Float) {
        drawerDragging = false
        val delta = progress - drawerStartProgress
        drawerProgress = when {
            delta >= 0.35f -> 1f
            delta <= -0.35f -> 0f
            else -> if (drawerStartProgress >= 0.5f) 1f else 0f
        }
    }

    val drawerProgressState = rememberUpdatedState(drawerProgress)

    fun persistCurrent(updatedMessages: List<ChatMessage> = messages, touchUpdatedAt: Boolean = true) {
        if (clearingHistory) return
        if (updatedMessages.isEmpty() && currentConversation.messages.isEmpty()) return
        val stored = currentConversation.copy(
            // titlePending 期间用截断文本临时显示；AI 生成（或手动改名）之后不再被覆盖
            title = if (currentConversation.titlePending) {
                ConversationStore.titleFrom(updatedMessages)
            } else {
                currentConversation.title
            },
            updatedAt = if (touchUpdatedAt) System.currentTimeMillis() else currentConversation.updatedAt,
            messages = updatedMessages.filterNot {
                it.isPending && it.text.isBlank() && it.process.none { event ->
                    event.live || event.content.isNotBlank() || event.millis > 0L || event.pages > 0
                }
            },
            tokenUsage = currentConversation.tokenUsage
                ?: updatedMessages.lastOrNull { !it.fromUser && it.usage != null }?.usage,
        )
        currentConversation = stored
        conversations = conversations.filterNot { it.id == stored.id } + stored.summary()
        conversations = conversations.sortedWith(
            compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt },
        )
        scope.launch(Dispatchers.IO) {
            ConversationStore.save(stored)
            ConversationStore.saveLastOpenedId(stored.id)
        }
    }

    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            focusManager.clearFocus()
            attachMenuOpen = false
        }
    }

    fun replaceInConversation(
        conversationId: String,
        replyId: Long,
        updated: ChatMessage,
        persist: Boolean = false,
    ) {
        if (clearingHistory) return
        if (currentConversation.id == conversationId) {
            messages = if (messages.any { it.id == replyId }) {
                messages.map { if (it.id == replyId) updated else it }
            } else {
                messages + updated
            }
            if (persist) persistCurrent(messages)
        } else if (persist) {
            val stored = ConversationStore.load(conversationId) ?: return
            val nextMessages = if (stored.messages.any { it.id == replyId }) {
                stored.messages.map { if (it.id == replyId) updated else it }
            } else {
                stored.messages + updated
            }
            val next = stored.copy(
                messages = nextMessages,
                updatedAt = System.currentTimeMillis(),
                // titlePending 期间用截断文本临时显示；已生成的标题不被覆盖
                title = if (stored.titlePending) ConversationStore.titleFrom(nextMessages) else stored.title,
            )
            conversations = conversations.filterNot { it.id == next.id } + next.summary()
            conversations = conversations.sortedWith(
                compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt },
            )
            scope.launch(Dispatchers.IO) {
                ConversationStore.save(next)
            }
        }
    }

    fun addAttachments(files: List<java.io.File>) {
        if (files.isEmpty()) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                files.mapNotNull { file -> copyAttachment(file) }
            }
            val merged = pendingAttachments + loaded
            val slots = attachmentSlots(merged)
            val bytes = attachmentBytes(merged)
            pendingAttachments = when {
                slots > ChatApi.MAX_ATTACHMENT_SLOTS -> pendingAttachments
                bytes > ChatApi.MAX_ATTACHMENT_BYTES_TOTAL -> pendingAttachments
                else -> merged
            }
            composerStatus = when {
                loaded.size < files.size -> tr("有附件无法读取")
                slots > ChatApi.MAX_ATTACHMENT_SLOTS -> tr("附件数量超过 15 个槽位（照片和视频各算半个）")
                bytes > ChatApi.MAX_ATTACHMENT_BYTES_TOTAL -> tr("附件总大小超过 5GB")
                else -> null
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = {
            scope.launch(Dispatchers.IO) {
                val loaded = ProviderStore.loadProviders()
                val active = ProviderStore.loadActiveProviderId()
                val provider = loaded.firstOrNull { it.id == active } ?: loaded.firstOrNull()
                val effort = ProviderStore.loadReasoningEffort()
                val fast = ProviderStore.loadFastMode()
                val merge = ProviderStore.loadMergeModels()
                val searchReady = SearchStore.hasApiKey()
                val searchOn = SearchStore.loadWebSearchEnabled()
                withContext(Dispatchers.Main) {
                    providers = loaded
                    activeProviderId = active
                    selectedModel = provider?.selectedModel.orEmpty()
                    reasoningEffort = effort
                    fastMode = fast
                    mergeModels = merge
                    webSearchAvailable = searchReady
                    webSearchEnabled = searchOn
                }
            }
            Unit
        }
        val conversationsListener = {
            val current = currentConversationState.value
            val currentId = current.id
            val currentUpdatedAt = current.updatedAt
            val streamingThisChat = currentId in generationsSnapshotState.value.values.map { it.conversationId }
            scope.launch(Dispatchers.IO) {
                val loaded = ConversationStore.loadAllSummaries()
                val full = loaded.firstOrNull { it.id == currentId }
                    ?.takeIf { it.updatedAt != currentUpdatedAt && !streamingThisChat }
                    ?.let { ConversationStore.load(it.id) }
                withContext(Dispatchers.Main) {
                    conversations = loaded
                    if (full != null && currentConversation.id == currentId && currentId !in generations.values.map { it.conversationId }) {
                        currentConversation = full
                        messages = full.messages
                    }
                }
            }
            Unit
        }
        val searchListener = {
            webSearchAvailable = SearchStore.hasApiKey()
            webSearchEnabled = SearchStore.loadWebSearchEnabled()
        }
        ProviderStore.addChangeListener(listener)
        ConversationStore.addChangeListener(conversationsListener)
        SearchStore.addChangeListener(searchListener)
        onDispose {
            ProviderStore.removeChangeListener(listener)
            ConversationStore.removeChangeListener(conversationsListener)
            SearchStore.removeChangeListener(searchListener)
        }
    }

    LaunchedEffect(providers, activeProviderId) {
        val activeModels = providers.firstOrNull { it.id == activeProviderId }?.models.orEmpty()
        if (selectedModel !in activeModels) {
            selectedModel = ""
        }
        if (providers.none { it.models.isNotEmpty() }) modelMenuOpen = false
    }

    val inFlight = currentConversation.id in generatingIds
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    LaunchedEffect(activeProviderId, activeProvider?.endpointUrl, currentConversation.id, inFlight) {
        val provider = activeProvider ?: return@LaunchedEffect
        val scopeId = currentConversation.id
        if (EndpointWarmup.shouldWarm(provider, inFlight)) {
            withContext(Dispatchers.IO) { EndpointWarmup.warmBlocking(provider, scopeId) }
        }
    }

    val transcriptFollowJob = remember { object { var job: Job? = null } }
    LaunchedEffect(
        currentConversation.id,
        shownTranscript.size,
        shownTranscript.lastOrNull()?.let(::transcriptTailFollowKey),
        newUserMessageId,
        stickToBottom,
        userDrivingTranscript,
    ) {
        if (currentConversation.id != seenTranscriptConversationId) {
            seenTranscriptConversationId = currentConversation.id
            seenTranscriptTailId = null
            stickToBottom = true
        }
        val last = shownTranscript.lastOrNull()
        if (last == null) {
            transcriptFollowJob.job?.cancel()
            seenTranscriptTailId = null
            stickToBottom = true
            return@LaunchedEffect
        }
        val opening = pendingConversationScroll == currentConversation.id
        // 发送时尾部是待回复占位，不是用户气泡。用 newUserMessageId 识别这次发送。
        val justSent = newUserMessageId != null && newUserMessageId != seenSentUserMessageId
        val scrollToNewMessage = justSent && ThemeStore.newMessageScroll
        val force = opening || scrollToNewMessage || (last.fromUser && last.id != seenTranscriptTailId)
        if (justSent) seenSentUserMessageId = newUserMessageId
        if (opening) pendingConversationScroll = null
        if (!shouldSnapTranscriptToBottom(
                stickToBottom = stickToBottom,
                userScrollInProgress = userDrivingTranscript,
                force = force,
            )
        ) {
            transcriptFollowJob.job?.cancel()
            seenTranscriptTailId = last.id
            return@LaunchedEffect
        }
        if (force) stickToBottom = true
        // 贴底发送要滑过去。新行还没排进屏幕时 scrollToItem 会先锚到视口顶上，列表就抽一下。
        if (justSent && stickToBottom) {
            transcriptFollowJob.job?.cancel()
            transcriptFollowJob.job = scope.launch {
                glideTranscriptToBottom(
                    gate = transcriptScrollGate,
                    follow = {
                        shouldSnapTranscriptToBottom(
                            stickToBottom = stickToBottomState.value,
                            userScrollInProgress = userDrivingTranscriptState.value,
                            force = false,
                        )
                    },
                    remainingPx = { lazyMeasuredTailOverflow(listState) },
                    preparePx = { lazyUnmeasuredTailPrepareStep(listState) },
                    scrollByPx = { step ->
                        listState.scroll { scrollBy(step.toFloat()) }
                    },
                )
            }
            seenTranscriptTailId = last.id
            return@LaunchedEffect
        }
        // 等这一帧把长高的尾部量完再决定滚多少。已经贴住底边就不要滚。
        // 尾部还没进布局时按估算距离逐帧走，不要 scrollToItem：那会先锚到视口顶上再拉回来。
        withFrameNanos { }
        if (!shouldSnapTranscriptToBottom(
                stickToBottom = stickToBottom,
                userScrollInProgress = userDrivingTranscript,
                force = force,
            )
        ) {
            transcriptFollowJob.job?.cancel()
            seenTranscriptTailId = last.id
            return@LaunchedEffect
        }
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
        val catchUp = transcriptBottomCatchUpDelta(
            itemCount = shownTranscript.size,
            lastVisibleIndex = lastVisible?.index ?: -1,
            lastEdge = lastVisible?.let { it.offset + it.size } ?: 0,
            viewportEnd = info.viewportEndOffset,
        )
        if (catchUp == null && transcriptFollowJob.job?.isActive == true) {
            seenTranscriptTailId = last.id
            return@LaunchedEffect
        }
        if ((catchUp == null || catchUp > 0) && transcriptFollowJob.job?.isActive != true) {
            transcriptFollowJob.job?.cancel()
            transcriptFollowJob.job = scope.launch {
                var idleConfirm = false
                var stuck = false
                var unlaidFrames = 0
                while (isActive) {
                    if (!shouldSnapTranscriptToBottom(
                            stickToBottom = stickToBottomState.value,
                            userScrollInProgress = userDrivingTranscriptState.value,
                            force = false,
                        )
                    ) {
                        break
                    }
                    val layout = listState.layoutInfo
                    val visibleLast = layout.visibleItemsInfo.lastOrNull()
                    val remaining = transcriptBottomCatchUpDelta(
                        itemCount = layout.totalItemsCount,
                        lastVisibleIndex = visibleLast?.index ?: -1,
                        lastEdge = visibleLast?.let { it.offset + it.size } ?: 0,
                        viewportEnd = layout.viewportEndOffset,
                    )
                    when {
                        remaining == null -> {
                            if (unlaidFrames++ >= 24) break
                            val step = lazyUnmeasuredTailPrepareStep(listState)
                            if (step <= 0) break
                            var consumed = 0f
                            transcriptScrollGate.depth++
                            try {
                                listState.scroll { consumed = scrollBy(step.toFloat()) }
                            } finally {
                                transcriptScrollGate.depth--
                            }
                            if (consumed == 0f) {
                                if (stuck) break
                                stuck = true
                            } else {
                                stuck = false
                            }
                            withFrameNanos { }
                        }
                        remaining <= 0 -> {
                            if (idleConfirm) break
                            idleConfirm = true
                            withFrameNanos { }
                        }
                        else -> {
                            unlaidFrames = 0
                            idleConfirm = false
                            val step = smoothFollowStep(remaining)
                            var consumed = 0f
                            transcriptScrollGate.depth++
                            try {
                                listState.scroll { consumed = scrollBy(step.toFloat()) }
                            } finally {
                                transcriptScrollGate.depth--
                            }
                            if (consumed == 0f) {
                                if (stuck) break
                                stuck = true
                            } else {
                                stuck = false
                            }
                            withFrameNanos { }
                        }
                    }
                }
            }
        }
        seenTranscriptTailId = last.id
    }

    LaunchedEffect(listState, transcriptScrollGate) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling && transcriptScrollGate.depth == 0) {
                    userDrivingTranscript = false
                }
            }
    }

    LaunchedEffect(Unit) {
        conversations = withContext(Dispatchers.IO) { ConversationStore.loadAllSummaries() }
    }

    fun openConversation(conversation: ConversationSummary) {
        if (conversation.id == currentConversation.id) {
            drawerProgress = 0f
            return
        }
        SpeechPlayer.stop()
        val requestToken = conversationLoadToken + 1
        conversationLoadToken = requestToken
        val previousId = currentConversation.id
        val keepPool = previousId in generatingIds
        // 先关抽屉；正文磁盘读取和 Markdown/TeX 预热都不能阻塞点击反馈。
        drawerProgress = 0f
        scope.launch(Dispatchers.IO) {
            val full = ConversationStore.load(conversation.id) ?: return@launch
            val applied = withContext(Dispatchers.Main) {
                if (conversationLoadToken != requestToken) return@withContext false
                val previous = currentConversation
                val previousMessages = messages
                pendingConversationScroll = full.id
                swapComposer(previous.id, full.id)
                if (previousMessages.isEmpty() && previous.messages.isEmpty()) {
                    composerDrafts.remove(previous.id)
                }
                currentConversation = full
                messages = full.messages
                true
            }
            if (applied) {
                ConversationStore.saveLastOpenedId(conversation.id)
                if (!keepPool) IrioHttp.evictConversation(previousId)
            }
        }
    }

    fun newConversation(closeDrawer: Boolean = true) {
        SpeechPlayer.stop()
        val previous = currentConversation
        val previousMessages = messages
        val keepPool = previous.id in generatingIds
        conversationLoadToken++
        val created = ConversationStore.newConversation()
        swapComposer(previous.id, created.id)
        if (previousMessages.isEmpty() && previous.messages.isEmpty()) {
            composerDrafts.remove(previous.id)
        }
        currentConversation = created
        messages = emptyList()
        // The counter is never reset: message ids are used as keys in [generations] and
        // [replaceInConversation], so restarting at 1 collides with another conversation's ids.
        if (closeDrawer) drawerProgress = 0f
        if (!keepPool) IrioHttp.evictConversation(previous.id)
    }

    fun stopGeneration(conversationId: String = currentConversation.id) {
        // 仅取消网络请求；保留生成协程运行到 finally/收尾逻辑，
        // 这样停止思考时也能把事件闭合并持久化为「已思考 x 秒」。
        generations.values
            .filter { it.conversationId == conversationId }
            .forEach { it.cancellation.cancel() }
    }

    fun clearAllHistory() {
        clearingHistory = true
        generations.values.forEach { it.cancellation.cancel() }
        IrioHttp.evictAllScopes()
        ConversationStore.deleteAll()
        message.clearText()
        pendingAttachments = emptyList()
        editingMessageId = null
        composerDrafts.clear()
        conversations = emptyList()
        newConversation(closeDrawer = false)
        val jobs = generations.values.map { it.job }
        if (jobs.isEmpty()) {
            clearingHistory = false
        } else {
            jobs.forEach { job ->
                job.invokeOnCompletion {
                    if (generations.isEmpty()) clearingHistory = false
                }
            }
        }
    }

    fun togglePinned(conversation: ConversationSummary) {
        val nextPinned = !conversation.pinned
        conversations = conversations
            .map { if (it.id == conversation.id) it.copy(pinned = nextPinned) else it }
            .sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt })
        if (currentConversation.id == conversation.id) {
            currentConversation = currentConversation.copy(pinned = nextPinned)
        }
        scope.launch(Dispatchers.IO) {
            ConversationStore.load(conversation.id)?.let { stored ->
                ConversationStore.save(stored.copy(pinned = nextPinned))
            }
        }
    }

    fun deleteConversation(conversation: ConversationSummary) {
        if (conversation.id in generatingIds) stopGeneration(conversation.id)
        IrioHttp.evictConversation(conversation.id)
        conversations = conversations.filterNot { it.id == conversation.id }
        if (currentConversation.id == conversation.id) {
            newConversation(closeDrawer = false)
        }
        composerDrafts.remove(conversation.id)
        scope.launch(Dispatchers.IO) {
            ConversationStore.delete(conversation.id)
        }
    }

    fun openAttachment(attachment: ChatAttachment) {
        if (attachment.isImage) {
            viewingPhoto = attachment
            return
        }
        runCatching {
            Desktop.getDesktop().open(File(attachment.filePath))
        }
    }

    /**
     * AI 标题生成：只在会话标记了 titlePending（尚未生成过）时跑；手动改过标题的不带标记，不会被覆盖。
     * 模型优先级：设置中的标题模型 → 当前对话模型（非图片）→ 上次对话模型。
     */
    fun maybeGenerateTitle(conversationId: String, userText: String, assistantText: String) {
        if (userText.isBlank() || assistantText.isBlank()) return
        val conversation = conversations.firstOrNull { it.id == conversationId } ?: return
        if (!conversation.titlePending) return
        val configured = ToolStore.loadTitleModel()
        val titleModel = when {
            configured.isNotBlank() -> configured
            !ModelCatalog.isImageModel(selectedModel) -> selectedModel
            else -> ProviderStore.loadLastChatModel()
        }
        if (titleModel.isBlank()) return
        scope.launch {
            val provider = ProviderStore.loadProviders()
                .firstOrNull { titleModel in it.models }
                ?: ProviderStore.loadProvider()
                ?: return@launch
            val title = ChatApi.generateTitle(
                provider,
                titleModel,
                userText,
                assistantText,
                connectionScope = "$conversationId#title",
            )
            if (title.isNullOrBlank()) return@launch
            val summary = conversations.firstOrNull { it.id == conversationId } ?: return@launch
            if (!summary.titlePending) return@launch
            val current = ConversationStore.load(conversationId) ?: return@launch
            val updated = current.copy(title = title, titlePending = false)
            withContext(Dispatchers.Main) {
                conversations = conversations.map { if (it.id == updated.id) updated.summary() else it }
                if (currentConversation.id == updated.id) {
                    currentConversation = currentConversation.copy(title = title, titlePending = false)
                }
            }
            withContext(Dispatchers.IO) {
                ConversationStore.save(updated)
            }
        }
    }

    fun startGeneration(
        requestConversationId: String,
        requestSource: List<ChatMessage>,
        reply: ChatMessage,
    ) {
        SpeechPlayer.stop()
        val requestModel = selectedModel
        val requestEffort = reasoningEffort
        val requestFast = fastMode
        val requestWebSearch = SearchStore.isWebSearchActive()
        val requestSearchProvider = SearchStore.loadProvider()
        val requestSearchKey = SearchStore.loadApiKey(requestSearchProvider)
        val requestSearchMaxResults = SearchStore.loadMaxResults(requestSearchProvider)
        val requestMaxToolRounds = ToolStore.loadMaxRounds()
        // 双重门槛，任一不满足都不注入 generate_image 工具：
        // ① 对话模型本身是生图模型（它自己能画；且图片渠道普遍不支持 tools，注入必报能力错）；
        // ② 设置里存的生图模型必须在目录判定为真生图模型（过滤规则上线前的旧值——
        //    如曾被选成聊天模型——拿去打 /images/generations 一样报能力错，直接视为未配置）。
        val requestImageGenModel = ToolStore.loadImageGenModel()
            .takeIf { ModelCatalog.isImageModel(it) }
            .takeUnless { ModelCatalog.isImageModel(selectedModel) } ?: ""
        val requestSystemPrompt = buildString {
            SystemPromptStore.load().trim().takeIf { it.isNotEmpty() }?.let { append(it) }
            MemoryStore.systemPrompt()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
        }.ifBlank { null }
        val requestRegistry = ToolRegistry(
            buildList {
                if (requestWebSearch) {
                    add(WebSearchToolProvider())
                    add(WebFetchToolProvider())
                }
                if (requestImageGenModel.isNotBlank()) add(ImageGenToolProvider())
                add(TimeToolProvider())
                if (MemoryStore.loadEnabled()) add(MemoryToolProvider())
            }
        )
        val requestCancellation = ChatRequestCancellation()
        val job = scope.launch {
            var appliedFinal = false
            try {
                val provider = ProviderStore.loadProvider()
                var streamedText = ""
                var roundBreakPending = false
                var streamedAttachments = emptyList<ChatAttachment>()
                val process = ProcessTracker()
                var lastPublishAt = 0L
                var publishedBodyLength = 0
                var publishedThinking = false
                var streamedUsage: TokenUsage? = null
                // 对话模型本身是生图模型：聊天请求整条改走 /images/generations
                // （gpt-image 等纯生图模型在 chat/completions 上游必拒），
                // 最后一条用户消息当 prompt，结果图片作为回复附件回流。非流式，过程行只报生成状态。
                val result = if (provider == null) {
                    ChatCompletionResult(error = tr("请先在设置中添加提供商"))
                } else if (ModelCatalog.isImageModel(requestModel)) {
                    val lastUserIndex = requestSource.indexOfLast { it.fromUser && !it.hidden }
                    val prompt = requestSource.getOrNull(lastUserIndex)?.text.orEmpty().ifBlank { tr("图片") }
                    // Images API 无状态：多轮要回传上轮 prompt + 生成图。
                    val imageContext = if (lastUserIndex > 0) {
                        val previousUserPrompt = requestSource.subList(0, lastUserIndex)
                            .lastOrNull { it.fromUser && !it.hidden }?.text.orEmpty()
                        val previousImages = requestSource.subList(0, lastUserIndex)
                            .filter { !it.fromUser }
                            .flatMap { it.attachments.filter { attachment -> attachment.isImage } }
                        if (previousImages.isNotEmpty()) {
                            ProviderApi.ImageGenContext(previousUserPrompt, previousImages.takeLast(3))
                        } else null
                    } else null
                    process.onToolStarted(ImageGenTool.NAME)
                    replaceInConversation(
                        requestConversationId,
                        reply.id,
                        reply.copy(
                            process = process.snapshot(),
                            isPending = false,
                            isStreaming = true,
                        ),
                    )
                    val imageProvider = provider
                    val genResult = withContext(Dispatchers.IO) {
                        ProviderApi.generateImage(
                            imageProvider,
                            requestModel,
                            prompt,
                            imageContext,
                            cancellation = requestCancellation,
                        )
                    }
                    val stopped = requestCancellation.isCancelled() || genResult.error == tr("图片生成已停止")
                    if (!stopped) {
                        if (genResult.attachment != null) process.dropLiveTool()
                        else process.onToolFinished(ImageGenTool.STATUS_FAILED)
                    }
                    when {
                        stopped -> ChatCompletionResult(stopped = true)
                        genResult.attachment != null -> ChatCompletionResult(
                            attachments = listOf(genResult.attachment),
                            model = requestModel,
                        )
                        else -> ChatCompletionResult(error = genResult.error ?: tr("图片生成失败"))
                    }
                } else if (requestModel.isBlank()) {
                    ChatCompletionResult(error = tr("请先选择一个可用模型"))
                } else {
                val requestMessages = modelRequestMessages(requestSource)
                val baseContextTokens = estimateRequestTokens(requestMessages)
                val streamedToolCalls = StringBuilder()
                withContext(Dispatchers.IO) {
                    ChatApi.streamChat(
                        provider = provider,
                        model = requestModel,
                        messages = requestMessages,
                        reasoningEffort = requestEffort,
                        fastMode = requestFast,
                        webSearch = requestWebSearch,
                        searchProvider = requestSearchProvider,
                        searchApiKey = requestSearchKey,
                        searchMaxResults = requestSearchMaxResults,
                        maxToolRounds = requestMaxToolRounds,
                        systemPrompt = requestSystemPrompt,
                        imageGenModel = requestImageGenModel,
                        toolRegistry = requestRegistry,
                        connectionScope = modelConversationScope(requestConversationId, requestSource),
                        cancellation = requestCancellation,
                        onUpdate = update@{ update ->
                            val cancelled = requestCancellation.isCancelled()
                            val terminalState = update.searching == false || update.toolActive == false || update.done
                            if (!cancelled || terminalState || update.attachments.isNotEmpty()) {
                                if (!cancelled) {
                                    if (update.newRound && streamedText.isNotEmpty()) roundBreakPending = true
                                    if (update.thinkingDelta.isNotEmpty()) {
                                        process.onThinkingDelta(update.thinkingDelta)
                                    }
                                    if (update.toolCallDeltas.isNotEmpty()) {
                                        update.toolCallDeltas.forEach { delta ->
                                            if (delta.name.isNotEmpty()) streamedToolCalls.append(delta.name)
                                            if (delta.argumentsDelta.isNotEmpty()) streamedToolCalls.append(delta.argumentsDelta)
                                        }
                                    }
                                    if (update.delta.isNotEmpty()) {
                                        if (roundBreakPending) {
                                            streamedText = appendAssistantRoundBreak(streamedText)
                                            roundBreakPending = false
                                        }
                                        streamedText += update.delta
                                        process.onOutputStarted(textLength = streamedText.length)
                                    }
                                }
                                if (update.attachments.isNotEmpty()) {
                                    streamedAttachments = (streamedAttachments + update.attachments)
                                        .distinctBy { it.filePath }
                                }
                                if (update.usage != null) streamedUsage = update.usage
                                if (update.searching == true && !cancelled) process.onSearchStarted()
                                if (update.searching == false) process.onSearchFinished(update.searchPagesDelta, update.searchContent)
                                if (update.toolActive == true && !cancelled) process.onToolStarted(update.toolName)
                                if (update.toolActive == false) {
                                    if (update.toolName == ImageGenTool.NAME && update.toolContent == ImageGenTool.STATUS_OK) {
                                        process.dropLiveTool()
                                    } else {
                                        process.onToolFinished(update.toolContent)
                                    }
                                }
                                if (update.done) process.finishAll()
                            }
                            // 停止后不再把普通增量绘制到 UI；上面的终止事件仍用于闭合过程状态。
                            if (cancelled) return@update
                            val now = System.currentTimeMillis()
                            val publish = shouldPublishStreamUpdate(
                                nowMs = now,
                                lastPublishAt = lastPublishAt,
                                deltaLength = update.delta.length,
                                thinkingDeltaLength = update.thinkingDelta.length,
                                hasAttachments = update.attachments.isNotEmpty(),
                                done = update.done,
                                searchingChanged = update.searching != null,
                                searchPagesDelta = update.searchPagesDelta,
                                toolChanged = update.toolActive != null,
                                toolCallDeltaLength = update.toolCallDeltas.sumOf { it.name.length + it.argumentsDelta.length },
                                publishedBodyLength = publishedBodyLength,
                                publishedThinking = publishedThinking,
                                hasUsage = update.usage != null,
                            )
                            if (!publish) return@update
                            lastPublishAt = now
                            publishedBodyLength = streamedText.length
                            val snapshot = process.snapshot()
                            publishedThinking = snapshot.any { it.kind == ProcessEventKind.THINKING && it.content.isNotBlank() }
                            val text = streamedText
                            val attachments = streamedAttachments
                            val usage = streamedUsage
                            withContext(Dispatchers.Main.immediate) {
                                if (requestCancellation.isCancelled()) return@withContext
                                replaceInConversation(
                                    requestConversationId,
                                    reply.id,
                                    reply.copy(
                                        text = text,
                                        process = snapshot,
                                        attachments = attachments,
                                        usage = usage,
                                        finishedAt = if (update.done && snapshot.none { it.live }) {
                                            System.currentTimeMillis()
                                        } else {
                                            0L
                                        },
                                        isPending = text.isBlank() && snapshot.none { it.live || it.content.isNotBlank() || it.pages > 0 },
                                        isStreaming = (!update.done && !requestCancellation.isCancelled()) ||
                                            snapshot.any { it.live },
                                    )
                                )
                                if (usage != null && currentConversation.id == requestConversationId) {
                                    currentConversation = currentConversation.copy(tokenUsage = usage)
                                }
                                if (currentConversation.id == requestConversationId) {
                                    liveContextTokens = baseContextTokens + estimateLiveReplyTokens(
                                        streamedText = streamedText,
                                        process = snapshot,
                                        toolCallText = streamedToolCalls.toString(),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            // 确保所有进行中的事件都正确闭合（包括停止时的思考块）
            val now = System.currentTimeMillis()
            process.finishAll(now)
            val snapshot = process.snapshot()
            // A stopped/errored/finished turn must never persist a live event: a row saved with
            // live=true keeps rendering "思考中" forever when the conversation is reopened.
            val closedProcess = snapshot.map { it.copy(live = false) }
            val current = if (currentConversation.id == requestConversationId) {
                messages.firstOrNull { it.id == reply.id }
            } else {
                ConversationStore.load(requestConversationId)?.messages?.firstOrNull { it.id == reply.id }
            }
            val finalProcess = closedProcess
            val finalUsage = result.usage ?: streamedUsage ?: current?.usage
            val finishedAt = current?.finishedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
            val finalMessage = when {
                result.stopped -> {
                    val finalText = streamedText.ifBlank { current?.text.orEmpty() }
                    val finalAttachments = streamedAttachments.ifEmpty { current?.attachments.orEmpty() }
                    if (finalText.isBlank() && finalAttachments.isEmpty() && finalProcess.none { it.content.isNotBlank() || it.millis > 0L || it.pages > 0 }) {
                        null
                    } else {
                        reply.copy(
                            text = finalText,
                            process = finalProcess.map { it.copy(live = false) },
                            attachments = finalAttachments,
                            usage = finalUsage,
                            finishedAt = finishedAt,
                            isPending = false,
                            isStreaming = false,
                        )
                    }
                }
                result.error != null -> reply.copy(
                    text = result.error,
                    process = finalProcess.map { it.copy(live = false) },
                    usage = finalUsage,
                    finishedAt = finishedAt,
                    isError = true,
                    isPending = false,
                    isStreaming = false,
                )
                else -> reply.copy(
                    text = settledAssistantText(streamedText, result.content),
                    process = finalProcess.map { it.copy(live = false) },
                    attachments = result.attachments.ifEmpty { streamedAttachments },
                    usage = finalUsage,
                    finishedAt = finishedAt,
                    isPending = false,
                    isStreaming = false,
                )
            }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (!clearingHistory) {
                    Snapshot.withMutableSnapshot {
                        if (finalMessage == null) {
                            if (currentConversation.id == requestConversationId) {
                                messages = messages.filterNot { it.id == reply.id }
                                persistCurrent(messages)
                            } else {
                                val stored = ConversationStore.load(requestConversationId)
                                if (stored != null) {
                                    ConversationStore.save(
                                        stored.copy(messages = stored.messages.filterNot { it.id == reply.id })
                                    )
                                }
                            }
                        } else {
                            replaceInConversation(requestConversationId, reply.id, finalMessage, persist = true)
                            if (finalMessage.usage != null && currentConversation.id == requestConversationId) {
                                currentConversation = currentConversation.copy(tokenUsage = finalMessage.usage)
                            }
                            // 首轮成功回复后生成 AI 标题（异步，不阻塞 UI）
                            maybeGenerateTitle(
                                conversationId = requestConversationId,
                                userText = requestSource.lastOrNull { it.fromUser && !it.hidden }?.text.orEmpty(),
                                assistantText = finalMessage.text,
                            )
                            if (
                                result.error == null && !result.stopped &&
                                currentConversation.id == requestConversationId &&
                                SpeechStore.loadAutoRead()
                            ) {
                                val spoken = speechPlainText(finalMessage.text)
                                if (spoken.isNotBlank()) SpeechPlayer.speak(finalMessage.id, spoken)
                            }
                        }
                    }
                    appliedFinal = true
                }
                generations = generations - reply.id
            }
            } finally {
                if (!appliedFinal) generations = generations - reply.id
            }
        }
        generations = generations + (reply.id to ActiveGeneration(
            conversationId = requestConversationId,
            job = job,
            cancellation = requestCancellation,
        ))
    }

    /** 点击用户气泡：文本与附件带回输入框进入编辑模式（仅最新一条用户消息）。 */
    fun beginEdit(target: ChatMessage) {
        if (generatingThisChat) return
        if (target.id != visibleMessages(messages).lastOrNull { it.fromUser }?.id) return
        editingMessageId = target.id
        message.edit {
            replace(0, length, target.text)
        }
        pendingAttachments = target.attachments
        composerStatus = null
    }

    /** 退出编辑模式（不保存）。 */
    fun cancelEdit() {
        editingMessageId = null
        message.clearText()
        pendingAttachments = emptyList()
    }


    fun compressionModelId(): String {
        val configured = ToolStore.loadCompressionModel()
        return when {
            configured.isNotBlank() -> configured
            !ModelCatalog.isImageModel(selectedModel) -> selectedModel
            else -> ProviderStore.loadLastChatModel()
        }
    }

    suspend fun runSummary(
        plan: CompressionPlan,
        conversationId: String,
        cancellation: ChatRequestCancellation,
    ): String? {
        val model = compressionModelId()
        if (model.isBlank()) return null
        val provider = ProviderStore.loadProviders()
            .firstOrNull { model in it.models }
            ?: ProviderStore.loadProvider()
            ?: return null
        val window = ModelCatalog.contextWindow(selectedModel)
        val target = ToolStore.loadCompressionTarget()
        val budget = summaryBudgetTokens(window, target)
        return ChatApi.summarizeContext(
            provider = provider,
            model = model,
            systemPrompt = summarySystemPrompt(target, budget),
            messages = summaryTurns(plan),
            connectionScope = "$conversationId#summary",
            cancellation = cancellation,
        )
    }

    fun messagesOf(conversationId: String): List<ChatMessage> {
        return if (currentConversation.id == conversationId) {
            messages
        } else {
            ConversationStore.load(conversationId)?.messages.orEmpty()
        }
    }

    fun finishSummaryCard(
        conversationId: String,
        card: ChatMessage,
        plan: CompressionPlan,
        text: String?,
        cancelled: Boolean,
    ) {
        val updated = if (!cancelled && !text.isNullOrBlank()) {
            card.copy(
                text = stripContextSummary(text),
                summaryState = SummaryState.SUCCESS,
                summaryUntilId = plan.untilId,
                isPending = false,
            )
        } else if (cancelled) {
            // 取消不留失败行。
            card.copy(text = "", summaryState = SummaryState.FAILED, isPending = false, selected = false)
        } else {
            card.copy(text = "", summaryState = SummaryState.FAILED, isPending = false)
        }
        replaceInConversation(conversationId, card.id, updated, persist = true)
    }

    /** 发送前若估算已到触发线，先插一张交接卡片。失败或目录里没有窗口就不挡住这次发送。 */
    fun sendOrCompress(conversationId: String, source: List<ChatMessage>, reply: ChatMessage) {
        val window = ModelCatalog.contextWindow(selectedModel)
        val estimate = estimateRequestTokens(modelRequestMessages(source))
        val compress = !ModelCatalog.isImageModel(selectedModel) &&
            shouldAutoCompress(
                enabled = ToolStore.loadContextCompression(),
                windowTokens = window,
                triggerPercent = ToolStore.loadCompressionTrigger(),
                estimatedTokens = estimate,
            )
        val covered = if (source.lastOrNull()?.fromUser == true) source.dropLast(1) else source
        val plan = if (compress) planCompression(covered) else null
        if (plan == null) {
            startGeneration(conversationId, source, reply)
            return
        }
        val card = ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            summaryState = SummaryState.RUNNING,
            isPending = true,
        )
        if (currentConversation.id == conversationId) {
            messages = insertSummaryCard(messages, plan.untilId, card)
            persistCurrent(messages)
        }
        val cancellation = ChatRequestCancellation()
        val job = scope.launch {
            val text = runSummary(plan, conversationId, cancellation)
            withContext(Dispatchers.Main.immediate) {
                val cancelled = cancellation.isCancelled()
                finishSummaryCard(conversationId, card, plan, text, cancelled)
                generations = generations - card.id
                if (cancelled) {
                    val next = messagesOf(conversationId).filterNot {
                        it.id == reply.id && it.text.isBlank() && it.attachments.isEmpty() && it.process.isEmpty()
                    }
                    if (currentConversation.id == conversationId) {
                        messages = next
                        persistCurrent(messages)
                    }
                    return@withContext
                }
                val nextSource = contextMessages(messagesOf(conversationId))
                    .filterNot { it.id == reply.id || it.isError || it.isPending }
                startGeneration(conversationId, nextSource, reply)
            }
        }
        generations = generations + (card.id to ActiveGeneration(
            conversationId = conversationId,
            job = job,
            cancellation = cancellation,
        ))
    }

    /** 不等触发线，也不接着生成回复。当前可见历史整段压成一道边界。 */
    fun compressNow() {
        if (generatingThisChat) return
        val conversationId = currentConversation.id
        val covered = contextMessages(messages).filterNot { it.isError || it.isPending || it.isStreaming }
        val plan = planCompression(covered) ?: return
        val card = ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            summaryState = SummaryState.RUNNING,
            isPending = true,
        )
        messages = insertSummaryCard(messages, plan.untilId, card)
        persistCurrent(messages)
        val cancellation = ChatRequestCancellation()
        val job = scope.launch {
            val text = runSummary(plan, conversationId, cancellation)
            withContext(Dispatchers.Main.immediate) {
                val cancelled = cancellation.isCancelled()
                finishSummaryCard(conversationId, card, plan, text, cancelled)
                generations = generations - card.id
            }
        }
        generations = generations + (card.id to ActiveGeneration(
            conversationId = conversationId,
            job = job,
            cancellation = cancellation,
        ))
    }

    /**
     * 提交编辑：新版本插回原消息位置（而不是列表尾），旧版本取消选中；
     * 紧随其后的模型回复组取消选中，新 pending 回复进同一个 retryGroup 并启动重生成。
     */
    fun commitEdit() {
        if (generatingThisChat) return
        val targetId = editingMessageId ?: return
        val targetIndex = messages.indexOfFirst { it.id == targetId }
        if (targetIndex < 0) return
        val target = messages[targetIndex]
        val text = message.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        val committed = commitEditedTurn(
            messages = messages,
            target = target,
            targetIndex = targetIndex,
            versionId = MessageIds.next(),
            replyId = MessageIds.next(),
            text = text,
            attachments = pendingAttachments,
        )
        newUserMessageId = committed.history.last().id
        messages = committed.messages
        persistCurrent(messages)
        message.clearText()
        pendingAttachments = emptyList()
        editingMessageId = null
        focusManager.clearFocus()
        sendOrCompress(currentConversation.id, committed.history, committed.reply)
    }

    /**
     * 切换用户消息的编辑版本：用户组切换 + 联动切换它对应的回复组。
     * 配对规则：该版本消息之后的第一条非 error assistant 消息即它当时的回复。
     */
    fun showEditVersion(target: ChatMessage) {
        val targetIndex = messages.indexOfFirst { it.id == target.id }
        val pairedReplyGroupId = replyGroupForUserVersion(messages, target)
        val pairedReply = messages.drop(targetIndex + 1)
            .firstOrNull { !it.fromUser && !it.isError && it.groupId == pairedReplyGroupId }
        messages = selectEditVersion(messages, target)
        if (pairedReply != null) {
            messages = selectRetryVersion(messages, pairedReply)
        }
        persistCurrent(messages, touchUpdatedAt = false)
    }

    fun submitMessage() {
        val text = message.text.toString().trim()
        if (generatingThisChat || (text.isEmpty() && pendingAttachments.isEmpty())) return
        val attachments = pendingAttachments
        val userMessage = ChatMessage(
            id = MessageIds.next(),
            text = text,
            fromUser = true,
            attachments = attachments,
        )
        newUserMessageId = userMessage.id
        val reply = ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            isPending = true,
            responseToUserId = userMessage.id,
        )
        val history = contextMessages(messages).filterNot { it.isError || it.isPending }
        messages = messages + userMessage + reply
        persistCurrent(messages)
        pendingAttachments = emptyList()
        composerStatus = null
        message.clearText()
        sendOrCompress(currentConversation.id, history + userMessage, reply)
        focusManager.clearFocus()
    }

    fun retryMessage(assistant: ChatMessage) {
        if (assistant.fromUser || assistant.isPending || assistant.isStreaming) return
        // Cancel only this message's generation inside the current conversation: the same id
        // may belong to a message in another conversation.
        generations.entries
            .firstOrNull { it.key == assistant.id && it.value.conversationId == currentConversation.id }
            ?.value?.cancellation?.cancel()
        val groupId = assistant.groupId
        val firstIndex = messages.indexOfFirst { !it.fromUser && it.groupId == groupId }
        if (firstIndex <= 0) return
        val history = contextMessages(messages.take(firstIndex)).filterNot { it.isError || it.isPending }
        if (history.isEmpty()) return
        val reply = ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            isPending = true,
            retryGroup = groupId,
            selected = true,
            responseToUserId = assistant.responseToUserId
                ?: messages.take(firstIndex).lastOrNull { it.fromUser }?.id,
        )
        val lastIndex = messages.indexOfLast { !it.fromUser && it.groupId == groupId }
        messages = buildList {
            messages.forEachIndexed { index, message ->
                add(
                    if (!message.fromUser && message.groupId == groupId) {
                        message.copy(selected = false)
                    } else {
                        message
                    },
                )
                if (index == lastIndex) add(reply)
            }
        }
        persistCurrent(messages)
        sendOrCompress(currentConversation.id, history, reply)
    }

    fun showRetryVersion(target: ChatMessage) {
        messages = selectRetryVersion(messages, target)
        persistCurrent(messages, touchUpdatedAt = false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape &&
                    processSheetState.targetDetent != BottomSheetDetent.Hidden
                ) {
                    processSheetState.targetDetent = BottomSheetDetent.Hidden
                    true
                } else {
                    false
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .drawWithContent {
                    drawContent()
                    if (pageDim > 0.001f) {
                        drawRect(Color.Black.copy(alpha = pageDim))
                    }
                },
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(drawerWidth)
                .offset(x = pageOffset - drawerWidth)
                .graphicsLayer {
                    shadowElevation = drawerShadowPx * drawerOpenProgress
                    clip = false
                }
                .zIndex(1f)
                .background(background)
                .drawerSwipe(
                    drawerWidthPx = drawerWidthPx,
                    openDirection = false,
                    allowTapToClose = false,
                    progress = { drawerProgressState.value },
                    onStart = {
                        drawerDragging = true
                        drawerStartProgress = drawerProgressState.value
                    },
                    onDrag = { drawerProgress = it },
                    onSettle = { settleDrawer(drawerProgressState.value) },
                    onTapClose = {},
                )
                .padding(
                    start = 4.dp,
                    top = 8.dp,
                    end = 12.dp,
                )
        ) {
            // 列表在后绘制，且抽屉 graphicsLayer 不裁剪。顶栏必须自带不透明底并压在列表之上，
            // 否则滚出视口的历史行会叠在「新对话」上，再滚一点就被回收、整行消失。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .background(background),
            ) {
                ListRowButton(
                    label = tr("新对话"),
                    icon = Icons.Rounded.Add,
                    style = ButtonStyle.Ghost,
                    contentPadding = DrawerRowPadding,
                    onClick = { newConversation() },
                )
                Spacer(Modifier.height(8.dp))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds(),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 72.dp),
                ) {
                    groupedConversationRows.forEach { (label, items) ->
                        item(key = "header-$label") {
                            Text(
                                label,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                color = muted,
                                fontSize = 12.sp,
                            )
                        }
                        items(items, key = { it.id }) { conversation ->
                            DropdownMenu(
                                expanded = conversationAction?.id == conversation.id,
                                onExpandedChange = { expanded ->
                                    conversationAction = if (expanded) conversation else null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                alignment = DropdownMenuAlignment.Start,
                                panel = {
                                    DropdownMenuPanel(modifier = Modifier.widthIn(min = 140.dp)) {
                                        DropdownMenuItem(
                                            onClick = {
                                                togglePinned(conversation)
                                                conversationAction = null
                                            },
                                        ) {
                                            Text(if (conversation.pinned) tr("取消置顶") else tr("置顶"))
                                        }
                                        DropdownMenuItem(
                                            onClick = {
                                                deleteConversation(conversation)
                                                conversationAction = null
                                            },
                                        ) {
                                            Text(tr("删除对话"))
                                        }
                                    }
                                },
                            ) {
                                ListRowButton(
                                    label = displayConversationTitle(conversation.title),
                                    style = ButtonStyle.Ghost,
                                    contentPadding = DrawerRowPadding,
                                    icon = if (conversation.pinned) Icons.Rounded.PushPin else null,
                                    active = conversation.id == currentConversation.id,
                                    modifier = Modifier.generatingHighlight(conversation.id in generatingIds),
                                    onClick = { openConversation(conversation) },
                                    onLongClick = { conversationAction = conversation },
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .zIndex(1f)
                        .background(background)
                        .padding(bottom = 8.dp),
                ) {
                    ListRowButton(
                        label = tr("设置"),
                        icon = Icons.Rounded.Settings,
                        style = ButtonStyle.Ghost,
                        contentPadding = DrawerRowPadding,
                        onClick = {
                            onOpenSettings()
                        },
                        onLongClick = { clearHistoryDialogOpen = true },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f),
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .nestedScroll(transcriptFollowConnection)
                        .fillMaxSize()
                        .hazeSource(chatHazeState)
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(
                        top = 16.dp + toolbarHeight,
                        bottom = 16.dp + composerBarHeight,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        shownTranscript,
                        key = { it.id },
                        // Distinct structures reuse slots independently while scrolling.
                        contentType = { msg ->
                            when {
                                msg.summaryState != SummaryState.NONE -> "summary"
                                msg.fromUser -> "user"
                                msg.isError -> "error"
                                msg.isStreaming || msg.isPending -> "streaming"
                                else -> "assistant"
                            }
                        },
                    ) { chatMessage ->
                        val versions = if (chatMessage.fromUser) {
                            listOf(chatMessage)
                        } else {
                            retryGroups[chatMessage.groupId].orEmpty()
                        }
                        val versionIndex = versions.indexOfFirst { it.id == chatMessage.id }.coerceAtLeast(0)
                        val editVersions = if (chatMessage.fromUser) editGroups[chatMessage.userGroupId].orEmpty() else emptyList()
                        val editIndex = editVersions.indexOfFirst { it.id == chatMessage.id }.coerceAtLeast(0)
                        MessageRow(
                            chatMessage = chatMessage,
                            newUserMessageId = newUserMessageId,


                            markdownScope = scope,
                            onOpenProcess = { event ->
                                processSheetEvent = event
                                processSheetState.targetDetent = BottomSheetDetent.FullyExpanded
                            },
                            onOpenAttachment = { openAttachment(it) },
                            onCopy = { clipboard.setText(AnnotatedString(chatMessage.text)) },
                            onRetry = { retryMessage(chatMessage) },
                            canRetry = chatMessage.summaryState == SummaryState.NONE &&
                                !chatMessage.fromUser && !chatMessage.isPending && !chatMessage.isStreaming,
                            versionIndex = versionIndex + 1,
                            versionCount = versions.size,
                            onPrevVersion = versions.getOrNull(versionIndex - 1)?.let { previous ->
                                { showRetryVersion(previous) }
                            },
                            onNextVersion = versions.getOrNull(versionIndex + 1)?.let { next ->
                                { showRetryVersion(next) }
                            },
                            isEditing = editingMessageId == chatMessage.id,
                            // 只允许编辑最新一条用户消息：下方不会有其他轮次需要隐藏，重生成路径保持简单正确
                            onEdit = if (
                                chatMessage.fromUser && chatMessage.summaryState == SummaryState.NONE &&
                                !chatMessage.isPending && !chatMessage.isStreaming &&
                                chatMessage.id == visibleTranscript.lastOrNull { it.fromUser && it.summaryState == SummaryState.NONE }?.id
                            ) {
                                { beginEdit(chatMessage) }
                            } else null,
                            editVersionIndex = editIndex + 1,
                            editVersionCount = editVersions.size,
                            onPrevEditVersion = editVersions.getOrNull(editIndex - 1)?.let { previous ->
                                { showEditVersion(previous) }
                            },
                            onNextEditVersion = editVersions.getOrNull(editIndex + 1)?.let { next ->
                                { showEditVersion(next) }
                            },
                            outsideContext = visualizeContext && isBeforeCompression(visibleTranscript, chatMessage.id),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .hazeBlur(
                            input = HazeInput.Sources(chatHazeState),
                            style = chatComposerHazeStyle(),
                        ),
                ) {
                    Spacer(Modifier.height(ChatComposerHazeBleed))
                    Column(
                        modifier = Modifier.onSizeChanged { composerBarHeightPx = it.height },
                    ) {
                if (pendingAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pendingAttachments.forEach { attachment ->
                            PendingAttachmentChip(
                                attachment = attachment,
                                onRemove = {
                                    pendingAttachments = pendingAttachments.filterNot { it == attachment }
                                },
                            )
                        }
                    }
                }

                if (composerStatus != null) {
                    Text(
                        composerStatus!!,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = irioErrorTextColor(),
                        fontSize = 12.sp,
                    )
                }

                // 编辑模式提示条：正在编辑某条用户消息
                if (editingMessageId != null) {
                    val dark = LocalResolvedTheme.current.isDark
                    val blackOnDark = ThemeStore.accent == AccentColor.BLACK && dark
                    val editFill = if (blackOnDark) Theme[colors][onBackgroundColor] else Theme[colors][primaryColor]
                    val editContent = if (blackOnDark) Theme[colors][backgroundColor] else Theme[colors][onPrimaryColor]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .background(
                                color = editFill,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = editContent,
                        )
                        Text(
                            tr("正在编辑消息"),
                            modifier = Modifier.weight(1f),
                            color = editContent,
                            fontSize = 13.sp,
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = tr("取消编辑"),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(onClick = { cancelEdit() }),
                            tint = editContent,
                        )
                    }
                }

                SpeechTransportBar()
                val canSend = (editingMessageId != null && (message.text.isNotBlank() || pendingAttachments.isNotEmpty())) ||
                    (!generatingThisChat && editingMessageId == null && (message.text.isNotBlank() || pendingAttachments.isNotEmpty()))
                ComposerField(
                    state = message,
                    placeholder = "Message",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    accessibilityLabel = "Message",
                    lineLimits = TextFieldLineLimits.MultiLine(
                        minHeightInLines = 1,
                        maxHeightInLines = 4,
                    ),
                    leading = {
                        DropdownMenu(
                            expanded = attachMenuOpen,
                            onExpandedChange = { attachMenuOpen = !generatingThisChat && it },
                            modifier = Modifier.wrapContentWidth(Alignment.Start),
                            alignment = DropdownMenuAlignment.Start,
                            side = DropdownMenuSide.Top,
                            panel = {
                                DropdownMenuPanel {
                                    DropdownMenuItem(
                                        onClick = {
                                            attachMenuOpen = false
                                            addAttachments(FilePickers.pickPhotos())
                                        },
                                        leading = {
                                            Icon(
                                                imageVector = Icons.Rounded.Photo,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                    ) { Text(tr("照片")) }
                                    DropdownMenuItem(
                                        onClick = {
                                            attachMenuOpen = false
                                            addAttachments(FilePickers.pickFiles())
                                        },
                                        leading = {
                                            Icon(
                                                imageVector = Icons.Rounded.AttachFile,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                    ) { Text(tr("文件")) }
                                }
                            },
                        ) {
                            IconButton(
                                onClick = { if (!generatingThisChat) attachMenuOpen = !attachMenuOpen },
                                enabled = !generatingThisChat,
                                style = ButtonStyle.Ghost,
                                buttonSize = ButtonSize.Small,
                                modifier = Modifier
                                    .size(32.dp)
                                    .semantics { contentDescription = tr("添加附件") },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = tr("添加附件"),
                                    modifier = Modifier.size(20.dp),
                                    tint = if (generatingThisChat) muted else content,
                                )
                            }
                        }
                    },
                    trailing = {
                        val sendActive = generatingThisChat || canSend
                        AccentCircleIconButton(
                            onClick = {
                                when {
                                    generatingThisChat -> stopGeneration()
                                    editingMessageId != null -> commitEdit()
                                    else -> submitMessage()
                                }
                            },
                            imageVector = if (generatingThisChat) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                            contentDescription = if (generatingThisChat) tr("停止输出") else "Send message",
                            enabled = sendActive,
                            active = sendActive,
                            iconSize = if (generatingThisChat) 22.dp else 20.dp,
                        )
                    }
                )
                }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .hazeBlur(
                            input = HazeInput.Sources(chatHazeState),
                            style = chatToolbarHazeStyle(),
                        ),
                ) {
                IrioToolbar(
                    modifier = Modifier.onSizeChanged { toolbarHeightPx = it.height },
                    title = { Spacer(Modifier.weight(1f)) },
                    backgroundColor = Color.Transparent,
                    contentColor = content,
                    compact = true,
                    leading = {
                        ToolbarCapsule {
                            IconButton(
                                onClick = { drawerProgress = if (drawerOpen) 0f else 1f },
                                style = ButtonStyle.Ghost,
                                shape = ToolbarCapsuleShape,
                                modifier = Modifier.semantics {
                                    contentDescription = if (drawerOpen) "Close sidebar" else "Open sidebar"
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Menu,
                                    contentDescription = if (drawerOpen) "Close sidebar" else "Open sidebar",
                                    tint = content
                                )
                            }
                        }
                    },
                    trailing = {
                        val showContextRing = ThemeStore.contextRing
                        val contextUsage = currentConversation.tokenUsage
                            ?: messages.lastOrNull { !it.fromUser && it.usage != null }?.usage
                        val apiUsed = contextUsage?.usedTokens ?: 0
                        val compressingThisChat = messages.any { message ->
                            message.summaryState == SummaryState.RUNNING &&
                                generations[message.id]?.conversationId == currentConversation.id
                        }
                        val shownUsed = contextRingUsedTokens(
                            apiUsed = apiUsed,
                            liveEstimate = liveContextTokens,
                            generatingReply = generatingThisChat && !compressingThisChat,
                            compressionEstimate = compressionUsageEstimate(messages),
                        )
                        if (showContextRing) {
                            val showCompression = ToolStore.contextCompressionEnabled
                            val canCompress = showCompression && !generatingThisChat && planCompression(
                                contextMessages(messages).filterNot {
                                    it.isError || it.isPending || it.isStreaming
                                },
                            ) != null
                            ToolbarCapsule {
                                ContextUsageRing(
                                    usedTokens = shownUsed,
                                    windowTokens = ModelCatalog.contextWindow(selectedModel),
                                    compressionCount = compressionCount(messages),
                                    showCompression = showCompression,
                                    compressEnabled = canCompress,
                                    onCompress = ::compressNow,
                                    expanded = contextRingOpen,
                                    onExpandedChange = { contextRingOpen = it },
                                )
                            }
                        }
                        ToolbarCapsule {
                            ModelPicker(
                            selectedModel = selectedModel,
                            providers = providers,
                            activeProviderId = activeProviderId,
                            mergeModels = mergeModels,
                            reasoningEffort = reasoningEffort,
                            fastMode = fastMode,
                            webSearchAvailable = webSearchAvailable,
                            webSearchEnabled = webSearchEnabled,
                            autoRead = autoRead,
                            expanded = modelMenuOpen,
                            onExpandedChange = { modelMenuOpen = it },
                            onSelectedModelChange = { model, providerId ->
                                selectedModel = model
                                // 记录最近一次选中的非图片对话模型（标题生成在图片模型场景的回退目标）
                                if (!ModelCatalog.isImageModel(model)) {
                                    ProviderStore.saveLastChatModel(model)
                                }
                                // 持久化走 IO：persist 逐个 provider 写 providers.json，
                                // 主线程同步执行会造成点击切换模型的可感卡顿。
                                scope.launch(Dispatchers.IO) {
                                    if (providerId != activeProviderId) {
                                        withContext(Dispatchers.Main) { activeProviderId = providerId }
                                        ProviderStore.saveActiveProviderId(providerId)
                                    }
                                    ProviderStore.saveSelectedModel(model, providerId)
                                }
                            },
                            onReasoningChange = { effort ->
                                reasoningEffort = effort
                                ProviderStore.saveReasoningEffort(effort)
                            },
                            onFastModeChange = { enabled ->
                                fastMode = enabled
                                ProviderStore.saveFastMode(enabled)
                            },
                            onWebSearchChange = { enabled ->
                                webSearchEnabled = enabled
                                SearchStore.saveWebSearchEnabled(enabled)
                            },
                            onAutoReadChange = { enabled ->
                                autoRead = enabled
                                SpeechStore.saveAutoRead(enabled)
                            },
                            )
                        }
                    },
                )
                    Spacer(Modifier.height(ChatToolbarHazeBleed))
                }
            }

        }

            if (drawerOpenProgress > 0.01f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
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
                        ),
                )
            }
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
            PhotoViewer(
                attachment = photo,
                onDismiss = { viewingPhoto = null },
            )
        }
        ProcessEventSheet(
            event = processSheetEvent,
            state = processSheetState,
            onDismiss = {
                processSheetState.targetDetent = BottomSheetDetent.Hidden
            },
        )
    }
}

/** 模型列表的预计算行：文本与目标模型在 remember 里一次算好，滚动路径零文本处理。 */
private sealed interface ModelPickerRow {
    val key: String

    data class Gap(override val key: String) : ModelPickerRow
    data class GroupLabel(override val key: String, val text: String) : ModelPickerRow
    data class Model(
        override val key: String,
        val providerId: String,
        val display: String,
        val target: String,
        val variants: List<String>,
    ) : ModelPickerRow
}

@Composable
private fun ModelPicker(
    selectedModel: String,
    providers: List<ProviderConfig>,
    activeProviderId: String,
    mergeModels: Boolean,
    reasoningEffort: ReasoningEffort,
    fastMode: Boolean,
    webSearchAvailable: Boolean,
    webSearchEnabled: Boolean,
    autoRead: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectedModelChange: (model: String, providerId: String) -> Unit,
    onReasoningChange: (ReasoningEffort) -> Unit,
    onFastModeChange: (Boolean) -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
    onAutoReadChange: (Boolean) -> Unit,
) {
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val providersWithModels = providers.filter { it.enabled && it.enabledModels().isNotEmpty() }
    if (providersWithModels.isEmpty()) {
        Button(
            onClick = {},
            enabled = false,
            style = ButtonStyle.Ghost,
            buttonSize = ButtonSize.Small,
            shape = ToolbarCapsuleShape,
            modifier = Modifier.semantics { contentDescription = tr("选择模型") },
        ) {
            Text(
                tr("未配置模型"),
                fontSize = 15.sp,
                lineHeight = 16.sp,
                style = TextStyle(
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.None,
                    ),
                ),
                color = muted,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = tr("选择模型"),
                modifier = Modifier.size(18.dp),
                tint = muted,
            )
        }
        return
    }
    // 每个提供商内部合并 -thinking / -fast 变体；不同提供商之间不合并。
    // 行的显示名 / 目标模型在 remember 里预计算，滚动组合时只剩纯 Text。
    val rows = remember(providersWithModels, mergeModels) {
        buildList {
            providersWithModels.forEachIndexed { providerIndex, provider ->
                if (providerIndex > 0) add(ModelPickerRow.Gap("gap-$providerIndex"))
                add(ModelPickerRow.GroupLabel("label-${provider.id}", provider.name))
                val groups = if (mergeModels) {
                    provider.enabledModels().groupBy { model -> model.removeSuffix("-thinking").removeSuffix("-fast") }
                } else {
                    LinkedHashMap<String, List<String>>().apply {
                        provider.enabledModels().forEach { put(it, listOf(it)) }
                    }
                }
                groups.entries.forEach { (base, variants) ->
                    add(
                        ModelPickerRow.Model(
                            key = "${provider.id}/$base",
                            providerId = provider.id,
                            display = ModelCatalog.displayName(base),
                            target = if (mergeModels) {
                                variants.firstOrNull { it.endsWith("-thinking") }
                                    ?: variants.firstOrNull { it.endsWith("-fast") }
                                    ?: variants.first()
                            } else {
                                base
                            },
                            variants = variants,
                        )
                    )
                }
            }
        }
    }
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.wrapContentWidth(Alignment.End),
        alignment = DropdownMenuAlignment.End,
        panel = {
            Row {
                DropdownMenuPanel(modifier = Modifier.widthIn(min = 112.dp)) {
                    ReasoningEffort.entries.forEach { option ->
                        DropdownMenuItem(
                            onClick = { onReasoningChange(option) },
                            leading = {
                                if (reasoningEffort == option) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Spacer(Modifier.size(16.dp))
                                }
                            },
                        ) {
                            Text(tr(option.label))
                        }
                    }
                    DropdownMenuSeparator()
                    DropdownMenuItem(
                        onClick = { onFastModeChange(!fastMode) },
                        leading = {
                            if (fastMode) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        },
                    ) {
                        Text(tr("快速"))
                    }
                    val webSearchOn = webSearchAvailable && webSearchEnabled
                    DropdownMenuItem(
                        onClick = { onWebSearchChange(!webSearchEnabled) },
                        enabled = webSearchAvailable,
                        leading = {
                            if (webSearchOn) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        },
                    ) {
                        Text(tr("网络搜索"))
                    }
                    DropdownMenuItem(
                        onClick = { onAutoReadChange(!autoRead) },
                        leading = {
                            if (autoRead) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        },
                    ) {
                        Text(tr("自动朗读"))
                    }
                }
                DropdownMenuPanel(modifier = Modifier.width(200.dp)) {
                    // 面板会做 IntrinsicSize.Max，LazyColumn 在展开时直接崩。一棵可滚动 Column 即可。
                    Column(
                        modifier = Modifier
                            .width(200.dp)
                            .heightIn(max = 600.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        rows.forEach { row ->
                            key(row.key) {
                                when (row) {
                                    is ModelPickerRow.Gap -> Spacer(Modifier.height(10.dp))
                                    is ModelPickerRow.GroupLabel -> Text(
                                        // 提供商分组小字：与下方条目文本左对齐，与上一组之间留出空隙
                                        row.text,
                                        modifier = Modifier
                                            .padding(start = 36.dp)
                                            .padding(top = 6.dp, bottom = 2.dp),
                                        color = Theme[colors][mutedColor],
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    is ModelPickerRow.Model -> {
                                        val active = row.providerId == activeProviderId && selectedModel in row.variants
                                        DropdownMenuItem(
                                            onClick = { onSelectedModelChange(row.target, row.providerId) },
                                            leading = {
                                                if (active) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                } else {
                                                    Spacer(Modifier.size(16.dp))
                                                }
                                            },
                                        ) {
                                            Text(row.display)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) {
        Button(
            onClick = { onExpandedChange(!expanded) },
            style = ButtonStyle.Ghost,
            buttonSize = ButtonSize.Small,
            shape = ToolbarCapsuleShape,
            modifier = Modifier.semantics { contentDescription = tr("选择模型") },
        ) {
            Text(
                selectedModel.ifBlank { tr("选择模型") },
                modifier = Modifier.widthIn(max = 180.dp),
                fontSize = 15.sp,
                lineHeight = 16.sp,
                style = TextStyle(
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.None,
                    ),
                ),
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = tr("选择模型"),
                modifier = Modifier.size(18.dp),
                tint = content,
            )
        }
    }
}

@Composable
private fun MessageRow(
    chatMessage: ChatMessage,
    newUserMessageId: Long? = null,


    markdownScope: CoroutineScope,
    onOpenProcess: (ProcessEvent) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    canRetry: Boolean,
    versionIndex: Int = 1,
    versionCount: Int = 1,
    onPrevVersion: (() -> Unit)? = null,
    onNextVersion: (() -> Unit)? = null,
    isEditing: Boolean = false,
    onEdit: (() -> Unit)? = null,
    editVersionIndex: Int = 1,
    editVersionCount: Int = 1,
    onPrevEditVersion: (() -> Unit)? = null,
    onNextEditVersion: (() -> Unit)? = null,
    outsideContext: Boolean = false,
) {
    observeIrioTheme()
    if (chatMessage.summaryState != SummaryState.NONE) {
        CompressionStatusLine(chatMessage.summaryState)
        return
    }
    val spoken = remember(chatMessage.id, chatMessage.text) { speechPlainText(chatMessage.text) }
    val speech = SpeechPlayer.playback
    val isNewUserMessage = chatMessage.fromUser && chatMessage.id == newUserMessageId

    val entranceProgress = remember(chatMessage.id) {
        Animatable(if (isNewUserMessage) 0f else 1f)
    }
    LaunchedEffect(chatMessage.id, isNewUserMessage) {
        if (isNewUserMessage) {
            entranceProgress.animateTo(1f, animationSpec = tween(260, easing = FastOutSlowInEasing))
        }
    }
    val alignment = if (chatMessage.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = (if (isNewUserMessage) entranceProgress.value else 1f) * (if (outsideContext) 0.45f else 1f)

            },
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (chatMessage.fromUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (chatMessage.fromUser) {
                if (chatMessage.attachments.isNotEmpty()) {
                    SentAttachmentRow(
                        attachments = chatMessage.attachments,
                        boxed = true,
                        alignEnd = true,
                        scrollKey = chatMessage.id,
                        onOpen = onOpenAttachment,
                    )
                }
                if (chatMessage.text.isNotBlank()) {
                    val bubble = currentUserBubbleColors(isEditing)
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            chatMessage.text,
                            modifier = Modifier
                                .widthIn(max = maxWidth * 0.85f)
                                .background(
                                    color = bubble.background,
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .then(
                                    if (onEdit != null) {
                                        Modifier.clickable(onClick = onEdit)
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            fontSize = 16.sp,
                            lineHeight = 33.6.sp,
                            color = bubble.content,
                        )
                    }
                }
                // 编辑版本切换器：与模型回复的版本切换同款
                if (editVersionCount > 1) {
                    UserEditVersionSwitcher(
                        versionIndex = editVersionIndex,
                        versionCount = editVersionCount,
                        onPrev = onPrevEditVersion,
                        onNext = onNextEditVersion,
                    )
                }
            } else {
                if (chatMessage.attachments.isNotEmpty()) {
                    SentAttachmentRow(
                        attachments = chatMessage.attachments,
                        boxed = false,
                        alignEnd = false,
                        scrollKey = chatMessage.id,
                        onOpen = onOpenAttachment,
                    )
                }
                if (chatMessage.isError && chatMessage.text.isNotBlank()) {
                    chatMessage.process.forEach { event ->
                        if (!event.belowText) ProcessEventRow(event, onOpen = onOpenProcess)
                    }
                    Box(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            chatMessage.text,
                            color = irioErrorTextColor(),
                            fontSize = 17.sp,
                            lineHeight = 35.7.sp,
                        )
                    }
                    chatMessage.process.forEach { event ->
                        if (event.belowText) ProcessEventRow(event, onOpen = onOpenProcess)
                    }
                } else {
                    // 过程事件锚在它发生时的正文长度上：先说的话、工具、再说的话，完成输出后也不换位。
                    val pieces = interleaveTranscript(chatMessage.text, chatMessage.process)
                    val streamingBody = chatMessage.isStreaming || chatMessage.isPending
                    val lastTextIndex = pieces.indexOfLast { it is TranscriptPiece.Text }
                    var seenText = false
                    pieces.forEachIndexed { index, piece ->
                        when (piece) {
                            is TranscriptPiece.Event -> key("process-${piece.event.id}") {
                                ProcessEventRow(piece.event, onOpen = onOpenProcess)
                            }
                            is TranscriptPiece.Text -> {
                                val shown = if (seenText) piece.text.trimStart('\n') else piece.text
                                seenText = true
                                if (shown.isNotBlank()) {
                                    key("body-$index") {
                                        Box(Modifier.padding(vertical = 2.dp)) {
                                            AssistantMarkdown(
                                                text = shown,
                                                streaming = streamingBody && index == lastTextIndex,
                                                renderScope = markdownScope,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if ((chatMessage.isPending || chatMessage.isStreaming) &&
                        chatMessage.text.isBlank() &&
                        chatMessage.process.none { it.live }
                    ) {
                        StatusSweepText(tr("请求中"))
                    }
                }
                // Thinking/tool rounds can end with no body text at all: keep the action row outside
                // the `when`, or such a message never gets copy/retry.
                if (!chatMessage.fromUser && !chatMessage.isPending && !chatMessage.isStreaming &&
                    editVersionCount <= 1
                ) {
                    AssistantActions(
                        onCopy = onCopy,
                        onSpeak = if (spoken.isNotBlank()) {
                            { SpeechPlayer.speak(chatMessage.id, spoken) }
                        } else {
                            null
                        },
                        speaking = speech.active && speech.messageId == chatMessage.id,
                        onRetry = onRetry,
                        canRetry = canRetry,
                        finishedAt = chatMessage.finishedAt,
                        versionIndex = versionIndex,
                        versionCount = versionCount,
                        onPrevVersion = onPrevVersion,
                        onNextVersion = onNextVersion,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompressionStatusLine(state: SummaryState) {
    val raw = compressionStatusLabel(state) ?: return
    val label = tr(raw)
    if (state == SummaryState.RUNNING) {
        StatusSweepText(label)
    } else {
        Text(
            label,
            color = Theme[colors][mutedColor],
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun StatusSweepText(label: String) {
    val highlight = Theme[colors][onBackgroundColor]
    val base = Theme[colors][mutedColor]
    val shift by rememberInfiniteTransition(label = "statusSweep").animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "statusSweepShift",
    )
    Text(
        label,
        fontSize = 13.sp,
        color = base,
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val width = size.width
                val start = width * shift
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, highlight.copy(alpha = 0.7f), Color.Transparent),
                        start = Offset(start - width * 0.25f, 0f),
                        end = Offset(start + width * 0.25f, 0f),
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
    )
}

@Composable
private fun ProcessEventRow(
    event: ProcessEvent,
    onOpen: (ProcessEvent) -> Unit,
) {
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
    val openable = event.toolName != ImageGenTool.NAME && !event.live && event.content.isNotBlank()
    val label = when (event.kind) {
        ProcessEventKind.THINKING -> if (event.live) null else tr("已思考 %s").format(formatThinkingDuration(event.millis))
        ProcessEventKind.SEARCH -> if (event.live) null else tr("搜索了 %s 个网页").format(event.pages)
        ProcessEventKind.TOOL -> if (event.live) null else MemoryTool.doneLabel(event.toolName, event.content)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (event.live) {
            StatusSweepText(
                when (event.kind) {
                    ProcessEventKind.THINKING -> tr("思考中")
                    ProcessEventKind.SEARCH -> tr("搜索中")
                    ProcessEventKind.TOOL -> MemoryTool.liveLabel(event.toolName)
                }
            )
            if (event.kind == ProcessEventKind.THINKING && event.content.isNotBlank()) {
                val thinkingScrollState = rememberScrollState()
                val thinkingFollowGate = remember { TranscriptScrollGate() }
                val followThinkingBottom = remember { mutableStateOf(true) }
                val thinkingFollowConnection = remember(thinkingScrollState, thinkingFollowGate) {
                    object : NestedScrollConnection {
                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            if (thinkingFollowGate.depth == 0 && source == NestedScrollSource.UserInput) {
                                followThinkingBottom.value =
                                    thinkingScrollState.value >= thinkingScrollState.maxValue - 48
                            }
                            return Offset.Zero
                        }

                        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                            if (thinkingFollowGate.depth == 0) {
                                followThinkingBottom.value =
                                    thinkingScrollState.value >= thinkingScrollState.maxValue - 48
                            }
                            return Velocity.Zero
                        }
                    }
                }
                LaunchedEffect(followThinkingBottom.value) {
                    if (!followThinkingBottom.value) return@LaunchedEffect
                    thinkingScrollState.smoothFollowBottom(thinkingFollowGate) { followThinkingBottom.value }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .nestedScroll(thinkingFollowConnection)
                        .verticalScroll(thinkingScrollState),
                ) {
                    Text(
                        event.content,
                        color = muted,
                        fontSize = 15.sp,
                    )
                }
            }
        } else if (label != null) {
            Row(
                modifier = Modifier
                    .padding(bottom = 2.dp)
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
                Text(
                    label,
                    color = muted,
                    fontSize = 13.sp,
                )
                if (openable) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
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
private fun ProcessEventSheet(
    event: ProcessEvent?,
    state: BottomSheetState,
    onDismiss: () -> Unit,
) {
    val content = Theme[colors][onPanelColor]
    BottomSheet(
        state = state,
        onDismissRequest = onDismiss,
        contentColor = content,
        contentPadding = PaddingValues(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 8.dp),
    ) {
        val minSheetHeight = with(LocalDensity.current) {
            (LocalWindowInfo.current.containerSize.height * 0.45f).toDp()
        }
        val maxSheetHeight = with(LocalDensity.current) {
            (LocalWindowInfo.current.containerSize.height * 0.90f).toDp()
        }
        val body = event?.content.orEmpty()
        val chunks = remember(body) { splitSheetChunks(body) }
        val longContent = body.length > 800 || chunks.size > 1
        if (longContent) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minSheetHeight, max = maxSheetHeight),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(chunks.size) { index ->
                    Text(chunks[index], fontSize = 16.sp, color = content)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minSheetHeight, max = maxSheetHeight),
            ) {
                if (body.isNotBlank()) {
                    Text(body, fontSize = 16.sp, color = content)
                }
            }
        }
    }
}

@Composable
private fun SentAttachmentRow(
    attachments: List<ChatAttachment>,
    boxed: Boolean,
    alignEnd: Boolean,
    scrollKey: Any,
    onOpen: (ChatAttachment) -> Unit,
) {
    // 行宽随内容，短于视口时仍由消息列贴右或贴左。超出后才横向滚动。
    // 用户消息反向滚动，0 停在末尾，条带继续贴着气泡一侧。
    key(scrollKey) {
        Row(
            modifier = Modifier.horizontalScroll(
                rememberScrollState(),
                reverseScrolling = alignEnd,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            attachments.forEach { attachment ->
                AttachmentPreview(
                    attachment,
                    boxed = boxed,
                    onOpen = { onOpen(attachment) },
                )
            }
        }
    }
}

@Composable
private fun PendingAttachmentChip(
    attachment: ChatAttachment,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Theme[colors][panelColor])
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AttachmentPreview(attachment, boxed = false, compact = true, onOpen = null)
        IconButton(
            onClick = onRemove,
            style = ButtonStyle.Ghost,
            buttonSize = ButtonSize.Small,
            modifier = Modifier
                .size(28.dp)
                .semantics { contentDescription = tr("移除附件") },
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = tr("移除附件"),
                modifier = Modifier.size(16.dp),
                tint = Theme[colors][mutedColor],
            )
        }
    }
}

internal object AttachmentPreviewCache {
    private const val MAX_ENTRIES = 24
    private val map = LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true)

    fun get(path: String): ImageBitmap? = synchronized(map) { map[path] }

    fun getOrDecode(path: String, decode: () -> ImageBitmap?): ImageBitmap? {
        synchronized(map) { map[path]?.let { return it } }
        val bitmap = decode() ?: return null
        synchronized(map) {
            map[path] = bitmap
            while (map.size > MAX_ENTRIES) {
                val eldest = map.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
        }
        return bitmap
    }
}

@Composable
private fun AttachmentPreview(
    attachment: ChatAttachment,
    boxed: Boolean,
    compact: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    // Decode off the main thread: scrolled-off items are disposed, and a
    // synchronous `remember { decodePreview() }` re-decoded on every scroll-back.
    var preview by remember(attachment.filePath) {
        mutableStateOf(
            if (attachment.isImage) AttachmentPreviewCache.get(attachment.filePath) else null,
        )
    }
    if (attachment.isImage && preview == null) {
        LaunchedEffect(attachment.filePath) {
            preview = withContext(Dispatchers.IO) {
                AttachmentPreviewCache.getOrDecode(attachment.filePath) {
                    decodePreview(attachment.filePath)
                }
            }
        }
    }
    val clickable = if (onOpen != null && !compact) Modifier.clickable(onClick = onOpen) else Modifier
    val rendered = preview
    if (rendered != null) {
        val previewSize = attachmentPreviewSize(
            rendered.width, rendered.height, LocalDensity.current.density, compact,
        )
        Image(
            bitmap = rendered,
            contentDescription = attachment.name,
            modifier = Modifier
                .size(previewSize.width, previewSize.height)
                .clip(RoundedCornerShape(12.dp))
                .then(clickable),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(
            attachment.name,
            fontSize = 12.sp,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 160.dp)
                .then(
                    if (boxed || compact) {
                        Modifier
                            .background(
                                color = Theme[colors][panelColor],
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    } else {
                        Modifier
                    },
                )
                .then(clickable),
        )
    }
}

private fun decodePreview(path: String): androidx.compose.ui.graphics.ImageBitmap? {
    return runCatching {
        File(path).inputStream().buffered().use { androidx.compose.ui.res.loadImageBitmap(it) }
    }.getOrNull()
}

private fun mergeProcess(current: List<ProcessEvent>, snapshot: List<ProcessEvent>): List<ProcessEvent> {
    if (snapshot.isEmpty()) return current.map { it.copy(live = false) }
    if (current.isEmpty()) return snapshot
    val byId = current.associateBy { it.id }
    return snapshot.map { event ->
        val previous = byId[event.id]
        if (previous == null) {
            event
        } else {
            event.copy(
                content = event.content.ifBlank { previous.content },
                millis = maxOf(event.millis, previous.millis),
                pages = maxOf(event.pages, previous.pages),
            )
        }
    }
}

private fun groupedConversations(conversations: List<ConversationSummary>): List<Pair<String, List<ConversationSummary>>> {
    val pinned = conversations.filter { it.pinned }
    val rest = conversations.filterNot { it.pinned }
    return buildList {
        if (pinned.isNotEmpty()) add(tr("置顶") to pinned)
        addAll(rest.groupBy { conversationDateLabel(it.updatedAt) }.toList())
    }
}

private fun conversationDateLabel(updatedAt: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = updatedAt }
    val today = now.clone() as Calendar
    today.set(Calendar.HOUR_OF_DAY, 0)
    today.set(Calendar.MINUTE, 0)
    today.set(Calendar.SECOND, 0)
    today.set(Calendar.MILLISECOND, 0)
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        then.timeInMillis >= today.timeInMillis -> tr("今天")
        then.timeInMillis >= yesterday.timeInMillis -> tr("昨天")
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            if (ThemeStore.language == AppLanguage.EN) SimpleDateFormat("MMM d", Locale.ENGLISH).format(Date(updatedAt)) else SimpleDateFormat("M月d日", Locale.CHINA).format(Date(updatedAt))
        else -> if (ThemeStore.language == AppLanguage.EN) SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(Date(updatedAt)) else SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(updatedAt))
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

private class PreParsedMarkdownState(private val parsed: State.Success) : MarkdownState {
    override val state: StateFlow<State> = MutableStateFlow(parsed)
    override val links: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    override suspend fun parse(): State = parsed
}

/**
 * Parsed Markdown states for finished assistant messages, computed once per
 * message segment above the LazyColumn. Lazy items are disposed when scrolled
 * off-screen; without this pool every scroll-back re-parses on the main thread.
 * Only finished messages are pooled (streaming text keeps changing, and error /
 * user bubbles never go through Markdown). A pool miss simply falls back to
 * parsing inline, so correctness never depends on the cache.
 */
internal object FinishedMarkdownCache {
    private const val MAX_ENTRIES = 128
    private val map = object : LinkedHashMap<String, MutableState<MarkdownState?>>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MutableState<MarkdownState?>>): Boolean {
            return size > MAX_ENTRIES
        }
    }

    private val parsed = SingleFlightCache<String, State.Success>()

    fun slot(content: String): MutableState<MarkdownState?> =
        synchronized(map) { map.getOrPut(content) { mutableStateOf(null) } }

    fun request(content: String, scope: CoroutineScope, dispatcher: CoroutineDispatcher): MutableState<MarkdownState?> {
        val slot = slot(content)
        val result = parsed.request(content, scope, dispatcher) {
            parseMarkdown(content) as? State.Success ?: error("Markdown parse failed")
        }
        when {
            result.status == AsyncCacheStatus.READY && slot.value == null -> {
                slot.value = result.value?.let(::PreParsedMarkdownState)
            }
            result.status == AsyncCacheStatus.PENDING && result.completion != null -> {
                scope.launch {
                    val value = result.completion.await()
                    if (value != null) slot.value = PreParsedMarkdownState(value)
                }
            }
        }
        return slot
    }

    suspend fun prepare(content: String, scope: CoroutineScope) {
        val result = parsed.request(content, scope, Dispatchers.Default) {
            parseMarkdown(content) as? State.Success ?: error("Markdown parse failed")
        }
        result.completion?.await()
    }
}

internal object FinishedMarkdownSegmentsCache {
    private const val MAX_ENTRIES = 128
    private val map = object : LinkedHashMap<String, MutableState<List<MarkdownSegment>?>>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MutableState<List<MarkdownSegment>?>>): Boolean {
            return size > MAX_ENTRIES
        }
    }
    private val parsed = SingleFlightCache<String, List<MarkdownSegment>>()

    fun request(content: String, scope: CoroutineScope): MutableState<List<MarkdownSegment>?> {
        val slot = synchronized(map) { map.getOrPut(content) { mutableStateOf(null) } }
        val result = parsed.request(content, scope, Dispatchers.Default) {
            markdownSegments(content)
        }
        when {
            result.status == AsyncCacheStatus.READY && slot.value == null -> slot.value = result.value
            result.status == AsyncCacheStatus.PENDING && result.completion != null -> {
                scope.launch { result.completion.await()?.let { slot.value = it } }
            }
        }
        return slot
    }

    suspend fun prepare(content: String, scope: CoroutineScope): List<MarkdownSegment>? {
        val result = parsed.request(content, scope, Dispatchers.Default) {
            markdownSegments(content)
        }
        return result.value ?: result.completion?.await()
    }
}

internal data class TexCacheKey(
    val latex: String,
    val colorArgb: Int,
    val displayStyle: Boolean,
    val pxPerPt: Float,
    val texScale: Float,
)

/**
 * Process-wide LRU for JLatexMath rasters. Formula layout + raster costs far more
 * than Markdown parsing, and lazy-item disposal discards the per-item `remember`
 * on every scroll-off. Survives scrolling and conversation switches.
 */
internal object TexBitmapCache {
    private val cache = SingleFlightCache<TexCacheKey, ImageBitmap>(maxEntries = 64)

    fun request(key: TexCacheKey, scope: CoroutineScope, render: () -> ImageBitmap?): AsyncCacheResult<ImageBitmap> =
        cache.request(key, scope, Dispatchers.Default) {
            render() ?: error("TeX rasterization failed")
        }

    fun get(key: TexCacheKey): ImageBitmap? = cache.peek(key)?.value

    suspend fun prepare(key: TexCacheKey, scope: CoroutineScope, render: () -> ImageBitmap?) {
        val result = request(key, scope, render)
        result.completion?.await()
    }
}

private suspend fun prepareFinishedMessage(
    message: ChatMessage,
    contentArgb: Int,
    pxPerPt: Float,
    texScale: Float,
    scope: CoroutineScope,
) {
    if (message.summaryState != SummaryState.NONE || message.fromUser || message.isError || message.isPending || message.isStreaming || message.text.isBlank()) return
    val segments = FinishedMarkdownSegmentsCache.prepare(message.text, scope) ?: return
    segments.filterIsInstance<MarkdownSegment.Text_>()
        .filter { it.markdown }
        .map { it.text }
        .distinct()
        .forEach { FinishedMarkdownCache.prepare(it, scope) }
    val formulas = segments.filterIsInstance<MarkdownSegment.TexBlock>().map { it.latex to true } +
        segments.filterIsInstance<MarkdownSegment.Text_>()
            .flatMap { segment -> inlineTexContents(segment.text).map { it to false } }
    formulas.distinct().forEach { (latex, displayStyle) ->
        val key = TexCacheKey(latex, contentArgb, displayStyle, pxPerPt, texScale)
        TexBitmapCache.prepare(key, scope) {
            renderTexImage(latex, contentArgb, displayStyle, pxPerPt, texScale)
        }
    }
}

@Composable
private fun AssistantMarkdown(
    text: String,
    streaming: Boolean,
    renderScope: CoroutineScope,
) {
    val segments = remember(text) { markdownSegments(text) }
    val markdownSlots = remember(text, segments, streaming) {
        if (streaming) {
            emptyMap()
        } else {
            segments
                .filterIsInstance<MarkdownSegment.Text_>()
                .filter { it.markdown }
                .distinctBy { it.text }
                .associate { segment ->
                    segment.text to FinishedMarkdownCache.request(segment.text, renderScope, Dispatchers.Default)
                }
        }
    }
    observeIrioTheme()
    val contentColor = Theme[colors][onBackgroundColor]
    var texScale by remember { mutableStateOf(ProviderStore.loadTexScale()) }
    DisposableEffect(Unit) {
        val listener: () -> Unit = { texScale = ProviderStore.loadTexScale() }
        ProviderStore.addChangeListener(listener)
        onDispose { ProviderStore.removeChangeListener(listener) }
    }
    val bodyStyle = TextStyle(fontSize = 17.sp, lineHeight = 35.7.sp, color = contentColor)
    val inlineCode = irioInlineCodeStyle()
    val codeBlock = irioCodeBlockStyle()
    val typography = markdownTypography(
        h1 = TextStyle(fontSize = 24.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = contentColor),
        h2 = TextStyle(fontSize = 22.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = contentColor),
        h3 = TextStyle(fontSize = 20.sp, lineHeight = 35.7.sp, fontWeight = FontWeight.Bold, color = contentColor),
        h4 = TextStyle(fontSize = 18.sp, lineHeight = 35.7.sp, fontWeight = FontWeight.Bold, color = contentColor),
        h5 = TextStyle(fontSize = 17.sp, lineHeight = 35.7.sp, fontWeight = FontWeight.Bold, color = contentColor),
        h6 = TextStyle(fontSize = 17.sp, lineHeight = 35.7.sp, fontWeight = FontWeight.Medium, color = contentColor),
        text = bodyStyle,
        paragraph = bodyStyle,
        quote = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        table = bodyStyle,
        code = codeBlock,
        inlineCode = inlineCode,
    )
    val texTransformer = remember(contentColor, texScale) {
        TexImageTransformer(contentColor.toArgb(), texScale, renderScope)
    }
    val imageTransformer = remember(texTransformer) { IrioImageTransformer(texTransformer) }
    ProvideTextStyle(bodyStyle) {
        Column {
            segments.forEach { segment ->
                key(segment.key) {
                    when (segment) {
                        is MarkdownSegment.TexBlock -> TexBlock(
                            latex = segment.latex,
                            colorArgb = contentColor.toArgb(),
                            texScale = texScale,
                            renderScope = renderScope,
                        )
                        is MarkdownSegment.Text_ -> {
                            val markdownState = markdownSlots[segment.text]?.value
                            IrioMarkdown(
                                content = segment.text,
                                state = markdownState,
                                typography = typography,
                                imageTransformer = imageTransformer,
                                glyphFade = streaming && ThemeStore.glyphFade,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Block-level `$$…$$` / `\[…\]` formula rendered with JLatexMath. */
@Composable
private fun TexBlock(latex: String, colorArgb: Int, texScale: Float, renderScope: CoroutineScope) {
    val density = LocalDensity.current
    val pxPerPt = density.density
    val cacheKey = TexCacheKey(latex, colorArgb, displayStyle = true, pxPerPt, texScale)
    // Cache hit is drawn immediately; a miss renders off the main thread so a
    // formula entering the viewport never blocks the scroll frame.
    var bitmap by remember(cacheKey) { mutableStateOf(TexBitmapCache.get(cacheKey)) }
    if (bitmap == null) {
        LaunchedEffect(cacheKey) {
            val result = TexBitmapCache.request(cacheKey, renderScope) {
                renderTexImage(latex, colorArgb, displayStyle = true, pxPerPt = pxPerPt, texScale = texScale)
            }
            bitmap = result.value ?: result.completion?.await()
        }
    }
    val rendered = bitmap
    if (rendered != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Image(
                bitmap = rendered,
                contentDescription = null,
                // Paint the bitmap back down to dp units so it displays at the
                // intended physical size on any density.
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .size(
                        width = with(density) { rendered.width.toDp() },
                        height = with(density) { rendered.height.toDp() },
                    ),
            )
        }
    } else {
        // Parse failure: keep the raw LaTeX visible.
        Text(latex, fontSize = 17.sp, lineHeight = 35.7.sp, color = Theme[colors][onBackgroundColor])
    }
}

private class TexImageTransformer(
    private val contentArgb: Int,
    private val texScale: Float,
    private val renderScope: CoroutineScope,
) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        if (!link.startsWith(TEX_LINK_PREFIX)) return null
        val latex = link.removePrefix(TEX_LINK_PREFIX)
        val density = LocalDensity.current
        val pxPerPt = density.density
        val cacheKey = TexCacheKey(latex, contentArgb, displayStyle = false, pxPerPt, texScale)
        // Same contract as TexBlock: cache hit is immediate, a miss renders on
        // IO and reports its real size via imageSizeChanged when ready.
        var bitmap by remember(cacheKey) { mutableStateOf(TexBitmapCache.get(cacheKey)) }
        if (bitmap == null) {
            LaunchedEffect(cacheKey) {
                val result = TexBitmapCache.request(cacheKey, renderScope) {
                    renderTexImage(
                        latex, contentArgb, displayStyle = false,
                        pxPerPt = pxPerPt, texScale = texScale,
                    )
                }
                bitmap = result.value ?: result.completion?.await()
            }
        }
        val rendered = bitmap
        return ImageData(
            painter = rendered?.let(::BitmapPainter) ?: ColorPainter(Color.Transparent),
            modifier = Modifier,
            contentDescription = null,
            alignment = Alignment.CenterStart,
            contentScale = ContentScale.Fit,
            alpha = 1f,
            colorFilter = null,
        )
    }

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((String, Size) -> Unit)?,
    ): PlaceholderConfig {
        val size = if (link.startsWith(TEX_LINK_PREFIX)) {
            // imageSize is in px (painter intrinsic); PlaceholderConfig.size is dp.
            with(density) {
                Size(imageSize.width.toDp().value, imageSize.height.toDp().value)
            }
        } else {
            super.placeholderConfig(link, density, containerSize, imageWidth, imageSize, imageSizeChanged)
                .size
        }
        return PlaceholderConfig(size, PlaceholderVerticalAlign.Bottom)
    }
}

/**
 * CJK (and other non-TeX unicode) is painted by JavaFontRenderingBox from a
 * 10pt/px font and never multiplied by [TeXFormula.PIXELS_PER_POINT].
 * Latin math metrics are. Wrap CJK runs in \scalebox so they match density.
 */
private fun scaleCjkForDensity(latex: String, pxPerPt: Float): String {
    if (pxPerPt <= 1.01f || latex.isEmpty()) return latex
    val scale = formatTexScale(pxPerPt)
    val out = StringBuilder(latex.length + 16)
    var index = 0
    while (index < latex.length) {
        val code = latex.codePointAt(index)
        val width = Character.charCount(code)
        if (isTexCjkCode(code)) {
            val start = index
            index += width
            while (index < latex.length) {
                val next = latex.codePointAt(index)
                if (!isTexCjkCode(next)) break
                index += Character.charCount(next)
            }
            out.append("\\scalebox{").append(scale).append("}{")
            out.append(latex, start, index)
            out.append('}')
        } else {
            out.appendCodePoint(code)
            index += width
        }
    }
    return out.toString()
}

private fun formatTexScale(value: Float): String {
    val rounded = (value * 1000f).toInt() / 1000f
    return if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

private fun isTexCjkCode(code: Int): Boolean {
    val block = Character.UnicodeBlock.of(code) ?: return false
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
        block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
        block == Character.UnicodeBlock.HANGUL_JAMO ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
}

/** Render LaTeX to an ImageBitmap; null on parse failure. */
private val texRenderLock = Any()

private fun renderTexImage(
    latex: String,
    colorArgb: Int,
    displayStyle: Boolean,
    pxPerPt: Float,
    texScale: Float,
): ImageBitmap? = synchronized(texRenderLock) {
    try {
        val formula = TeXFormula(scaleCjkForDensity(latex, pxPerPt))
        val style = if (displayStyle) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT
        TeXFormula.PIXELS_PER_POINT = pxPerPt
        val baseSize = if (displayStyle) 20f else 17f
        val icon = formula.createTeXIcon(style, baseSize * texScale)
        icon.setForeground(AwtColor(colorArgb))
        val width = icon.iconWidth
        val height = icon.iconHeight
        if (width <= 0 || height <= 0) return null
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics: Graphics2D = image.createGraphics()
        try {
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        image.toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}


private fun Modifier.drawerSwipe(
    drawerWidthPx: Float,
    openDirection: Boolean,
    allowTapToClose: Boolean,
    progress: () -> Float,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onSettle: () -> Unit,
    onTapClose: () -> Unit,
): Modifier = pointerInput(drawerWidthPx, openDirection, allowTapToClose) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var totalX = 0f
        var totalY = 0f
        var dragging = false
        var passedSlop = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (dragging) onSettle()
                else if (allowTapToClose && !passedSlop) onTapClose()
                break
            }
            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y
            if (!passedSlop && (kotlin.math.abs(totalX) > touchSlop || kotlin.math.abs(totalY) > touchSlop)) {
                passedSlop = true
                val horizontal = kotlin.math.abs(totalX) > kotlin.math.abs(totalY)
                val matching = if (openDirection) totalX > 0f else totalX < 0f
                if (horizontal && matching) {
                    dragging = true
                    onStart()
                    change.consume()
                    onDrag((progress() + totalX / drawerWidthPx).coerceIn(0f, 1f))
                }
            } else if (dragging) {
                change.consume()
                onDrag((progress() + delta.x / drawerWidthPx).coerceIn(0f, 1f))
            }
        }
    }
}

private val DrawerRowPadding = PaddingValues(start = 16.dp, end = 12.dp)

private data class ActiveGeneration(
    val conversationId: String,
    val job: Job,
    val cancellation: ChatRequestCancellation,
)

private fun Modifier.generatingHighlight(active: Boolean): Modifier = composed {
    if (!active) return@composed this
    val highlight = Theme[colors][onBackgroundColor]
    val progress by rememberInfiniteTransition(label = "generatingSweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "generatingSweepProgress",
    )
    drawWithContent {
        drawContent()
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2f
        val radius = 12.dp.toPx()
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = inset,
                    top = inset,
                    right = size.width - inset,
                    bottom = size.height - inset,
                    radiusX = radius,
                    radiusY = radius,
                )
            )
        }
        val measure = PathMeasure()
        measure.setPath(path, true)
        val length = measure.length
        if (length <= 0f) return@drawWithContent
        val arc = length * 0.5f
        val head = progress * length
        val tail = (head - arc + length) % length
        val segment = Path()
        if (head >= arc) {
            measure.getSegment(head - arc, head, segment, true)
        } else {
            measure.getSegment(tail, length, segment, true)
            measure.getSegment(0f, head, segment, false)
        }
        drawPath(
            path = segment,
            brush = Brush.linearGradient(
                0f to highlight.copy(alpha = 0f),
                0.35f to highlight.copy(alpha = 0.35f),
                1f to highlight,
                start = measure.getPosition((head - arc + length) % length),
                end = measure.getPosition(head),
            ),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** 用户消息编辑版本的切换器：与模型回复版本切换器同款式。 */
@Composable
private fun UserEditVersionSwitcher(
    versionIndex: Int,
    versionCount: Int,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val muted = Theme[colors][mutedColor]
    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = tr("上一版本"),
            modifier = Modifier
                .size(16.dp)
                .clickable(enabled = onPrev != null, onClick = { onPrev?.invoke() }),
            tint = muted.copy(alpha = if (onPrev != null) 1f else 0.35f),
        )
        Text(
            "$versionIndex / $versionCount",
            color = muted,
            fontSize = 13.sp,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = tr("下一版本"),
            modifier = Modifier
                .size(16.dp)
                .clickable(enabled = onNext != null, onClick = { onNext?.invoke() }),
            tint = muted.copy(alpha = if (onNext != null) 1f else 0.35f),
        )
    }
}

@Composable
private fun AssistantActions(
    onCopy: () -> Unit,
    onSpeak: (() -> Unit)?,
    speaking: Boolean,
    onRetry: () -> Unit,
    canRetry: Boolean,
    finishedAt: Long,
    versionIndex: Int,
    versionCount: Int,
    onPrevVersion: (() -> Unit)?,
    onNextVersion: (() -> Unit)?,
) {
    val muted = Theme[colors][mutedColor]
    val clock = formatReplyClock(finishedAt)
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = tr("复制"),
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onCopy),
            tint = muted,
        )
        if (canRetry) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = tr("重试"),
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onRetry),
                tint = muted,
            )
        }
        if (onSpeak != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = tr("朗读"),
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onSpeak),
                tint = if (speaking) Theme[colors][onBackgroundColor] else muted,
            )
        }
        if (clock.isNotEmpty()) {
            Text(
                clock,
                color = muted,
                fontSize = 13.sp,
            )
        }
        if (versionCount > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = tr("上一个回复"),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(enabled = onPrevVersion != null, onClick = { onPrevVersion?.invoke() }),
                    tint = muted.copy(alpha = if (onPrevVersion != null) 1f else 0.35f),
                )
                Text(
                    "$versionIndex / $versionCount",
                    color = muted,
                    fontSize = 13.sp,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = tr("下一个回复"),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(enabled = onNextVersion != null, onClick = { onNextVersion?.invoke() }),
                    tint = muted.copy(alpha = if (onNextVersion != null) 1f else 0.35f),
                )
            }
        }
    }
}

@Composable
private fun ZoomableOriginalImage(
    image: ImageBitmap,
    zoomState: ZoomableState,
) {
    // zoomable 默认用 graphicsLayer 放大屏幕大小的图层，放大后就是糊的。
    // 关掉自动变换，按缩放把原图像素直接画进视口。
    DisposableEffect(zoomState) {
        val previous = zoomState.autoApplyTransformations
        zoomState.autoApplyTransformations = false
        onDispose { zoomState.autoApplyTransformations = previous }
    }
    zoomState.setContentLocation(
        ZoomableContentLocation.unscaledAndTopLeftAligned(
            Size(image.width.toFloat(), image.height.toFloat()),
        ),
    )
    Box(
        Modifier
            // 整个屏幕都是图片手势区：双击缩放、双指缩放/平移。
            // 不设 onClick：tap 判定要等 double-tap 超时窗口（~300ms），关闭有可感延迟；
            // 关闭走 Esc（PhotoViewer 的 onPreviewKeyEvent）。
            .fillMaxSize()
            .zoomable(zoomState)
            .drawBehind {
                val transformation = zoomState.contentTransformation
                if (!transformation.isSpecified) return@drawBehind
                val scale = transformation.scale
                drawImage(
                    image = image,
                    dstOffset = IntOffset(
                        transformation.offset.x.roundToInt(),
                        transformation.offset.y.roundToInt(),
                    ),
                    dstSize = IntSize(
                        (image.width * scale.scaleX).roundToInt().coerceAtLeast(1),
                        (image.height * scale.scaleY).roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.High,
                )
            },
    )
}

@Composable
private fun PhotoViewer(
    attachment: ChatAttachment,
    onDismiss: () -> Unit,
) {
    var bitmap by remember(attachment.filePath) { mutableStateOf<ImageBitmap?>(null) }
    var loaded by remember(attachment.filePath) { mutableStateOf(false) }
    LaunchedEffect(attachment.filePath) {
        bitmap = withContext(Dispatchers.IO) { decodePreview(attachment.filePath) }
        loaded = true
    }
    val zoomState = rememberZoomableState()
    var saved by remember(attachment.filePath) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(4f)
            .background(Color.Black.copy(alpha = 0.92f))
            // Esc 关闭预览（photo viewer 在 process sheet 之上，自己的 Esc 自己处理）
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            ZoomableOriginalImage(image, zoomState)
        } else if (loaded) {
            Text(attachment.name, color = Color.White)
        }
        // 右下角下载按钮：系统保存对话框
        IconButton(
            onClick = {
                if (saved) return@IconButton
                val target = FilePickers.saveFile(attachment.name) ?: return@IconButton
                saved = runCatching {
                    File(attachment.filePath).copyTo(target, overwrite = true)
                    true
                }.getOrDefault(false)
            },
            style = ButtonStyle.Ghost,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
        ) {
            if (saved) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = tr("已保存"),
                    tint = Color.White,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = tr("下载图片"),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ClearHistoryDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    IrioAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        backgroundColor = Theme[colors][panelColor],
        contentColor = Theme[colors][onPanelColor],
    ) {
        Column {
            DialogTitle(tr("删除全部历史"))
            Spacer(Modifier.height(12.dp))
            Text(tr("确定要删除全部历史聊天记录吗？此操作无法撤销。"))
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

