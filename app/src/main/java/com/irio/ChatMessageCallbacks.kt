package com.irio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import com.composables.ui.components.BottomSheetDetent

@Composable
internal fun rememberChatMessageCallbacks(state: ChatScreenState): AndroidMessageCallbacks =
    with(state) {
        // 模型选单开关会重组这一页。callbacks 若每次都是新对象，可见消息行会全部重绘，
        // 毛玻璃源跟着失效。只在行上真能看见的状态变化时才换一份。
        val editAnchorId =
            visibleTranscript.lastOrNull { it.fromUser && it.summaryState == SummaryState.NONE }?.id
        val contextBoundaryId =
            visibleTranscript.lastOrNull { it.summaryState == SummaryState.SUCCESS }?.id
        return remember(
            currentConversation.id,
            newUserMessageId,
            editingMessageId,
            editAnchorId,
            retryGroups,
            editGroups,
            contextBoundaryId,
            visualizeContext,
            busyThisChat,
        ) {
            AndroidMessageCallbacks(
                markdownScope = scope,
                uploadTracker = attachmentUploads,
                uploadConversationId = currentConversation.id,
                onOpenProcess = { messageId, event ->
                    processSheetMessageId = messageId
                    processSheetEventId = event.id
                    processSheetState.targetDetent = BottomSheetDetent.FullyExpanded
                },
                onOpenAttachment = state::openAttachment,
                onCopy = { chatMessage ->
                    clipboard.setText(AnnotatedString(chatMessage.visibleText()))
                },
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
                    updateMessageReaction(
                        currentConversation.id,
                        chatMessage.id,
                        "",
                        fromModel = false,
                    )
                },
                reactionAnimations = reactionAnimations,
                onRetry = state::retryMessage,
                canRetry = { chatMessage ->
                    canRetryMessage(chatMessage, conversationBusy = false)
                },
                retryEnabled = !busyThisChat,
                onPrevVersion = { target ->
                    val versions =
                        if (target.fromUser) listOf(target)
                        else retryGroups[target.groupId].orEmpty()
                    val index = versions.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
                    versions.getOrNull(index - 1)?.let { previous ->
                        { showRetryVersion(previous) }
                    }
                },
                onNextVersion = { target ->
                    val versions =
                        if (target.fromUser) listOf(target)
                        else retryGroups[target.groupId].orEmpty()
                    val index = versions.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
                    versions.getOrNull(index + 1)?.let { next -> { showRetryVersion(next) } }
                },
                isEditing = { chatMessage -> editingMessageId == chatMessage.id },
                onEdit = { chatMessage ->
                    if (
                        chatMessage.fromUser &&
                            !chatMessage.isPending &&
                            !chatMessage.isStreaming &&
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
                versionInfo = state::retryVersionInfo,
                editVersionInfo = state::editVersionInfo,
                newUserMessageId = newUserMessageId,
                outsideContext = { chatMessage ->
                    visualizeContext && isBeforeCompression(visibleTranscript, chatMessage.id)
                },
            )
        }
    }
