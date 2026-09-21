package com.irio

import me.saket.telephoto.zoomable.zoomable
import me.saket.telephoto.zoomable.rememberZoomableState
import androidx.compose.material.icons.rounded.Download
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.AlertDialog
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import com.composables.ui.components.BottomSheet
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.BottomSheetState
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
import com.composables.ui.theme.defaultTextSelectionColors
import com.composables.ui.theme.destructiveColor
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.textSelectionColors
import com.composeunstyled.ProvideTextStyle
import com.composeunstyled.theme.Theme
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.recyclerview.widget.RecyclerView
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color as TexColor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkRuntime.ensure(this)
        setIrioContent {
            MainScreen(restoreLastOpened = savedInstanceState != null)
        }
    }
}

@Composable
fun MainScreen(restoreLastOpened: Boolean = false) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var processSheetEvent by remember { mutableStateOf<ProcessEvent?>(null) }
    val processSheetState = rememberBottomSheetState()
    val message = rememberTextFieldState()
    // 抽屉列表只持轻量摘要（无正文）；正文按需从磁盘取并常驻缓存（见 [conversationContent]）。
    var conversations by remember { mutableStateOf(emptyList<ConversationSummary>()) }
    var currentConversation by remember {
        mutableStateOf(ConversationStore.newConversation())
    }
    var messages by remember { mutableStateOf(currentConversation.messages) }
    var pendingAttachments by remember { mutableStateOf(emptyList<ChatAttachment>()) }
    var clearingHistory by remember { mutableStateOf(false) }
    var composerStatus by remember { mutableStateOf<String?>(null) }
    /** 正在编辑的用户消息 id；null = 普通发送模式。 */
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    val composerFocusRequester = remember { FocusRequester() }
    var selectedModel by remember {
        mutableStateOf(ProviderStore.loadProvider(context)?.selectedModel.orEmpty())
    }
    var providers by remember { mutableStateOf(ProviderStore.loadProviders(context)) }
    var activeProviderId by remember { mutableStateOf(ProviderStore.loadActiveProviderId(context)) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var reasoningEffort by remember { mutableStateOf(ProviderStore.loadReasoningEffort(context)) }
    var fastMode by remember { mutableStateOf(ProviderStore.loadFastMode(context)) }
    var mergeModels by remember { mutableStateOf(ProviderStore.loadMergeModels(context)) }
    var modelsDevCatalog by remember { mutableStateOf(ProviderStore.loadModelsDevCatalog(context)) }
    var modelsDevReasoning by remember { mutableStateOf(ProviderStore.loadModelsDevReasoning(context)) }
    var webSearchAvailable by remember { mutableStateOf(SearchStore.hasApiKey(context)) }
    var webSearchEnabled by remember { mutableStateOf(SearchStore.loadWebSearchEnabled(context)) }
    var clearHistoryDialogOpen by remember { mutableStateOf(false) }
    var conversationAction by remember { mutableStateOf<ConversationSummary?>(null) }
    var viewingPhoto by remember { mutableStateOf<ChatAttachment?>(null) }
    var generations by remember { mutableStateOf(emptyMap<Long, ActiveGeneration>()) }
    val currentConversationState = rememberUpdatedState(currentConversation)
    val generationsSnapshotState = rememberUpdatedState(generations)
    // 消息 ID 由全局 MessageIds 分配，避免跨会话/重启后的回复错配。
    // 点击保存前标记，DisposableEffect 里的 listener 在 300ms 内跳过自触发回环。
    // 普通引用即可：listener 闭包捕获引用，不需要 Compose 状态观测。
    class SuppressFlag { var fn: () -> Unit = {} }
    val suppressProviderEcho = remember { SuppressFlag() }
    val messageRecyclerController = remember { MessageRecyclerController() }
    var recyclerScrolling by remember { mutableStateOf(false) }
    var composerBarHeightPx by remember { mutableIntStateOf(0) }
    val composerBarHeight = with(LocalDensity.current) { composerBarHeightPx.toDp() }
    var toolbarHeightPx by remember { mutableIntStateOf(0) }
    val toolbarHeight = with(LocalDensity.current) { toolbarHeightPx.toDp() }
    val toolbarContentPaddingPx = with(LocalDensity.current) { (toolbarHeight + 16.dp).toPx().roundToInt() }
    val composerContentPaddingPx = with(LocalDensity.current) { (composerBarHeight + 16.dp).toPx().roundToInt() }
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
    val generatingIds = remember(generations) { generations.values.map { it.conversationId }.toSet() }
    val groupedConversationRows = remember(conversations) { groupedConversations(conversations) }
    val generatingThisChat = currentConversation.id in generatingIds
    val imeAnimating = remember { AtomicBoolean(false) }
    ImeAnimationProbe(imeAnimating)
    val streamingDeferActive = remember { AtomicBoolean(false) }
    // 预热不能抢首次展示的 CPU：只保留一个可取消的邻近消息任务，
    // 快速滚动时丢弃旧范围，避免几十个 LaTeX 同时排队。
    val transcriptPrewarmJob = remember { mutableStateOf<Job?>(null) }

    /**
     * 会话正文缓存（LRU 8 条）：conversations 里只有摘要，打开/置顶/流式兜底都要正文，
     * 全部走这里，避免同一会话在同一帧里被重复读盘解析 JSON。
     */
    val conversationCache = remember {
        object : LinkedHashMap<String, Conversation>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Conversation>): Boolean = size > 8
        }
    }

    fun conversationContent(id: String): Conversation? {
        synchronized(conversationCache) { conversationCache[id] }?.let { return it }
        val loaded = ConversationStore.load(context, id) ?: return null
        synchronized(conversationCache) { conversationCache[id] = loaded }
        return loaded
    }

    fun cacheConversationContent(conversation: Conversation) {
        synchronized(conversationCache) { conversationCache[conversation.id] = conversation }
    }

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
    val renderContentArgb = content.toArgb()
    val renderPxPerPt = density.density
    val renderTexScale = remember { ProviderStore.loadTexScale(context) }

    val clearingHistoryState = rememberUpdatedState(clearingHistory)

    // 普通冷启动保持空白；系统重建 Activity（包括进程被回收后的恢复）时回到上次会话。
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { ConversationStore.loadAll(context) }
        if (!clearingHistoryState.value) conversations = loaded
    }

    // 普通冷启动保持空白；系统重建 Activity（包括进程被回收后的恢复）时回到上次会话。
    LaunchedEffect(Unit) {
        if (!restoreLastOpened) return@LaunchedEffect
        val restored = withContext(Dispatchers.IO) { ConversationStore.loadLastOpened(context) }
        if (restored != null) {
            cacheConversationContent(restored)
            pendingConversationScroll = restored.id
            currentConversation = restored
            messages = restored.messages
            // MessageIds 保证跨会话及进程内的消息 ID 不冲突。
        }
    }
    val retryGroups = if (presentationReady) transcriptPresentation.retryGroups else emptyMap()
    val editGroups = if (presentationReady) transcriptPresentation.editGroups else emptyMap()
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
        cacheConversationContent(stored)
        // 摘要就地更新（filter/map 保留引用相等性，不打乱抽屉滚动位置）；
        // 排序只在顺序真变化时重写引用，避免无谓的整列表重组。
        conversations = conversations
            .filterNot { it.id == stored.id }
            .plus(stored.summary())
            .let { list ->
                val sorted = list.sortedWith(
                    compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt },
                )
                if (sorted == list) list else sorted
            }
        scope.launch(Dispatchers.IO) {
            ConversationStore.save(context, stored)
            ConversationStore.saveLastOpenedId(context, stored.id)
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
            val stored = conversationContent(conversationId) ?: return
            val next = stored.copy(
                messages = if (stored.messages.any { it.id == replyId }) {
                    stored.messages.map { if (it.id == replyId) updated else it }
                } else {
                    stored.messages + updated
                },
                updatedAt = System.currentTimeMillis(),
                // titlePending 期间用截断文本临时显示；已生成的标题不被覆盖
                title = if (stored.titlePending) {
                    ConversationStore.titleFrom(
                        if (stored.messages.any { it.id == replyId }) {
                            stored.messages.map { if (it.id == replyId) updated else it }
                        } else {
                            stored.messages + updated
                        },
                    )
                } else {
                    stored.title
                },
            )
            cacheConversationContent(next)
            conversations = conversations
                .filterNot { it.id == next.id }
                .plus(next.summary())
                .let { list ->
                    val sorted = list.sortedWith(
                        compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt },
                    )
                    if (sorted == list) list else sorted
                }
            scope.launch(Dispatchers.IO) {
                ConversationStore.save(context, next)
            }
        }
    }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> context.copyAttachment(uri) }
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
                loaded.size < uris.size -> "有附件无法读取"
                slots > ChatApi.MAX_ATTACHMENT_SLOTS -> "附件数量超过 15 个槽位（照片和视频各算半个）"
                bytes > ChatApi.MAX_ATTACHMENT_BYTES_TOTAL -> "附件总大小超过 5GB"
                else -> null
            }
        }
    }

    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> addAttachments(uris) }
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> addAttachments(uris) }

    DisposableEffect(context) {
        // SharedPreferences 监听：模型/思考等设置被外部（设置页）改动时刷新。
        // 注意：本页面自己保存的字段已在点击回调里先更新内存态，listener 全量重载
        // 会覆盖为磁盘值——新值与内存一致时靠「引用相等跳过」避免无谓重组。
        val preferences = ProviderStore.preferences(context)
        var suppressUntil = 0L
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme_preference") return@OnSharedPreferenceChangeListener
            // 自己刚发起的保存：300ms 内跳过回环重载（内存态已是最新）
            if (System.currentTimeMillis() < suppressUntil) return@OnSharedPreferenceChangeListener
            scope.launch(Dispatchers.IO) {
                val loaded = ProviderStore.loadProviders(context)
                val active = ProviderStore.loadActiveProviderId(context)
                val provider = loaded.firstOrNull { it.id == active } ?: loaded.firstOrNull()
                val effort = ProviderStore.loadReasoningEffort(context)
                val fast = ProviderStore.loadFastMode(context)
                val merge = ProviderStore.loadMergeModels(context)
                val catalog = ProviderStore.loadModelsDevCatalog(context)
                val reasoning = ProviderStore.loadModelsDevReasoning(context)
                val searchReady = SearchStore.hasApiKey(context)
                val searchOn = SearchStore.loadWebSearchEnabled(context)
                withContext(Dispatchers.Main) {
                    if (System.currentTimeMillis() < suppressUntil) return@withContext
                    providers = loaded
                    activeProviderId = active
                    selectedModel = provider?.selectedModel.orEmpty()
                    reasoningEffort = effort
                    fastMode = fast
                    mergeModels = merge
                    modelsDevCatalog = catalog
                    modelsDevReasoning = reasoning
                    webSearchAvailable = searchReady
                    webSearchEnabled = searchOn
                }
            }
        }
        val conversationsListener = {
            val current = currentConversationState.value
            val currentId = current.id
            val currentUpdatedAt = current.updatedAt
            val streamingThisChat = currentId in generationsSnapshotState.value.values.map { it.conversationId }
            scope.launch(Dispatchers.IO) {
                val loaded = ConversationStore.loadAll(context)
                val full = loaded.firstOrNull { it.id == currentId }
                    ?.takeIf { it.updatedAt != currentUpdatedAt && !streamingThisChat }
                    ?.let { ConversationStore.load(context, it.id) }
                withContext(Dispatchers.Main) {
                    // 自触发回环（persistCurrent → save → notifyChanged）里 id 集合没变时，
                    // 逐条比较字段、不变就保留原引用，避免点击发送后整个抽屉重组。
                    val merged = if (loaded.map { it.id } == conversations.map { it.id }) {
                        conversations.map { old ->
                            loaded.firstOrNull { it.id == old.id }?.let { new ->
                                if (old.title != new.title || old.updatedAt != new.updatedAt || old.pinned != new.pinned) new else old
                            } ?: old
                        }
                    } else {
                        loaded
                    }
                    if (merged != conversations) conversations = merged
                    if (full != null && currentConversation.id == currentId && currentId !in generations.values.map { it.conversationId }) {
                        currentConversation = full
                        messages = full.messages
                    }
                }
            }
            Unit
        }
        // ProviderStore 全局变化（含本页面 save 触发的 notifyChanged）：
        // 自己刚点击保存的场景下跳过，避免对同一批数据做第二次全量解密+解析+状态覆盖。
        val providersListener = {
            if (System.currentTimeMillis() >= suppressUntil) {
                scope.launch(Dispatchers.IO) {
                    val loaded = ProviderStore.loadProviders(context)
                    val active = ProviderStore.loadActiveProviderId(context)
                    val provider = loaded.firstOrNull { it.id == active } ?: loaded.firstOrNull()
                    val effort = ProviderStore.loadReasoningEffort(context)
                    val fast = ProviderStore.loadFastMode(context)
                    val merge = ProviderStore.loadMergeModels(context)
                    val catalog = ProviderStore.loadModelsDevCatalog(context)
                    val reasoning = ProviderStore.loadModelsDevReasoning(context)
                    val searchReady = SearchStore.hasApiKey(context)
                    val searchOn = SearchStore.loadWebSearchEnabled(context)
                    withContext(Dispatchers.Main) {
                        if (System.currentTimeMillis() < suppressUntil) return@withContext
                        providers = loaded
                        activeProviderId = active
                        selectedModel = provider?.selectedModel.orEmpty()
                        reasoningEffort = effort
                        fastMode = fast
                        mergeModels = merge
                        modelsDevCatalog = catalog
                        modelsDevReasoning = reasoning
                        webSearchAvailable = searchReady
                        webSearchEnabled = searchOn
                    }
                }
            }
        }
        suppressProviderEcho.fn = { suppressUntil = System.currentTimeMillis() + 300 }
        val searchListener = {
            webSearchAvailable = SearchStore.hasApiKey(context)
            webSearchEnabled = SearchStore.loadWebSearchEnabled(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        ConversationStore.addChangeListener(conversationsListener)
        ProviderStore.addChangeListener(providersListener)
        SearchStore.addChangeListener(searchListener)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            ConversationStore.removeChangeListener(conversationsListener)
            ProviderStore.removeChangeListener(providersListener)
            SearchStore.removeChangeListener(searchListener)
            suppressProviderEcho.fn = {}
        }
    }

    LaunchedEffect(providers, activeProviderId) {
        val activeModels = providers.firstOrNull { it.id == activeProviderId }?.models.orEmpty()
        if (selectedModel !in activeModels) {
            selectedModel = activeModels.firstOrNull().orEmpty()
        }
        if (providers.none { it.models.isNotEmpty() }) modelMenuOpen = false
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val currentProviders by rememberUpdatedState(providers)
    val currentActiveId by rememberUpdatedState(activeProviderId)
    val currentInFlight by rememberUpdatedState(generations.isNotEmpty())
    val activeProvider = currentProviders.firstOrNull { it.id == currentActiveId }

    LaunchedEffect(currentActiveId, activeProvider?.endpointUrl, currentInFlight) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            EndpointWarmup.shouldWarm(activeProvider, currentInFlight)
        ) {
            withContext(Dispatchers.IO) { EndpointWarmup.warmBlocking(activeProvider!!) }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val provider = currentProviders.firstOrNull { it.id == currentActiveId }
                    if (EndpointWarmup.shouldWarm(provider, currentInFlight)) {
                        scope.launch(Dispatchers.IO) { EndpointWarmup.warmBlocking(provider!!) }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(currentConversation.id, visibleTranscript.size, visibleTranscript.lastOrNull()?.id) {
        val last = visibleTranscript.lastOrNull() ?: return@LaunchedEffect
        if (pendingConversationScroll == currentConversation.id) {
            pendingConversationScroll = null
            messageRecyclerController.requestScrollToBottom()
            return@LaunchedEffect
        }
        // 用户不在底部（在往回翻看历史）时不抢滚动位置，让浏览保持流畅。
        if (recyclerScrolling || messageRecyclerController.isScrollInProgress()) return@LaunchedEffect
        // 新发送的用户消息必须落到底部；只有后续助手更新才受“用户上滑不抢位置”保护。
        if (last.fromUser) {
            messageRecyclerController.requestScrollToBottom()
            return@LaunchedEffect
        }
        val lastVisible = messageRecyclerController.lastVisibleIndex()
        val nearBottom = lastVisible >= visibleTranscript.lastIndex - 1
        if (nearBottom && (!last.isPending && !last.isStreaming)) {
            messageRecyclerController.requestScrollToBottom()
        }
    }

    // 拖动停止 / 惯性衰减 / 流式结束后复位 streamingDeferActive，
    // 否则后续流式一直被压在 120ms 的滚动间隔上。
    LaunchedEffect(recyclerScrolling, generations) {
        if (!recyclerScrolling) {
            // 手指刚抬起时给惯性一点缓冲再放开
            delay(240)
            streamingDeferActive.set(false)
        }
    }

    fun openConversation(conversation: ConversationSummary) {
        if (conversation.id == currentConversation.id) {
            drawerProgress = 0f
            return
        }
        val requestToken = conversationLoadToken + 1
        conversationLoadToken = requestToken
        // 先关抽屉；正文磁盘读取和 Markdown/TeX 预热都不能阻塞点击反馈。
        drawerProgress = 0f
        scope.launch(Dispatchers.IO) {
            val full = conversationContent(conversation.id) ?: return@launch
            val applied = withContext(Dispatchers.Main) {
                if (conversationLoadToken != requestToken) return@withContext false
                pendingConversationScroll = full.id
                currentConversation = full
                messages = full.messages
                true
            }
            if (applied) ConversationStore.saveLastOpenedId(context, conversation.id)
        }
    }

    fun newConversation(closeDrawer: Boolean = true) {
        conversationLoadToken++
        currentConversation = ConversationStore.newConversation()
        messages = emptyList()
        pendingAttachments = emptyList()
        composerStatus = null
        if (closeDrawer) drawerProgress = 0f
    }

    val processSheetOpen = processSheetState.targetDetent != BottomSheetDetent.Hidden
    NavigationBackHandler(
        viewingPhoto = viewingPhoto != null,
        processSheetOpen = processSheetOpen,
        drawerOpen = drawerOpen,
        hasMessages = messages.isNotEmpty(),
        onDismissPhoto = { viewingPhoto = null },
        onDismissSheet = { processSheetState.targetDetent = BottomSheetDetent.Hidden },
        onCloseDrawer = { drawerProgress = 0f },
        onHideIme = {
            keyboardController?.hide()
            focusManager.clearFocus()
        },
        onNewConversation = { newConversation() },
    )

    fun stopGeneration(conversationId: String = currentConversation.id) {
        val active = generations.values.filter { it.conversationId == conversationId }
        if (active.isEmpty()) return
        // 立即把占位消息从 streaming 降级为 pending，UI 立刻恢复可点。
        val replyIds = generations.entries
            .filter { it.value.conversationId == conversationId }
            .map { it.key }
            .toSet()
        messages = messages.map { msg ->
            if (msg.id in replyIds && msg.isStreaming) msg.copy(isStreaming = false) else msg
        }
        // 断开 HTTP 连接会让 ChatApi 返回 stopped；不要取消协程本身，
        // 否则取消状态会跳过后续收尾逻辑，思考事件无法写入最终消息。
        active.forEach { entry ->
            entry.cancellation.cancel()
        }
    }

    fun clearAllHistory() {
        clearingHistory = true
        generations.values.forEach { it.cancellation.cancel() }
        ConversationStore.deleteAll(context)
        conversations = emptyList()
        message.clearText()
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
            ConversationStore.load(context, conversation.id)?.let { stored ->
                ConversationStore.save(context, stored.copy(pinned = nextPinned))
            }
        }
    }

    fun deleteConversation(conversation: ConversationSummary) {
        if (conversation.id in generatingIds) stopGeneration(conversation.id)
        conversations = conversations.filterNot { it.id == conversation.id }
        if (currentConversation.id == conversation.id) {
            newConversation(closeDrawer = false)
        }
        synchronized(conversationCache) { conversationCache.remove(conversation.id) }
        scope.launch(Dispatchers.IO) {
            ConversationStore.delete(context, conversation.id)
        }
    }

    fun openAttachment(attachment: ChatAttachment) {
        if (attachment.isImage) {
            viewingPhoto = attachment
            return
        }
        runCatching {
            val file = File(attachment.filePath)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, attachment.name))
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
        val configured = ToolStore.loadTitleModel(context)
        val titleModel = when {
            configured.isNotBlank() -> configured
            !ModelCatalog.isImageModel(context, selectedModel) -> selectedModel
            else -> ProviderStore.loadLastChatModel(context)
        }
        if (titleModel.isBlank()) return
        scope.launch {
            // 标题模型可能与当前 provider 不同：优先找拥有该模型的 provider
            val provider = ProviderStore.loadProviders(context)
                .firstOrNull { titleModel in it.models }
                ?: ProviderStore.loadProvider(context)
                ?: return@launch
            val title = ChatApi.generateTitle(provider, titleModel, userText, assistantText)
            if (title.isNullOrBlank()) return@launch
            // 仍要确认标题没被别的路径改掉（并发防抢占）
            val current = conversations.firstOrNull { it.id == conversationId } ?: return@launch
            if (!current.titlePending) return@launch
            withContext(Dispatchers.Main) {
                conversations = conversations.map { if (it.id == conversationId) it.copy(title = title, titlePending = false) else it }
                if (currentConversation.id == conversationId) {
                    currentConversation = currentConversation.copy(title = title, titlePending = false)
                }
            }
            withContext(Dispatchers.IO) {
                conversationContent(conversationId)?.let { full ->
                    val updated = full.copy(title = title, titlePending = false)
                    cacheConversationContent(updated)
                    ConversationStore.save(context, updated)
                }
            }
        }
    }

    fun startGeneration(
        requestConversationId: String,
        requestSource: List<ChatMessage>,
        reply: ChatMessage,
    ) {
        val requestModel = selectedModel
        val effectiveReasoningInfo = if (ProviderStore.loadModelsDevReasoning(context)) {
            ModelCatalog.reasoningInfo(context, requestModel, useCatalog = true)
        } else null
        val requestEffort = when {
            effectiveReasoningInfo != null && !effectiveReasoningInfo.supports -> ReasoningEffort.OFF
            effectiveReasoningInfo?.toggleable == false && reasoningEffort == ReasoningEffort.OFF -> ReasoningEffort.AUTO
            else -> reasoningEffort
        }
        val requestFast = fastMode
        val requestWebSearch = SearchStore.isWebSearchActive(context)
        val requestSearchProvider = SearchStore.loadProvider(context)
        val requestSearchKey = SearchStore.loadApiKey(context, requestSearchProvider)
        val requestSearchMaxResults = SearchStore.loadMaxResults(context, requestSearchProvider)
        val requestMaxToolRounds = ToolStore.loadMaxRounds(context)
        val requestMemory = MemoryStore.systemPrompt(context)
        val requestSystemPrompt = buildString {
            SystemPromptStore.load(context).trim().takeIf { it.isNotEmpty() }?.let { append(it) }
            requestMemory?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
        }.ifBlank { null }
        // 双重门槛，任一不满足都不注入 generate_image 工具：
        // ① 对话模型本身是生图模型（它自己能画；且图片渠道普遍不支持 tools，注入必报能力错）；
        // ② 设置里存的生图模型必须在目录判定为真生图模型（过滤规则上线前的旧值——
        //    如曾被选成聊天模型——拿去打 /images/generations 一样报能力错，直接视为未配置）。
        val requestImageGenModel = ToolStore.loadImageGenModel(context)
            .takeIf { ModelCatalog.isImageModel(context, it) }
            .takeUnless { ModelCatalog.isImageModel(context, selectedModel) } ?: ""
        val requestRegistry = ToolRegistry(
            buildList {
                if (requestWebSearch) {
                    add(WebSearchToolProvider())
                    add(WebFetchToolProvider())
                }
                if (requestImageGenModel.isNotBlank()) add(ImageGenToolProvider())
                add(TimeToolProvider())
                if (MemoryStore.loadEnabled(context)) add(MemoryToolProvider())
            }
        )
        val requestCancellation = ChatRequestCancellation()
        // 优先用当前内存态的 provider（省一次全量 Keystore 解密解析）；
        // 被选中 provider 被删掉时回落磁盘。
        val requestProvider = providers.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
            ?: providers.firstOrNull { it.models.isNotEmpty() }
            ?: ProviderStore.loadProvider(context)
        val job = scope.launch {
            var appliedFinal = false
            try {
                val provider = requestProvider
                var streamedText = ""
                var streamedAttachments = emptyList<ChatAttachment>()
                val process = ProcessTracker()
                var lastPublishAt = 0L
                var publishedBodyLength = 0
                var publishedThinking = false
                var streamedUsage: TokenUsage? = null
                // 对话模型本身是生图模型：聊天请求整条改走 /images/generations
                // （gpt-image 等纯生图模型在 chat/completions 上游必拒），
                // 最后一条用户消息当 prompt，结果图片作为回复附件回流。非流式，无工具/思考。
                val result = if (provider == null) {
                    ChatCompletionResult(error = "请先在设置中添加提供商")
                } else if (ModelCatalog.isImageModel(context, requestModel)) {
                    val lastUserIndex = requestSource.indexOfLast { it.fromUser }
                    val prompt = requestSource.getOrNull(lastUserIndex)?.text.orEmpty().ifBlank { "图片" }
                    // Images API 无状态：多轮要回传上轮 prompt + 生成图。
                    // 上轮 = 本条用户消息之前最近的一次生图往返（上轮用户 prompt + 其后模型产出的图）。
                    val context = if (lastUserIndex > 0) {
                        val previousUserPrompt = requestSource.subList(0, lastUserIndex)
                            .lastOrNull { it.fromUser }?.text.orEmpty()
                        val previousImages = requestSource.subList(0, lastUserIndex)
                            .filter { !it.fromUser }
                            .flatMap { it.attachments.filter { attachment -> attachment.isImage } }
                        if (previousImages.isNotEmpty()) {
                            ProviderApi.ImageGenContext(previousUserPrompt, previousImages.takeLast(3))
                        } else null
                    } else null
                    val imageProvider = provider
                    withContext(Dispatchers.IO) {
                        val genResult = ProviderApi.generateImage(
                            imageProvider,
                            requestModel,
                            prompt,
                            context,
                            cancellation = requestCancellation,
                        )
                        if (requestCancellation.isCancelled()) {
                            ChatCompletionResult(stopped = true)
                        } else if (genResult.attachment != null) {
                            // 图片本身就是回复，不加成功文案。
                            ChatCompletionResult(
                                attachments = listOf(genResult.attachment),
                                model = requestModel,
                            )
                        } else if (requestCancellation.isCancelled() || genResult.error == "图片生成已停止") {
                            ChatCompletionResult(stopped = true)
                        } else {
                            ChatCompletionResult(error = genResult.error ?: "图片生成失败")
                        }
                    }
                } else if (requestModel.isBlank()) {
                    ChatCompletionResult(error = "请先选择一个可用模型")
                } else {
                val requestMessages = requestSource.map { item ->
                    ChatRequestMessage(
                        role = if (item.fromUser) "user" else "assistant",
                        text = item.text,
                        attachments = item.attachments,
                        thinking = item.thinking,
                        createdAt = item.createdAt,
                    )
                }
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
                        cancellation = requestCancellation,
                        appContext = context,
                        onUpdate = update@{ update ->
                            val cancelled = requestCancellation.isCancelled()
                            val terminalState = update.searching == false || update.toolActive == false || update.done
                            if (!cancelled || terminalState || update.attachments.isNotEmpty()) {
                                if (!cancelled) {
                                    if (update.newRound) streamedText = ""
                                    if (update.delta.isNotEmpty()) streamedText += update.delta
                                    if (update.thinkingDelta.isNotEmpty()) {
                                        process.onThinkingDelta(update.thinkingDelta)
                                    }
                                    if (update.delta.isNotEmpty()) process.onOutputStarted()
                                }
                                if (update.attachments.isNotEmpty()) {
                                    streamedAttachments = (streamedAttachments + update.attachments)
                                        .distinctBy { it.filePath }
                                }
                                if (update.usage != null) streamedUsage = update.usage
                                if (update.searching == true && !cancelled) process.onSearchStarted()
                                if (update.searching == false) process.onSearchFinished(update.searchPagesDelta, update.searchContent)
                                if (update.toolActive == true && !cancelled) process.onToolStarted(update.toolName)
                                if (update.toolActive == false) process.onToolFinished(update.toolContent)
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
                                publishedBodyLength = publishedBodyLength,
                                publishedThinking = publishedThinking,
                                deferNonEssential = imeAnimating.get(),
                                userActivelyScrolling = streamingDeferActive.get(),
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
                                        isPending = text.isBlank() && snapshot.none { it.live || it.content.isNotBlank() || it.pages > 0 },
                                        isStreaming = (!update.done && !requestCancellation.isCancelled()) ||
                                            snapshot.any { it.live },
                                    )
                                )
                                if (usage != null && currentConversation.id == requestConversationId) {
                                    currentConversation = currentConversation.copy(tokenUsage = usage)
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
            // 停止/报错/正常结束都必须让过程事件闭合：仍在进行中的事件如果带着 live=true 落库，
            // 重新进入对话时会一直显示"思考中"。正常结束时 finishAll 已经收尾，这里只兜底。
            val closedProcess = snapshot.map { it.copy(live = false) }
            val current = if (currentConversation.id == requestConversationId) {
                messages.firstOrNull { it.id == reply.id }
            } else {
                conversationContent(requestConversationId)?.messages?.firstOrNull { it.id == reply.id }
            }
            val finalProcess = closedProcess
            val finalUsage = result.usage ?: streamedUsage ?: current?.usage
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
                            isPending = false,
                            isStreaming = false,
                        )
                    }
                }
                result.error != null -> reply.copy(
                    text = result.error,
                    process = finalProcess.map { it.copy(live = false) },
                    usage = finalUsage,
                    isError = true,
                    isPending = false,
                    isStreaming = false,
                )
                else -> reply.copy(
                    text = result.content.ifBlank { streamedText },
                    process = finalProcess.map { it.copy(live = false) },
                    attachments = result.attachments.ifEmpty { streamedAttachments },
                    usage = finalUsage,
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
                                val stored = conversationContent(requestConversationId)
                                if (stored != null) {
                                    val updated = stored.copy(messages = stored.messages.filterNot { it.id == reply.id })
                                    cacheConversationContent(updated)
                                    ConversationStore.save(context, updated)
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
                                userText = requestSource.lastOrNull { it.fromUser }?.text.orEmpty(),
                                assistantText = finalMessage.text,
                            )
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
        val reply = ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            isPending = true,
        )
        val history = visibleMessages(messages).filterNot { it.isError || it.isPending }
        messages = messages + userMessage + reply
        persistCurrent(messages)
        pendingAttachments = emptyList()
        composerStatus = null
        message.clearText()
        startGeneration(currentConversation.id, history + userMessage, reply)
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
        // 清掉本就待发的内容可能带来的混淆状态
        composerStatus = null
        // 直接编辑：拉起输入法并聚焦输入框
        composerFocusRequester.requestFocus()
        keyboardController?.show()
    }

    /** 退出编辑模式（不保存）。 */
    fun cancelEdit() {
        editingMessageId = null
        message.clearText()
        pendingAttachments = emptyList()
    }

    /**
     * 提交编辑：新版本插回原消息位置（而不是列表尾），旧版本取消选中；
     * 紧随其后的模型回复组取消选中，新 pending 回复进同一个 retryGroup 并启动重生成。
     * 版本切换（showEditVersion）与回复切换（showRetryVersion）独立，各自切换各自组。
     */
    fun commitEdit() {
        if (generatingThisChat) return
        val targetId = editingMessageId ?: return
        val targetIndex = messages.indexOfFirst { it.id == targetId }
        if (targetIndex < 0) return
        val target = messages[targetIndex]
        val text = message.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        val version = target.copy(
            id = MessageIds.next(),
            text = text,
            attachments = pendingAttachments,
            process = emptyList(),
            isError = false,
            isPending = false,
            isStreaming = false,
            editGroup = target.userGroupId,
            selected = true,
        )
        // 原用户消息的回复组（紧随 target 之后的 assistant 消息的 groupId）
        val replyGroupId = messages.drop(targetIndex + 1)
            .firstOrNull { !it.fromUser && !it.isError }
            ?.groupId
        // history = 新版本之前可见消息（含新版本本身，等同新发送）
        val history = buildList {
            addAll(visibleMessages(messages.subList(0, targetIndex)).filterNot { it.isError || it.isPending })
            add(version)
        }
        val reply = replyGroupId?.let { groupId ->
            ChatMessage(
                id = MessageIds.next(),
                text = "",
                fromUser = false,
                isPending = true,
                retryGroup = groupId,
                selected = true,
            )
        }
        messages = buildList {
            messages.forEachIndexed { index, message ->
                when {
                    // 旧编辑版本取消选中
                    message.fromUser && message.userGroupId == target.userGroupId -> add(message.copy(selected = false))
                    // 原回复组取消选中（没有回复则跳过）
                    reply != null && !message.fromUser && message.groupId == replyGroupId -> add(message.copy(selected = false))
                    else -> add(message)
                }
                // 新版本插在原位置之后
                if (index == targetIndex) add(version)
            }
            if (reply != null) add(reply)
        }
        persistCurrent(messages)
        message.clearText()
        pendingAttachments = emptyList()
        editingMessageId = null
        if (reply != null) {
            startGeneration(currentConversation.id, history, reply)
        }
    }

    /**
     * 切换用户消息的编辑版本：用户组切换 + 联动切换它对应的回复组。
     * 配对规则：该版本消息之后的第一条非 error assistant 消息即它当时的回复。
     */
    fun showEditVersion(target: ChatMessage) {
        val targetIndex = messages.indexOfFirst { it.id == target.id }
        val pairedReply = messages.drop(targetIndex + 1)
            .firstOrNull { !it.fromUser && !it.isError }
        messages = selectEditVersion(messages, target)
        if (pairedReply != null) {
            messages = selectRetryVersion(messages, pairedReply)
        }
        persistCurrent(messages, touchUpdatedAt = false)
    }

    fun retryMessage(assistant: ChatMessage) {
        if (assistant.fromUser || assistant.isPending || assistant.isStreaming) return
        generations[assistant.id]?.cancellation?.cancel()
        val groupId = assistant.groupId
        val firstIndex = messages.indexOfFirst { !it.fromUser && it.groupId == groupId }
        if (firstIndex <= 0) return
        val history = visibleMessages(messages.take(firstIndex)).filterNot { it.isError || it.isPending }
        if (history.isEmpty()) return
        val reply = ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            isPending = true,
            retryGroup = groupId,
            selected = true,
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
        startGeneration(currentConversation.id, history, reply)
    }

    fun showRetryVersion(target: ChatMessage) {
        messages = selectRetryVersion(messages, target)
        persistCurrent(messages, touchUpdatedAt = false)
    }

    fun retryVersionInfo(target: ChatMessage): VersionInfo {
        val versions = if (target.fromUser) listOf(target) else retryGroups[target.groupId].orEmpty()
        val index = versions.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        return VersionInfo(index + 1, versions.size)
    }

    fun editVersionInfo(target: ChatMessage): VersionInfo {
        val versions = if (target.fromUser) editGroups[target.userGroupId].orEmpty() else emptyList()
        val index = versions.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        return VersionInfo(index + 1, versions.size)
    }

    val messageCallbacks = AndroidMessageCallbacks(
        markdownScope = scope,
        onOpenProcess = { event ->
            processSheetEvent = event
            processSheetState.targetDetent = BottomSheetDetent.FullyExpanded
        },
        onOpenAttachment = ::openAttachment,
        onCopy = { chatMessage -> clipboard.setText(AnnotatedString(chatMessage.text)) },
        onRetry = ::retryMessage,
        canRetry = { chatMessage -> !chatMessage.fromUser && !chatMessage.isPending && !chatMessage.isStreaming },
        onPrevVersion = { target ->
            val versions = if (target.fromUser) listOf(target) else retryGroups[target.groupId].orEmpty()
            val index = versions.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
            versions.getOrNull(index - 1)?.let { previous -> { showRetryVersion(previous) } }
        },
        onNextVersion = { target ->
            val versions = if (target.fromUser) listOf(target) else retryGroups[target.groupId].orEmpty()
            val index = versions.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
            versions.getOrNull(index + 1)?.let { next -> { showRetryVersion(next) } }
        },
        isEditing = { chatMessage -> editingMessageId == chatMessage.id },
        onEdit = { chatMessage ->
            if (
                chatMessage.fromUser && !chatMessage.isPending && !chatMessage.isStreaming &&
                chatMessage.id == visibleTranscript.lastOrNull { it.fromUser }?.id
            ) {
                { beginEdit(chatMessage) }
            } else null
        },
        onPrevEditVersion = { target ->
            val versions = editGroups[target.userGroupId].orEmpty()
            val index = versions.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
            versions.getOrNull(index - 1)?.let { previous -> { showEditVersion(previous) } }
        },
        onNextEditVersion = { target ->
            val versions = editGroups[target.userGroupId].orEmpty()
            val index = versions.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
            versions.getOrNull(index + 1)?.let { next -> { showEditVersion(next) } }
        },
        versionInfo = ::retryVersionInfo,
        editVersionInfo = ::editVersionInfo,
    )

    Box(modifier = Modifier.fillMaxSize()) {
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
                .statusBarsPadding()
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
                    bottom = 8.dp
                )
        ) {
            ListRowButton(
                label = "新对话",
                icon = Icons.Rounded.Add,
                style = ButtonStyle.Ghost,
                contentPadding = DrawerRowPadding,
                onClick = { newConversation() },
            )
            Spacer(Modifier.heightIn(min = 8.dp))
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 64.dp),
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
                                            Text(if (conversation.pinned) "取消置顶" else "置顶")
                                        }
                                        DropdownMenuItem(
                                            onClick = {
                                                deleteConversation(conversation)
                                                conversationAction = null
                                            },
                                        ) {
                                            Text("删除对话")
                                        }
                                    }
                                },
                            ) {
                                ListRowButton(
                                    label = conversation.title,
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
                        .background(background),
                ) {
                    ListRowButton(
                        label = "设置",
                        icon = Icons.Rounded.Settings,
                        style = ButtonStyle.Ghost,
                        contentPadding = DrawerRowPadding,
                        onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
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
                    .imePadding()
            ) {
                AndroidMessageList(
                    messages = visibleTranscript,
                    controller = messageRecyclerController,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(chatHazeState)
                        .padding(horizontal = 16.dp),
                    contentPaddingTop = toolbarContentPaddingPx,
                    contentPaddingBottom = composerContentPaddingPx,
                    callbacks = messageCallbacks,
                    onVisibleRangeChanged = { first, last ->
                        val snapshot = visibleTranscript
                        val conversationId = currentConversation.id
                        val candidates = listOf(first - 1, last + 1)
                            .filter { it in snapshot.indices }
                            .distinct()
                        transcriptPrewarmJob.value?.cancel()
                        transcriptPrewarmJob.value = if (candidates.isEmpty()) {
                            null
                        } else {
                            scope.launch {
                                // 先让当前可见行完成首帧和自身渲染，再预热邻居。
                                withFrameNanos { }
                                delay(180)
                                if (currentConversation.id != conversationId) return@launch
                                candidates.forEach { index ->
                                    prepareFinishedMessage(
                                        snapshot[index], renderContentArgb, renderPxPerPt, renderTexScale, scope,
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
                )

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
                        modifier = Modifier
                            .windowInsetsPadding(
                                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                                    .exclude(WindowInsets.ime),
                            )
                            .onSizeChanged { composerBarHeightPx = it.height },
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
                        color = Theme[colors][destructiveColor],
                        fontSize = 12.sp,
                    )
                }

                // 编辑模式提示条：正在编辑某条用户消息
                if (editingMessageId != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .background(
                                color = Theme[colors][primaryColor].copy(alpha = 0.12f),
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
                            tint = Theme[colors][primaryColor],
                        )
                        Text(
                            "正在编辑消息。",
                            modifier = Modifier.weight(1f),
                            color = Theme[colors][primaryColor],
                            fontSize = 13.sp,
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "取消编辑",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(onClick = { cancelEdit() }),
                            tint = Theme[colors][primaryColor],
                        )
                    }
                }

                val canSend = (editingMessageId != null && (message.text.isNotBlank() || pendingAttachments.isNotEmpty())) ||
                    (!generatingThisChat && editingMessageId == null && (message.text.isNotBlank() || pendingAttachments.isNotEmpty()))
                ComposerField(
                    state = message,
                    placeholder = "Message",
                    focusRequester = composerFocusRequester,
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
                                            pickPhotos.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                            )
                                        },
                                        leading = {
                                            Icon(
                                                imageVector = Icons.Rounded.Photo,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                    ) { Text("照片") }
                                    DropdownMenuItem(
                                        onClick = {
                                            attachMenuOpen = false
                                            pickFiles.launch(arrayOf("*/*"))
                                        },
                                        leading = {
                                            Icon(
                                                imageVector = Icons.Rounded.AttachFile,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                    ) { Text("文件") }
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
                                    .semantics { contentDescription = "添加附件" },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "添加附件",
                                    modifier = Modifier.size(20.dp),
                                    tint = if (generatingThisChat) muted else content,
                                )
                            }
                        }
                    },
                    trailing = {
                        IconButton(
                            onClick = {
                                when {
                                    generatingThisChat -> stopGeneration()
                                    editingMessageId != null -> commitEdit()
                                    else -> submitMessage()
                                }
                            },
                            enabled = generatingThisChat || canSend,
                            style = ButtonStyle.Ghost,
                            buttonSize = ButtonSize.Small,
                            modifier = Modifier
                                .size(32.dp)
                                .semantics {
                                    contentDescription = if (generatingThisChat) "停止输出" else "Send message"
                                },
                        ) {
                            Icon(
                                imageVector = if (generatingThisChat) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                                contentDescription = if (generatingThisChat) "停止输出" else "Send message",
                                modifier = Modifier.size(if (generatingThisChat) 22.dp else 20.dp),
                                tint = when {
                                    generatingThisChat -> content
                                    canSend -> Theme[colors][primaryColor]
                                    else -> muted
                                }
                            )
                        }
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
                        val showContextRing = ThemeStore.contextRingEnabled && ThemeStore.contextRing
                        val contextUsage = currentConversation.tokenUsage
                            ?: messages.lastOrNull { !it.fromUser && it.usage != null }?.usage
                        if (showContextRing) {
                            ToolbarCapsule {
                                ContextUsageRing(
                                    usedTokens = contextUsage?.usedTokens ?: 0,
                                    windowTokens = ModelCatalog.contextWindow(context, selectedModel),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                )
                            }
                        }
                        ToolbarCapsule {
                            ModelPicker(
                            selectedModel = selectedModel,
                            providers = providers,
                            activeProviderId = activeProviderId,
                            mergeModels = mergeModels,
                            useModelsDev = modelsDevCatalog,
                            useModelsDevReasoning = modelsDevReasoning,
                            reasoningEffort = reasoningEffort,
                            fastMode = fastMode,
                            webSearchAvailable = webSearchAvailable,
                            webSearchEnabled = webSearchEnabled,
                            expanded = modelMenuOpen,
                            onExpandedChange = { modelMenuOpen = it },
                            onSelectedModelChange = { model, providerId ->
                                suppressProviderEcho.fn()
                                selectedModel = model
                                // 记录最近一次选中的非图片对话模型（标题生成在图片模型场景的回退目标）
                                if (!ModelCatalog.isImageModel(context, model)) {
                                    ProviderStore.saveLastChatModel(context, model)
                                }
                                // 持久化走 IO：persist 会对每个 provider 做 AndroidKeyStore 加密
                                // （binder 往返），主线程同步执行会造成点击切换模型的可感卡顿。
                                scope.launch(Dispatchers.IO) {
                                    if (providerId != activeProviderId) {
                                        withContext(Dispatchers.Main) { activeProviderId = providerId }
                                        ProviderStore.saveActiveProviderId(context, providerId)
                                    }
                                    ProviderStore.saveSelectedModel(context, model, providerId)
                                }
                            },
                            onReasoningChange = { effort ->
                                suppressProviderEcho.fn()
                                reasoningEffort = effort
                                ProviderStore.saveReasoningEffort(context, effort)
                            },
                            onFastModeChange = { enabled ->
                                suppressProviderEcho.fn()
                                fastMode = enabled
                                ProviderStore.saveFastMode(context, enabled)
                            },
                            onWebSearchChange = { enabled ->
                                webSearchEnabled = enabled
                                SearchStore.saveWebSearchEnabled(context, enabled)
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
    useModelsDev: Boolean,
    useModelsDevReasoning: Boolean,
    reasoningEffort: ReasoningEffort,
    fastMode: Boolean,
    webSearchAvailable: Boolean,
    webSearchEnabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectedModelChange: (model: String, providerId: String) -> Unit,
    onReasoningChange: (ReasoningEffort) -> Unit,
    onFastModeChange: (Boolean) -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val providersWithModels = providers.filter { it.enabledModels().isNotEmpty() }
    if (providersWithModels.isEmpty()) {
        Text(
            "未配置模型",
            color = muted,
            fontSize = 16.sp,
        )
        return
    }
    // 每个提供商内部合并 -thinking / -fast 变体；不同提供商之间不合并。
    // 行的显示名 / 目标模型在 remember 里预计算，滚动组合时只剩纯 Text。
    val rows = remember(providersWithModels, mergeModels, useModelsDev) {
        buildList {
            providersWithModels.forEachIndexed { providerIndex, provider ->
                if (providerIndex > 0) add(ModelPickerRow.Gap("gap-$providerIndex"))
                add(ModelPickerRow.GroupLabel("label-${provider.id}", provider.name))
                val groups = if (mergeModels) {
                    provider.enabledModels().groupBy { ModelCatalog.mergeKey(it, mergeEnabled = true) }
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
                            display = ModelCatalog.displayName(context, base, useModelsDev),
                            target = if (mergeModels) ModelCatalog.canonicalModel(variants) else base,
                            variants = variants,
                        )
                    )
                }
            }
        }
    }
    val selectedRow = rows
        .asSequence()
        .filterIsInstance<ModelPickerRow.Model>()
        .firstOrNull { it.providerId == activeProviderId && selectedModel in it.variants }
    val selectedDisplay = selectedRow?.display
        ?: ModelCatalog.displayName(context, selectedModel, useModelsDev)
    val selectedProvider = providersWithModels
        .firstOrNull { it.id == activeProviderId && selectedModel in it.models }
        ?: providersWithModels.firstOrNull { selectedModel in it.models }
    val reasoningInfo = remember(selectedModel, selectedProvider, mergeModels, useModelsDevReasoning) {
        if (!useModelsDevReasoning || selectedProvider == null) {
            null
        } else {
            if (mergeModels) {
                val variants = selectedProvider.models.filter {
                    ModelCatalog.mergeKey(it, true) == ModelCatalog.mergeKey(selectedModel, true)
                }
                val infos = variants.mapNotNull { ModelCatalog.reasoningInfo(context, it, useCatalog = true) }
                ModelCatalog.mergedReasoning(infos)
            } else {
                ModelCatalog.reasoningInfo(context, selectedModel, useCatalog = true)
            }
        }
    }
    val supportsThinking = reasoningInfo?.supports ?: true
    val thinkingLockedOn = reasoningInfo?.let { it.toggleable == false } == true
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.wrapContentWidth(Alignment.End),
        alignment = DropdownMenuAlignment.End,
        panel = {
            Row(modifier = Modifier.widthIn(max = 340.dp).heightIn(max = 520.dp)) {
                DropdownMenuPanel(modifier = Modifier.widthIn(min = 112.dp, max = 140.dp)) {
                    if (supportsThinking) {
                        ReasoningEffort.entries.forEach { option ->
                            // 模型被标记为无法关闭思考时，只允许保持非「关闭」档位
                            val enabled = !thinkingLockedOn || option != ReasoningEffort.OFF
                            DropdownMenuItem(
                                onClick = { if (enabled) onReasoningChange(option) },
                                leading = {
                                    val checked = reasoningEffort == option && (enabled || option != ReasoningEffort.OFF)
                                    if (checked) {
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
                                Text(
                                    option.label,
                                    fontSize = 15.5.sp,
                                    color = if (enabled) Theme[colors][onBackgroundColor] else Theme[colors][mutedColor],
                                )
                            }
                        }
                    } else {
                        DropdownMenuItem(
                            onClick = {},
                            leading = { Spacer(Modifier.size(16.dp)) },
                        ) {
                            Text("不支持思考", fontSize = 15.5.sp, color = Theme[colors][mutedColor])
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
                        Text("快速", fontSize = 15.5.sp)
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
                        Text("网络搜索", fontSize = 15.5.sp)
                    }
                }
                DropdownMenuPanel(modifier = Modifier.width(200.dp)) {
                    LazyColumn(
                        modifier = Modifier
                            .width(200.dp)
                            .heightIn(max = 520.dp)
                    ) {
                        items(
                            count = rows.size,
                            key = { rows[it].key },
                            contentType = { index ->
                                when (rows[index]) {
                                    is ModelPickerRow.Gap -> "gap"
                                    is ModelPickerRow.GroupLabel -> "label"
                                    is ModelPickerRow.Model -> "model"
                                }
                            },
                        ) { index ->
                            when (val row = rows[index]) {
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
                                        Text(
                                            row.display,
                                            fontSize = 15.5.sp,
                                        )
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
            modifier = Modifier.semantics { contentDescription = "选择模型" },
        ) {
            Text(
                selectedDisplay.ifBlank { "选择模型" },
                modifier = Modifier.widthIn(max = 180.dp),
                fontSize = 15.sp,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "选择模型",
                modifier = Modifier.size(18.dp),
                tint = content,
            )
        }
    }
}

@Composable
internal fun MessageRow(
    chatMessage: ChatMessage,
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
) {
    val alignment = if (chatMessage.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (chatMessage.fromUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (chatMessage.fromUser) {
                if (chatMessage.attachments.isNotEmpty()) {
                    chatMessage.attachments.forEach { attachment ->
                        AttachmentPreview(attachment, boxed = true, onOpen = { onOpenAttachment(attachment) })
                    }
                }
                if (chatMessage.text.isNotBlank()) {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        CompositionLocalProvider(
                            LocalTextSelectionColors provides Theme[textSelectionColors][defaultTextSelectionColors],
                        ) {
                            SelectionContainer {
                                Text(
                                    chatMessage.text,
                                    modifier = Modifier
                                        .widthIn(max = maxWidth * 0.85f)
                                        .background(
                                            color = when {
                                                isEditing -> Theme[colors][primaryColor].copy(alpha = 0.16f)
                                                else -> Theme[colors][panelColor]
                                            },
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
                                    lineHeight = 22.sp,
                                    color = if (isEditing) Theme[colors][primaryColor] else Theme[colors][onPanelColor],
                                )
                            }
                        }
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
                    chatMessage.attachments.forEach { attachment ->
                        AttachmentPreview(attachment, boxed = false, onOpen = { onOpenAttachment(attachment) })
                    }
                }
                // 正文开始前启动的过程事件（思考/搜索）在文本上方；开始后启动的（工具/后续搜索）在下方
                chatMessage.process.forEach { event ->
                    if (!event.belowText) ProcessEventRow(event, onOpen = onOpenProcess)
                }
                when {
                    (chatMessage.isPending || chatMessage.isStreaming) &&
                        chatMessage.text.isBlank() &&
                        chatMessage.process.none { it.live } -> {
                        StatusSweepText("请求中")
                    }
                    chatMessage.text.isNotBlank() -> {
                        Box(Modifier.padding(vertical = 2.dp)) {
                            CompositionLocalProvider(
                                LocalTextSelectionColors provides Theme[textSelectionColors][defaultTextSelectionColors],
                            ) {
                                SelectionContainer {
                                    if (chatMessage.isError) {
                                        Text(
                                            chatMessage.text,
                                            color = Theme[colors][destructiveColor],
                                            fontSize = 17.sp,
                                            lineHeight = 26.sp,
                                        )
                                    } else {
                                        AssistantMarkdown(
                                            text = chatMessage.text,
                                            streaming = chatMessage.isStreaming || chatMessage.isPending,
                                            renderScope = markdownScope,
                                        )
                                    }
                                }
                            }
                        }
                        // 正文开始后启动的过程事件渲染在已输出文本下方
                        chatMessage.process.forEach { event ->
                            if (event.belowText) ProcessEventRow(event, onOpen = onOpenProcess)
                        }
                    }
                }
                // 停止/结束后正文可能一直为空（例如只输出思考就被停止）：
                // 操作行必须挂在 when 之外，否则这条消息永远拿不到复制和重试。
                if (!chatMessage.fromUser && !chatMessage.isPending && !chatMessage.isStreaming &&
                    editVersionCount <= 1
                ) {
                    AssistantActions(
                        onCopy = onCopy,
                        onRetry = onRetry,
                        canRetry = canRetry,
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
    val muted = Theme[colors][mutedColor]
    val openable = !event.live && event.content.isNotBlank()
    val label = when (event.kind) {
        ProcessEventKind.THINKING -> if (event.live) null else "已思考 ${formatThinkingDuration(event.millis)}"
        ProcessEventKind.SEARCH -> if (event.live) null else "搜索了 ${event.pages} 个网页"
        ProcessEventKind.TOOL -> if (event.live) null else MemoryTool.doneLabel(event.toolName)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (event.live) {
            StatusSweepText(
                when (event.kind) {
                    ProcessEventKind.THINKING -> "思考中"
                    ProcessEventKind.SEARCH -> "搜索中"
                    ProcessEventKind.TOOL -> MemoryTool.liveLabel(event.toolName)
                }
            )
            if (event.kind == ProcessEventKind.THINKING && event.content.isNotBlank()) {
                val thinkingScrollState = rememberScrollState()
                val hostView = LocalView.current
                // Read this during the composition that contains the old text. Reading it
                // inside LaunchedEffect can already see the newly measured maxValue, making a
                // view that was at the bottom look like the user had scrolled away.
                val wasNearBottom = thinkingScrollState.value >=
                    thinkingScrollState.maxValue - 48
                LaunchedEffect(event.content) {
                    withFrameNanos { }
                    if (wasNearBottom) thinkingScrollState.scrollTo(thinkingScrollState.maxValue)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        // The row itself lives inside the transcript RecyclerView. Let this
                        // nested scroller keep the gesture instead of RecyclerView stealing it.
                        .pointerInteropFilter { event ->
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    hostView.parent?.requestDisallowInterceptTouchEvent(true)
                                    false
                                }
                                android.view.MotionEvent.ACTION_UP,
                                android.view.MotionEvent.ACTION_CANCEL -> {
                                    hostView.parent?.requestDisallowInterceptTouchEvent(false)
                                    false
                                }
                                else -> false
                            }
                        }
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
            (LocalWindowInfo.current.containerSize.height * 0.32f).toDp()
        }
        val maxSheetHeight = with(LocalDensity.current) {
            (LocalWindowInfo.current.containerSize.height * 0.85f).toDp()
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
                .semantics { contentDescription = "移除附件" },
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "移除附件",
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
        Image(
            bitmap = rendered,
            contentDescription = attachment.name,
            modifier = Modifier
                .heightIn(max = if (compact) 64.dp else 280.dp)
                .widthIn(max = if (compact) 64.dp else 360.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(clickable),
            contentScale = ContentScale.Crop,
        )
    } else {
        Text(
            attachment.name,
            fontSize = 12.sp,
            modifier = Modifier
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

private fun Context.copyAttachment(uri: Uri): ChatAttachment? {
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    val mimeType = contentResolver.getType(uri)?.lowercase()?.ifBlank { null } ?: mimeFromName(name)
    val dir = File(filesDir, "attachments").apply { mkdirs() }
    val target = File(dir, "${UUID.randomUUID()}${extensionFor(mimeType).ifBlank { "" }}")
    contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    if (!target.exists() || target.length() <= 0L) {
        target.delete()
        return null
    }
    return ChatAttachment(
        name = name?.ifBlank { null } ?: "attachment${extensionFor(mimeType)}",
        mimeType = mimeType,
        filePath = target.absolutePath,
    )
}

private fun mimeFromName(name: String?): String {
    val extension = name?.substringAfterLast('.', "").orEmpty().lowercase()
    return when (extension) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }
}

private fun decodePreview(path: String, maxPx: Int = 720): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, options)?.asImageBitmap()
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
        if (pinned.isNotEmpty()) add("置顶" to pinned)
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
        then.timeInMillis >= today.timeInMillis -> "今天"
        then.timeInMillis >= yesterday.timeInMillis -> "昨天"
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            SimpleDateFormat("M月d日", Locale.CHINA).format(Date(updatedAt))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(updatedAt))
    }
}

private fun formatThinkingDuration(millis: Long): String {
    val seconds = (millis / 1000.0)
    return if (seconds < 10) {
        String.format(Locale.CHINA, "%.1f 秒", seconds)
    } else {
        "${seconds.toInt()} 秒"
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

@Composable
private fun NavigationBackHandler(
    viewingPhoto: Boolean,
    processSheetOpen: Boolean,
    drawerOpen: Boolean,
    hasMessages: Boolean,
    onDismissPhoto: () -> Unit,
    onDismissSheet: () -> Unit,
    onCloseDrawer: () -> Unit,
    onHideIme: () -> Unit,
    onNewConversation: () -> Unit,
) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    BackHandler(enabled = viewingPhoto || processSheetOpen || drawerOpen || imeVisible || hasMessages) {
        when {
            viewingPhoto -> onDismissPhoto()
            processSheetOpen -> onDismissSheet()
            drawerOpen -> onCloseDrawer()
            imeVisible -> onHideIme()
            else -> onNewConversation()
        }
    }
}

@Composable
private fun ImeAnimationProbe(animating: AtomicBoolean) {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val previous = remember { intArrayOf(imeBottom) }
    SideEffect {
        if (imeBottom != previous[0]) {
            animating.set(true)
            previous[0] = imeBottom
        }
    }
    LaunchedEffect(imeBottom) {
        delay(48)
        if (previous[0] == imeBottom) animating.set(false)
    }
}

private class PreParsedMarkdownState(private val parsed: State.Success) : MarkdownState {
    override val state: StateFlow<State> = MutableStateFlow(parsed)
    override val links: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    override suspend fun parse(): State = parsed
}

/**
 * Process-wide LRU of parsed Markdown states keyed by segment content.
 * Content-keyed (not message-id-keyed) so scroll-off disposal, conversation
 * switches, and app-restored sessions all hit the same entry, and parsing is
 * never repeated for identical text. The map holds `MutableState<MarkdownState?>`
 * slots: a miss returns an empty slot that a background coroutine fills once —
 * the scroll frame itself never waits on a parse. Null content (parse failure /
 * still parsing) falls back to inline parsing in `IrioMarkdown` as before.
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
        synchronized(map) {
            map.getOrPut(content) { mutableStateOf(null) }
        }

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
            markdownSegments(content, streaming = false)
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
            markdownSegments(content, streaming = false)
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
    // JLatexMath has a process-wide rendering lock. Dispatching dozens of
    // formulas onto Default makes every worker except one block on that lock,
    // starving Markdown parsing and transcript presentation during first open.
    // Keep the serialized renderer on its own lane instead.
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(1)

    fun request(
        key: TexCacheKey,
        scope: CoroutineScope,
        render: () -> ImageBitmap?,
    ): AsyncCacheResult<ImageBitmap> = cache.request(key, scope, renderDispatcher) {
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
    if (message.fromUser || message.isError || message.isPending || message.isStreaming || message.text.isBlank()) return
    val segments = FinishedMarkdownSegmentsCache.prepare(message.text, scope) ?: return
    segments.filterIsInstance<MarkdownSegment.Text_>()
        .filter { it.markdown }
        .map { it.text }
        .distinct()
        .forEach { FinishedMarkdownCache.request(it, scope, Dispatchers.Default) }
    val formulas = segments.filterIsInstance<MarkdownSegment.TexBlock>().map { it.latex to true } +
        segments.filterIsInstance<MarkdownSegment.Text_>()
            .flatMap { segment -> inlineTexContents(segment.text).map { it to false } }
    formulas.distinct().forEach { (latex, displayStyle) ->
        val key = TexCacheKey(latex, contentArgb, displayStyle, pxPerPt, texScale)
        TexBitmapCache.request(key, scope) {
            renderTexBitmap(latex, contentArgb, displayStyle, pxPerPt, texScale)
        }
    }
}

@Composable
private fun AssistantMarkdown(
    text: String,
    streaming: Boolean,
    renderScope: CoroutineScope,
) {
    var renderStreaming by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) {
        if (streaming) {
            renderStreaming = true
        } else {
            withFrameNanos { }
            renderStreaming = false
        }
    }
    val segmentSlot = if (!renderStreaming) {
        remember(text) { FinishedMarkdownSegmentsCache.request(text, renderScope) }
    } else {
        null
    }
    val preparedSegments = segmentSlot?.value
    val segments = if (renderStreaming) {
        remember(text, renderStreaming) { markdownSegments(text, streaming = true) }
    } else {
        preparedSegments ?: listOf(MarkdownSegment.Text_("pending", text, markdown = false))
    }
    val markdownSlots = if (!renderStreaming) {
        remember(text, segments) {
            segments
                .filterIsInstance<MarkdownSegment.Text_>()
                .filter { it.markdown }
                .distinctBy { it.text }
                .associate { segment ->
                    segment.text to FinishedMarkdownCache.request(segment.text, renderScope, Dispatchers.Default)
                }
        }
    } else {
        emptyMap()
    }
    val contentColor = Theme[colors][onBackgroundColor]
    val context = LocalContext.current
    // texScale 只在进入组合时读一次：不再监听 ProviderStore 全局变化——
    // 否则模型切换/思考档位保存会让屏幕上每条消息都重组（切换卡顿的主要来源之一）。
    val texScale = remember { ProviderStore.loadTexScale(context) }
    val bodyStyle = TextStyle(fontSize = 17.sp, lineHeight = 26.sp)
    val inlineCode = irioInlineCodeStyle()
    val codeBlock = irioCodeBlockStyle()
    val typography = markdownTypography(
        h1 = TextStyle(fontSize = 24.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
        h2 = TextStyle(fontSize = 22.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold),
        h3 = TextStyle(fontSize = 20.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
        h4 = TextStyle(fontSize = 18.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        h5 = TextStyle(fontSize = 17.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        h6 = TextStyle(fontSize = 17.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
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
    val density = LocalDensity.current
    val texTransformer = remember(contentColor, texScale) {
        TexImageTransformer(contentColor.toArgb(), texScale, renderScope)
    }
    val imageTransformer = remember(texTransformer) { IrioImageTransformer(texTransformer) }
    ProvideTextStyle(bodyStyle) {
        Column {
            segments.forEach { segment ->
                key(segment.key) {
                    when {
                        segment is MarkdownSegment.TexBlock -> FadeInBlock(fadeIn = renderStreaming) {
                            TexBlock(
                                latex = segment.latex,
                                colorArgb = contentColor.toArgb(),
                                density = density,
                                texScale = texScale,
                                renderScope = renderScope,
                            )
                        }
                        segment is MarkdownSegment.Text_ && segment.markdown -> FadeInBlock(fadeIn = renderStreaming) {
                            val markdownState = markdownSlots[segment.text]?.value
                            IrioMarkdown(
                                content = segment.text,
                                state = if (renderStreaming) null else markdownState,
                                fallbackToRaw = !renderStreaming && markdownState == null,
                                typography = typography,
                                imageTransformer = imageTransformer,
                            )
                        }
                        segment is MarkdownSegment.Text_ -> Text(
                            segment.text,
                            fontSize = 17.sp,
                            lineHeight = 30.sp,
                            color = contentColor,
                        )
                    }
                }
            }
        }
    }
}

/** Block-level `$$…$$` / `\[…\]` formula rendered with JLatexMath. */
@Composable
private fun TexBlock(latex: String, colorArgb: Int, density: Density, texScale: Float, renderScope: CoroutineScope) {
    val pxPerPt = density.density
    val cacheKey = TexCacheKey(latex, colorArgb, displayStyle = true, pxPerPt, texScale)
    // Cache hit is drawn immediately; a miss renders off the main thread so a
    // formula entering the viewport never blocks the scroll frame.
    var bitmap by remember(cacheKey) { mutableStateOf(TexBitmapCache.get(cacheKey)) }
    if (bitmap == null) {
        LaunchedEffect(cacheKey) {
            val result = TexBitmapCache.request(cacheKey, renderScope) {
                renderTexBitmap(latex, colorArgb, displayStyle = true, pxPerPt = pxPerPt, texScale = texScale)
            }
            bitmap = result.value ?: result.completion?.await()
        }
    }
    val rendered = bitmap
    if (rendered != null) {
        // Bitmap holds device pixels (pxPerPt = density); show it 1:1 px.
        val widthDp = with(density) { rendered.width.toDp() }
        val heightDp = with(density) { rendered.height.toDp() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Image(
                bitmap = rendered,
                contentDescription = null,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .size(width = widthDp, height = heightDp),
            )
        }
    } else {
        // Parse failure: keep the raw LaTeX visible.
        Text(latex, fontSize = 17.sp, lineHeight = 30.sp)
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
                    renderTexBitmap(
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
 * 10px Android font and never multiplied by [TeXFormula.PIXELS_PER_POINT].
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

private fun renderTexBitmap(
    latex: String,
    colorArgb: Int,
    displayStyle: Boolean,
    pxPerPt: Float,
    texScale: Float,
): ImageBitmap? = synchronized(texRenderLock) {
    try {
        val formula = TeXFormula(scaleCjkForDensity(latex, pxPerPt))
        val style = if (displayStyle) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT
        // Must be set BEFORE createTeXIcon: the icon layout is computed from it.
        TeXFormula.PIXELS_PER_POINT = pxPerPt
        val baseSize = if (displayStyle) 20f else 17f
        val icon = formula.createTeXIcon(style, baseSize * texScale)
        icon.setForeground(TexColor(colorArgb))
        val width = icon.iconWidth
        val height = icon.iconHeight
        if (width <= 0 || height <= 0) return null
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val graphics = AndroidGraphics2D()
        graphics.setCanvas(canvas)
        icon.paintIcon(null, graphics, 0, 0)
        bitmap.asImageBitmap()
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
    val alpha by rememberInfiniteTransition(label = "generatingPulse").animateFloat(
        initialValue = 0.2f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "generatingPulseAlpha",
    )
    drawWithContent {
        drawContent()
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2f
        val radius = 12.dp.toPx()
        drawRoundRect(
            color = highlight.copy(alpha = alpha),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** 用户消息编辑版本的切换器：与模型回复版本切换器同款式，右对齐贴气泡。 */
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
            contentDescription = "上一版本",
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
            contentDescription = "下一版本",
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
    onRetry: () -> Unit,
    canRetry: Boolean,
    versionIndex: Int,
    versionCount: Int,
    onPrevVersion: (() -> Unit)?,
    onNextVersion: (() -> Unit)?,
) {
    val muted = Theme[colors][mutedColor]
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = "复制",
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onCopy),
            tint = muted,
        )
        if (canRetry) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "重试",
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onRetry),
                tint = muted,
            )
        }
        if (versionCount > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "上一个回复",
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
                    contentDescription = "下一个回复",
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
private fun PhotoViewer(
    attachment: ChatAttachment,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val preview = remember(attachment.filePath) {
        decodePreview(attachment.filePath, maxPx = 2048)
    }
    val zoomState = rememberZoomableState()
    var saved by remember(attachment.filePath) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(4f)
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = attachment.name,
                modifier = Modifier
                    // 整个屏幕都是图片手势区：双击缩放、双指缩放/平移。
                    // 不设 onClick：tap 判定要等 double-tap 超时窗口（~300ms），关闭有可感延迟；
                    // 关闭走返回键（NavigationBackHandler）。
                    .fillMaxSize()
                    .zoomable(zoomState),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(attachment.name, color = Color.White)
        }
        // 右下角下载按钮：保存到系统下载（相册可见）
        IconButton(
            onClick = {
                if (saved) return@IconButton
                saved = true
                runCatching {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, attachment.name)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, attachment.mimeType.ifBlank { "image/png" })
                    }
                    val uri = context.contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values,
                    ) ?: return@runCatching
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(File(attachment.filePath).readBytes())
                    }
                }
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
                    contentDescription = "已保存",
                    tint = Color.White,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "下载图片",
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
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        backgroundColor = Theme[colors][panelColor],
        contentColor = Theme[colors][onPanelColor],
    ) {
        Column {
            DialogTitle("删除全部历史")
            Spacer(Modifier.height(12.dp))
            Text("确定要删除全部历史聊天记录吗？此操作无法撤销。")
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = "删除",
                onConfirm = onConfirm,
                onCancel = onDismiss,
                confirmStyle = ButtonStyle.Destructive,
            )
        }
    }
}


