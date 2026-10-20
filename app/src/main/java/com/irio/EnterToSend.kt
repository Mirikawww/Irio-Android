package com.irio

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer

internal enum class NewlineEdit {
    /** 这次输入是回车，应发送并撤掉换行。 */
    Send,

    /** Shift+Enter 允许的换行，保留。 */
    KeepNewline,

    /** 粘贴或其他编辑，不动。 */
    Ignore,
}

/**
 * 只有「一次编辑、插入内容正好是一个换行」才算回车。
 * 粘贴进来的多行文本不会被当成发送。
 */
internal fun classifyNewlineEdit(
    changeCount: Int,
    inserted: String,
    allowNewline: Boolean,
): NewlineEdit {
    if (changeCount != 1 || inserted != "\n") return NewlineEdit.Ignore
    return if (allowNewline) NewlineEdit.KeepNewline else NewlineEdit.Send
}

/**
 * 同一次按键可能既走硬件 KeyEvent，又走输入法动作或插入换行。
 * 只保留最后一次请求，避免发出两条消息。
 */
internal class EnterToSendSession(
    private val post: (() -> Unit) -> Unit = { action ->
        Handler(Looper.getMainLooper()).post(action)
    },
) {
    var allowNextNewline: Boolean = false
    private var generation = 0

    fun request(block: () -> Unit) {
        val ticket = ++generation
        post {
            if (ticket == generation) block()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal class EnterToSendTransformation(
    private val session: EnterToSendSession,
    private val onSend: () -> Unit,
) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val inserted = if (changes.changeCount == 1) {
            val range = changes.getRange(0)
            asCharSequence().subSequence(range.min, range.max).toString()
        } else {
            ""
        }
        when (classifyNewlineEdit(changes.changeCount, inserted, session.allowNextNewline)) {
            NewlineEdit.KeepNewline -> session.allowNextNewline = false
            NewlineEdit.Send -> {
                session.allowNextNewline = false
                revertAllChanges()
                onSend()
            }
            NewlineEdit.Ignore -> session.allowNextNewline = false
        }
    }
}
