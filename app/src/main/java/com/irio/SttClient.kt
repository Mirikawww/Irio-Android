package com.irio

import org.json.JSONObject
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

data class SttResult(
    val text: String = "",
    val error: String? = null,
)

internal object SttClient {
    private const val GROQ_TRANSCRIPTIONS = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val DEEPGRAM_TRANSCRIPTIONS = "https://api.deepgram.com/v1/listen"
    private const val GLADIA_UPLOAD = "https://api.gladia.io/v2/upload"
    private const val GLADIA_TRANSCRIPTIONS = "https://api.gladia.io/v2/pre-recorded"
    private const val ASSEMBLYAI_TRANSCRIPTIONS = "https://sync.assemblyai.com/v1/transcribe"
    private const val ASSEMBLYAI_UPLOAD = "https://api.assemblyai.com/v2/upload"
    private const val ASSEMBLYAI_ASYNC_TRANSCRIPTIONS = "https://api.assemblyai.com/v2/transcript"
    private const val AUDIO_MEDIA_TYPE = "audio/mp4"

    fun transcribe(file: File, provider: SttProviderConfig): SttResult {
        if (!file.exists() || file.length() == 0L) return SttResult(error = tr("录音为空"))
        return when (provider.kind) {
            SttProviderKind.GROQ -> transcribeGroq(file, provider)
            SttProviderKind.DEEPGRAM -> transcribeDeepgram(file, provider)
            SttProviderKind.GLADIA -> transcribeGladia(file, provider)
            SttProviderKind.ASSEMBLYAI -> transcribeAssemblyAi(file, provider)
        }
    }

    private fun transcribeDeepgram(file: File, provider: SttProviderConfig): SttResult {
        val apiKey = normalizeSttApiKey(provider.apiKey)
        if (apiKey.isBlank()) return SttResult(error = tr("请先配置 STT 提供商"))
        return try {
            val url = DEEPGRAM_TRANSCRIPTIONS.toHttpUrl().newBuilder()
                .addQueryParameter("model", provider.model)
                .addQueryParameter("smart_format", "true")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Token $apiKey")
                .header("Content-Type", AUDIO_MEDIA_TYPE)
                .post(file.asRequestBody(AUDIO_MEDIA_TYPE.toMediaType()))
                .build()
            IrioHttp.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return httpError(response.code, body)
                val text = runCatching {
                    JSONObject(body).getJSONObject("results").getJSONArray("channels")
                        .getJSONObject(0).getJSONArray("alternatives").getJSONObject(0)
                        .optString("transcript")
                }.getOrDefault("")
                transcriptResult(text)
            }
        } catch (error: Exception) {
            SttResult(error = error.message ?: tr("STT 请求失败"))
        }
    }

    private fun transcribeGladia(file: File, provider: SttProviderConfig): SttResult {
        val apiKey = normalizeSttApiKey(provider.apiKey)
        if (apiKey.isBlank()) return SttResult(error = tr("请先配置 STT 提供商"))
        return try {
            val uploadBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    "recording.m4a",
                    file.asRequestBody(AUDIO_MEDIA_TYPE.toMediaType()),
                )
                .build()
            val uploadRequest = Request.Builder()
                .url(GLADIA_UPLOAD)
                .header("x-gladia-key", apiKey)
                .post(uploadBody)
                .build()
            val audioUrl = IrioHttp.client.newCall(uploadRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return httpError(response.code, body)
                JSONObject(body).optString("audio_url").takeIf { it.isNotBlank() }
                    ?: return SttResult(error = tr("STT 请求失败"))
            }
            val jobPayload = JSONObject()
                .put("audio_url", audioUrl)
                .put("model", provider.model)
            if (provider.model == "solaria-3") {
                jobPayload.put("language_config", JSONObject().put("languages", org.json.JSONArray().put(provider.language)))
            }
            val jobBody = jobPayload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val jobRequest = Request.Builder()
                .url(GLADIA_TRANSCRIPTIONS)
                .header("x-gladia-key", apiKey)
                .post(jobBody)
                .build()
            val (jobId, resultUrl) = IrioHttp.client.newCall(jobRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return httpError(response.code, body)
                val job = JSONObject(body)
                job.optString("id") to job.optString("result_url")
            }
            if (jobId.isBlank() && resultUrl.isBlank()) return SttResult(error = tr("STT 请求失败"))
            pollGladia(apiKey, jobId, resultUrl)
        } catch (error: Exception) {
            SttResult(error = error.message ?: tr("STT 请求失败"))
        }
    }

    private fun pollGladia(apiKey: String, jobId: String, resultUrl: String): SttResult {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3)
        val url = resultUrl.ifBlank { "$GLADIA_TRANSCRIPTIONS/$jobId" }
        while (System.nanoTime() < deadline) {
            val request = Request.Builder().url(url).header("x-gladia-key", apiKey).get().build()
            IrioHttp.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return httpError(response.code, body)
                val json = JSONObject(body)
                when (json.optString("status").lowercase()) {
                    "done" -> {
                        val result = json.optJSONObject("result")
                        val text = result?.optJSONObject("transcription")?.optString("full_transcript")
                            .orEmpty().ifBlank { result?.optString("full_transcript").orEmpty() }
                        return transcriptResult(text)
                    }
                    "error", "failed" -> {
                        return SttResult(error = apiError(body).ifBlank { tr("STT 请求失败") })
                    }
                }
            }
            Thread.sleep(1_000)
        }
        return SttResult(error = tr("STT 请求失败"))
    }

    private fun transcribeAssemblyAi(file: File, provider: SttProviderConfig): SttResult {
        val apiKey = normalizeSttApiKey(provider.apiKey)
        if (apiKey.isBlank()) return SttResult(error = tr("请先配置 STT 提供商"))
        if (provider.model == "universal-2") return transcribeAssemblyAiAsync(file, apiKey, provider.model)
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    "recording.m4a",
                    file.asRequestBody(AUDIO_MEDIA_TYPE.toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url(ASSEMBLYAI_TRANSCRIPTIONS)
                .header("Authorization", apiKey)
                .header("X-AAI-Model", provider.model)
                .post(body)
                .build()
            IrioHttp.client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) return httpError(response.code, responseBody)
                transcriptResult(runCatching { JSONObject(responseBody).optString("text") }.getOrDefault(""))
            }
        } catch (error: Exception) {
            SttResult(error = error.message ?: tr("STT 请求失败"))
        }
    }

    private fun transcribeAssemblyAiAsync(file: File, apiKey: String, model: String): SttResult {
        return try {
            val uploadRequest = Request.Builder()
                .url(ASSEMBLYAI_UPLOAD)
                .header("Authorization", apiKey)
                .post(file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            val audioUrl = IrioHttp.client.newCall(uploadRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return httpError(response.code, body)
                JSONObject(body).optString("upload_url").takeIf { it.isNotBlank() }
                    ?: return SttResult(error = tr("STT 请求失败"))
            }
            val payload = JSONObject()
                .put("audio_url", audioUrl)
                .put("speech_models", org.json.JSONArray().put(model))
                .put("language_detection", true)
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val transcriptId = IrioHttp.client.newCall(
                Request.Builder()
                    .url(ASSEMBLYAI_ASYNC_TRANSCRIPTIONS)
                    .header("Authorization", apiKey)
                    .post(payload)
                    .build(),
            ).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return httpError(response.code, body)
                JSONObject(body).optString("id").takeIf { it.isNotBlank() }
                    ?: return SttResult(error = tr("STT 请求失败"))
            }
            pollAssemblyAi(apiKey, transcriptId)
        } catch (error: Exception) {
            SttResult(error = error.message ?: tr("STT 请求失败"))
        }
    }

    private fun pollAssemblyAi(apiKey: String, transcriptId: String): SttResult {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3)
        while (System.nanoTime() < deadline) {
            val request = Request.Builder()
                .url("$ASSEMBLYAI_ASYNC_TRANSCRIPTIONS/$transcriptId")
                .header("Authorization", apiKey)
                .get()
                .build()
            IrioHttp.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return httpError(response.code, body)
                val json = JSONObject(body)
                when (json.optString("status")) {
                    "completed" -> return transcriptResult(json.optString("text"))
                    "error" -> return SttResult(error = json.optString("error").ifBlank { tr("STT 请求失败") })
                }
            }
            Thread.sleep(3_000)
        }
        return SttResult(error = tr("STT 请求失败"))
    }

    private fun transcribeGroq(file: File, provider: SttProviderConfig): SttResult {
        val apiKey = normalizeSttApiKey(provider.apiKey)
        if (apiKey.isBlank()) return SttResult(error = tr("请先配置 STT 提供商"))
        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", provider.model)
                .addFormDataPart("response_format", "json")
                .addFormDataPart(
                    "file",
                    "recording.m4a",
                    file.asRequestBody("audio/mp4".toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url(GROQ_TRANSCRIPTIONS)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .post(requestBody)
                .build()
            IrioHttp.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    SttResult(
                        error = when {
                            response.code == 403 -> tr("Groq API Key 无权访问 STT，请检查项目权限，也可能是网络问题")
                            apiError(body).isNotBlank() -> apiError(body)
                            else -> tr("STT 请求失败（HTTP %s）").format(response.code)
                        },
                    )
                } else {
                    val text = runCatching { JSONObject(body).optString("text") }.getOrDefault("").trim()
                    if (text.isBlank()) SttResult(error = tr("STT 没有返回文字")) else SttResult(text = text)
                }
            }
        } catch (error: Exception) {
            SttResult(error = error.message ?: tr("STT 请求失败"))
        }
    }

    private fun apiError(body: String): String = runCatching {
        val root = JSONObject(body)
        root.optJSONObject("error")?.optString("message")
            .orEmpty()
            .ifBlank { root.optString("message") }
    }.getOrDefault("")

    private fun transcriptResult(text: String): SttResult = text.trim().let {
        if (it.isBlank()) SttResult(error = tr("STT 没有返回文字")) else SttResult(text = it)
    }

    private fun httpError(code: Int, body: String): SttResult = SttResult(
        error = apiError(body).ifBlank { tr("STT 请求失败（HTTP %s）").format(code) },
    )
}
