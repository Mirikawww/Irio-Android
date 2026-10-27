package com.irio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.irio.ScaledDropdownMenuPanel as DropdownMenuPanel
import com.composables.ui.components.DropdownMenuSide
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldColor
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle

@Composable
internal fun BoxScope.ChatComposerBar(state: ChatScreenState, availableHeight: Dp) {
    with(state) {
        val content = Theme[colors][onBackgroundColor]
        val compactHeight = with(LocalDensity.current) { compactComposerHeightPx.toDp() }
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val canSend =
            pendingAttachments.none { it.filePath in writingPastedText } &&
                ((editingMessageId != null &&
                    (message.text.isNotBlank() || pendingAttachments.isNotEmpty())) ||
                    (!generatingThisChat &&
                        editingMessageId == null &&
                        (message.text.isNotBlank() || pendingAttachments.isNotEmpty())))

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
                                pickPhotos()
                            },
                            leading = {
                                Icon(
                                    imageVector = IrioIcons.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        ) {
                            Text(tr("照片"))
                        }
                        DropdownMenuItem(
                            onClick = {
                                attachMenuOpen = false
                                pickFiles()
                            },
                            leading = {
                                Icon(
                                    imageVector = IrioIcons.Paperclip,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        ) {
                            Text(tr("文件"))
                        }
                    }
                },
            ) {
                IconButton(
                    onClick = { attachMenuOpen = !attachMenuOpen },
                    style = ButtonStyle.Ghost,
                    buttonSize = ButtonSize.Small,
                    modifier = Modifier.size(32.dp).semantics { contentDescription = tr("添加附件") },
                ) {
                    Icon(
                        imageVector = IrioIcons.Plus,
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
                onClick = state::toggleSttRecording,
                imageVector = if (sttRecording) IrioIcons.Square else IrioIcons.Mic,
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
                imageVector = if (generatingThisChat) IrioIcons.Square else IrioIcons.ArrowUp,
                contentDescription = if (generatingThisChat) tr("停止输出") else "Send message",
                enabled = sendActive,
                active = sendActive,
                iconSize = if (generatingThisChat) 22.dp else 20.dp,
            )
        }

        @Composable
        fun AttachmentChips() {
            if (pendingAttachments.isNotEmpty()) {
                ChatAttachmentList(
                    attachments = pendingAttachments,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    loadingFiles = writingPastedText,
                    onRemove = { attachment ->
                        pendingAttachments = pendingAttachments.filterNot { it == attachment }
                    },
                )
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
                modifier =
                    Modifier.fillMaxWidth()
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(8.dp).background(Color(0xFFFF453A), CircleShape))
                    Text(timer, color = content, fontSize = 13.sp)
                    Canvas(Modifier.weight(1f).height(32.dp)) {
                        val barWidth = 2.dp.toPx()
                        val gap = 3.dp.toPx()
                        val slotWidth = barWidth + gap
                        val barCount = (size.width / slotWidth).toInt().coerceAtLeast(1)
                        val values = sttWaveformLevels.takeLast(barCount)
                        val centerY = size.height / 2f
                        values.forEachIndexed { index, value ->
                            val x = size.width - (values.size - index - 0.5f) * slotWidth
                            val height =
                                (3.dp.toPx() + value * 24.dp.toPx()).coerceAtMost(size.height)
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
                modifier =
                    Modifier.then(
                            if (expandedLayout) {
                                // 状态栏留白随展开进度渐入，避免第一帧整体下压。
                                Modifier.weight(1f)
                                    .composerAnimatedPadding(expansion, expandedTop = statusBarTop)
                            } else {
                                Modifier
                            }
                        )
                        .windowInsetsPadding(
                            WindowInsets.navigationBars
                                .only(WindowInsetsSides.Bottom)
                                .exclude(WindowInsets.ime)
                        )
                        .onSizeChanged { if (!expandedLayout) composerBarHeightPx = it.height }
            ) {
                if (!expandedLayout) AttachmentChips()
                // 编辑模式提示条：正在编辑某条用户消息
                if (editingMessageId != null) {
                    val editFill = Theme[colors][primaryColor]
                    val editContent = Theme[colors][onPrimaryColor]
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .background(color = editFill, shape = RoundedRectangle(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = IrioIcons.Pencil,
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
                            imageVector = IrioIcons.X,
                            contentDescription = tr("取消编辑"),
                            modifier = Modifier.size(16.dp).clickable(onClick = { cancelEdit() }),
                            tint = editContent,
                        )
                    }
                }

                SpeechTransportBar()
                LinuxBackgroundBar(
                    onOpen = { id ->
                        backgroundCommandId = id
                        LinuxBackgroundJobs.find(id.toString())?.let { job ->
                            backgroundSheetEvent = linuxBackgroundTerminalEvent(job)
                        }
                        backgroundSheetState.targetDetent = BottomSheetDetent.FullyExpanded
                    }
                )
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
                        (fadeIn(tween(180)) +
                                expandVertically(
                                    expandFrom = Alignment.CenterVertically,
                                    animationSpec = tween(220),
                                ))
                            .togetherWith(
                                fadeOut(tween(120)) +
                                    shrinkVertically(
                                        shrinkTowards = Alignment.CenterVertically,
                                        animationSpec = tween(180),
                                    )
                            )
                            .using(SizeTransform(clip = false))
                    },
                    label = "composer-recording-transition",
                ) { recording ->
                    if (recording) {
                        RecordingComposerField(
                            expansionProgress = expansion,
                            modifier = if (expandedLayout) Modifier.weight(1f) else Modifier,
                        )
                    } else
                        ComposerField(
                            state = message,
                            onPasteAsAttachment = state::pasteTextAsAttachment,
                            placeholder = "Message",
                            focusRequester = composerFocusRequester,
                            modifier =
                                Modifier.fillMaxWidth()
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
                            onTextLayout = {
                                if (!expandedLayout) composerLineCount = it.lineCount
                            },
                            lineLimits =
                                TextFieldLineLimits.MultiLine(
                                    minHeightInLines = 1,
                                    maxHeightInLines = if (expandedLayout) Int.MAX_VALUE else 4,
                                ),
                            onEnterSend =
                                if (ThemeStore.enterToSend) {
                                    {
                                        if (!generatingThisChat && canSend) {
                                            composerFullscreen = false
                                            if (editingMessageId != null) commitEdit()
                                            else submitMessage()
                                        }
                                    }
                                } else {
                                    null
                                },
                            leading = { AttachmentButton() },
                            trailing = {
                                Column(
                                    modifier =
                                        if (composerLineCount >= 4)
                                            Modifier.height(
                                                with(LocalDensity.current) { 80.sp.toDp() }
                                            )
                                        else Modifier,
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
                                            Icon(
                                                IrioIcons.Maximize2,
                                                contentDescription = tr("全屏输入"),
                                                modifier = Modifier.size(18.dp),
                                                tint = content,
                                            )
                                        }
                                    }
                                    SendButton()
                                }
                            },
                            expandedFooter = {
                                Column(Modifier.fillMaxWidth()) {
                                    AttachmentChips()
                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 20.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        AttachmentButton()
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            IconButton(
                                                onClick = { composerFullscreen = false },
                                                style = ButtonStyle.Ghost,
                                                buttonSize = ButtonSize.Small,
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    IrioIcons.Minimize2,
                                                    contentDescription = tr("收起全屏输入"),
                                                    modifier = Modifier.size(20.dp),
                                                    tint = content,
                                                )
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
}
