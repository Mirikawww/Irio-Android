package com.irio

import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.ui.platform.ComposeView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MessageAdapterSnapshotTest {
    @Test
    fun aPendingInsertionCannotBindAnotherMessagesTextAndReactionToACommittedId() = withAdapter { list, adapter, tasks ->
        val previous = ChatMessage(1L, "old long bubble", true, modelReaction = "❤️")
        val inserted = ChatMessage(2L, "new", true)
        list.updateMessages(listOf(previous))
        list.updateMessages(listOf(inserted, previous))
        assertTrue(list.isMessageUpdatePending())
        val holder = adapter.createViewHolder(list, 0)
        try {
            adapter.onBindViewHolder(holder, 0, mutableListOf(Any()))
            assertEquals("The bound content must belong to RecyclerView's committed stable id", adapter.currentList[0].id, boundMessage(holder).id)
            assertEquals(previous, boundMessage(holder))
            tasks.runAll()
            adapter.onBindViewHolder(holder, 0, mutableListOf(Any()))
            assertEquals(inserted, boundMessage(holder))
            assertEquals(inserted.id, adapter.currentList[0].id)
        } finally {
            (holder.itemView as ComposeView).disposeComposition()
        }
    }

    @Test
    fun revertingToTheCommittedStructureCancelsAnOlderPendingSubmission() = withAdapter { list, adapter, tasks ->
        val original = listOf(ChatMessage(1L, "original", true))
        var originalCommits = 0
        list.updateMessages(original)
        list.updateMessages(listOf(ChatMessage(2L, "obsolete", true)))
        list.updateMessages(original) { originalCommits++ }
        tasks.runAll()
        assertEquals(original, adapter.currentList)
        assertFalse(list.isMessageUpdatePending())
        assertEquals(1, originalCommits)
    }

    @Test
    fun fullRebindsKeepTheLatestPayloadContentForTheSameId() = withAdapter { list, adapter, tasks ->
        val pending = ChatMessage(1L, "", false, isPending = true)
        val finished = pending.copy(text = "actual completed reply", isPending = false)
        list.updateMessages(listOf(pending))
        list.updateMessages(listOf(finished))
        tasks.runAll()
        val holder = adapter.createViewHolder(list, 0)
        try {
            adapter.onBindViewHolder(holder, 0, mutableListOf(Any()))
            assertEquals(finished, boundMessage(holder))
        } finally {
            (holder.itemView as ComposeView).disposeComposition()
        }
    }

    private fun boundMessage(holder: RecyclerView.ViewHolder): ChatMessage {
        val field = holder.javaClass.getDeclaredField("message\$delegate").apply { isAccessible = true }
        return (field.get(holder) as State<*>).value as ChatMessage
    }

    @Suppress("UNCHECKED_CAST")
    private fun withAdapter(test: (MessageRecyclerView, ListAdapter<ChatMessage, RecyclerView.ViewHolder>, QueuedExecutor) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val list = MessageRecyclerView(controller.get())
        val adapter = list.adapter as ListAdapter<ChatMessage, RecyclerView.ViewHolder>
        val tasks = QueuedExecutor()
        val config = AsyncDifferConfig.Builder(object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
        }).setBackgroundThreadExecutor(tasks).setMainThreadExecutor(Executor { it.run() }).build()
        // Control the real ListAdapter diff queue without changing production threading or APIs.
        ListAdapter::class.java.getDeclaredField("mDiffer").apply { isAccessible = true }
            .set(adapter, AsyncListDiffer(AdapterListUpdateCallback(adapter), config))
        try {
            test(list, adapter, tasks)
        } finally {
            tasks.runAll()
            controller.pause().stop().destroy()
        }
    }

    private class QueuedExecutor : Executor {
        private val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeAt(0).run() }
    }
}
