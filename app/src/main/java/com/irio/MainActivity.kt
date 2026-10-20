package com.irio

import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageErrorReporter
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import okio.Path.Companion.toPath
import androidx.compose.material.icons.rounded.Download
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import com.kyant.shapes.RoundedRectangle
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
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.FilterQuality
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.AlertDialog

import dev.chrisbanes.haze.HazeInput


import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import kotlin.math.sqrt
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
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.defaultTextSelectionColors
import com.composables.ui.theme.destructiveColor
import com.composables.ui.theme.fieldColor
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
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
import java.io.IOException
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
        ReplyOpenRequest.publish(intent)
        ReplyLiveNotice.install(this)
        LinkRuntime.ensure(this)
        enableIrioGlassBackdrop()
        setIrioContent {
            MainScreen(restoreLastOpened = savedInstanceState != null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ReplyOpenRequest.publish(intent)
    }
}

@Composable
fun MainScreen(restoreLastOpened: Boolean = false) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var processSheetMessageId by remember { mutableStateOf<Long?>(null) }
    var processSheetEventId by remember { mutableStateOf<Long?>(null) }
    var reactionTarget by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val reactionSheetState = rememberBottomSheetState()
    val reactionAnimations = remember { ReactionBounceTracker() }
    val processSheetState = rememberBottomSheetState()
    var backgroundCommandId by remember { mutableStateOf<Long?>(null) }
    var backgroundSheetEvent by remember { mutableStateOf<ProcessEvent?>(null) }
    val backgroundSheetState = rememberBottomSheetState()
    LaunchedEffect(backgroundCommandId) {
        val id = backgroundCommandId ?: return@LaunchedEffect
        while (true) {
            val job = LinuxBackgroundJobs.find(id.toString()) ?: break
            backgroundSheetEvent = linuxBackgroundTerminalEvent(job)
            if (!job.running && job.exitCode != null) break
            delay(200)
        }
    }
    val message = rememberTextFieldState()
    var sttRecording by remember { mutableStateOf(false) }
    var sttTranscribing by remember { mutableStateOf(false) }
    var sttWaveformLevels by remember { mutableStateOf(emptyList<Float>()) }
    var sttRecordingStartedAt by remember { mutableLongStateOf(0L) }
    var sttRecordingElapsedMillis by remember { mutableLongStateOf(0L) }
    val sttRecorder = remember { SttRecorder(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { sttRecorder.cancel() }
    }
    // 抽屉列表只持轻量摘要（无正文）；正文按需从磁盘取并常驻缓存（见 [conversationContent]）。
    var conversations by remember { mutableStateOf(emptyList<ConversationSummary>()) }
    var currentConversation by remember {
        mutableStateOf(ConversationStore.newConversation())
    }
    var messages by remember { mutableStateOf(currentConversation.messages) }
    var newUserMessageId by remember { mutableStateOf<Long?>(null) }
    var pendingAttachments by remember { mutableStateOf(emptyList<ChatAttachment>()) }
    var writingPastedText by remember { mutableStateOf(emptySet<String>()) }
    var clearingHistory by remember { mutableStateOf(false) }
    var composerStatus by remember { mutableStateOf<String?>(null) }
    var sttProviderSnackbarVisible by remember { mutableStateOf(false) }
    var sttProviderSnackbarRestartKey by remember { mutableIntStateOf(0) }
    /** 正在编辑的用户消息 id；null = 普通发送模式。 */
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    var pendingQuote by remember { mutableStateOf("") }
    var composerFullscreen by rememberSaveable { mutableStateOf(false) }
    var composerLineCount by remember { mutableIntStateOf(1) }
    var compactComposerHeightPx by remember { mutableIntStateOf(0) }
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
                quote = pendingQuote,
            ),
        )
        if (message.text.toString() != restored.text) {
            message.edit { replace(0, length, restored.text) }
        }
        pendingAttachments = restored.attachments
        editingMessageId = restored.editingMessageId
        pendingQuote = restored.quote
        composerStatus = null
        composerFullscreen = false
    }
    val composerFocusRequester = remember { FocusRequester() }
    fun showSttProviderSnackbar() {
        composerStatus = null
        sttProviderSnackbarVisible = true
        sttProviderSnackbarRestartKey++
    }
    fun startSttRecording() {
        if (sttTranscribing || sttRecording) return
        if (SttStore.loadActiveProvider(context) == null) {
            showSttProviderSnackbar()
            return
        }
        if (sttRecorder.start()) {
            sttRecording = true
            sttRecordingStartedAt = SystemClock.elapsedRealtime()
            sttRecordingElapsedMillis = 0L
            sttWaveformLevels = emptyList()
            composerStatus = null
        } else {
            composerStatus = tr("无法开始录音")
        }
    }
    fun finishSttRecording() {
        if (!sttRecording) return
        sttRecording = false
        val file = sttRecorder.stop()
        if (file == null) {
            composerStatus = tr("录音为空")
            return
        }
        val provider = SttStore.loadActiveProvider(context)
        if (provider == null) {
            file.delete()
            showSttProviderSnackbar()
            return
        }
        sttTranscribing = true
        composerStatus = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { SttClient.transcribe(file, provider) }
            file.delete()
            sttTranscribing = false
            if (result.error != null) {
                composerStatus = result.error
            } else if (result.text.isNotBlank()) {
                val needsSpace = message.text.isNotEmpty() && !message.text.last().isWhitespace()
                message.edit {
                    if (needsSpace) append(" ")
                    append(result.text)
                }
                composerFocusRequester.requestFocus()
            }
        }
    }
    val requestRecordAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startSttRecording() else composerStatus = tr("需要麦克风权限才能使用 STT")
    }
    fun toggleSttRecording() {
        when {
            sttTranscribing -> Unit
            sttRecording -> finishSttRecording()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startSttRecording()
            else -> requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(sttRecording) {
        if (sttRecording) {
            while (true) {
                val amplitude = withContext(Dispatchers.IO) { sttRecorder.sampleAmplitude() }
                val level = sqrt((amplitude / 32_767f).coerceIn(0f, 1f))
                sttWaveformLevels = (sttWaveformLevels + level).takeLast(64)
                sttRecordingElapsedMillis = SystemClock.elapsedRealtime() - sttRecordingStartedAt
                delay(70)
            }
        }
    }
    LaunchedEffect(currentConversation.id) {
        reactionSheetState.targetDetent = BottomSheetDetent.Hidden
        reactionTarget = null
    }
    var selectedModel by remember {
        mutableStateOf(ProviderStore.loadProvider(context)?.selectedModel.orEmpty())
    }
    var providers by remember { mutableStateOf(ProviderStore.loadProviders(context)) }
    var activeProviderId by remember { mutableStateOf(ProviderStore.loadActiveProviderId(context)) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var contextRingOpen by remember { mutableStateOf(false) }
    var contextCompressionEnabled by remember { mutableStateOf(ToolStore.loadContextCompression(context)) }
    var contextCompressionTrigger by remember { mutableStateOf(ToolStore.loadCompressionTrigger(context)) }
    var contextOverheadNonce by remember { mutableStateOf(0) }
    var contextSystemTokens by remember { mutableStateOf(0) }
    var contextToolTokens by remember { mutableStateOf("" to 0) }
    var liveToolTokens by remember { mutableStateOf("" to 0) }
    var visualizeContext by remember { mutableStateOf(ToolStore.loadVisualizeContext(context)) }
    var liveContextEstimate by remember { mutableStateOf("" to 0) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var reasoningEffort by remember { mutableStateOf(ProviderStore.loadReasoningEffort(context)) }
    var fastMode by remember { mutableStateOf(ProviderStore.loadFastMode(context)) }
    var mergeModels by remember { mutableStateOf(ProviderStore.loadMergeModels(context)) }
    var modelsDevCatalog by remember { mutableStateOf(ProviderStore.loadModelsDevCatalog(context)) }
    var modelsDevReasoning by remember { mutableStateOf(ProviderStore.loadModelsDevReasoning(context)) }
    var webSearchAvailable by remember { mutableStateOf(SearchStore.hasApiKey(context)) }
    var webSearchEnabled by remember { mutableStateOf(SearchStore.loadWebSearchEnabled(context)) }
    var autoRead by remember { mutableStateOf(SpeechStore.loadAutoRead(context)) }
    var clearHistoryDialogOpen by remember { mutableStateOf(false) }
    var conversationAction by remember { mutableStateOf<ConversationSummary?>(null) }
    var viewingPhoto by remember { mutableStateOf<ChatAttachment?>(null) }
    var generations by remember { mutableStateOf(emptyMap<Long, ActiveGeneration>()) }
    // 发送前预检（token 估算在后台线程）进行中的回复占位：堵住异步窗口期的重复提交。
    var preparing by remember { mutableStateOf(emptyMap<Long, String>()) }
    class BackgroundNoticeSink {
        var deliver: (String, List<LinuxBackgroundCommand>) -> Unit = { _, _ -> }
    }
    val backgroundNoticeSink = remember { BackgroundNoticeSink() }
    class SubagentResultSink {
        var deliver: (String, List<SubagentSnapshot>) -> Unit = { _, _ -> }
    }
    val subagentResultSink = remember { SubagentResultSink() }
    val pendingBackgroundNotices = remember { mutableMapOf<String, MutableList<LinuxBackgroundCommand>>() }
    val subagentFinishes = remember { SubagentFinishQueue() }

    fun flushSubagentResults(conversationId: String) {
        if (generations.values.any { it.conversationId == conversationId }) return
        if (preparing.values.any { it == conversationId }) return
        val queued = subagentFinishes.take(conversationId)
        if (queued.isEmpty()) return
        subagentResultSink.deliver(conversationId, queued)
    }

    fun flushBackgroundNotices(conversationId: String) {
        if (generations.values.any { it.conversationId == conversationId }) return
        if (preparing.values.any { it == conversationId }) return
        val queued = pendingBackgroundNotices.remove(conversationId)?.toList().orEmpty()
        if (queued.isNotEmpty()) backgroundNoticeSink.deliver(conversationId, queued)
        flushSubagentResults(conversationId)
    }

    fun noteSubagentFinished(snapshot: SubagentSnapshot) {
        if (snapshot.state == SubagentState.RUNNING || snapshot.conversationId.isBlank()) return
        if (!subagentFinishes.add(snapshot)) return
        flushSubagentResults(snapshot.conversationId)
    }

    fun noteBackgroundFinished(command: LinuxBackgroundCommand) {
        val conversationId = command.conversationId
        if (conversationId.isBlank()) return
        pendingBackgroundNotices.getOrPut(conversationId) { mutableListOf() }.add(command)
        flushBackgroundNotices(conversationId)
    }

    DisposableEffect(Unit) {
        val remove = LinuxBackgroundJobs.onFinished { command ->
            scope.launch(Dispatchers.Main.immediate) {
                noteBackgroundFinished(command)
            }
        }
        onDispose { remove() }
    }
    val currentConversationState = rememberUpdatedState(currentConversation)
    val generationsSnapshotState = rememberUpdatedState(generations)
    // 消息 ID 由全局 MessageIds 分配，避免跨会话/重启后的回复错配。
    // 点击保存前标记，DisposableEffect 里的 listener 在 300ms 内跳过自触发回环。
    // 普通引用即可：listener 闭包捕获引用，不需要 Compose 状态观测。
    class SuppressFlag { var fn: () -> Unit = {} }
    val suppressProviderEcho = remember { SuppressFlag() }
    val messageRecyclerController = remember { MessageRecyclerController() }
    var recyclerScrolling by remember { mutableStateOf(false) }
    var stickToBottom by remember { mutableStateOf(true) }
    var seenTranscriptConversationId by remember { mutableStateOf<String?>(null) }
    var seenTranscriptTailId by remember { mutableStateOf<Long?>(null) }
    var seenSentUserMessageId by remember { mutableStateOf<Long?>(null) }
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
    val transcriptBottomSlackPx = with(density) { 24.dp.toPx().roundToInt() }
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    var drawerProgress by remember { mutableStateOf(0f) }
    var drawerDragging by remember { mutableStateOf(false) }
    var drawerStartProgress by remember { mutableStateOf(0f) }
    val drawerOpenProgress by animateFloatAsState(
        targetValue = drawerProgress,
        animationSpec = if (drawerDragging) snap() else irioSpring<Float>(stiffness = 360f),
        label = "drawerOpenProgress",
    )
    val pageOffset = drawerWidth * drawerOpenProgress
    val pageDim = 0.28f * drawerOpenProgress
    val drawerShadowPx = with(density) { 8.dp.toPx() }
    val drawerOpen = drawerProgress >= 0.5f
    var pendingConversationScroll by remember { mutableStateOf<String?>(null) }
    var conversationLoadToken by remember { mutableIntStateOf(0) }
    val generatingIds = remember(generations) { generations.values.map { it.conversationId }.toSet() }
    val preparingIds = remember(preparing) { preparing.values.toSet() }
    val busyThisChat = currentConversation.id in generatingIds || currentConversation.id in preparingIds
    val groupedConversationRows = remember(conversations, ThemeStore.language) { groupedConversations(conversations) }
    val conversationDrawerRows = remember(groupedConversationRows) {
        buildList {
            groupedConversationRows.forEach { (label, items) ->
                add(ConversationDrawerRow.Header("header-$label", label))
                items.forEach { conversation ->
                    add(ConversationDrawerRow.Conversation(conversation))
                }
            }
        }
    }
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
    // 缺少接口统计的旧会话也能恢复本地估算；不在主线程或每个流式增量上重算历史。
    LaunchedEffect(currentConversation.id, messages, generatingThisChat, ThemeStore.contextRing) {
        if (generatingThisChat || !ThemeStore.contextRing) return@LaunchedEffect
        val conversationId = currentConversation.id
        val messageSnapshot = messages
        val parts = withContext(Dispatchers.Default) {
            val included = contextMessages(messageSnapshot)
            estimateRequestTokens(modelRequestMessages(included)) to estimateToolContextTokens(included)
        }
        if (currentConversation.id == conversationId && messages === messageSnapshot) {
            contextToolTokens = conversationId to parts.second
            // 接口总量也需要配套的本地分类估算，恢复会话时不能只更新工具。
            liveContextEstimate = conversationId to parts.first
        }
    }
    LaunchedEffect(
        ThemeStore.contextRing,
        activeProviderId,
        selectedModel,
        webSearchEnabled,
        webSearchAvailable,
        contextOverheadNonce,
        providers,
    ) {
        if (!ThemeStore.contextRing) return@LaunchedEffect
        val protocol = providers.firstOrNull { it.id == activeProviderId }?.protocol
            ?: ProviderProtocol.CHAT_COMPLETIONS
        val modelId = selectedModel
        val overhead = withContext(Dispatchers.IO) {
            loadContextRequestOverhead(context, protocol, modelId)
        }
        contextSystemTokens = overhead
    }
    val visibleTranscript = if (presentationReady) transcriptPresentation.visible else emptyList()
    // 压缩记录留在列表里，但只画状态行，不画摘要正文。
    val shownTranscript = visibleTranscript
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
            swapComposer(currentConversation.id, restored.id)
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
                it.isPending && it.toolRecords.isEmpty() && !hasReplyContent(it.text, it.attachments, it.process)
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

    fun updateMessageReaction(conversationId: String, messageId: Long, emoji: String, fromModel: Boolean): Boolean {
        if (clearingHistory) return false
        val stored = if (currentConversation.id == conversationId) {
            currentConversation.copy(messages = messages)
        } else conversationContent(conversationId) ?: return false
        val target = stored.messages.firstOrNull { it.id == messageId && it.selected } ?: return false
        val updated = if (fromModel) setModelReaction(target, emoji) else setUserReaction(target, emoji)
        if (updated == null) return false
        if (updated == target) return true
        reactionAnimations.record(messageId, if (fromModel) updated.modelReaction else updated.userReaction)
        val next = stored.messages.map { if (it.id == messageId) updated else it }
        if (currentConversation.id == conversationId) {
            messages = next
            persistCurrent(messages, touchUpdatedAt = false)
        } else {
            val conversation = stored.copy(messages = next)
            cacheConversationContent(conversation)
            scope.launch(Dispatchers.IO) { ConversationStore.save(context, conversation) }
        }
        return true
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
                loaded.size < uris.size -> tr("有附件无法读取")
                slots > ChatApi.MAX_ATTACHMENT_SLOTS -> tr("附件数量超过 15 个槽位（照片和视频各算半个）")
                bytes > ChatApi.MAX_ATTACHMENT_BYTES_TOTAL -> tr("附件总大小超过 5GB")
                else -> null
            }
        }
    }

    fun pasteTextAsAttachment(text: String): Boolean {
        if (clearingHistory) return false
        if (attachmentSlots(pendingAttachments) + 1f > ChatApi.MAX_ATTACHMENT_SLOTS) {
            composerStatus = tr("附件数量超过 15 个槽位（照片和视频各算半个）")
            return false // Leave the clipboard text to the editor's normal paste path.
        }
        val attachment = LongTextPaste.attachment(File(context.filesDir, "attachments"))
        val conversationId = currentConversation.id
        pendingAttachments = pendingAttachments + attachment
        writingPastedText = writingPastedText + attachment.filePath
        scope.launch {
            try {
                val written = withContext(Dispatchers.IO) {
                    runCatching { LongTextPaste.write(attachment, text) }.isSuccess
                }
                val active = currentConversation.id == conversationId
                val draft = if (active) {
                    ComposerDraft(message.text.toString(), pendingAttachments, editingMessageId, pendingQuote)
                } else {
                    composerDrafts[conversationId]
                }
                val retained = draft != null && attachment in draft.attachments
                val overLimit = retained && attachmentBytes(draft.attachments) > ChatApi.MAX_ATTACHMENT_BYTES_TOTAL
                if (retained && (!written || overLimit)) {
                    val restored = restoreFailedTextPaste(draft, attachment, text)
                    if (active) {
                        pendingAttachments = restored.attachments
                        message.edit { append(text) }
                        composerStatus = if (overLimit) tr("附件总大小超过 5GB")
                            else tr("文本文件创建失败，已保留原文")
                    } else {
                        composerDrafts[conversationId] = restored
                    }
                }
                if (!retained || !written || overLimit) {
                    withContext(Dispatchers.IO) { File(attachment.filePath).delete() }
                }
            } finally {
                writingPastedText = writingPastedText - attachment.filePath
            }
        }
        composerStatus = null
        return true
    }

    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> addAttachments(uris) }
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> addAttachments(uris) }
    val requestSpeechNotification = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) SpeechNotifications.sync(context, SpeechPlayer.playback)
    }
    val requestReplyNotification = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    fun ensureSpeechNotification() {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestSpeechNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            selectedModel = ""
        }
        if (providers.none { it.models.isNotEmpty() }) modelMenuOpen = false
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val currentProviders by rememberUpdatedState(providers)
    val currentActiveId by rememberUpdatedState(activeProviderId)
    val currentConversationId by rememberUpdatedState(currentConversation.id)
    val currentConversationBusy by rememberUpdatedState(currentConversation.id in generatingIds)
    val activeProvider = currentProviders.firstOrNull { it.id == currentActiveId }

    LaunchedEffect(currentActiveId, activeProvider?.endpointUrl, currentConversationId, currentConversationBusy) {
        val provider = activeProvider ?: return@LaunchedEffect
        val scopeId = currentConversationId
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            EndpointWarmup.shouldWarm(provider, currentConversationBusy)
        ) {
            withContext(Dispatchers.IO) { EndpointWarmup.warmBlocking(provider, scopeId) }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // 设置页是独立 Activity；返回时主动重读，确保名称开关无需重启主页即可生效。
                    modelsDevCatalog = ProviderStore.loadModelsDevCatalog(context)
                    modelsDevReasoning = ProviderStore.loadModelsDevReasoning(context)
                    contextCompressionEnabled = ToolStore.loadContextCompression(context)
                    contextCompressionTrigger = ToolStore.loadCompressionTrigger(context)
                    contextOverheadNonce += 1
                    visualizeContext = ToolStore.loadVisualizeContext(context)
                    val provider = currentProviders.firstOrNull { it.id == currentActiveId }
                    val scopeId = currentConversationId
                    if (EndpointWarmup.shouldWarm(provider, currentConversationBusy)) {
                        scope.launch(Dispatchers.IO) { EndpointWarmup.warmBlocking(provider!!, scopeId) }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        currentConversation.id,
        shownTranscript.size,
        shownTranscript.lastOrNull()?.let(::transcriptTailFollowKey),
        newUserMessageId,
        stickToBottom,
        recyclerScrolling,
    ) {
        if (currentConversation.id != seenTranscriptConversationId) {
            seenTranscriptConversationId = currentConversation.id
            seenTranscriptTailId = null
            stickToBottom = true
        }
        val last = shownTranscript.lastOrNull()
        if (last == null) {
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
                userScrollInProgress = recyclerScrolling || messageRecyclerController.isScrollInProgress(),
                force = force,
            )
        ) {
            seenTranscriptTailId = last.id
            return@LaunchedEffect
        }
        if (force) stickToBottom = true
        // 贴底发送要滑过去。新行还没排进屏幕时 scrollToPosition 会先锚到视口顶上，列表就抽一下。
        if (justSent && stickToBottom) {
            messageRecyclerController.revealTail()
        } else if (opening) {
            // 切换/恢复会话：直接落在底部，不要从顶上滚下来。
            messageRecyclerController.openAtBottom()
        } else {
            messageRecyclerController.requestScrollToBottom()
        }
        seenTranscriptTailId = last.id
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
        SpeechPlayer.stop()
        val requestToken = conversationLoadToken + 1
        conversationLoadToken = requestToken
        val previousId = currentConversation.id
        val keepPool = previousId in generatingIds
        // 先关抽屉；正文磁盘读取和 Markdown/TeX 预热都不能阻塞点击反馈。
        drawerProgress = 0f
        scope.launch(Dispatchers.IO) {
            val full = conversationContent(conversation.id) ?: return@launch
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
                ConversationStore.saveLastOpenedId(context, conversation.id)
                if (!keepPool) IrioHttp.evictConversation(previousId)
            }
        }
    }

    LaunchedEffect(ReplyOpenRequest.conversationId, conversations) {
        val id = ReplyOpenRequest.conversationId ?: return@LaunchedEffect
        val summary = conversations.firstOrNull { it.id == id } ?: return@LaunchedEffect
        ReplyOpenRequest.clear()
        ReplyNotifications.cancel(context, id)
        openConversation(summary)
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
        if (closeDrawer) drawerProgress = 0f
        if (!keepPool) IrioHttp.evictConversation(previous.id)
    }

    val processSheetOpen = processSheetState.targetDetent != BottomSheetDetent.Hidden ||
        reactionSheetState.targetDetent != BottomSheetDetent.Hidden
    DismissComposerFocusWhenImeHides()
    val persistSubagent by rememberUpdatedState<(SubagentSnapshot) -> Unit> { snapshot ->
        val json = snapshot.toJson()
        fun apply(source: List<ChatMessage>): List<ChatMessage> = source.map { message ->
            var changed = false
            val process = message.process.map { event ->
                if (event.kind == ProcessEventKind.SUBAGENT && subagentSnapshot(event.content)?.id == snapshot.id) {
                    changed = true
                    event.copy(content = json)
                } else {
                    event
                }
            }
            if (changed) message.copy(process = process) else message
        }
        if (currentConversation.id == snapshot.conversationId) {
            val next = apply(messages)
            if (next != messages) {
                messages = next
                persistCurrent(next)
            }
        } else {
            val stored = ConversationStore.load(context, snapshot.conversationId)
            if (stored != null) {
                val next = apply(stored.messages)
                if (next != stored.messages) {
                    ConversationStore.save(context, stored.copy(messages = next, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }
    DisposableEffect(Unit) {
        SubagentHub.onChanged = { persistSubagent(it) }
        SubagentHub.onFinished = { snapshot ->
            scope.launch(Dispatchers.Main.immediate) { noteSubagentFinished(snapshot) }
        }
        onDispose {
            SubagentHub.onChanged = null
            SubagentHub.onFinished = null
        }
    }
    NavigationBackHandler(
        viewingPhoto = viewingPhoto != null,
        processSheetOpen = processSheetOpen,
        drawerOpen = drawerOpen,
        hasMessages = messages.isNotEmpty(),
        onDismissPhoto = { viewingPhoto = null },
        onDismissSheet = {
            if (reactionSheetState.targetDetent != BottomSheetDetent.Hidden) {
                reactionSheetState.targetDetent = BottomSheetDetent.Hidden
            } else processSheetState.targetDetent = BottomSheetDetent.Hidden
        },
        onCloseDrawer = { drawerProgress = 0f },
        onHideIme = {
            keyboardController?.hide()
            focusManager.clearFocus()
        },
        onNewConversation = { newConversation() },
    )

    BackHandler(enabled = composerFullscreen && !processSheetOpen && viewingPhoto == null && !drawerOpen) {
        composerFullscreen = false
    }

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
        IrioHttp.evictAllScopes()
        ConversationStore.deleteAll(context)
        message.clearText()
        pendingAttachments = emptyList()
        editingMessageId = null
        composerDrafts.clear()
        pendingQuote = ""
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
            ConversationStore.load(context, conversation.id)?.let { stored ->
                ConversationStore.save(context, stored.copy(pinned = nextPinned))
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
            val title = ChatApi.generateTitle(
                provider,
                titleModel,
                userText,
                assistantText,
                connectionScope = "$conversationId#title",
            )
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
        ReplyNotifications.requestPermissionIfNeeded(
            granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        ) {
            requestReplyNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        SpeechPlayer.stop()
        val requestModel = selectedModel
        val effectiveReasoningInfo = if (ProviderStore.loadModelsDevReasoning(context)) {
            ModelCatalog.reasoningInfo(context, requestModel, useCatalog = true)
        } else null
        val requestEffort = ModelCatalog.resolveReasoningEffort(
            reasoningEffort, ModelCatalog.reasoningLevels(effectiveReasoningInfo),
        )
        val requestFast = fastMode
        val requestWebSearch = SearchStore.isWebSearchActive(context)
        val requestSearchProvider = SearchStore.loadProvider(context)
        val requestSearchKey = SearchStore.loadApiKey(context, requestSearchProvider)
        val requestSearchMaxResults = SearchStore.loadMaxResults(context, requestSearchProvider)
        val requestImageSearchKey = SearchStore.loadImageApiKey(context)
        val requestImageSearchMaxResults = SearchStore.loadImageMaxResults(context)
        val requestMaxToolRounds = ToolStore.loadMaxRounds(context)
        val requestSystemPrompt = chatSystemPrompt(context)
        // 双重门槛，任一不满足都不注入 generate_image 工具：
        // ① 对话模型本身是生图模型（它自己能画；且图片渠道普遍不支持 tools，注入必报能力错）；
        // ② 设置里存的生图模型必须在目录判定为真生图模型（过滤规则上线前的旧值——
        //    如曾被选成聊天模型——拿去打 /images/generations 一样报能力错，直接视为未配置）。
        val requestImageGenModel = configuredImageGenModel(context, requestModel)
        val requestRegistry = chatToolRegistry(
            context, requestWebSearch, requestImageGenModel, imageSearch = requestImageSearchKey.isNotBlank(),
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
                GitHubMcpStore.of(context).toolProvider(requestCancellation)?.let(requestRegistry::register)
                TodoistMcpStore.of(context).toolProvider(requestCancellation)?.let(requestRegistry::register)
                val provider = requestProvider
                var streamedText = ""
                var roundBreakPending = false
                var streamedAttachments = emptyList<ChatAttachment>()
                var toolRecords = emptyList<ChatToolRecord>()
                val process = ProcessTracker()
                var notice = tr("响应中")
                fun noticeTitle(): String {
                    val stored = if (currentConversation.id == requestConversationId) {
                        currentConversation.title
                    } else {
                        conversationContent(requestConversationId)?.title ?: "新对话"
                    }
                    return displayConversationTitle(stored)
                }
                fun pushNotice(text: String, finished: Boolean) {
                    if (text == notice && !finished) return
                    notice = text
                    ReplyLiveNotice.publish(context, requestConversationId, noticeTitle(), text, finished)
                }
                pushNotice(notice, finished = false)
                var lastPublishAt = 0L
                var publishedBodyLength = 0
                var publishedThinking = false
                var streamedUsage: TokenUsage? = null
                // 对话模型本身是生图模型：聊天请求整条改走 /images/generations
                // （gpt-image 等纯生图模型在 chat/completions 上游必拒），
                // 最后一条用户消息当 prompt，结果图片作为回复附件回流。非流式，过程行只报生成状态。
                val result = if (provider == null) {
                    ChatCompletionResult(error = tr("请先在设置中添加提供商"))
                } else if (ModelCatalog.isImageModel(context, requestModel)) {
                    val lastUserIndex = requestSource.indexOfLast { it.fromUser && !it.hidden }
                    val prompt = requestSource.getOrNull(lastUserIndex)?.requestText().orEmpty().ifBlank { tr("图片") }
                    // Images API 无状态：多轮要回传上轮 prompt + 生成图。
                    // 上轮 = 本条用户消息之前最近的一次生图往返（上轮用户 prompt + 其后模型产出的图）。
                    val imageContext = if (lastUserIndex > 0) {
                        val previousUserPrompt = requestSource.subList(0, lastUserIndex)
                            .lastOrNull { it.fromUser && !it.hidden }?.requestText().orEmpty()
                        val previousImages = requestSource.subList(0, lastUserIndex)
                            .filter { !it.fromUser }
                            .flatMap { it.attachments.filter { attachment -> attachment.isImage } }
                        if (previousImages.isNotEmpty()) {
                            ProviderApi.ImageGenContext(previousUserPrompt, previousImages.takeLast(3))
                        } else null
                    } else null
                    process.onToolStarted(ImageGenTool.NAME)
                    pushNotice(MemoryTool.liveLabel(ImageGenTool.NAME), finished = false)
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
                val baseContext = withContext(Dispatchers.Default) {
                    estimateRequestTokens(requestMessages) to estimateToolContextTokens(requestSource)
                }
                val baseContextTokens = baseContext.first
                if (currentConversation.id == requestConversationId) {
                    liveContextEstimate = requestConversationId to baseContextTokens
                    contextToolTokens = requestConversationId to baseContext.second
                    liveToolTokens = requestConversationId to 0
                }
                val liveTokenEstimator = LiveReplyTokenEstimator()
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
                        imageSearchApiKey = requestImageSearchKey,
                        imageSearchMaxResults = requestImageSearchMaxResults,
                        maxToolRounds = requestMaxToolRounds,
                        systemPrompt = requestSystemPrompt,
                        imageGenModel = requestImageGenModel,
                        toolRegistry = requestRegistry,
                        connectionScope = modelConversationScope(requestConversationId, requestSource),
                        conversationId = requestConversationId,
                        cancellation = requestCancellation,
                        appContext = context,
                        onReaction = { messageId, emoji ->
                            withContext(Dispatchers.Main.immediate) {
                                !requestCancellation.isCancelled() &&
                                    updateMessageReaction(requestConversationId, messageId, emoji, fromModel = true)
                            }
                        },
                        onUpdate = update@{ update ->
                            update.toolRecord?.let { toolRecords = toolRecords + it }
                            val advanced = advanceReplyNotice(notice, update)
                            if (advanced != notice) {
                                notice = advanced
                                val step = advanced
                                withContext(Dispatchers.Main.immediate) {
                                    ReplyLiveNotice.publish(context, requestConversationId, noticeTitle(), step, finished = false)
                                }
                            }
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
                                if (!cancelled && update.subagentJson.isNotEmpty()) process.onSubagent(update.subagentJson)
                                process.refreshSubagents(SubagentHub.jsonById())
                                if (!cancelled && update.coloredBubbles.isNotEmpty()) process.onColoredBubbles(update.coloredBubbles)
                                if (!cancelled && update.displayedImages.isNotEmpty()) process.onDisplayedImages(update.displayedImages)
                                if (update.attachments.isNotEmpty()) {
                                    streamedAttachments = (streamedAttachments + update.attachments)
                                        .distinctBy { it.filePath }
                                }
                                if (update.usage != null) streamedUsage = update.usage
                                if (update.searching == true && !cancelled) process.onSearchStarted(update.searchQuery, update.displayText)
                                if (update.searching == false) process.onSearchFinished(update.searchPagesDelta, update.searchContent)
                                if (update.toolActive == true && !cancelled) process.onToolStarted(update.toolName, update.webDomain, update.displayText)
                                if (update.toolOutput != null) process.onToolOutput(update.toolOutput)
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
                                toolChanged = update.toolActive != null || update.toolRecord != null || update.coloredBubbles.isNotEmpty() || update.subagentJson.isNotEmpty() || update.displayedImages.isNotEmpty(),
                                toolOutputChanged = update.toolOutput != null,
                                toolCallDeltaLength = update.toolCallDeltas.sumOf { it.name.length + it.argumentsDelta.length },
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
                            val liveTokens = liveTokenEstimator.estimateOnWorker(
                                enabled = ThemeStore.contextRing,
                                streamedText = text,
                                process = snapshot,
                                toolCallText = streamedToolCalls.toString(),
                            )
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
                                        toolRecords = toolRecords,
                                        finishedAt = if (update.done && snapshot.none { it.live }) {
                                            System.currentTimeMillis()
                                        } else {
                                            0L
                                        },
                                        isPending = toolRecords.isEmpty() && !hasReplyContent(text, attachments, snapshot),
                                        isStreaming = (!update.done && !requestCancellation.isCancelled()) ||
                                            snapshot.any { it.live },
                                    )
                                )
                                if (usage != null && currentConversation.id == requestConversationId) {
                                    currentConversation = currentConversation.copy(tokenUsage = usage)
                                }
                                if (liveTokens != null && currentConversation.id == requestConversationId) {
                                    liveContextEstimate = requestConversationId to (baseContextTokens + liveTokens.first)
                                    liveToolTokens = requestConversationId to liveTokens.second
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
            val finishedAt = current?.finishedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
            val finalMessage = when {
                result.stopped -> {
                    val finalText = streamedText.ifBlank { current?.text.orEmpty() }
                    val finalAttachments = streamedAttachments.ifEmpty { current?.attachments.orEmpty() }
                    if (toolRecords.isEmpty() && !hasReplyContent(finalText, finalAttachments, finalProcess)) {
                        null
                    } else {
                        reply.copy(
                            text = finalText,
                            process = finalProcess.map { it.copy(live = false) },
                            attachments = finalAttachments,
                            usage = finalUsage,
                            toolRecords = toolRecords,
                            finishedAt = finishedAt,
                            isPending = false,
                            isStreaming = false,
                        )
                    }
                }
                result.error != null -> reply.copy(
                    text = result.error,
                    process = finalProcess.map { it.copy(live = false) },
                    toolRecords = toolRecords,
                    usage = finalUsage,
                    finishedAt = finishedAt,
                    isError = true,
                    isPending = false,
                    isStreaming = false,
                )
                else -> reply.copy(
                    text = settledAssistantText(streamedText, result.content),
                    process = finalProcess.map { it.copy(live = false) },
                    toolRecords = toolRecords,
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
                            ReplyLiveNotice.dismiss(context, requestConversationId)
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
                                userText = requestSource.lastOrNull { it.fromUser && !it.hidden }?.text.orEmpty(),
                                assistantText = finalMessage.text,
                            )
                            val completed = result.error == null && !result.stopped
                            if (completed) {
                                val preview = replyNotificationPreview(finalMessage.text).ifEmpty {
                                    if (finalMessage.attachments.any { it.isImage }) tr("已生成图片") else ""
                                }
                                pushNotice(preview.ifEmpty { tr("完成") }, finished = true)
                            } else {
                                ReplyLiveNotice.dismiss(context, requestConversationId)
                            }
                            if (
                                result.error == null && !result.stopped &&
                                currentConversation.id == requestConversationId &&
                                SpeechStore.loadAutoRead(context)
                            ) {
                                val spoken = speechPlainText(finalMessage.visibleText())
                                if (spoken.isNotBlank()) {
                                    ensureSpeechNotification()
                                    SpeechPlayer.speak(context, finalMessage.id, spoken)
                                }
                            }
                        }
                    }
                    appliedFinal = true
                }
                generations = generations - reply.id
                flushBackgroundNotices(requestConversationId)
            }
            } finally {
                if (!appliedFinal) {
                    generations = generations - reply.id
                    flushBackgroundNotices(requestConversationId)
                }
            }
        }
        generations = generations + (reply.id to ActiveGeneration(
            conversationId = requestConversationId,
            job = job,
            cancellation = requestCancellation,
        ))
    }


    fun compressionModelId(): String {
        val configured = ToolStore.loadCompressionModel(context)
        return when {
            configured.isNotBlank() -> configured
            !ModelCatalog.isImageModel(context, selectedModel) -> selectedModel
            else -> ProviderStore.loadLastChatModel(context)
        }
    }

    suspend fun runSummary(
        plan: CompressionPlan,
        conversationId: String,
        cancellation: ChatRequestCancellation,
    ): String? {
        val model = compressionModelId()
        if (model.isBlank()) return null
        val provider = ProviderStore.loadProviders(context)
            .firstOrNull { model in it.models }
            ?: ProviderStore.loadProvider(context)
            ?: return null
        val window = ModelCatalog.contextWindow(context, selectedModel)
        val target = ToolStore.loadCompressionTarget(context)
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
            conversationContent(conversationId)?.messages.orEmpty()
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
        // 同一回复只预检一次；占位同步写入，调用方 guards 据此拦截重复提交。
        if (reply.id in preparing) return
        preparing = preparing + (reply.id to conversationId)
        val requestModel = selectedModel
        val compressionEnabled = ToolStore.loadContextCompression(context)
        val compressionTrigger = ToolStore.loadCompressionTrigger(context)
        scope.launch {
            try {
                // jtokkit 估算 + 压缩决策是发送路径最重的同步块，放到后台线程。
                // source 是调用方传入的不可变快照，跨线程共享安全。
                val plan = withContext(Dispatchers.Default) {
                    val window = ModelCatalog.contextWindow(context, requestModel)
                    val estimate = estimateRequestTokens(modelRequestMessages(source))
                    val compress = !ModelCatalog.isImageModel(context, requestModel) &&
                        shouldAutoCompress(
                            enabled = compressionEnabled,
                            windowTokens = window,
                            triggerPercent = compressionTrigger,
                            estimatedTokens = estimate,
                        )
                    val covered = if (source.lastOrNull()?.fromUser == true) source.dropLast(1) else source
                    if (compress) planCompression(covered) else null
                }
                if (plan == null) {
                    startGeneration(conversationId, source, reply)
                    return@launch
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
                            flushBackgroundNotices(conversationId)
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
            } finally {
                preparing = preparing - reply.id
            }
        }
    }

    /** 不等触发线，也不接着生成回复。当前可见历史整段压成一道边界。 */
    fun compressNow() {
        if (busyThisChat) return
        val conversationId = currentConversation.id
        val snapshot = messages
        val cardId = MessageIds.next()
        preparing = preparing + (cardId to conversationId)
        scope.launch {
            try {
                val plan = withContext(Dispatchers.Default) {
                    planCompression(
                        contextMessages(snapshot).filterNot { it.isError || it.isPending || it.isStreaming },
                    )
                } ?: return@launch
                if (clearingHistory) return@launch
                val card = ChatMessage(
                    id = cardId,
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
                        flushBackgroundNotices(conversationId)
                    }
                }
                generations = generations + (card.id to ActiveGeneration(
                    conversationId = conversationId,
                    job = job,
                    cancellation = cancellation,
                ))
            } finally {
                preparing = preparing - cardId
            }
        }
    }

    fun deliverBackgroundNotices(conversationId: String, commands: List<LinuxBackgroundCommand>) {
        if (clearingHistory || commands.isEmpty()) return
        val stored = if (currentConversation.id == conversationId) {
            currentConversation.copy(messages = messages)
        } else {
            conversationContent(conversationId) ?: return
        }
        val notice = ChatMessage(
            id = MessageIds.next(),
            text = linuxBackgroundCompletionText(commands),
            fromUser = true,
            hidden = true,
        )
        val reply = ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            isPending = true,
            responseToUserId = notice.id,
        )
        val next = messagesOf(conversationId) + notice + reply
        if (currentConversation.id == conversationId) {
            messages = next
            persistCurrent(messages)
        } else {
            val updated = stored.copy(
                messages = next,
                updatedAt = System.currentTimeMillis(),
                title = if (stored.titlePending) ConversationStore.titleFrom(next) else stored.title,
            )
            cacheConversationContent(updated)
            conversations = conversations
                .filterNot { it.id == updated.id }
                .plus(updated.summary())
                .let { list ->
                    val sorted = list.sortedWith(
                        compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt },
                    )
                    if (sorted == list) list else sorted
                }
            scope.launch(Dispatchers.IO) { ConversationStore.save(context, updated) }
        }
        val history = contextMessages(next).filterNot { it.isError || it.isPending }
        sendOrCompress(conversationId, history, reply)
    }

    fun deliverSubagentResults(conversationId: String, finished: List<SubagentSnapshot>) {
        if (clearingHistory || finished.isEmpty()) return
        val stored = if (currentConversation.id == conversationId) {
            currentConversation.copy(messages = messages)
        } else {
            conversationContent(conversationId) ?: return
        }
        val notice = ChatMessage(
            id = MessageIds.next(),
            text = finished.joinToString("\n\n") { snapshot ->
                val output = snapshot.finalOutputText()
                if (snapshot.state == SubagentState.COMPLETED) {
                    tr("子智能体「%s」已完成。最终输出：\n%s").format(snapshot.name, output.ifBlank { tr("（没有文本输出）") })
                } else {
                    tr("子智能体「%s」失败或已被停止。已有输出：\n%s").format(snapshot.name, output.ifBlank { tr("（没有文本输出）") })
                }
            },
            fromUser = true,
            hidden = true,
        )
        val reply = ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            isPending = true,
            responseToUserId = notice.id,
        )
        val next = messagesOf(conversationId) + notice + reply
        if (currentConversation.id == conversationId) {
            messages = next
            persistCurrent(messages)
        } else {
            val updated = stored.copy(
                messages = next,
                updatedAt = System.currentTimeMillis(),
                title = if (stored.titlePending) ConversationStore.titleFrom(next) else stored.title,
            )
            cacheConversationContent(updated)
            conversations = conversations
                .filterNot { it.id == updated.id }
                .plus(updated.summary())
                .let { list ->
                    val sorted = list.sortedWith(
                        compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt },
                    )
                    if (sorted == list) list else sorted
                }
            scope.launch(Dispatchers.IO) { ConversationStore.save(context, updated) }
        }
        val history = contextMessages(next).filterNot { it.isError || it.isPending }
        sendOrCompress(conversationId, history, reply)
    }

    backgroundNoticeSink.deliver = { conversationId, commands ->
        deliverBackgroundNotices(conversationId, commands)
    }
    subagentResultSink.deliver = { conversationId, finished ->
        deliverSubagentResults(conversationId, finished)
    }

    /**
     * 入场淡入只播一次：260ms 动画结束后标记即失去意义。不清掉的话，
     * 该行组合被回收重建（滚远再滚回、重进会话）时会从透明重新播一遍，造成闪烁。
     * 只清自己那次发送的标记，300ms 内重发不会误清新的。
     */
    fun clearEntranceMarkerAfterDelay(id: Long) {
        scope.launch {
            delay(300)
            if (newUserMessageId == id) newUserMessageId = null
        }
    }

    fun submitMessage() {
        if (pendingAttachments.any { it.filePath in writingPastedText }) return
        val text = message.text.toString().trim()
        if (busyThisChat || (text.isEmpty() && pendingAttachments.isEmpty())) return
        val attachments = pendingAttachments
        val userMessage = ChatMessage(
            id = MessageIds.next(),
            text = text,
            fromUser = true,
            attachments = attachments,
            quote = pendingQuote,
        )
        newUserMessageId = userMessage.id
        clearEntranceMarkerAfterDelay(userMessage.id)
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
        pendingQuote = ""
        message.clearText()
        sendOrCompress(currentConversation.id, history + userMessage, reply)
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    /** 点击用户气泡：文本与附件带回输入框进入编辑模式（仅最新一条用户消息）。 */
    fun beginEdit(target: ChatMessage) {
        if (busyThisChat) return
        if (target.id != visibleMessages(messages).lastOrNull { it.fromUser }?.id) return
        editingMessageId = target.id
        message.edit {
            replace(0, length, target.text)
        }
        pendingAttachments = target.attachments
        pendingQuote = target.quote
        // 清掉本就待发的内容可能带来的混淆状态
        composerStatus = null
        // 直接编辑：拉起输入法并聚焦输入框
        composerFocusRequester.requestFocus()
        keyboardController?.show()
    }

    /** 退出编辑模式（不保存）。 */
    fun cancelEdit() {
        editingMessageId = null
        pendingQuote = ""
        message.clearText()
        pendingAttachments = emptyList()
    }

    /**
     * 提交编辑：新版本插回原消息位置（而不是列表尾），旧版本取消选中；
     * 紧随其后的模型回复组取消选中，新 pending 回复进同一个 retryGroup 并启动重生成。
     * 版本切换（showEditVersion）与回复切换（showRetryVersion）独立，各自切换各自组。
     */
    fun commitEdit() {
        if (pendingAttachments.any { it.filePath in writingPastedText }) return
        if (busyThisChat) return
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
            quote = pendingQuote,
        )
        newUserMessageId = committed.history.last().id
        clearEntranceMarkerAfterDelay(committed.history.last().id)
        messages = committed.messages
        persistCurrent(messages)
        message.clearText()
        pendingAttachments = emptyList()
        editingMessageId = null
        pendingQuote = ""
        keyboardController?.hide()
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

    fun retryMessage(assistant: ChatMessage) {
        if (assistant.fromUser || assistant.isPending || assistant.isStreaming) return
        if (currentConversation.id in preparingIds) return
        generations[assistant.id]?.cancellation?.cancel()
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

    // 模型选单开关会重组这一页。callbacks 若每次都是新对象，可见消息行会全部重绘，
    // 毛玻璃源跟着失效。只在行上真能看见的状态变化时才换一份。
    val editAnchorId = visibleTranscript.lastOrNull { it.fromUser && it.summaryState == SummaryState.NONE }?.id
    val contextBoundaryId = visibleTranscript.lastOrNull { it.summaryState == SummaryState.SUCCESS }?.id
    val messageCallbacks = remember(
        newUserMessageId,
        editingMessageId,
        editAnchorId,
        retryGroups,
        editGroups,
        contextBoundaryId,
        visualizeContext,
    ) {
        AndroidMessageCallbacks(
        markdownScope = scope,
        onOpenProcess = { messageId, event ->
            processSheetMessageId = messageId
            processSheetEventId = event.id
            processSheetState.targetDetent = BottomSheetDetent.FullyExpanded
        },
        onOpenAttachment = ::openAttachment,
        onCopy = { chatMessage -> clipboard.setText(AnnotatedString(chatMessage.visibleText())) },
        onQuote = { excerpt ->
            pendingQuote = excerpt
            composerFocusRequester.requestFocus()
            keyboardController?.show()
        },
        onAddReaction = { chatMessage ->
            reactionTarget = currentConversation.id to chatMessage.id
            keyboardController?.hide()
            focusManager.clearFocus()
            reactionSheetState.targetDetent = BottomSheetDetent.FullyExpanded
        },
        onRemoveReaction = { chatMessage ->
            updateMessageReaction(currentConversation.id, chatMessage.id, "", fromModel = false)
        },
        reactionAnimations = reactionAnimations,
        onRetry = ::retryMessage,
        canRetry = { chatMessage ->
            chatMessage.summaryState == SummaryState.NONE &&
                !chatMessage.fromUser && !chatMessage.isPending && !chatMessage.isStreaming
        },
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
        newUserMessageId = newUserMessageId,
        outsideContext = { chatMessage ->
            visualizeContext && isBeforeCompression(visibleTranscript, chatMessage.id)
        },
        )
    }

    // 抽屉、输入栏、顶栏各自是独立组合函数：MainScreen 本体曾用到 259 个寄存器，
    // 超过 ART 单方法 256 的上限，打开就会 VerifyError。
    @Composable
    fun ConversationDrawer() {
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
                AndroidComposeRecyclerList(
                    items = conversationDrawerRows,
                    key = { it.key },
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                    contentPadding = PaddingValues(bottom = 72.dp),
                    itemSpacing = 2.dp,
                ) {
                    when (val row = it) {
                        is ConversationDrawerRow.Header -> {
                            Text(
                                row.text,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                color = muted,
                                fontSize = 12.sp,
                            )
                        }
                        is ConversationDrawerRow.Conversation -> {
                            val conversation = row.summary
                            val generating = conversation.id in generatingIds
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
                                    contentPadding = if (generating) {
                                        PaddingValues(start = 16.dp, end = 56.dp)
                                    } else DrawerRowPadding,
                                    icon = if (conversation.pinned) Icons.Rounded.PushPin else null,
                                    active = conversation.id == currentConversation.id,
                                    modifier = Modifier.generatingRipple(generating),
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
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        },
                        // 空抽屉没有可删除的历史会话，不提供“删除全部历史”的长按入口。
                        onLongClick = conversations
                            .takeIf { it.isNotEmpty() }
                            ?.let { { clearHistoryDialogOpen = true } },
                    )
                }
            }
        }
    }

    @Composable
    fun BoxScope.ChatComposerBar(availableHeight: Dp) {
        val compactHeight = with(LocalDensity.current) { compactComposerHeightPx.toDp() }
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val canSend = pendingAttachments.none { it.filePath in writingPastedText } &&
            ((editingMessageId != null && (message.text.isNotBlank() || pendingAttachments.isNotEmpty())) ||
                (!generatingThisChat && editingMessageId == null && (message.text.isNotBlank() || pendingAttachments.isNotEmpty())))

        @Composable
        fun AttachmentButton() {
            DropdownMenu(
                expanded = attachMenuOpen,
                onExpandedChange = { attachMenuOpen = it },
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
                        ) { Text(tr("照片")) }
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
                        ) { Text(tr("文件")) }
                    }
                },
            ) {
                IconButton(
                    onClick = { attachMenuOpen = !attachMenuOpen },
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
                        tint = content,
                    )
                }
            }
        }

        @Composable
        fun SttButton() {
            AccentCircleIconButton(
                onClick = ::toggleSttRecording,
                imageVector = if (sttRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = if (sttRecording) tr("停止录音") else tr("语音输入"),
                enabled = !sttTranscribing,
                active = !sttTranscribing,
                iconSize = 20.dp,
            )
        }

        @Composable
        fun SendButton() {
            val sendActive = generatingThisChat || canSend
            if (!generatingThisChat && !canSend) {
                SttButton()
                return
            }
            AccentCircleIconButton(
                onClick = {
                    if (!generatingThisChat && canSend) composerFullscreen = false
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

        @Composable
        fun AttachmentChips() {
            if (pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingAttachments.forEach { attachment ->
                        AttachmentCard(
                            attachment = attachment,
                            loading = attachment.filePath in writingPastedText,
                            onRemove = {
                                pendingAttachments = pendingAttachments.filterNot { it == attachment }
                            },
                        )
                    }
                }
            }
        }

        @Composable
        fun RecordingComposerField(
            expansionProgress: androidx.compose.runtime.State<Float>,
            modifier: Modifier = Modifier,
        ) {
            val shape = Theme[shapes][fieldShape]
            val waveColor = Theme[colors][primaryColor]
            val fillColor = Theme[colors][fieldColor]
            val outlineColor = Theme[colors][borderColor]
            val glass = GlassSurface.COMPOSER in ThemeStore.glassSurfaces
            val totalSeconds = (sttRecordingElapsedMillis / 1_000L).toInt()
            val timer = "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(modifier)
                    .composerAnimatedPadding(
                        progress = expansionProgress,
                        start = 16.dp,
                        end = 16.dp,
                        top = 10.dp,
                        bottom = 10.dp,
                    )
                    .heightIn(min = 64.dp)
                    .irioSolidOrGlass(glass, fillColor, shape)
                    .border(1.dp, outlineColor, shape)
                    .clip(shape)
                    .semantics { contentDescription = "${tr("正在录音")} $timer" },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(Color(0xFFFF453A), CircleShape),
                    )
                    Text(timer, color = content, fontSize = 13.sp)
                    Canvas(
                        Modifier
                            .weight(1f)
                            .height(32.dp),
                    ) {
                        val barWidth = 2.dp.toPx()
                        val gap = 3.dp.toPx()
                        val slotWidth = barWidth + gap
                        val barCount = (size.width / slotWidth).toInt().coerceAtLeast(1)
                        val values = sttWaveformLevels.takeLast(barCount)
                        val centerY = size.height / 2f
                        values.forEachIndexed { index, value ->
                            val x = size.width - (values.size - index - 0.5f) * slotWidth
                            val height = (3.dp.toPx() + value * 24.dp.toPx()).coerceAtMost(size.height)
                            drawLine(
                                color = waveColor,
                                start = Offset(x, centerY - height / 2f),
                                end = Offset(x, centerY + height / 2f),
                                strokeWidth = barWidth,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    SttButton()
                }
            }
        }

        AnimatedComposerContainer(
            expanded = composerFullscreen,
            availableHeight = availableHeight,
            compactHeight = compactHeight,
            hazeState = chatHazeState,
            onCompactHeightChanged = { compactComposerHeightPx = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { expansion, expandedLayout ->
            Spacer(Modifier.composerAnimatedHeight(expansion, ChatComposerHazeBleed, 0.dp))
            Column(
                modifier = Modifier
                    .then(
                        if (expandedLayout) {
                            // 状态栏留白随展开进度渐入，避免第一帧整体下压。
                            Modifier.weight(1f).composerAnimatedPadding(expansion, expandedTop = statusBarTop)
                        } else {
                            Modifier
                        },
                    )
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).exclude(WindowInsets.ime),
                    )
                    .onSizeChanged { if (!expandedLayout) composerBarHeightPx = it.height },
            ) {
                if (!expandedLayout) AttachmentChips()
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
                    val editFill = Theme[colors][primaryColor]
                    val editContent = Theme[colors][onPrimaryColor]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .background(
                                color = editFill,
                                shape = RoundedRectangle(10.dp),
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
                LinuxBackgroundBar(onOpen = { id ->
                    backgroundCommandId = id
                    LinuxBackgroundJobs.find(id.toString())?.let { job ->
                        backgroundSheetEvent = linuxBackgroundTerminalEvent(job)
                    }
                    backgroundSheetState.targetDetent = BottomSheetDetent.FullyExpanded
                })
                if (pendingQuote.isNotBlank()) {
                    MessageQuotePreview(
                        quote = pendingQuote,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        onCancel = { pendingQuote = "" },
                    )
                }

                AnimatedContent(
                    targetState = sttRecording,
                    transitionSpec = {
                        (fadeIn(tween(180)) + expandVertically(
                            expandFrom = Alignment.CenterVertically,
                            animationSpec = tween(220),
                        )).togetherWith(
                            fadeOut(tween(120)) + shrinkVertically(
                                shrinkTowards = Alignment.CenterVertically,
                                animationSpec = tween(180),
                            ),
                        ).using(SizeTransform(clip = false))
                    },
                    label = "composer-recording-transition",
                ) { recording ->
                    if (recording) {
                        RecordingComposerField(
                            expansionProgress = expansion,
                            modifier = if (expandedLayout) Modifier.weight(1f) else Modifier,
                        )
                    } else ComposerField(
                        state = message,
                        onPasteAsAttachment = ::pasteTextAsAttachment,
                        placeholder = "Message",
                        focusRequester = composerFocusRequester,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (expandedLayout) Modifier.weight(1f) else Modifier)
                            .composerAnimatedPadding(
                                progress = expansion,
                                start = 16.dp,
                                end = 16.dp,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        accessibilityLabel = "Message",
                        expanded = expandedLayout,
                        expansion = expansion,
                        onTextLayout = { if (!expandedLayout) composerLineCount = it.lineCount },
                        lineLimits = TextFieldLineLimits.MultiLine(
                            minHeightInLines = 1,
                            maxHeightInLines = if (expandedLayout) Int.MAX_VALUE else 4,
                        ),
                        onEnterSend = if (ThemeStore.enterToSend) {
                            {
                                if (!generatingThisChat && canSend) {
                                    composerFullscreen = false
                                    if (editingMessageId != null) commitEdit() else submitMessage()
                                }
                            }
                        } else {
                            null
                        },
                        leading = { AttachmentButton() },
                        trailing = {
                            Column(
                                modifier = if (composerLineCount >= 4) Modifier.height(with(LocalDensity.current) { 80.sp.toDp() }) else Modifier,
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (composerLineCount >= 4) {
                                    IconButton(
                                        onClick = {
                                            attachMenuOpen = false
                                            composerFullscreen = true
                                        },
                                        style = ButtonStyle.Ghost,
                                        buttonSize = ButtonSize.Small,
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Rounded.OpenInFull, contentDescription = tr("全屏输入"), modifier = Modifier.size(18.dp), tint = content)
                                    }
                                }
                                SendButton()
                            }
                        },
                        expandedFooter = {
                            Column(Modifier.fillMaxWidth()) {
                                AttachmentChips()
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    AttachmentButton()
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { composerFullscreen = false },
                                            style = ButtonStyle.Ghost,
                                            buttonSize = ButtonSize.Small,
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(Icons.Rounded.CloseFullscreen, contentDescription = tr("收起全屏输入"), modifier = Modifier.size(20.dp), tint = content)
                                        }
                                        SendButton()
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    @Composable
    fun BoxScope.ChatToolbar() {
        val modelPickerOnLeft = ThemeStore.modelPickerOnLeft

        @Composable
        fun ToolbarModelPicker() {
            ToolbarCapsule(glass = GlassSurface.MODEL_SWITCHER in ThemeStore.glassSurfaces) {
                ModelPicker(
                    alignLeft = modelPickerOnLeft,
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
                    autoRead = autoRead,
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
                    onAutoReadChange = { enabled ->
                        autoRead = enabled
                        SpeechStore.saveAutoRead(context, enabled)
                    },
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
                        ToolbarCapsule(glass = GlassSurface.SIDEBAR_BUTTON in ThemeStore.glassSurfaces) {
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
                        if (modelPickerOnLeft) ToolbarModelPicker()
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
                        val liveEstimate = liveContextEstimate.takeIf { it.first == currentConversation.id }?.second ?: 0
                        val generatingReply = generatingThisChat && !compressingThisChat
                        val occupied = contextRingOccupied(
                            apiUsed = apiUsed,
                            liveEstimate = liveEstimate,
                            generatingReply = generatingReply,
                            compressionEstimate = compressionUsageEstimate(messages),
                        )
                        if (showContextRing) {
                            val showCompression = contextCompressionEnabled
                            val windowTokens = ModelCatalog.contextWindow(context, selectedModel)
                            val historicalTools = contextToolTokens.takeIf { it.first == currentConversation.id }?.second ?: 0
                            val streamingTools = if (generatingReply) {
                                liveToolTokens.takeIf { it.first == currentConversation.id }?.second ?: 0
                            } else {
                                0
                            }
                            val breakdown = contextUsageBreakdown(
                                windowTokens = windowTokens,
                                occupiedTokens = occupied.tokens,
                                occupiedIncludesOverhead = occupied.includesOverhead,
                                systemTokens = contextSystemTokens,
                                toolTokens = historicalTools + streamingTools,
                                messageTokens = (liveEstimate - historicalTools - streamingTools).coerceAtLeast(0),
                                reservedBudget = contextReservedBudget(
                                    windowTokens = windowTokens,
                                    compressionEnabled = showCompression,
                                    triggerPercent = contextCompressionTrigger,
                                ),
                            )
                            val canCompress = showCompression && !generatingThisChat && planCompression(
                                contextMessages(messages).filterNot {
                                    it.isError || it.isPending || it.isStreaming
                                },
                            ) != null
                            ToolbarCapsule(glass = GlassSurface.CONTEXT_RING in ThemeStore.glassSurfaces) {
                                ContextUsageRing(
                                    breakdown = breakdown,
                                    compressionCount = compressionCount(messages),
                                    showCompression = showCompression,
                                    compressEnabled = canCompress,
                                    onCompress = ::compressNow,
                                    expanded = contextRingOpen,
                                    onExpandedChange = { contextRingOpen = it },
                                )
                            }
                        }
                        if (!modelPickerOnLeft) ToolbarModelPicker()
                    },
                )
                    Spacer(Modifier.height(ChatToolbarHazeBleed))
                }
    }

    CompositionLocalProvider(LocalChatHazeState provides chatHazeState) {
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
        ConversationDrawer()

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
                    .padding(paddingValues),
            ) {
                // 背景图固定铺满聊天页，不跟键盘一起重新缩放，避免出现黑边。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(background),
                ) {
                    ChatBackgroundImage()
                }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                AndroidMessageList(
                    messages = shownTranscript,
                    controller = messageRecyclerController,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(chatHazeState)
                        .then(if (ChatBackgroundEnabled) Modifier else Modifier.background(background))
                        .padding(horizontal = 16.dp),
                    contentPaddingTop = toolbarContentPaddingPx,
                    contentPaddingBottom = composerContentPaddingPx,
                    callbacks = messageCallbacks,
                    onVisibleRangeChanged = { first, last ->
                        val snapshot = shownTranscript
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
                    onUserScrollSample = { scrollDeltaY ->
                        if (!messageRecyclerController.isProgrammaticScroll()) {
                            val pinned = if (scrollDeltaY > 0) {
                                messageRecyclerController.isPinnedToBottom(transcriptBottomSlackPx)
                            } else {
                                null
                            }
                            stickToBottom = transcriptBottomLockAfterUserScroll(stickToBottom, scrollDeltaY, pinned)
                        }
                    },
                )

                ChatComposerBar(maxHeight)

                ChatToolbar()
                IrioSnackbar(
                    visible = sttProviderSnackbarVisible,
                    message = tr("请先在「TTS/STT」中配置 STT 提供商"),
                    bottomInset = composerBarHeight + 16.dp,
                    onDismiss = { sttProviderSnackbarVisible = false },
                    restartKey = sttProviderSnackbarRestartKey,
                )
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
            event = messages.firstOrNull { it.id == processSheetMessageId }
                ?.process
                ?.firstOrNull { it.id == processSheetEventId },
            state = processSheetState,
            onDismiss = {
                processSheetState.targetDetent = BottomSheetDetent.Hidden
            },
        )
        ReactionEmojiSheet(
            state = reactionSheetState,
            onDismiss = { reactionSheetState.targetDetent = BottomSheetDetent.Hidden },
            onSelect = { emoji ->
                reactionTarget?.let { (conversationId, messageId) ->
                    updateMessageReaction(conversationId, messageId, emoji, fromModel = false)
                }
                reactionSheetState.targetDetent = BottomSheetDetent.Hidden
            },
        )
        TerminalCommandSheet(
            event = backgroundSheetEvent ?: ProcessEvent(
                id = 0,
                kind = ProcessEventKind.TOOL,
                toolName = LinuxShellTool.NAME,
                content = "$",
            ),
            state = backgroundSheetState,
            onDismiss = {
                backgroundSheetState.targetDetent = BottomSheetDetent.Hidden
            },
        )
    }
    }
}

private sealed interface ConversationDrawerRow {
    val key: String

    data class Header(override val key: String, val text: String) : ConversationDrawerRow
    data class Conversation(val summary: ConversationSummary) : ConversationDrawerRow {
        override val key: String = summary.id
    }
}

/** 模型列表的预计算行：文本与目标模型在 remember 里一次算好，滚动路径零文本处理。 */
internal sealed interface ModelPickerRow {
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
    alignLeft: Boolean,
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
    autoRead: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectedModelChange: (model: String, providerId: String) -> Unit,
    onReasoningChange: (ReasoningEffort) -> Unit,
    onFastModeChange: (Boolean) -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
    onAutoReadChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
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
    val reasoningInfo = remember(selectedModel, useModelsDevReasoning) {
        ModelCatalog.reasoningInfo(context, selectedModel, useCatalog = useModelsDevReasoning)
    }
    val reasoningLevels = remember(reasoningInfo) { ModelCatalog.reasoningLevels(reasoningInfo) }
    val effectiveEffort = ModelCatalog.resolveReasoningEffort(reasoningEffort, reasoningLevels)
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.wrapContentWidth(if (alignLeft) Alignment.Start else Alignment.End),
        alignment = if (alignLeft) DropdownMenuAlignment.Start else DropdownMenuAlignment.End,
        panel = {
            ModelSwitcherPanel(
                selectedDisplay = selectedDisplay,
                selectedModel = selectedModel,
                activeProviderId = activeProviderId,
                rows = rows,
                reasoningEffort = effectiveEffort,
                supportsThinking = reasoningInfo?.supports ?: true,
                reasoningLevels = reasoningLevels,
                fastMode = fastMode,
                webSearchAvailable = webSearchAvailable,
                webSearchEnabled = webSearchEnabled,
                autoRead = autoRead,
                onSelectedModelChange = onSelectedModelChange,
                onReasoningChange = onReasoningChange,
                onFastModeChange = onFastModeChange,
                onWebSearchChange = onWebSearchChange,
                onAutoReadChange = onAutoReadChange,
            )
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
                selectedDisplay.ifBlank { tr("选择模型") },
                modifier = Modifier.widthIn(max = 180.dp),
                fontSize = 15.sp,
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

private val UserMessageAvatarSize = 28.dp
private val UserMessageAvatarGap = 8.dp
private val UserBubblePaddingVertical = 10.dp
private val UserBubbleLineHeight = 22.sp

/** 头像中心对齐气泡第一行。不看气泡总高度，所以多行时不会落到最后一行旁边。 */
internal fun userMessageAvatarTopOffsetPx(
    bubblePaddingTopPx: Float,
    firstLineHeightPx: Float,
    avatarSizePx: Float,
): Float {
    if (firstLineHeightPx <= 0f || avatarSizePx <= 0f) return 0f
    val firstLineCenter = bubblePaddingTopPx.coerceAtLeast(0f) + firstLineHeightPx / 2f
    return (firstLineCenter - avatarSizePx / 2f).coerceAtLeast(0f)
}

@Composable
private fun userMessageAvatarTopOffset(): Dp {
    val density = LocalDensity.current
    return with(density) {
        userMessageAvatarTopOffsetPx(
            UserBubblePaddingVertical.toPx(),
            UserBubbleLineHeight.toPx(),
            UserMessageAvatarSize.toPx(),
        ).toDp()
    }
}

/**
 * 用户消息内容贴右。打开头像时，头像占右侧一列，气泡变窄，避免顶出屏幕。
 * [besideFirstLine] 为真时头像对准第一行，而不是整块内容的底边。
 */
@Composable
private fun UserMessageSlot(
    showAvatar: Boolean,
    besideFirstLine: Boolean,
    maxFraction: Float = 0.85f,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slot = if (showAvatar) UserMessageAvatarSize + UserMessageAvatarGap else 0.dp
        val maxContent = (maxWidth * maxFraction - slot).coerceAtLeast(0.dp)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = slot)
                .widthIn(max = maxContent),
        ) {
            content()
        }
        if (showAvatar) {
            UserMessageAvatar(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (besideFirstLine) userMessageAvatarTopOffset() else 0.dp),
            )
        }
    }
}

@Composable
private fun UserMessageAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(UserMessageAvatarSize)
            .clip(CircleShape)
            .background(Theme[colors][primaryColor])
            .semantics { contentDescription = tr("头像") },
    ) {
        ProfileStore.avatar?.let { avatar ->
            Image(
                bitmap = avatar,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
internal fun MessageRow(
    chatMessage: ChatMessage,
    reactionAnimations: ReactionBounceTracker,
    newUserMessageId: Long? = null,


    markdownScope: CoroutineScope,
    onOpenProcess: (ProcessEvent) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onCopy: () -> Unit,
    onQuote: (String) -> Unit,
    onAddReaction: () -> Unit,
    onRemoveReaction: () -> Unit,
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
    val context = LocalContext.current
    val requestSpeechNotification = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) SpeechNotifications.sync(context, SpeechPlayer.playback)
    }
    val spoken = remember(chatMessage.id, chatMessage.text, chatMessage.process) { speechPlainText(chatMessage.visibleText()) }
    val reactionBounce = rememberReactionBounce(
        chatMessage.id,
        if (chatMessage.fromUser) chatMessage.modelReaction else chatMessage.userReaction,
        reactionAnimations,
    )
    val speech = SpeechPlayer.playback
    val isNewUserMessage = chatMessage.fromUser && chatMessage.id == newUserMessageId

    val entranceProgress = rememberMessageEntranceAlpha(chatMessage.id, isNewUserMessage)
    // Observe opacity in composition so finishing the fade removes its temporary layer.
    // A retained transparent RenderNode must not outlive a recycled row's entrance.
    val rowAlpha = (if (isNewUserMessage) entranceProgress.value else 1f) * (if (outsideContext) 0.45f else 1f)
    val alignment = if (chatMessage.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (rowAlpha < 1f) Modifier.graphicsLayer(alpha = rowAlpha) else Modifier),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (chatMessage.fromUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (chatMessage.fromUser) {
                val showAvatar = ThemeStore.showUserAvatar
                val hasText = chatMessage.text.isNotBlank()
                val hasAttachments = chatMessage.attachments.isNotEmpty()
                if (chatMessage.quote.isNotBlank()) {
                    if (showAvatar && !hasText && !hasAttachments) {
                        UserMessageSlot(showAvatar = true, besideFirstLine = false) {
                            MessageQuotePreview(quote = chatMessage.quote)
                        }
                    } else {
                        MessageQuotePreview(
                            quote = chatMessage.quote,
                            modifier = Modifier.fillMaxWidth(0.85f),
                        )
                    }
                }
                if (hasAttachments) {
                    val attachmentRow = @Composable {
                        UserBubbleReaction(
                            emoji = if (!hasText) chatMessage.modelReaction else "",
                            bubbleColor = currentUserBubbleColors(isEditing).background,
                            bounce = reactionBounce,
                        ) {
                            SentAttachmentRow(
                                attachments = chatMessage.attachments,
                                alignEnd = true,
                                scrollKey = chatMessage.id,
                                onOpen = onOpenAttachment,
                            )
                        }
                    }
                    if (showAvatar && !hasText) {
                        UserMessageSlot(showAvatar = true, besideFirstLine = false, maxFraction = 1f) {
                            attachmentRow()
                        }
                    } else {
                        attachmentRow()
                    }
                }
                if (hasText) {
                    val bubble = currentUserBubbleColors(isEditing)
                    UserMessageSlot(showAvatar = showAvatar, besideFirstLine = true) {
                        CompositionLocalProvider(
                            LocalTextSelectionColors provides Theme[textSelectionColors][defaultTextSelectionColors],
                        ) {
                            UserBubbleReaction(chatMessage.modelReaction, bubble.background, reactionBounce) {
                                QuoteSelectionContainer(onQuote = onQuote) {
                                    Text(
                                        chatMessage.text,
                                        modifier = Modifier
                                            .irioSolidOrGlass(
                                                GlassSurface.USER_BUBBLE in ThemeStore.glassSurfaces,
                                                bubble.background,
                                                RoundedRectangle(16.dp),
                                                haze = null,
                                                border = true,
                                            )
                                            .then(
                                                if (onEdit != null) {
                                                    Modifier.clickable(onClick = onEdit)
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .padding(horizontal = 14.dp, vertical = UserBubblePaddingVertical),
                                        fontSize = 16.sp,
                                        lineHeight = UserBubbleLineHeight,
                                        fontFamily = rememberAppFontFamily() ?: FontFamily.Default,
                                        color = bubble.content,
                                    )
                                }
                            }
                        }
                    }
                }
                // 编辑版本切换器：与模型回复的版本切换同款，仍贴着气泡而不是头像。
                if (editVersionCount > 1) {
                    Box(
                        Modifier.padding(
                            end = if (showAvatar) UserMessageAvatarSize + UserMessageAvatarGap else 0.dp,
                        ),
                    ) {
                        UserEditVersionSwitcher(
                            versionIndex = editVersionIndex,
                            versionCount = editVersionCount,
                            onPrev = onPrevEditVersion,
                            onNext = onNextEditVersion,
                        )
                    }
                }
            } else {
                if (chatMessage.attachments.isNotEmpty()) {
                    SentAttachmentRow(
                        attachments = chatMessage.attachments,
                        alignEnd = false,
                        scrollKey = chatMessage.id,
                        onOpen = onOpenAttachment,
                    )
                }
                if (chatMessage.isError && chatMessage.text.isNotBlank()) {
                    chatMessage.process.filterNot { it.belowText }.let { events ->
                        ProcessRun(events, onOpen = onOpenProcess, onQuote = onQuote)
                    }
                    Box(Modifier.padding(vertical = 2.dp)) {
                        CompositionLocalProvider(
                            LocalTextSelectionColors provides Theme[textSelectionColors][defaultTextSelectionColors],
                        ) {
                            QuoteSelectionContainer(onQuote = onQuote) {
                                Text(
                                    chatMessage.text,
                                    color = irioErrorTextColor(),
                                    fontSize = 17.sp,
                                    lineHeight = 26.sp,
                                )
                            }
                        }
                    }
                    chatMessage.process.filter { it.belowText }.let { events ->
                        ProcessRun(events, onOpen = onOpenProcess, onQuote = onQuote)
                    }
                } else {
                    val processForRender = renderedProcessEvents(chatMessage.process, ThemeStore.mergeToolCalls)
                    val pieces = interleaveTranscript(chatMessage.text, processForRender)
                    val streamingBody = chatMessage.isStreaming || chatMessage.isPending
                    val lastTextIndex = pieces.indexOfLast { it is TranscriptPiece.Text }
                    var seenText = false
                    transcriptRenderRows(pieces).forEach { row ->
                        when (row) {
                            is TranscriptRenderRow.Subagents -> key(row.events.joinToString("-") { it.id.toString() }) {
                                SubagentCapsuleRow(row.events)
                            }
                            is TranscriptRenderRow.Single -> when (val piece = row.piece) {
                                is TranscriptPiece.Event -> key("process-${piece.event.id}") {
                                    ProcessEventRow(piece.event, onOpen = onOpenProcess, onQuote = onQuote)
                                }
                                is TranscriptPiece.Text -> {
                                    val shown = if (seenText) piece.text.trimStart('\n') else piece.text
                                    seenText = true
                                    if (shown.isNotBlank()) {
                                        key("body-${row.index}") {
                                            Box(Modifier.padding(vertical = 2.dp)) {
                                                CompositionLocalProvider(
                                                    LocalTextSelectionColors provides Theme[textSelectionColors][defaultTextSelectionColors],
                                                ) {
                                                    QuoteSelectionContainer(onQuote = onQuote) {
                                                        AssistantMarkdown(
                                                            text = shown,
                                                            streaming = streamingBody && row.index == lastTextIndex,
                                                            renderScope = markdownScope,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if ((chatMessage.isPending || chatMessage.isStreaming) &&
                        chatMessage.text.isBlank() &&
                        chatMessage.process.none { it.live || it.kind == ProcessEventKind.BUBBLE || it.toolName == CommonsImageTools.SHOW }
                    ) {
                        StatusSweepText(tr("请求中"))
                    }
                }
                // 停止/结束后正文可能一直为空（例如只输出思考就被停止）：
                // 操作行必须挂在 when 之外，否则这条消息永远拿不到复制和重试。
                if (!chatMessage.fromUser && !chatMessage.isPending && !chatMessage.isStreaming &&
                    editVersionCount <= 1
                ) {
                    AssistantActions(
                        messageId = chatMessage.id,
                        onCopy = onCopy,
                        userReaction = chatMessage.userReaction,
                        reactionBounce = reactionBounce,
                        onAddReaction = onAddReaction,
                        onRemoveReaction = onRemoveReaction,
                        onSpeak = if (spoken.isNotBlank()) {
                            {
                                if (
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    requestSpeechNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                SpeechPlayer.speak(context, chatMessage.id, spoken)
                            }
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
private fun ProcessRun(
    events: List<ProcessEvent>,
    onOpen: (ProcessEvent) -> Unit,
    onQuote: (String) -> Unit,
) {
    transcriptRenderRows(events.map { TranscriptPiece.Event(it) }).forEach { row ->
        when (row) {
            is TranscriptRenderRow.Subagents -> key(row.events.joinToString("-") { it.id.toString() }) {
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
) {
    if (event.toolName == CommonsImageTools.SHOW) {
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
    val openable = event.toolName != ImageGenTool.NAME &&
        (terminal || (!event.live && event.content.isNotBlank()))
    val label = when (event.kind) {
        ProcessEventKind.THINKING -> if (event.live) null else tr("已思考 %s").format(formatThinkingDuration(event.millis))
        ProcessEventKind.SEARCH -> if (event.live) null else searchCompletedLabel(event.pages, event.searchQuery)
        ProcessEventKind.TOOL -> if (event.live) null else MemoryTool.doneLabel(event.toolName, event.content, event.webDomain)
        ProcessEventKind.BUBBLE, ProcessEventKind.SUBAGENT -> null
    }?.let { event.displayText.ifEmpty { it } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (event.live) {
            if (terminal) {
                Row(
                    modifier = Modifier.clickable { onOpen(event) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    StatusSweepText(event.displayText.ifEmpty { MemoryTool.liveLabel(event.toolName) })
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
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
                        ProcessEventKind.TOOL -> MemoryTool.liveLabel(event.toolName, event.webDomain)
                        ProcessEventKind.BUBBLE, ProcessEventKind.SUBAGENT -> ""
                    }
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
                val hostView = LocalView.current
                LaunchedEffect(followThinkingBottom.value) {
                    if (!followThinkingBottom.value) return@LaunchedEffect
                    thinkingScrollState.smoothFollowBottom(thinkingFollowGate) { followThinkingBottom.value }
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
internal fun ProcessEventSheet(
    event: ProcessEvent?,
    state: BottomSheetState,
    onDismiss: () -> Unit,
) {
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
private fun TerminalCommandSheet(
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
    ) {
        val minSheetHeight = with(LocalDensity.current) {
            (LocalWindowInfo.current.containerSize.height * 0.32f).toDp()
        }
        val maxSheetHeight = with(LocalDensity.current) {
            (LocalWindowInfo.current.containerSize.height * 0.85f).toDp()
        }
        val scroll = rememberScrollState()
        val wasNearBottom = scroll.value >= scroll.maxValue - 48
        LaunchedEffect(event.content, event.live) {
            withFrameNanos { }
            if (wasNearBottom) scroll.scrollTo(scroll.maxValue)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minSheetHeight, max = maxSheetHeight)
                .verticalScroll(scroll),
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

@Composable
private fun SentAttachmentRow(
    attachments: List<ChatAttachment>,
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
                AttachmentCard(
                    attachment,
                    onOpen = { onOpen(attachment) },
                )
            }
        }
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

/**
 * 导航栏下箭头、或被输入法自己吃掉的返回，都不会进 [NavigationBackHandler]，
 * 消息框会一直保持聚焦。只在输入法从可见落到收起时清焦点；弹起和首帧不碰，
 * 否则一点消息框键盘就会被立刻关掉。退后台或窗口失焦造成的收起不算主动关闭。
 */
@Composable
internal fun DismissComposerFocusWhenImeHides(
    imeVisible: Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0,
) {
    val focusManager by rememberUpdatedState(LocalFocusManager.current)
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current
    val windowFocused = windowInfo.isWindowFocused
    val currentImeVisible by rememberUpdatedState(imeVisible)
    var imeWasVisible by remember { mutableStateOf(false) }
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
            // 同步取消这次可见记录，快速退出再进入也不能把后台收起当作主动关闭。
            if (!resumed) imeWasVisible = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(imeVisible, resumed, windowFocused) {
        if (!resumed || !windowFocused) {
            imeWasVisible = false
            return@LaunchedEffect
        }
        if (imeVisible) {
            imeWasVisible = true
            return@LaunchedEffect
        }
        if (!imeWasVisible) return@LaunchedEffect
        // 动画过程中 inset 可能空一帧。等它稳定再清，否则聚焦消息框会立刻失焦。
        delay(48)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            windowInfo.isWindowFocused && !currentImeVisible && imeWasVisible
        ) {
            focusManager.clearFocus(force = true)
        }
        imeWasVisible = false
    }
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
    if (message.summaryState != SummaryState.NONE || message.fromUser || message.isError || message.isPending || message.isStreaming || message.text.isBlank()) return
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
internal fun AssistantMarkdown(
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
    val context = LocalContext.current
    // texScale 只在进入组合时读一次：不再监听 ProviderStore 全局变化——
    // 否则模型切换/思考档位保存会让屏幕上每条消息都重组（切换卡顿的主要来源之一）。
    val texScale = remember { ProviderStore.loadTexScale(context) }
    val appFont = rememberAppFontFamily()
    val bodyStyle = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, color = contentColor, fontFamily = appFont)
    val inlineCode = irioInlineCodeStyle()
    val codeBlock = irioCodeBlockStyle()
    val typography = markdownTypography(
        h1 = TextStyle(fontSize = 24.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = contentColor, fontFamily = appFont),
        h2 = TextStyle(fontSize = 22.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, color = contentColor, fontFamily = appFont),
        h3 = TextStyle(fontSize = 20.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, color = contentColor, fontFamily = appFont),
        h4 = TextStyle(fontSize = 18.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = contentColor, fontFamily = appFont),
        h5 = TextStyle(fontSize = 17.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = contentColor, fontFamily = appFont),
        h6 = TextStyle(fontSize = 17.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, color = contentColor, fontFamily = appFont),
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
                    when (segment) {
                        is MarkdownSegment.TexBlock -> TexBlock(
                            latex = segment.latex,
                            colorArgb = contentColor.toArgb(),
                            density = density,
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
        Text(latex, fontSize = 17.sp, lineHeight = 26.sp, color = Theme[colors][onBackgroundColor])
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
    messageId: Long,
    onCopy: () -> Unit,
    userReaction: String,
    reactionBounce: androidx.compose.runtime.State<Float>,
    onAddReaction: () -> Unit,
    onRemoveReaction: () -> Unit,
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
    val copyConfirmation = rememberCopyConfirmation(messageId)
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (copyConfirmation.confirmed) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = tr(if (copyConfirmation.confirmed) "已复制" else "复制"),
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = { copyConfirmation.copy(onCopy) }),
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
        AssistantReactionButton(userReaction, reactionBounce, onAddReaction, onRemoveReaction)
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
internal fun PhotoViewer(
    attachment: ChatAttachment,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val zoomState = rememberZoomableState()
    var saved by remember(attachment.filePath) { mutableStateOf(false) }
    var regionDecodeFailed by remember(attachment.filePath) { mutableStateOf(false) }
    val errorReporter = remember(attachment.filePath) {
        object : SubSamplingImageErrorReporter {
            override fun onImageLoadingFailed(e: IOException, imageSource: SubSamplingImageSource) {
                regionDecodeFailed = true
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(4f)
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!regionDecodeFailed) {
            // 按当前缩放从原文件切块，而不是先压成屏幕大小的位图再放大。
            val imageState = rememberSubSamplingImageState(
                imageSource = SubSamplingImageSource.file(attachment.filePath.toPath()),
                zoomableState = zoomState,
                errorReporter = errorReporter,
            )
            SubSamplingImage(
                state = imageState,
                contentDescription = attachment.name,
                modifier = Modifier
                    // 整个屏幕都是图片手势区：双击缩放、双指缩放/平移。
                    // 不设 onClick：tap 判定要等 double-tap 超时窗口（~300ms），关闭有可感延迟；
                    // 关闭走返回键（NavigationBackHandler）。
                    .fillMaxSize()
                    .zoomable(zoomState),
            )
        } else {
            ZoomableDecodedImage(attachment, zoomState)
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
                .background(Color.Black.copy(alpha = 0.55f), ContinuousCapsule),
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
private fun ZoomableDecodedImage(
    attachment: ChatAttachment,
    zoomState: ZoomableState,
) {
    var bitmap by remember(attachment.filePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(attachment.filePath) {
        bitmap = withContext(Dispatchers.IO) { decodeOriginal(attachment.filePath) }
    }
    val image = bitmap
    DisposableEffect(zoomState) {
        val previous = zoomState.autoApplyTransformations
        zoomState.autoApplyTransformations = false
        onDispose { zoomState.autoApplyTransformations = previous }
    }
    if (image != null) {
        zoomState.setContentLocation(
            ZoomableContentLocation.unscaledAndTopLeftAligned(
                Size(image.width.toFloat(), image.height.toFloat()),
            ),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .zoomable(zoomState)
            .drawBehind {
                val current = image ?: return@drawBehind
                val transformation = zoomState.contentTransformation
                if (!transformation.isSpecified) return@drawBehind
                val scale = transformation.scale
                drawImage(
                    image = current,
                    dstOffset = IntOffset(
                        transformation.offset.x.roundToInt(),
                        transformation.offset.y.roundToInt(),
                    ),
                    dstSize = IntSize(
                        (current.width * scale.scaleX).roundToInt().coerceAtLeast(1),
                        (current.height * scale.scaleY).roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.High,
                )
            },
    )
}

private fun decodeOriginal(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val maxPixels = 24_000_000L
    var sample = 1
    while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inScaled = false
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, options)?.asImageBitmap()
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


