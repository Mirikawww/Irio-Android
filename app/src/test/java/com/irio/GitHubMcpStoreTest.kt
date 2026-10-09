package com.irio

import java.io.File
import javax.crypto.KeyGenerator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubMcpStoreTest {
    @Test fun legacyInstallationCanAcquireAccountDataWithoutResettingDisabledState() {
        val store = GitHubMcpStore(File(temp.root, "legacy.json")) { key }
        val tools = listOf(McpTool("get_me", "", JSONObject("{\"type\":\"object\"}")))
        store.save(GitHubMcpInstallation(GitHubCredential("legacy-token", 123), "OAuth", tools, enabled = false))
        assertNull(store.load()!!.account)
        val account = GitHubMcpAccount("octocat", "https://avatars.githubusercontent.com/u/1", "primary@example.test")
        val updated = store.updateAccount("legacy-token", account)!!
        assertEquals(account, updated.account)
        assertFalse(updated.enabled)
        assertEquals(123L, updated.credential.expiresAt)
        assertEquals("OAuth", updated.auth)
        assertEquals(account, store.load()!!.account)
    }

    @Test fun staleProfileResponsesCannotRestoreARemovedInstallationOrChangeAnotherAccount() {
        val store = GitHubMcpStore(File(temp.root, "stale.json")) { key }
        val tools = listOf(McpTool("get_me", "", JSONObject("{\"type\":\"object\"}")))
        val oldAccount = GitHubMcpAccount("old", "", "old@example.test")
        val newAccount = GitHubMcpAccount("new", "", "new@example.test")
        store.save(GitHubMcpInstallation(GitHubCredential("old-token"), "PAT", tools))
        store.remove()
        assertNull(store.updateAccount("old-token", oldAccount))
        assertNull(store.load())
        store.save(GitHubMcpInstallation(GitHubCredential("new-token"), "OAuth", tools, account = newAccount))
        assertNull(store.updateAccount("old-token", oldAccount))
        assertEquals(newAccount, store.load()!!.account)
        assertEquals("new-token", store.load()!!.credential.accessToken)
    }

    @Test fun accountSurvivesReopenAndEnableChangesWithoutExposingCredentials() {
        val file = File(temp.root, "account.json")
        val store = GitHubMcpStore(file) { key }
        val account = GitHubMcpAccount("octocat", "https://avatars.githubusercontent.com/u/1", "primary@example.test")
        val tools = listOf(McpTool("get_me", "Read profile", JSONObject("{\"type\":\"object\"}")))
        store.save(GitHubMcpInstallation(GitHubCredential("secret-fixture"), "PAT", tools, account = account))
        assertEquals(account, GitHubMcpStore(file) { key }.load()!!.account)
        store.setEnabled(false)
        assertEquals(account, store.load()!!.account)
        assertFalse(store.load()!!.enabled)
        assertFalse(file.readText().contains("secret-fixture"))
    }

    @Test fun onlyEnabledValidInstallationsRegisterWithTheRealRequestRegistry() = kotlinx.coroutines.runBlocking {
        val store = GitHubMcpStore(File(temp.root, "registry.json")) { key }
        val cancellation = ChatRequestCancellation()
        assertNull(store.toolProvider(cancellation))
        store.save(GitHubMcpInstallation(GitHubCredential("fixture"), "PAT", listOf(
            McpTool("get_me", "Read profile", JSONObject("{\"type\":\"object\",\"properties\":{}}")))))
        val registry = ToolRegistry(listOf(TimeToolProvider()))
        store.toolProvider(cancellation)?.let(registry::register)
        assertTrue(registry.handles("github_mcp_get_me"))
        for (protocol in ProviderProtocol.entries) assertEquals(2, registry.definitions(protocol).size)
        store.setEnabled(false)
        assertNull(store.toolProvider(cancellation))
        store.remove()
        assertNull(store.toolProvider(cancellation))
    }

    @get:Rule val temp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun installationSurvivesReopenWithoutPlaintextCredentialsOnDisk() {
        val file = File(temp.root, "github.json")
        val store = GitHubMcpStore(file) { key }
        val tool = McpTool("get_me", "Read profile", JSONObject("{\"type\":\"object\",\"properties\":{}}"))
        store.save(GitHubMcpInstallation(GitHubCredential("secret-fixture"), "PAT", listOf(tool)))
        assertFalse(file.readText().contains("secret-fixture"))
        val reopened = GitHubMcpStore(file) { key }.load()!!
        assertEquals("secret-fixture", reopened.credential.accessToken)
        assertEquals("get_me", reopened.tools.single().name)
        assertTrue(reopened.enabled)
        store.setEnabled(false)
        assertFalse(store.load()!!.enabled)
        store.remove()
        assertNull(store.load())
    }

    @Test fun expiredOrUnreadableCredentialsNeverBecomeUsable() {
        val file = File(temp.root, "github.json")
        val store = GitHubMcpStore(file) { key }
        store.save(GitHubMcpInstallation(GitHubCredential("fixture", 1), "OAuth", listOf(
            McpTool("get_me", "", JSONObject("{\"type\":\"object\"}")))))
        assertFalse(store.load()!!.usable())
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(GitHubMcpStore(file) { otherKey }.load())
    }
}
