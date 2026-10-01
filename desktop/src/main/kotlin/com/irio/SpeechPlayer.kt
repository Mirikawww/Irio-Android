package com.irio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.swing.SwingUtilities

/**
 * 桌面端用系统朗读引擎把整段合成成一个音频文件，再用 Java Sound 播放。
 * Windows 走 System.Speech，macOS 走 say，Linux 走 espeak-ng / espeak。
 * 播放线程按采样位置跳转，所以快退 / 快进五秒是真实时间。
 */
internal object SpeechPlayer {
    var playback by mutableStateOf(SpeechPlayback())
        private set

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "irio-speech-synth").apply { isDaemon = true }
    }
    private val generation = AtomicInteger(0)
    private var player: PcmPlayer? = null
    private var synthProcess: Process? = null
    private var audioFile: File? = null
    private var retainedKey: String? = null
    private var retainedFile: File? = null
    private var clock: javax.swing.Timer? = null
    @Volatile private var paused = false
    @Volatile private var pendingSeekMs = 0

    suspend fun loadVoices(): List<SpeechVoice>? = withContext(Dispatchers.IO) {
        engine()?.voices()
    }

    fun speak(messageId: Long, text: String) {
        val spoken = text.trim()
        if (playback.active && playback.messageId == messageId) {
            stop()
            return
        }
        stop()
        if (spoken.isBlank()) return
        val key = "$messageId\u0000${SpeechStore.loadVoiceId()}\u0000$spoken"
        val cached = retainedFile?.takeIf { retainedKey == key && it.isFile && it.length() > 0L }
        val gen = generation.incrementAndGet()
        paused = false
        pendingSeekMs = 0
        publish(SpeechPlayback(messageId, active = true, playing = false, preparing = true))
        worker.execute {
            if (gen != generation.get()) return@execute
            val file = if (cached != null) {
                cached
            } else {
                val synth = engine()
                if (synth == null) {
                    if (gen == generation.get()) stop()
                    return@execute
                }
                val dir = workDir()
                val textFile = File(dir, "speech-$gen.txt")
                val audio = File(dir, "speech-$gen.${synth.extension}")
                writeText(textFile, spoken, bom = synth.bom)
                val ok = try {
                    synth.synthesize(spoken = audio, textFile = textFile, voiceId = SpeechStore.loadVoiceId()) { process ->
                        if (gen != generation.get()) {
                            process.destroyForcibly()
                            false
                        } else {
                            synthProcess = process
                            true
                        }
                    }
                } finally {
                    textFile.delete()
                    synthProcess = null
                }
                if (gen != generation.get()) {
                    audio.delete()
                    return@execute
                }
                if (!ok || !audio.isFile || audio.length() == 0L) {
                    audio.delete()
                    stop()
                    return@execute
                }
                val dest = File(dir, "last.${synth.extension}")
                if (audio.absolutePath != dest.absolutePath) {
                    if (retainedFile?.absolutePath != dest.absolutePath) retainedFile?.delete()
                    audio.copyTo(dest, overwrite = true)
                    audio.delete()
                }
                retainedKey = key
                retainedFile = dest
                dest
            }
            if (gen != generation.get()) return@execute
            beginPlayback(file, gen, messageId)
        }
    }

    private fun beginPlayback(file: File, gen: Int, messageId: Long) {
        val started = runCatching {
            PcmPlayer(file) {
                if (gen == generation.get()) stop()
            }
        }.getOrNull()
        if (started == null || gen != generation.get()) {
            started?.close()
            if (file.absolutePath != retainedFile?.absolutePath) file.delete()
            if (gen == generation.get()) stop()
            return
        }
        player = started
        audioFile = file
        val seek = pendingSeekMs
        if (seek > 0) started.seekToMs(seek)
        if (paused) started.pause() else started.start()
        publish(
            SpeechPlayback(
                messageId = messageId,
                active = true,
                playing = !paused,
                preparing = false,
                positionMs = started.positionMs,
                durationMs = started.durationMs,
            ),
        )
        startClock()
    }

    fun togglePause() {
        if (!playback.active) return
        paused = !paused
        if (paused) player?.pause() else player?.start()
        publish(
            playback.copy(
                playing = player != null && !paused,
                preparing = player == null,
                positionMs = player?.positionMs ?: playback.positionMs,
            ),
        )
        if (!paused && player != null) startClock()
    }

    fun seekBy(deltaMs: Int) {
        val current = player
        if (current == null) {
            pendingSeekMs = (pendingSeekMs + deltaMs).coerceAtLeast(0)
            return
        }
        val target = (current.positionMs + deltaMs).coerceAtLeast(0)
        if (current.durationMs > 0 && target >= current.durationMs) {
            stop()
        } else {
            current.seekToMs(target)
        }
    }

    fun stop() {
        generation.incrementAndGet()
        paused = false
        pendingSeekMs = 0
        val timer = clock
        if (SwingUtilities.isEventDispatchThread()) timer?.stop() else SwingUtilities.invokeLater { timer?.stop() }
        synthProcess?.destroyForcibly()
        synthProcess = null
        player?.close()
        player = null
        if (audioFile != null && audioFile?.absolutePath != retainedFile?.absolutePath) audioFile?.delete()
        audioFile = null
        publish(SpeechPlayback())
    }

    private fun startClock() {
        val start = {
            val timer = clock ?: javax.swing.Timer(200) {
                val current = player ?: return@Timer
                if (!playback.active || playback.preparing) return@Timer
                publish(
                    playback.copy(
                        playing = !paused,
                        positionMs = current.positionMs,
                        durationMs = current.durationMs.coerceAtLeast(playback.durationMs),
                    ),
                )
            }.also {
                it.isRepeats = true
                clock = it
            }
            if (!timer.isRunning) timer.start()
        }
        if (SwingUtilities.isEventDispatchThread()) start() else SwingUtilities.invokeLater(start)
    }

    private fun publish(next: SpeechPlayback) {
        val apply = { playback = next }
        if (SwingUtilities.isEventDispatchThread()) apply()
        else SwingUtilities.invokeLater(apply)
    }

    private fun workDir(): File =
        File(System.getProperty("java.io.tmpdir"), "irio-speech").apply { mkdirs() }

    private fun engine(): SpeechEngine? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> WindowsSpeech
            os.contains("mac") && commandExists("say") -> MacSpeech
            commandExists("espeak-ng") -> EspeakSpeech("espeak-ng")
            commandExists("espeak") -> EspeakSpeech("espeak")
            else -> null
        }
    }
}

private interface SpeechEngine {
    val extension: String
    val bom: Boolean
    fun voices(): List<SpeechVoice>?
    fun synthesize(spoken: File, textFile: File, voiceId: String, attach: (Process) -> Boolean): Boolean
}

private object WindowsSpeech : SpeechEngine {
    override val extension = "wav"
    override val bom = true

    override fun voices(): List<SpeechVoice>? {
        val result = runScript(LIST_SCRIPT, emptyList(), 30) ?: return null
        if (result.first != 0) return null
        val voices = result.second.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0].isBlank()) null
            else SpeechVoice(id = parts[0], label = parts[1].ifBlank { parts[0] })
        }.distinctBy { it.id }.toList()
        val counts = voices.groupingBy { it.label }.eachCount()
        return voices.map { voice ->
            if ((counts[voice.label] ?: 0) > 1) voice.copy(label = "${voice.label} · ${voice.id}") else voice
        }
    }

    override fun synthesize(spoken: File, textFile: File, voiceId: String, attach: (Process) -> Boolean): Boolean {
        val result = runScript(
            SYNTH_SCRIPT,
            listOf("-voice", voiceId, "-textPath", textFile.absolutePath, "-wavPath", spoken.absolutePath),
            180,
            attach,
        ) ?: return false
        return result.first == 0 && spoken.isFile && spoken.length() > 44
    }

    private fun runScript(
        script: String,
        args: List<String>,
        timeoutSec: Long,
        attach: ((Process) -> Boolean)? = null,
    ): Pair<Int, String>? {
        val powershell = powershellPath() ?: return null
        val file = File(File(System.getProperty("java.io.tmpdir"), "irio-speech").apply { mkdirs() }, "speech-${System.nanoTime()}.ps1")
        writeText(file, script, bom = true)
        return try {
            val process = ProcessBuilder(
                listOf(
                    powershell,
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    file.absolutePath,
                ) + args,
            ).redirectErrorStream(true).start()
            if (attach != null && !attach(process)) {
                process.destroyForcibly()
                return null
            }
            val output = StringBuilder()
            val pump = Thread {
                runCatching {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            output.append(line).append('\n')
                        }
                    }
                }
            }
            pump.isDaemon = true
            pump.start()
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            pump.join(2_000)
            process.exitValue() to output.toString()
        } finally {
            file.delete()
        }
    }

    private fun powershellPath(): String? {
        val root = System.getenv("SystemRoot") ?: return null
        val exe = File(root, "System32/WindowsPowerShell/v1.0/powershell.exe")
        return exe.takeIf { it.isFile }?.absolutePath
    }
}

private object MacSpeech : SpeechEngine {
    override val extension = "aiff"
    override val bom = false

    override fun voices(): List<SpeechVoice>? {
        val output = runCommand(listOf("say", "-v", "?"), 20) ?: return null
        return output.lineSequence().mapNotNull { line ->
            val match = SAY_VOICE.find(line.trim()) ?: return@mapNotNull null
            SpeechVoice(id = match.groupValues[1], label = "${match.groupValues[1]} · ${match.groupValues[2]}")
        }.distinctBy { it.id }.toList()
    }

    override fun synthesize(spoken: File, textFile: File, voiceId: String, attach: (Process) -> Boolean): Boolean {
        val command = buildList {
            add("say")
            add("-o")
            add(spoken.absolutePath)
            add("-f")
            add(textFile.absolutePath)
            if (voiceId.isNotBlank()) {
                add("-v")
                add(voiceId)
            }
        }
        return runCommand(command, 180, attach) != null && spoken.isFile && spoken.length() > 0
    }
}

private class EspeakSpeech(private val bin: String) : SpeechEngine {
    override val extension = "wav"
    override val bom = false

    override fun voices(): List<SpeechVoice>? {
        val output = runCommand(listOf(bin, "--voices"), 20) ?: return null
        return output.lineSequence().mapNotNull { line -> parseEspeakVoice(line) }.distinctBy { it.id }.toList()
    }

    override fun synthesize(spoken: File, textFile: File, voiceId: String, attach: (Process) -> Boolean): Boolean {
        val command = buildList {
            add(bin)
            add("-w")
            add(spoken.absolutePath)
            add("-f")
            add(textFile.absolutePath)
            if (voiceId.isNotBlank()) {
                add("-v")
                add(voiceId)
            }
        }
        return runCommand(command, 180, attach) != null && spoken.isFile && spoken.length() > 44
    }
}

private fun parseEspeakVoice(line: String): SpeechVoice? {
    val parts = line.trim().split(Regex("\\s+")).toMutableList()
    if (parts.size < 4 || parts[0] == "Pty" || parts[0].toIntOrNull() == null) return null
    while (parts.lastOrNull()?.startsWith("(") == true) parts.removeLast()
    if (parts.size < 4) return null
    val file = parts.last()
    val language = parts[1]
    val name = parts.subList(3, parts.size - 1).joinToString(" ").ifBlank { file }
    return SpeechVoice(id = file, label = "$name ($language)")
}

private val SAY_VOICE = Regex("""^(\S+)\s+(\S+)\s+#""")

private fun commandExists(name: String): Boolean {
    val path = System.getenv("PATH").orEmpty()
    if (path.isBlank()) return false
    return path.split(File.pathSeparator).any { dir -> File(dir, name).isFile }
}

private fun runCommand(
    command: List<String>,
    timeoutSec: Long,
    attach: ((Process) -> Boolean)? = null,
): String? {
    val process = runCatching {
        ProcessBuilder(command).redirectErrorStream(true).start()
    }.getOrNull() ?: return null
    if (attach != null && !attach(process)) {
        process.destroyForcibly()
        return null
    }
    val output = StringBuilder()
    val pump = Thread {
        runCatching {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                output.append(reader.readText())
            }
        }
    }
    pump.isDaemon = true
    pump.start()
    if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    pump.join(2_000)
    return if (process.exitValue() == 0) output.toString() else null
}

private fun writeText(file: File, text: String, bom: Boolean) {
    file.parentFile?.mkdirs()
    file.outputStream().use { out ->
        if (bom) out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        out.write(text.toByteArray(Charsets.UTF_8))
    }
}

private val PS_UTF8 = "${'$'}OutputEncoding = New-Object System.Text.UTF8Encoding ${'$'}false\n" +
    "[Console]::OutputEncoding = ${'$'}OutputEncoding\n"

private val LIST_SCRIPT = PS_UTF8 + """
Add-Type -AssemblyName System.Speech
${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
${'$'}synth.GetInstalledVoices() | ForEach-Object {
  if (${'$'}_.Enabled) {
    ${'$'}info = ${'$'}_.VoiceInfo
    Write-Output (${'$'}info.Name + "`t" + ${'$'}info.Name)
  }
}
${'$'}synth.Dispose()
""".trimIndent()

private val SYNTH_SCRIPT = PS_UTF8 + """
param(
  [string]${'$'}voice = "",
  [Parameter(Mandatory=${'$'}true)][string]${'$'}textPath,
  [Parameter(Mandatory=${'$'}true)][string]${'$'}wavPath
)
${'$'}ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech
${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
  if (${'$'}voice -ne "") { ${'$'}synth.SelectVoice(${'$'}voice) }
  ${'$'}synth.SetOutputToWaveFile(${'$'}wavPath)
  ${'$'}encoding = New-Object System.Text.UTF8Encoding ${'$'}true
  ${'$'}synth.Speak([System.IO.File]::ReadAllText(${'$'}textPath, ${'$'}encoding))
} finally {
  ${'$'}synth.Dispose()
}
""".trimIndent()

private class PcmPlayer(
    private val file: File,
    private val onEnded: () -> Unit,
) {
    private val format: AudioFormat
    private val totalFrames: Long
    @Volatile private var positionFrames = 0L
    @Volatile private var paused = true
    @Volatile private var closed = false
    @Volatile private var seekFrame = -1L
    @Volatile private var ended = false
    private var line: SourceDataLine? = null
    private var thread: Thread? = null

    val positionMs: Int
        get() = framesToMs(positionFrames)

    val durationMs: Int
        get() = if (totalFrames <= 0) 0 else framesToMs(totalFrames)

    init {
        val raw = AudioSystem.getAudioInputStream(file)
        val pcm = toPcm(raw)
        try {
            format = pcm.format
            totalFrames = pcm.frameLength.coerceAtLeast(0)
        } finally {
            // 转换流会关掉原始流；已经是 PCM 时两者是同一个，只能关一次。
            runCatching { pcm.close() }
        }
    }

    fun start() {
        if (closed) return
        paused = false
        val running = thread
        if (running?.isAlive == true) {
            runCatching { line?.start() }
            return
        }
        val created = Thread({ runLoop() }, "irio-speech-play")
        created.isDaemon = true
        thread = created
        created.start()
    }

    fun pause() {
        paused = true
        runCatching { line?.stop() }
    }

    fun seekToMs(ms: Int) {
        val frame = (ms / 1000.0 * format.sampleRate).toLong()
        val clamped = if (totalFrames > 0) frame.coerceIn(0, totalFrames) else frame.coerceAtLeast(0)
        if (totalFrames > 0 && clamped >= totalFrames) {
            finish()
            return
        }
        seekFrame = clamped
    }

    fun close() {
        closed = true
        ended = true
        runCatching { line?.stop() }
        runCatching { line?.close() }
        val running = thread
        if (running != null && Thread.currentThread() != running) running.join(400)
    }

    private fun runLoop() {
        val frameSize = format.frameSize.coerceAtLeast(1)
        val opened = runCatching {
            val info = javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format)
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(format)
                line = it
            }
        }.getOrNull() ?: run {
            finish()
            return
        }
        var input = openAt(0)
        val buffer = ByteArray(4096 - (4096 % frameSize))
        try {
            if (!paused) opened.start()
            while (!closed) {
                val seek = seekFrame
                if (seek >= 0) {
                    seekFrame = -1
                    runCatching { input.close() }
                    input = openAt(seek)
                    positionFrames = seek
                    runCatching { opened.flush() }
                    if (paused) runCatching { opened.stop() }
                }
                if (paused) {
                    Thread.sleep(40)
                    continue
                }
                if (!opened.isRunning) opened.start()
                val read = input.read(buffer)
                if (read < 0) {
                    runCatching { opened.drain() }
                    finish()
                    return
                }
                opened.write(buffer, 0, read)
                positionFrames += read / frameSize
            }
        } catch (_: InterruptedException) {
            return
        } catch (_: RuntimeException) {
            finish()
        } finally {
            runCatching { input.close() }
        }
    }

    private fun finish() {
        if (ended) return
        ended = true
        if (!closed) onEnded()
    }

    private fun openAt(frame: Long): AudioInputStream {
        val pcm = toPcm(AudioSystem.getAudioInputStream(file))
        var left = frame * pcm.format.frameSize.coerceAtLeast(1)
        val buffer = ByteArray(8192)
        while (left > 0) {
            val read = pcm.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) break
            left -= read
        }
        return pcm
    }

    private fun framesToMs(frames: Long): Int =
        ((frames * 1000.0) / format.sampleRate.coerceAtLeast(1f)).toInt()
}

private fun toPcm(input: AudioInputStream): AudioInputStream {
    val format = input.format
    if (format.encoding == AudioFormat.Encoding.PCM_SIGNED && format.sampleSizeInBits == 16) return input
    val target = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        format.sampleRate,
        16,
        format.channels,
        format.channels * 2,
        format.sampleRate,
        false,
    )
    return AudioSystem.getAudioInputStream(target, input)
}

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
            .background(Theme[colors][secondaryColor], RoundedCornerShape(20.dp))
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
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(track, cornerRadius = radius)
        val width = size.width * fraction
        if (width > 0f) {
            drawRoundRect(
                color = fill,
                size = Size(width.coerceAtLeast(size.height), size.height),
                cornerRadius = radius,
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
        shape = CircleShape,
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
