package com.irio

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.composables.ui.components.BottomSheetState
import dev.chrisbanes.haze.HazeState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Composition-scoped state. No disk writes or request execution in this holder. */
internal class ChatScreenState(
    val context: Context,
    val scope: CoroutineScope,
    val message: TextFieldState,
    composerFullscreenState: MutableState<Boolean>,
    val reactionSheetState: BottomSheetState,
    val processSheetState: BottomSheetState,
    val backgroundSheetState: BottomSheetState,
    val chatHazeState: HazeState,
    var focusManager: FocusManager,
    var keyboardController: SoftwareKeyboardController?,
    var clipboard: ClipboardManager,
) {
    // Sheets and recording.
    var processSheetMessageId by mutableStateOf<Long?>(null)
    var processSheetEventId by mutableStateOf<Long?>(null)
    var reactionTarget by mutableStateOf<Pair<String, Long>?>(null)
    val reactionAnimations = ReactionBounceTracker()
    var backgroundCommandId by mutableStateOf<Long?>(null)
    var backgroundSheetEvent by mutableStateOf<ProcessEvent?>(null)
    var sttRecording by mutableStateOf(false)
    var sttTranscribing by mutableStateOf(false)
    var sttWaveformLevels by mutableStateOf(emptyList<Float>())
    var sttRecordingStartedAt by mutableLongStateOf(0L)
    var sttRecordingElapsedMillis by mutableLongStateOf(0L)
    val sttRecorder = SttRecorder(context.applicationContext)

    // 抽屉列表只持轻量摘要（无正文）；正文按需从磁盘取并常驻缓存（见 [conversationContent]）。
    var conversations by mutableStateOf(emptyList<ConversationSummary>())
    var currentConversation by mutableStateOf(ConversationStore.newConversation())
    var messages by mutableStateOf(currentConversation.messages)
    var newUserMessageId by mutableStateOf<Long?>(null)
    var pendingAttachments by mutableStateOf(emptyList<ChatAttachment>())
    val attachmentUploads = AttachmentUploadTracker()
    var writingPastedText by mutableStateOf(emptySet<String>())
    var clearingHistory by mutableStateOf(false)
    var composerStatus by mutableStateOf<String?>(null)
    var composerStatusNonce by mutableIntStateOf(0)
    /** 正在编辑的用户消息 id；null = 普通发送模式。 */
    var editingMessageId by mutableStateOf<Long?>(null)
    var pendingQuote by mutableStateOf("")
    var composerFullscreen by composerFullscreenState
    var composerLineCount by mutableIntStateOf(1)
    var compactComposerHeightPx by mutableIntStateOf(0)
    // 未发送的输入按会话分开。当前会话的内容只在输入框里，切走才写入这张表。
    val composerDrafts = mutableMapOf<String, ComposerDraft>()

    val composerFocusRequester = FocusRequester()

    // Model selection and context usage.
    var selectedModel by
        mutableStateOf(ProviderStore.loadProvider(context)?.selectedModel.orEmpty())
    var providers by mutableStateOf(ProviderStore.loadProviders(context))
    var activeProviderId by mutableStateOf(ProviderStore.loadActiveProviderId(context))
    var modelMenuOpen by mutableStateOf(false)
    var contextRingOpen by mutableStateOf(false)
    var contextCompressionEnabled by mutableStateOf(ToolStore.loadContextCompression(context))
    var contextCompressionTrigger by mutableStateOf(ToolStore.loadCompressionTrigger(context))
    var contextOverheadNonce by mutableIntStateOf(0)
    var contextSystemTokens by mutableIntStateOf(0)
    var contextToolTokens by mutableStateOf("" to 0)
    var liveToolTokens by mutableStateOf("" to 0)
    var visualizeContext by mutableStateOf(ToolStore.loadVisualizeContext(context))
    var liveContextEstimate by mutableStateOf("" to 0)
    var attachMenuOpen by mutableStateOf(false)
    var reasoningEffort by mutableStateOf(ProviderStore.loadReasoningEffort(context))
    var fastMode by mutableStateOf(ProviderStore.loadFastMode(context))
    var mergeModels by mutableStateOf(ProviderStore.loadMergeModels(context))
    var modelsDevCatalog by mutableStateOf(ProviderStore.loadModelsDevCatalog(context))
    var modelsDevReasoning by mutableStateOf(ProviderStore.loadModelsDevReasoning(context))
    var webSearchAvailable by mutableStateOf(SearchStore.hasApiKey(context))
    var webSearchEnabled by mutableStateOf(SearchStore.loadWebSearchEnabled(context))
    var autoRead by mutableStateOf(SpeechStore.loadAutoRead(context))
    var clearHistoryDialogOpen by mutableStateOf(false)
    var conversationAction by mutableStateOf<ConversationSummary?>(null)
    val titleGeneration = ConversationTitleGenerationState()
    var viewingPhoto by mutableStateOf<ChatAttachment?>(null)

    // Generation and queued background completions.
    var generations by mutableStateOf(emptyMap<Long, ActiveGeneration>())
    // 发送前预检（token 估算在后台线程）进行中的回复占位：堵住异步窗口期的重复提交。
    var preparing by mutableStateOf(emptyMap<Long, String>())
    val pendingBackgroundNotices = mutableMapOf<String, MutableList<LinuxBackgroundCommand>>()
    val subagentFinishes = SubagentFinishQueue()
    val pendingVideoResults = linkedMapOf<String, VideoGenerationJob>()

    // Transcript scrolling and screen geometry.
    val messageRecyclerController = MessageRecyclerController()
    var recyclerScrolling by mutableStateOf(false)
    var stickToBottom by mutableStateOf(true)
    var seenTranscriptConversationId by mutableStateOf<String?>(null)
    var seenTranscriptTailId by mutableStateOf<Long?>(null)
    var seenSentUserMessageId by mutableStateOf<Long?>(null)
    var composerBarHeightPx by mutableIntStateOf(0)
    var toolbarHeightPx by mutableIntStateOf(0)
    val drawerWidth = 280.dp

    var drawerProgress by mutableStateOf(0f)
    var drawerDragging by mutableStateOf(false)
    var drawerStartProgress by mutableStateOf(0f)
    var pendingConversationScroll by mutableStateOf<String?>(null)
    var conversationLoadToken by mutableIntStateOf(0)
    val imeAnimating = AtomicBoolean(false)
    val streamingDeferActive = AtomicBoolean(false)
    // 预热不能抢首次展示的 CPU：只保留一个可取消的邻近消息任务，
    // 快速滚动时丢弃旧范围，避免几十个 LaTeX 同时排队。
    val transcriptPrewarmJob = mutableStateOf<Job?>(null)

    /** 会话正文缓存（LRU 8 条）：conversations 里只有摘要，打开/置顶/流式兜底都要正文， 全部走这里，避免同一会话在同一帧里被重复读盘解析 JSON。 */
    val conversationCache = ConversationContentCache()
    var transcriptPresentation by
        mutableStateOf(buildTranscriptPresentation(currentConversation.id, emptyList()))

    val renderTexScale = ProviderStore.loadTexScale(context)
    val suppressProviderEcho = ProviderEchoSuppression()
    // Bound by ChatScreenLaunchers before any effects or UI can dispatch actions.
    lateinit var requestRecordAudio: () -> Unit
    lateinit var requestReplyNotification: () -> Unit
    lateinit var ensureSpeechNotification: () -> Unit
    lateinit var pickPhotos: () -> Unit
    lateinit var pickFiles: () -> Unit

    val generatingIds by derivedStateOf { generations.values.map { it.conversationId }.toSet() }
    val preparingIds by derivedStateOf { preparing.values.toSet() }
    val busyThisChat
        get() = currentConversation.id in generatingIds || currentConversation.id in preparingIds

    val generatingThisChat
        get() = currentConversation.id in generatingIds

    val drawerOpen
        get() = drawerProgress >= 0.5f

    val presentationReady
        get() = transcriptPresentation.conversationId == currentConversation.id

    val visibleTranscript
        get() = if (presentationReady) transcriptPresentation.visible else emptyList()

    val shownTranscript
        get() = visibleTranscript

    val retryGroups
        get() = if (presentationReady) transcriptPresentation.retryGroups else emptyMap()

    val editGroups
        get() = if (presentationReady) transcriptPresentation.editGroups else emptyMap()
}

internal class ProviderEchoSuppression {
    var fn: () -> Unit = {}
}
