package com.irio

/** Access-ordered conversation bodies; callers synchronize compound reads/writes. */
internal class ConversationContentCache(private val maxEntries: Int = 8) :
    LinkedHashMap<String, Conversation>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Conversation>): Boolean =
        size > maxEntries
}
