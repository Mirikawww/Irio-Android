package com.irio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder

internal object SpeechNotifications {
    const val ACTION_TOGGLE = "com.irio.speech.TOGGLE"
    const val ACTION_REWIND = "com.irio.speech.REWIND"
    const val ACTION_FORWARD = "com.irio.speech.FORWARD"
    const val ACTION_STOP = "com.irio.speech.STOP"

    private const val CHANNEL_ID = "irio-speech"
    internal const val NOTIFICATION_ID = 42

    @Volatile private var started = false
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null

    fun sync(context: Context, state: SpeechPlayback) {
        val app = context.applicationContext
        try {
            if (!state.active) {
                abandonFocus(app)
                session?.isActive = false
                if (started) {
                    started = false
                    app.stopService(Intent(app, SpeechPlaybackService::class.java))
                }
                return
            }
            ensureChannel(app)
            val media = session(app)
            media.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, tr("正在朗读"))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs.toLong())
                    .build(),
            )
            media.setPlaybackState(playbackState(state))
            media.isActive = true
            val notification = build(app, state, media)
            if (!started) {
                started = true
                androidx.core.content.ContextCompat.startForegroundService(
                    app,
                    Intent(app, SpeechPlaybackService::class.java),
                )
            } else {
                app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
            gainFocus(app)
        } catch (_: RuntimeException) {
            started = false
        }
    }

    internal fun build(context: Context, state: SpeechPlayback): Notification {
        ensureChannel(context)
        return build(context, state, session(context))
    }

    private fun build(context: Context, state: SpeechPlayback, media: MediaSession): Notification {
        val toggleIcon = if (state.playing) R.drawable.ic_speech_pause else R.drawable.ic_speech_play
        val toggleLabel = tr(if (state.playing) "暂停" else "播放")
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_speech)
            .setContentTitle("Irio")
            .setContentText(tr(if (state.preparing) "正在朗读" else if (state.playing) "正在朗读" else "朗读已暂停"))
            .setOngoing(state.playing || state.preparing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(context, R.drawable.ic_speech_rewind, tr("快退五秒"), ACTION_REWIND, 1))
            .addAction(action(context, toggleIcon, toggleLabel, ACTION_TOGGLE, 2))
            .addAction(action(context, R.drawable.ic_speech_fast_forward, tr("快进五秒"), ACTION_FORWARD, 3))
            .addAction(action(context, R.drawable.ic_speech_stop, tr("停止朗读"), ACTION_STOP, 4))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(media.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun session(context: Context): MediaSession {
        session?.let { return it }
        val created = MediaSession(context.applicationContext, "irio-speech")
        created.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (!SpeechPlayer.playback.playing) SpeechPlayer.togglePause()
            }

            override fun onPause() {
                if (SpeechPlayer.playback.playing) SpeechPlayer.togglePause()
            }

            override fun onStop() {
                SpeechPlayer.stop()
            }

            override fun onSeekTo(pos: Long) {
                SpeechPlayer.seekTo(pos.toInt())
            }

            override fun onRewind() {
                SpeechPlayer.seekBy(-5_000)
            }

            override fun onFastForward() {
                SpeechPlayer.seekBy(5_000)
            }
        })
        session = created
        return created
    }

    private fun playbackState(state: SpeechPlayback): PlaybackState {
        val playback = when {
            state.preparing -> PlaybackState.STATE_BUFFERING
            state.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        return PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_REWIND or
                    PlaybackState.ACTION_FAST_FORWARD,
            )
            .setState(playback, state.positionMs.toLong(), if (state.playing) 1f else 0f)
            .build()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, tr("朗读"), NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun action(context: Context, icon: Int, label: String, action: String, request: Int): Notification.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, SpeechNotificationReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, icon), label, pending).build()
    }

    private fun gainFocus(context: Context) {
        if (focus != null) return
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (
                    change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    if (SpeechPlayer.playback.playing) SpeechPlayer.togglePause()
                }
            }
            .build()
        focus = request
        audio.requestAudioFocus(request)
    }

    private fun abandonFocus(context: Context) {
        val request = focus ?: return
        focus = null
        context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }
}

class SpeechPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = SpeechPlayer.playback
        if (!state.active) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            SpeechNotifications.NOTIFICATION_ID,
            SpeechNotifications.build(this, state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

class SpeechNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SpeechNotifications.ACTION_TOGGLE -> SpeechPlayer.togglePause()
            SpeechNotifications.ACTION_REWIND -> SpeechPlayer.seekBy(-5_000)
            SpeechNotifications.ACTION_FORWARD -> SpeechPlayer.seekBy(5_000)
            SpeechNotifications.ACTION_STOP -> SpeechPlayer.stop()
        }
    }
}
