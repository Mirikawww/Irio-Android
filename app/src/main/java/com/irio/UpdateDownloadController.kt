package com.irio

import android.content.Context
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class UpdateDownloadPhase { Idle, Downloading, Ready, Failed }
internal data class UpdateDownloadState(
    val release: GitHubUpdate? = null,
    val phase: UpdateDownloadPhase = UpdateDownloadPhase.Idle,
    val progress: UpdateDownloadProgress = UpdateDownloadProgress(0, 1),
    val file: File? = null,
    val error: String? = null,
)

/** One process-owned transfer; rotations and briefly backgrounding do not duplicate it. */
internal class UpdateDownloadController(
    private val scope: CoroutineScope,
    private val downloader: UpdateApkDownloader = UpdateApkDownloader(),
) {
    private val mutable = MutableStateFlow(UpdateDownloadState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()

    @Synchronized fun start(context: Context, release: GitHubUpdate) {
        if (job?.isActive == true) return
        val asset = release.asset ?: return
        val app = context.applicationContext
        val token = generation.incrementAndGet()
        mutable.value = UpdateDownloadState(release, UpdateDownloadPhase.Downloading, UpdateDownloadProgress(0, asset.size))
        job = scope.launch {
            try {
                val file = downloader.download(asset, File(app.cacheDir, "app-updates")) { progress ->
                    if (generation.get() == token) mutable.value = mutable.value.copy(progress = progress)
                }
                withContext(Dispatchers.IO) { validateDownloadedUpdate(app, release, file) }
                if (generation.get() == token) mutable.value = mutable.value.copy(phase = UpdateDownloadPhase.Ready, file = file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation.get() == token) mutable.value = mutable.value.copy(phase = UpdateDownloadPhase.Failed, error = e.message ?: "更新下载失败")
            }
        }
    }

    @Synchronized fun cancel() {
        generation.incrementAndGet()
        job?.cancel(); job = null
        mutable.value = UpdateDownloadState()
    }
}

internal object AppUpdateDownloads {
    val controller = UpdateDownloadController(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
}
