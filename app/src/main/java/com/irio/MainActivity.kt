package com.irio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleConversationIntent(intent)
        ReplyLiveNotice.install(this)
        LinkRuntime.ensure(this)
        StartupUpdates.start(this)
        enableIrioGlassBackdrop()
        setIrioContent { MainScreen(restoreLastOpened = savedInstanceState != null) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConversationIntent(intent)
    }

    private fun handleConversationIntent(intent: Intent?) {
        ReplyOpenRequest.publish(intent)
        if (ReplyOpenRequest.conversationId != null) {
            ChatIdleTracker.get(this).openExplicitConversation()
        }
    }
}
