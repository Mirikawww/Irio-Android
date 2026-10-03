package com.irio

enum class LinkPlatform(val label: String) {
    PHONE("手机"),
    DESKTOP("电脑"),
}

enum class LinkSyncMode(val label: String) {
    COEXIST("共存"),
    OVERWRITE("覆盖"),
}

enum class LinkConnectionState(val label: String) {
    DISCONNECTED("未连接"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
}

data class LinkedPeer(
    val id: String,
    val name: String,
    val platform: LinkPlatform,
    val host: String,
    val port: Int,
    val token: String,
)

data class LinkSettings(
    val enabled: Boolean = false,
    val autoSync: Boolean = false,
    val syncMode: LinkSyncMode = LinkSyncMode.COEXIST,
    val deviceId: String = "",
    val deviceName: String = "",
    val listenPort: Int = LinkProtocol.PORT,
    val peers: List<LinkedPeer> = emptyList(),
)

data class LinkUiState(
    val settings: LinkSettings = LinkSettings(),
    val pairingCode: String = "",
    val pairingHost: String = "",
    val pairingPort: Int = LinkProtocol.PORT,
    val connections: Map<String, LinkConnectionState> = emptyMap(),
    val syncing: Boolean = false,
    val syncProgress: Float = 0f,
    val status: String? = null,
    val pairErrorNonce: Int = 0,
    val pairSuccessNonce: Int = 0,
)
