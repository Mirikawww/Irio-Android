package com.irio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable data rather than a copy of Android's credential-bound application directory. */
internal object BackupArchive {
    private const val FORMAT = "irio-backup-v1"
    private val snapshotNames = setOf("provider_snapshot.json", "stt_snapshot.json", "search_snapshot.json",
        "preferences_snapshot.json", "github_mcp_snapshot.json", "todoist_mcp_snapshot.json")

    fun export(context: Context, output: OutputStream,
        github: GitHubMcpStore = GitHubMcpStore.of(context),
        todoist: TodoistMcpStore = TodoistMcpStore.of(context),
    ) {
        val snapshots = linkedMapOf(
            "provider_snapshot.json" to ProviderStore.snapshot(context),
            "stt_snapshot.json" to SttStore.snapshot(context),
            "search_snapshot.json" to SearchStore.snapshot(context),
            "preferences_snapshot.json" to BackupPreferences.snapshot(context),
            "github_mcp_snapshot.json" to github.snapshot(),
            "todoist_mcp_snapshot.json" to todoist.snapshot(),
        )
        ZipOutputStream(output).use { zip ->
            zip.text("format", FORMAT)
            ConversationSummaries.withLock { addDirectory(zip, context.filesDir, "files") }
            snapshots.forEach { (name, json) -> zip.text(name, json.toString()) }
        }
    }

    fun import(context: Context, input: InputStream, overwrite: Boolean,
        github: GitHubMcpStore = GitHubMcpStore.of(context),
        todoist: TodoistMcpStore = TodoistMcpStore.of(context),
    ): BackupImportResult {
        val stage = File(context.cacheDir, "irio-import-${System.nanoTime()}").apply { mkdirs() }
        try {
            var valid = false
            val snapshots = linkedMapOf<String, JSONObject>()
            val seen = mutableSetOf<String>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.replace('\\', '/')
                    require(relative.isNotBlank() && !relative.startsWith('/') && ':' !in relative &&
                        relative.split('/').none { it == ".." || it == "." } && seen.add(relative)) { tr("无效的 Irio 文件") }
                    when {
                        relative == "format" -> valid = zip.readBytes().toString(Charsets.UTF_8) == FORMAT
                        relative in snapshotNames -> snapshots[relative] = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        !entry.isDirectory && (allowedFile(relative) || legacyPreference(relative)) -> {
                            val target = File(stage, relative)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (!valid) return BackupImportResult(false, tr("不是有效的 Irio 导出文件"))
            // Parse before copying; invalid payloads must not partly replace live files.
            val preferences = snapshots["preferences_snapshot.json"]?.let(BackupPreferences::decode)
                ?: BackupPreferences.legacy(File(stage, "shared_prefs"))
            val incomingBodies = File(stage, "files/conversations").listFiles().orEmpty()
                .filter { it.extension == "json" && it.name != "index.json" }.map { file ->
                    val conversation = ConversationStore.parseConversation(JSONObject(file.readText()))
                    require(conversation.id.matches(Regex("[A-Za-z0-9_-]+")) && file.name == "${conversation.id}.json")
                    conversation
                }
            snapshots["github_mcp_snapshot.json"]?.let { GitHubMcpStore.validateSnapshot(it) }
            snapshots["todoist_mcp_snapshot.json"]?.let { TodoistMcpStore.validateSnapshot(it) }
            val files = File(stage, "files")
            if (files.isDirectory) files.walkTopDown().filter { it.isFile }.forEach { staged ->
                safeTarget(context.dataDir, "files/" + staged.relativeTo(files).path.replace('\\', '/'))
            }
            if (files.isDirectory) files.walkTopDown().filter { it.isFile }.forEach { staged ->
                val relative = "files/" + staged.relativeTo(files).path.replace('\\', '/')
                if (!allowedFile(relative) || relative.startsWith("files/conversations/")) return@forEach
                val target = safeTarget(context.dataDir, relative)
                if (relative == "files/linux-sandbox/state.json" && !overwrite && target.isFile) {
                    val local = JSONObject(target.readText()); val incoming = JSONObject(staged.readText())
                    incoming.keys().forEach { key -> if (!local.has(key)) local.put(key, incoming.get(key)) }
                    target.writeText(local.toString())
                } else if (overwrite || !target.exists()) {
                    target.parentFile?.mkdirs()
                    staged.copyTo(target, overwrite = true)
                }
            }
            if (overwrite && snapshots.containsKey("preferences_snapshot.json") &&
                !File(stage, "files/profile/avatar.png").exists()) {
                File(context.filesDir, "profile/avatar.png").delete()
            }
            BackupPreferences.apply(context, preferences, overwrite)
            snapshots["provider_snapshot.json"]?.let { ProviderStore.applySnapshot(context, it, overwrite) }
            snapshots["stt_snapshot.json"]?.let { SttStore.applySnapshot(context, it, overwrite) }
            snapshots["search_snapshot.json"]?.let { SearchStore.applySnapshot(context, it, overwrite) }
            snapshots["github_mcp_snapshot.json"]?.let { github.applySnapshot(it, overwrite) }
            snapshots["todoist_mcp_snapshot.json"]?.let { todoist.applySnapshot(it, overwrite) }
            // Rebuild the index through the store; a copied old index hides merged bodies.
            if (incomingBodies.isNotEmpty()) {
                ConversationStore.importBackup(context, incomingBodies, overwrite)
            }
            val lastId = File(stage, "files/conversations/last_id.txt").takeIf { it.isFile }?.readText()?.trim()
            if (!lastId.isNullOrBlank() && lastId.matches(Regex("[A-Za-z0-9_-]+")) &&
                (overwrite || ConversationStore.loadLastOpenedId(context) == null) && ConversationStore.load(context, lastId) != null) {
                ConversationStore.saveLastOpenedId(context, lastId)
            }
            ThemeStore.reload(context)
            ProfileStore.reload(context)
            LinkRuntime.reloadAfterImport(context)
            return BackupImportResult(true, tr("恢复完成"), incomingBodies.size,
                snapshots.containsKey("provider_snapshot.json"), snapshots.containsKey("stt_snapshot.json"),
                snapshots.containsKey("search_snapshot.json"), buildList {
                    if (!snapshots.containsKey("provider_snapshot.json")) add(tr("提供商数据"))
                    if (!snapshots.containsKey("stt_snapshot.json")) add(tr("STT 提供商数据"))
                    if (!snapshots.containsKey("search_snapshot.json")) add(tr("搜索提供商数据"))
                })
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun safeTarget(root: File, relative: String): File {
        val target = File(root, relative)
        require(target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { tr("无效的 Irio 文件") }
        return target
    }

    private fun ZipOutputStream.text(name: String, value: String) {
        putNextEntry(ZipEntry(name)); write(value.toByteArray(Charsets.UTF_8)); closeEntry()
    }

    private fun addDirectory(zip: ZipOutputStream, root: File, relativeRoot: String) {
        root.listFiles()?.forEach { file ->
            val relative = "$relativeRoot/${file.name}"
            if (Files.isSymbolicLink(file.toPath()) || !allowedFile(relative)) return@forEach
            if (file.isDirectory) addDirectory(zip, file, relative)
            else {
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun legacyPreference(path: String): Boolean = path.startsWith("shared_prefs/") &&
        path.removePrefix("shared_prefs/").let { it.endsWith(".xml") && '/' !in it && it.removeSuffix(".xml") in BackupPreferences.names }

    private fun allowedFile(path: String): Boolean = path.startsWith("files/") &&
        path != "files/attachments" && !path.startsWith("files/attachments/") &&
        (!path.startsWith("files/linux-sandbox/") || path == "files/linux-sandbox/state.json") &&
        !path.endsWith(".tmp") && !path.endsWith(".part") && !path.contains("/.avatar-")
}
