package com.irio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.composables.ui.components.AlertDialog
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composeunstyled.theme.Theme

/** AlertDialog wrapper that keeps system back from dismissing the dialog. */
@Composable
fun IrioAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Theme[colors][panelColor],
    contentColor: Color = Theme[colors][onPanelColor],
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = visible) {}
    val scaledContent: @Composable () -> Unit = {
        ProvideAppTextScale {
            DismissComposerFocusWhenImeHides()
            content()
        }
    }
    if (overlay != null) {
        OverlayAlertDialog(visible, onDismissRequest, modifier, backgroundColor, contentColor,
            overlay = {
                val host = this
                ProvideAppTextScale { overlay(host) }
            }, content = scaledContent)
        return
    }
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        content = scaledContent,
    )
}
