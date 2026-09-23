package com.irio

import java.io.File

object AppPaths {
    val root: File by lazy {
        val override = System.getenv("IRIO_HOME")?.trim()?.takeIf { it.isNotEmpty() }
        val dir = if (override != null) File(override) else File(windowsAppData(), "Irio")
        dir.apply { mkdirs() }
    }

    val conversations: File
        get() = File(root, "conversations").apply { mkdirs() }

    val attachments: File
        get() = File(root, "attachments").apply { mkdirs() }

    val providersFile: File
        get() = File(root, "providers.json")

    val appearanceFile: File
        get() = File(root, "appearance.json")

    val keyFile: File
        get() = File(root, "provider.key")

    val searchFile: File
        get() = File(root, "search.json")

    val searchKeyFile: File
        get() = File(root, "search.key")

    val toolsFile: File
        get() = File(root, "tools.json")

    val memoryFile: File
        get() = File(root, "memory.json")

    val systemPromptFile: File
        get() = File(root, "system_prompt.json")

    val linkFile: File
        get() = File(root, "link.json")

    private fun windowsAppData(): File {
        val roaming = System.getenv("APPDATA")?.trim()?.takeIf { it.isNotEmpty() }
        return if (roaming != null) File(roaming) else File(System.getProperty("user.home"), "AppData/Roaming")
    }
}
