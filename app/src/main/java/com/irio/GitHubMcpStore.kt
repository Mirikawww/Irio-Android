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

internal class GitHubMcpInstallation(val credential: GitHubCredential, val auth: String, val tools: List<McpTool>,
    val enabled: Boolean = true, val account: GitHubMcpAccount? = null) {
    fun usable(): Boolean = enabled && credential.accessToken.isNotBlank() && tools.isNotEmpty() &&
        (credential.expiresAt == 0L || credential.expiresAt > System.currentTimeMillis())
}
internal class GitHubMcpStore(private val file: File, private val secretKey: () -> SecretKey) {
    fun save(installation: GitHubMcpInstallation) = synchronized(lock) {
        require(installation.credential.accessToken.isNotBlank() && installation.tools.isNotEmpty())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(installation.credential.accessToken.toByteArray(Charsets.UTF_8))
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
            val token = String(cipher.doFinal(Base64.getDecoder().decode(json.getString("credential"))), Charsets.UTF_8)
            val array = json.getJSONArray("tools")
            val tools = (0 until array.length()).map { index ->
                val tool = array.getJSONObject(index)
                McpTool(tool.getString("name"), tool.optString("description"), tool.getJSONObject("inputSchema"))
            }
            val account = json.optJSONObject("account")?.let {
                GitHubMcpAccount(it.getString("login"), it.getString("avatar_url"), it.getString("email"))
            }
            GitHubMcpInstallation(GitHubCredential(token, json.optLong("expiresAt")), json.getString("auth"), tools, json.optBoolean("enabled", true), account)
        }.getOrNull()
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

    fun remove(): Unit = synchronized(lock) {
        check(!file.exists() || file.delete()) { tr("无法移除 GitHub 集成，请重试") }
    }

    suspend fun toolProvider(cancellation: ChatRequestCancellation): ToolProvider? = withContext(Dispatchers.IO) {
        val installation = load()?.takeIf { it.usable() } ?: return@withContext null
        GitHubMcpToolProvider(installation.tools) { name, arguments ->
            withContext(Dispatchers.IO) {
                val current = load()?.takeIf { it.usable() }
                    ?: error(tr("GitHub 集成已关闭或登录已失效"))
                GitHubMcpClient(current.credential.accessToken, cancellation = cancellation).callTool(name, arguments)
            }
        }
    }

    companion object {
        private val lock = Any()
        private const val KEY_ALIAS = "irio_mcp_github"
        fun of(context: Context): GitHubMcpStore = GitHubMcpStore(File(context.noBackupFilesDir, "mcp/github.json")) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}
