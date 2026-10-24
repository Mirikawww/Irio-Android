package com.irio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.icu.text.Transliterator
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

internal const val SETTINGS_SEARCH_TARGET = "com.irio.settings.SEARCH_TARGET"

internal data class SettingsSearchEntry(
    val id: String,
    val title: String,
    val page: String,
    val destination: Class<out Activity>,
    /** 点进去后要闪烁的那一项。页面本身没有对应行时为空。 */
    val anchor: String? = id,
)

internal data class SettingsSearchHit(
    val id: String,
    val title: String,
    val page: String,
    val ranges: List<IntRange>,
    val destination: Class<out Activity>,
    val anchor: String?,
    val fuzzy: Boolean,
)

internal fun Context.openSettingsSearch(hit: SettingsSearchHit) {
    val intent = Intent(this, hit.destination)
    hit.anchor?.let { intent.putExtra(SETTINGS_SEARCH_TARGET, it) }
    startActivity(intent)
}

internal fun Context.settingsSearchTarget(): String =
    (this as? Activity)?.intent?.getStringExtra(SETTINGS_SEARCH_TARGET).orEmpty()

/**
 * 先按整段不区分大小写匹配，并高亮每一处。
 * 对不上时再按字符顺序做模糊匹配，高亮命中的字。
 * 还对不上，就按拼音匹配。汉字和拼音可以混着写，命中的字全部高亮。
 * 没有命中返回 null。
 */
internal fun settingsSearchRanges(
    title: String,
    query: String,
    pinyinOf: (Char) -> String = ::settingsPinyin,
): List<IntRange>? {
    val needle = query.trim()
    if (needle.isEmpty() || title.isEmpty()) return null
    val contiguous = contiguousRanges(title, needle)
    if (contiguous.isNotEmpty()) return contiguous
    return subsequenceRanges(title, needle) ?: pinyinRanges(title, needle, pinyinOf)
}

internal fun searchSettings(
    query: String,
    pinyinOf: (Char) -> String = ::settingsPinyin,
    label: (String) -> String = ::tr,
): List<SettingsSearchHit> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return settingsSearchEntries.mapNotNull { entry ->
        val title = label(entry.title)
        val ranges = settingsSearchRanges(title, needle, pinyinOf) ?: return@mapNotNull null
        val fuzzy = contiguousRanges(title, needle).isEmpty()
        SettingsSearchHit(
            id = entry.id,
            title = title,
            page = label(entry.page),
            ranges = ranges,
            destination = entry.destination,
            anchor = entry.anchor,
            fuzzy = fuzzy,
        )
    }.sortedWith(compareBy<SettingsSearchHit> { it.fuzzy }.thenBy { it.ranges.first().first }.thenBy { it.title.length })
}

private fun contiguousRanges(title: String, query: String): List<IntRange> {
    val haystack = title.lowercase()
    val needle = query.lowercase()
    if (needle.isEmpty() || needle.length > title.length) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (from <= title.length - needle.length) {
        val at = haystack.indexOf(needle, from)
        if (at < 0) break
        ranges += at until at + needle.length
        from = at + needle.length
    }
    return ranges
}

private fun subsequenceRanges(title: String, query: String): List<IntRange>? {
    val haystack = title.lowercase()
    val needle = query.lowercase()
    val hits = mutableListOf<Int>()
    var from = 0
    for (ch in needle) {
        val at = haystack.indexOf(ch, from)
        if (at < 0) return null
        hits += at
        from = at + 1
    }
    if (hits.isEmpty()) return null
    val ranges = mutableListOf<IntRange>()
    var start = hits.first()
    var end = hits.first()
    for (index in hits.drop(1)) {
        if (index == end + 1) {
            end = index
        } else {
            ranges += start..end
            start = index
            end = index
        }
    }
    ranges += start..end
    return ranges
}

private val hanToLatin: Transliterator? by lazy {
    sequenceOf("Han-Latin; Latin-ASCII", "Han-Latin", "Any-Latin; Latin-ASCII")
        .firstNotNullOfOrNull { id -> runCatching { Transliterator.getInstance(id) }.getOrNull() }
}

private val pinyinCache = ConcurrentHashMap<Char, String>()

/** 汉字转成不带声调、不带空格的拼音。转写不可用时返回空，搜索退回原文匹配。 */
internal fun settingsPinyin(ch: Char): String {
    if (ch.code < 0x3400 || ch.code > 0x9FFF) {
        return if (ch.isLetterOrDigit()) ch.lowercaseChar().toString() else ""
    }
    return pinyinCache.computeIfAbsent(ch) { han ->
        val transliterator = hanToLatin ?: return@computeIfAbsent ""
        val raw = runCatching {
            synchronized(transliterator) { transliterator.transliterate(han.toString()) }
        }.getOrDefault("")
        Normalizer.normalize(raw, Normalizer.Form.NFD).lowercase().filter { it in 'a'..'z' }
    }
}

private fun pinyinRanges(title: String, query: String, pinyinOf: (Char) -> String): List<IntRange>? {
    // 保留汉字。只丢掉空格和标点，这样「毛 boli」仍能连成一次匹配。
    val needle = query.lowercase().filter { it.isLetterOrDigit() }
    if (needle.isEmpty()) return null
    val syllables = Array(title.length) { index ->
        pinyinOf(title[index]).lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    }
    val covered = sortedSetOf<Int>()
    val path = ArrayList<Int>(needle.length)

    fun dfs(titleIndex: Int, pyOffset: Int, queryIndex: Int): Boolean {
        if (queryIndex == needle.length) {
            covered.addAll(path)
            return true
        }
        if (titleIndex >= title.length) return false
        val syllable = syllables[titleIndex]
        // 空格和标点不占拼音，跳过，避免把「Enter 发送」拦断。
        if (pyOffset == 0 && syllable.isEmpty() && title[titleIndex].code !in 0x3400..0x9FFF) {
            return dfs(titleIndex + 1, 0, queryIndex)
        }
        var matched = false
        val queryChar = needle[queryIndex]
        val literal = pyOffset == 0 && queryChar == title[titleIndex].lowercaseChar()
        if (literal) {
            path += titleIndex
            if (dfs(titleIndex + 1, 0, queryIndex + 1)) matched = true
            path.removeAt(path.lastIndex)
        }
        val sameAsLiteral = literal && syllable.length == 1 && syllable[0] == queryChar
        val pinyinLetter = queryChar in 'a'..'z' || queryChar in '0'..'9'
        if (!sameAsLiteral && pinyinLetter && pyOffset < syllable.length && syllable[pyOffset] == queryChar) {
            path += titleIndex
            val nextOffset = pyOffset + 1
            if (nextOffset == syllable.length) {
                if (dfs(titleIndex + 1, 0, queryIndex + 1)) matched = true
            } else if (dfs(titleIndex, nextOffset, queryIndex + 1)) {
                matched = true
            }
            path.removeAt(path.lastIndex)
        } else if (pyOffset > 0) {
            // 「毛b璃」里的 b 只写了「玻」的声母，后面的汉字要落到下一个字上。
            if (dfs(titleIndex + 1, 0, queryIndex)) matched = true
        }
        return matched
    }

    for (start in title.indices) {
        dfs(start, 0, 0)
        val syllable = syllables[start]
        for (offset in 1 until syllable.length) dfs(start, offset, 0)
    }
    if (covered.isEmpty()) return null
    val ranges = mutableListOf<IntRange>()
    var start = -1
    var end = -1
    for (index in covered) {
        if (start < 0) {
            start = index
            end = index
        } else if (index == end + 1) {
            end = index
        } else {
            ranges += start..end
            start = index
            end = index
        }
    }
    if (start >= 0) ranges += start..end
    return ranges
}

private val settingsSearchEntries = listOf(
    page("page.providers", "提供商", ProviderSettingsActivity::class.java),
    page("page.models", "模型", ModelSettingsActivity::class.java),
    page("page.search", "搜索", SearchSettingsActivity::class.java),
    page("page.speech", "TTS/STT", SpeechSettingsActivity::class.java),
    page("page.memory", "记忆", MemorySettingsActivity::class.java),
    page("page.mcp", "MCP", McpSettingsActivity::class.java),
    page("page.appearance", "外观与偏好", AppearanceSettingsActivity::class.java),
    page("page.link", "互连", LinkSettingsActivity::class.java),
    page("page.linux", "Linux 沙盒", LinuxSandboxSettingsActivity::class.java),
    page("page.other", "其他", OtherSettingsActivity::class.java),

    item("appearance.language", "语言", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.theme", "主题", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.accent", "强调色", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.amoled", "自动 AMOLED", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.background", "聊天背景", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.bubbleOpacity", "气泡透明度", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.glass", "毛玻璃", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.glass.bubble", "用户气泡", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.glass.sidebar", "侧边栏按钮", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.glass.ring", "上下文圆环", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.glass.switcher", "模型切换器", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.glass.composer", "消息框、语音框", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.userAvatar", "显示用户头像", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.font", "字体", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.font.sans", "非衬线体", "外观与偏好", AppearanceSettingsActivity::class.java, anchor = "appearance.font"),
    item("appearance.font.serif", "衬线体", "外观与偏好", AppearanceSettingsActivity::class.java, anchor = "appearance.font"),
    item("appearance.paste", "粘贴长文本为文件", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.paste.threshold", "字符阈值", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.retry", "自动重试", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.retry.limit", "重试次数上限", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.retry.cooldown", "重试冷却时长", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.glyph", "文本淡入", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.imageStreaming", "图片生成流式传输", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.diff", "模型差异", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.pickerLeft", "模型切换器居左", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.scroll", "新消息滚动", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.composerAnim", "消息框延展动画", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.enter", "按 Enter 发送", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.wrap", "代码块自动换行", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.lineNumbers", "代码块行号", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.ring", "上下文圆环", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.thinking", "默认折叠思考块", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.mergeTools", "合并工具调用显示", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.math", "公式大小", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.mergeModels", "合并模型显示", "外观与偏好", AppearanceSettingsActivity::class.java),
    item("appearance.names", "使用 OpenRouter 格式化模型名称", "外观与偏好", AppearanceSettingsActivity::class.java),

    item("model.tools", "工具", "模型", ModelSettingsActivity::class.java),
    item("model.rounds", "单轮工具调用", "模型", ModelSettingsActivity::class.java),
    item("model.smart", "更智能的工具调用提示", "模型", ModelSettingsActivity::class.java),
    item("model.subagents", "子智能体", "模型", ModelSettingsActivity::class.java),
    item("model.subagentModel", "子智能体默认模型", "模型", ModelSettingsActivity::class.java),
    item("model.image", "图片生成模型", "模型", ModelSettingsActivity::class.java),
    item("model.title", "标题生成模型", "模型", ModelSettingsActivity::class.java),
    item("model.prompt", "编辑系统提示词", "模型", ModelSettingsActivity::class.java),
    item("model.timestamps", "消息时间戳", "模型", ModelSettingsActivity::class.java),
    item("model.compression", "上下文压缩", "模型", ModelSettingsActivity::class.java),
    item("model.compressionModel", "压缩模型", "模型", ModelSettingsActivity::class.java),
    item("model.trigger", "触发时机", "模型", ModelSettingsActivity::class.java),
    item("model.target", "压缩目标", "模型", ModelSettingsActivity::class.java),
    item("model.visualize", "可视化上下文", "模型", ModelSettingsActivity::class.java),
    item("model.reasoning", "使用 OpenRouter 思考数据", "模型", ModelSettingsActivity::class.java),

    item("search.provider", "搜索提供商", "搜索", SearchSettingsActivity::class.java),
    item("search.limit", "单次搜索上限", "搜索", SearchSettingsActivity::class.java),
    item("search.image", "图片搜索提供商", "搜索", SearchSettingsActivity::class.java),
    item("search.imageLimit", "单次搜索上限", "搜索", SearchSettingsActivity::class.java),

    item("speech.voice", "朗读音色", "TTS/STT", SpeechSettingsActivity::class.java),
    item("speech.rate", "朗读速度", "TTS/STT", SpeechSettingsActivity::class.java),
    item("speech.provider", "STT 提供商", "TTS/STT", SpeechSettingsActivity::class.java),
    item("speech.model", "模型", "TTS/STT", SpeechSettingsActivity::class.java),
    item("speech.language", "语言", "TTS/STT", SpeechSettingsActivity::class.java),

    item("memory.enabled", "启用记忆", "记忆", MemorySettingsActivity::class.java),

    item("mcp.github", "GitHub", "MCP", McpSettingsActivity::class.java),
    item("mcp.todoist", "Todoist", "MCP", McpSettingsActivity::class.java),

    item("link.enabled", "启用互连", "互连", LinkSettingsActivity::class.java),
    item("link.mode", "同步方式", "互连", LinkSettingsActivity::class.java),
    item("link.auto", "自动同步数据", "互连", LinkSettingsActivity::class.java),

    item("linux.enabled", "启用 Linux 沙盒", "Linux 沙盒", LinuxSandboxSettingsActivity::class.java),
    item("linux.permission", "授予权限", "Linux 沙盒", LinuxSandboxSettingsActivity::class.java),
    item("linux.rebuild", "用安装包里的版本重新准备", "Linux 沙盒", LinuxSandboxSettingsActivity::class.java),
    item("linux.commands", "单次命令数", "Linux 沙盒", LinuxSandboxSettingsActivity::class.java),
    item("linux.background", "后台命令", "Linux 沙盒", LinuxSandboxSettingsActivity::class.java),
    item("linux.delete", "删除 Linux 沙盒", "Linux 沙盒", LinuxSandboxSettingsActivity::class.java),

    item("other.history", "删除所有历史", "其他", OtherSettingsActivity::class.java),
    item("other.export", "导出数据", "其他", OtherSettingsActivity::class.java),
    item("other.import", "导入数据", "其他", OtherSettingsActivity::class.java),

    item("provider.add", "添加提供商", "提供商", ProviderSettingsActivity::class.java),
)

private fun page(id: String, title: String, destination: Class<out Activity>) =
    SettingsSearchEntry(id, title, "设置", destination, anchor = null)

private fun item(
    id: String,
    title: String,
    page: String,
    destination: Class<out Activity>,
    anchor: String = id,
) = SettingsSearchEntry(id, title, page, destination, anchor = anchor)
