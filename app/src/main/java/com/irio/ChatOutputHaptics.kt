package com.irio

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** Called only for a live publication, never from a recycled row or a rendering effect. */
internal fun ChatScreenState.outputHaptics(previous: ChatMessage?, updated: ChatMessage) {
    if (previous == null || updated.fromUser || updated.isError) return
    if (!previous.isPending && !previous.isStreaming && !updated.isStreaming) return
    val lifecycle = (context as? LifecycleOwner)?.lifecycle ?: return
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    fun appended(before: String, after: String): Boolean =
        after.length > before.length && after.startsWith(before) &&
            after.substring(before.length).any { !it.isWhitespace() }

    val bodyAdvanced = appended(previous.text, updated.text)
    val thinkingAdvanced = updated.process.any { event ->
        event.kind == ProcessEventKind.THINKING && appended(
            previous.process.firstOrNull { it.id == event.id }?.content.orEmpty(), event.content,
        )
    }
    if (bodyAdvanced || thinkingAdvanced) AppHaptics.perform(context, HapticEvent.STREAM_OUTPUT)
}
