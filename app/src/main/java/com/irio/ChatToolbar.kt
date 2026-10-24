package com.irio

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BoxScope.ChatToolbar(state: ChatScreenState) {
    with(state) {
        val content = Theme[colors][onBackgroundColor]
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
            modifier =
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .hazeBlur(
                        input = HazeInput.Sources(chatHazeState),
                        style = chatToolbarHazeStyle(),
                    )
        ) {
            IrioToolbar(
                modifier = Modifier.onSizeChanged { toolbarHeightPx = it.height },
                title = { Spacer(Modifier.weight(1f)) },
                backgroundColor = Color.Transparent,
                contentColor = content,
                compact = true,
                leading = {
                    ToolbarCapsule(
                        glass = GlassSurface.SIDEBAR_BUTTON in ThemeStore.glassSurfaces
                    ) {
                        IconButton(
                            onClick = { drawerProgress = if (drawerOpen) 0f else 1f },
                            style = ButtonStyle.Ghost,
                            shape = ToolbarCapsuleShape,
                            modifier =
                                Modifier.semantics {
                                    contentDescription =
                                        if (drawerOpen) "Close sidebar" else "Open sidebar"
                                },
                        ) {
                            Icon(
                                imageVector = IrioIcons.Menu,
                                contentDescription =
                                    if (drawerOpen) "Close sidebar" else "Open sidebar",
                                tint = content,
                            )
                        }
                    }
                    if (modelPickerOnLeft) ToolbarModelPicker()
                },
                trailing = {
                    val showContextRing = ThemeStore.contextRing
                    val contextUsage =
                        currentConversation.tokenUsage
                            ?: messages.lastOrNull { !it.fromUser && it.usage != null }?.usage
                    val apiUsed = contextUsage?.usedTokens ?: 0
                    val compressingThisChat =
                        messages.any { message ->
                            message.summaryState == SummaryState.RUNNING &&
                                generations[message.id]?.conversationId == currentConversation.id
                        }
                    val liveEstimate =
                        liveContextEstimate.takeIf { it.first == currentConversation.id }?.second
                            ?: 0
                    val generatingReply = generatingThisChat && !compressingThisChat
                    val occupied =
                        contextRingOccupied(
                            apiUsed = apiUsed,
                            liveEstimate = liveEstimate,
                            generatingReply = generatingReply,
                            compressionEstimate = compressionUsageEstimate(messages),
                        )
                    if (showContextRing) {
                        val showCompression = contextCompressionEnabled
                        val windowTokens = ModelCatalog.contextWindow(context, selectedModel)
                        val historicalTools =
                            contextToolTokens.takeIf { it.first == currentConversation.id }?.second
                                ?: 0
                        val streamingTools =
                            if (generatingReply) {
                                liveToolTokens.takeIf { it.first == currentConversation.id }?.second
                                    ?: 0
                            } else {
                                0
                            }
                        val breakdown =
                            contextUsageBreakdown(
                                windowTokens = windowTokens,
                                occupiedTokens = occupied.tokens,
                                occupiedIncludesOverhead = occupied.includesOverhead,
                                systemTokens = contextSystemTokens,
                                toolTokens = historicalTools + streamingTools,
                                messageTokens =
                                    (liveEstimate - historicalTools - streamingTools).coerceAtLeast(
                                        0
                                    ),
                                reservedBudget =
                                    contextReservedBudget(
                                        windowTokens = windowTokens,
                                        compressionEnabled = showCompression,
                                        triggerPercent = contextCompressionTrigger,
                                    ),
                            )
                        val canCompress =
                            showCompression &&
                                !generatingThisChat &&
                                planCompression(
                                    contextMessages(messages).filterNot {
                                        it.isError || it.isPending || it.isStreaming
                                    }
                                ) != null
                        ToolbarCapsule(
                            glass = GlassSurface.CONTEXT_RING in ThemeStore.glassSurfaces
                        ) {
                            ContextUsageRing(
                                breakdown = breakdown,
                                compressionCount = compressionCount(messages),
                                showCompression = showCompression,
                                compressEnabled = canCompress,
                                onCompress = state::compressNow,
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
}
