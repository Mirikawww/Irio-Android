package com.irio

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class SearchResultItem(
    val url: String,
    val title: String,
    val snippet: String,
    val siteName: String,
    val faviconUrl: String,
    val publishedAt: String = "",
)

internal data class SearchResultsContent(
    val items: List<SearchResultItem> = emptyList(),
    val fallback: String = "",
)

/** Both providers' original metadata is kept for display, independently of the model's text. */
internal fun searchResultsContent(body: String): SearchResultsContent {
    val trimmed = body.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        val root = runCatching { JSONObject(trimmed) }.getOrNull()
        val results = root?.optJSONArray("results")
            ?: runCatching { JSONArray(trimmed) }.getOrNull()
        val items = if (results == null) emptyList() else (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            searchResultItem(
                url = item.searchString("url"),
                title = item.searchString("title"),
                snippet = item.searchString("content", "text", "summary", "snippet").ifBlank {
                    item.optJSONArray("highlights")?.let { highlights ->
                        (0 until highlights.length()).joinToString(" ") { highlights.optString(it) }
                    }.orEmpty()
                },
                siteName = item.searchString("site_name", "siteName"),
                favicon = item.searchString("favicon", "favicon_url"),
                publishedAt = item.searchString("publishedDate", "published_date", "published_at"),
            )
        }
        if (root != null || results != null) return SearchResultsContent(
            items = items,
            fallback = root?.searchString("answer", "context", "message").orEmpty(),
        )
    }
    // Older conversations stored title / URL / body blocks instead of JSON.
    val lines = body.lines()
    val urls = lines.indices.filter { safeSearchUrl(lines[it].trim()) != null }
    if (urls.isEmpty()) return SearchResultsContent(fallback = body)
    fun titleIndex(urlIndex: Int): Int = (urlIndex - 1).takeIf {
        it >= 0 && lines[it].isNotBlank() && safeSearchUrl(lines[it].trim()) == null
    } ?: urlIndex
    return SearchResultsContent(items = urls.mapIndexedNotNull { index, urlIndex ->
        val nextStart = urls.getOrNull(index + 1)?.let(::titleIndex) ?: lines.size
        searchResultItem(
            url = lines[urlIndex].trim(),
            title = lines[titleIndex(urlIndex)].takeIf { titleIndex(urlIndex) != urlIndex }.orEmpty(),
            snippet = lines.subList(urlIndex + 1, nextStart).joinToString(" "),
        )
    })
}

private fun JSONObject.searchString(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
}.orEmpty()

private fun safeSearchUrl(value: String) = value.toHttpUrlOrNull()?.takeIf {
    it.username.isEmpty() && it.password.isEmpty() && value.none(Char::isWhitespace)
}

private fun searchResultItem(
    url: String,
    title: String,
    snippet: String,
    siteName: String = "",
    favicon: String = "",
    publishedAt: String = "",
): SearchResultItem? {
    val parsed = safeSearchUrl(url) ?: return null
    val faviconUrl = parsed.resolve(favicon.takeIf { it.isNotBlank() } ?: "/favicon.ico")
        ?.toString()?.takeIf { safeSearchUrl(it) != null }.orEmpty()
    return SearchResultItem(
        url = url,
        title = searchPreviewText(title).ifBlank { parsed.host },
        snippet = searchPreviewText(snippet),
        siteName = searchPreviewText(siteName).ifBlank { parsed.host.removePrefix("www.") },
        faviconUrl = faviconUrl,
        publishedAt = publishedAt,
    )
}

private fun searchPreviewText(value: String): String = value
    .replace(Regex("<[^>]*>"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
