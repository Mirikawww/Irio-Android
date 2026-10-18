package com.irio

import android.content.Context
import android.media.MediaRecorder
import java.io.File

internal class SttRecorder(context: Context) {
    private val context = context.applicationContext
    private val cacheDir = File(context.cacheDir, "stt").apply { mkdirs() }
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(): Boolean {
        if (recorder != null) return false
        val target = runCatching { File.createTempFile("recording-", ".m4a", cacheDir) }.getOrNull() ?: return false
        val created = runCatching {
            MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(16_000)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }
        }.getOrNull()
        if (created == null) {
            target.delete()
            return false
        }
        output = target
        recorder = created
        return true
    }

    fun sampleAmplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    fun stop(): File? {
        val current = recorder ?: return null
        recorder = null
        val target = output
        output = null
        runCatching { current.stop() }
        current.release()
        return target?.takeIf { it.exists() && it.length() > 0L } ?: target?.also { it.delete() }
    }

    fun cancel() {
        val current = recorder ?: return
        recorder = null
        output?.delete()
        output = null
        runCatching { current.stop() }
        current.release()
    }
}
