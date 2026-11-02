/*
 * Adapted from Compose Unstyled 2.7.0 DropdownMenu / Modal (MIT).
 * Copyright (c) 2026 Composable Horizons
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.irio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuScope
import com.composables.ui.components.DropdownMenuSide
import com.composeunstyled.AnchoredFloatingContent
import com.composeunstyled.DropdownMenuState
import com.composeunstyled.LocalDropdownMenuState
import com.composeunstyled.LocalModalState
import com.composeunstyled.LocalModalWindow
import com.composeunstyled.ModalState
import com.composeunstyled.rememberModalState
import java.util.UUID

/**
 * UI 0.2.0 does not expose the Modal window's initial focus policy. Preserve its
 * anchored layout, state, keyboard traversal and styled panels, replacing only
 * the Android window owner so NOT_FOCUSABLE is present before Dialog.show().
 * Check these internal adapters when upgrading Compose Unstyled 2.7.0.
 */
@Composable
internal fun ImePreservingDropdownMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    side: DropdownMenuSide = DropdownMenuSide.Bottom,
    alignment: DropdownMenuAlignment = DropdownMenuAlignment.Start,
    sideOffset: Dp = 4.dp,
    alignmentOffset: Dp = 0.dp,
    panel: @Composable DropdownMenuScope.() -> Unit,
    anchor: @Composable () -> Unit,
) {
    val keepIme = rememberKeepImeOnMenuOpen(expanded)
    // Non-focusable windows receive touches, but Back remains on the host Activity.
    BackHandler(enabled = expanded && keepIme) { onExpandedChange(false) }
    val modal = rememberModalState(initiallyVisible = expanded)
    val focusManager = LocalFocusManager.current
    val previous = remember { FocusRequester() }
    val next = remember { FocusRequester() }
    var initialDirection by remember { mutableStateOf<FocusDirection?>(null) }
    var exitDirection by remember { mutableStateOf<FocusDirection?>(null) }
    SideEffect { modal.transitionState.targetState = expanded }
    LaunchedEffect(expanded) { if (!expanded) initialDirection = null }
    LaunchedEffect(expanded, exitDirection) {
        val direction = exitDirection
        if (!expanded && direction != null) {
            (if (direction == FocusDirection.Previous) previous else next).requestFocus()
            focusManager.moveFocus(direction)
            exitDirection = null
        }
    }
    val state = remember(onExpandedChange, modal, initialDirection) {
        DropdownMenuState(onExpandedChange, modal.transitionState, initialDirection) { direction ->
            exitDirection = direction
            onExpandedChange(false)
        }
    }
    val scope = remember(side, alignment) {
        DropdownMenuScope(object : com.composeunstyled.DropdownMenuScope {}, side, alignment)
    }
    Box(modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false
        else when (event.key) {
            Key.DirectionDown, Key.DirectionUp -> {
                initialDirection = if (event.key == Key.DirectionUp) FocusDirection.Previous else FocusDirection.Next
                if (!expanded) onExpandedChange(true)
                true
            }
            Key.Enter, Key.Spacebar -> if (expanded) false else {
                initialDirection = FocusDirection.Next
                onExpandedChange(true)
                true
            }
            else -> false
        }
    }) {
        AnchoredFloatingContent(
            layer = { content ->
                MenuWindow(modal, keepIme, onDismiss = { onExpandedChange(false) }, content = content)
            },
            content = {
                CompositionLocalProvider(LocalDropdownMenuState provides state) { scope.panel() }
            },
            contentModifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { onExpandedChange(false) }
            },
            side = side.anchorSide,
            alignment = alignment.anchorAlignment,
            sideOffset = sideOffset,
            alignmentOffset = alignmentOffset,
            anchor = {
                Box {
                    Box(Modifier.size(0.dp).focusRequester(previous)
                        .focusable(exitDirection == FocusDirection.Previous))
                    anchor()
                    Box(Modifier.size(0.dp).focusRequester(next)
                        .focusable(exitDirection == FocusDirection.Next))
                }
            },
        )
    }
}

@Composable
private fun MenuWindow(
    state: ModalState,
    keepIme: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!state.transitionState.targetState && state.mountedFragments == 0) return
    val context = LocalContext.current
    val parent = LocalView.current
    val layoutDirection = LocalLayoutDirection.current
    val id = rememberSaveable { UUID.randomUUID() }
    val composition = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(parent) {
        val dialog = ComponentDialog(context, com.composeunstyled.modal.R.style.Modal)
        val window = requireNotNull(dialog.window)
        // Set before show(), not from an effect inside the dialog content.
        if (keepIme) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        var hostContext: Context? = context
        while (hostContext is ContextWrapper && hostContext !is Activity) hostContext = hostContext.baseContext
        (hostContext as? Activity)?.window?.let { host ->
            val source = WindowCompat.getInsetsController(host, host.decorView)
            val target = WindowCompat.getInsetsController(window, window.decorView)
            target.isAppearanceLightStatusBars = source.isAppearanceLightStatusBars
            target.isAppearanceLightNavigationBars = source.isAppearanceLightNavigationBars
        }
        val view = ComposeView(context).apply {
            setTag(androidx.compose.ui.R.id.compose_view_saveable_id_tag, "menu_$id")
            setParentCompositionContext(composition)
            setContent {
                CompositionLocalProvider(
                    LocalModalState provides state,
                    LocalModalWindow provides window,
                    LocalMenuKeepsIme provides keepIme,
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    DisposableEffect(state) {
                        state.attachedToWindow = true
                        onDispose { state.attachedToWindow = false }
                    }
                    BackHandler { dismiss() }
                    if (state.attachedToWindow) Box(Modifier.fillMaxSize().semantics { dialog() }.onPreviewKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                            dismiss(); true
                        } else false
                    }) { currentContent() }
                }
            }
        }
        dialog.setContentView(view)
        view.setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
        view.setViewTreeViewModelStoreOwner(parent.findViewTreeViewModelStoreOwner())
        view.setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        onDispose {
            // Remove first: disposing panel effects must not refocus a closing window.
            dialog.dismiss()
            view.disposeComposition()
        }
    }
}
