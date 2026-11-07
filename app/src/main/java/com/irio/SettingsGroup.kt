package com.irio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composeunstyled.theme.Theme

/** A compact section heading above the existing settings card, aligned with its rows. */
@Composable
internal fun SettingsGroup(title: String?, content: @Composable () -> Unit) {
    if (title == null) {
        content()
        return
    }
    Column(Modifier.fillMaxWidth()) {
        SettingsGroupTitle(
            title = title,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        content()
    }
}

/** Shared heading style for both grouped cards and existing inset sections. */
@Composable
internal fun SettingsGroupTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.semantics { heading() },
        color = Theme[colors][mutedColor],
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    )
}
