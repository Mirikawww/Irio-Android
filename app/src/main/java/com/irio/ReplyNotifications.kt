package com.irio

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal const val EXTRA_OPEN_CONVERSATION = "com.irio.OPEN_CONVERSATION"
internal const val ACTION_DISMISS_REPLY = "com.irio.reply.DISMISS"
private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
internal const val REPLY_NOTIFICATION_PREVIEW_LENGTH = 25
internal const val REPLY_DONE_VISIBLE_MS = 10_000L

private const val REPLY_CHANNEL_ID = "irio-reply"

/** 通知正文：压成一行后只留前 [limit] 个字符。空白回复不发通知。 */
internal fun replyNotificationPreview(text: String, limit: Int = REPLY_NOTIFICATION_PREVIEW_LENGTH): String {
    val compact = text.replace(Regex("\\s+"), " ").trim()
    return compact.take(limit)
}

/** 状态栏芯片最多完整显示 6 个字符。步骤名更长时只留开头。 */
internal fun replyStatusChip(step: String): String {
    val english = ThemeStore.language == AppLanguage.EN
    val short = when (step) {
        tr("请求中") -> if (english) "Wait" else "请求"
        tr("响应中") -> if (english) "Live" else "响应"
        tr("思考中") -> if (english) "Think" else "思考"
        tr("输出中") -> if (english) "Write" else "输出"
        tr("完成") -> if (english) "Done" else "完成"
        else -> step
    }
    return short.take(6)
}

/** 只在模型正常输出结束、应用已退到后台、并且正文非空时通知。 */
internal fun shouldNotifyReplyCompletion(
    completed: Boolean,
    inForeground: Boolean,
    preview: String,
): Boolean = completed && !inForeground && preview.isNotEmpty()

internal fun appInForeground(): Boolean = ReplyLiveNotice.foreground()

/**
 * 后台通知的下一步文案。界面里不显示调用提示的工具，这里仍用它们自己的状态句。
 * 同一次更新里后面的信号覆盖前面的，结束时改成最终输出的前 25 个字符。
 */
internal fun advanceReplyNotice(current: String, update: ChatStreamUpdate, finished: Boolean = false, preview: String = ""): String {
    var step = if (update.newRound || update.resetAttempt) tr("请求中") else current
    update.retry?.let { step = chatRetryLine(it) }
    val silentPronunciation = isSilentPronunciationLookup(update.toolName, update.toolOutcome)
    if (update.thinkingDelta.isNotBlank()) step = tr("思考中")
    if (update.searching == true) {
        step = update.displayText.ifEmpty { searchStatusLabel(update.searchQuery) }
    }
    if (update.searching == false) step = ProcessEvent(0, ProcessEventKind.SEARCH,
        pages = update.searchPagesDelta, searchQuery = update.searchQuery,
        displayText = update.displayText, toolOutcome = update.toolOutcome).completedLabel()
    if (update.toolActive == true && !silentPronunciation) {
        step = update.displayText.ifEmpty { MemoryTool.liveLabel(update.toolName, update.webDomain) }
    }
    if (update.toolActive == false && !silentPronunciation) {
        step = if (update.toolName == ImageGenTool.NAME && update.toolContent == ImageGenTool.STATUS_OK) {
            tr("已生成图片")
        } else {
            ProcessEvent(0, ProcessEventKind.TOOL, update.toolContent, toolName = update.toolName,
                webDomain = update.webDomain, displayText = update.displayText, toolOutcome = update.toolOutcome).completedLabel()
        }
    }
    if (update.subagentJson.isNotEmpty()) step = tr("创建子智能体")
    if (update.coloredBubbles.isNotEmpty()) step = tr("发送彩色气泡")
    if (update.displayedImages.isNotEmpty()) step = tr("展示图片")
    if (update.displayedMermaid.isNotEmpty()) step = tr("渲染 Mermaid 图表")
    if (update.notifyTool.isNotEmpty() && !isSilentPronunciationLookup(update.notifyTool, update.toolOutcome)) step = MemoryTool.liveLabel(update.notifyTool)
    if (update.delta.isNotBlank()) step = tr("输出中")
    if (finished) step = preview.ifEmpty { tr("完成") }
    return step
}

internal object ReplyLiveNotice {
    private data class State(
        val conversationId: String,
        val title: String,
        val text: String,
        val finished: Boolean,
    )

    private val states = ConcurrentHashMap<String, State>()
    private val shown = ConcurrentHashMap.newKeySet<String>()
    private val dismissed = ConcurrentHashMap.newKeySet<String>()
    private val hideFinished = ConcurrentHashMap<String, Runnable>()
    private val observing = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var startedActivities = 0

    fun foreground(): Boolean = startedActivities > 0

    fun install(context: Context) {
        appContext = context.applicationContext
        if (!observing.compareAndSet(false, true)) return
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) onBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        val app = context.applicationContext as Application
        if (Looper.myLooper() == Looper.getMainLooper()) app.registerActivityLifecycleCallbacks(callbacks)
        else main.post { app.registerActivityLifecycleCallbacks(callbacks) }
    }

    fun publish(context: Context, conversationId: String, title: String, text: String, finished: Boolean, requestStarted: Boolean = false) {
        if (text.isEmpty() || conversationId.isBlank()) return
        val app = context.applicationContext
        install(app)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            publishOnMain(app, conversationId, title, text, finished, requestStarted)
        } else {
            main.post { publishOnMain(app, conversationId, title, text, finished, requestStarted) }
        }
    }

    fun dismiss(context: Context, conversationId: String) {
        states.remove(conversationId)
        shown.remove(conversationId)
        hideFinished.remove(conversationId)?.let { main.removeCallbacks(it) }
        val app = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) ReplyNotifications.cancel(app, conversationId)
        else main.post { ReplyNotifications.cancel(app, conversationId) }
    }

    private fun publishOnMain(context: Context, conversationId: String, title: String, text: String, finished: Boolean, requestStarted: Boolean) {
        hideFinished.remove(conversationId)?.let { main.removeCallbacks(it) }
        if (finished) {
            shown.remove(conversationId)
            dismissed.remove(conversationId)
            if (!shouldNotifyReplyCompletion(completed = true, inForeground = foreground(), preview = text)) {
                states.remove(conversationId)
                ReplyNotifications.cancel(context, conversationId)
                return
            }
            // 结束后仍保持 Live Update，否则系统会把提升通知直接收掉。
            states[conversationId] = State(conversationId, title, text, finished = true)
            ReplyNotifications.show(
                context,
                conversationId,
                title,
                text,
                ongoing = true,
                alert = true,
                chip = tr("完成"),
                timeoutMs = REPLY_DONE_VISIBLE_MS,
            )
            val hide = Runnable {
                hideFinished.remove(conversationId)
                if (states[conversationId]?.finished != true) return@Runnable
                states.remove(conversationId)
                shown.remove(conversationId)
                ReplyNotifications.cancel(context, conversationId)
            }
            hideFinished[conversationId] = hide
            main.postDelayed(hide, REPLY_DONE_VISIBLE_MS)
            return
        }
        if (requestStarted) dismissed.remove(conversationId)
        states[conversationId] = State(conversationId, title, text, finished = false)
        if (conversationId in dismissed) return
        if (foreground()) {
            shown.remove(conversationId)
            ReplyNotifications.cancel(context, conversationId)
        } else {
            val first = shown.add(conversationId)
            ReplyNotifications.show(context, conversationId, title, text, ongoing = true, alert = first)
        }
    }

    fun onUserDismissed(conversationId: String) {
        dismissed.add(conversationId)
        shown.remove(conversationId)
    }

    private fun onBackground() {
        val context = appContext ?: return
        states.values.forEach { state ->
            if (!state.finished && state.conversationId !in dismissed) {
                val first = shown.add(state.conversationId)
                ReplyNotifications.show(context, state.conversationId, state.title, state.text, ongoing = true, alert = first)
            }
        }
    }

    private fun onForeground() {
        val context = appContext ?: return
        states.values.toList().forEach { state ->
            hideFinished.remove(state.conversationId)?.let { main.removeCallbacks(it) }
            shown.remove(state.conversationId)
            // Foreground hides the notification, not the in-flight request state.
            // Waiting for a token is not a state change: background must restore it as-is.
            if (state.finished) states.remove(state.conversationId)
            ReplyNotifications.cancel(context, state.conversationId)
        }
    }
}

internal object ReplyOpenRequest {
    var conversationId by mutableStateOf<String?>(null)
        private set

    fun publish(intent: Intent?) {
        conversationId = intent?.getStringExtra(EXTRA_OPEN_CONVERSATION)?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        conversationId = null
    }
}

internal object ReplyNotifications {
    private val permissionRequested = AtomicBoolean(false)

    fun requestPermissionIfNeeded(granted: Boolean, launch: () -> Unit) {
        if (granted || !permissionRequested.compareAndSet(false, true)) return
        launch()
    }

    fun show(
        context: Context,
        conversationId: String,
        title: String,
        preview: String,
        ongoing: Boolean = false,
        alert: Boolean = true,
        chip: String = preview,
        timeoutMs: Long = 0,
    ) {
        if (preview.isEmpty()) return
        val app = context.applicationContext
        if (
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(app)
        val open = Intent(app, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_CONVERSATION, conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            app,
            conversationId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            app,
            conversationId.hashCode(),
            Intent(app, ReplyNotificationDismissReceiver::class.java)
                .setAction(ACTION_DISMISS_REPLY)
                .putExtra(EXTRA_OPEN_CONVERSATION, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(app, REPLY_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(preview)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(!ongoing)
            .setShowWhen(false)
            .setCategory(if (ongoing) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_MESSAGE)
            .setContentIntent(pending)
        if (timeoutMs > 0) builder.setTimeoutAfter(timeoutMs)
        if (ongoing) {
            builder.setDeleteIntent(dismiss)
            applyLiveUpdate(builder, chip)
        }
        val notification = builder.build()
        app.getSystemService(NotificationManager::class.java)
            .notify(notificationId(conversationId), notification)
    }

    fun cancel(context: Context, conversationId: String) {
        context.applicationContext.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(conversationId))
    }

    private fun notificationId(conversationId: String): Int {
        val hash = conversationId.hashCode()
        return if (hash == SpeechNotifications.NOTIFICATION_ID) hash xor 0x5200 else hash
    }

    private fun applyLiveUpdate(builder: Notification.Builder, step: String) {
        if (Build.VERSION.SDK_INT < 36) return
        builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        builder.setShortCriticalText(replyStatusChip(step))
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(REPLY_CHANNEL_ID) != null) return
        val channel = NotificationChannel(REPLY_CHANNEL_ID, tr("回复"), NotificationManager.IMPORTANCE_HIGH)
        channel.setShowBadge(true)
        manager.createNotificationChannel(channel)
    }
}

internal class ReplyNotificationDismissReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action != ACTION_DISMISS_REPLY) return
        val id = intent.getStringExtra(EXTRA_OPEN_CONVERSATION) ?: return
        ReplyLiveNotice.onUserDismissed(id)
    }
}
