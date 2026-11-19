package com.irio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

internal const val ChatIdleTimeoutMs = 2 * 60 * 60 * 1_000L
private const val CheckpointIntervalMs = 30_000L

internal val LocalChatIdleTracker = staticCompositionLocalOf<ChatIdleTracker?> { null }

/** Main-thread clock shared by the chat and settings windows. */
internal class ChatIdleTracker(context: Context) {
    private val preferences = context.getSharedPreferences("chat_idle", Context.MODE_PRIVATE)
    private val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutCheck = Runnable { checkTimeout() }
    private var lastCheckpoint = SystemClock.elapsedRealtime()
    private var lastInteraction = restoreInteractionTime()
    private var dirty = !preferences.contains("wall_time")
    private var expired = false

    var resetPending by mutableStateOf(false)
        private set

    init {
        checkpoint()
        checkTimeout()
    }

    val isIdle: Boolean
        get() = SystemClock.elapsedRealtime() - lastInteraction >= ChatIdleTimeoutMs

    fun checkTimeout() {
        val remaining = ChatIdleTimeoutMs - (SystemClock.elapsedRealtime() - lastInteraction)
        handler.removeCallbacks(timeoutCheck)
        if (remaining <= 0L) {
            if (!expired) {
                expired = true
                resetPending = true
            }
        } else {
            handler.postDelayed(timeoutCheck, remaining)
        }
    }

    fun recordInteraction() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInteraction >= ChatIdleTimeoutMs) checkTimeout()
        lastInteraction = now
        expired = false
        dirty = true
        if (now - lastCheckpoint >= CheckpointIntervalMs) checkpoint()
        // Leave the existing callback queued. It will recheck the deadline and reschedule if needed.
        if (!handler.hasCallbacks(timeoutCheck)) handler.postDelayed(timeoutCheck, ChatIdleTimeoutMs)
    }

    fun openExplicitConversation() {
        recordInteraction()
        consumeReset()
        checkpoint()
    }

    fun consumeReset() {
        resetPending = false
    }

    fun checkpoint() {
        if (!dirty) return
        val now = SystemClock.elapsedRealtime()
        preferences.edit()
            .putInt("boot_count", bootCount)
            .putLong("elapsed_time", lastInteraction)
            .putLong("wall_time", System.currentTimeMillis() - (now - lastInteraction))
            .apply()
        lastCheckpoint = now
        dirty = false
    }

    private fun restoreInteractionTime(): Long {
        val now = SystemClock.elapsedRealtime()
        val recordedElapsed = preferences.getLong("elapsed_time", -1L)
        if (bootCount >= 0 && bootCount == preferences.getInt("boot_count", -1) && recordedElapsed in 0L..now) {
            return recordedElapsed
        }
        // After a reboot the elapsed clock has a new origin. Reconstruct the age from wall time.
        val wallNow = System.currentTimeMillis()
        val age = (wallNow - preferences.getLong("wall_time", wallNow)).coerceIn(0L, ChatIdleTimeoutMs)
        return now - age
    }

    companion object {
        private var instance: ChatIdleTracker? = null

        fun get(context: Context): ChatIdleTracker =
            instance ?: ChatIdleTracker(context.applicationContext).also { instance = it }
    }
}

internal fun Window.trackChatIdleInput(tracker: ChatIdleTracker) {
    val previous = callback ?: return
    callback = object : Window.Callback by previous {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_CANCEL) tracker.recordInteraction()
            return previous.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            tracker.recordInteraction()
            return previous.dispatchKeyEvent(event)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) tracker.recordInteraction()
            return previous.dispatchGenericMotionEvent(event)
        }
    }
}
