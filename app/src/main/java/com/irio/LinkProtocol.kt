package com.irio

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID

object LinkProtocol {
    const val PORT = 17432
    const val HELLO = "IRIO"
    private const val MAX_FRAME = 32 * 1024 * 1024

    fun newDeviceId(): String = UUID.randomUUID().toString()

    fun newPairingCode(): String {
        val value = SecureRandom().nextInt(1_000_000)
        return value.toString().padStart(6, '0')
    }

    /** 挑选对外可达的局域网 IPv4；跳过 waydroid/docker/veth 等虚拟网卡，避免把对端连不到的地址写进配对。 */
    fun lanIpv4(): String? {
        val virtualMarkers = listOf(
            "waydroid", "docker", "veth", "br-", "virbr", "vmnet", "vboxnet", "tap", "tun",
        )
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .filterNot { nic -> virtualMarkers.any { nic.name.lowercase().startsWith(it) } }
        val addresses = interfaces
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filterNot { it.startsWith("127.") }
        return addresses.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") }
            ?: addresses.firstOrNull()
    }

    fun encodeCode(code: String, host: String, port: Int): String {
        return "$code@$host:$port"
    }

    fun parseCode(raw: String): Triple<String, String, Int>? {
        val value = raw.trim().replace(" ", "")
        val at = value.indexOf('@')
        if (at <= 0) return null
        val code = value.take(at).filter(Char::isDigit)
        if (code.length != 6) return null
        val rest = value.substring(at + 1)
        val colon = rest.lastIndexOf(':')
        if (colon <= 0) return null
        val host = rest.take(colon)
        val port = rest.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isBlank()) return null
        return Triple(code, host, port)
    }

    fun writeJson(socket: Socket, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array()
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    fun readJson(socket: Socket): JSONObject {
        val input = BufferedInputStream(socket.getInputStream())
        val header = ByteArray(4)
        readFully(input, header)
        val size = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        require(size in 1..MAX_FRAME) { "invalid frame" }
        val body = ByteArray(size)
        readFully(input, body)
        return JSONObject(String(body, Charsets.UTF_8))
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) error("closed")
            offset += read
        }
    }

    fun connect(host: String, port: Int, timeoutMs: Int = 4_000): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = 20_000
        return socket
    }

    fun bind(port: Int = PORT): ServerSocket {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        return server
    }

    fun portCandidates(current: Int, span: Int = 20): List<Int> {
        val last = PORT + span
        val avoid = if (current in PORT..last) current else PORT
        return (PORT..last).filter { it != avoid }.shuffled()
    }

    fun bindFresh(current: Int): ServerSocket {
        var error: Exception? = null
        for (port in portCandidates(current)) {
            try {
                return bind(port)
            } catch (e: Exception) {
                error = e
            }
        }
        throw error ?: IllegalStateException("no port")
    }
}

object LinkCodec {
    fun peerJson(peer: LinkedPeer): JSONObject = JSONObject().apply {
        put("id", peer.id)
        put("name", peer.name)
        put("platform", peer.platform.name)
        put("host", peer.host)
        put("port", peer.port)
        put("token", peer.token)
    }

    fun peerFrom(json: JSONObject): LinkedPeer = LinkedPeer(
        id = json.optString("id"),
        name = json.optString("name").ifBlank { tr("未知设备") },
        platform = runCatching { LinkPlatform.valueOf(json.optString("platform")) }
            .getOrDefault(LinkPlatform.PHONE),
        host = json.optString("host"),
        port = json.optInt("port", LinkProtocol.PORT),
        token = json.optString("token"),
    )

    fun settingsJson(settings: LinkSettings): JSONObject = JSONObject().apply {
        put("enabled", settings.enabled)
        put("autoSync", settings.autoSync)
        put("syncMode", settings.syncMode.name)
        put("deviceId", settings.deviceId)
        put("deviceName", settings.deviceName)
        put("listenPort", settings.listenPort)
        put(
            "peers",
            JSONArray().apply { settings.peers.forEach { put(peerJson(it)) } },
        )
    }

    fun settingsFrom(json: JSONObject): LinkSettings {
        val peersJson = json.optJSONArray("peers") ?: JSONArray()
        val peers = buildList {
            for (index in 0 until peersJson.length()) {
                val item = peersJson.optJSONObject(index) ?: continue
                add(peerFrom(item))
            }
        }
        return LinkSettings(
            enabled = json.optBoolean("enabled"),
            autoSync = json.optBoolean("autoSync"),
            syncMode = runCatching { LinkSyncMode.valueOf(json.optString("syncMode")) }
                .getOrDefault(LinkSyncMode.COEXIST),
            deviceId = json.optString("deviceId"),
            deviceName = json.optString("deviceName"),
            listenPort = json.optInt("listenPort", LinkProtocol.PORT).let { port ->
                if (port in 1..65535) port else LinkProtocol.PORT
            },
            peers = peers,
        )
    }

    fun stripAttachments(conversation: Conversation): Conversation {
        return conversation.copy(
            messages = conversation.messages.map { message ->
                message.copy(attachments = emptyList())
            },
        )
    }

    fun conversationsJson(conversations: List<Conversation>): JSONArray {
        return JSONArray().apply {
            conversations.forEach { put(LinkConversationJson.encode(stripAttachments(it))) }
        }
    }

    fun conversationsFrom(array: JSONArray): List<Conversation> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(LinkConversationJson.decode(item))
            }
        }
    }
}

object LinkConversationJson {
    fun encode(conversation: Conversation): JSONObject {
        return JSONObject().apply {
            put("id", conversation.id)
            put("title", conversation.title)
            put("updatedAt", conversation.updatedAt)
            put("pinned", conversation.pinned)
            put("titlePending", conversation.titlePending)
            conversation.tokenUsage?.let { usage ->
                put(
                    "tokenUsage",
                    JSONObject().apply {
                        usage.promptTokens?.let { put("promptTokens", it) }
                        usage.completionTokens?.let { put("completionTokens", it) }
                        usage.totalTokens?.let { put("totalTokens", it) }
                    },
                )
            }
            put(
                "messages",
                JSONArray().apply {
                    conversation.messages.forEach { message ->
                        put(
                            JSONObject().apply {
                                put("id", message.id)
                                put("text", message.text)
                                put("fromUser", message.fromUser)
                                put("isError", message.isError)
                                put("retryGroup", message.groupId)
                                put("selected", message.selected)
                                if (message.hidden) put("hidden", true)
                                if (message.finishedAt > 0L) put("finishedAt", message.finishedAt)
                                message.responseToUserId?.let { put("responseToUserId", it) }
                                message.usage?.let { usage ->
                                    put(
                                        "usage",
                                        JSONObject().apply {
                                            usage.promptTokens?.let { put("promptTokens", it) }
                                            usage.completionTokens?.let { put("completionTokens", it) }
                                            usage.totalTokens?.let { put("totalTokens", it) }
                                        },
                                    )
                                }
                                put(
                                    "process",
                                    JSONArray().apply {
                                        message.process.forEach { event ->
                                            put(
                                                JSONObject().apply {
                                                    put("id", event.id)
                                                    put("kind", event.kind.name)
                                                    put("content", event.content)
                                                    put("millis", event.millis)
                                                    put("pages", event.pages)
                                                    if (event.belowText) put("belowText", true)
                                                    if (event.textOffset > 0) put("textOffset", event.textOffset)
                                                    if (event.toolName.isNotBlank()) put("toolName", event.toolName)
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
    }

    fun decode(json: JSONObject): Conversation {
        val messagesJson = json.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until messagesJson.length()) {
                val item = messagesJson.optJSONObject(index) ?: continue
                add(
                    ChatMessage(
                        id = item.optLong("id"),
                        text = item.optString("text"),
                        fromUser = item.optBoolean("fromUser"),
                        process = processEventsFrom(item),
                        isError = item.optBoolean("isError"),
                        retryGroup = item.optLong("retryGroup").takeIf { it != 0L } ?: item.optLong("id"),
                        selected = if (item.has("selected")) item.optBoolean("selected") else true,
                        finishedAt = item.optLong("finishedAt"),
                        responseToUserId = item.optLong("responseToUserId").takeIf { it != 0L },
                        usage = tokenUsageFrom(item.optJSONObject("usage")),
                        hidden = item.optBoolean("hidden"),
                    )
                )
            }
        }
        return Conversation(
            id = json.optString("id"),
            title = json.optString("title").ifBlank { "新对话" },
            updatedAt = json.optLong("updatedAt"),
            messages = messages,
            pinned = json.optBoolean("pinned"),
            titlePending = json.optBoolean("titlePending"),
            tokenUsage = tokenUsageFrom(json.optJSONObject("tokenUsage"))
                ?: messages.lastOrNull { !it.fromUser && it.usage != null }?.usage,
        )
    }
}

