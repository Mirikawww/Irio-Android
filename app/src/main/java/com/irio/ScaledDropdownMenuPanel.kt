package com.irio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.composables.ui.components.DropdownMenuPanel
import com.composables.ui.components.DropdownMenuPanelContentScope
import com.composables.ui.components.DropdownMenuScope

/** Popup windows create a new Android Compose density; reapply only the app's text scale. */
@Composable
internal fun DropdownMenuScope.ScaledDropdownMenuPanel(
    modifier: Modifier = Modifier,
    minWidth: Dp? = null,
    content: @Composable DropdownMenuPanelContentScope.() -> Unit,
) {
    if (minWidth == null) {
        DropdownMenuPanel(modifier = modifier) { ProvideAppTextScale { content() } }
    } else {
        DropdownMenuPanel(modifier = modifier, minWidth = minWidth) { ProvideAppTextScale { content() } }
    }
}
