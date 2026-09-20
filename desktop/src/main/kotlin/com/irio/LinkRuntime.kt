package com.irio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

object LinkRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(LinkUiState())
    val state: StateFlow<LinkUiState> = _state

    private var server: ServerSocket? = null
    private var serverJob: Job? = null
    private var reconnectJob: Job? = null
    private var started = false
    @Volatile private var pairingCode: String = ""

    fun ensure() {
        if (started) return
        started = true
        val settings = LinkStore.load()
        _state.value = LinkUiState(settings = settings)
        if (settings.enabled) startServer(settings)
        startReconnectLoop()
    }

    fun refreshPairing() {
        pairingCode = LinkProtocol.newPairingCode()
        val host = LinkProtocol.lanIpv4().orEmpty()
        _state.update {
            it.copy(
                pairingCode = pairingCode,
                pairingHost = host,
                pairingPort = LinkProtocol.PORT,
                settings = LinkStore.load(),
            )
        }
        if (LinkStore.load().enabled) startServer(LinkStore.load())
    }

    fun setEnabled(enabled: Boolean) {
        val settings = LinkStore.load().copy(enabled = enabled)
        LinkStore.save(settings)
        _state.update { it.copy(settings = settings, status = null) }
        if (enabled) {
            startServer(settings)
        } else {
            stopServer()
            _state.update { it.copy(connections = emptyMap()) }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        val settings = LinkStore.load().copy(autoSync = enabled)
        LinkStore.save(settings)
        _state.update { it.copy(settings = settings) }
        if (enabled) scope.launch { syncConnected() }
    }

    fun setSyncMode(mode: LinkSyncMode) {
        val settings = LinkStore.load().copy(syncMode = mode)
        LinkStore.save(settings)
        _state.update { it.copy(settings = settings) }
    }

    fun pairWithCode(raw: String) {
        val parsed = LinkProtocol.parseCode(raw)
        if (parsed == null) {
            _state.update { it.copy(pairErrorNonce = it.pairErrorNonce + 1, status = null) }
            return
        }
        scope.launch {
            _state.update { it.copy(status = null) }
            runCatching { pair(parsed.first, parsed.second, parsed.third) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            status = null,
                            pairErrorNonce = 0,
                            pairSuccessNonce = it.pairSuccessNonce + 1,
                        )
                    }
                }
                .onFailure {
                    _state.update { it.copy(pairErrorNonce = it.pairErrorNonce + 1, status = null) }
                }
        }
    }

    fun syncNow() {
        scope.launch { syncConnected() }
    }

    fun forget(peerId: String) {
        val settings = LinkStore.forget(peerId)
        _state.update {
            it.copy(
                settings = settings,
                connections = it.connections - peerId,
                status = null,
            )
        }
    }

    private fun startReconnectLoop() {
        if (reconnectJob != null) return
        reconnectJob = scope.launch {
            while (isActive) {
                val settings = LinkStore.load()
                if (settings.enabled) {
                    settings.peers.forEach { peer ->
                        val connected = _state.value.connections[peer.id] == LinkConnectionState.CONNECTED
                        if (!connected) {
                            runCatching { handshake(peer.host, peer.port, token = peer.token, pairingCode = null) }
                        }
                    }
                    if (settings.autoSync) {
                        runCatching { syncConnected(report = false) }
                    }
                }
                delay(8_000)
            }
        }
    }

    private fun startServer(settings: LinkSettings) {
        if (!settings.enabled) return
        if (server?.isClosed == false) return
        stopServer()
        serverJob = scope.launch {
            val bound = runCatching { LinkProtocol.bind() }.getOrElse {
                _state.update { it.copy(status = "无法在局域网端口 ${LinkProtocol.PORT} 监听") }
                return@launch
            }
            server = bound
            while (isActive && !bound.isClosed) {
                val socket = runCatching { bound.accept() }.getOrNull() ?: continue
                launch { handleClient(socket) }
            }
        }
    }

    private fun stopServer() {
        runCatching { server?.close() }
        server = null
        serverJob?.cancel()
        serverJob = null
    }

    private suspend fun handleClient(socket: Socket) {
        socket.soTimeout = 20_000
        socket.tcpNoDelay = true
        try {
            val request = LinkProtocol.readJson(socket)
            when (request.optString("type")) {
                "hello" -> handleHello(socket, request)
                "sync-request" -> handleSyncRequest(socket, request)
                "sync-push" -> handleSyncPush(socket, request)
                else -> LinkProtocol.writeJson(socket, JSONObject().put("type", "error").put("message", "未知请求"))
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleHello(socket: Socket, request: JSONObject) {
        if (request.optString("hello") != LinkProtocol.HELLO) {
            LinkProtocol.writeJson(socket, JSONObject().put("type", "error").put("message", "不是 Irio 设备"))
            return
        }
        val settings = LinkStore.load()
        val remoteId = request.optString("deviceId")
        val pairing = request.optString("pairingCode")
        val token = request.optString("token")
        val accepted = when {
            pairing.isNotBlank() && pairing == pairingCode -> true
            token.isNotBlank() && settings.peers.any { it.id == remoteId && it.token == token } -> true
            else -> false
        }
        if (!accepted || remoteId.isBlank()) {
            LinkProtocol.writeJson(socket, JSONObject().put("type", "error").put("message", "配对码无效或已刷新"))
            return
        }
        val peerToken = token.ifBlank { randomToken() }
        val peer = LinkedPeer(
            id = remoteId,
            name = request.optString("name").ifBlank { "未知设备" },
            platform = runCatching { LinkPlatform.valueOf(request.optString("platform")) }
                .getOrDefault(LinkPlatform.PHONE),
            host = socket.inetAddress.hostAddress ?: request.optString("host"),
            port = request.optInt("port", LinkProtocol.PORT),
            token = peerToken,
        )
        val next = LinkStore.upsertPeer(peer)
        mark(peer.id, LinkConnectionState.CONNECTED)
        _state.update { it.copy(settings = next) }
        LinkProtocol.writeJson(
            socket,
            JSONObject()
                .put("type", "hello-ok")
                .put("deviceId", settings.deviceId)
                .put("name", settings.deviceName)
                .put("platform", LinkPlatform.DESKTOP.name)
                .put("token", peerToken)
                .put("port", LinkProtocol.PORT)
                .put("host", LinkProtocol.lanIpv4().orEmpty()),
        )
        if (next.autoSync) {
            // 自动同步失败（对端离线/未授权）不能炸掉整个 App：标成未连接，等下轮重连。
            scope.launch {
                runCatching { syncPeer(peer) }
                    .onFailure { mark(peer.id, LinkConnectionState.DISCONNECTED) }
            }
        }
    }

    private fun handleSyncRequest(socket: Socket, request: JSONObject) {
        if (!authorized(request)) {
            LinkProtocol.writeJson(socket, JSONObject().put("type", "error").put("message", "未授权"))
            return
        }
        LinkProtocol.writeJson(socket, snapshotMessage("sync-data"))
        val reply = LinkProtocol.readJson(socket)
        if (reply.optString("type") == "sync-data") {
            applySnapshot(reply)
        }
        LinkProtocol.writeJson(socket, JSONObject().put("type", "sync-ok"))
    }

    private fun handleSyncPush(socket: Socket, request: JSONObject) {
        if (!authorized(request)) {
            LinkProtocol.writeJson(socket, JSONObject().put("type", "error").put("message", "未授权"))
            return
        }
        applySnapshot(request)
        LinkProtocol.writeJson(socket, JSONObject().put("type", "sync-ok"))
    }

    private fun authorized(request: JSONObject): Boolean {
        val settings = LinkStore.load()
        val remoteId = request.optString("deviceId")
        val token = request.optString("token")
        return settings.peers.any { it.id == remoteId && it.token == token }
    }

    private suspend fun pair(code: String, host: String, port: Int) {
        handshake(host, port, token = null, pairingCode = code)
    }

    private suspend fun handshake(
        host: String,
        port: Int,
        token: String?,
        pairingCode: String?,
    ) {
        mutex.withLock {
            val settings = LinkStore.load()
            val socket = LinkProtocol.connect(host, port)
            try {
                LinkProtocol.writeJson(
                    socket,
                    JSONObject()
                        .put("type", "hello")
                        .put("hello", LinkProtocol.HELLO)
                        .put("deviceId", settings.deviceId)
                        .put("name", settings.deviceName)
                        .put("platform", LinkPlatform.DESKTOP.name)
                        .put("port", LinkProtocol.PORT)
                        .put("host", LinkProtocol.lanIpv4().orEmpty())
                        .put("token", token.orEmpty())
                        .put("pairingCode", pairingCode.orEmpty()),
                )
                val reply = LinkProtocol.readJson(socket)
                if (reply.optString("type") != "hello-ok") {
                    error(reply.optString("message").ifBlank { "配对失败" })
                }
                val peer = LinkedPeer(
                    id = reply.optString("deviceId"),
                    name = reply.optString("name").ifBlank { "未知设备" },
                    platform = runCatching { LinkPlatform.valueOf(reply.optString("platform")) }
                        .getOrDefault(LinkPlatform.PHONE),
                    host = reply.optString("host").ifBlank { host },
                    port = reply.optInt("port", port),
                    token = reply.optString("token"),
                )
                val next = LinkStore.upsertPeer(peer)
                mark(peer.id, LinkConnectionState.CONNECTED)
                _state.update { it.copy(settings = next) }
                // 配对已成功；随后的首次同步失败只影响连接态，不应把配对报成失败。
                if (next.autoSync) {
                    runCatching { syncPeer(peer) }
                        .onFailure { mark(peer.id, LinkConnectionState.DISCONNECTED) }
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private suspend fun syncConnected(report: Boolean = true) {
        val settings = LinkStore.load()
        val peers = settings.peers
        if (peers.isEmpty()) {
            if (report) _state.update { it.copy(status = "还没有已配对设备") }
            return
        }
        if (report) _state.update { it.copy(syncing = true, syncProgress = 0.08f, status = null) }
        var done = 0
        peers.forEach { peer ->
            runCatching { syncPeer(peer) }
                .onFailure { mark(peer.id, LinkConnectionState.DISCONNECTED) }
            done += 1
            if (report) {
                _state.update { it.copy(syncProgress = done.toFloat() / peers.size) }
            }
        }
        if (report) {
            _state.update { it.copy(syncing = false, syncProgress = 0f, status = null) }
        }
    }

    private suspend fun syncPeer(peer: LinkedPeer) {
        if (_state.value.connections[peer.id] != LinkConnectionState.CONNECTED) {
            mark(peer.id, LinkConnectionState.CONNECTING)
        }
        val settings = LinkStore.load()
        val socket = LinkProtocol.connect(peer.host, peer.port)
        try {
            if (settings.syncMode == LinkSyncMode.OVERWRITE) {
                LinkProtocol.writeJson(socket, snapshotMessage("sync-push", peer, overwrite = true))
                val reply = LinkProtocol.readJson(socket)
                if (reply.optString("type") != "sync-ok") {
                    error(reply.optString("message").ifBlank { "同步失败" })
                }
            } else {
                LinkProtocol.writeJson(
                    socket,
                    JSONObject()
                        .put("type", "sync-request")
                        .put("deviceId", settings.deviceId)
                        .put("token", peer.token),
                )
                val remote = LinkProtocol.readJson(socket)
                if (remote.optString("type") != "sync-data") {
                    error(remote.optString("message").ifBlank { "同步失败" })
                }
                applySnapshot(remote)
                LinkProtocol.writeJson(socket, snapshotMessage("sync-data", peer, overwrite = false))
                val done = LinkProtocol.readJson(socket)
                if (done.optString("type") != "sync-ok") {
                    error(done.optString("message").ifBlank { "同步失败" })
                }
            }
            mark(peer.id, LinkConnectionState.CONNECTED)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun snapshotMessage(
        type: String,
        peer: LinkedPeer? = null,
        overwrite: Boolean = false,
    ): JSONObject {
        val settings = LinkStore.load()
        return JSONObject()
            .put("type", type)
            .put("deviceId", settings.deviceId)
            .put("token", peer?.token.orEmpty())
            .put("overwrite", overwrite)
            .put("conversations", LinkCodec.conversationsJson(ConversationStore.loadAll()))
            .put("lastOpenedId", ConversationStore.loadLastOpenedId().orEmpty())
            .put("providers", ProviderStore.snapshot())
            .put("search", SearchStore.snapshot())
    }

    private fun applySnapshot(json: JSONObject) {
        val conversations = LinkCodec.conversationsFrom(json.optJSONArray("conversations") ?: org.json.JSONArray())
        val last = json.optString("lastOpenedId").takeIf { it.isNotBlank() }
        val overwrite = json.optBoolean("overwrite") || LinkStore.load().syncMode == LinkSyncMode.OVERWRITE
        if (overwrite) {
            ConversationStore.replaceAll(conversations, last)
            json.optJSONObject("providers")?.let { ProviderStore.applySnapshot(it, true) }
            json.optJSONObject("search")?.let { SearchStore.applySnapshot(it, true) }
        } else {
            ConversationStore.importCoexist(conversations)
            json.optJSONObject("providers")?.let { ProviderStore.applySnapshot(it, false) }
            json.optJSONObject("search")?.let { SearchStore.applySnapshot(it, false) }
        }
    }

    private fun mark(peerId: String, state: LinkConnectionState) {
        _state.update { it.copy(connections = it.connections + (peerId to state)) }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
