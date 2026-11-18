package com.irio

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Accept shared files, never interpret a copied URL or path string as a file. */
@OptIn(ExperimentalFoundationApi::class)
internal class AttachmentPasteReceiver(
    private val onAttach: (List<Uri>) -> Boolean,
) : ReceiveContentListener {
    override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
        if (transferableContent.source != TransferableContent.Source.Clipboard &&
            transferableContent.source != TransferableContent.Source.Keyboard) return transferableContent
        val clip = transferableContent.clipEntry.clipData
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            .filter { it.scheme == "content" }
        if (uris.isEmpty() || !onAttach(uris)) return transferableContent
        return transferableContent.consume { it.uri?.scheme == "content" }
    }
}

/** Reserve removable draft cards before IO so send cannot race an unfinished import. */
internal fun ChatScreenState.pasteAttachments(uris: List<Uri>): Boolean {
    if (clearingHistory || uris.isEmpty()) return false
    val conversationId = currentConversation.id
    val placeholders = uris.map {
        ChatAttachment(name = "attachment", mimeType = "application/octet-stream",
            filePath = File(context.filesDir, "attachments/${UUID.randomUUID()}").absolutePath)
    }
    pendingAttachments = pendingAttachments + placeholders
    writingPastedText = writingPastedText + placeholders.map { it.filePath }
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val loaded = mutableListOf<ChatAttachment>()
        val placeholderPaths = placeholders.map { it.filePath }.toSet()
        var completed = false
        try {
            withContext(Dispatchers.IO) {
                uris.zip(placeholders).forEach { (uri, placeholder) ->
                    context.copyAttachment(uri, File(placeholder.filePath))?.let(loaded::add)
                }
            }
            val active = currentConversation.id == conversationId
            val draft = if (active) ComposerDraft(message.text.toString(), pendingAttachments, editingMessageId, pendingQuote)
                else composerDrafts[conversationId]
            if (draft == null) return@launch
            val byPath = loaded.associateBy { it.filePath }
            val merged = draft.attachments.mapNotNull { attachment ->
                if (attachment.filePath in placeholderPaths) byPath[attachment.filePath] else attachment
            }
            // Other paste batches still contain generic loading cards. Their actual
            // slot cost/size is checked when those batches finish on this dispatcher.
            val ready = merged.filterNot { it.filePath in writingPastedText && it.filePath !in byPath }
            val error = when {
                attachmentSlots(ready) > ChatApi.MAX_ATTACHMENT_SLOTS -> tr("附件数量超过 15 个槽位（照片和视频各算半个）")
                attachmentBytes(ready) > ChatApi.MAX_ATTACHMENT_BYTES_TOTAL -> tr("附件总大小超过 5GB")
                else -> null
            }
            val attachments = if (error == null) merged else draft.attachments.filterNot { it.filePath in placeholderPaths }
            if (active) {
                pendingAttachments = attachments
                when {
                    error != null -> showComposerError(error)
                    loaded.size != uris.size -> showComposerError(tr("有附件无法读取"))
                    else -> composerStatus = null
                }
            } else {
                composerDrafts[conversationId] = draft.copy(attachments = attachments)
            }
            completed = true
        } finally {
            if (!completed) {
                if (currentConversation.id == conversationId) {
                    pendingAttachments = pendingAttachments.filterNot { it.filePath in placeholderPaths }
                } else composerDrafts[conversationId]?.let { draft ->
                    composerDrafts[conversationId] = draft.copy(attachments = draft.attachments.filterNot { it.filePath in placeholderPaths })
                }
            }
            val retained = pendingAttachments.map { it.filePath }.toSet() +
                composerDrafts.values.flatMap { draft -> draft.attachments.map { it.filePath } }
            // Publish completion before yielding for cleanup, so the next paste
            // batch counts these ready files when enforcing the shared limits.
            writingPastedText = writingPastedText - placeholderPaths
            withContext(NonCancellable + Dispatchers.IO) {
                placeholders.filterNot { it.filePath in retained }.forEach { File(it.filePath).delete() }
            }
        }
    }
    return true
}
