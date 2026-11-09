package com.irio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** Additive Link payload; device identity and active pairing are never portable. */
internal object LinkPortableData {
    fun snapshot(context: Context,
        github: GitHubMcpStore = GitHubMcpStore.of(context),
        todoist: TodoistMcpStore = TodoistMcpStore.of(context),
    ): JSONObject {
        val preferences = BackupPreferences.snapshot(context).apply { remove("link_settings") }
        val files = JSONObject()
        fun collect(directory: File, prefix: String) {
            directory.listFiles().orEmpty().forEach { file ->
                val path = prefix + file.name
                if (Files.isSymbolicLink(file.toPath()) || !allowed(path)) return@forEach
                if (file.isDirectory) collect(file, "$path/")
                else files.put(path, Base64.getEncoder().encodeToString(file.readBytes()))
            }
        }
        collect(context.filesDir, "")
        return JSONObject().put("version", 1).put("preferences", preferences).put("files", files)
            .put("githubMcp", github.snapshot()).put("todoistMcp", todoist.snapshot())
    }

    fun prepare(context: Context, json: JSONObject): Prepared {
        require(json.get("version") == 1) { "Unsupported Link portable data version" }
        val preferences = JSONObject(json.getJSONObject("preferences").toString()).apply { remove("link_settings") }
        val decodedPreferences = BackupPreferences.decode(preferences)
        val github = if (json.has("githubMcp")) json.getJSONObject("githubMcp").also(GitHubMcpStore::validateSnapshot) else null
        val todoist = if (json.has("todoistMcp")) json.getJSONObject("todoistMcp").also(TodoistMcpStore::validateSnapshot) else null
        val files = json.getJSONObject("files")
        val decodedFiles = files.keys().asSequence().associateWith { path ->
            safeTarget(context, path)
            val bytes = Base64.getDecoder().decode(files.getString(path))
            if (path in setOf("memory.json", "system_prompt.json", "linux-sandbox/state.json")) {
                JSONObject(bytes.toString(Charsets.UTF_8))
            }
            bytes
        }
        return Prepared(decodedPreferences, decodedFiles, github, todoist)
    }

    internal class Prepared(
        private val preferences: Map<String, Map<String, Any>>,
        private val files: Map<String, ByteArray>,
        private val githubSnapshot: JSONObject?,
        private val todoistSnapshot: JSONObject?,
    ) {
        fun apply(context: Context, overwrite: Boolean,
            github: GitHubMcpStore = GitHubMcpStore.of(context),
            todoist: TodoistMcpStore = TodoistMcpStore.of(context),
        ) {
            // Prepare merged state before any preference or file write, just like backup import.
            val writes = files.mapNotNull { (path, bytes) ->
                val target = safeTarget(context, path)
                when {
                    !overwrite && target.isFile && path == "linux-sandbox/state.json" -> {
                        val local = JSONObject(target.readText())
                        val incoming = JSONObject(bytes.toString(Charsets.UTF_8))
                        incoming.keys().forEach { key -> if (!local.has(key)) local.put(key, incoming.get(key)) }
                        target to local.toString().toByteArray(Charsets.UTF_8)
                    }
                    overwrite || !target.exists() -> target to bytes
                    else -> null
                }
            }
            writes.forEach { (target, bytes) -> target.parentFile?.mkdirs(); target.writeBytes(bytes) }
            if (overwrite && "profile/avatar.png" !in files) safeTarget(context, "profile/avatar.png").delete()
            BackupPreferences.apply(context, preferences, overwrite)
            githubSnapshot?.let { github.applySnapshot(it, overwrite) }
            todoistSnapshot?.let { todoist.applySnapshot(it, overwrite) }
            ThemeStore.reload(context)
            ProfileStore.reload(context)
        }
    }

    private fun allowed(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
        ':' !in path && '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        path != "conversations" && !path.startsWith("conversations/") && BackupArchive.allowedFile("files/$path")

    private fun safeTarget(context: Context, path: String): File {
        require(allowed(path)) { "Invalid Link portable file path" }
        val root = context.filesDir
        val target = File(root, path)
        require(target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) && !target.isDirectory) { "Invalid Link portable file target" }
        var current: File? = target
        while (current != null && current != root) {
            require(!Files.isSymbolicLink(current.toPath())) { "Link portable files cannot follow symlinks" }
            if (current != target) require(!current.isFile) { "Invalid Link portable file parent" }
            current = current.parentFile
        }
        return target
    }
}
