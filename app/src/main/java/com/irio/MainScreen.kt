package com.irio

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.composables.ui.components.rememberBottomSheetState
import dev.chrisbanes.haze.rememberHazeState

/** Android chat composition root: create state, bind platform effects, render. */
@Composable
fun MainScreen(restoreLastOpened: Boolean = false) {
    val state = rememberChatScreenState()
    ChatScreenLaunchers(state)
    ChatScreenEffects(state, restoreLastOpened)
    ChatScreenLayout(state)
}

@Composable
internal fun rememberChatScreenState(): ChatScreenState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message = rememberTextFieldState()
    val fullscreen = rememberSaveable { mutableStateOf(false) }
    val reactionSheet = rememberBottomSheetState()
    val processSheet = rememberBottomSheetState()
    val backgroundSheet = rememberBottomSheetState()
    val haze = rememberHazeState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val state =
        remember(context, scope) {
            ChatScreenState(
                context,
                scope,
                message,
                fullscreen,
                reactionSheet,
                processSheet,
                backgroundSheet,
                haze,
                focus,
                keyboard,
                clipboard,
            )
        }
    state.focusManager = focus
    state.keyboardController = keyboard
    state.clipboard = clipboard
    return state
}
