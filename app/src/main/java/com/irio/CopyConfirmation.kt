package com.irio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class CopyConfirmationState(private val scope: CoroutineScope) {
    var confirmed by mutableStateOf(false)
        private set
    private var resetJob: Job? = null

    fun copy(action: () -> Unit) {
        action()
        confirmed = true
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(2_000L)
            confirmed = false
        }
    }

    fun dispose() {
        resetJob?.cancel()
        resetJob = null
        confirmed = false
    }
}

@Composable
internal fun rememberCopyConfirmation(messageId: Long): CopyConfirmationState {
    val scope = rememberCoroutineScope()
    val state = remember(messageId) { CopyConfirmationState(scope) }
    DisposableEffect(state) {
        onDispose { state.dispose() }
    }
    return state
}
