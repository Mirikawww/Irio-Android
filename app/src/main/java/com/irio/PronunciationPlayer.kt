package com.irio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal class PronunciationAudioCache(private val client: OkHttpClient = publicClient) {
    fun fetch(directory: File, recording: PronunciationRecording, cancellation: ChatRequestCancellation, userAgent: String): File {
        val url = pronunciationAudioUrl(recording.audioUrl) ?: error("Invalid pronunciation source")
        require(recording.id.matches(Regex("ll_[0-9a-f]{24}")))
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "${recording.id}.audio")
        if (target.isFile && target.length() in 1..MAX_BYTES) return target
        val temporary = File(directory, "${recording.id}-${UUID.randomUUID()}.part")
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
        cancellation.attach(call)
        try {
            check(!cancellation.isCancelled())
            call.execute().use { response ->
                check(response.isSuccessful)
                check(response.body.contentLength() <= MAX_BYTES)
                val mime = response.body.contentType()?.toString().orEmpty()
                check(mime.startsWith("audio/") || mime.startsWith("application/ogg") || mime.startsWith("application/octet-stream"))
                response.body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        check(!cancellation.isCancelled())
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    check(total > 0L)
                } }
            }
            check(!cancellation.isCancelled())
            check(temporary.renameTo(target))
            return target
        } finally {
            cancellation.detach(call)
            temporary.delete()
        }
    }

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        private val publicClient by lazy { IrioHttp.defaultClient().newBuilder().followRedirects(false)
            .followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build() }
    }
}

/** Short human recordings only. Never creates SpeechPlayback, a TTS session or a mini player. */
internal object PronunciationPlayer {
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var job: Job? = null
    private var cancellation: ChatRequestCancellation? = null
    private var player: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var generation = 0L
    var error by mutableStateOf<Pair<Long, String>?>(null)
        private set

    fun play(context: Context, recording: PronunciationRecording) {
        stop()
        SpeechPlayer.stop()
        val token = generation
        val app = context.applicationContext
        val cancel = ChatRequestCancellation()
        cancellation = cancel
        job = scope.launch {
            var file: File? = null
            try {
                val cached = withContext(Dispatchers.IO) {
                    PronunciationAudioCache().fetch(File(app.cacheDir, "pronunciations"), recording, cancel, CommonsImageTools.userAgent(app))
                }
                file = cached
                if (token != generation || cancel.isCancelled()) return@launch
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                val manager = app.getSystemService(AudioManager::class.java)
                val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                        if (change < 0 && token == generation) stop()
                    }.build()
                audioManager = manager
                focusRequest = focus
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                val media = MediaPlayer()
                player = media
                media.setAudioAttributes(attributes)
                media.setOnPreparedListener { if (token == generation && player === it) it.start() }
                media.setOnCompletionListener { if (token == generation) stop() }
                media.setOnErrorListener { _, _, _ ->
                    if (token == generation) failed(file)
                    true
                }
                media.setDataSource(cached.absolutePath)
                media.prepareAsync()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation && !cancel.isCancelled()) failed(file) }
        }
    }

    private fun failed(file: File?) {
        stop()
        error = generation to tr("发音播放失败，请重试")
        if (file != null) scope.launch(Dispatchers.IO) { file.delete() }
    }

    fun stop() {
        generation++
        cancellation?.cancel()
        cancellation = null
        job?.cancel()
        job = null
        player?.let { runCatching { it.release() } }
        player = null
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        focusRequest = null
        audioManager = null
    }

    fun consumeError(event: Pair<Long, String>) { if (error == event) error = null }
}

@Composable
internal fun ObservePronunciationPlayback(onError: (String) -> Unit) {
    val currentError = PronunciationPlayer.error
    val report by rememberUpdatedState(onError)
    val owner = LocalLifecycleOwner.current
    val lifecycleState by owner.lifecycle.currentStateAsState()
    LaunchedEffect(currentError, lifecycleState) {
        if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            currentError?.let { report(it.second); PronunciationPlayer.consumeError(it) }
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) PronunciationPlayer.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            PronunciationPlayer.stop()
        }
    }
}
