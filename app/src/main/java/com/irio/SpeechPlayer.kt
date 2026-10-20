package com.irio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.animation.core.LinearEasing

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.kyant.shapes.RoundedRectangle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

internal fun speechCacheKey(messageId: Long, voiceId: String, speechRate: Float, text: String): String =
    "$messageId\u0000$voiceId\u0000$speechRate\u0000$text"

/**
 * 系统默认 TTS 先把正文合成成音频，再用 [MediaPlayer] 播放。
 * 引擎本身不能按秒跳转，播放器可以，所以快退 / 快进五秒是真实的时间位置。
 */
internal object SpeechPlayer {
    var playback by mutableStateOf(SpeechPlayback())
        private set

    private var handler: Handler? = null
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initFailed = false
    private val initWaiters = mutableListOf<(Boolean) -> Unit>()
    private val synthCallbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private var session: Session? = null
    private var retained: RetainedSpeech? = null
    private var lastNotified = SpeechPlayback()
    private val ticker: Runnable = Runnable {
        val current = session ?: return@Runnable
        publishSession(current)
        if (session === current && current.ready && !current.paused) {
            handler?.postDelayed(ticker, 200)
        }
    }

    suspend fun loadVoices(context: Context): List<SpeechVoice>? = suspendCancellableCoroutine { cont ->
        listVoices(context) { voices ->
            if (cont.isActive) cont.resume(voices)
        }
    }

    fun speak(context: Context, messageId: Long, text: String) {
        handler().post {
            if (session?.id == messageId) {
                stopOnThread()
                return@post
            }
            stopOnThread()
            val app = context.applicationContext
            appContext = app
            val voiceId = SpeechStore.loadVoiceId(app)
            val speechRate = SpeechStore.loadSpeechRate(app)
            val key = speechCacheKey(messageId, voiceId, speechRate, text)
            val hit = retained?.takeIf { cached ->
                cached.key == key && cached.files.all { it.isFile && it.length() > 0L }
            }
            if (hit != null) {
                playRetained(messageId, hit)
                return@post
            }
            val chunks = speechChunks(text)
            if (chunks.isEmpty()) return@post
            val created = Session(messageId, chunks)
            created.retainKey = key
            session = created
            publishSession(created)
            ensureEngine(app) { ok ->
                if (session !== created) return@ensureEngine
                if (!ok) {
                    stopOnThread()
                    return@ensureEngine
                }
                applyVoice(voiceId, speechRate)
                pump(created)
            }
        }
    }

    fun togglePause() {
        handler?.post {
            val current = session ?: return@post
            current.paused = !current.paused
            val player = current.player
            if (current.paused) {
                runCatching { if (player?.isPlaying == true) player.pause() }
            } else if (player != null && current.ready) {
                runCatching { player.start() }
            } else {
                maybeStart(current)
            }
            publishSession(current)
            if (current.player?.isPlaying == true) armTicker()
        }
    }

    fun seekBy(deltaMs: Int) {
        handler?.post {
            val current = session ?: return@post
            seekTo(current, (positionMs(current) + deltaMs).coerceAtLeast(0))
        }
    }

    fun seekTo(positionMs: Int) {
        handler?.post {
            val current = session ?: return@post
            seekTo(current, positionMs.coerceAtLeast(0))
        }
    }

    fun stop() {
        handler?.post { stopOnThread() }
    }

    private fun listVoices(context: Context, done: (List<SpeechVoice>?) -> Unit) {
        handler().post {
            ensureEngine(context.applicationContext) { ok ->
                if (!ok) {
                    publishVoices(done, null)
                    return@ensureEngine
                }
                publishVoices(done, voicesOf(tts?.voices.orEmpty()))
            }
        }
    }

    private fun publishVoices(done: (List<SpeechVoice>?) -> Unit, voices: List<SpeechVoice>?) {
        val deliver = { done(voices) }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver()
        else Handler(Looper.getMainLooper()).post(deliver)
    }

    private fun voicesOf(voices: Collection<Voice>): List<SpeechVoice> {
        val preferred = Locale.getDefault().language
        val usable = voices.filter { voice ->
            voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }.sortedWith(
            compareByDescending<Voice> { it.locale.language == preferred }
                .thenBy { it.locale.displayName }
                .thenBy { it.name },
        )
        val bases = usable.map { it.locale.displayName }
        val counts = bases.groupingBy { it }.eachCount()
        return usable.map { voice ->
            val base = voice.locale.displayName
            val label = if ((counts[base] ?: 0) > 1) "$base · ${voice.name}" else base
            SpeechVoice(id = voice.name, label = label, online = voice.isNetworkConnectionRequired)
        }
    }

    private fun handler(): Handler {
        handler?.let { return it }
        val created = HandlerThread("irio-speech").apply { start() }
        val next = Handler(created.looper)
        handler = next
        return next
    }

    private fun ensureEngine(context: Context, done: (Boolean) -> Unit) {
        appContext = context.applicationContext
        if (ready) {
            done(true)
            return
        }
        if (initFailed) {
            done(false)
            return
        }
        initWaiters += done
        if (tts != null) return
        try {
            // 初始化回调可能在构造函数里同步回来。一律再丢回这条线程，等 tts 赋值完成。
            tts = TextToSpeech(appContext!!) { status ->
                handler?.post {
                    val success = status == TextToSpeech.SUCCESS
                    ready = success
                    initFailed = !success
                    if (success) installListener()
                    val pending = initWaiters.toList()
                    initWaiters.clear()
                    pending.forEach { it(success) }
                }
            }
        } catch (_: RuntimeException) {
            initFailed = true
            val pending = initWaiters.toList()
            initWaiters.clear()
            pending.forEach { it(false) }
        }
    }

    private fun installListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                finishUtterance(utteranceId, true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishUtterance(utteranceId, false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishUtterance(utteranceId, false)
            }
        })
    }

    private fun finishUtterance(utteranceId: String?, ok: Boolean) {
        val callback = utteranceId?.let { synthCallbacks.remove(it) } ?: return
        handler?.post { callback(ok) }
    }

    private fun applyVoice(voiceId: String, speechRate: Float) {
        val engine = tts ?: return
        val match = voiceId.takeIf { it.isNotBlank() }?.let { saved ->
            engine.voices?.firstOrNull { it.name == saved }
        }
        engine.voice = match ?: engine.defaultVoice
        // Bake speed into synthesized audio so seeking, duration and progress stay accurate.
        engine.setSpeechRate(speechRate)
    }

    private fun pump(target: Session) {
        if (session !== target || target.synthesizing) return
        val index = target.files.indexOfFirst { it == null }
        if (index < 0) return
        val context = appContext ?: return
        val file = File(cacheDir(context), "speech-${target.generation}-$index.wav")
        val utterance = "irio-${target.generation}-$index-${System.nanoTime()}"
        target.synthesizing = true
        synthCallbacks[utterance] = finish@{ ok ->
            if (session !== target) {
                file.delete()
                return@finish
            }
            target.synthesizing = false
            if (!ok || !file.isFile || file.length() == 0L) {
                file.delete()
                stopOnThread()
                return@finish
            }
            target.files[index] = file
            maybeRetain(target)
            maybeStart(target)
            applyPendingSeek(target)
            pump(target)
        }
        val queued = tts?.synthesizeToFile(target.chunks[index], null, file, utterance)
        if (queued != TextToSpeech.SUCCESS) {
            synthCallbacks.remove(utterance)
            target.synthesizing = false
            stopOnThread()
        }
    }

    private fun maybeStart(target: Session) {
        if (session !== target || target.paused || target.player != null) return
        if (target.files.getOrNull(target.index) == null) return
        playFile(target, target.index, 0)
    }

    private fun playFile(target: Session, index: Int, offsetMs: Int) {
        val file = target.files.getOrNull(index) ?: return
        releasePlayer(target)
        target.index = index
        val player = MediaPlayer()
        target.player = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        player.setOnPreparedListener {
            handler?.post {
                if (session !== target || target.player !== player) return@post
                target.durations[index] = player.duration.coerceAtLeast(0)
                target.ready = true
                maybeRetain(target)
                val pending = target.pendingSeekMs
                if (pending != null && canResolve(target, pending)) {
                    target.pendingSeekMs = null
                    seekTo(target, pending)
                    return@post
                }
                val offset = offsetMs.coerceIn(0, (player.duration - 1).coerceAtLeast(0))
                if (offset > 0) runCatching { player.seekTo(offset) }
                if (!target.paused) runCatching { player.start() }
                publishSession(target)
                if (!target.paused) armTicker()
            }
        }
        player.setOnCompletionListener {
            handler?.post {
                if (session !== target || target.player !== player) return@post
                target.player = null
                runCatching { player.release() }
                if (target.paused) return@post
                val next = index + 1
                if (next >= target.chunks.size) {
                    stopOnThread()
                    return@post
                }
                target.index = next
                if (target.files[next] != null) playFile(target, next, 0)
            }
        }
        player.setOnErrorListener { _, _, _ ->
            handler?.post { if (session === target) stopOnThread() }
            true
        }
        try {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (_: RuntimeException) {
            target.player = null
            runCatching { player.release() }
            stopOnThread()
        }
    }

    private fun seekTo(target: Session, targetMs: Int) {
        if (session !== target) return
        if (target.player != null && target.durations.getOrElse(target.index) { -1 } < 0) {
            target.pendingSeekMs = targetMs
            return
        }
        var remain = targetMs
        for (index in target.chunks.indices) {
            val duration = target.durations[index]
            val file = target.files[index]
            if (duration < 0) {
                target.pendingSeekMs = targetMs
                if (file != null) playFile(target, index, remain)
                return
            }
            if (duration == 0) continue
            if (remain < duration) {
                target.pendingSeekMs = null
                val sameClip = target.index == index && target.player != null
                if (sameClip) {
                    runCatching { target.player?.seekTo(remain) }
                    if (!target.paused) runCatching { target.player?.start() }
                    publishSession(target)
                    if (!target.paused) armTicker()
                } else {
                    playFile(target, index, remain)
                }
                return
            }
            remain -= duration
        }
        if (target.files.any { it == null }) {
            target.pendingSeekMs = targetMs
            return
        }
        stopOnThread()
    }

    private fun applyPendingSeek(target: Session) {
        val pending = target.pendingSeekMs ?: return
        if (canResolve(target, pending) || target.files.all { it != null }) {
            seekTo(target, pending)
        }
    }

    private fun canResolve(target: Session, targetMs: Int): Boolean {
        var sum = 0
        for (index in target.chunks.indices) {
            val duration = target.durations[index]
            if (duration < 0) return false
            sum += duration
            if (targetMs < sum) return true
        }
        return true
    }

    private fun positionMs(target: Session): Int {
        var sum = 0
        for (index in 0 until target.index) {
            val duration = target.durations[index]
            if (duration < 0) return sum
            sum += duration
        }
        sum += target.player?.currentPosition ?: 0
        return sum
    }

    private fun stopOnThread() {
        val current = session
        session = null
        handler?.removeCallbacks(ticker)
        tts?.stop()
        synthCallbacks.clear()
        if (current != null) {
            releasePlayer(current)
            if (!current.fromCache) {
                val kept = retained?.files?.map { it.absolutePath }?.toSet().orEmpty()
                current.files.forEach { file ->
                    if (file != null && file.absolutePath !in kept) file.delete()
                }
            }
        }
        publish(SpeechPlayback())
    }

    private fun playRetained(messageId: Long, hit: RetainedSpeech) {
        val created = Session(messageId, List(hit.files.size) { "" })
        created.fromCache = true
        created.retainKey = hit.key
        hit.files.forEachIndexed { index, file -> created.files[index] = file }
        hit.durations.copyInto(created.durations)
        session = created
        publishSession(created)
        playFile(created, 0, 0)
    }

    private fun maybeRetain(target: Session) {
        val key = target.retainKey ?: return
        if (target.fromCache || target.files.any { it == null }) return
        val context = appContext ?: return
        val dir = File(cacheDir(context), "last").apply { mkdirs() }
        val copies = target.files.mapIndexed { index, source ->
            val dest = File(dir, "$index.wav")
            if (source!!.absolutePath != dest.absolutePath) source.copyTo(dest, overwrite = true)
            dest
        }
        dir.listFiles()?.forEach { file ->
            if (copies.none { it.absolutePath == file.absolutePath }) file.delete()
        }
        retained = RetainedSpeech(
            key = key,
            files = copies,
            durations = IntArray(copies.size) { index ->
                val known = target.durations[index]
                if (known >= 0) known else wavDurationMs(copies[index])
            },
        )
    }

    private fun wavDurationMs(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        } catch (_: RuntimeException) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun publishSession(target: Session) {
        var duration = 0
        for (value in target.durations) if (value > 0) duration += value
        publish(
            SpeechPlayback(
                messageId = target.id,
                active = true,
                playing = target.ready && !target.paused,
                preparing = !target.ready,
                positionMs = positionMs(target),
                durationMs = duration,
            ),
        )
    }

    private fun armTicker() {
        val loop = handler ?: return
        loop.removeCallbacks(ticker)
        loop.postDelayed(ticker, 200)
    }

    private fun releasePlayer(target: Session) {
        val player = target.player ?: return
        target.player = null
        runCatching {
            player.setOnCompletionListener(null)
            player.setOnPreparedListener(null)
            player.setOnErrorListener(null)
            player.release()
        }
    }

    private fun publish(next: SpeechPlayback) {
        val apply = {
            val previous = lastNotified
            playback = next
            val changed = previous.active != next.active ||
                previous.playing != next.playing ||
                previous.preparing != next.preparing ||
                previous.durationMs != next.durationMs ||
                kotlin.math.abs(previous.positionMs - next.positionMs) >= 1_000
            if (changed) {
                lastNotified = next
                appContext?.let { context -> SpeechNotifications.sync(context, next) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply()
        else Handler(Looper.getMainLooper()).post(apply)
    }

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "speech").apply { mkdirs() }

    private class Session(
        val id: Long,
        val chunks: List<String>,
    ) {
        val generation = System.nanoTime()
        val files = arrayOfNulls<File>(chunks.size)
        val durations = IntArray(chunks.size) { -1 }
        var index = 0
        var paused = false
        var synthesizing = false
        var pendingSeekMs: Int? = null
        var player: MediaPlayer? = null
        var retainKey: String? = null
        var fromCache = false
        var ready = false
    }
}

private class RetainedSpeech(
    val key: String,
    val files: List<File>,
    val durations: IntArray,
)

@Composable
internal fun SpeechTransportBar() {
    val state = SpeechPlayer.playback
    if (!state.active) return
    val tint = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val position = rememberSpeechPosition(state)
    val fraction = if (state.durationMs <= 0) 0f else (position.toFloat() / state.durationMs).coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .padding(start = 16.dp, bottom = 4.dp)
            .horizontalScroll(rememberScrollState())
            .background(Theme[colors][secondaryColor], RoundedRectangle(20.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeechTransportButton(Icons.Rounded.Replay5, tr("快退五秒"), tint) { SpeechPlayer.seekBy(-5_000) }
        if (state.preparing) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                SpeechLoadingIndicator(tint)
            }
        } else {
            SpeechTransportButton(
                if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.playing) tr("暂停") else tr("播放"),
                tint,
            ) { SpeechPlayer.togglePause() }
        }
        SpeechTransportButton(Icons.Rounded.Forward5, tr("快进五秒"), tint) { SpeechPlayer.seekBy(5_000) }
        Text(
            formatSpeechClock(position),
            modifier = Modifier.padding(start = 6.dp),
            color = muted,
            fontSize = 12.sp,
        )
        SpeechProgress(fraction, tint, muted.copy(alpha = 0.28f))
        Text(
            formatSpeechClock(state.durationMs),
            modifier = Modifier.padding(end = 6.dp),
            color = muted,
            fontSize = 12.sp,
        )
        SpeechTransportButton(Icons.Rounded.Close, tr("停止朗读"), tint) { SpeechPlayer.stop() }
    }
}

@Composable
private fun rememberSpeechPosition(state: SpeechPlayback): Int {
    val clock = remember { SpeechClock() }
    var frame by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(state.playing) {
        if (!state.playing) return@LaunchedEffect
        while (true) withFrameNanos { frame = it }
    }
    val now = if (state.playing) frame else System.nanoTime()
    if (!state.playing || !clock.playing) {
        clock.anchorMs = state.positionMs
        clock.anchorNanos = System.nanoTime()
        clock.playing = state.playing
    } else if (kotlin.math.abs(clock.anchorMs + elapsedMs(now, clock.anchorNanos) - state.positionMs) > 400) {
        clock.anchorMs = state.positionMs
        clock.anchorNanos = System.nanoTime()
    }
    val shown = if (state.playing) clock.anchorMs + elapsedMs(now, clock.anchorNanos) else state.positionMs
    return if (state.durationMs > 0) shown.coerceIn(0, state.durationMs) else shown.coerceAtLeast(0)
}

private fun elapsedMs(now: Long, anchor: Long): Int =
    ((now - anchor) / 1_000_000L).toInt().coerceAtLeast(0)

private class SpeechClock {
    var anchorMs: Int = 0
    var anchorNanos: Long = 0L
    var playing: Boolean = false
}

@Composable
private fun SpeechProgress(
    fraction: Float,
    fill: androidx.compose.ui.graphics.Color,
    track: androidx.compose.ui.graphics.Color,
) {
    Canvas(
        Modifier
            .padding(horizontal = 6.dp)
            .width(96.dp)
            .height(4.dp),
    ) {
        drawContinuous(track, ContinuousCapsule)
        val width = size.width * fraction
        if (width > 0f) {
            drawContinuous(
                fill,
                ContinuousCapsule,
                size = Size(width.coerceAtLeast(size.height), size.height),
            )
        }
    }
}

@Composable
private fun SpeechLoadingIndicator(tint: androidx.compose.ui.graphics.Color) {
    val rotation by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
    )
    Canvas(Modifier.size(18.dp)) {
        drawArc(
            color = tint,
            startAngle = rotation,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun SpeechTransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        style = ButtonStyle.Ghost,
        buttonSize = ButtonSize.Small,
        shape = ContinuousCapsule,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}
