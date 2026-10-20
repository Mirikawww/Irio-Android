package com.irio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

internal object LongTextPaste {
    const val DEFAULT_THRESHOLD = 1500
    const val MIN_THRESHOLD = 100
    const val MAX_THRESHOLD = 10000
    const val STEP = 100

    fun normalizeThreshold(value: Int): Int =
        ((value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD).toFloat() / STEP).roundToInt() * STEP)

    fun shouldAttach(text: String, enabled: Boolean, threshold: Int): Boolean =
        enabled && text.codePointCount(0, text.length) > normalizeThreshold(threshold)

    fun attachment(directory: File): ChatAttachment {
        val id = UUID.randomUUID().toString()
        return ChatAttachment(
            name = "pasted-${id.take(8)}.txt",
            mimeType = "text/plain",
            filePath = File(directory, "$id.txt").absolutePath,
        )
    }

    /** Call on the IO dispatcher; reserve the attachment in the draft before starting the write. */
    fun write(attachment: ChatAttachment, text: String) {
        val file = File(attachment.filePath)
        try {
            checkNotNull(file.parentFile).mkdirs()
            file.writeText(text, Charsets.UTF_8)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}

internal fun restoreFailedTextPaste(
    draft: ComposerDraft,
    attachment: ChatAttachment,
    text: String,
): ComposerDraft {
    if (attachment !in draft.attachments) return draft
    return draft.copy(
        text = draft.text + text,
        attachments = draft.attachments - attachment,
    )
}

@OptIn(ExperimentalFoundationApi::class)
internal class LongTextPasteReceiver(
    private val enabled: () -> Boolean,
    private val threshold: () -> Int,
    private val onAttach: (String) -> Boolean,
) : ReceiveContentListener {
    override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
        if (transferableContent.source != TransferableContent.Source.Clipboard || !enabled()) {
            return transferableContent
        }
        val clip = transferableContent.clipEntry.clipData
        val text = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text }.joinToString("\n")
        if (!LongTextPaste.shouldAttach(text, true, threshold()) || !onAttach(text)) {
            return transferableContent
        }
        // Keep non-text clipboard items available to the editor/other content receivers.
        return transferableContent.consume { it.text != null }
    }
}
