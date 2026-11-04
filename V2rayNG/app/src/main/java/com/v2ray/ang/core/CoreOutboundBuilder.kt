package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/**
 * Centralizes ProfileItem -> OutboundBean conversion.
 * Most protocol builders mirror the previous *Fmt.toOutbound behavior.
 */
object CoreOutboundBuilder {

    /** Dispatches a profile to protocol-specific outbound builder. */
    fun convert(profileItem: ProfileItem): OutboundBean? {
        val outbound = when (profileItem.configType) {
            EConfigType.VMESS -> toOutboundVmess(profileItem)
            EConfigType.SHADOWSOCKS -> toOutboundShadowsocks(profileItem)
            EConfigType.SOCKS -> toOutboundSocks(profileItem)
            EConfigType.VLESS -> toOutboundVless(profileItem)
            EConfigType.TROJAN -> toOutboundTrojan(profileItem)
            EConfigType.WIREGUARD -> toOutboundWireguard(profileItem)
            EConfigType.HYSTERIA2 -> toOutboundHysteria2(profileItem)
            EConfigType.HTTP -> toOutboundHttp(profileItem)
            else -> null
        }

        outbound ?: return null
        applyDialMode(outbound, profileItem)
        applyTargetStrategy(outbound, profileItem)
        val ret = updateOutboundWithGlobalSettings(outbound)
        if (!ret) return null
        return outbound
    }

    /**
     * Copies the profile dialMode into streamSettings.sockopt.dialMode.
     *
     * Only the dialMode field is written, so sockopt options set elsewhere
     * (dialerProxy, domainStrategy, happyEyeballs, ...) are kept.
     */
    internal fun applyDialMode(outbound: OutboundBean, profileItem: ProfileItem) = applyDialMode(outbound, profileItem.dialMode)

    /** [applyDialMode] with the dialMode itself. */
    internal fun applyDialMode(outbound: OutboundBean, mode: String?) {
        val dialMode = mode.nullIfBlank() ?: return
        if (outbound.streamSettings == null) {
            // wireguard outbounds are built without streamSettings, but Xray still dials
            // their endpoint through the system dialer with streamSettings.sockopt.
            // Add one that only carries sockopt: network stays unset as there is no transport.
            outbound.streamSettings = OutboundBean.StreamSettingsBean(network = null)
        }
        outbound.ensureSockopt().dialMode = dialMode
    }

    /**
     * Copies the profile targetStrategy onto the outbound, or the profile's default when it stores none, see
     * [defaultTargetStrategy]. AsIs, Xray's default, leaves the field out.
     */
    internal fun applyTargetStrategy(outbound: OutboundBean, profileItem: ProfileItem) {
        val strategy = profileItem.targetStrategy?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultTargetStrategy(profileItem)
        outbound.targetStrategy = strategy.takeUnless { it.equals(AppConfig.TARGET_STRATEGY_AS_IS, ignoreCase = true) }
    }

    /**
     * The targetStrategy of [profile] when it stores none: AsIs, Xray's own, which passes names on as they are.
     */
    @Suppress("UNUSED_PARAMETER")
    fun defaultTargetStrategy(profile: ProfileItem): String = AppConfig.TARGET_STRATEGY_AS_IS

    /**
     * PattNG: the targetStrategy of the outbounds of a proxy chain, [hops] its profiles beside their outbounds, tagged,
     * the first carrying your traffic. A hop after the first carries the connection of the hop before it to that
     * hop's server, whose name a lookup by Xray would ask of the DNS that reaches out through this same chain: it
     * passes names on as they are unless its profile sets a targetStrategy.
     */
    internal fun applyChainTargetStrategies(hops: List<Pair<ProfileItem, OutboundBean>>) {
        hops.forEachIndexed { index, (profile, outbound) ->
            if (index > 0 && profile.targetStrategy.isNullOrBlank()) outbound.targetStrategy = null
        }
    }

    /**
     * PattNG: a mux concurrency setting, [text], as Xray takes it: the whole number written, or [default] when the field,
     * which takes any text, holds none. A blank field left the outbound unbuilt, and the profile's traffic went out
     * directly while the app showed it connected.
     */
    internal fun muxConcurrency(text: String?, default: Int): Int = Utils.parseInt(text?.trim(), default)

    /** Applies global outbound options (mux, protocol-specific tweaks, etc.). */
    private fun updateOutboundWithGlobalSettings(outbound: OutboundBean): Boolean {
        try {
            var muxEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED, false)
            val protocol = outbound.protocol
            if (protocol.equals(EConfigType.SHADOWSOCKS.name, true)
                || protocol.equals(EConfigType.SOCKS.name, true)
                || protocol.equals(EConfigType.HTTP.name, true)
                || protocol.equals(EConfigType.TROJAN.name, true)
                || protocol.equals(EConfigType.WIREGUARD.name, true)
                || protocol.equals(EConfigType.HYSTERIA2.name, true)
                || protocol.equals(EConfigType.HYSTERIA.name, true)
            ) {
                muxEnabled = false
            } else if (outbound.streamSettings?.network == NetworkType.XHTTP.type) {
                muxEnabled = false
            }

            if (muxEnabled) {
                outbound.mux?.enabled = true
                outbound.mux?.concurrency = muxConcurrency(MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY, "8"), 8)
                outbound.mux?.xudpConcurrency = muxConcurrency(
                    MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY),
                    AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY.toInt(),
                )
                outbound.mux?.xudpProxyUDP443 = MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_XUDP_QUIC, "reject")
                if (protocol.equals(EConfigType.VLESS.name, true) && outbound.settings?.flow?.isNotEmpty() == true) {
                    outbound.mux?.concurrency = -1
                }
            } else {
                outbound.mux?.enabled = false
                outbound.mux?.concurrency = -1
            }

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update outbound with global settings", e)
            return false
        }
        return true
    }

    /** Creates an initial outbound template for a protocol type. */
    fun createInitOutbound(configType: EConfigType): OutboundBean? {
        return when (configType) {
            EConfigType.VMESS,
            EConfigType.VLESS,
            EConfigType.SHADOWSOCKS,
            EConfigType.SOCKS,
            EConfigType.HTTP,
            EConfigType.TROJAN -> OutboundBean(
                protocol = configType.name.lowercase(),
                settings = OutboundBean.OutSettingsBean(),
                streamSettings = OutboundBean.StreamSettingsBean()
            )

            EConfigType.WIREGUARD -> OutboundBean(
                protocol = configType.name.lowercase(),
                settings = OutboundBean.OutSettingsBean(
                    secretKey = "",
                    peers = listOf(OutboundBean.OutSettingsBean.WireGuardBean())
                )
            )

            EConfigType.HYSTERIA,
            EConfigType.HYSTERIA2 -> OutboundBean(
                protocol = EConfigType.HYSTERIA.name.lowercase(),
                settings = OutboundBean.OutSettingsBean(),
                streamSettings = OutboundBean.StreamSettingsBean()
            )

            else -> null
        }
    }

    // ── Per-protocol builders — implementations are identical to each *Fmt.toOutbound ──

    private fun toOutboundVmess(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.VMESS)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            settings.id = profileItem.password.orEmpty()
            settings.security = profileItem.method
        }

        val sni = outboundBean?.streamSettings?.let {
            populateTransportSettings(it, profileItem)
        }

        outboundBean?.streamSettings?.let {
            populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }

    private fun toOutboundVless(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.VLESS)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            settings.id = profileItem.password.orEmpty()
            settings.encryption = profileItem.method
            settings.flow = profileItem.flow
        }

        val sni = outboundBean?.streamSettings?.let {
            populateTransportSettings(it, profileItem)
        }

        outboundBean?.streamSettings?.let {
            populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }

    private fun toOutboundShadowsocks(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.SHADOWSOCKS)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            settings.password = profileItem.password
            settings.method = profileItem.method
        }

        val sni = outboundBean?.streamSettings?.let {
            populateTransportSettings(it, profileItem)
        }

        outboundBean?.streamSettings?.let {
            populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }

    private fun toOutboundTrojan(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.TROJAN)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            settings.password = profileItem.password
            settings.flow = profileItem.flow
        }

        val sni = outboundBean?.streamSettings?.let {
            populateTransportSettings(it, profileItem)
        }

        outboundBean?.streamSettings?.let {
            populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }

    private fun toOutboundSocks(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.SOCKS)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            if (profileItem.username.isNotNullEmpty()) {
                settings.user = profileItem.username.orEmpty()
                settings.pass = profileItem.password.orEmpty()
            }
        }

        return outboundBean
    }

    private fun toOutboundHttp(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.HTTP)

        outboundBean?.settings?.let { settings ->
            settings.address = getServerAddress(profileItem)
            settings.port = profileItem.serverPort.orEmpty().toInt()
            if (profileItem.username.isNotNullEmpty()) {
                settings.user = profileItem.username.orEmpty()
                settings.pass = profileItem.password.orEmpty()
            }
        }

        return outboundBean
    }

    /**
     * PattNG: the remote DNS servers of a WireGuard outbound, and the entries of [remoteDNS] left out. The core parses
     * each server with Go's netip.ParseAddr and stops the whole process on anything it refuses, as a host name, an
     * address with a port, or "local", which it takes no more; so only what it takes goes, see [isNetipAddress], an
     * IPv6 address without its brackets, the IPv4 ones alone when IPv6 is off, each its own entry; and when none is
     * left, the default ones, see [AppConfig.WIREGUARD_LOCAL_REMOTE_DNS], split as well.
     */
    internal fun wireguardRemoteDns(remoteDNS: String?, ipv6Enabled: Boolean): Pair<List<String>, List<String>> {
        fun entries(list: String?) = list?.split(",").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        fun address(entry: String): String? {
            val bare = if (entry.startsWith("[") && entry.endsWith("]")) entry.substring(1, entry.length - 1) else entry
            return bare.takeIf(::isNetipAddress)
        }

        val read = entries(remoteDNS).map { entry -> entry to address(entry) }
        val leftOut = read.filter { it.second == null }.map { it.first }
        val usable = read.mapNotNull { it.second }.filter { ipv6Enabled || !it.contains(":") }
        val servers = usable.ifEmpty { entries(AppConfig.WIREGUARD_LOCAL_REMOTE_DNS).filter { ipv6Enabled || !it.contains(":") } }
        return servers to leftOut
    }

    /**
     * PattNG: whether Go's netip.ParseAddr takes [text], as the core reads a WireGuard remoteDNS server, see
     * [wireguardRemoteDns]: IPv4 in four decimal fields without leading zeros, or IPv6 with one :: at most, standing for
     * one field or more, and a dotted IPv4 tail and a zone allowed; no brackets. Follows net/netip's ParseAddr,
     * parseIPv4Fields and parseIPv6 step by step.
     */
    internal fun isNetipAddress(text: String): Boolean {
        for (c in text) {
            when (c) {
                '.' -> return isNetipIpv4(text)
                ':' -> return isNetipIpv6(text)
                '%' -> return false
            }
        }
        return false
    }

    private fun isNetipIpv4(text: String): Boolean {
        val fields = text.split('.')
        return fields.size == 4 && fields.all { field ->
            field.isNotEmpty() && field.length <= 3 && field.all { it in '0'..'9' } &&
                (field.length == 1 || field[0] != '0') && field.toInt() <= 255
        }
    }

    private fun isNetipIpv6(text: String): Boolean {
        var s = text
        val zoneAt = s.indexOf('%')
        if (zoneAt >= 0) {
            if (zoneAt == s.length - 1) return false
            s = s.substring(0, zoneAt)
        }
        var ellipsis = -1
        if (s.startsWith("::")) {
            ellipsis = 0
            s = s.substring(2)
            if (s.isEmpty()) return true
        }
        var i = 0
        while (i < 16) {
            var off = 0
            while (off < s.length && isHexDigit(s[off])) {
                if (off > 3) return false
                off++
            }
            if (off == 0) return false
            if (off < s.length && s[off] == '.') {
                if (ellipsis < 0 && i != 12) return false
                if (i + 4 > 16) return false
                if (!isNetipIpv4(s)) return false
                s = ""
                i += 4
                break
            }
            i += 2
            s = s.substring(off)
            if (s.isEmpty()) break
            if (s[0] != ':' || s.length == 1) return false
            s = s.substring(1)
            if (s[0] == ':') {
                if (ellipsis >= 0) return false
                ellipsis = i
                s = s.substring(1)
                if (s.isEmpty()) break
            }
        }
        if (s.isNotEmpty()) return false
        return if (i < 16) ellipsis >= 0 else ellipsis < 0
    }

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun toOutboundWireguard(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.WIREGUARD)

        val rawAddresses = profileItem.localAddress
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { null }
            ?: listOf(AppConfig.WIREGUARD_LOCAL_ADDRESS_V4)

        val ipv6Enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, AppConfig.DEFAULT_IPV6_ENABLED)
        val addresses = if (ipv6Enabled) {
            rawAddresses
        } else {
            val ipv4Addresses = rawAddresses.filter { !it.contains(":") }
            ipv4Addresses.ifEmpty { listOf(AppConfig.WIREGUARD_LOCAL_ADDRESS_V4) }
        }

        val (remotes, leftOut) = wireguardRemoteDns(
            profileItem.remoteDNS,
            ipv6Enabled = ipv6Enabled,
        )
        if (leftOut.isNotEmpty()) {
            // The entries themselves stay out of the log: one may be a DNS URL with an account in it.
            LogUtil.w(
                AppConfig.TAG,
                "CoreOutboundBuilder: WireGuard profile '${profileItem.remarks}': ${leftOut.size} remoteDNS entries left out, not IP addresses"
            )
        }

        outboundBean?.settings?.let { wireguard ->
            wireguard.secretKey = profileItem.secretKey
            wireguard.address = addresses
            wireguard.port = null
            wireguard.peers?.firstOrNull()?.let { peer ->
                peer.publicKey = profileItem.publicKey.orEmpty()
                peer.preSharedKey = profileItem.preSharedKey?.nullIfBlank()
                peer.endpoint = Utils.getIpv6Address(profileItem.server) + ":${profileItem.serverPort}"
            }
            wireguard.mtu = profileItem.mtu
            wireguard.remoteDNS = remotes
            wireguard.reserved = profileItem.reserved?.takeIf { it.isNotBlank() }?.split(",")?.filter { it.isNotBlank() }?.map { it.trim().toInt() }
        }

        if (!profileItem.finalMask.isNullOrBlank()) {
            outboundBean?.streamSettings = OutboundBean.StreamSettingsBean()
            outboundBean?.streamSettings?.let {
                updateOutboundFinalMask(it, profileItem)
                it.network = null
            }
        }
        return outboundBean
    }

    private fun toOutboundHysteria2(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = createInitOutbound(EConfigType.HYSTERIA2) ?: return null
        profileItem.network = NetworkType.HYSTERIA.type
        profileItem.alpn = "h3"

        outboundBean.settings?.let { server ->
            server.address = getServerAddress(profileItem)
            server.port = profileItem.serverPort.orEmpty().toInt()
            server.version = 2
        }

        val sni = outboundBean.streamSettings?.let {
            populateTransportSettings(it, profileItem)
        }

        outboundBean.streamSettings?.let {
            populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }

    private fun createTcpHttpRequest(
        host: String?,
        path: String?
    ): OutboundBean.StreamSettingsBean.TcpSettingsBean.HeaderBean.RequestBean {
        val requestString =
            """{"version":"1.1","method":"GET","headers":{"User-Agent":["Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"],"Accept-Encoding":["gzip, deflate"],"Connection":["keep-alive"],"Pragma":"no-cache"}}"""
        val request = JsonUtil.fromJson(
            requestString,
            OutboundBean.StreamSettingsBean.TcpSettingsBean.HeaderBean.RequestBean::class.java
        ) ?: OutboundBean.StreamSettingsBean.TcpSettingsBean.HeaderBean.RequestBean()

        val parsedHost = host.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        request.headers.Host = parsedHost.ifEmpty { null }
        request.path = path.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("/") }
        return request
    }

    /**
     * Configures transport settings for an outbound connection.
     *
     * Sets up protocol-specific transport options based on the profile settings.
     *
     * @param streamSettings The stream settings to configure
     * @param profileItem The profile containing transport configuration
     * @return The Server Name Indication (SNI) value to use, or null if not applicable
     */
    fun populateTransportSettings(streamSettings: OutboundBean.StreamSettingsBean, profileItem: ProfileItem): String? {
        val transport = profileItem.network.orEmpty()
        val headerType = profileItem.headerType
        val host = profileItem.host
        val path = profileItem.path
        val seed = profileItem.seed
//        val quicSecurity = profileItem.quicSecurity
//        val key = profileItem.quicKey
        val mode = profileItem.mode
        val serviceName = profileItem.serviceName
        val authority = profileItem.authority
        val xhttpMode = profileItem.xhttpMode
        val xhttpExtra = profileItem.xhttpExtra
        val finalMask = profileItem.finalMask
        var sni: String? = null
        streamSettings.network = transport.ifEmpty { NetworkType.TCP.type }
        when (streamSettings.network) {
            NetworkType.TCP.type -> {
                val tcpSetting = OutboundBean.StreamSettingsBean.TcpSettingsBean()
                if (headerType == AppConfig.HEADER_TYPE_HTTP) {
                    tcpSetting.header.type = AppConfig.HEADER_TYPE_HTTP
                    val requestObj = createTcpHttpRequest(host, path)
                    tcpSetting.header.request = requestObj
                    sni = requestObj.headers.Host?.getOrNull(0)
                } else {
                    tcpSetting.header.type = "none"
                    sni = host
                }
                streamSettings.tcpSettings = tcpSetting
            }

            NetworkType.KCP.type -> {
                val kcpSetting = OutboundBean.StreamSettingsBean.KcpSettingsBean()
                profileItem.kcpMtu?.let { kcpSetting.mtu = it }
                profileItem.kcpTti?.let { kcpSetting.tti = it }
                streamSettings.kcpSettings = kcpSetting
                val udpMaskList =
                    mutableListOf<OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean>()
                if (!headerType.isNullOrEmpty() && headerType != "none") {
                    val kcpHeaderType = when {
                        headerType == "wechat-video" -> "wechat"
                        else -> headerType
                    }
                    udpMaskList.add(
                        OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean(
                            type = "mkcp-legacy",
                            settings =
                                OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(
                                    header = kcpHeaderType,
                                    value = if (headerType == "dns" && !host.isNullOrEmpty()) {
                                        host
                                    } else {
                                        null
                                    }
                                )
                        )
                    )
                }
                if (seed.isNullOrEmpty()) {
                    udpMaskList.add(
                        OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean(
                            type = "mkcp-legacy"
                        )
                    )
                } else {
                    udpMaskList.add(
                        OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean(
                            type = "mkcp-legacy",
                            settings = OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(
                                value = seed
                            )
                        )
                    )
                }
                udpMaskList.reverse()
                streamSettings.finalmask = OutboundBean.StreamSettingsBean.FinalMaskBean(
                    udp = udpMaskList.toList()
                )
            }

            NetworkType.WS.type -> {
                val wssetting = OutboundBean.StreamSettingsBean.WsSettingsBean()
                wssetting.host = host.orEmpty()
                sni = host
                wssetting.path = path ?: "/"
                streamSettings.wsSettings = wssetting
            }

            NetworkType.HTTP_UPGRADE.type -> {
                val httpupgradeSetting = OutboundBean.StreamSettingsBean.HttpupgradeSettingsBean()
                httpupgradeSetting.host = host.orEmpty()
                sni = host
                httpupgradeSetting.path = path ?: "/"
                streamSettings.httpupgradeSettings = httpupgradeSetting
            }

            NetworkType.XHTTP.type -> {
                val xhttpSetting = OutboundBean.StreamSettingsBean.XhttpSettingsBean()
                xhttpSetting.host = host.orEmpty()
                sni = host
                xhttpSetting.path = path ?: "/"
                xhttpSetting.mode = xhttpMode
                xhttpSetting.extra = JsonUtil.parseString(xhttpExtra)
                streamSettings.xhttpSettings = xhttpSetting
            }

            NetworkType.H2.type, NetworkType.HTTP.type -> {
                streamSettings.network = NetworkType.H2.type
                val h2Setting = OutboundBean.StreamSettingsBean.HttpSettingsBean()
                h2Setting.host = host.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                sni = h2Setting.host.getOrNull(0)
                h2Setting.path = path ?: "/"
                streamSettings.httpSettings = h2Setting
            }

//                    "quic" -> {
//                        val quicsetting = QuicSettingBean()
//                        quicsetting.security = quicSecurity ?: "none"
//                        quicsetting.key = key.orEmpty()
//                        quicsetting.header.type = headerType ?: "none"
//                        quicSettings = quicsetting
//                    }

            NetworkType.GRPC.type -> {
                val grpcSetting = OutboundBean.StreamSettingsBean.GrpcSettingsBean()
                grpcSetting.multiMode = mode == "multi"
                grpcSetting.serviceName = serviceName.orEmpty()
                grpcSetting.authority = authority.orEmpty()
                grpcSetting.idle_timeout = 60
                grpcSetting.health_check_timeout = 20
                sni = authority
                streamSettings.grpcSettings = grpcSetting
            }

            NetworkType.HYSTERIA.type -> {
                val hysteriaSetting = OutboundBean.StreamSettingsBean.HysteriaSettingsBean(
                    version = 2,
                    auth = profileItem.password.orEmpty(),
                )
                val quicParams = OutboundBean.StreamSettingsBean.FinalMaskBean.QuicParamsBean(
                    brutalUp = profileItem.bandwidthUp?.nullIfBlank(),
                    brutalDown = profileItem.bandwidthDown?.nullIfBlank(),
                )
                quicParams.congestion = if (quicParams.brutalUp != null || quicParams.brutalDown != null) "brutal" else null
                if (profileItem.portHopping.isNotNullEmpty()) {
                    val rawInterval = profileItem.portHoppingInterval?.trim().nullIfBlank()
                    val interval = if (rawInterval == null) {
                        "30"
                    } else {
                        val singleValue = rawInterval.toIntOrNull()
                        if (singleValue != null) {
                            if (singleValue < 5) {
                                "30"
                            } else {
                                rawInterval
                            }
                        } else {
                            val parts = rawInterval.split('-')
                            if (parts.size == 2) {
                                val start = parts[0].trim().toIntOrNull()
                                val end = parts[1].trim().toIntOrNull()
                                if (start != null && end != null) {
                                    val minStart = maxOf(5, start)
                                    val minEnd = maxOf(minStart, end)
                                    "$minStart-$minEnd"
                                } else {
                                    "30"
                                }
                            } else {
                                "30"
                            }
                        }
                    }
                    quicParams.udpHop = OutboundBean.StreamSettingsBean.FinalMaskBean.QuicParamsBean.UdpHopBean(
                        ports = profileItem.portHopping,
                        interval = interval
                    )
                }
                val finalmask = OutboundBean.StreamSettingsBean.FinalMaskBean(
                    quicParams = quicParams
                )
                if (profileItem.obfsPassword.isNotNullEmpty()) {
                    finalmask.udp = listOf(
                        OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean(
                            type = "salamander",
                            settings = OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(
                                password = profileItem.obfsPassword.orEmpty()
                            )
                        )
                    )
                }
                streamSettings.hysteriaSettings = hysteriaSetting
                streamSettings.finalmask = finalmask
            }
        }
        finalMask?.let {
            val parsedFinalMask = JsonUtil.parseString(finalMask)
            if (parsedFinalMask != null) {
                streamSettings.finalmask = parsedFinalMask
            } else {
                LogUtil.w("V2rayConfigManager", "Invalid finalMask JSON, keeping previously generated finalmask")
            }
        }
        return sni
    }

    /** PattNG: the protocols [alpn] names, comma-separated, as the TLS settings of an outbound offer them; empty for none. */
    internal fun alpnProtocols(alpn: String?): List<String> =
        alpn?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * Configures TLS or REALITY security settings for an outbound connection.
     *
     * Sets up security-related parameters like certificates, fingerprints, and SNI.
     *
     * @param streamSettings The stream settings to configure
     * @param profileItem The profile containing security configuration
     * @param sniExt An external SNI value to use if the profile doesn't specify one
     */
    fun populateTlsSettings(streamSettings: OutboundBean.StreamSettingsBean, profileItem: ProfileItem, sniExt: String?) {
        val streamSecurity = profileItem.security.orEmpty()
        val allowInsecure = profileItem.insecure == true && profileItem.pinnedCA256.isNullOrEmpty()
        val sni = if (profileItem.sni.isNullOrEmpty()) {
            when {
                sniExt.isNotNullEmpty() && Utils.isDomainName(sniExt) -> sniExt
                profileItem.server.isNotNullEmpty() && Utils.isDomainName(profileItem.server) -> profileItem.server
                else -> sniExt
            }
        } else {
            profileItem.sni
        }

        streamSettings.security = streamSecurity.nullIfBlank()
        if (streamSettings.security == null) return
        val tlsSetting = OutboundBean.StreamSettingsBean.TlsSettingsBean(
            allowInsecure = allowInsecure,
            serverName = sni.nullIfBlank(),
            fingerprint = profileItem.fingerPrint.nullIfBlank(),
            alpn = alpnProtocols(profileItem.alpn).takeIf { it.isNotEmpty() },
            cipherSuites = profileItem.cipherSuites.nullIfBlank(),
            echConfigList = profileItem.echConfigList.nullIfBlank(),
            verifyPeerCertByName = profileItem.verifyPeerCertByName.nullIfBlank(),
            pinnedPeerCertSha256 = profileItem.pinnedCA256.nullIfBlank(),
            publicKey = profileItem.publicKey.nullIfBlank(),
            shortId = profileItem.shortId.nullIfBlank(),
            spiderX = profileItem.spiderX.nullIfBlank(),
            mldsa65Verify = profileItem.mldsa65Verify.nullIfBlank(),
        )
        if (streamSettings.security == AppConfig.TLS) {
            streamSettings.tlsSettings = tlsSetting
            streamSettings.realitySettings = null
            // PattNG: the ECH config query goes through the profile's ECH outbound, which
            // EchOutbound.serialize checks, points echSockopt at and appends after every other outbound
            tlsSetting.echOutbound = profileItem.echOutbound.nullIfBlank()
        } else if (streamSettings.security == AppConfig.REALITY) {
            streamSettings.tlsSettings = null
            streamSettings.realitySettings = tlsSetting
        }
    }

    private fun getServerAddress(profileItem: ProfileItem): String {
        if (Utils.isPureIpAddress(profileItem.server.orEmpty())) {
            return profileItem.server.orEmpty()
        }

        val domain = HttpUtil.toIdnDomain(profileItem.server.orEmpty())
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, AppConfig.DEFAULT_OUTBOUND_DOMAIN_RESOLVE_METHOD) != "2") {
            return domain
        }
        //Resolve and replace domain
        val resolvedIps = HttpUtil.resolveHostToIP(domain, MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6))
        if (resolvedIps.isNullOrEmpty()) {
            return domain
        }
        return resolvedIps.first()
    }

    fun updateOutboundFinalMask(streamSettings: OutboundBean.StreamSettingsBean, profileItem: ProfileItem) =
        updateOutboundFinalMask(streamSettings, profileItem.finalMask)

    /** [updateOutboundFinalMask] with the finalMask JSON itself. */
    fun updateOutboundFinalMask(streamSettings: OutboundBean.StreamSettingsBean, finalMask: String?) {
        finalMask?.let {
            val parsedFinalMask = JsonUtil.parseString(finalMask)
            if (parsedFinalMask != null) {
                streamSettings.finalmask = parsedFinalMask
            } else {
                LogUtil.w("V2rayConfigManager", "Invalid finalMask JSON, keeping previously generated finalmask")
            }
        }
    }
}
