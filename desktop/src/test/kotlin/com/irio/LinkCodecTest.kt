package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkCodecTest {
    @Test
    fun peerFromBlankNameFallsBackToUnknownDevice() {
        val json = JSONObject().put("id", "abc").put("name", "")
        val peer = LinkCodec.peerFrom(json)
        assertEquals("未知设备", peer.name)
    }

    @Test
    fun peerFromMissingNameFallsBackToUnknownDevice() {
        val json = JSONObject().put("id", "abc")
        val peer = LinkCodec.peerFrom(json)
        assertEquals("未知设备", peer.name)
    }

    @Test
    fun peerFromKeepsRealName() {
        val json = JSONObject().put("id", "abc").put("name", "ELI-AN00")
        assertEquals("ELI-AN00", LinkCodec.peerFrom(json).name)
    }

    @Test
    fun settingsRoundTripPreservesPeerName() {
        val peer = LinkedPeer(
            id = "id-1",
            name = "未知设备",
            platform = LinkPlatform.DESKTOP,
            host = "192.168.31.32",
            port = 17432,
            token = "tok",
        )
        val settings = LinkSettings(deviceId = "me", deviceName = "本机", peers = listOf(peer))
        val restored = LinkCodec.settingsFrom(JSONObject(LinkCodec.settingsJson(settings).toString()))
        assertEquals("未知设备", restored.peers.single().name)
    }

    @Test
    fun refreshPortSkipsTheCurrentPortAndWraps() {
        assertEquals((17433..17452).toList(), LinkProtocol.portCandidates(17432))
        assertEquals(17432, LinkProtocol.portCandidates(17452).first())
        assertEquals(false, LinkProtocol.portCandidates(17440).contains(17440))
        assertEquals(17433, LinkProtocol.portCandidates(80).first())
    }
}
