package com.irio

import java.util.concurrent.atomic.AtomicLong

/**
 * 全局单调消息 id 分配器：所有对话共用，跨进程重启也保证唯一递增。
 *
 * 为什么不能用「每个对话从 1 开始的计数器」：消息 id 会当 [ChatMessage] 在
 * generations 表里的键、Markdown 渲染缓存的关联锚点用，两个对话出现同号 id 就会
 * 互相覆盖正文（流式回写按 id 定位消息）——即「对话间输出互串」。持久化计数器
 * 需要启动 IO + 写盘竞态处理，直接用墙钟毫秒 × 1000 + 进程内序列：重启后墙钟必然
 * 大于旧值，同毫秒多次分配由序列号兜底，零 IO。
 */
object MessageIds {
    private const val SCALE = 1000L
    private val lastIssued = AtomicLong(0L)

    fun next(): Long {
        while (true) {
            val prev = lastIssued.get()
            val candidate = System.currentTimeMillis() * SCALE
            val next = if (candidate > prev) candidate else prev + 1
            if (lastIssued.compareAndSet(prev, next)) return next
        }
    }
}
