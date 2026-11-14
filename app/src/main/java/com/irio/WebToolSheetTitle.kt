package com.irio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme
import org.json.JSONObject

/** Use original request metadata, never a generated status hint or the URL's domain alone. */
internal fun webToolSheetTitle(event: ProcessEvent): String {
    val key = when {
        event.kind == ProcessEventKind.SEARCH || event.toolName == WebSearchTool.NAME -> {
            if (event.searchQuery.isNotBlank()) return event.searchQuery
            "query"
        }
        event.toolName == WebFetchTool.NAME -> "url"
        else -> return ""
    }
    return runCatching { JSONObject(event.toolArguments).opt(key) as? String }.getOrNull().orEmpty()
}

@Composable
internal fun WebToolSheetTitle(event: ProcessEvent?) {
    val title = remember(event) { event?.let(::webToolSheetTitle).orEmpty() }
    if (title.isBlank()) return
    Text(title,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).semantics { heading() },
        color = Theme[colors][onPanelColor], fontSize = 18.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold)
}
