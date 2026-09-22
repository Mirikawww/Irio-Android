package com.irio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 抽屉冷启动只读 index。旧索引是 id 数组，第一次加载用流式扫描抽出摘要
 * （跳过 messages，不建正文对象树）并写成 v2；之后只读这份摘要。
 */
internal object ConversationSummaries {
    private const val INDEX = "index.json"
    private val lock = Any()

    fun load(dir: File): List<ConversationSummary> = synchronized(lock) {
        readUnlocked(dir).sortedWith(summaryOrder)
    }

    fun upsert(dir: File, summary: ConversationSummary): Boolean = synchronized(lock) {
        upsertUnlocked(dir, summary)
    }

    /**
     * 正文和摘要在同一把锁里提交。updatedAt 更旧的写入直接丢弃，
     * 避免后完成的旧 save 把抽屉标题改回去，或把较新的正文覆盖掉。
     */
    fun commit(dir: File, summary: ConversationSummary, writeBody: () -> Unit): Boolean = synchronized(lock) {
        val existing = readUnlocked(dir)
        val previous = existing.find { it.id == summary.id }
        if (previous != null && previous.updatedAt > summary.updatedAt) return false
        writeBody()
        write(dir, existing.filterNot { it.id == summary.id } + summary)
        true
    }

    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    fun remove(dir: File, id: String) {
        synchronized(lock) {
            write(dir, readUnlocked(dir).filterNot { it.id == id })
        }
    }

    fun replace(dir: File, summaries: List<ConversationSummary>) {
        synchronized(lock) {
            write(dir, summaries)
        }
    }

    private fun readUnlocked(dir: File): List<ConversationSummary> {
        if (!dir.isDirectory) return emptyList()
        val index = File(dir, INDEX)
        if (!index.isFile) return emptyList()
        val text = runCatching { index.readText().trim() }.getOrDefault("")
        return runCatching {
            when {
                text.startsWith("{") -> decodeV2(text)
                text.startsWith("[") -> {
                    val migrated = scanLegacy(dir, text)
                    write(dir, migrated)
                    migrated
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun upsertUnlocked(dir: File, summary: ConversationSummary): Boolean {
        val existing = readUnlocked(dir)
        val previous = existing.find { it.id == summary.id }
        if (previous != null && previous.updatedAt > summary.updatedAt) return false
        write(dir, existing.filterNot { it.id == summary.id } + summary)
        return true
    }

    private fun scanLegacy(dir: File, text: String): List<ConversationSummary> {
        return decodeLegacyIds(text).mapNotNull { id ->
            val file = File(dir, "$id.json")
            if (!file.isFile) return@mapNotNull null
            summaryFromFile(file, id)
        }
    }

    private fun decodeV2(text: String): List<ConversationSummary> {
        val items = JSONObject(text).optJSONArray("items") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    ConversationSummary(
                        id = id,
                        title = item.optString("title").ifBlank { "新对话" },
                        updatedAt = item.optLong("updatedAt"),
                        pinned = item.optBoolean("pinned"),
                        titlePending = item.optBoolean("titlePending"),
                    ),
                )
            }
        }
    }

    private fun write(dir: File, summaries: List<ConversationSummary>) {
        dir.mkdirs()
        val items = JSONArray()
        summaries.forEach { summary ->
            items.put(
                JSONObject()
                    .put("id", summary.id)
                    .put("title", summary.title)
                    .put("updatedAt", summary.updatedAt)
                    .put("pinned", summary.pinned)
                    .put("titlePending", summary.titlePending),
            )
        }
        val payload = JSONObject().put("v", 2).put("items", items).toString()
        val target = File(dir, INDEX)
        val tmp = File(dir, "$INDEX.tmp")
        tmp.writeText(payload)
        val source = tmp.toPath()
        val dest = target.toPath()
        runCatching {
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun decodeLegacyIds(text: String): List<String> {
        val array = JSONArray(text)
        return buildList {
            for (index in 0 until array.length()) {
                val id = array.optString(index)
                if (id.isNotBlank()) add(id)
            }
        }
    }

    private val summaryOrder =
        compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt }
}

private fun summaryFromFile(file: File, fallbackId: String): ConversationSummary? {
    val scanned = runCatching {
        file.inputStream().use { summaryFromStream(it, fallbackId) }
    }.getOrNull()
    if (scanned != null) return scanned
    return runCatching {
        val json = JSONObject(file.readText())
        val id = json.optString("id").ifBlank { fallbackId }
        if (id.isBlank()) null
        else ConversationSummary(
            id = id,
            title = json.optString("title").ifBlank { "新对话" },
            updatedAt = json.optLong("updatedAt"),
            pinned = json.optBoolean("pinned"),
            titlePending = json.optBoolean("titlePending"),
        )
    }.getOrNull()
}

private fun summaryFromStream(input: InputStream, fallbackId: String): ConversationSummary? {
    val reader = JsonByteReader(input)
    reader.skipWs()
    if (reader.peek() != '{'.code) return null
    reader.next()
    var id = ""
    var title = ""
    var updatedAt = 0L
    var pinned = false
    var titlePending = false
    while (true) {
        reader.skipWs()
        when (reader.peek()) {
            '}'.code -> break
            ','.code -> {
                reader.next()
                continue
            }
            '"'.code -> Unit
            else -> return null
        }
        val key = reader.readString() ?: return null
        reader.skipWs()
        if (reader.next() != ':'.code) return null
        reader.skipWs()
        when (key) {
            "id" -> id = reader.readString() ?: return null
            "title" -> title = reader.readString() ?: return null
            "updatedAt" -> updatedAt = reader.readLong()
            "pinned" -> pinned = reader.readBoolean()
            "titlePending" -> titlePending = reader.readBoolean()
            else -> if (!reader.skipValue()) return null
        }
    }
    val resolvedId = id.ifBlank { fallbackId }
    if (resolvedId.isBlank()) return null
    return ConversationSummary(
        id = resolvedId,
        title = title.ifBlank { "新对话" },
        updatedAt = updatedAt,
        pinned = pinned,
        titlePending = titlePending,
    )
}

private class JsonByteReader(private val input: InputStream) {
    private val buf = ByteArray(8192)
    private var len = 0
    private var pos = 0
    private var pushed = Int.MIN_VALUE

    fun peek(): Int {
        if (pushed == Int.MIN_VALUE) pushed = next()
        return pushed
    }

    fun next(): Int {
        if (pushed != Int.MIN_VALUE) {
            val value = pushed
            pushed = Int.MIN_VALUE
            return value
        }
        if (pos >= len) {
            len = input.read(buf)
            pos = 0
            if (len < 0) return -1
        }
        return buf[pos++].toInt() and 0xFF
    }

    fun skipWs() {
        while (true) {
            when (peek()) {
                ' '.code, '\n'.code, '\r'.code, '\t'.code -> next()
                else -> return
            }
        }
    }

    fun readString(): String? {
        if (next() != '"'.code) return null
        val out = StringBuilder()
        while (true) {
            val b = next()
            when {
                b < 0 -> return null
                b == '"'.code -> return out.toString()
                b == '\\'.code -> if (!appendEscape(out)) return null
                else -> {
                    val cp = decodeCodePoint(b)
                    if (cp < 0) return null
                    out.appendCodePoint(cp)
                }
            }
        }
    }

    fun readLong(): Long {
        val token = StringBuilder()
        if (peek() == '-'.code) token.append(next().toChar())
        while (true) {
            val p = peek()
            if (p in '0'.code..'9'.code || p == '.'.code || p == 'e'.code || p == 'E'.code || p == '+'.code) {
                token.append(next().toChar())
            } else {
                break
            }
        }
        val text = token.toString()
        return text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong() ?: 0L
    }

    fun readBoolean(): Boolean {
        return if (peek() == 't'.code) {
            expectLiteral("true")
            true
        } else {
            expectLiteral("false")
            false
        }
    }

    fun skipValue(): Boolean {
        skipWs()
        return when (peek()) {
            '"'.code -> skipString()
            '{'.code -> {
                next()
                skipContainer('{'.code, '}'.code)
            }
            '['.code -> {
                next()
                skipContainer('['.code, ']'.code)
            }
            't'.code -> expectLiteral("true")
            'f'.code -> expectLiteral("false")
            'n'.code -> expectLiteral("null")
            '-'.code, in '0'.code..'9'.code -> {
                readLong()
                true
            }
            else -> false
        }
    }

    private fun skipContainer(open: Int, close: Int): Boolean {
        var depth = 1
        while (depth > 0) {
            when (val b = next()) {
                -1 -> return false
                '"'.code -> if (!skipStringContents()) return false
                else -> when (b) {
                    open -> depth++
                    close -> depth--
                }
            }
        }
        return true
    }

    private fun skipString(): Boolean {
        if (next() != '"'.code) return false
        return skipStringContents()
    }

    private fun skipStringContents(): Boolean {
        while (true) {
            when (val b = next()) {
                -1 -> return false
                '"'.code -> return true
                '\\'.code -> if (!skipEscape()) return false
                else -> if (b >= 0x80 && !skipUtf8Continuation(b)) return false
            }
        }
    }

    private fun skipEscape(): Boolean {
        val escaped = next()
        if (escaped < 0) return false
        if (escaped == 'u'.code) {
            repeat(4) { if (hexValue(next()) < 0) return false }
        }
        return true
    }

    private fun appendEscape(out: StringBuilder): Boolean {
        when (val escaped = next()) {
            '"'.code, '\\'.code, '/'.code -> out.append(escaped.toChar())
            'b'.code -> out.append('\b')
            'f'.code -> out.append('\u000C')
            'n'.code -> out.append('\n')
            'r'.code -> out.append('\r')
            't'.code -> out.append('\t')
            'u'.code -> {
                var value = 0
                repeat(4) {
                    val digit = hexValue(next())
                    if (digit < 0) return false
                    value = (value shl 4) or digit
                }
                out.append(value.toChar())
            }
            else -> return false
        }
        return true
    }

    private fun decodeCodePoint(first: Int): Int {
        if (first and 0x80 == 0) return first
        val count: Int
        val mask: Int
        val min: Int
        when {
            first and 0xE0 == 0xC0 -> {
                count = 1
                mask = 0x1F
                min = 0x80
            }
            first and 0xF0 == 0xE0 -> {
                count = 2
                mask = 0x0F
                min = 0x800
            }
            first and 0xF8 == 0xF0 -> {
                count = 3
                mask = 0x07
                min = 0x10000
            }
            else -> return -1
        }
        var cp = first and mask
        repeat(count) {
            val nextByte = next()
            if (nextByte and 0xC0 != 0x80) return -1
            cp = (cp shl 6) or (nextByte and 0x3F)
        }
        if (cp < min || cp > 0x10FFFF) return -1
        return cp
    }

    private fun skipUtf8Continuation(first: Int): Boolean {
        val count = when {
            first and 0xE0 == 0xC0 -> 1
            first and 0xF0 == 0xE0 -> 2
            first and 0xF8 == 0xF0 -> 3
            else -> return false
        }
        repeat(count) {
            if (next() and 0xC0 != 0x80) return false
        }
        return true
    }

    private fun expectLiteral(literal: String): Boolean {
        for (ch in literal) {
            if (next() != ch.code) return false
        }
        return true
    }

    private fun hexValue(byte: Int): Int = when (byte) {
        in '0'.code..'9'.code -> byte - '0'.code
        in 'a'.code..'f'.code -> byte - 'a'.code + 10
        in 'A'.code..'F'.code -> byte - 'A'.code + 10
        else -> -1
    }
}
