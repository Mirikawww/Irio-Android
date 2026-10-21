package com.irio

import androidx.compose.ui.graphics.Color

internal fun toolStatusTextColor(outcome: ToolOutcome?, dark: Boolean, muted: Color, error: Color): Color = when (outcome) {
    ToolOutcome.FAILED -> error
    // Darker yellow remains readable on the light background.
    ToolOutcome.STOPPED -> if (dark) Color(0xFFFACC15) else Color(0xFF8A6D00)
    else -> muted
}

internal fun toolFailureLabel(name: String): String = when (name) {
    WebSearchTool.NAME -> tr("搜索失败")
    WebFetchTool.NAME -> tr("浏览网页失败")
    ImageGenTool.NAME -> tr("生成图片失败")
    CommonsImageTools.SEARCH -> tr("图片搜索失败")
    CommonsImageTools.VIEW -> tr("图片查看失败")
    CommonsImageTools.SHOW -> tr("图片展示失败")
    HistoryTool.NAME -> tr("历史会话查询失败")
    TimeTool.NAME -> tr("获取时间失败")
    MemoryTool.GET -> tr("读取记忆失败")
    MemoryTool.UPDATE -> tr("写入记忆失败")
    LinuxShellTool.NAME -> tr("运行命令失败")
    LinuxBackgroundTool.NAME -> tr("处理后台命令失败")
    SubagentTools.CREATE -> tr("创建子智能体失败")
    SubagentTools.INSPECT -> tr("查看子智能体失败")
    SubagentTools.MESSAGE -> tr("联系子智能体失败")
    SubagentTools.STOP -> tr("停止子智能体失败")
    ReactionTool.NAME -> tr("表情回应失败")
    ColoredBubbleTool.NAME -> tr("发送彩色气泡失败")
    else -> tr("%s失败").format(MemoryTool.liveLabel(name))
}

internal fun toolStoppedLabel(name: String, webDomain: String = ""): String = when (name) {
    WebSearchTool.NAME -> tr("搜索已停止")
    WebFetchTool.NAME -> tr("网页读取已停止")
    ImageGenTool.NAME -> tr("图片生成已停止")
    CommonsImageTools.SEARCH -> tr("图片搜索已停止")
    CommonsImageTools.VIEW -> tr("图片查看已停止")
    HistoryTool.NAME -> tr("历史会话查询已停止")
    else -> tr("%s已停止").format(MemoryTool.liveLabel(name, webDomain))
}

/** Custom action text may describe success, but may never mask failure/cancellation. */
internal fun ProcessEvent.completedLabel(): String = when {
    toolOutcome == ToolOutcome.FAILED -> toolFailureLabel(if (kind == ProcessEventKind.SEARCH) WebSearchTool.NAME else toolName)
    toolOutcome == ToolOutcome.STOPPED -> if (kind == ProcessEventKind.SEARCH) tr("搜索已停止")
        else MemoryTool.doneLabel(toolName, content, webDomain, toolOutcome)
    displayText.isNotEmpty() -> displayText
    kind == ProcessEventKind.SEARCH -> searchCompletedLabel(pages, searchQuery)
    else -> MemoryTool.doneLabel(toolName, content, webDomain, toolOutcome)
}
