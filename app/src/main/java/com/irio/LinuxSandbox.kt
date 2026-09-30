package com.irio

import org.json.JSONObject
import java.io.File

/**
 * 随安装包附带的 Alpine 根文件系统。开关只决定要不要把环境交给模型，
 * 不负责下载，也不在关闭时删除已经解压的系统。
 *
 * 版本由 scripts/refresh-linux-sandbox.py 写进 assets 和 sandbox/upstream.lock.json。
 */
internal data class LinuxSandboxCatalog(
    val alpineVersion: String,
    val archives: Map<String, LinuxSandboxArchivePin>,
)

internal data class LinuxSandboxArchivePin(
    val abi: String,
    val asset: String,
    val sha256: String,
)

internal fun parseLinuxSandboxCatalog(json: String): LinuxSandboxCatalog {
    val root = JSONObject(json)
    val archives = root.getJSONArray("archives")
    val pins = buildMap {
        for (index in 0 until archives.length()) {
            val item = archives.getJSONObject(index)
            val pin = LinuxSandboxArchivePin(
                abi = item.getString("abi"),
                asset = item.getString("asset"),
                sha256 = item.getString("sha256"),
            )
            put(pin.abi, pin)
        }
    }
    return LinuxSandboxCatalog(root.getString("alpineVersion"), pins)
}

internal class LinuxSandboxLayout(val root: File) {
    val stateFile: File = File(root, "state.json")
    val rootfs: File = File(root, "rootfs")
    val partial: File = File(root, "rootfs.partial")
    val versionFile: File = File(root, "rootfs.version")

    fun installedVersion(): String? =
        versionFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    fun shellPresent(): Boolean {
        val shell = listOf(File(rootfs, "bin/sh"), File(rootfs, "usr/bin/sh"))
        return shell.any { file ->
            val path = file.toPath()
            java.nio.file.Files.isSymbolicLink(path) || file.exists()
        }
    }

    companion object {
        fun of(filesDir: File): LinuxSandboxLayout = LinuxSandboxLayout(File(filesDir, "linux-sandbox"))
    }
}

internal object LinuxSandboxStore {
    /** 缺省关闭。关闭只改这个标记。 */
    fun loadEnabled(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching { JSONObject(file.readText()).optBoolean("enabled", false) }.getOrDefault(false)
    }

    fun saveEnabled(file: File, enabled: Boolean) {
        file.parentFile?.mkdirs()
        val root = if (file.isFile) {
            runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        } else {
            JSONObject()
        }
        root.put("enabled", enabled)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }
}

internal object LinuxSandboxPolicy {
    fun exposed(enabled: Boolean, allFiles: Boolean): Boolean = enabled && allFiles
}

/**
 * 没有已安装版本，或根目录本身不在了，才自动解压。
 * 重新准备必须显式要求，避免关掉开关或打开页面时清掉用户装过的包。
 */
internal fun linuxSandboxShouldExtract(
    installedVersion: String?,
    rootfsPresent: Boolean,
    rebuild: Boolean,
): Boolean = rebuild || !rootfsPresent || installedVersion == null

/** 新根目录已经解压完成后再替换旧的。替换失败时把旧目录放回去。 */
internal fun linuxSandboxCommitExtractedRootfs(layout: LinuxSandboxLayout, version: String) {
    val previous = File(layout.root, "rootfs.previous")
    if (previous.exists()) linuxSandboxDeleteTree(previous)
    if (layout.rootfs.exists() && !layout.rootfs.renameTo(previous)) {
        throw java.io.IOException(tr("无法完成环境目录"))
    }
    if (!layout.partial.renameTo(layout.rootfs)) {
        if (previous.exists()) previous.renameTo(layout.rootfs)
        throw java.io.IOException(tr("无法完成环境目录"))
    }
    if (previous.exists()) linuxSandboxDeleteTree(previous)
    layout.versionFile.writeText(version)
}

internal fun linuxSandboxFailureMessage(error: Throwable): String {
    val message = error.message?.trim().orEmpty()
    if (message.isEmpty() || message.contains("linux-sandbox/") || message.endsWith(".tar.gz") || message.endsWith(".tar")) {
        return tr("安装包里没有这份根文件系统")
    }
    return message
}

internal fun linuxSandboxOfferRebuild(
    installedVersion: String?,
    bundledVersion: String?,
    shellPresent: Boolean,
): Boolean {
    if (installedVersion == null) return false
    if (!shellPresent) return true
    return bundledVersion != null && installedVersion != bundledVersion
}

internal fun linuxSandboxStatus(
    enabled: Boolean,
    allFiles: Boolean,
    preparing: Boolean,
    error: String?,
    installedVersion: String?,
    bundledVersion: String?,
    shellPresent: Boolean,
): String {
    if (preparing) return tr("正在准备环境")
    if (enabled && !allFiles) {
        return tr("还没有管理所有文件的权限，模型暂时看不到这个环境。数据会保留。")
    }
    if (!error.isNullOrBlank()) return tr("准备失败：%s").format(error)
    if (installedVersion != null && !shellPresent) return tr("环境不完整，需要重新准备。")
    if (enabled && installedVersion != null && bundledVersion != null && installedVersion != bundledVersion) {
        return tr("已安装 Alpine %s，安装包附带 %s。模型仍使用已安装的环境。")
            .format(installedVersion, bundledVersion)
    }
    if (enabled && shellPresent && installedVersion != null) {
        return tr("已就绪 · Alpine %s").format(installedVersion)
    }
    if (!enabled && installedVersion != null) return tr("环境已保留在本机，当前没有提供给模型。")
    return tr("尚未准备。打开开关后会从安装包解压，不需要下载。")
}

internal fun linuxSandboxNormalizeCommand(raw: String): String? {
    val command = raw.trim()
    if (command.isEmpty() || command.length > 8_000) return null
    return command
}

internal fun linuxSandboxProotArguments(
    proot: String,
    rootfs: String,
    storage: String?,
    hostTmp: String,
    command: String,
): List<String> = buildList {
    add(proot)
    // Termux proot 的长选项必须写成 --name=value，空格分开会直接退出。
    add("--rootfs=$rootfs")
    add("--bind=/dev")
    add("--bind=/proc")
    add("--bind=/sys")
    add("--bind=$hostTmp:/tmp")
    if (!storage.isNullOrBlank()) {
        add("--bind=$storage:/sdcard")
        add("--bind=$storage:/storage/emulated/0")
    }
    add("--cwd=/root")
    add("--link2symlink")
    add("--kill-on-exit")
    add("-0")
    add("/bin/sh")
    add("-c")
    add(command)
}

internal fun linuxSandboxProcessEnvironment(
    nativeLibDir: String,
    loader: String,
    loader32: String?,
    hostTmp: String,
): Map<String, String> = buildMap {
    put("HOME", "/root")
    put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    put("TERM", "xterm-256color")
    put("LANG", "C.UTF-8")
    put("TMPDIR", "/tmp")
    put("PROOT_LOADER", loader)
    if (!loader32.isNullOrBlank()) put("PROOT_LOADER_32", loader32)
    put("PROOT_TMP_DIR", hostTmp)
    put("PROOT_NO_SECCOMP", "1")
    put("PROOT_IGNORE_MISSING_BINDINGS", "1")
    put("LD_LIBRARY_PATH", nativeLibDir)
}

internal data class LinuxSandboxCommandProgress(
    val output: String,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

/** 终端 sheet 里的全文。进行中没有退出码；结束时把超时、截断和退出码接在输出后面。 */
internal fun linuxSandboxTerminalTranscript(
    command: String,
    output: String,
    exitCode: Int? = null,
    timedOut: Boolean = false,
    truncated: Boolean = false,
): String = buildString {
    append("$ ")
    append(command.trim())
    val body = output.trimEnd()
    if (body.isNotEmpty()) {
        append('\n')
        append(body)
    }
    val notes = buildList {
        if (timedOut) add(tr("命令超时"))
        if (truncated) add(tr("输出已截断"))
    }
    if (notes.isNotEmpty() || exitCode != null) {
        append('\n')
        if (notes.isNotEmpty()) {
            append(notes.joinToString("，"))
            append('\n')
        }
        if (exitCode != null) append(tr("退出码 %s").format(exitCode))
    }
}

internal fun linuxSandboxFormatResult(
    exitCode: Int,
    output: String,
    timedOut: Boolean,
    truncated: Boolean,
): String {
    val notes = buildList {
        if (timedOut) add(tr("命令超时"))
        if (truncated) add(tr("输出已截断"))
    }
    return buildString {
        if (notes.isNotEmpty()) {
            append(notes.joinToString("，"))
            append('\n')
        }
        append(tr("退出码 %s").format(exitCode))
        if (output.isNotEmpty()) {
            append('\n')
            append(output.trimEnd())
        }
    }
}

internal fun linuxSandboxEnsureResolvConf(rootfs: File) {
    val etc = File(rootfs, "etc").apply { mkdirs() }
    val file = File(etc, "resolv.conf")
    val link = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
    if (link) java.nio.file.Files.delete(file.toPath())
    if (!link && file.isFile && file.length() > 0L) return
    file.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
}

/** 不跟随符号链接，避免根文件系统里指向共享存储的链接在重新准备时被清空。 */
internal fun linuxSandboxDeleteTree(file: File) {
    val path = file.toPath()
    if (java.nio.file.Files.isSymbolicLink(path)) {
        java.nio.file.Files.deleteIfExists(path)
        return
    }
    if (java.nio.file.Files.isDirectory(path)) {
        java.nio.file.Files.list(path).use { children ->
            children.forEach { linuxSandboxDeleteTree(it.toFile()) }
        }
    }
    java.nio.file.Files.deleteIfExists(path)
}
