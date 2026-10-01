package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class LinuxSandboxTest {
    @Test
    fun sandboxIsHiddenUnlessEnabledAndAllFilesAreGranted() {
        assertFalse(LinuxSandboxPolicy.exposed(enabled = false, allFiles = false))
        assertFalse(LinuxSandboxPolicy.exposed(enabled = false, allFiles = true))
        assertFalse(LinuxSandboxPolicy.exposed(enabled = true, allFiles = false))
        assertTrue(LinuxSandboxPolicy.exposed(enabled = true, allFiles = true))
    }

    @Test
    fun disablingKeepsTheRootfsAndDefaultsToOff() {
        val root = Files.createTempDirectory("irio-sandbox").toFile()
        val layout = LinuxSandboxLayout.of(root)
        layout.rootfs.mkdirs()
        File(layout.rootfs, "home.txt").writeText("keep")
        assertFalse(LinuxSandboxStore.loadEnabled(layout.stateFile))
        LinuxSandboxStore.saveEnabled(layout.stateFile, true)
        assertTrue(LinuxSandboxStore.loadEnabled(layout.stateFile))
        LinuxSandboxStore.saveEnabled(layout.stateFile, false)
        assertFalse(LinuxSandboxStore.loadEnabled(layout.stateFile))
        assertEquals("keep", File(layout.rootfs, "home.txt").readText())
        assertTrue(layout.rootfs.isDirectory)
    }

    @Test
    fun extractOnlyHappensForAMissingRootfsOrAnExplicitRebuild() {
        assertTrue(linuxSandboxShouldExtract(installedVersion = null, rootfsPresent = false, rebuild = false))
        assertFalse(linuxSandboxShouldExtract(installedVersion = "3.24.2", rootfsPresent = true, rebuild = false))
        assertTrue(linuxSandboxShouldExtract(installedVersion = "3.24.2", rootfsPresent = true, rebuild = true))
        assertTrue(linuxSandboxShouldExtract(installedVersion = "3.24.2", rootfsPresent = false, rebuild = false))
    }

    @Test
    fun rebuildIsOfferedOnlyWhenAnInstallExistsAndDiffersOrIsBroken() {
        assertFalse(linuxSandboxOfferRebuild(null, "3.24.2", shellPresent = false))
        assertFalse(linuxSandboxOfferRebuild("3.24.2", "3.24.2", shellPresent = true))
        assertTrue(linuxSandboxOfferRebuild("3.24.1", "3.24.2", shellPresent = true))
        assertTrue(linuxSandboxOfferRebuild("3.24.2", "3.24.2", shellPresent = false))
    }

    @Test
    fun statusHidesAReadyEnvironmentWithoutDeletingIt() {
        assertEquals("尚未准备。打开开关后会从安装包解压，不需要下载。", status(enabled = false))
        assertEquals("正在准备环境", status(preparing = true))
        assertEquals(
            "还没有管理所有文件的权限，模型暂时看不到这个环境。数据会保留。",
            status(enabled = true, allFiles = false, installedVersion = "3.24.2", shellPresent = true),
        )
        assertEquals(
            "已就绪 · Alpine 3.24.2",
            status(enabled = true, allFiles = true, installedVersion = "3.24.2", shellPresent = true),
        )
        assertEquals(
            "环境已保留在本机，当前没有提供给模型。",
            status(enabled = false, installedVersion = "3.24.2", shellPresent = true),
        )
        assertEquals(
            "已安装 Alpine 3.24.1，安装包附带 3.24.2。模型仍使用已安装的环境。",
            status(
                enabled = true,
                allFiles = true,
                installedVersion = "3.24.1",
                bundledVersion = "3.24.2",
                shellPresent = true,
            ),
        )
        assertEquals("环境不完整，需要重新准备。", status(installedVersion = "3.24.2", shellPresent = false))
        assertEquals("准备失败：校验", status(error = "校验"))
        assertEquals(
            "安装包里没有这份根文件系统",
            linuxSandboxFailureMessage(java.io.FileNotFoundException("linux-sandbox/alpine-minirootfs-aarch64.tar.gz")),
        )
        assertEquals("无法完成环境目录", linuxSandboxFailureMessage(java.io.IOException("无法完成环境目录")))
    }

    @Test
    fun commandLineStaysInsideTheGuest() {
        val args = linuxSandboxProotArguments(
            proot = "/native/libproot.so",
            rootfs = "/data/rootfs",
            storage = "/storage/emulated/0",
            hostTmp = "/cache/tmp",
            command = "echo hi",
        )
        assertEquals("/native/libproot.so", args.first())
        assertEquals(listOf("/bin/sh", "-c", "echo hi"), args.takeLast(3))
        assertTrue(args.contains("--rootfs=/data/rootfs"))
        assertTrue(args.contains("--bind=/dev"))
        assertTrue(args.contains("--bind=/cache/tmp:/tmp"))
        assertTrue(args.contains("--cwd=/root"))
        assertTrue(args.contains("-0"))
        assertTrue(args.contains("--bind=/storage/emulated/0:/sdcard"))
        assertFalse(args.contains("--rootfs"))
        assertFalse(args.contains("--bind"))
        assertFalse(args.contains("/data/rootfs"))
        val env = linuxSandboxProcessEnvironment("/native", "/native/loader", null, "/cache/tmp")
        assertEquals("/native/loader", env["PROOT_LOADER"])
        assertFalse(env.containsKey("PROOT_LOADER_32"))
        assertNull(linuxSandboxNormalizeCommand("   "))
        assertNull(linuxSandboxNormalizeCommand("x".repeat(8_001)))
        assertEquals("echo hi", linuxSandboxNormalizeCommand(" echo hi "))
    }

    @Test
    fun commandResultKeepsTheExitCodeAndTruncation() {
        assertEquals("退出码 0\nhi", linuxSandboxFormatResult(0, "hi\n", timedOut = false, truncated = false))
        assertEquals(
            "命令超时，输出已截断\n退出码 -1",
            linuxSandboxFormatResult(-1, "", timedOut = true, truncated = true),
        )
        assertEquals("$ echo hi", linuxSandboxTerminalTranscript(" echo hi ", ""))
        assertEquals(
            "$ echo hi\nhi\n退出码 0",
            linuxSandboxTerminalTranscript("echo hi", "hi\n", exitCode = 0),
        )
        assertEquals(
            "$ true\n命令超时，输出已截断\n退出码 -1",
            linuxSandboxTerminalTranscript("true", "", exitCode = -1, timedOut = true, truncated = true),
        )
    }

    @Test
    fun resolvConfIsAddedOnceAndNotOverwritten() {
        val rootfs = Files.createTempDirectory("irio-rootfs").toFile()
        linuxSandboxEnsureResolvConf(rootfs)
        val resolv = File(rootfs, "etc/resolv.conf")
        assertTrue(resolv.readText().contains("nameserver 1.1.1.1"))
        resolv.writeText("nameserver 9.9.9.9\n")
        linuxSandboxEnsureResolvConf(rootfs)
        assertEquals("nameserver 9.9.9.9\n", resolv.readText())
    }

    @Test
    fun commitSwapsInTheNewRootfsAndDropsThePreviousOne() {
        val root = Files.createTempDirectory("irio-commit").toFile()
        val layout = LinuxSandboxLayout.of(root)
        layout.rootfs.mkdirs()
        File(layout.rootfs, "keep.txt").writeText("old")
        layout.versionFile.writeText("3.24.1")
        layout.partial.mkdirs()
        File(layout.partial, "new.txt").writeText("new")
        linuxSandboxCommitExtractedRootfs(layout, "3.24.2")
        assertEquals("new", File(layout.rootfs, "new.txt").readText())
        assertFalse(File(layout.rootfs, "keep.txt").exists())
        assertFalse(File(layout.root, "rootfs.previous").exists())
        assertFalse(layout.partial.exists())
        assertEquals("3.24.2", layout.installedVersion())
    }

    @Test
    fun deletingATreeRemovesChildrenButNotASibling() {
        val root = Files.createTempDirectory("irio-delete").toFile()
        val tree = File(root, "rootfs")
        File(tree, "a").apply { mkdirs() }
        File(tree, "a/b.txt").writeText("x")
        val sibling = File(root, "state.json").apply { writeText("{\"enabled\":true}") }
        linuxSandboxDeleteTree(tree)
        assertFalse(tree.exists())
        assertEquals("{\"enabled\":true}", sibling.readText())
    }

    @Test
    fun archiveRoundTripWritesFilesAndRejectsEscape() {
        val dest = Files.createTempDirectory("irio-tar").toFile()
        linuxSandboxExtractArchive(gzip(tar(file("hello.txt", "hi"), directory("dir"))), dest)
        assertEquals("hi", File(dest, "hello.txt").readText())
        assertTrue(File(dest, "dir").isDirectory)
        val escaped = Files.createTempDirectory("irio-escape").toFile()
        val crashed = runCatching {
            linuxSandboxExtractArchive(gzip(tar(file("../evil.txt", "no"))), escaped)
        }
        assertTrue(crashed.isFailure)
        assertFalse(File(escaped.parentFile, "evil.txt").exists())
    }

    @Test
    fun paxHeaderSuppliesTheRealPath() {
        val record = paxRecord("path", "nested/hello.txt")
        val archive = tar(
            entry(name = "pax", size = record.toByteArray().size, type = 'x', content = record.toByteArray()),
            file("a", "hi"),
        )
        val dest = Files.createTempDirectory("irio-pax").toFile()
        linuxSandboxExtractArchive(gzip(archive), dest)
        assertEquals("hi", File(dest, "nested/hello.txt").readText())
        assertEquals("nested/hello.txt", linuxSandboxPaxRecords(record.toByteArray())["path"])
    }

    @Test
    fun bundledRootfsMatchesTheCatalog() {
        val catalog = parseLinuxSandboxCatalog(asset("catalog.json").readText())
        assertEquals("3.24.2", catalog.alpineVersion)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), catalog.archives.keys)
        catalog.archives.values.forEach { pin ->
            val bytes = asset(File(pin.asset).name).readBytes()
            assertEquals(pin.sha256, linuxSandboxSha256(bytes))
            val entries = linuxSandboxListArchive(bytes)
            assertTrue(entries.any { it.path.removePrefix("./") == "etc/alpine-release" })
            assertTrue(entries.any { it.path.removePrefix("./") == "bin/busybox" && it.type == '0' })
            assertTrue(entries.any { it.path.removePrefix("./") == "bin/sh" && it.type == '2' })
        }
    }

    @Test
    fun shellToolSchemaIsAdvertisedForEveryProtocol() {
        ProviderProtocol.entries.forEach { protocol ->
            val schema = LinuxShellTool.schemaFor(protocol)
            val name = when (protocol) {
                ProviderProtocol.CHAT_COMPLETIONS -> schema.getJSONObject("function").getString("name")
                else -> schema.getString("name")
            }
            assertEquals(LinuxShellTool.NAME, name)
        }
        val registry = ToolRegistry(listOf(LinuxShellToolProvider()))
        assertTrue(registry.handles("linux_shell"))
        assertEquals("运行命令中", MemoryTool.liveLabel(LinuxShellTool.NAME))
        assertEquals("已运行命令", MemoryTool.doneLabel(LinuxShellTool.NAME))
        assertEquals("Linux sandbox", translate(AppLanguage.EN, "Linux 沙盒"))
        assertEquals("Running a command", translate(AppLanguage.EN, "运行命令中"))
        assertEquals("Commands per call", translate(AppLanguage.EN, "单次命令数"))
        assertEquals("Unlimited", translate(AppLanguage.EN, "无限"))
    }

    @Test
    fun commandsPerCallDefaultsToUnlimitedAndDoesNotClearTheSwitch() {
        val root = Files.createTempDirectory("irio-sandbox-cap").toFile()
        val layout = LinuxSandboxLayout.of(root)
        assertEquals(LinuxSandboxStore.COMMANDS_UNLIMITED, LinuxSandboxStore.loadCommandsPerCall(layout.stateFile))
        LinuxSandboxStore.saveEnabled(layout.stateFile, true)
        LinuxSandboxStore.saveCommandsPerCall(layout.stateFile, 12)
        assertEquals(12, LinuxSandboxStore.loadCommandsPerCall(layout.stateFile))
        assertTrue(LinuxSandboxStore.loadEnabled(layout.stateFile))
        LinuxSandboxStore.saveCommandsPerCall(layout.stateFile, 500)
        assertEquals(LinuxSandboxStore.COMMANDS_MAX, LinuxSandboxStore.loadCommandsPerCall(layout.stateFile))
        LinuxSandboxStore.saveEnabled(layout.stateFile, false)
        assertEquals(LinuxSandboxStore.COMMANDS_MAX, LinuxSandboxStore.loadCommandsPerCall(layout.stateFile))
        assertFalse(LinuxSandboxStore.loadEnabled(layout.stateFile))
    }

    @Test
    fun oneToolCallRunsEveryCommandUntilTheSliderLimit() {
        val all = linuxSandboxSelectCommands("""{"commands":[" echo a ","","echo b","echo c"]}""", 0)
        assertEquals(listOf(" echo a ", "echo b", "echo c"), all.commands)
        assertEquals(0, all.skipped)
        val capped = linuxSandboxSelectCommands("""{"commands":["echo a","echo b","echo c"]}""", 2)
        assertEquals(listOf("echo a", "echo b"), capped.commands)
        assertEquals(1, capped.skipped)
        val legacy = linuxSandboxSelectCommands("""{"command":"echo hi"}""", 1)
        assertEquals(listOf("echo hi"), legacy.commands)
        assertEquals(0, legacy.skipped)
        assertEquals(0, linuxSandboxClampCommandsPerCall(-4))
        assertEquals(100, linuxSandboxClampCommandsPerCall(101))
        ProviderProtocol.entries.forEach { protocol ->
            assertFalse(commandItems(protocol, 4).has("minItems"))
            assertFalse(commandItems(protocol, 4).has("maxItems"))
        }
        assertFalse(LinuxShellTool.descriptionFor(0).contains("At most"))
        assertTrue(LinuxShellTool.descriptionFor(4).contains("At most 4"))
        assertEquals(
            "The per-call command limit was reached. 3 more were not run.",
            translate(AppLanguage.EN, "已达到单次命令上限，其余 %s 条未运行。").format(3),
        )
        assertEquals(
            listOf("echo a", "echo b"),
            linuxSandboxSelectCommands("""{"command":["echo a","echo b"]}""", 0).commands,
        )
    }

    @Test
    fun catalogKeepsThePinnedChecksum() {
        val catalog = parseLinuxSandboxCatalog(
            """
            {"alpineVersion":"3.24.2","archives":[{"abi":"arm64-v8a","asset":"linux-sandbox/root.tar.gz","sha256":"abc"}]}
            """.trimIndent(),
        )
        assertEquals("abc", catalog.archives.getValue("arm64-v8a").sha256)
        assertFalse(linuxSandboxSha256("no".toByteArray()).equals("abc", ignoreCase = true))
    }

    private fun commandItems(protocol: ProviderProtocol, limit: Int): org.json.JSONObject {
        val schema = LinuxShellTool.schemaFor(protocol, limit)
        val parameters = when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> schema.getJSONObject("function").getJSONObject("parameters")
            ProviderProtocol.ANTHROPIC -> schema.getJSONObject("input_schema")
            else -> schema.getJSONObject("parameters")
        }
        return parameters.getJSONObject("properties").getJSONObject("commands")
    }

    private fun status(
        enabled: Boolean = false,
        allFiles: Boolean = false,
        preparing: Boolean = false,
        error: String? = null,
        installedVersion: String? = null,
        bundledVersion: String? = "3.24.2",
        shellPresent: Boolean = false,
    ): String = linuxSandboxStatus(enabled, allFiles, preparing, error, installedVersion, bundledVersion, shellPresent)

    private fun asset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/linux-sandbox", name),
            File("app/src/main/assets/linux-sandbox", name),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("missing $name, looked in ${candidates.joinToString { it.path }}")
    }
}

private fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

private fun tar(vararg entries: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    entries.forEach { out.write(it) }
    out.write(ByteArray(512))
    return out.toByteArray()
}

private fun file(name: String, text: String): ByteArray = entry(name, text.toByteArray().size, '0', text.toByteArray())

private fun directory(name: String): ByteArray = entry(name, 0, '5', ByteArray(0))

private fun entry(name: String, size: Int, type: Char, content: ByteArray): ByteArray {
    val header = ByteArray(512)
    write(header, 0, name)
    writeOctal(header, 100, 8, 420)
    writeOctal(header, 108, 8, 0)
    writeOctal(header, 116, 8, 0)
    writeOctal(header, 124, 12, size.toLong())
    writeOctal(header, 136, 12, 0)
    for (index in 148 until 156) header[index] = ' '.code.toByte()
    header[156] = type.code.toByte()
    write(header, 257, "ustar")
    header[262] = 0
    var sum = 0
    header.forEach { sum += it.toInt() and 0xff }
    val checksum = sum.toString(8).padStart(6, '0')
    for (index in checksum.indices) header[148 + index] = checksum[index].code.toByte()
    header[154] = 0
    header[155] = ' '.code.toByte()
    val padded = ByteArray(size + ((512 - size % 512) % 512))
    content.copyInto(padded)
    return header + padded
}

private fun write(target: ByteArray, offset: Int, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    bytes.copyInto(target, offset)
}

private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
    val text = value.toString(8).padStart(length - 1, '0')
    write(target, offset, text)
    target[offset + length - 1] = 0
}

private fun paxRecord(key: String, value: String): String {
    var length = 1
    while (true) {
        val record = "$length $key=$value\n"
        if (record.length == length) return record
        length = record.toByteArray(Charsets.UTF_8).size
        if (length > 10_000) error("pax record")
    }
}
