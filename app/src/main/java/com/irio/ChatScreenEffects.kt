package com.irio

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.ui.components.BottomSheetDetent
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Effects are installed once per screen composition, never from an Activity. */
@Composable
internal fun ChatScreenEffects(state: ChatScreenState, restoreLastOpened: Boolean) {
    ObserveChatRecording(state)
    ObserveChatPresentation(state)
    RestoreChatConversations(state, restoreLastOpened)
    ObserveChatStores(state)
    ObserveChatBackgroundResults(state)
    HandleChatNavigation(state)
    ObservePronunciationPlayback(onError = state::showComposerError)
    ImeAnimationProbe(state.imeAnimating)
}

@Composable
private fun ObserveChatRecording(state: ChatScreenState) =
    with(state) {
        DisposableEffect(Unit) { onDispose { sttRecorder.cancel() } }

        LaunchedEffect(sttRecording) {
            if (sttRecording) {
                while (true) {
                    val amplitude = withContext(Dispatchers.IO) { sttRecorder.sampleAmplitude() }
                    val level = sqrt((amplitude / 32_767f).coerceIn(0f, 1f))
                    sttWaveformLevels = (sttWaveformLevels + level).takeLast(64)
                    sttRecordingElapsedMillis =
                        SystemClock.elapsedRealtime() - sttRecordingStartedAt
                    delay(70)
                }
            }
        }
    }

/** Reopened/settled transcripts need local estimates, but a preparing send owns its own snapshot. */
@Composable
internal fun ObserveIdleContextEstimate(state: ChatScreenState) = with(state) {
    LaunchedEffect(currentConversation.id, messages, busyThisChat, ThemeStore.contextRing) {
        if (busyThisChat || !ThemeStore.contextRing) return@LaunchedEffect
        val conversationId = currentConversation.id
        val messageSnapshot = messages
        val parts = withContext(Dispatchers.Default) {
            val included = contextMessages(messageSnapshot)
            estimateRequestTokens(modelRequestMessages(included)) to estimateToolContextTokens(included)
        }
        if (!busyThisChat && currentConversation.id == conversationId && messages === messageSnapshot) {
            contextToolTokens = conversationId to parts.second
            liveContextEstimate = conversationId to parts.first
        }
    }
}

@Composable
private fun ObserveChatPresentation(state: ChatScreenState) =
    with(state) {
        LaunchedEffect(currentConversation.id) {
            reactionSheetState.targetDetent = BottomSheetDetent.Hidden
            reactionTarget = null
        }

        LaunchedEffect(currentConversation.id, messages) {
            val conversationId = currentConversation.id
            val messageSnapshot = messages
            if (transcriptPresentation.conversationId != conversationId) {
                transcriptPresentation = buildTranscriptPresentation(conversationId, emptyList())
            }
            val next =
                withContext(Dispatchers.Default) {
                    buildTranscriptPresentation(conversationId, messageSnapshot)
                }
            if (currentConversation.id == conversationId && messages === messageSnapshot) {
                transcriptPresentation = next
            }
        }
        ObserveIdleContextEstimate(state)
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
            val protocol =
                providers.firstOrNull { it.id == activeProviderId }?.protocol
                    ?: ProviderProtocol.CHAT_COMPLETIONS
            val modelId = selectedModel
            val overhead =
                withContext(Dispatchers.IO) {
                    loadContextRequestOverhead(context, protocol, modelId)
                }
            contextSystemTokens = overhead
        }

        LaunchedEffect(drawerOpen) {
            if (drawerOpen) {
                focusManager.clearFocus()
                attachMenuOpen = false
            }
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
            val force =
                opening || scrollToNewMessage || (last.fromUser && last.id != seenTranscriptTailId)
            if (justSent) seenSentUserMessageId = newUserMessageId
            if (opening) pendingConversationScroll = null
            if (
                !shouldSnapTranscriptToBottom(
                    stickToBottom = stickToBottom,
                    userScrollInProgress =
                        recyclerScrolling || messageRecyclerController.isScrollInProgress(),
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
    }

@Composable
private fun RestoreChatConversations(state: ChatScreenState, restoreLastOpened: Boolean) =
    with(state) {
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

        LaunchedEffect(ReplyOpenRequest.conversationId, conversations) {
            val id = ReplyOpenRequest.conversationId ?: return@LaunchedEffect
            val summary = conversations.firstOrNull { it.id == id } ?: return@LaunchedEffect
            ReplyOpenRequest.clear()
            ReplyNotifications.cancel(context, id)
            openConversation(summary)
        }
    }

@Composable
private fun ObserveChatStores(state: ChatScreenState) =
    with(state) {
        val currentConversationState = rememberUpdatedState(currentConversation)
        val generationsSnapshotState = rememberUpdatedState(generations)

        DisposableEffect(context) {
            // SharedPreferences 监听：模型/思考等设置被外部（设置页）改动时刷新。
            // 注意：本页面自己保存的字段已在点击回调里先更新内存态，listener 全量重载
            // 会覆盖为磁盘值——新值与内存一致时靠「引用相等跳过」避免无谓重组。
            val preferences = ProviderStore.preferences(context)
            var suppressUntil = 0L
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "theme_preference") return@OnSharedPreferenceChangeListener
                    // 自己刚发起的保存：300ms 内跳过回环重载（内存态已是最新）
                    if (System.currentTimeMillis() < suppressUntil)
                        return@OnSharedPreferenceChangeListener
                    scope.launch(Dispatchers.IO) {
                        val loaded = ProviderStore.loadProviders(context)
                        val active = ProviderStore.loadActiveProviderId(context)
                        val provider =
                            loaded.firstOrNull { it.id == active } ?: loaded.firstOrNull()
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
                val streamingThisChat =
                    currentId in generationsSnapshotState.value.values.map { it.conversationId }
                scope.launch(Dispatchers.IO) {
                    val loaded = ConversationStore.loadAll(context)
                    val full =
                        loaded
                            .firstOrNull { it.id == currentId }
                            ?.takeIf { it.updatedAt != currentUpdatedAt && !streamingThisChat }
                            ?.let { ConversationStore.load(context, it.id) }
                    withContext(Dispatchers.Main) {
                        // 自触发回环（persistCurrent → save → notifyChanged）里 id 集合没变时，
                        // 逐条比较字段、不变就保留原引用，避免点击发送后整个抽屉重组。
                        val merged =
                            if (loaded.map { it.id } == conversations.map { it.id }) {
                                conversations.map { old ->
                                    loaded
                                        .firstOrNull { it.id == old.id }
                                        ?.let { new ->
                                            if (
                                                old.title != new.title ||
                                                    old.updatedAt != new.updatedAt ||
                                                    old.pinned != new.pinned
                                            )
                                                new
                                            else old
                                        } ?: old
                                }
                            } else {
                                loaded
                            }
                        if (merged != conversations) conversations = merged
                        if (
                            full != null &&
                                currentConversation.id == currentId &&
                                currentId !in preparing.values &&
                                currentId !in generations.values.map { it.conversationId }
                        ) {
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
                        val provider =
                            loaded.firstOrNull { it.id == active } ?: loaded.firstOrNull()
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

        LaunchedEffect(
            currentActiveId,
            activeProvider?.endpointUrl,
            currentConversationId,
            currentConversationBusy,
        ) {
            val provider = activeProvider ?: return@LaunchedEffect
            val scopeId = currentConversationId
            if (
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
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
                            scope.launch(Dispatchers.IO) {
                                EndpointWarmup.warmBlocking(provider!!, scopeId)
                            }
                        }
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

@Composable
private fun ObserveChatBackgroundResults(state: ChatScreenState) =
    with(state) {
        DisposableEffect(Unit) {
            val remove = VideoGenerationJobs.observe(context) { job ->
                scope.launch(Dispatchers.Main.immediate) { noteVideoFinished(job) }
            }
            onDispose { remove() }
        }
        LaunchedEffect(backgroundCommandId) {
            val id = backgroundCommandId ?: return@LaunchedEffect
            while (true) {
                val job = LinuxBackgroundJobs.find(id.toString()) ?: break
                backgroundSheetEvent = linuxBackgroundTerminalEvent(job)
                if (!job.running && job.exitCode != null) break
                delay(200)
            }
        }

        DisposableEffect(Unit) {
            val remove =
                LinuxBackgroundJobs.onFinished { command ->
                    scope.launch(Dispatchers.Main.immediate) { noteBackgroundFinished(command) }
                }
            onDispose { remove() }
        }

        val persistSubagent by
            rememberUpdatedState<(SubagentSnapshot) -> Unit> { snapshot ->
                val json = snapshot.toJson()
                fun apply(source: List<ChatMessage>): List<ChatMessage> =
                    source.map { message ->
                        var changed = false
                        val process =
                            message.process.map { event ->
                                if (
                                    event.kind == ProcessEventKind.SUBAGENT &&
                                        subagentSnapshot(event.content)?.id == snapshot.id
                                ) {
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
                            ConversationStore.save(
                                context,
                                stored.copy(messages = next, updatedAt = System.currentTimeMillis()),
                            )
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
    }

@Composable
private fun HandleChatNavigation(state: ChatScreenState) =
    with(state) {
        val processSheetOpen =
            processSheetState.targetDetent != BottomSheetDetent.Hidden ||
                reactionSheetState.targetDetent != BottomSheetDetent.Hidden
        DismissComposerFocusWhenImeHides()

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

        BackHandler(
            enabled = composerFullscreen && !processSheetOpen && viewingPhoto == null && !drawerOpen
        ) {
            composerFullscreen = false
        }
    }
