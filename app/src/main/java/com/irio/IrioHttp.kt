package com.irio

import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal object IrioHttp {
    const val IDLE_CONNECTIONS = 5
    const val KEEP_ALIVE_MINUTES = 5L

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var baseClient: OkHttpClient = defaultClient()

    private val scoped = ConcurrentHashMap<String, OkHttpClient>()
    private val http1Scopes = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var baseHttp1 = false

    // evictAll 会写 TLS close_notify。Android 主线程上做这件事会触发 NetworkOnMainThreadException。
    private val evictThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "irio-connection-evict").apply { isDaemon = true }
    }

    /** 未分区调用（测试、预热默认 scope）走进程级 client。 */
    val client: OkHttpClient
        get() = baseClient

    /**
     * 每个对话独立连接池，并且不和别的对话共享 TLS 会话。
     * newBuilder() 会拷贝同一份 SSLSocketFactory；只换连接池时，新 TCP 仍会用 session ticket
     * 恢复上一条 TLS 会话，粘滞上游就把别的对话留在这条会话上。
     * 同一对话内的预热、后续轮次和工具回合继续复用自己的连接。
     */
    fun clientFor(scope: String): OkHttpClient {
        if (scope.isEmpty()) return baseClient
        val http1 = scope in http1Scopes
        return scoped.computeIfAbsent(scope) { newScopeClient(http1) }
    }

    /**
     * 上游用 HTTP/2 RST_STREAM(PROTOCOL_ERROR) 掐掉还没吐出正文的流时，这条连接不能再复用。
     * 下次 [clientFor] 只协商 HTTP/1.1。
     */
    fun downgradeToHttp1(scope: String) {
        if (scope.isEmpty()) {
            if (baseHttp1) return
            baseHttp1 = true
            val previous = baseClient
            baseClient = previous.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
            closeLater(listOf(previous))
            return
        }
        if (!http1Scopes.add(scope)) return
        val previous = scoped.remove(scope)
        if (previous != null) closeLater(listOf(previous))
    }

    private fun newScopeClient(http1: Boolean): OkHttpClient {
        val trustManager = systemTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        val builder = baseClient.newBuilder()
            .connectionPool(ConnectionPool(IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .sslSocketFactory(sslContext.socketFactory, trustManager)
        if (http1) builder.protocols(listOf(Protocol.HTTP_1_1))
        return builder.build()
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun evictConversation(conversationId: String) {
        if (conversationId.isEmpty()) return
        closeLater(
            listOfNotNull(
                scoped.remove(conversationId),
                scoped.remove("$conversationId#title"),
            ),
        )
    }

    fun evictAllScopes() {
        val clients = ArrayList(scoped.values)
        scoped.clear()
        clients += baseClient
        closeLater(clients)
    }

    private fun closeLater(clients: List<OkHttpClient>) {
        if (clients.isEmpty()) return
        evictThread.execute {
            clients.forEach { client ->
                runCatching { client.connectionPool.evictAll() }
            }
        }
    }

    fun resetForTests(client: OkHttpClient = defaultClient()) {
        http1Scopes.clear()
        baseHttp1 = false
        val previous = scoped.values.toList()
        scoped.clear()
        previous.forEach { it.connectionPool.evictAll() }
        baseClient.connectionPool.evictAll()
        baseClient = client
    }
}
