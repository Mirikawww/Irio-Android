package com.irio

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BackupStoreTest {
    private lateinit var originalPreferences: JSONObject
    @Before fun preserveLoadedPreferences() {
        originalPreferences = BackupPreferences.snapshot(RuntimeEnvironment.getApplication())
    }
    @After fun restoreLoadedPreferences() {
        val context = RuntimeEnvironment.getApplication()
        BackupPreferences.apply(context, BackupPreferences.decode(originalPreferences), overwrite = true)
        ThemeStore.reload(context)
        ProfileStore.reload(context)
    }
    private fun isolated(): Context {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, "backup-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir() = root
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences(root.name + "-" + name, mode)
        }
    }
    private fun export(context: Context): ByteArray = ByteArrayOutputStream().also { BackupStore.export(context, it) }.toByteArray()
    private fun archive(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((path, value) in listOf("format" to "irio-backup-v1") + entries) {
                zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()
    private fun entries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) }
        }
    }

    @Test fun currentPreferencesAndNewFeatureFilesRoundTripIntoAlreadyLoadedStores() {
        val source = isolated(); val target = isolated()
        val settings = source.getSharedPreferences("appearance_settings", 0)
        settings.edit().putBoolean("image_upload_compression", false).putString("image_upload_format", "jpeg")
            .putInt("image_upload_quality", 71).putStringSet("glass_surfaces", setOf("composer"))
            .putString("text_scale_mode", "custom").putInt("text_scale_percent", 135).commit()
        source.getSharedPreferences("tool_settings", 0).edit().putBoolean("subagents", true)
            .putString("context_compression_model", "fixture-model").commit()
        ToolPreferencesStore.saveEnabled(source, "fixture", false)
        ToolPreferencesStore.saveDescription(source, "fixture", "")
        SpeechStore.saveSpeechRate(source, 1.7f)
        source.getSharedPreferences("image_generation_settings", 0).edit().putString("fixture", "image").commit()
        source.getSharedPreferences("video_generation_settings", 0).edit().putString("fixture", "video").commit()
        ProfileStore.saveName(source, "Backup user")
        MemoryStore.saveContent(source, "remember fixture")
        MemoryStore.saveEnabled(source, true)
        SystemPromptStore.save(source, "system fixture")
        File(source.filesDir, "profile").mkdirs()
        File(source.filesDir, "profile/avatar.png").writeBytes(byteArrayOf(1, 2, 3))
        val sandbox = LinuxSandboxLayout.of(source.filesDir)
        LinuxSandboxStore.saveCommandsPerCall(sandbox.stateFile, 17)
        LinuxSandboxStore.saveAttachmentPaths(sandbox.stateFile, true)
        sandbox.rootfs.mkdirs(); File(sandbox.rootfs, "fixture").writeText("excluded")
        sandbox.versionFile.writeText("fixture-version")
        File(source.filesDir, "attachments").mkdirs()
        File(source.filesDir, "attachments/private.txt").writeText("excluded")
        File(source.noBackupFilesDir, "runtime.tmp").writeText("excluded")
        val previousTheme = ThemeStore.imageUploadOptions()
        try {
            ThemeStore.saveImageUploadCompression(target, true)
            ThemeStore.saveImageUploadQuality(target, 90)
            ProfileStore.saveName(target, "Local user")
            val bytes = export(source)
            val names = entries(bytes).keys
            assertFalse(names.any { it.startsWith("files/attachments/") || it.contains("/rootfs") || it.startsWith("no_backup/") })
            val result = BackupStore.import(target, ByteArrayInputStream(bytes))
            assertTrue(result.success)
            val restored = target.getSharedPreferences("appearance_settings", 0)
            assertEquals(settings.all, restored.all)
            assertFalse(ThemeStore.imageUploadCompression)
            assertEquals(71, ThemeStore.imageUploadQuality)
            assertEquals("Backup user", ProfileStore.name)
            assertEquals("remember fixture", MemoryStore.loadContent(target))
            assertEquals("system fixture", SystemPromptStore.load(target))
            assertEquals(1.7f, SpeechStore.loadSpeechRate(target), 0f)
            assertEquals(setOf("fixture"), ToolPreferencesStore.load(target).disabledTools)
            assertEquals("", ToolPreferencesStore.load(target).descriptions["fixture"])
            assertEquals("image", target.getSharedPreferences("image_generation_settings", 0).getString("fixture", null))
            assertEquals("video", target.getSharedPreferences("video_generation_settings", 0).getString("fixture", null))
            assertArrayEquals(byteArrayOf(1, 2, 3), File(target.filesDir, "profile/avatar.png").readBytes())
            assertEquals(17, LinuxSandboxStore.loadCommandsPerCall(LinuxSandboxLayout.of(target.filesDir).stateFile))
        } finally {
            ThemeStore.saveImageUploadCompression(target, previousTheme.enabled)
            ThemeStore.saveImageUploadQuality(target, previousTheme.quality)
            ThemeStore.saveImageUploadFormat(target, previousTheme.format)
            ProfileStore.forgetForTest()
        }
    }

    @Test fun legacyXmlRestoresThroughPreferencesAndMergeFillsMissingKeys() {
        val target = isolated()
        val prefs = target.getSharedPreferences("tool_preferences", 0)
        prefs.edit().putString("description:local", "keep").commit()
        val bytes = archive("shared_prefs/tool_preferences.xml" to """<map><string name="description:local">remote</string><string name="description:new">new</string><boolean name="enabled:new" value="false"/></map>""")
        BackupStore.import(target, ByteArrayInputStream(bytes), overwrite = false)
        assertEquals("keep", prefs.getString("description:local", null))
        assertEquals("new", prefs.getString("description:new", null))
        assertFalse(prefs.getBoolean("enabled:new", true))
        BackupStore.import(target, ByteArrayInputStream(bytes), overwrite = true)
        assertEquals("remote", prefs.getString("description:local", null))
    }

    @Test fun mergedConversationsAreDiscoverableWithoutReplacingLocalHistoryOrDuplicates() {
        val source = isolated(); val target = isolated()
        val remote = ConversationStore.newConversation().copy(title = "remote", updatedAt = 100)
        val local = ConversationStore.newConversation().copy(title = "local", updatedAt = 200)
        ConversationStore.save(source, remote)
        ConversationStore.save(target, local)
        val bytes = export(source)
        repeat(2) { assertTrue(BackupStore.import(target, ByteArrayInputStream(bytes), overwrite = false).success) }
        assertEquals(setOf(local.id, remote.id), ConversationStore.loadAll(target).map { it.id }.toSet())
        assertEquals("local", ConversationStore.load(target, local.id)?.title)
        assertEquals(2, ConversationStore.loadAll(target).size)
    }

    @Test fun allPreferenceNamespacesAndTypesRoundTripAndMergeKeepsLocalKeys() {
        val source = isolated(); val target = isolated()
        BackupPreferences.names.forEach { name ->
            source.getSharedPreferences(name, 0).edit().putString("fixture.string", name)
                .putBoolean("fixture.boolean", true).putInt("fixture.int", 19)
                .putLong("fixture.long", Long.MAX_VALUE - 1).putFloat("fixture.float", 1.25f)
                .putStringSet("fixture.set", setOf("one", "中文")).commit()
            target.getSharedPreferences(name, 0).edit().putString("fixture.string", "local").commit()
        }
        val bytes = export(source)
        assertTrue(BackupStore.import(target, ByteArrayInputStream(bytes), overwrite = false).success)
        BackupPreferences.names.forEach { name ->
            val prefs = target.getSharedPreferences(name, 0)
            assertEquals("local", prefs.getString("fixture.string", null))
            assertEquals(Long.MAX_VALUE - 1, prefs.getLong("fixture.long", 0))
            assertEquals(1.25f, prefs.getFloat("fixture.float", 0f), 0f)
            assertEquals(setOf("one", "中文"), prefs.getStringSet("fixture.set", null))
        }
        assertTrue(BackupStore.import(target, ByteArrayInputStream(bytes), overwrite = true).success)
        BackupPreferences.names.forEach { name ->
            assertEquals(name, target.getSharedPreferences(name, 0).getString("fixture.string", null))
        }
    }

    @Test fun providerModelsHeadersAndCredentialsReencryptUsingTheDestinationKey() {
        val source = isolated(); val target = isolated()
        val field = ProviderStore::class.java.getDeclaredField("cachedSecretKey").apply { isAccessible = true }
        val previous = field.get(ProviderStore)
        try {
            field.set(ProviderStore, javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES"))
            ProviderStore.saveProvider(source, ProviderConfig("fixture", "Fixture", "https://example.test", "fixture-secret",
                models = listOf("server", "custom"), customModels = listOf("custom"), disabledModels = setOf("custom"),
                customHeaders = listOf(ProviderHeader("X-Fixture", "header-secret")), selectedModel = "server"))
            val expected = ProviderStore.loadProviders(source)
            val bytes = export(source)
            field.set(ProviderStore, javax.crypto.spec.SecretKeySpec(ByteArray(32) { 23 }, "AES"))
            assertTrue(BackupStore.import(target, ByteArrayInputStream(bytes)).success)
            assertEquals(expected, ProviderStore.loadProviders(target))
            assertFalse(ProviderStore.preferences(target).getString("providers", "").orEmpty().contains("fixture-secret"))
        } finally { field.set(ProviderStore, previous) }
    }

    @Test fun archiveCarriesBothMcpInstallationsAcrossKeysWithoutCopyingRawCiphertext() {
        val source = isolated(); val target = isolated()
        val first = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val second = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 9 }, "AES")
        val tools = listOf(McpTool("fixture", "Fixture", JSONObject("""{"type":"object"}""")))
        val githubSource = GitHubMcpStore(File(source.noBackupFilesDir, "mcp/github.json"), { first })
        val todoistSource = TodoistMcpStore(File(source.noBackupFilesDir, "mcp/todoist.json"), { first })
        githubSource.save(GitHubMcpInstallation(GitHubCredential("github-fixture"), "PAT", tools, enabled = false))
        todoistSource.save(TodoistMcpInstallation("client", TodoistCredential("todoist-fixture", 0, "refresh-fixture"), tools))
        val out = ByteArrayOutputStream()
        BackupArchive.export(source, out, githubSource, todoistSource)
        val files = entries(out.toByteArray())
        assertTrue(files.containsKey("github_mcp_snapshot.json"))
        assertTrue(files.containsKey("todoist_mcp_snapshot.json"))
        assertFalse(files.keys.any { it.startsWith("no_backup/") })
        val githubTarget = GitHubMcpStore(File(target.noBackupFilesDir, "mcp/github.json"), { second })
        val todoistTarget = TodoistMcpStore(File(target.noBackupFilesDir, "mcp/todoist.json"), { second })
        assertTrue(BackupArchive.import(target, ByteArrayInputStream(out.toByteArray()), true, githubTarget, todoistTarget).success)
        assertEquals("github-fixture", githubTarget.load()!!.credential.accessToken)
        assertFalse(githubTarget.load()!!.enabled)
        assertEquals("refresh-fixture", todoistTarget.load()!!.credential.refreshToken)
    }

    @Test fun overwriteCanRestoreAnOlderConversationButMergePreservesLocalConflicts() {
        val source = isolated(); val target = isolated()
        val older = ConversationStore.newConversation().copy(title = "older", updatedAt = 100)
        ConversationStore.save(source, older)
        ConversationStore.save(target, older.copy(title = "newer", updatedAt = 200))
        val bytes = export(source)
        BackupStore.import(target, ByteArrayInputStream(bytes), overwrite = false)
        assertEquals("newer", ConversationStore.load(target, older.id)?.title)
        BackupStore.import(target, ByteArrayInputStream(bytes), overwrite = true)
        assertEquals("older", ConversationStore.load(target, older.id)?.title)
        assertEquals("older", ConversationStore.loadAll(target).single().title)
    }

    @Test fun malformedSnapshotAndTraversalNeverModifyExistingData() {
        val target = isolated()
        MemoryStore.saveContent(target, "keep")
        val bytes = archive("files/memory.json" to "{}", "provider_snapshot.json" to "not json")
        runCatching { BackupStore.import(target, ByteArrayInputStream(bytes)) }
        assertEquals("keep", MemoryStore.loadContent(target))
        val traversal = archive("../outside" to "bad")
        assertTrue(runCatching { BackupStore.import(target, ByteArrayInputStream(traversal)) }.isFailure)
        assertFalse(File(target.dataDir.parentFile, "outside").exists())
    }
}
