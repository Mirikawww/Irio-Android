package com.irio

import org.json.JSONObject
import java.net.InetAddress

object LinkStore {
    fun load(): LinkSettings {
        val file = AppPaths.linkFile
        val parsed = if (file.exists()) {
            runCatching { LinkCodec.settingsFrom(JSONObject(file.readText())) }.getOrNull()
        } else {
            null
        } ?: LinkSettings()
        val deviceId = parsed.deviceId.ifBlank { LinkProtocol.newDeviceId() }
        val deviceName = parsed.deviceName.ifBlank { defaultName() }
        val normalized = parsed.copy(deviceId = deviceId, deviceName = deviceName)
        if (normalized != parsed) save(normalized)
        return normalized
    }

    fun save(settings: LinkSettings) {
        AppPaths.linkFile.writeText(LinkCodec.settingsJson(settings).toString())
    }

    fun forget(peerId: String): LinkSettings {
        val next = load().let { it.copy(peers = it.peers.filterNot { peer -> peer.id == peerId }) }
        save(next)
        return next
    }

    fun upsertPeer(peer: LinkedPeer): LinkSettings {
        val current = load()
        val peers = current.peers.filterNot { it.id == peer.id } + peer
        val next = current.copy(peers = peers)
        save(next)
        return next
    }

    private fun defaultName(): String {
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull().orEmpty()
        return host.ifBlank { tr("电脑") }
    }
}
