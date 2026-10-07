package com.irio

import org.json.JSONArray
import org.json.JSONObject

data class ProviderHeader(val name: String, val value: String)

private val providerHeaderName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

/** Return a translatable error key, never a header value (which can be a credential). */
fun validateProviderHeaders(headers: List<ProviderHeader>): String? {
    val names = mutableSetOf<String>()
    for (header in headers) {
        if (!providerHeaderName.matches(header.name)) return "请求头名称无效"
        if (!names.add(header.name.lowercase(java.util.Locale.ROOT))) return "请求头名称不能重复"
        if (header.value.any { it != '\t' && it !in ' '..'~' }) return "请求头值只能包含可打印的 ASCII 字符或制表符"
    }
    return null
}

internal fun providerHeadersJson(headers: List<ProviderHeader>): JSONArray = JSONArray().apply {
    require(validateProviderHeaders(headers) == null) { "Invalid custom request headers" }
    headers.forEach { put(JSONObject().put("name", it.name).put("value", it.value)) }
}

internal fun providerHeadersFromJson(array: JSONArray?): List<ProviderHeader> {
    if (array == null) return emptyList()
    val headers = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        ProviderHeader(item.getString("name"), item.getString("value"))
    }
    require(validateProviderHeaders(headers) == null) { "Invalid custom request headers" }
    return headers
}

/** Preserve the conversation's pool, but never redirect arbitrary credential headers. */
internal fun ProviderConfig.requestClient(scope: String): okhttp3.OkHttpClient {
    val client = IrioHttp.clientFor(scope)
    return if (customHeaders.isEmpty() && !protocol.isGoogleGemini) client else client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
