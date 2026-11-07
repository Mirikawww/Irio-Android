package com.irio

import java.io.File
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared strict parsing for the two portable, plaintext MCP snapshot formats. */
internal object McpSnapshotJson {
    fun boolean(json: JSONObject, name: String): Boolean {
        val value = json.get(name)
        require(value is Boolean) { "Invalid MCP snapshot field: $name" }
        return value
    }

    fun string(json: JSONObject, name: String, allowBlank: Boolean = false): String {
        val value = json.get(name)
        require(value is String && (allowBlank || value.isNotBlank())) { "Invalid MCP snapshot field: $name" }
        return value
    }

    fun expiry(json: JSONObject, name: String): Long {
        val value = json.get(name)
        require(value is Int || value is Long) { "Invalid MCP snapshot field: $name" }
        return (value as Number).toLong().also { require(it >= 0) { "Invalid MCP snapshot field: $name" } }
    }

    fun refreshToken(credential: JSONObject): String? =
        if (!credential.has("refresh_token") || credential.isNull("refresh_token")) null
        else string(credential, "refresh_token")

    fun account(json: JSONObject): JSONObject? =
        if (!json.has("account") || json.isNull("account")) null else json.getJSONObject("account")

    fun tools(json: JSONObject, maxNameLength: Int): List<McpTool> {
        val array = json.getJSONArray("tools")
        require(array.length() > 0) { "Empty MCP snapshot tools" }
        val names = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val tool = array.getJSONObject(index)
            val name = string(tool, "name")
            require(name.matches(Regex("[A-Za-z0-9_-]{1,$maxNameLength}")) && names.add(name)) { "Invalid MCP snapshot tool name" }
            McpTool(name, string(tool, "description", allowBlank = true), JSONObject(tool.getJSONObject("inputSchema").toString()))
        }
    }
}

internal class GitHubMcpInstallation(val credential: GitHubCredential, val auth: String, val tools: List<McpTool>,
    val enabled: Boolean = true, val account: GitHubMcpAccount? = null) {
    fun usable(): Boolean = enabled && credential.accessToken.isNotBlank() && tools.isNotEmpty() &&
        (credential.expiresAt == 0L || credential.expiresAt > System.currentTimeMillis())
}
internal class GitHubMcpStore(
    private val file: File,
    private val secretKey: () -> SecretKey,
    private val refreshAuth: () -> GitHubDeviceAuth = { GitHubDeviceAuth() },
) {
    fun save(installation: GitHubMcpInstallation) = synchronized(lock) {
        require(installation.credential.accessToken.isNotBlank() && installation.tools.isNotEmpty())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val credentialPayload = JSONObject()
            .put("access_token", installation.credential.accessToken)
            .put("expires_at", installation.credential.expiresAt)
        installation.credential.refreshToken?.let { credentialPayload.put("refresh_token", it) }
        credentialPayload.put("refresh_token_expires_at", installation.credential.refreshTokenExpiresAt)
        val encrypted = cipher.doFinal(credentialPayload.toString().toByteArray(Charsets.UTF_8))
        val json = JSONObject().put("auth", installation.auth).put("enabled", installation.enabled)
            .put("expiresAt", installation.credential.expiresAt)
            .put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("credential", Base64.getEncoder().encodeToString(encrypted))
            .put("tools", JSONArray(installation.tools.map { tool -> JSONObject().put("name", tool.name)
                .put("description", tool.description).put("inputSchema", tool.inputSchema) }))
        installation.account?.let {
            json.put("account", JSONObject().put("login", it.login).put("avatar_url", it.avatarUrl).put("email", it.email))
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    fun load(): GitHubMcpInstallation? = synchronized(lock) {
        runCatching {
            if (!file.exists() || file.length() > 4 * 1024 * 1024) return@synchronized null
            val json = JSONObject(file.readText())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.getDecoder().decode(json.getString("iv"))))
            val credentialBytes = cipher.doFinal(Base64.getDecoder().decode(json.getString("credential")))
            val credentialText = String(credentialBytes, Charsets.UTF_8)
            val credentialJson = runCatching { JSONObject(credentialText) }.getOrNull()
            val credential = if (credentialJson != null && credentialJson.has("access_token")) {
                GitHubCredential(
                    accessToken = credentialJson.getString("access_token"),
                    expiresAt = credentialJson.optLong("expires_at", json.optLong("expiresAt")),
                    refreshToken = credentialJson.optString("refresh_token").takeIf(String::isNotBlank),
                    refreshTokenExpiresAt = credentialJson.optLong("refresh_token_expires_at", 0),
                )
            } else {
                // Legacy installations encrypted only the access token.
                GitHubCredential(credentialText, json.optLong("expiresAt"))
            }
            val array = json.getJSONArray("tools")
            val tools = (0 until array.length()).map { index ->
                val tool = array.getJSONObject(index)
                McpTool(tool.getString("name"), tool.optString("description"), tool.getJSONObject("inputSchema"))
            }
            val account = json.optJSONObject("account")?.let {
                GitHubMcpAccount(it.getString("login"), it.getString("avatar_url"), it.getString("email"))
            }
            GitHubMcpInstallation(credential, json.getString("auth"), tools, json.optBoolean("enabled", true), account)
        }.getOrNull()
    }

    /** Explicit plaintext export; never refreshes or contacts GitHub. */
    internal fun snapshot(): JSONObject = synchronized(lock) {
        val installation = load() ?: run {
            check(!file.exists()) { "Unable to decrypt GitHub integration for backup" }
            return@synchronized JSONObject().put("installed", false)
        }
        JSONObject().put("installed", true).put("auth", installation.auth).put("enabled", installation.enabled)
            .put("credential", JSONObject().put("access_token", installation.credential.accessToken)
                .put("expires_at", installation.credential.expiresAt)
                .put("refresh_token", installation.credential.refreshToken ?: JSONObject.NULL)
                .put("refresh_token_expires_at", installation.credential.refreshTokenExpiresAt))
            .put("tools", JSONArray(installation.tools.map { tool -> JSONObject().put("name", tool.name)
                .put("description", tool.description).put("inputSchema", JSONObject(tool.inputSchema.toString())) }))
            .put("account", installation.account?.let { JSONObject().put("login", it.login)
                .put("avatar_url", it.avatarUrl).put("email", it.email) } ?: JSONObject.NULL)
    }

    internal fun applySnapshot(snapshot: JSONObject, overwrite: Boolean): Unit = synchronized(lock) {
        val installation = parseSnapshot(snapshot)
        if (installation == null) {
            if (overwrite) remove()
            return@synchronized
        }
        // Validate the entire incoming installation even when merge will preserve the current one.
        if (overwrite || load() == null) save(installation)
    }

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        load()?.let { save(GitHubMcpInstallation(it.credential, it.auth, it.tools, enabled, it.account)) }
    }

    fun updateAccount(expectedToken: String, account: GitHubMcpAccount): GitHubMcpInstallation? = synchronized(lock) {
        val current = load()?.takeIf { it.credential.accessToken == expectedToken } ?: return@synchronized null
        val updated = GitHubMcpInstallation(current.credential, current.auth, current.tools, current.enabled, account)
        save(updated)
        updated
    }

    suspend fun refreshIfNeeded(allowDisabled: Boolean = false): GitHubMcpInstallation? {
        val current = load()?.takeIf { (allowDisabled || it.enabled) && it.tools.isNotEmpty() && it.credential.accessToken.isNotBlank() }
            ?: return null
        if (!current.credential.shouldRefresh()) return current.takeIf {
            it.credential.expiresAt == 0L || it.credential.expiresAt > System.currentTimeMillis()
        }
        if (current.credential.refreshTokenExpiresAt > 0 && current.credential.refreshTokenExpiresAt <= System.currentTimeMillis()) return null
        val refreshed = runCatching { refreshAuth().refresh(current.credential) }.getOrNull() ?: return null
        return synchronized(lock) {
            val latest = load()?.takeIf { it.credential.accessToken == current.credential.accessToken } ?: return@synchronized null
            val updated = GitHubMcpInstallation(refreshed, latest.auth, latest.tools, latest.enabled, latest.account)
            save(updated)
            updated
        }
    }

    fun remove(): Unit = synchronized(lock) {
        check(!file.exists() || file.delete()) { tr("无法移除 GitHub 集成，请重试") }
    }

    fun updateTools(expectedToken: String, tools: List<McpTool>): GitHubMcpInstallation? = synchronized(lock) {
        require(tools.isNotEmpty())
        val current = load()?.takeIf { it.credential.accessToken == expectedToken } ?: return@synchronized null
        save(GitHubMcpInstallation(current.credential, current.auth, tools, current.enabled, current.account))
        load()
    }

    suspend fun toolProvider(cancellation: ChatRequestCancellation): ToolProvider? = withContext(Dispatchers.IO) {
        val installation = refreshIfNeeded()?.takeIf { it.usable() } ?: return@withContext null
        GitHubMcpToolProvider(installation.tools) { name, arguments ->
            withContext(Dispatchers.IO) {
                val current = refreshIfNeeded()?.takeIf { it.usable() }
                    ?: error(tr("GitHub 集成已关闭或登录已失效"))
                GitHubMcpClient(current.credential.accessToken, cancellation = cancellation).callTool(name, arguments)
            }
        }
    }

    companion object {
        private val lock = Any()
        private const val KEY_ALIAS = "irio_mcp_github"

        /** Backup preflight: no store access, key access, or network. */
        internal fun validateSnapshot(snapshot: JSONObject): Unit { parseSnapshot(snapshot) }

        private fun parseSnapshot(snapshot: JSONObject): GitHubMcpInstallation? {
            if (!McpSnapshotJson.boolean(snapshot, "installed")) return null
            val credential = snapshot.getJSONObject("credential")
            val tools = McpSnapshotJson.tools(snapshot, maxNameLength = 50)
            val account = McpSnapshotJson.account(snapshot)?.let {
                GitHubMcpAccount(McpSnapshotJson.string(it, "login", allowBlank = true),
                    McpSnapshotJson.string(it, "avatar_url", allowBlank = true), McpSnapshotJson.string(it, "email", allowBlank = true))
            }
            return GitHubMcpInstallation(GitHubCredential(McpSnapshotJson.string(credential, "access_token"),
                McpSnapshotJson.expiry(credential, "expires_at"), McpSnapshotJson.refreshToken(credential),
                McpSnapshotJson.expiry(credential, "refresh_token_expires_at")), McpSnapshotJson.string(snapshot, "auth"), tools,
                McpSnapshotJson.boolean(snapshot, "enabled"), account)
        }

        fun of(context: Context): GitHubMcpStore = GitHubMcpStore(File(context.noBackupFilesDir, "mcp/github.json"), secretKey = {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        })
    }
}
