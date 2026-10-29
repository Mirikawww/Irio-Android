package com.irio

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Request-only metadata; never saved into the conversation or inserted into the visible bubble. */
internal class LinuxAttachmentPaths private constructor(val directory: File?) {
    fun decorate(messages: List<ChatRequestMessage>): List<ChatRequestMessage> {
        val root = directory?.toPath() ?: return messages
        return messages.map { message ->
            if (message.role != "user" || message.attachments.isEmpty()) return@map message
            val paths = JSONArray()
            message.attachments.distinctBy { it.filePath }.forEach { attachment ->
                val file = runCatching { File(attachment.filePath).canonicalFile }.getOrNull() ?: return@forEach
                // Share only actual uploads exposed by the narrow attachments-directory bind.
                if (file.toPath().startsWith(root) && file.isFile) {
                    paths.put(JSONObject().put("name", attachment.name).put("path", file.absolutePath))
                }
            }
            if (paths.length() == 0) message else message.copy(text = buildString {
                append(message.text)
                if (isNotEmpty()) append("\n\n")
                append("Local uploaded file paths accessible inside the Linux sandbox (attachment metadata):\n")
                append(paths.toString())
            })
        }
    }

    companion object {
        fun forRequest(context: Context?): LinuxAttachmentPaths {
            if (context == null) return LinuxAttachmentPaths(null)
            val state = LinuxSandboxLayout.of(context.filesDir).stateFile
            val enabled = LinuxSandboxStore.loadAttachmentPaths(state) &&
                LinuxSandboxStore.loadEnabled(state) && LinuxSandboxAccess.hasAllFiles()
            return LinuxAttachmentPaths(if (enabled) File(context.filesDir, "attachments").canonicalFile else null)
        }
    }
}
