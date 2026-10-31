package com.irio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.defaultTextSelectionColors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.textSelectionColors
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.CoroutineScope

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
            )
            .toDp()
    }
}

/** 用户消息内容贴右。打开头像时，头像占右侧一列，气泡变窄，避免顶出屏幕。 [besideFirstLine] 为真时头像对准第一行，而不是整块内容的底边。 */
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
        Box(Modifier.align(Alignment.TopEnd).padding(end = slot).widthIn(max = maxContent)) {
            content()
        }
        if (showAvatar) {
            UserMessageAvatar(
                Modifier.align(Alignment.TopEnd)
                    .padding(top = if (besideFirstLine) userMessageAvatarTopOffset() else 0.dp)
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
            .semantics { contentDescription = tr("头像") }
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
    uploadProgress: AttachmentUploadProgress? = null,
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
    retryEnabled: Boolean = true,
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
    val requestSpeechNotification =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) SpeechNotifications.sync(context, SpeechPlayer.playback)
        }
    val spoken =
        remember(chatMessage.id, chatMessage.text, chatMessage.process) {
            speechPlainText(chatMessage.visibleText())
        }
    val pronunciations =
        remember(chatMessage.toolRecords) { pronunciationRecordings(chatMessage.toolRecords) }
    val reactionBounce =
        rememberReactionBounce(
            chatMessage.id,
            if (chatMessage.fromUser) chatMessage.modelReaction else chatMessage.userReaction,
            reactionAnimations,
        )
    val speech = SpeechPlayer.playback
    val isNewUserMessage = chatMessage.fromUser && chatMessage.id == newUserMessageId

    val entranceProgress = rememberMessageEntranceAlpha(chatMessage.id, isNewUserMessage)
    // Observe opacity in composition so finishing the fade removes its temporary layer.
    // A retained transparent RenderNode must not outlive a recycled row's entrance.
    val rowAlpha =
        (if (isNewUserMessage) entranceProgress.value else 1f) * (if (outsideContext) 0.45f else 1f)
    val alignment = if (chatMessage.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier =
            Modifier.fillMaxWidth()
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
                    UserMessageSlot(
                        showAvatar = showAvatar && !hasText && !hasAttachments,
                        besideFirstLine = false,
                    ) {
                        MessageQuotePreview(quote = chatMessage.quote)
                    }
                }
                if (hasAttachments) {
                    val attachmentRow =
                        @Composable {
                            UserBubbleReaction(
                                emoji = if (!hasText) chatMessage.modelReaction else "",
                                bubbleColor = currentUserBubbleColors(isEditing).background,
                                bounce = reactionBounce,
                            ) {
                                SentAttachmentRow(
                                    attachments = chatMessage.attachments,
                                    alignEnd = true,
                                    scrollKey = chatMessage.id,
                                    avatarInset =
                                        if (showAvatar && !hasText)
                                            UserMessageAvatarSize + UserMessageAvatarGap
                                        else 0.dp,
                                    uploadProgress = uploadProgress,
                                    onOpen = onOpenAttachment,
                                )
                            }
                        }
                    if (showAvatar && !hasText) {
                        UserMessageSlot(
                            showAvatar = true,
                            besideFirstLine = false,
                            maxFraction = 1f,
                        ) {
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
                            LocalTextSelectionColors provides
                                Theme[textSelectionColors][defaultTextSelectionColors]
                        ) {
                            UserBubbleReaction(
                                chatMessage.modelReaction,
                                bubble.background,
                                reactionBounce,
                            ) {
                                QuoteSelectionContainer(onQuote = onQuote) {
                                    Text(
                                        chatMessage.text,
                                        modifier =
                                            Modifier.irioSolidOrGlass(
                                                    GlassSurface.USER_BUBBLE in
                                                        ThemeStore.glassSurfaces,
                                                    bubble.background,
                                                    RoundedRectangle(16.dp),
                                                    haze = null,
                                                    border = true,
                                                    glassTintAlpha = bubble.background.alpha,
                                                )
                                                .then(
                                                    if (onEdit != null) {
                                                        Modifier.clickable(onClick = onEdit)
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                                .padding(
                                                    horizontal = 14.dp,
                                                    vertical = UserBubblePaddingVertical,
                                                ),
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
                            end =
                                if (showAvatar) UserMessageAvatarSize + UserMessageAvatarGap
                                else 0.dp
                        )
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
                val inlineImagePaths =
                    chatMessage.process
                        .mapNotNull { it.imageGeneration?.finalAttachment?.filePath }
                        .toSet()
                val ordinaryAttachments =
                    chatMessage.attachments.filterNot { it.filePath in inlineImagePaths }
                if (ordinaryAttachments.isNotEmpty()) {
                    AssistantAttachmentList(
                        attachments = ordinaryAttachments,
                        onOpen = onOpenAttachment,
                    )
                }
                if (
                    chatMessage.isError &&
                        chatMessage.retryStatus == null &&
                        chatMessage.text.isNotBlank()
                ) {
                    chatMessage.process
                        .filterNot { it.belowText }
                        .let { events ->
                            ProcessRun(events, onOpen = onOpenProcess, onQuote = onQuote)
                        }
                    Box(Modifier.padding(vertical = 2.dp)) {
                        CompositionLocalProvider(
                            LocalTextSelectionColors provides
                                Theme[textSelectionColors][defaultTextSelectionColors]
                        ) {
                            QuoteSelectionContainer(onQuote = onQuote) {
                                ReplyErrorRow(ChatRetryStatus(chatMessage.text, stopped = true))
                            }
                        }
                    }
                    chatMessage.process
                        .filter { it.belowText }
                        .let { events ->
                            ProcessRun(events, onOpen = onOpenProcess, onQuote = onQuote)
                        }
                } else {
                    val processForRender =
                        renderedProcessEvents(chatMessage.process, ThemeStore.mergeToolCalls)
                    val pieces = interleaveTranscript(chatMessage.text, processForRender)
                    val streamingBody = chatMessage.isStreaming || chatMessage.isPending
                    val lastTextIndex = pieces.indexOfLast { it is TranscriptPiece.Text }
                    var seenText = false
                    transcriptRenderRows(pieces).forEach { row ->
                        when (row) {
                            is TranscriptRenderRow.Subagents ->
                                key(row.events.joinToString("-") { it.id.toString() }) {
                                    SubagentCapsuleRow(row.events)
                                }
                            is TranscriptRenderRow.Single ->
                                when (val piece = row.piece) {
                                    is TranscriptPiece.Event ->
                                        key("process-${piece.event.id}") {
                                            ProcessEventRow(
                                                piece.event,
                                                onOpen = onOpenProcess,
                                                onQuote = onQuote,
                                                onOpenImage = onOpenAttachment,
                                            )
                                        }
                                    is TranscriptPiece.Text -> {
                                        val shown =
                                            if (seenText) piece.text.trimStart('\n') else piece.text
                                        seenText = true
                                        if (shown.isNotBlank()) {
                                            key("body-${row.index}") {
                                                Box(Modifier.padding(vertical = 2.dp)) {
                                                    CompositionLocalProvider(
                                                        LocalTextSelectionColors provides
                                                            Theme[textSelectionColors][
                                                                defaultTextSelectionColors]
                                                    ) {
                                                        QuoteSelectionContainer(onQuote = onQuote) {
                                                            AssistantMarkdown(
                                                                text = shown,
                                                                streaming =
                                                                    streamingBody &&
                                                                        row.index == lastTextIndex,
                                                                renderScope = markdownScope,
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
                    }
                    chatMessage.retryStatus?.let { ReplyErrorRow(it) }
                    if (
                        chatMessage.retryStatus == null &&
                            (chatMessage.isPending || chatMessage.isStreaming) &&
                            chatMessage.text.isBlank() &&
                            chatMessage.process.none {
                                !isSilentPronunciationLookup(it.toolName, it.toolOutcome) &&
                                    (it.live ||
                                        it.kind == ProcessEventKind.BUBBLE ||
                                        it.toolName == CommonsImageTools.SHOW ||
                                        (it.toolName == MermaidTool.NAME && it.toolOutcome == ToolOutcome.SUCCESS))
                            }
                    ) {
                        StatusSweepText(tr("请求中"))
                    }
                }
                // 停止/结束后正文可能一直为空（例如只输出思考就被停止）：
                // 操作行必须挂在 when 之外，否则这条消息永远拿不到复制和重试。
                if (
                    !chatMessage.fromUser &&
                        !chatMessage.isPending &&
                        !chatMessage.isStreaming &&
                        editVersionCount <= 1
                ) {
                    AssistantActions(
                        messageId = chatMessage.id,
                        onCopy = onCopy,
                        userReaction = chatMessage.userReaction,
                        reactionBounce = reactionBounce,
                        onAddReaction = onAddReaction,
                        onRemoveReaction = onRemoveReaction,
                        onSpeak =
                            if (spoken.isNotBlank()) {
                                {
                                    if (
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        requestSpeechNotification.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    }
                                    SpeechPlayer.speak(context, chatMessage.id, spoken)
                                }
                            } else {
                                null
                            },
                        speaking = speech.active && speech.messageId == chatMessage.id,
                        onRetry = onRetry,
                        canRetry = canRetry,
                        retryEnabled = retryEnabled,
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
        Text(label, color = Theme[colors][mutedColor], fontSize = 13.sp)
    }
}

@Composable
internal fun StatusSweepText(label: String) {
    val highlight = Theme[colors][onBackgroundColor]
    val base = Theme[colors][mutedColor]
    val shift by
        rememberInfiniteTransition(label = "statusSweep")
            .animateFloat(
                initialValue = -0.4f,
                targetValue = 1.4f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(1400, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "statusSweepShift",
            )
    Text(
        label,
        fontSize = 13.sp,
        color = base,
        modifier =
            Modifier.testTag("status-sweep")
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val width = size.width
                    val start = width * shift
                    drawRect(
                        brush =
                            Brush.linearGradient(
                                colors =
                                    listOf(
                                        Color.Transparent,
                                        highlight.copy(alpha = 0.7f),
                                        Color.Transparent,
                                    ),
                                start = Offset(start - width * 0.25f, 0f),
                                end = Offset(start + width * 0.25f, 0f),
                            ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
    )
}

/** Final assistant images keep the same large surface even without ephemeral generation state. */
@Composable
internal fun AssistantAttachmentList(
    attachments: List<ChatAttachment>,
    onOpen: (ChatAttachment) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        attachments.forEach { attachment ->
            key(attachment.remoteUrl.ifEmpty { attachment.filePath }) {
                if (attachment.isImage) {
                    ImageGenerationCard(
                        progress = ImageGenerationProgress(
                            id = attachment.filePath,
                            finalAttachment = attachment,
                            active = false,
                        ),
                        onOpen = onOpen,
                    )
                } else if (attachment.isGeneratedVideo) {
                    GeneratedVideoAttachmentCard(attachment, onOpen = onOpen)
                } else {
                    AttachmentCard(attachment, onOpen = { onOpen(attachment) })
                }
            }
        }
    }
}

@Composable
private fun SentAttachmentRow(
    attachments: List<ChatAttachment>,
    alignEnd: Boolean,
    scrollKey: Any,
    avatarInset: Dp = 0.dp,
    uploadProgress: AttachmentUploadProgress? = null,
    onOpen: (ChatAttachment) -> Unit,
) {
    // 行宽随内容，短于视口时仍由消息列贴右或贴左。超出后才横向滚动。
    // 用户消息反向滚动，0 停在末尾，条带继续贴着气泡一侧。
    val gutter = LocalChatImageStripOutset.current
    key(scrollKey) {
        ChatAttachmentList(
            attachments = attachments,
            alignEnd = alignEnd,
            startOutset = gutter,
            endOutset = gutter + avatarInset,
            uploadProgress = uploadProgress,
            onOpen = onOpen,
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
            imageVector = IrioIcons.ChevronLeft,
            contentDescription = tr("上一版本"),
            modifier =
                Modifier.size(16.dp)
                    .clickable(enabled = onPrev != null, onClick = { onPrev?.invoke() }),
            tint = muted.copy(alpha = if (onPrev != null) 1f else 0.35f),
        )
        Text("$versionIndex / $versionCount", color = muted, fontSize = 13.sp)
        Icon(
            imageVector = IrioIcons.ChevronRight,
            contentDescription = tr("下一版本"),
            modifier =
                Modifier.size(16.dp)
                    .clickable(enabled = onNext != null, onClick = { onNext?.invoke() }),
            tint = muted.copy(alpha = if (onNext != null) 1f else 0.35f),
        )
    }
}

@Composable
internal fun MessageRetryButton(enabled: Boolean, onClick: () -> Unit) {
    val muted = Theme[colors][mutedColor]
    Icon(
        imageVector = IrioIcons.RefreshCw,
        contentDescription = tr("重试"),
        modifier = Modifier.size(18.dp).clickable(enabled = enabled, onClick = onClick),
        tint = muted.copy(alpha = if (enabled) 1f else 0.35f),
    )
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
    retryEnabled: Boolean,
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
            imageVector = if (copyConfirmation.confirmed) IrioIcons.Check else IrioIcons.Copy,
            contentDescription = tr(if (copyConfirmation.confirmed) "已复制" else "复制"),
            modifier = Modifier.size(18.dp).clickable(onClick = { copyConfirmation.copy(onCopy) }),
            tint = muted,
        )
        if (canRetry) {
            MessageRetryButton(enabled = retryEnabled, onClick = onRetry)
        }
        if (onSpeak != null) {
            Icon(
                imageVector = IrioIcons.Volume2,
                contentDescription = tr("朗读"),
                modifier = Modifier.size(18.dp).clickable(onClick = onSpeak),
                tint = if (speaking) Theme[colors][onBackgroundColor] else muted,
            )
        }
        AssistantReactionButton(userReaction, reactionBounce, onAddReaction, onRemoveReaction)
        if (clock.isNotEmpty()) {
            Text(clock, color = muted, fontSize = 13.sp)
        }
        if (versionCount > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = IrioIcons.ChevronLeft,
                    contentDescription = tr("上一个回复"),
                    modifier =
                        Modifier.size(16.dp)
                            .clickable(
                                enabled = onPrevVersion != null,
                                onClick = { onPrevVersion?.invoke() },
                            ),
                    tint = muted.copy(alpha = if (onPrevVersion != null) 1f else 0.35f),
                )
                Text("$versionIndex / $versionCount", color = muted, fontSize = 13.sp)
                Icon(
                    imageVector = IrioIcons.ChevronRight,
                    contentDescription = tr("下一个回复"),
                    modifier =
                        Modifier.size(16.dp)
                            .clickable(
                                enabled = onNextVersion != null,
                                onClick = { onNextVersion?.invoke() },
                            ),
                    tint = muted.copy(alpha = if (onNextVersion != null) 1f else 0.35f),
                )
            }
        }
    }
}
