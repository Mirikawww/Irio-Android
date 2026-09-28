package com.irio

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupImportResult(
    val success: Boolean,
    val message: String,
    val conversationCount: Int = 0,
    val providerRestored: Boolean = false,
    val searchRestored: Boolean = false,
    val missing: List<String> = emptyList(),
)

object BackupFileValidation {
    fun isIrioFile(name: String): Boolean = name.lowercase().endsWith(".irio")
}

/** Exports application data, but deliberately never includes attachment bytes. */
object BackupStore {
    private const val FORMAT = "irio-backup-v1"

    fun export(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("format"))
            zip.write(FORMAT.toByteArray())
            zip.closeEntry()
            addDirectory(zip, AppPaths.root, "")
            zip.putNextEntry(ZipEntry("provider_snapshot.json"))
            zip.write(ProviderStore.snapshot().toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("search_snapshot.json"))
            zip.write(SearchStore.snapshot().toString().toByteArray())
            zip.closeEntry()
        }
    }

    fun import(input: InputStream, overwrite: Boolean = true): BackupImportResult {
        val stage = File.createTempFile("irio-import-", "").apply { delete(); mkdirs() }
        try {
            var valid = false
            var providerSnapshot: String? = null
            var searchSnapshot: String? = null
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.replace('\\', '/')
                    require(relative.isNotBlank() && !relative.startsWith('/') &&
                        relative.split('/').none { it == ".." }) { tr("无效的 Irio 文件") }
                    if (relative == "format") {
                        valid = zip.readBytes().toString(Charsets.UTF_8) == FORMAT
                    } else if (relative == "provider_snapshot.json") {
                        providerSnapshot = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (relative == "search_snapshot.json") {
                        searchSnapshot = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (!entry.isDirectory && !isAttachment(relative)) {
                        val target = File(stage, relative)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            if (!valid) return BackupImportResult(false, tr("不是有效的 Irio 导出文件"))
            var conversationCount = 0
            stage.walkTopDown().filter { it.isFile }.forEach { staged ->
                val relative = staged.relativeTo(stage)
                val relativePath = relative.path.replace('\\', '/')
                if (isAttachment(relativePath) || relativePath == "provider_snapshot.json" || relativePath == "search_snapshot.json") return@forEach
                val target = File(AppPaths.root, relative.path)
                if (overwrite || !target.exists()) {
                    target.parentFile?.mkdirs()
                    staged.copyTo(target, overwrite = true)
                }
                if (relativePath.startsWith("conversations/") && relativePath.endsWith(".json")) conversationCount++
            }
            val providerRestored = providerSnapshot != null
            val searchRestored = searchSnapshot != null
            providerSnapshot?.let { ProviderStore.applySnapshot(org.json.JSONObject(it), overwrite) }
            searchSnapshot?.let { SearchStore.applySnapshot(org.json.JSONObject(it), overwrite) }
            return BackupImportResult(
                success = true,
                message = tr("恢复完成"),
                conversationCount = conversationCount,
                providerRestored = providerRestored,
                searchRestored = searchRestored,
                missing = buildList {
                    if (!providerRestored) add(tr("提供商数据"))
                    if (!searchRestored) add(tr("搜索提供商数据"))
                },
            )
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun addDirectory(zip: ZipOutputStream, root: File, relativeRoot: String) {
        root.listFiles()?.forEach { file ->
            val relative = if (relativeRoot.isEmpty()) file.name else "$relativeRoot/${file.name}"
            if (isAttachment(relative)) return@forEach
            if (file.isDirectory) addDirectory(zip, file, relative)
            else {
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun isAttachment(path: String): Boolean = path == "attachments" || path.startsWith("attachments/")
}