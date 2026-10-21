package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme

@Composable
internal fun SearchResultsSheetContent(body: String, modifier: Modifier = Modifier) {
    val results = remember(body) { searchResultsContent(body) }
    if (results.items.isEmpty()) {
        Column(modifier) {
            Text(
                results.fallback.ifBlank { tr("没有搜索结果") },
                fontSize = 16.sp,
                lineHeight = 22.sp,
                color = Theme[colors][onPanelColor],
            )
        }
        return
    }
    AndroidComposeRecyclerList(
        items = results.items.withIndex().toList(),
        key = { "${it.index}:${it.value.url}" },
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 16.dp),
        itemSpacing = 24.dp,
    ) { SearchResultRow(it.value) }
}

@Composable
internal fun SearchResultRow(item: SearchResultItem) {
    val uriHandler = LocalUriHandler.current
    val foreground = Theme[colors][onPanelColor]
    val muted = Theme[colors][mutedColor]
    Column(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button) { runCatching { uriHandler.openUri(item.url) } }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Theme[colors][secondaryColor]),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.siteName.take(1).uppercase(), color = foreground, fontSize = 13.sp, lineHeight = 18.sp)
                AsyncImage(
                    model = item.faviconUrl,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(item.siteName, color = foreground, fontSize = 14.sp, lineHeight = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.url, color = muted, fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(item.title, color = Theme[colors][primaryColor], fontSize = 20.sp, lineHeight = 26.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        val preview = listOf(item.publishedAt, item.snippet).filter { it.isNotBlank() }.joinToString(" — ")
        if (preview.isNotEmpty()) {
            Text(preview, color = muted, fontSize = 14.sp, lineHeight = 20.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
