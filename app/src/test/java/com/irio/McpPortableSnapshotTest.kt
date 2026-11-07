package com.irio

import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpPortableSnapshotTest {
    @get:Rule val temp = TemporaryFolder()
    private val sourceKey = SecretKeySpec(ByteArray(32) { 11 }, "AES")
    private val destinationKey = SecretKeySpec(ByteArray(32) { 29 }, "AES")
    private val tools get() = listOf(
        McpTool("get_items", "Read items 中文", JSONObject(
            """{"type":"object","properties":{"filter":{"type":"string","enum":["today","all"]}},"required":["filter"],"additionalProperties":false}""")),
        McpTool("get_profile", "", JSONObject("""{"type":"object","properties":{}}""")),
    )

    @Test fun githubSnapshotMovesExpiredDisabledInstallationAcrossKeysWithoutRefresh() {
        val source = GitHubMcpStore(File(temp.root, "github-source.json"), { sourceKey },
            refreshAuth = { error("Snapshot must not refresh credentials") })
        val original = GitHubMcpInstallation(GitHubCredential("github-access", 1, "github-refresh", 456),
            "OAuth", tools, enabled = false, GitHubMcpAccount("octocat", "https://example.test/avatar", "octo@example.test"))
        source.save(original)
        val snapshot = JSONObject(source.snapshot().toString())
        assertTrue(snapshot.getBoolean("installed"))
        assertEquals("github-access", snapshot.getJSONObject("credential").getString("access_token"))
        val destinationFile = File(temp.root, "github-destination.json")
        GitHubMcpStore(destinationFile, { destinationKey }).applySnapshot(snapshot, overwrite = true)

        val reopened = GitHubMcpStore(destinationFile, { destinationKey })
        val restored = reopened.load()!!
        assertEquals(original.credential, restored.credential)
        assertEquals(original.auth, restored.auth)
        assertEquals(original.account, restored.account)
        assertFalse(restored.enabled)
        assertTools(restored.tools)
        assertEquals(normalize(snapshot), normalize(reopened.snapshot()))
        assertEncrypted(destinationFile, destinationKey, "github-access", "github-refresh")
        assertNull(GitHubMcpStore(destinationFile, { sourceKey }).load())
        assertEquals(original.credential, source.load()!!.credential)
    }

    @Test fun todoistSnapshotMovesExpiredDisabledInstallationAcrossKeysWithoutRefresh() {
        val source = TodoistMcpStore(File(temp.root, "todoist-source.json"), { sourceKey }) { _, _ ->
            error("Snapshot must not refresh credentials")
        }
        val original = TodoistMcpInstallation("client-fixture", TodoistCredential("todoist-access", 1, "todoist-refresh"),
            tools, enabled = false, TodoistMcpAccount("Todoist User", "https://example.test/avatar", "todo@example.test"))
        source.save(original)
        val snapshot = JSONObject(source.snapshot().toString())
        assertTrue(snapshot.getBoolean("installed"))
        assertEquals("todoist-access", snapshot.getJSONObject("credential").getString("access_token"))
        val destinationFile = File(temp.root, "todoist-destination.json")
        TodoistMcpStore(destinationFile, { destinationKey }).applySnapshot(snapshot, overwrite = true)

        val reopened = TodoistMcpStore(destinationFile, { destinationKey })
        val restored = reopened.load()!!
        assertEquals(original.credential, restored.credential)
        assertEquals(original.clientId, restored.clientId)
        assertEquals(original.account, restored.account)
        assertFalse(restored.enabled)
        assertTools(restored.tools)
        assertEquals(normalize(snapshot), normalize(reopened.snapshot()))
        assertEncrypted(destinationFile, destinationKey, "todoist-access", "todoist-refresh")
        assertNull(TodoistMcpStore(destinationFile, { sourceKey }).load())
        assertEquals(original.credential, source.load()!!.credential)
    }

    @Test fun missingInstallationIsExplicitAndDoesNotRequestAKey() {
        val github = GitHubMcpStore(File(temp.root, "missing-github.json"), { error("No credentials to decrypt") })
        val todoist = TodoistMcpStore(File(temp.root, "missing-todoist.json"), { error("No credentials to decrypt") })
        for (snapshot in listOf(github.snapshot(), todoist.snapshot())) {
            assertEquals(setOf("installed"), snapshot.keys().asSequence().toSet())
            assertEquals(false, snapshot.get("installed"))
        }
    }

    @Test fun overwriteMissingRemovesInstallationWhileMergeMissingPreservesBytes() = forEachStore { destination ->
        destination.seed("existing", false)
        val before = destination.file.readBytes()
        val missing = JSONObject().put("installed", false)
        destination.apply(missing, false)
        assertArrayEquals(before, destination.file.readBytes())
        destination.apply(missing, true)
        assertFalse(destination.file.exists())
        assertEquals(false, destination.snapshot().get("installed"))
        destination.apply(missing, true)
        destination.apply(missing, false)
        assertFalse(destination.file.exists())
    }

    @Test fun mergePreservesExistingInstallationWithoutRewritingAnyBytes() = forEachStore { destination ->
        destination.seed("incoming", true)
        val incoming = destination.snapshot()
        destination.seed("existing", false)
        val before = destination.file.readBytes()
        val current = normalize(destination.snapshot())
        destination.apply(incoming, false)
        assertArrayEquals(before, destination.file.readBytes())
        assertEquals(current, normalize(destination.snapshot()))
    }

    @Test fun mergeFillsAbsentInstallationAndOverwriteReplacesExistingInstallation() = forEachStore { destination ->
        destination.seed("incoming", true)
        val incoming = destination.snapshot()
        destination.file.delete()
        destination.apply(incoming, false)
        assertEquals(normalize(incoming), normalize(destination.snapshot()))
        destination.seed("existing", false)
        destination.apply(incoming, true)
        assertEquals(normalize(incoming), normalize(destination.snapshot()))
    }

    @Test fun nullableAccountAndRefreshTokenWithNonExpiringCredentialsRoundTrip() = forEachStore { destination ->
        destination.seed("source", true)
        val snapshot = destination.snapshot()
        snapshot.put("account", JSONObject.NULL)
        snapshot.getJSONObject("credential").put("refresh_token", JSONObject.NULL).put("expires_at", 0L)
        if (snapshot.has("auth")) {
            snapshot.put("auth", "PAT")
            snapshot.getJSONObject("credential").put("refresh_token_expires_at", 0L)
        }
        destination.apply(snapshot, true)
        assertEquals(normalize(snapshot), normalize(destination.snapshot()))
    }

    @Test fun invalidSnapshotsAreRejectedBeforeEitherModeCanAlterExistingBytes() = forEachStore { destination ->
        destination.seed("existing", false)
        val valid = destination.snapshot()
        val before = destination.file.readBytes()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("installed") },
            { it.put("installed", "true") },
            { it.put("installed", JSONObject.NULL) },
            { it.put("enabled", "false") },
            { it.remove("enabled") },
            { it.put(if (it.has("auth")) "auth" else "client_id", " ") },
            { it.put("credential", JSONArray()) },
            { it.getJSONObject("credential").put("access_token", " ") },
            { it.getJSONObject("credential").put("access_token", 123) },
            { it.getJSONObject("credential").put("expires_at", "123") },
            { it.getJSONObject("credential").put("expires_at", -1L) },
            { it.getJSONObject("credential").put("expires_at", 1.5) },
            { it.getJSONObject("credential").put("expires_at", 1e30) },
            { it.getJSONObject("credential").put("refresh_token", 123) },
            { it.getJSONObject("credential").put("refresh_token", " ") },
            { it.put("tools", JSONArray()) },
            { it.getJSONArray("tools").getJSONObject(1).put("name", "invalid tool") },
            { it.getJSONArray("tools").getJSONObject(1).put("description", 123) },
            { it.getJSONArray("tools").getJSONObject(1).put("inputSchema", JSONArray()) },
            { it.put("account", JSONArray()) },
            { it.getJSONObject("account").put("email", 123) },
            { it.getJSONObject("account").remove("avatar_url") },
        )
        for ((index, mutate) in mutations.withIndex()) for (overwrite in listOf(false, true)) {
            val malformed = JSONObject(valid.toString()).also(mutate)
            val failure = runCatching { destination.apply(malformed, overwrite) }.exceptionOrNull()
            assertNotNull("Mutation $index must fail (overwrite=$overwrite)", failure)
            assertArrayEquals("Mutation $index changed stored bytes", before, destination.file.readBytes())
        }
        if (valid.has("auth")) {
            val malformed = JSONObject(valid.toString())
            malformed.getJSONObject("credential").put("refresh_token_expires_at", -1)
            assertNotNull(runCatching { destination.apply(malformed, true) }.exceptionOrNull())
            assertArrayEquals(before, destination.file.readBytes())
        }
    }

    @Test fun invalidSnapshotCannotCreateAnInstallationWhenDestinationIsAbsent() = forEachStore { destination ->
        destination.seed("incoming", true)
        val malformed = destination.snapshot()
        malformed.getJSONArray("tools").getJSONObject(1).put("inputSchema", "not an object")
        destination.file.delete()
        for (overwrite in listOf(false, true)) {
            assertNotNull(runCatching { destination.apply(malformed, overwrite) }.exceptionOrNull())
            assertFalse(destination.file.exists())
        }
    }

    @Test fun companionValidationIsPureAndRejectsMalformedSnapshotsBeforeImport() = forEachStore { destination ->
        destination.seed("existing", false)
        val snapshot = destination.snapshot()
        val before = destination.file.readBytes()
        val validate: (JSONObject) -> Unit = if (snapshot.has("auth")) GitHubMcpStore::validateSnapshot
            else TodoistMcpStore::validateSnapshot
        validate(JSONObject().put("installed", false))
        validate(snapshot)
        val malformed = JSONObject(snapshot.toString())
        malformed.getJSONArray("tools").getJSONObject(1).put("inputSchema", "invalid")
        assertNotNull(runCatching { validate(malformed) }.exceptionOrNull())
        assertNotNull(runCatching { validate(JSONObject()) }.exceptionOrNull())
        assertArrayEquals(before, destination.file.readBytes())
        assertTrue(destination.file.delete())
        validate(snapshot)
        validate(JSONObject().put("installed", false))
        assertFalse(destination.file.exists())
    }

    private class Fixture(
        val file: File,
        val snapshot: () -> JSONObject,
        val apply: (JSONObject, Boolean) -> Unit,
        val seed: (String, Boolean) -> Unit,
    )

    private fun forEachStore(test: (Fixture) -> Unit) {
        val githubFile = File(temp.root, "github.json")
        val github = GitHubMcpStore(githubFile, { destinationKey })
        test(Fixture(githubFile, github::snapshot, github::applySnapshot) { token, enabled ->
            github.save(GitHubMcpInstallation(GitHubCredential(token, 123, "$token-refresh", 456), "OAuth", tools,
                enabled, GitHubMcpAccount(token, "", "$token@example.test")))
        })
        val todoistFile = File(temp.root, "todoist.json")
        val todoist = TodoistMcpStore(todoistFile, { destinationKey })
        test(Fixture(todoistFile, todoist::snapshot, todoist::applySnapshot) { token, enabled ->
            todoist.save(TodoistMcpInstallation("$token-client", TodoistCredential(token, 123, "$token-refresh"), tools,
                enabled, TodoistMcpAccount(token, "", "$token@example.test")))
        })
    }

    private fun assertTools(actual: List<McpTool>) {
        assertEquals(tools.map { it.name }, actual.map { it.name })
        assertEquals(tools.map { it.description }, actual.map { it.description })
        tools.zip(actual).forEach { (expected, restored) -> assertEquals(normalize(expected.inputSchema), normalize(restored.inputSchema)) }
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { normalize(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { normalize(value.get(it)) }
        is Number -> value.toString()
        JSONObject.NULL -> null
        else -> value
    }

    private fun assertEncrypted(file: File, key: SecretKey, access: String, refresh: String) {
        val text = file.readText()
        assertFalse(text.contains(access))
        assertFalse(text.contains(refresh))
        val envelope = JSONObject(text)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(envelope.getString("iv"))))
        val payload = JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(envelope.getString("credential"))), Charsets.UTF_8))
        assertEquals(access, payload.getString("access_token"))
        assertEquals(refresh, payload.getString("refresh_token"))
    }
}