package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object SpeedtestManager {

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()

        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)

            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            socket?.let { s ->
                try {
                    if (!s.isClosed) {
                        s.close()
                    }
                } catch (closeEx: IOException) {
                }
            }
        }
        return -1
    }

    fun getRemoteIPInfo(): RemoteEndpointInfo? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        // Griffin: the service of the settings first; should it be blocked or slow at the exit, Cloudflare's trace,
        // which nearly every exit reaches.
        val primary = fetchThroughProxy(url, httpPort)?.let(::parseIpApi)
        if (primary?.ipAddress != null) return primary
        val trace = AppConfig.IP_TRACE_URLS.firstNotNullOfOrNull { traceUrl ->
            fetchThroughProxy(traceUrl, httpPort)?.let(::parseCloudflareTrace)
        }
        return trace ?: primary
    }

    private fun fetchThroughProxy(url: String, httpPort: Int): String? =
        HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = SettingsManager.getSocksUsername(),
                proxyPassword = SettingsManager.getSocksPassword()
            )
        )

    /** The exit IP and country an IP service answered with in JSON, by any of the field names the known ones use. */
    internal fun parseIpApi(content: String): RemoteEndpointInfo? {
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.countryCode,
            ipInfo.location?.country_code,
            ipInfo.country
        ).firstOrNull { !it.isNullOrBlank() }

        return RemoteEndpointInfo(
            country = country,
            ipAddress = ip,
        )
    }

    /** Griffin: the exit IP and country of a Cloudflare /cdn-cgi/trace answer, lines of key=value; null without an IP. */
    internal fun parseCloudflareTrace(content: String): RemoteEndpointInfo? {
        val fields = content.lineSequence()
            .mapNotNull { line -> line.trim().split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { (key, value) -> key to value.trim() }
        val ip = fields["ip"]?.takeIf { it.isNotEmpty() } ?: return null
        // XX and T1 are what Cloudflare reports when it cannot tell, or the visitor comes through Tor.
        val country = fields["loc"]?.takeIf { it.length == 2 && it != "XX" && it != "T1" }
        return RemoteEndpointInfo(country = country, ipAddress = ip)
    }
}
