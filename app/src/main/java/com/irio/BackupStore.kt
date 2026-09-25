package com.irio

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Exports application data, but deliberately never includes attachment bytes. */
object BackupStore {
    private const val FORMAT = "irio-backup-v1"

    fun export(context: Context, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("format"))
            zip.write(FORMAT.toByteArray())
            zip.closeEntry()
            addDirectory(zip, context.dataDir, "")
        }
    }

    fun import(context: Context, input: InputStream) {
        val stage = File(context.cacheDir, "irio-import-${System.nanoTime()}").apply { mkdirs() }
        try {
            var valid = false
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.replace('\\', '/')
                    require(relative.isNotBlank() && !relative.startsWith('/') &&
                        relative.split('/').none { it == ".." }) { "无效的 Irio 文件" }
                    if (relative == "format") {
                        valid = zip.readBytes().toString(Charsets.UTF_8) == FORMAT
                    } else if (!entry.isDirectory && !isAttachment(relative)) {
                        val target = File(stage, relative)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            require(valid) { "不是有效的 Irio 导出文件" }
            stage.walkTopDown().filter { it.isFile }.forEach { staged ->
                val relative = staged.relativeTo(stage)
                if (isAttachment(relative.path.replace('\\', '/'))) return@forEach
                val target = File(context.dataDir, relative.path)
                target.parentFile?.mkdirs()
                staged.copyTo(target, overwrite = true)
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun addDirectory(zip: ZipOutputStream, root: File, relativeRoot: String) {
        root.listFiles()?.forEach { file ->
            val relative = if (relativeRoot.isEmpty()) file.name else "$relativeRoot/${file.name}"
            if (relative == "cache" || relative == "code_cache" || isAttachment(relative)) return@forEach
            if (file.isDirectory) addDirectory(zip, file, relative)
            else {
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun isAttachment(path: String): Boolean = path == "files/attachments" || path.startsWith("files/attachments/")
}