package com.irio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class ReactionBounceEvent(val sequence: Long, val emoji: String)

/** Screen-owned event tokens survive row recycling; loading history does not create events. */
internal class ReactionBounceTracker {
    private val events = mutableStateMapOf<Long, ReactionBounceEvent>()
    private val played = mutableMapOf<Long, Long>()
    private var sequence = 0L

    fun record(messageId: Long, emoji: String) {
        if (emoji.isEmpty()) {
            events.remove(messageId)
            played.remove(messageId)
        } else {
            events[messageId] = ReactionBounceEvent(++sequence, emoji)
        }
    }

    fun eventFor(messageId: Long): ReactionBounceEvent? = events[messageId]

    fun claim(messageId: Long, event: ReactionBounceEvent): Boolean {
        if (events[messageId] != event || played[messageId] == event.sequence) return false
        played[messageId] = event.sequence
        return true
    }
}

/** One grow-and-settle pulse, with no translation, repeats, or spring oscillations. */
@Composable
internal fun rememberReactionBounce(
    messageId: Long,
    emoji: String,
    tracker: ReactionBounceTracker,
): State<Float> {
    val progress = remember(messageId) { Animatable(1f) }
    val event = tracker.eventFor(messageId)
    LaunchedEffect(messageId, emoji, event) {
        try {
            progress.snapTo(1f)
            if (emoji.isNotEmpty() && event?.emoji == emoji && tracker.claim(messageId, event)) {
                // Recycled/detached ComposeViews can stop receiving frames midway through scaling.
                withTimeoutOrNull(600L) {
                    progress.animateTo(1f, animationSpec = keyframes {
                        durationMillis = 300
                        1f at 0 using FastOutSlowInEasing
                        1.16f at 120 using FastOutSlowInEasing
                        1f at 300
                    })
                }
            }
        } finally {
            withContext(NonCancellable) { progress.snapTo(1f) }
        }
    }
    return progress.asState()
}
