package com.irio

import android.content.Context
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
import java.util.concurrent.ConcurrentHashMap

object LinkRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(LinkUiState())
    val state: StateFlow<LinkUiState> = _state

    private var appContext: Context? = null
    private var server: ServerSocket? = null
    private var serverJob: Job? = null
    private var reconnectJob: Job? = null
    private var started = false
    @Volatile private var pairingCode: String = ""

    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (started) return
        started = true
        val settings = LinkStore.load(app)
        _state.value = LinkUiState(settings = settings)
        if (settings.enabled) startServerLocked(app, settings)
        startReconnectLoop()
    }

    fun refreshPairing() {
        val context = appContext ?: return
        pairingCode = LinkProtocol.newPairingCode()
        val host = LinkProtocol.lanIpv4().orEmpty()
        _state.update {
            it.copy(
                pairingCode = pairingCode,
                pairingHost = host,
                pairingPort = normalizedListenPort(LinkStore.load(context).listenPort),
                settings = LinkStore.load(context),
            )
        }
        if (itEnabled()) startServerLocked(context, LinkStore.load(context))
    }

    fun setEnabled(enabled: Boolean) {
        val context = appContext ?: return
        val settings = LinkStore.load(context).copy(enabled = enabled)
        LinkStore.save(context, settings)
        _state.update { it.copy(settings = settings, status = null) }
        if (enabled) {
            refreshPort()
        } else {
            stopServer()
            _state.update { it.copy(connections = emptyMap()) }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        val context = appContext ?: return
        val settings = LinkStore.load(context).copy(autoSync = enabled)
        LinkStore.save(context, settings)
        _state.update { it.copy(settings = settings) }
        if (enabled) scope.launch { syncConnected(context) }
    }

    fun setSyncMode(mode: LinkSyncMode) {
        val context = appContext ?: return
        val settings = LinkStore.load(context).copy(syncMode = mode)
        LinkStore.save(context, settings)
        _state.update { it.copy(settings = settings) }
    }

    fun pairWithCode(raw: String) {
        val context = appContext ?: return
        val parsed = LinkProtocol.parseCode(raw)
        if (parsed == null) {
            _state.update { it.copy(pairErrorNonce = it.pairErrorNonce + 1, status = null) }
            return
        }
        scope.launch {
            _state.update { it.copy(status = null) }
            runCatching { pair(context, parsed.first, parsed.second, parsed.third) }
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
        val context = appContext ?: return
        scope.launch { syncConnected(context) }
    }

    fun refreshPort() {
        val context = appContext ?: return
        if (!itEnabled()) return
        val avoid = listeningPort()
        stopServer()
        serverJob = scope.launch {
            val bound = runCatching { LinkProtocol.bindFresh(avoid) }.getOrElse {
                _state.update { it.copy(status = tr("无法在局域网端口 %s 监听").format(avoid), statusNonce = it.statusNonce + 1) }
                return@launch
            }
            acceptClients(context, bound)
        }
    }

    fun forget(peerId: String) {
        val context = appContext ?: return
        val settings = LinkStore.forget(context, peerId)
        _state.update {
            it.copy(
                settings = settings,
                connections = it.connections - peerId,
                status = null,
            )
        }
    }

    private fun itEnabled(): Boolean = _state.value.settings.enabled

    private fun startReconnectLoop() {
        if (reconnectJob != null) return
        reconnectJob = scope.launch {
            while (isActive) {
                val context = appContext
                val settings = context?.let { LinkStore.load(it) }
                if (context != null && settings != null && settings.enabled) {
                    settings.peers.forEach { peer ->
                        val connected = _state.value.connections[peer.id] == LinkConnectionState.CONNECTED
                        if (!connected) {
                            runCatching { handshake(context, peer.host, peer.port, token = peer.token, pairingCode = null) }
                        }
                    }
                    if (settings.autoSync) {
                        runCatching { syncConnected(context, report = false) }
                    }
                }
                delay(8_000)
            }
        }
    }

    private fun startServerLocked(context: Context, settings: LinkSettings) {
        if (!settings.enabled) return
        val port = normalizedListenPort(settings.listenPort)
        if (server?.isClosed == false && server?.localPort == port) return
        stopServer()
        serverJob = scope.launch {
            val bound = runCatching { LinkProtocol.bind(port) }.getOrElse {
                _state.update { it.copy(status = tr("无法在局域网端口 %s 监听").format(port), statusNonce = it.statusNonce + 1) }
                return@launch
            }
            acceptClients(context, bound)
        }
    }

    private fun listeningPort(): Int {
        val live = server?.takeIf { !it.isClosed }?.localPort
        if (live != null && live > 0) return live
        return normalizedListenPort(_state.value.settings.listenPort)
    }

    private fun normalizedListenPort(port: Int): Int = if (port in 1..65535) port else LinkProtocol.PORT

    private fun advertisedPort(): Int = listeningPort()

    private suspend fun CoroutineScope.acceptClients(context: Context, bound: ServerSocket) {
        server = bound
        val saved = LinkStore.load(context).copy(listenPort = bound.localPort)
        LinkStore.save(context, saved)
        _state.update { it.copy(settings = saved, pairingPort = bound.localPort, status = null) }
        while (isActive && !bound.isClosed) {
            val socket = runCatching { bound.accept() }.getOrNull() ?: continue
            launch { handleClient(context, socket) }
        }
    }

    private fun stopServer() {
        runCatching { server?.close() }
        server = null
        serverJob?.cancel()
        serverJob = null
    }

    private suspend fun handleClient(context: Context, socket: Socket) {
        socket.soTimeout = 20_000
        socket.tcpNoDelay = true
        try {
            val request = LinkProtocol.readJson(socket)
            when (request.optString("type")) {
                "hello" -> handleHello(context, socket, request)
                "sync-request" -> handleSyncRequest(context, socket, request)
                "sync-push" -> handleSyncPush(context, socket, request)
                else -> LinkProtocol.writeJson(socket, JSONObject().put("type", "error").put("message", tr("未知请求")))
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleHello(context: Context, socket: Socket, request: JSONObject) {
        if (request.optString("hello") != LinkProtocol.HELLO) {
            LinkProtocol.writeJson(socket, JSONObject().put("type", "error").put("message", tr("不是 Irio 设备")))
            return
        }
        val settings = LinkStore.load(context)
        val remoteId = request.optString("deviceId")
        val pairing = request.optString("pairingCode")
        val token = request.optString("token")
        val accepted = when {
            pairing.isNotBlank() && pairing == pairingCode -> true
            token.isNotBlank() && settings.peers.any { it.id == remoteId && it.token == token } -> true
            else -> false
        }
        if (!accepted || remoteId.isBlank()) {
            LinkProtocol.writeJson(socket, JSONObject().put("type", "error").put("message", tr("配对码无效或已刷新")))
            return
        }
        val peerToken = token.ifBlank { randomToken() }
        val peer = LinkedPeer(
            id = remoteId,
            name = request.optString("name").ifBlank { tr("未知设备") },
            platform = runCatching { LinkPlatform.valueOf(request.optString("platform")) }
                .getOrDefault(LinkPlatform.DESKTOP),
            host = socket.inetAddress.hostAddress ?: request.optString("host"),
            port = request.optInt("port", LinkProtocol.PORT),
            token = peerToken,
        )
        val next = LinkStore.upsertPeer(context, peer)
        mark(peer.id, LinkConnectionState.CONNECTED)
        _state.update { it.copy(settings = next) }
        LinkProtocol.writeJson(
            socket,
            JSONObject()
                .put("type", "hello-ok")
                .put("deviceId", settings.deviceId)
                .put("name", settings.deviceName)
                .put("platform", LinkPlatform.PHONE.name)
                .put("token", peerToken)
                .put("port", advertisedPort())
                .put("host", LinkProtocol.lanIpv4().orEmpty()),
        )
        if (next.autoSync) {
            // 自动同步失败（对端离线/未授权）不能炸掉整个 App：标成未连接，等下轮重连。
            scope.launch {
                runCatching { syncPeer(context, peer) }
                    .onFailure { mark(peer.id, LinkConnectionState.DISCONNECTED) }
            }
        }
    }

    private fun handleSyncRequest(context: Context, socket: Socket, request: JSONObject) {
        if (!authorized(context, request)) {
            LinkProtocol.writeJson(socket, JSONObject().put("type", "error").put("message", tr("未授权")))
            return
        }
        LinkProtocol.writeJson(socket, snapshotMessage(context, "sync-data"))
        val reply = LinkProtocol.readJson(socket)
        if (reply.optString("type") == "sync-data") {
            applySnapshot(context, reply)
        }
        LinkProtocol.writeJson(socket, JSONObject().put("type", "sync-ok"))
    }

    private fun handleSyncPush(context: Context, socket: Socket, request: JSONObject) {
        if (!authorized(context, request)) {
            LinkProtocol.writeJson(socket, JSONObject().put("type", "error").put("message", tr("未授权")))
            return
        }
        applySnapshot(context, request)
        LinkProtocol.writeJson(socket, JSONObject().put("type", "sync-ok"))
    }

    private fun authorized(context: Context, request: JSONObject): Boolean {
        val settings = LinkStore.load(context)
        val remoteId = request.optString("deviceId")
        val token = request.optString("token")
        return settings.peers.any { it.id == remoteId && it.token == token }
    }

    private suspend fun pair(context: Context, code: String, host: String, port: Int) {
        handshake(context, host, port, token = null, pairingCode = code)
    }

    private suspend fun handshake(
        context: Context,
        host: String,
        port: Int,
        token: String?,
        pairingCode: String?,
    ) {
        mutex.withLock {
            val settings = LinkStore.load(context)
            val socket = LinkProtocol.connect(host, port)
            try {
                LinkProtocol.writeJson(
                    socket,
                    JSONObject()
                        .put("type", "hello")
                        .put("hello", LinkProtocol.HELLO)
                        .put("deviceId", settings.deviceId)
                        .put("name", settings.deviceName)
                        .put("platform", LinkPlatform.PHONE.name)
                        .put("port", advertisedPort())
                        .put("host", LinkProtocol.lanIpv4().orEmpty())
                        .put("token", token.orEmpty())
                        .put("pairingCode", pairingCode.orEmpty()),
                )
                val reply = LinkProtocol.readJson(socket)
                if (reply.optString("type") != "hello-ok") {
                    error(reply.optString("message").ifBlank { tr("配对失败") })
                }
                val peer = LinkedPeer(
                    id = reply.optString("deviceId"),
                    name = reply.optString("name").ifBlank { tr("未知设备") },
                    platform = runCatching { LinkPlatform.valueOf(reply.optString("platform")) }
                        .getOrDefault(LinkPlatform.DESKTOP),
                    host = reply.optString("host").ifBlank { host },
                    port = reply.optInt("port", port),
                    token = reply.optString("token"),
                )
                val next = LinkStore.upsertPeer(context, peer)
                mark(peer.id, LinkConnectionState.CONNECTED)
                _state.update { it.copy(settings = next) }
                // 配对已成功；随后的首次同步失败只影响连接态，不应把配对报成失败。
                if (next.autoSync) {
                    runCatching { syncPeer(context, peer) }
                        .onFailure { mark(peer.id, LinkConnectionState.DISCONNECTED) }
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private suspend fun syncConnected(context: Context, report: Boolean = true) {
        val settings = LinkStore.load(context)
        val peers = settings.peers
        if (peers.isEmpty()) {
            if (report) _state.update { it.copy(status = tr("还没有已配对设备"), statusNonce = it.statusNonce + 1) }
            return
        }
        if (report) _state.update { it.copy(syncing = true, syncProgress = 0.08f, status = null) }
        var done = 0
        peers.forEach { peer ->
            runCatching { syncPeer(context, peer) }
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

    private suspend fun syncPeer(context: Context, peer: LinkedPeer) {
        if (_state.value.connections[peer.id] != LinkConnectionState.CONNECTED) {
            mark(peer.id, LinkConnectionState.CONNECTING)
        }
        val settings = LinkStore.load(context)
        val socket = LinkProtocol.connect(peer.host, peer.port)
        try {
            if (settings.syncMode == LinkSyncMode.OVERWRITE) {
                LinkProtocol.writeJson(socket, snapshotMessage(context, "sync-push", peer, overwrite = true))
                val reply = LinkProtocol.readJson(socket)
                if (reply.optString("type") != "sync-ok") {
                    error(reply.optString("message").ifBlank { tr("同步失败") })
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
                    error(remote.optString("message").ifBlank { tr("同步失败") })
                }
                applySnapshot(context, remote)
                LinkProtocol.writeJson(socket, snapshotMessage(context, "sync-data", peer, overwrite = false))
                val done = LinkProtocol.readJson(socket)
                if (done.optString("type") != "sync-ok") {
                    error(done.optString("message").ifBlank { tr("同步失败") })
                }
            }
            mark(peer.id, LinkConnectionState.CONNECTED)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun snapshotMessage(
        context: Context,
        type: String,
        peer: LinkedPeer? = null,
        overwrite: Boolean = false,
    ): JSONObject {
        val settings = LinkStore.load(context)
        return JSONObject()
            .put("type", type)
            .put("deviceId", settings.deviceId)
            .put("token", peer?.token.orEmpty())
            .put("overwrite", overwrite)
            .put("conversations", LinkCodec.conversationsJson(
                ConversationStore.loadAll(context).mapNotNull { ConversationStore.load(context, it.id) },
            ))
            .put("lastOpenedId", ConversationStore.loadLastOpenedId(context).orEmpty())
            .put("providers", ProviderStore.snapshot(context))
            .put("stt", SttStore.snapshot(context))
            .put("search", SearchStore.snapshot(context))
    }

    private fun applySnapshot(context: Context, json: JSONObject) {
        val conversations = LinkCodec.conversationsFrom(json.optJSONArray("conversations") ?: org.json.JSONArray())
        val last = json.optString("lastOpenedId").takeIf { it.isNotBlank() }
        val overwrite = json.optBoolean("overwrite") || LinkStore.load(context).syncMode == LinkSyncMode.OVERWRITE
        if (overwrite) {
            ConversationStore.replaceAll(context, conversations, last)
            json.optJSONObject("providers")?.let { ProviderStore.applySnapshot(context, it, true) }
            json.optJSONObject("stt")?.let { SttStore.applySnapshot(context, it, true) }
            json.optJSONObject("search")?.let { SearchStore.applySnapshot(context, it, true) }
        } else {
            ConversationStore.importCoexist(context, conversations)
            json.optJSONObject("providers")?.let { ProviderStore.applySnapshot(context, it, false) }
            json.optJSONObject("stt")?.let { SttStore.applySnapshot(context, it, false) }
            json.optJSONObject("search")?.let { SearchStore.applySnapshot(context, it, false) }
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
