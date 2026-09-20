package com.irio

enum class ProcessEventKind {
    THINKING,
    SEARCH,
    TOOL,
}

data class ProcessEvent(
    val id: Long,
    val kind: ProcessEventKind,
    val content: String = "",
    val millis: Long = 0L,
    val pages: Int = 0,
    val live: Boolean = false,
    /** 本事件在模型正文开始输出后才启动：UI 渲染在已输出文本下方，而不是正文上方。 */
    val belowText: Boolean = false,
    /** 本地工具名，如 get_time / get_memory / update_memory。 */
    val toolName: String = "",
)
