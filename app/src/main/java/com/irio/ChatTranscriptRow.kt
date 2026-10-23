package com.irio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal val LocalChatImageStripOutset = staticCompositionLocalOf { 0.dp }

/** Keep ordinary message gutters inside the full-width RecyclerView viewport. */
@Composable
internal fun ChatTranscriptRow(content: @Composable () -> Unit) {
    val gutter = 16.dp
    CompositionLocalProvider(LocalChatImageStripOutset provides gutter) {
        Box(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
            content()
        }
    }
}
