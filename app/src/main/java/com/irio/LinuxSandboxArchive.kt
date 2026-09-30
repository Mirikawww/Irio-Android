package com.irio

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal data class LinuxSandboxTarEntry(
    val path: String,
    val type: Char,
)

internal fun linuxSandboxSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun linuxSandboxListArchive(gzipped: ByteArray): List<LinuxSandboxTarEntry> {
    val entries = mutableListOf<LinuxSandboxTarEntry>()
    linuxSandboxReadArchive(gzipped, dest = null) { path, type ->
        entries += LinuxSandboxTarEntry(path, type)
    }
    return entries
}

internal fun linuxSandboxExtractArchive(gzipped: ByteArray, dest: File) {
    dest.mkdirs()
    linuxSandboxReadArchive(gzipped, dest) { _, _ -> }
}

private fun linuxSandboxReadArchive(
    gzipped: ByteArray,
    dest: File?,
    onEntry: (path: String, type: Char) -> Unit,
) {
    GZIPInputStream(ByteArrayInputStream(gzipped)).use { raw ->
        var pendingPath: String? = null
        var pendingLink: String? = null
        var pendingSize: Long? = null
        while (true) {
            val header = readFully(raw, 512) ?: return
            if (header.all { it == 0.toByte() }) return
            if (!tarChecksumOk(header)) throw IOException(tr("根文件系统已损坏"))
            val typeByte = header[156]
            val type = if (typeByte == 0.toByte()) '0' else typeByte.toInt().toChar()
            val headerSize = header.octal(124, 12)
            val headerName = ustarName(header)
            val headerLink = header.cstr(157, 100)
            when (type) {
                'x' -> {
                    val pax = parsePax(readExactly(raw, headerSize))
                    skipPadding(raw, headerSize)
                    pax["path"]?.let { pendingPath = it }
                    pax["linkpath"]?.let { pendingLink = it }
                    pax["size"]?.toLongOrNull()?.let { pendingSize = it }
                }
                'g' -> {
                    readExactly(raw, headerSize)
                    skipPadding(raw, headerSize)
                }
                'L' -> {
                    pendingPath = readExactly(raw, headerSize).toString(Charsets.UTF_8).trim('\u0000', '\n')
                    skipPadding(raw, headerSize)
                }
                'K' -> {
                    pendingLink = readExactly(raw, headerSize).toString(Charsets.UTF_8).trim('\u0000', '\n')
                    skipPadding(raw, headerSize)
                }
                else -> {
                    val path = pendingPath ?: headerName
                    val link = pendingLink ?: headerLink
                    val size = pendingSize ?: headerSize
                    pendingPath = null
                    pendingLink = null
                    pendingSize = null
                    onEntry(path, type)
                    when (type) {
                        '0' -> writeFile(dest, path, readExactly(raw, size), header.octal(100, 8).toInt())
                        '1' -> writeHardLink(dest, path, link)
                        '2' -> writeSymlink(dest, path, link)
                        '5' -> writeDirectory(dest, path, header.octal(100, 8).toInt())
                        '3', '4', '6' -> Unit
                        else -> throw IOException(tr("根文件系统包含无法解压的条目"))
                    }
                    if (type != '0') skipContent(raw, size) else skipPadding(raw, size)
                }
            }
        }
    }
}

private fun writeFile(dest: File?, path: String, bytes: ByteArray, mode: Int) {
    val file = resolveEntry(dest, path) ?: return
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
    applyMode(file.toPath(), mode)
}

private fun writeDirectory(dest: File?, path: String, mode: Int) {
    val file = resolveEntry(dest, path) ?: return
    file.mkdirs()
    applyMode(file.toPath(), mode)
}

private fun writeSymlink(dest: File?, path: String, link: String) {
    val file = resolveEntry(dest, path) ?: return
    file.parentFile?.mkdirs()
    if (file.exists() || Files.isSymbolicLink(file.toPath())) Files.delete(file.toPath())
    Files.createSymbolicLink(file.toPath(), Path.of(link))
}

private fun writeHardLink(dest: File?, path: String, link: String) {
    val file = resolveEntry(dest, path) ?: return
    val target = resolveEntry(dest, link) ?: return
    file.parentFile?.mkdirs()
    if (file.exists()) file.delete()
    val linked = runCatching { Files.createLink(file.toPath(), target.toPath()) }.isSuccess
    if (!linked && target.isFile) target.copyTo(file, overwrite = true)
}

private fun resolveEntry(dest: File?, path: String): File? {
    if (dest == null) return null
    if (linuxSandboxEscapes(path)) throw IOException(tr("拒绝逃逸的路径"))
    val safe = linuxSandboxSafePath(path) ?: return null
    val file = File(dest, safe)
    val root = dest.canonicalFile
    val target = file.canonicalFile
    val rootPath = root.path
    val targetPath = target.path
    if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
        throw IOException(tr("拒绝逃逸的路径"))
    }
    return file
}

internal fun linuxSandboxEscapes(path: String): Boolean {
    val normalized = path.replace('\\', '/').removePrefix("./")
    if (normalized.startsWith("/")) return true
    return normalized.split('/').any { it == ".." }
}

internal fun linuxSandboxSafePath(path: String): String? {
    if (linuxSandboxEscapes(path)) return null
    val parts = path.replace('\\', '/').trim().removePrefix("./")
        .split('/')
        .filter { it.isNotEmpty() && it != "." }
    if (parts.isEmpty()) return null
    return parts.joinToString("/")
}

internal fun linuxSandboxPaxRecords(body: ByteArray): Map<String, String> = parsePax(body)

private fun parsePax(body: ByteArray): Map<String, String> {
    val values = linkedMapOf<String, String>()
    var index = 0
    while (index < body.size) {
        if (body[index] == 0.toByte()) break
        var space = -1
        var cursor = index
        while (cursor < body.size) {
            if (body[cursor] == ' '.code.toByte()) {
                space = cursor
                break
            }
            cursor++
        }
        if (space < 0) break
        val length = body.copyOfRange(index, space).toString(Charsets.US_ASCII).trim().toIntOrNull() ?: break
        if (length <= 0 || index + length > body.size) break
        val record = body.copyOfRange(index, index + length).toString(Charsets.UTF_8)
        val separator = record.indexOf('=')
        if (separator > 0) {
            val keyStart = record.indexOf(' ') + 1
            values[record.substring(keyStart, separator)] = record.substring(separator + 1).removeSuffix("\n")
        }
        index += length
    }
    return values
}

private fun ustarName(header: ByteArray): String {
    val name = header.cstr(0, 100)
    val magic = header.cstr(257, 5)
    val prefix = if (magic == "ustar") header.cstr(345, 155) else ""
    return if (prefix.isEmpty()) name else "$prefix/$name"
}

private fun skipContent(raw: InputStream, size: Long) {
    if (size > 0) readExactly(raw, size)
    skipPadding(raw, size)
}

private fun skipPadding(raw: InputStream, size: Long) {
    val pad = ((512 - (size % 512)) % 512).toInt()
    if (pad > 0) readFully(raw, pad)
}

private fun readExactly(raw: InputStream, size: Long): ByteArray {
    if (size < 0 || size > Int.MAX_VALUE) throw IOException(tr("根文件系统已损坏"))
    if (size == 0L) return ByteArray(0)
    return readFully(raw, size.toInt()) ?: throw IOException(tr("根文件系统已损坏"))
}

private fun readFully(raw: InputStream, size: Int): ByteArray? {
    val buffer = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = raw.read(buffer, offset, size - offset)
        if (read < 0) return if (offset == 0) null else throw IOException(tr("根文件系统已损坏"))
        offset += read
    }
    return buffer
}

private fun tarChecksumOk(header: ByteArray): Boolean {
    var unsigned = 0
    var signed = 0
    for (index in header.indices) {
        val byte = header[index].toInt()
        val value = if (index in 148 until 156) ' '.code else byte
        unsigned += if (index in 148 until 156) ' '.code else byte and 0xff
        signed += value
    }
    val recorded = header.octal(148, 8)
    return recorded == unsigned.toLong() || recorded == signed.toLong()
}

private fun ByteArray.octal(offset: Int, length: Int): Long {
    var value = 0L
    for (index in offset until offset + length) {
        val char = this[index].toInt() and 0xff
        if (char == 0 || char == ' '.code) continue
        if (char !in '0'.code..'7'.code) break
        value = (value shl 3) + (char - '0'.code)
    }
    return value
}

private fun ByteArray.cstr(offset: Int, length: Int): String {
    var end = offset
    while (end < offset + length && this[end] != 0.toByte()) end++
    return copyOfRange(offset, end).toString(Charsets.UTF_8)
}

private fun applyMode(path: Path, mode: Int) {
    if (mode == 0) return
    runCatching {
        val permissions = mutableSetOf<PosixFilePermission>()
        if (mode and 256 != 0) permissions += PosixFilePermission.OWNER_READ
        if (mode and 128 != 0) permissions += PosixFilePermission.OWNER_WRITE
        if (mode and 64 != 0) permissions += PosixFilePermission.OWNER_EXECUTE
        if (mode and 32 != 0) permissions += PosixFilePermission.GROUP_READ
        if (mode and 16 != 0) permissions += PosixFilePermission.GROUP_WRITE
        if (mode and 8 != 0) permissions += PosixFilePermission.GROUP_EXECUTE
        if (mode and 4 != 0) permissions += PosixFilePermission.OTHERS_READ
        if (mode and 2 != 0) permissions += PosixFilePermission.OTHERS_WRITE
        if (mode and 1 != 0) permissions += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(path, permissions)
    }
}
