package com.irio

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LinkDataScopeTest {
    private lateinit var originalPreferences: JSONObject
    @Before fun preservePreferences() {
        originalPreferences = BackupPreferences.snapshot(RuntimeEnvironment.getApplication())
    }
    @After fun restorePreferences() {
        val context = RuntimeEnvironment.getApplication()
        BackupPreferences.apply(context, BackupPreferences.decode(originalPreferences), true)
        ThemeStore.reload(context)
        ProfileStore.reload(context)
    }
    private fun isolated(): Context {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, "link-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir() = root
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(root.name + "-" + name, mode)
        }
    }
    private fun snapshot(context: Context): JSONObject = LinkRuntime::class.java.getDeclaredMethod(
        "snapshotMessage", Context::class.java, String::class.java, LinkedPeer::class.java, Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }.invoke(LinkRuntime, context, "sync-data", null, false) as JSONObject
    private fun apply(context: Context, message: JSONObject, overwrite: Boolean = false) {
        LinkRuntime::class.java.getDeclaredMethod("applySnapshot", Context::class.java, JSONObject::class.java)
            .apply { isAccessible = true }.invoke(LinkRuntime, context, JSONObject(message.toString()).put("overwrite", overwrite))
    }
    private fun file(context: Context, path: String, content: String): File = File(context.filesDir, path).apply {
        parentFile?.mkdirs(); writeText(content)
    }

    @Test fun actualRuntimeSenderIncludesExplicitPortableMcpInstallations() {
        val message = snapshot(isolated())
        val portable = message.getJSONObject("portableData")
        assertTrue("GitHub installation state must be part of Link, even when absent", portable.has("githubMcp"))
        assertTrue("Todoist installation state must be part of Link, even when absent", portable.has("todoistMcp"))
        assertFalse(portable.getJSONObject("githubMcp").getBoolean("installed"))
        assertFalse(portable.getJSONObject("todoistMcp").getBoolean("installed"))
    }

    private fun overLoopback(message: JSONObject): JSONObject {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 5000
                val incoming = executor.submit<JSONObject> {
                    server.accept().use { socket -> socket.soTimeout = 5000; LinkProtocol.readJson(socket) }
                }
                LinkProtocol.connect(server.inetAddress.hostAddress!!, server.localPort).use { LinkProtocol.writeJson(it, message) }
                return incoming.get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun portableMcpInstallationsMoveAcrossDeviceKeysWithoutRefreshOrRawCiphertext() {
        val source = isolated(); val target = isolated()
        val first = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 11 }, "AES")
        val second = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 29 }, "AES")
        val tools = listOf(McpTool("fixture_tool", "Fixture", JSONObject("{\"type\":\"object\"}")))
        val githubSource = GitHubMcpStore(File(source.noBackupFilesDir, "mcp/github.json"), { first },
            refreshAuth = { error("Transfer must not refresh credentials") })
        val todoistSource = TodoistMcpStore(File(source.noBackupFilesDir, "mcp/todoist.json"), { first }) { _, _ -> error("Transfer must not refresh credentials") }
        val originalGithub = GitHubMcpInstallation(GitHubCredential("github-fixture", 1, "github-refresh", 456), "OAuth", tools, false,
            GitHubMcpAccount("fixture", "", "fixture@example.test"))
        val originalTodoist = TodoistMcpInstallation("fixture-client", TodoistCredential("todoist-fixture", 1, "todoist-refresh"), tools, false,
            TodoistMcpAccount("fixture", "", "fixture@example.test"))
        githubSource.save(originalGithub); todoistSource.save(originalTodoist)
        val payload = overLoopback(LinkPortableData.snapshot(source, githubSource, todoistSource))
        val githubFile = File(target.noBackupFilesDir, "mcp/github.json")
        val todoistFile = File(target.noBackupFilesDir, "mcp/todoist.json")
        val githubTarget = GitHubMcpStore(githubFile, { second })
        val todoistTarget = TodoistMcpStore(todoistFile, { second })
        LinkPortableData.prepare(target, payload).apply(target, false, githubTarget, todoistTarget)
        val restoredGithub = GitHubMcpStore(githubFile, { second }).load()!!
        val restoredTodoist = TodoistMcpStore(todoistFile, { second }).load()!!
        assertEquals(originalGithub.credential, restoredGithub.credential)
        assertEquals(originalGithub.account, restoredGithub.account)
        assertEquals(originalGithub.auth, restoredGithub.auth)
        assertFalse(restoredGithub.enabled)
        assertEquals(originalTodoist.credential, restoredTodoist.credential)
        assertEquals(originalTodoist.account, restoredTodoist.account)
        assertEquals(originalTodoist.clientId, restoredTodoist.clientId)
        assertFalse(restoredTodoist.enabled)
        assertEquals(tools.single().inputSchema.toString(), restoredGithub.tools.single().inputSchema.toString())
        assertEquals(tools.single().inputSchema.toString(), restoredTodoist.tools.single().inputSchema.toString())
        assertFalse(githubFile.readText().contains("github-fixture"))
        assertFalse(todoistFile.readText().contains("todoist-fixture"))
        assertNull(GitHubMcpStore(githubFile, { first }).load())
        assertNull(TodoistMcpStore(todoistFile, { first }).load())
        val githubBefore = githubFile.readBytes(); val todoistBefore = todoistFile.readBytes()
        val missing = LinkPortableData.snapshot(isolated())
        LinkPortableData.prepare(target, missing).apply(target, false, githubTarget, todoistTarget)
        assertArrayEquals(githubBefore, githubFile.readBytes())
        assertArrayEquals(todoistBefore, todoistFile.readBytes())
        val earlierExtension = JSONObject(missing.toString()).apply { remove("githubMcp"); remove("todoistMcp") }
        LinkPortableData.prepare(target, earlierExtension).apply(target, true, githubTarget, todoistTarget)
        assertArrayEquals("Missing fields must not act like installed=false", githubBefore, githubFile.readBytes())
        assertArrayEquals(todoistBefore, todoistFile.readBytes())
        LinkPortableData.prepare(target, missing).apply(target, true, githubTarget, todoistTarget)
        assertFalse(githubFile.exists()); assertFalse(todoistFile.exists())
    }

    @Test fun legacyMessagesDoNotErasePortableDataOrMcpInstallationsEvenInOverwriteMode() {
        val source = isolated(); val target = isolated()
        MemoryStore.saveContent(target, "keep memory")
        file(target, "profile/avatar.png", "keep avatar")
        val github = File(target.noBackupFilesDir, "mcp/github.json").apply { parentFile!!.mkdirs(); writeText("opaque local ciphertext") }
        val legacy = snapshot(source).apply { remove("portableData") }
        apply(target, legacy, true)
        assertEquals("keep memory", MemoryStore.loadContent(target))
        assertEquals("keep avatar", File(target.filesDir, "profile/avatar.png").readText())
        assertEquals("opaque local ciphertext", github.readText())
    }

    @Test fun malformedPortableDataIsRejectedBeforeLegacyConversationsOrSettingsCanChange() {
        val source = isolated(); val target = isolated()
        val conversation = ConversationStore.newConversation().copy(title = "keep conversation")
        ConversationStore.save(target, conversation)
        MemoryStore.saveContent(target, "keep memory")
        val good = snapshot(source)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 2) },
            { it.getJSONObject("preferences").put("tool_settings", "not an object") },
            { it.getJSONObject("githubMcp").put("installed", "true") },
            { it.getJSONObject("todoistMcp").put("installed", "true") },
            { it.getJSONObject("files").put("memory.json", "not base64!") },
            { it.getJSONObject("files").put("../outside", "eA==") },
            { it.getJSONObject("files").put("/outside", "eA==") },
            { it.getJSONObject("files").put("C:/outside", "eA==") },
            { it.getJSONObject("files").put("attachments/private.txt", "eA==") },
            { it.getJSONObject("files").put("linux-sandbox/rootfs/private", "eA==") },
        )
        for (mutate in mutations) {
            val message = JSONObject(good.toString())
            mutate(message.getJSONObject("portableData"))
            assertTrue(runCatching { apply(target, message, true) }.isFailure)
            assertEquals(conversation.id, ConversationStore.loadAll(target).single().id)
            assertEquals("keep memory", MemoryStore.loadContent(target))
        }
    }

    @Test fun runtimeTransfersAllPortablePreferenceNamespacesAndFeatureFilesWithoutCloningLinkIdentity() {
        val source = isolated(); val target = isolated()
        val targetLink = LinkSettings(enabled = true, autoSync = true, deviceId = "receiver", deviceName = "Receiver", listenPort = 17440,
            peers = listOf(LinkedPeer("sender", "Sender", LinkPlatform.PHONE, "127.0.0.1", 17441, "fixture-pair-token")))
        LinkStore.save(target, targetLink)
        (BackupPreferences.names - "link_settings").forEach { name ->
            source.getSharedPreferences(name, 0).edit().putString("fixture.string", name).putBoolean("fixture.boolean", true)
                .putInt("fixture.int", 19).putLong("fixture.long", Long.MAX_VALUE - 1).putFloat("fixture.float", 1.25f)
                .putStringSet("fixture.set", setOf("one", "中文")).commit()
        }
        ProfileStore.saveName(source, "Sender profile")
        MemoryStore.saveContent(source, "Remember the fixture")
        MemoryStore.saveEnabled(source, true)
        SystemPromptStore.save(source, "System fixture")
        file(source, "profile/avatar.png", "fixture-avatar")
        LinuxSandboxStore.saveCommandsPerCall(LinuxSandboxLayout.of(source.filesDir).stateFile, 17)
        file(source, "linux-sandbox/rootfs/private", "excluded")
        file(source, "linux-sandbox/rootfs.version", "excluded")
        file(source, "attachments/private.txt", "excluded")
        file(source, "partial.part", "excluded")
        File(source.noBackupFilesDir, "private-key").writeText("excluded")
        val message = snapshot(source)
        assertTrue("The actual runtime sender must include portable feature data", message.has("portableData"))
        val portable = message.getJSONObject("portableData")
        assertFalse(portable.getJSONObject("preferences").has("link_settings"))
        val files = portable.getJSONObject("files").keys().asSequence().toSet()
        assertTrue(files.containsAll(setOf("memory.json", "system_prompt.json", "profile/avatar.png", "linux-sandbox/state.json")))
        assertFalse(files.any { it.startsWith("attachments/") || it.contains("rootfs") || it.endsWith(".part") })
        portable.getJSONObject("preferences").put("link_settings", "must be ignored, never replace receiver identity")
        apply(target, overLoopback(message), overwrite = true)
        val sourcePreferences = BackupPreferences.decode(BackupPreferences.snapshot(source))
        val targetPreferences = BackupPreferences.decode(BackupPreferences.snapshot(target))
        (BackupPreferences.names - "link_settings").forEach { name ->
            assertEquals(sourcePreferences[name], targetPreferences[name])
        }
        assertEquals(targetLink, LinkStore.load(target))
        assertEquals("Sender profile", ProfileStore.name)
        assertEquals("Remember the fixture", MemoryStore.loadContent(target))
        assertTrue(MemoryStore.loadEnabled(target))
        assertEquals("System fixture", SystemPromptStore.load(target))
        assertEquals("fixture-avatar", File(target.filesDir, "profile/avatar.png").readText())
        assertEquals(17, LinuxSandboxStore.loadCommandsPerCall(LinuxSandboxLayout.of(target.filesDir).stateFile))
        assertFalse(File(target.filesDir, "attachments/private.txt").exists())
        assertFalse(File(target.filesDir, "linux-sandbox/rootfs").exists())
    }

    @Test fun sandboxMergeFillsMissingKeysAndAvatarAbsenceOnlyClearsWithExplicitOverwrite() {
        val source = isolated(); val target = isolated()
        val sourceState = LinuxSandboxLayout.of(source.filesDir).stateFile
        val targetState = LinuxSandboxLayout.of(target.filesDir).stateFile
        LinuxSandboxStore.saveCommandsPerCall(sourceState, 17)
        LinuxSandboxStore.saveAttachmentPaths(sourceState, true)
        LinuxSandboxStore.saveCommandsPerCall(targetState, 3)
        file(target, "profile/avatar.png", "local-avatar")
        val message = snapshot(source)
        apply(target, message)
        assertEquals(3, LinuxSandboxStore.loadCommandsPerCall(targetState))
        assertTrue(LinuxSandboxStore.loadAttachmentPaths(targetState))
        assertTrue(File(target.filesDir, "profile/avatar.png").exists())
        apply(target, message, true)
        assertEquals(17, LinuxSandboxStore.loadCommandsPerCall(targetState))
        assertFalse(File(target.filesDir, "profile/avatar.png").exists())
    }

    @Test fun coexistFillsMissingPreferenceKeysAndFilesButKeepsLocalConflicts() {
        val source = isolated(); val target = isolated()
        source.getSharedPreferences("tool_preferences", 0).edit().putString("description:existing", "remote")
            .putString("description:new", "new").commit()
        target.getSharedPreferences("tool_preferences", 0).edit().putString("description:existing", "local").commit()
        MemoryStore.saveContent(source, "remote memory"); MemoryStore.saveContent(target, "local memory")
        SystemPromptStore.save(source, "remote prompt")
        val message = snapshot(source)
        repeat(2) { apply(target, message) }
        assertEquals("local", target.getSharedPreferences("tool_preferences", 0).getString("description:existing", null))
        assertEquals("new", target.getSharedPreferences("tool_preferences", 0).getString("description:new", null))
        assertEquals("local memory", MemoryStore.loadContent(target))
        assertEquals("remote prompt", SystemPromptStore.load(target))
        apply(target, message, overwrite = true)
        assertEquals("remote memory", MemoryStore.loadContent(target))
        assertEquals("remote", target.getSharedPreferences("tool_preferences", 0).getString("description:existing", null))
    }
}
