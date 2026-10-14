package com.irio

import androidx.compose.animation.core.Animatable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun rememberMessageEntranceAlpha(messageId: Long, animate: Boolean): State<Float> {
    val progress = remember(messageId) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(messageId, animate) {
        try {
            if (animate) {
                // RecyclerView rows can lose their frame clock while detached or prefetched.
                // Opacity must not depend on receiving the final animation frame.
                withTimeoutOrNull(600L) {
                    progress.animateTo(1f, animationSpec = irioSpring<Float>(stiffness = 1800f))
                }
            }
        } finally {
            withContext(NonCancellable) { progress.snapTo(1f) }
        }
    }
    return progress.asState()
}
