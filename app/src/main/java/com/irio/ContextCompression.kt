package com.irio

/** 压缩边界。NONE 是普通消息；只有 SUCCESS 才把更早的消息隔在模型的新对话之外。界面只画状态行，不画摘要正文。 */
enum class SummaryState {
    NONE,
    RUNNING,
    SUCCESS,
    FAILED,
}

/** 消息列表里的状态文案。不带摘要正文。 */
internal fun compressionStatusLabel(state: SummaryState): String? = when (state) {
    SummaryState.RUNNING -> "上下文压缩中"
    SummaryState.SUCCESS -> "已压缩上下文"
    SummaryState.FAILED -> "上下文压缩失败"
    SummaryState.NONE -> null
}
