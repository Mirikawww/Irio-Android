package com.irio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment

/** Dependent options follow their feature switch without an independent disclosure state. */
@Composable
internal fun SettingsDependentContent(enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = enabled,
        enter = expandVertically(animationSpec = irioSpring(), expandFrom = Alignment.Top) +
            fadeIn(animationSpec = irioSpring()),
        exit = shrinkVertically(animationSpec = irioSpring(), shrinkTowards = Alignment.Top) +
            fadeOut(animationSpec = irioSpring()),
    ) {
        Column(content = content)
    }
}
