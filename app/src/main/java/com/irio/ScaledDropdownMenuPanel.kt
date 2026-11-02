package com.irio

import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.composables.ui.components.DropdownMenuPanel
import com.composables.ui.components.DropdownMenuPanelContentScope
import com.composables.ui.components.DropdownMenuScope
import com.composeunstyled.LocalModalWindow

internal val LocalMenuKeepsIme = staticCompositionLocalOf { false }

/** Capture the host IME at each open/close boundary, not the menu window's insets. */
@Composable
internal fun rememberKeepImeOnMenuOpen(expanded: Boolean): Boolean {
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    return remember(expanded) { imeVisible }
}

/** Menu windows create a new Android Compose density; reapply only the app's text scale. */
@Composable
internal fun DropdownMenuScope.ScaledDropdownMenuPanel(
    modifier: Modifier = Modifier,
    minWidth: Dp? = null,
    keepIme: Boolean = LocalMenuKeepsIme.current,
    content: @Composable DropdownMenuPanelContentScope.() -> Unit,
) {
    if (keepIme || LocalMenuKeepsIme.current) {
        val window = LocalModalWindow.current
        DisposableEffect(window, keepIme) {
            // The window owner sets this before show(); only editable pages opt back in.
            window.setFlags(
                if (keepIme) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            )
            onDispose { }
        }
    }
    if (minWidth == null) {
        DropdownMenuPanel(modifier = modifier) { ProvideAppTextScale { content() } }
    } else {
        DropdownMenuPanel(modifier = modifier, minWidth = minWidth) { ProvideAppTextScale { content() } }
    }
}
