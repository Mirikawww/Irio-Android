package com.irio

import android.content.Context
import android.os.Build
import org.json.JSONObject

object LinkStore {
    private const val PREFS = "link_settings"
    private const val KEY = "settings"

    fun load(context: Context): LinkSettings {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val parsed = raw?.let { runCatching { LinkCodec.settingsFrom(JSONObject(it)) }.getOrNull() }
            ?: LinkSettings()
        val deviceId = parsed.deviceId.ifBlank { LinkProtocol.newDeviceId() }
        val deviceName = parsed.deviceName.ifBlank { defaultName() }
        val normalized = parsed.copy(deviceId = deviceId, deviceName = deviceName)
        if (normalized != parsed) save(context, normalized)
        return normalized
    }

    fun save(context: Context, settings: LinkSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, LinkCodec.settingsJson(settings).toString())
            .apply()
    }

    fun forget(context: Context, peerId: String): LinkSettings {
        val next = load(context).let { it.copy(peers = it.peers.filterNot { peer -> peer.id == peerId }) }
        save(context, next)
        return next
    }

    fun upsertPeer(context: Context, peer: LinkedPeer): LinkSettings {
        val current = load(context)
        val peers = current.peers.filterNot { it.id == peer.id } + peer
        val next = current.copy(peers = peers)
        save(context, next)
        return next
    }

    private fun defaultName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isBlank()) tr("手机") else model
    }
}
