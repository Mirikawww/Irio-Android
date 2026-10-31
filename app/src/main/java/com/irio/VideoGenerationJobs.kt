package com.irio

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class VideoGenerationJob(
    val taskId: String,
    val conversationId: String,
    val callId: String,
    val videoProviderId: String,
    val model: String,
    val chatProviderId: String,
    val chatModel: String,
    val url: String = "",
    val error: String = "",
    val finished: Boolean = false,
    val delivered: Boolean = false,
) {
    fun attachment(): ChatAttachment? = url.takeIf(String::isNotBlank)?.let {
        ChatAttachment("video-$taskId.mp4", "video/mp4", "", remoteUrl = it)
    }
    fun resultJson(): String = JSONObject().put("task_id", taskId)
        .put("status", if (error.isNotBlank()) "failed" else if (finished) "succeeded" else "running")
        .apply { if (url.isNotBlank()) put("url", url); if (error.isNotBlank()) put("error", error) }.toString()
    fun toJson(): JSONObject = JSONObject(resultJson()).put("conversationId", conversationId).put("callId", callId)
        .put("videoProviderId", videoProviderId).put("model", model).put("chatProviderId", chatProviderId)
        .put("chatModel", chatModel).put("finished", finished).put("delivered", delivered)
    companion object {
        fun fromJson(item: JSONObject) = VideoGenerationJob(item.getString("task_id"), item.getString("conversationId"),
            item.getString("callId"), item.getString("videoProviderId"), item.getString("model"),
            item.getString("chatProviderId"), item.getString("chatModel"), item.optString("url"), item.optString("error"),
            item.optBoolean("finished"), item.optBoolean("delivered"))
    }
}

/** Application-scoped polling is independent of the chat stream and Activity composition. */
internal object VideoGenerationJobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = linkedMapOf<String, VideoGenerationJob>()
    private val cancellations = mutableMapOf<String, ChatRequestCancellation>()
    private val listeners = linkedSetOf<(VideoGenerationJob) -> Unit>()
    private var root: File? = null

    @Synchronized private fun initialize(context: Context) {
        val nextRoot = File(context.filesDir, "video-generation-jobs")
        if (root == nextRoot) return
        cancellations.values.forEach { it.cancel() }
        cancellations.clear()
        jobs.clear()
        root = nextRoot
        nextRoot.mkdirs()
        nextRoot.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            runCatching { VideoGenerationJob.fromJson(JSONObject(file.readText())) }.getOrNull()?.let { jobs[it.taskId] = it }
        }
    }

    @Synchronized private fun save(job: VideoGenerationJob) {
        jobs[job.taskId] = job
        val directory = root ?: return
        // Use a hash rather than interpolating an API-supplied identifier into a path.
        val filename = java.security.MessageDigest.getInstance("SHA-256").digest(job.taskId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$filename.json")
        val temporary = File(directory, "$filename.part")
        temporary.writeText(job.toJson().toString())
        java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun observe(context: Context, listener: (VideoGenerationJob) -> Unit): () -> Unit {
        val completed: List<VideoGenerationJob>
        synchronized(this) {
            initialize(context)
            listeners += listener
            completed = jobs.values.filter { it.finished && !it.delivered }
        }
        completed.forEach(listener)
        scope.launch { resume(context.applicationContext) }
        return { synchronized(this) { listeners -= listener } }
    }

    fun start(context: Context, job: VideoGenerationJob, provider: ProviderConfig) {
        synchronized(this) {
            initialize(context)
            if (job.taskId !in jobs) save(job)
        }
        poll(job.taskId, provider)
    }

    private fun resume(context: Context) {
        val pending = synchronized(this) { jobs.values.filter { !it.finished } }
        val providers = ProviderStore.loadProviders(context)
        pending.forEach { job ->
            val provider = providers.firstOrNull { it.id == job.videoProviderId }
            if (provider != null) poll(job.taskId, provider)
            else complete(job.copy(finished = true, error = tr("视频提供商已删除，无法查询任务")))
        }
    }

    private fun poll(taskId: String, provider: ProviderConfig) {
        val cancellation = synchronized(this) {
            if (cancellations.containsKey(taskId) || jobs[taskId]?.finished != false) return
            ChatRequestCancellation().also { cancellations[taskId] = it }
        }
        scope.launch {
            try {
                val result = VideoGenerationApi.await(provider, taskId, cancellation = cancellation)
                val job = synchronized(this@VideoGenerationJobs) { jobs[taskId] } ?: return@launch
                complete(job.copy(finished = true, url = result.attachment?.remoteUrl.orEmpty(), error = result.error.orEmpty()))
            } catch (error: Exception) {
                val job = synchronized(this@VideoGenerationJobs) { jobs[taskId] } ?: return@launch
                complete(job.copy(finished = true, error = error.message ?: tr("视频生成失败")))
            } finally {
                synchronized(this@VideoGenerationJobs) { cancellations.remove(taskId) }
            }
        }
    }

    private fun complete(job: VideoGenerationJob) {
        val callbacks = synchronized(this) {
            // Deleted conversations cannot be resurrected by a late HTTP result.
            if (job.taskId !in jobs) return
            save(job)
            listeners.toList()
        }
        callbacks.forEach { it(job) }
    }

    @Synchronized fun acknowledge(taskId: String) {
        jobs[taskId]?.let { save(it.copy(delivered = true)) }
    }

    @Synchronized fun forgetConversation(conversationId: String) {
        val removed = jobs.values.filter { it.conversationId == conversationId }
        removed.forEach { job ->
            cancellations.remove(job.taskId)?.cancel()
            jobs.remove(job.taskId)
        }
        root?.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            val id = runCatching { JSONObject(file.readText()).optString("task_id") }.getOrNull()
            if (removed.any { it.taskId == id }) file.delete()
        }
    }

    @Synchronized fun forgetAll() {
        jobs.values.map { it.conversationId }.distinct().forEach(::forgetConversation)
    }
}
