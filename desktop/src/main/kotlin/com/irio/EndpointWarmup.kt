package com.irio

import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean

internal object EndpointWarmup {
    private val inFlightWarm = AtomicBoolean(false)

    fun shouldWarm(provider: ProviderConfig?, inFlight: Boolean): Boolean {
        if (inFlight) return false
        val url = provider?.endpointUrl?.trim().orEmpty()
        return url.startsWith("http://") || url.startsWith("https://")
    }

    fun warmBlocking(provider: ProviderConfig): Boolean {
        if (!shouldWarm(provider, inFlight = false)) return false
        if (!inFlightWarm.compareAndSet(false, true)) return false
        return try {
            val origin = ProviderEndpoints.origin(provider.endpointUrl) + "/"
            val headOk = exchange(
                Request.Builder().url(origin).head().header("Connection", "keep-alive").build(),
            )
            if (headOk == true) return true
            if (headOk == false) {
                exchange(
                    Request.Builder().url(origin).get().header("Connection", "keep-alive").build(),
                ) != null
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            inFlightWarm.set(false)
        }
    }

    /**
     * true = 握手完成且无需 fallback（任意完成的 HTTP 响应，除 405/501）
     * false = 需要 GET fallback（405/501，或 HEAD 抛 IOException）
     * null = 连 origin 都构不出来
     */
    private fun exchange(request: Request): Boolean? {
        return try {
            IrioHttp.client.newCall(request).execute().use { response ->
                when (response.code) {
                    405, 501 -> if (request.method == "HEAD") false else true
                    else -> true
                }
            }
        } catch (_: Exception) {
            if (request.method == "HEAD") false else null
        }
    }
}
