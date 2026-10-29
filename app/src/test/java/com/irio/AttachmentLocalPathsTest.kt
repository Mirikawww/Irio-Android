package com.irio

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AttachmentLocalPathsTest {
    @Test fun pathsDefaultOffAndSavingPreservesOtherSandboxPreferences() {
        val app = RuntimeEnvironment.getApplication()
        val state = LinuxSandboxLayout.of(app.filesDir).stateFile
        state.delete()
        assertFalse(LinuxSandboxStore.loadAttachmentPaths(state))
        LinuxSandboxStore.saveEnabled(state, true)
        LinuxSandboxStore.saveCommandsPerCall(state, 7)
        LinuxSandboxStore.saveBackgroundEnabled(state, true)
        assertFalse("Existing installations remain opt-out", LinuxSandboxStore.loadAttachmentPaths(state))
        LinuxSandboxStore.saveAttachmentPaths(state, true)
        assertTrue(LinuxSandboxStore.loadAttachmentPaths(state))
        assertTrue(LinuxSandboxStore.loadEnabled(state))
        assertTrue(LinuxSandboxStore.loadBackgroundEnabled(state))
        assertEquals(7, LinuxSandboxStore.loadCommandsPerCall(state))
        LinuxSandboxStore.saveEnabled(state, false)
        assertTrue("Disabling the sandbox must preserve this preference", LinuxSandboxStore.loadAttachmentPaths(state))
        LinuxSandboxStore.saveAttachmentPaths(state, false)
        assertFalse(LinuxSandboxStore.loadAttachmentPaths(state))
        assertFalse(LinuxSandboxStore.loadEnabled(state))
        state.writeText("malformed")
        assertFalse(LinuxSandboxStore.loadAttachmentPaths(state))
        state.delete()
        val hit = searchSettings("附带上传文件路径", label = { it }).single()
        assertEquals("linux.attachmentPaths", hit.anchor)
        assertEquals("com.irio.LinuxSandboxSettingsActivity", hit.destination.name)
        assertEquals("Include uploaded file paths", translate(AppLanguage.EN, hit.title))
    }

    @Test fun prootOnlyMountsTheUploadDirectoryWhenRequested() {
        val args = linuxSandboxProotArguments("/proot", "/rootfs", "/storage/emulated/0", "/tmp", "true")
        assertFalse(args.any { it.contains("/files/attachments") })
        val path = "/data/user/0/com.irio/files/attachments"
        val on = linuxSandboxProotArguments("/proot", "/rootfs", "/storage/emulated/0", "/tmp", "true", path)
        assertTrue(on.contains("--bind=$path:$path"))
        assertFalse("Never expose the entire private files directory", on.contains("--bind=/data/user/0/com.irio/files:/data/user/0/com.irio/files"))
    }

    @Test fun metadataOmitsMissingAndUnsharedFilesAndNeverChangesAssistantMessages() {
        val app = RuntimeEnvironment.getApplication()
        ShadowEnvironment.addExternalDir(app.cacheDir.resolve("external").absolutePath)
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.AppOpsManager::class.java))
            .setMode("android:manage_external_storage", android.os.Process.myUid(), app.packageName,
                android.app.AppOpsManager.MODE_ALLOWED)
        val state = LinuxSandboxLayout.of(app.filesDir).stateFile
        LinuxSandboxStore.saveEnabled(state, true)
        LinuxSandboxStore.saveAttachmentPaths(state, true)
        val path = File(app.filesDir, "attachments/upload.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        val outside = File(app.filesDir, "private.txt").apply { writeText("private") }
        try {
            val attachments = listOf(
                ChatAttachment("a\"\n.txt", "text/plain", path.absolutePath),
                ChatAttachment("private.txt", "text/plain", outside.absolutePath),
                ChatAttachment("missing.txt", "text/plain", File(path.parentFile, "missing.txt").absolutePath),
            )
            val user = ChatRequestMessage("user", attachments = attachments)
            val assistant = user.copy(role = "assistant")
            val option = LinuxAttachmentPaths.forRequest(app)
            val result = option.decorate(listOf(user, assistant))
            val data = org.json.JSONArray(result.first().text.substringAfterLast('\n'))
            assertEquals(1, data.length())
            assertEquals(path.canonicalPath, data.getJSONObject(0).getString("path"))
            assertEquals(attachments.first().name, data.getJSONObject(0).getString("name"))
            assertSame(assistant, result.last())
            assertEquals("", user.text)
            assertEquals("keep", path.readText())
            val args = linuxSandboxProotArguments("/proot", "/rootfs", null, "/tmp", "true", option.directory!!.absolutePath)
            assertTrue(args.contains("--bind=${option.directory.absolutePath}:${option.directory.absolutePath}"))
            LinuxSandboxStore.saveAttachmentPaths(state, false)
            val off = LinuxAttachmentPaths.forRequest(app)
            assertNull(off.directory)
            assertEquals(listOf(user), off.decorate(listOf(user)))
        } finally { state.delete(); path.delete(); outside.delete() }
    }

    @Test fun everyProtocolOnlyDisclosesUploadedPathsWithBothSwitchesAndPermission() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        ShadowEnvironment.addExternalDir(app.cacheDir.resolve("external").absolutePath)
        val state = LinuxSandboxLayout.of(app.filesDir).stateFile
        state.parentFile!!.mkdirs()
        val attachment = File(app.filesDir, "attachments/path fixture.pdf").apply {
            parentFile!!.mkdirs(); writeText("attachment fixture")
        }
        val original = ChatRequestMessage("user", "Read the file", listOf(
            ChatAttachment("report.pdf", "application/pdf", attachment.absolutePath)))
        val before = attachment.readBytes()
        try {
            for (protocol in ProviderProtocol.entries) {
                for ((sandbox, paths, permission) in listOf(
                    Triple(true, true, true), Triple(true, false, true),
                    Triple(false, true, true), Triple(true, true, false),
                )) {
                    state.writeText(JSONObject().put("enabled", sandbox).put("attachmentPaths", paths).toString())
                    val ops = app.getSystemService(android.app.AppOpsManager::class.java)
                    org.robolectric.Shadows.shadowOf(ops).setMode("android:manage_external_storage",
                        android.os.Process.myUid(), app.packageName,
                        if (permission) android.app.AppOpsManager.MODE_ALLOWED else android.app.AppOpsManager.MODE_ERRORED)
                    assertEquals(permission, LinuxSandboxAccess.hasAllFiles())
                    MockWebServer().use { server ->
                        server.enqueue(MockResponse().setResponseCode(503).setBody("local fixture"))
                        IrioHttp.resetForTests(IrioHttp.defaultClient())
                        ChatApi.streamChat(
                            ProviderConfig("fixture", "Fixture", server.url("/v1beta").toString(), "fixture", protocol),
                            "model", listOf(original), ReasoningEffort.OFF, false,
                            toolRegistry = ToolRegistry(), cancellation = ChatRequestCancellation(),
                            appContext = app, onUpdate = {},
                        )
                        val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("No request for $protocol")
                        val json = JSONObject(request.body.readUtf8())
                        val text = when (protocol) {
                            ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.ANTHROPIC ->
                                json.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
                            ProviderProtocol.RESPONSES, ProviderProtocol.GEMINI_INTERACTIONS ->
                                json.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
                            ProviderProtocol.GEMINI ->
                                json.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
                        }
                        if (sandbox && paths && permission) {
                            val metadata = org.json.JSONArray(text.substringAfterLast('\n'))
                            assertEquals(attachment.canonicalPath, metadata.getJSONObject(0).getString("path"))
                            assertEquals("report.pdf", metadata.getJSONObject(0).getString("name"))
                        } else {
                            assertEquals("$protocol sandbox=$sandbox paths=$paths permission=$permission", original.text, text)
                        }
                        assertTrue(text.startsWith("Read the file"))
                        assertEquals("Request decoration must not change saved message text", "Read the file", original.text)
                        assertArrayEquals(before, attachment.readBytes())
                    }
                }
            }
        } finally {
            state.delete(); attachment.delete(); IrioHttp.resetForTests()
        }
    }
}
