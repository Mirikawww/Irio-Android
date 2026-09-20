package com.irio

import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
    var client: OkHttpClient = defaultClient()

    fun resetForTests(client: OkHttpClient = defaultClient()) {
        this.client = client
    }
}
