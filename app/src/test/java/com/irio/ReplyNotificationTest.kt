package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyNotificationTest {
    @Test fun blankFramesAndNewRoundsNeverPretendTheModelIsWriting() {
        assertEquals("请求中", advanceReplyNotice("请求中", ChatStreamUpdate(delta = " \n\t")))
        assertEquals("请求中", advanceReplyNotice("输出中", ChatStreamUpdate(newRound = true)))
        assertEquals("请求中", advanceReplyNotice("已获取时间", ChatStreamUpdate(newRound = true)))
        assertEquals("请求中", advanceReplyNotice("请求中", ChatStreamUpdate(model = "model", usage = TokenUsage(10, 0, 10))))
        assertEquals("思考中", advanceReplyNotice("请求中", ChatStreamUpdate(thinkingDelta = "reasoning", delta = "\n")))
        assertEquals("输出中", advanceReplyNotice("请求中", ChatStreamUpdate(delta = "正文")))
        assertEquals("思考中", advanceReplyNotice("输出中", ChatStreamUpdate(newRound = true, thinkingDelta = "reasoning")))
        assertEquals("请求", replyStatusChip("请求中"))
        assertEquals("请求中", advanceReplyNotice("请求中", ChatProtocol.parseChatCompletions(
            """{"choices":[{"delta":{"role":"assistant","content":null}}]}"""
        )!!))
    }

    @Test
    fun previewKeepsOnlyTheFirst25Characters() {
        val text = "一二三四五六七八九十一二三四五六七八九十甲乙丙丁戊多出来"
        assertEquals(25, replyNotificationPreview(text).length)
        assertEquals("一二三四五六七八九十一二三四五六七八九十甲乙丙丁戊", replyNotificationPreview(text))
    }

    @Test
    fun previewCollapsesWhitespaceAndDropsABlankReply() {
        assertEquals("你好 世界", replyNotificationPreview("\n\n你好\n世界  "))
        assertEquals("", replyNotificationPreview(" \n\t"))
        assertEquals("短", replyNotificationPreview("短"))
    }

    @Test
    fun liveNoticeFollowsEachStepIncludingSilentTools() {
        var step = "响应中"
        step = advanceReplyNotice(step, ChatStreamUpdate(thinkingDelta = "想"))
        assertEquals("思考中", step)
        step = advanceReplyNotice(step, ChatStreamUpdate(toolActive = true, toolName = TimeTool.NAME, displayText = "查询当地时间"))
        assertEquals("查询当地时间", step)
        step = advanceReplyNotice(step, ChatStreamUpdate(notifyTool = ReactionTool.NAME))
        assertEquals("表情回应", step)
        step = advanceReplyNotice(step, ChatStreamUpdate(notifyTool = SubagentTools.INSPECT))
        assertEquals("查看子智能体", step)
        step = advanceReplyNotice(step, ChatStreamUpdate(displayedImages = "{}"))
        assertEquals("展示图片", step)
        step = advanceReplyNotice(step, ChatStreamUpdate(coloredBubbles = listOf(ColoredBubble("hi", "#fff"))))
        assertEquals("发送彩色气泡", step)
        step = advanceReplyNotice(step, ChatStreamUpdate(subagentJson = "{}"))
        assertEquals("创建子智能体", step)
        step = advanceReplyNotice(step, ChatStreamUpdate(delta = "正文"))
        assertEquals("输出中", step)
        step = advanceReplyNotice(step, ChatStreamUpdate(), finished = true, preview = "正文前二十五字")
        assertEquals("正文前二十五字", step)
        assertEquals("完成", advanceReplyNotice("输出中", ChatStreamUpdate(), finished = true, preview = ""))
        assertEquals(
            "已生成图片",
            advanceReplyNotice(
                "图片生成中",
                ChatStreamUpdate(toolActive = false, toolName = ImageGenTool.NAME, toolContent = ImageGenTool.STATUS_OK),
            ),
        )
    }

    @Test
    fun finishedReplyStaysUpForTenSeconds() {
        assertEquals(10_000L, REPLY_DONE_VISIBLE_MS)
    }

    @Test
    fun statusChipStaysShortEnoughForTheLiveUpdate() {
        assertEquals("响应", replyStatusChip("响应中"))
        assertEquals("思考", replyStatusChip("思考中"))
        assertEquals("输出", replyStatusChip("输出中"))
        assertEquals("展示图片", replyStatusChip("展示图片"))
        assertEquals("创建子智能体", replyStatusChip("创建子智能体"))
        assertEquals("一二三四五六", replyStatusChip("一二三四五六七八"))
    }

    @Test
    fun notifiesOnlyAfterACompletedReplyWhileBackgrounded() {
        val preview = replyNotificationPreview("模型已经说完了")
        assertTrue(shouldNotifyReplyCompletion(completed = true, inForeground = false, preview = preview))
        assertFalse(shouldNotifyReplyCompletion(completed = true, inForeground = true, preview = preview))
        assertFalse(shouldNotifyReplyCompletion(completed = false, inForeground = false, preview = preview))
        assertFalse(shouldNotifyReplyCompletion(completed = true, inForeground = false, preview = ""))
    }
}
