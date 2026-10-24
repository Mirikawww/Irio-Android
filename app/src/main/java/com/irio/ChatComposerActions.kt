package com.irio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.text.input.clearText
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ChatScreenState.showComposerError(error: String) {
    composerStatus = error
    composerStatusNonce++
}

internal fun ChatScreenState.showSttProviderSnackbar() {
    showComposerError(tr("请先在「TTS/STT」中配置 STT 提供商"))
}

internal fun ChatScreenState.startSttRecording() {
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
        showComposerError(tr("无法开始录音"))
    }
}

internal fun ChatScreenState.finishSttRecording() {
    if (!sttRecording) return
    sttRecording = false
    val file = sttRecorder.stop()
    if (file == null) {
        showComposerError(tr("录音为空"))
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
            showComposerError(result.error)
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

internal fun ChatScreenState.toggleSttRecording() {
    when {
        sttTranscribing -> Unit
        sttRecording -> finishSttRecording()
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED -> startSttRecording()
        else -> requestRecordAudio()
    }
}

internal fun ChatScreenState.addAttachments(uris: List<Uri>) {
    if (uris.isEmpty()) return
    scope.launch {
        val loaded =
            withContext(Dispatchers.IO) { uris.mapNotNull { uri -> context.copyAttachment(uri) } }
        val merged = pendingAttachments + loaded
        val slots = attachmentSlots(merged)
        val bytes = attachmentBytes(merged)
        pendingAttachments =
            when {
                slots > ChatApi.MAX_ATTACHMENT_SLOTS -> pendingAttachments
                bytes > ChatApi.MAX_ATTACHMENT_BYTES_TOTAL -> pendingAttachments
                else -> merged
            }
        val error =
            when {
                loaded.size < uris.size -> tr("有附件无法读取")
                slots > ChatApi.MAX_ATTACHMENT_SLOTS -> tr("附件数量超过 15 个槽位（照片和视频各算半个）")
                bytes > ChatApi.MAX_ATTACHMENT_BYTES_TOTAL -> tr("附件总大小超过 5GB")
                else -> null
            }
        if (error != null) showComposerError(error) else composerStatus = null
    }
}

internal fun ChatScreenState.pasteTextAsAttachment(text: String): Boolean {
    if (clearingHistory) return false
    if (attachmentSlots(pendingAttachments) + 1f > ChatApi.MAX_ATTACHMENT_SLOTS) {
        showComposerError(tr("附件数量超过 15 个槽位（照片和视频各算半个）"))
        return false // Leave the clipboard text to the editor's normal paste path.
    }
    val attachment = LongTextPaste.attachment(File(context.filesDir, "attachments"))
    val conversationId = currentConversation.id
    pendingAttachments = pendingAttachments + attachment
    writingPastedText = writingPastedText + attachment.filePath
    scope.launch {
        try {
            val written =
                withContext(Dispatchers.IO) {
                    runCatching { LongTextPaste.write(attachment, text) }.isSuccess
                }
            val active = currentConversation.id == conversationId
            val draft =
                if (active) {
                    ComposerDraft(
                        message.text.toString(),
                        pendingAttachments,
                        editingMessageId,
                        pendingQuote,
                    )
                } else {
                    composerDrafts[conversationId]
                }
            val retained = draft != null && attachment in draft.attachments
            val overLimit =
                retained && attachmentBytes(draft.attachments) > ChatApi.MAX_ATTACHMENT_BYTES_TOTAL
            if (retained && (!written || overLimit)) {
                val restored = restoreFailedTextPaste(draft, attachment, text)
                if (active) {
                    pendingAttachments = restored.attachments
                    message.edit { append(text) }
                    showComposerError(if (overLimit) tr("附件总大小超过 5GB") else tr("文本文件创建失败，已保留原文"))
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

internal fun ChatScreenState.openAttachment(attachment: ChatAttachment) {
    if (attachment.isImage) {
        viewingPhoto = attachment
        return
    }
    runCatching {
        val file = File(attachment.filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, attachment.name))
    }
}

/**
 * 入场淡入只播一次：260ms 动画结束后标记即失去意义。不清掉的话， 该行组合被回收重建（滚远再滚回、重进会话）时会从透明重新播一遍，造成闪烁。 只清自己那次发送的标记，300ms
 * 内重发不会误清新的。
 */
internal fun ChatScreenState.clearEntranceMarkerAfterDelay(id: Long) {
    scope.launch {
        delay(300)
        if (newUserMessageId == id) newUserMessageId = null
    }
}

internal fun ChatScreenState.submitMessage() {
    if (pendingAttachments.any { it.filePath in writingPastedText }) return
    val text = message.text.toString().trim()
    if (busyThisChat || (text.isEmpty() && pendingAttachments.isEmpty())) return
    val attachments = pendingAttachments
    val userMessage =
        ChatMessage(
            id = MessageIds.next(),
            text = text,
            fromUser = true,
            attachments = attachments,
            quote = pendingQuote,
        )
    newUserMessageId = userMessage.id
    clearEntranceMarkerAfterDelay(userMessage.id)
    val reply =
        ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            isPending = true,
            responseToUserId = userMessage.id,
        )
    val history = contextMessages(messages).filterNot { it.isError || it.isPending }
    messages = dismissReplyErrors(messages) + userMessage + reply
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
internal fun ChatScreenState.beginEdit(target: ChatMessage) {
    if (busyThisChat) return
    if (target.id != visibleMessages(messages).lastOrNull { it.fromUser }?.id) return
    editingMessageId = target.id
    message.edit { replace(0, length, target.text) }
    pendingAttachments = target.attachments
    pendingQuote = target.quote
    // 清掉本就待发的内容可能带来的混淆状态
    composerStatus = null
    // 直接编辑：拉起输入法并聚焦输入框
    composerFocusRequester.requestFocus()
    keyboardController?.show()
}

/** 退出编辑模式（不保存）。 */
internal fun ChatScreenState.cancelEdit() {
    editingMessageId = null
    pendingQuote = ""
    message.clearText()
    pendingAttachments = emptyList()
}

/**
 * 提交编辑：新版本插回原消息位置（而不是列表尾），旧版本取消选中； 紧随其后的模型回复组取消选中，新 pending 回复进同一个 retryGroup 并启动重生成。
 * 版本切换（showEditVersion）与回复切换（showRetryVersion）独立，各自切换各自组。
 */
internal fun ChatScreenState.commitEdit() {
    if (pendingAttachments.any { it.filePath in writingPastedText }) return
    if (busyThisChat) return
    val targetId = editingMessageId ?: return
    val targetIndex = messages.indexOfFirst { it.id == targetId }
    if (targetIndex < 0) return
    val target = messages[targetIndex]
    val text = message.text.toString().trim()
    if (text.isEmpty() && pendingAttachments.isEmpty()) return
    val committed =
        commitEditedTurn(
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

/** 切换用户消息的编辑版本：用户组切换 + 联动切换它对应的回复组。 配对规则：该版本消息之后的第一条非 error assistant 消息即它当时的回复。 */
internal fun ChatScreenState.showEditVersion(target: ChatMessage) {
    val targetIndex = messages.indexOfFirst { it.id == target.id }
    val pairedReplyGroupId = replyGroupForUserVersion(messages, target)
    val pairedReply =
        messages.drop(targetIndex + 1).firstOrNull {
            !it.fromUser && !it.isError && it.groupId == pairedReplyGroupId
        }
    messages = selectEditVersion(messages, target)
    if (pairedReply != null) {
        messages = selectRetryVersion(messages, pairedReply)
    }
    persistCurrent(messages, touchUpdatedAt = false)
}

internal fun ChatScreenState.retryMessage(assistant: ChatMessage) {
    // Check live state as well as the disabled UI: a tap can arrive before recomposition.
    val busy =
        generations.values.any { it.conversationId == currentConversation.id } ||
            preparing.values.any { it == currentConversation.id }
    if (!canRetryMessage(assistant, busy)) return
    val groupId = assistant.groupId
    val firstIndex = messages.indexOfFirst { !it.fromUser && it.groupId == groupId }
    if (firstIndex <= 0) return
    val history =
        contextMessages(messages.take(firstIndex)).filterNot { it.isError || it.isPending }
    if (history.isEmpty()) return
    val reply =
        ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            isPending = true,
            retryGroup = groupId,
            selected = true,
            responseToUserId =
                assistant.responseToUserId
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
                }
            )
            if (index == lastIndex) add(reply)
        }
    }
    persistCurrent(messages)
    sendOrCompress(currentConversation.id, history, reply)
}

internal fun ChatScreenState.showRetryVersion(target: ChatMessage) {
    messages = selectRetryVersion(messages, target)
    persistCurrent(messages, touchUpdatedAt = false)
}

internal fun ChatScreenState.retryVersionInfo(target: ChatMessage): VersionInfo {
    val versions = if (target.fromUser) listOf(target) else retryGroups[target.groupId].orEmpty()
    val index = versions.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
    return VersionInfo(index + 1, versions.size)
}

internal fun ChatScreenState.editVersionInfo(target: ChatMessage): VersionInfo {
    val versions = if (target.fromUser) editGroups[target.userGroupId].orEmpty() else emptyList()
    val index = versions.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
    return VersionInfo(index + 1, versions.size)
}
