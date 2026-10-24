package com.irio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * 导航栏下箭头、或被输入法自己吃掉的返回，都不会进 [NavigationBackHandler]， 消息框会一直保持聚焦。只在输入法从可见落到收起时清焦点；弹起和首帧不碰，
 * 否则一点消息框键盘就会被立刻关掉。退后台或窗口失焦造成的收起不算主动关闭。
 */
@Composable
internal fun DismissComposerFocusWhenImeHides(
    imeVisible: Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0
) {
    val focusManager by rememberUpdatedState(LocalFocusManager.current)
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current
    val windowFocused = windowInfo.isWindowFocused
    val currentImeVisible by rememberUpdatedState(imeVisible)
    var imeWasVisible by remember { mutableStateOf(false) }
    var resumed by
        remember(lifecycleOwner) {
            mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
            // 同步取消这次可见记录，快速退出再进入也不能把后台收起当作主动关闭。
            if (!resumed) imeWasVisible = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(imeVisible, resumed, windowFocused) {
        if (!resumed || !windowFocused) {
            imeWasVisible = false
            return@LaunchedEffect
        }
        if (imeVisible) {
            imeWasVisible = true
            return@LaunchedEffect
        }
        if (!imeWasVisible) return@LaunchedEffect
        // 动画过程中 inset 可能空一帧。等它稳定再清，否则聚焦消息框会立刻失焦。
        delay(48)
        if (
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                windowInfo.isWindowFocused &&
                !currentImeVisible &&
                imeWasVisible
        ) {
            focusManager.clearFocus(force = true)
        }
        imeWasVisible = false
    }
}

@Composable
internal fun NavigationBackHandler(
    viewingPhoto: Boolean,
    processSheetOpen: Boolean,
    drawerOpen: Boolean,
    hasMessages: Boolean,
    onDismissPhoto: () -> Unit,
    onDismissSheet: () -> Unit,
    onCloseDrawer: () -> Unit,
    onHideIme: () -> Unit,
    onNewConversation: () -> Unit,
) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    BackHandler(
        enabled = viewingPhoto || processSheetOpen || drawerOpen || imeVisible || hasMessages
    ) {
        when {
            viewingPhoto -> onDismissPhoto()
            processSheetOpen -> onDismissSheet()
            drawerOpen -> onCloseDrawer()
            imeVisible -> onHideIme()
            else -> onNewConversation()
        }
    }
}

@Composable
internal fun ImeAnimationProbe(animating: AtomicBoolean) {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val previous = remember { intArrayOf(imeBottom) }
    SideEffect {
        if (imeBottom != previous[0]) {
            animating.set(true)
            previous[0] = imeBottom
        }
    }
    LaunchedEffect(imeBottom) {
        delay(48)
        if (previous[0] == imeBottom) animating.set(false)
    }
}
