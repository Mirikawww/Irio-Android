package com.irio

import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object LinuxSandboxHost {
    const val PROOT = "libproot.so"
    const val LOADER = "libproot-loader.so"
    const val LOADER32 = "libproot-loader32.so"
}

internal data class LinuxSandboxInstallResult(
    val ok: Boolean,
    val message: String,
)

/**
 * Android 10 起，应用不能执行自己目录里临时下载的程序。proot 和它的 loader
 * 因此打进 nativeLibraryDir，并在打包时展开成可执行文件。根文件系统只从
 * assets 解压，失败或关闭都不会删掉已经就绪的 rootfs。
 */
internal object LinuxSandboxSession {
    private const val OUTPUT_LIMIT = 12_000
    private const val TIMEOUT_MS = 60_000L

    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null

    @Volatile
    private var deleting = false

    @Volatile
    private var foregroundProcess: Process? = null

    @Volatile
    var preparing: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun kick(context: Context, rebuild: Boolean = false) {
        if (deleting || !LinuxSandboxStore.loadEnabled(LinuxSandboxLayout.of(context.filesDir).stateFile)) return
        if (rebuild) LinuxBackgroundJobs.stopAll()
        if (!rebuild && job?.isActive == true) return
        val app = context.applicationContext
        preparing = true
        lastError = null
        job = jobs.launch {
            val outcome = runCatching {
                mutex.withLock {
                    val layout = LinuxSandboxLayout.of(app.filesDir)
                    if (deleting || !LinuxSandboxStore.loadEnabled(layout.stateFile)) {
                        return@withLock LinuxSandboxInstallResult(true, "")
                    }
                    if (!rebuild && layout.installedVersion() != null && layout.shellPresent()) {
                        return@withLock LinuxSandboxInstallResult(true, "")
                    }
                    installLocked(app, rebuild)
                }
            }
            preparing = false
            outcome.onSuccess { result ->
                lastError = result.message.takeIf { !result.ok }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                lastError = linuxSandboxFailureMessage(error)
            }
        }
    }

    /** 与安装/前台命令串行，防止排队中的工具在删除后又把环境解压回来。 */
    suspend fun delete(context: Context) {
        deleting = true
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val layout = LinuxSandboxLayout.of(context.filesDir)
                LinuxSandboxStore.saveEnabled(layout.stateFile, false)
                foregroundProcess?.let { stopProcess(it) }
                job?.cancelAndJoin()
                mutex.withLock {
                    LinuxBackgroundJobs.running().forEach { LinuxBackgroundJobs.stop(it.id, notify = false) }
                    LinuxSandboxStore.delete(layout, File(context.cacheDir, "linux-sandbox-tmp"))
                    lastError = null
                }
            } finally {
                preparing = false
                deleting = false
            }
        }
    }

    suspend fun runCommand(
        context: Context,
        command: String,
        cancellation: ChatRequestCancellation,
        onProgress: suspend (LinuxSandboxCommandProgress) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val normalized = linuxSandboxNormalizeCommand(command)
            ?: return@withContext if (command.trim().isEmpty()) tr("命令为空") else tr("命令太长")
        mutex.withLock {
            if (deleting || !LinuxSandboxStore.loadEnabled(LinuxSandboxLayout.of(context.filesDir).stateFile)) {
                return@withLock tr("Linux 沙盒已关闭。")
            }
            val installed = installLocked(context, rebuild = false)
            if (!installed.ok) return@withLock installed.message
            execLocked(context, normalized, cancellation, onProgress)
        }
    }

    /** 启动后立刻返回。进程不跟这次工具调用一起结束。 */
    suspend fun startBackground(context: Context, command: String, conversationId: String = ""): LinuxBackgroundStart =
        withContext(Dispatchers.IO) {
            val normalized = linuxSandboxNormalizeCommand(command)
                ?: return@withContext LinuxBackgroundStart(
                    error = if (command.trim().isEmpty()) tr("命令为空") else tr("命令太长"),
                )
            mutex.withLock {
                if (deleting || !LinuxSandboxStore.loadEnabled(LinuxSandboxLayout.of(context.filesDir).stateFile)) {
                    return@withLock LinuxBackgroundStart(error = tr("Linux 沙盒已关闭。"))
                }
                val installed = installLocked(context, rebuild = false)
                if (!installed.ok) return@withLock LinuxBackgroundStart(error = installed.message)
                val process = try {
                    openProcess(context, normalized)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    return@withLock LinuxBackgroundStart(
                        error = error.message?.takeIf { it.isNotBlank() } ?: tr("无法启动后台命令。"),
                    )
                } ?: return@withLock LinuxBackgroundStart(error = tr("安装包里没有 Linux 运行时。"))
                val id = LinuxBackgroundJobs.track(normalized, conversationId) { stopProcess(process) }
                jobs.launch { drain(process, id) }
                LinuxBackgroundStart(id = id)
            }
        }

    private fun installLocked(context: Context, rebuild: Boolean): LinuxSandboxInstallResult {
        val catalog = loadLinuxSandboxCatalog(context)
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in catalog.archives }
            ?: return LinuxSandboxInstallResult(false, tr("这个安装包没有当前设备架构的 Linux 环境。"))
        val pin = catalog.archives.getValue(abi)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        if (!File(nativeDir, LinuxSandboxHost.PROOT).isFile || !File(nativeDir, LinuxSandboxHost.LOADER).isFile) {
            return LinuxSandboxInstallResult(false, tr("安装包里没有 Linux 运行时。"))
        }
        val layout = LinuxSandboxLayout.of(context.filesDir)
        val installed = layout.installedVersion()
        if (!linuxSandboxShouldExtract(installed, layout.rootfs.isDirectory, rebuild)) {
            return LinuxSandboxInstallResult(true, "")
        }
        val bytes = try {
            context.assets.open(pin.asset).use { it.readBytes() }
        } catch (missing: java.io.FileNotFoundException) {
            return LinuxSandboxInstallResult(false, linuxSandboxFailureMessage(missing))
        }
        if (!linuxSandboxSha256(bytes).equals(pin.sha256, ignoreCase = true)) {
            return LinuxSandboxInstallResult(false, tr("安装包里的根文件系统校验失败"))
        }
        if (layout.partial.exists()) linuxSandboxDeleteTree(layout.partial)
        layout.partial.mkdirs()
        try {
            linuxSandboxExtractArchive(bytes, layout.partial)
            linuxSandboxEnsureResolvConf(layout.partial)
            linuxSandboxCommitExtractedRootfs(layout, catalog.alpineVersion)
        } catch (error: Exception) {
            if (layout.partial.exists()) runCatching { linuxSandboxDeleteTree(layout.partial) }
            throw error
        }
        return LinuxSandboxInstallResult(true, "")
    }

    private suspend fun execLocked(
        context: Context,
        command: String,
        cancellation: ChatRequestCancellation,
        onProgress: suspend (LinuxSandboxCommandProgress) -> Unit,
    ): String {
        val process = openProcess(context, command) ?: return tr("安装包里没有 Linux 运行时。")
        foregroundProcess = process
        try {
            if (deleting) {
                stopProcess(process)
                return tr("Linux 沙盒已关闭。")
            }
            return readProcess(process, cancellation, onProgress)
        } finally {
            foregroundProcess = null
        }
    }

    private fun openProcess(context: Context, command: String): Process? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, LinuxSandboxHost.PROOT)
        val loader = File(nativeDir, LinuxSandboxHost.LOADER)
        if (!proot.isFile || !loader.isFile) return null
        val loader32 = File(nativeDir, LinuxSandboxHost.LOADER32).takeIf { it.isFile }
        val layout = LinuxSandboxLayout.of(context.filesDir)
        val storage = android.os.Environment.getExternalStorageDirectory().takeIf { it.isDirectory }?.absolutePath
        val hostTmp = File(context.cacheDir, "linux-sandbox-tmp").apply { mkdirs() }
        listOf(proot, loader, loader32).forEach { file ->
            if (file != null) runCatching { android.system.Os.chmod(file.absolutePath, 493) }
        }
        return ProcessBuilder(
            linuxSandboxProotArguments(
                proot = proot.absolutePath,
                rootfs = layout.rootfs.absolutePath,
                storage = storage,
                hostTmp = hostTmp.absolutePath,
                command = command,
            ),
        ).redirectErrorStream(true).directory(hostTmp).apply {
            environment().clear()
            environment().putAll(
                linuxSandboxProcessEnvironment(
                    nativeLibDir = nativeDir.absolutePath,
                    loader = loader.absolutePath,
                    loader32 = loader32?.absolutePath,
                    hostTmp = hostTmp.absolutePath,
                ),
            )
        }.start()
    }

    private fun stopProcess(process: Process) {
        val root = processPid(process)
        if (root > 0) killProcessTree(root, mutableSetOf())
        runCatching { process.destroyForcibly() }
    }

    private fun processPid(process: Process): Int {
        val field = runCatching { process.javaClass.getDeclaredField("pid") }.getOrNull()
            ?: process.javaClass.superclass?.let { type ->
                runCatching { type.getDeclaredField("pid") }.getOrNull()
            }
            ?: return 0
        field.isAccessible = true
        return when (val value = field.get(process)) {
            is Int -> value
            is Long -> value.toInt()
            else -> 0
        }
    }

    /** destroy 只打到 proot 自己。guest 里的命令要沿着 /proc 子进程树一起杀，否则停了还在跑。 */
    private fun killProcessTree(pid: Int, seen: MutableSet<Int>) {
        if (pid <= 0 || !seen.add(pid)) return
        val children = runCatching { File("/proc/$pid/task/$pid/children").readText() }.getOrDefault("")
        children.split(Regex("\\s+")).forEach { token ->
            token.toIntOrNull()?.let { killProcessTree(it, seen) }
        }
        android.os.Process.sendSignal(pid, 15)
        android.os.Process.sendSignal(pid, 9)
    }

    /** 标准输出结束不等于进程结束，这里只等待退出，不主动杀掉。 */
    private fun drain(process: Process, id: Long) {
        val buffer = ByteArray(4096)
        try {
            val stream = process.inputStream
            while (true) {
                val count = try {
                    stream.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (count < 0) break
                LinuxBackgroundJobs.append(id, buffer, count)
            }
        } finally {
            runCatching { process.waitFor() }
            val code = runCatching { process.exitValue() }.getOrDefault(-1)
            LinuxBackgroundJobs.finish(id, code)
        }
    }

    private suspend fun readProcess(
        process: Process,
        cancellation: ChatRequestCancellation,
        onProgress: suspend (LinuxSandboxCommandProgress) -> Unit,
    ): String {
        val collected = ByteArrayOutputStream()
        var timedOut = false
        var truncated = false
        val stream = process.inputStream
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        var emittedSize = -1
        suspend fun emit(exitCode: Int? = null) {
            if (exitCode == null && collected.size() == emittedSize) return
            emittedSize = collected.size()
            onProgress(
                LinuxSandboxCommandProgress(
                    output = collected.toByteArray().toString(Charsets.UTF_8),
                    exitCode = exitCode,
                    timedOut = timedOut,
                    truncated = truncated,
                ),
            )
        }
        try {
            val buffer = ByteArray(4096)
            while (process.isAlive) {
                if (cancellation.isCancelled()) throw CancellationException()
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    break
                }
                if (pumpAvailable(stream, buffer, collected)) truncated = true
                emit()
                if (collected.size() >= OUTPUT_LIMIT) {
                    truncated = true
                    break
                }
                if (process.isAlive) process.waitFor(50, TimeUnit.MILLISECONDS)
            }
            if (timedOut || truncated || cancellation.isCancelled()) {
                process.destroyForcibly()
                if (pumpAvailable(stream, buffer, collected)) truncated = true
            } else if (collected.size() < OUTPUT_LIMIT) {
                val rest = stream.readNBytes(OUTPUT_LIMIT - collected.size() + 1)
                if (rest.isNotEmpty() && !take(collected, rest, rest.size)) truncated = true
            }
        } catch (cancelled: CancellationException) {
            process.destroyForcibly()
            throw cancelled
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        if (cancellation.isCancelled()) throw CancellationException()
        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        emit(exitCode)
        return linuxSandboxFormatResult(
            exitCode = exitCode,
            output = collected.toByteArray().toString(Charsets.UTF_8),
            timedOut = timedOut,
            truncated = truncated,
        )
    }

    private fun pumpAvailable(
        stream: java.io.InputStream,
        buffer: ByteArray,
        collected: ByteArrayOutputStream,
    ): Boolean {
        var truncated = false
        while (collected.size() < OUTPUT_LIMIT) {
            val ready = stream.available()
            if (ready <= 0) break
            val count = stream.read(buffer, 0, minOf(buffer.size, ready))
            if (count <= 0) break
            if (!take(collected, buffer, count)) truncated = true
        }
        return truncated
    }

    private fun take(output: ByteArrayOutputStream, bytes: ByteArray, count: Int): Boolean {
        val room = OUTPUT_LIMIT - output.size()
        if (room <= 0) return false
        val accepted = minOf(count, room)
        output.write(bytes, 0, accepted)
        return accepted == count
    }
}

internal fun loadLinuxSandboxCatalog(context: Context): LinuxSandboxCatalog =
    parseLinuxSandboxCatalog(
        context.assets.open("linux-sandbox/catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() },
    )

internal object LinuxSandboxAccess {
    fun hasAllFiles(): Boolean = android.os.Environment.isExternalStorageManager()
}
