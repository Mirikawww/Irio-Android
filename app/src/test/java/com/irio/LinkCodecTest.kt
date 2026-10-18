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
    fun refreshPortSkipsTheCurrentPort() {
        val range = (LinkProtocol.PORT..(LinkProtocol.PORT + LinkProtocol.PORT_SPAN))
        assertEquals((range - LinkProtocol.PORT).toSet(), LinkProtocol.portCandidates(LinkProtocol.PORT).toSet())
        val fromMiddle = LinkProtocol.portCandidates(17440)
        assertEquals(range.filter { it != 17440 }.toSet(), fromMiddle.toSet())
        assertEquals(false, fromMiddle.contains(17440))
        assertEquals((range - LinkProtocol.PORT).toSet(), LinkProtocol.portCandidates(80).toSet())
    }
}
