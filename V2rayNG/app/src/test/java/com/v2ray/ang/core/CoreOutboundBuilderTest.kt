package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for CoreOutboundBuilder.applyDialMode: dialMode must land in
 * streamSettings.sockopt without discarding the other sockopt options.
 */
class CoreOutboundBuilderTest {

    private fun createProfile(mode: String?): ProfileItem =
        ProfileItem.create(EConfigType.VLESS).apply { dialMode = mode }

    @Test
    fun aMuxConcurrencyFieldThatHoldsNoNumberFallsBackToTheDefault() {
        assertEquals(16, CoreOutboundBuilder.muxConcurrency("16", 8))
        assertEquals(16, CoreOutboundBuilder.muxConcurrency(" 16 ", 8))
        assertEquals(-1, CoreOutboundBuilder.muxConcurrency("-1", 8))
        // Blank, whitespace, words, a decimal and an overflow left the outbound unbuilt before.
        for (text in listOf(null, "", "  ", "eight", "1.5", "99999999999")) {
            assertEquals(8, CoreOutboundBuilder.muxConcurrency(text, 8), text.toString())
        }
    }

    @Test
    fun test_applyDialMode_setsSockoptDialMode() {
        val outbound = OutboundBean(protocol = "vless", streamSettings = OutboundBean.StreamSettingsBean())

        CoreOutboundBuilder.applyDialMode(outbound, createProfile("code-1"))

        assertEquals("code-1", outbound.streamSettings?.sockopt?.dialMode)
        assertEquals(AppConfig.DEFAULT_NETWORK, outbound.streamSettings?.network)
    }

    @Test
    fun test_applyDialMode_keepsExistingSockoptOptions() {
        val outbound = OutboundBean(
            protocol = "vless",
            streamSettings = OutboundBean.StreamSettingsBean(
                sockopt = OutboundBean.StreamSettingsBean.SockoptBean(
                    dialerProxy = "hop-1",
                    domainStrategy = "UseIP"
                )
            )
        )

        CoreOutboundBuilder.applyDialMode(outbound, createProfile("code-1"))

        val sockopt = outbound.streamSettings?.sockopt
        assertEquals("code-1", sockopt?.dialMode)
        assertEquals("hop-1", sockopt?.dialerProxy)
        assertEquals("UseIP", sockopt?.domainStrategy)
    }

    @Test
    fun test_applyDialMode_ignoresBlankDialMode() {
        val outbound = OutboundBean(protocol = "vless", streamSettings = OutboundBean.StreamSettingsBean())

        CoreOutboundBuilder.applyDialMode(outbound, createProfile(" "))
        CoreOutboundBuilder.applyDialMode(outbound, createProfile(null))

        assertNull(outbound.streamSettings?.sockopt)
    }

    @Test
    fun test_applyDialMode_wireguardWithoutStreamSettings_getsSockoptWithoutNetwork() {
        // wireguard outbounds are created without streamSettings; Xray still dials their
        // endpoint through the system dialer, so a sockopt-only streamSettings is added
        val outbound = OutboundBean(protocol = "wireguard")

        CoreOutboundBuilder.applyDialMode(outbound, createProfile("code-1"))

        assertNotNull(outbound.streamSettings)
        assertNull(outbound.streamSettings?.network)
        assertEquals("code-1", outbound.streamSettings?.sockopt?.dialMode)
    }

    /** A profile whose sni keeps populateTlsSettings away from Utils. */
    private fun echProfile(security: String, echOutbound: String): ProfileItem =
        ProfileItem.create(EConfigType.VLESS).apply {
            this.security = security
            sni = "example.com"
            echConfigList = "cloudflare-ech.com+https://1.1.1.1/dns-query"
            this.echOutbound = echOutbound
        }

    @Test
    fun test_populateTlsSettings_addsNoFinalMaskOfItsOwn() {
        // Fragmenting is the profile's own finalMask; no global setting adds one to a TLS or REALITY outbound.
        for (security in listOf(AppConfig.TLS, AppConfig.REALITY)) {
            val streamSettings = OutboundBean.StreamSettingsBean()

            CoreOutboundBuilder.populateTlsSettings(streamSettings, echProfile(security, ""), null)

            assertNull(streamSettings.finalmask, security)
        }
    }

    @Test
    fun test_populateTlsSettings_attachesTheEchOutboundForTlsAsWritten() {
        // EchOutbound.serialize checks it, so an invalid one reaches it too and fails the configuration there.
        for (echOutbound in listOf("""{"tag": "ech-out", "protocol": "freedom"}""", """{"tag": "proxy"}""")) {
            val streamSettings = OutboundBean.StreamSettingsBean()

            CoreOutboundBuilder.populateTlsSettings(streamSettings, echProfile(AppConfig.TLS, echOutbound), null)

            assertEquals(echOutbound, streamSettings.tlsSettings?.echOutbound)
        }
    }

    @Test
    fun test_populateTlsSettings_attachesNoEchOutboundForRealityOrABlankOne() {
        val reality = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTlsSettings(reality, echProfile(AppConfig.REALITY, """{"tag": "ech-out"}"""), null)
        assertNotNull(reality.realitySettings)
        assertNull(reality.realitySettings?.echOutbound)

        val blank = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTlsSettings(blank, echProfile(AppConfig.TLS, " "), null)
        assertNotNull(blank.tlsSettings)
        assertNull(blank.tlsSettings?.echOutbound)
    }

    @Test
    fun test_populateTlsSettings_offersTheAlpnAsWrittenCommaSeparated() {
        // TlsSettingsCheck reads alpn with alpnProtocols as well, to refuse what WebSocket and HTTPUpgrade cannot use.
        val offered = mapOf(
            " h2 , http/1.1," to listOf("h2", "http/1.1"),
            "http/1.1" to listOf("http/1.1"),
            "h3,,h2" to listOf("h3", "h2"),
            // Only a comma parts two; Xray is offered what is between as one name.
            "h2 http/1.1" to listOf("h2 http/1.1"),
            " , " to null,
            "" to null,
            null to null,
        )
        for ((alpn, protocols) in offered) {
            val streamSettings = OutboundBean.StreamSettingsBean()

            CoreOutboundBuilder.populateTlsSettings(streamSettings, echProfile(AppConfig.TLS, " ").apply { this.alpn = alpn }, null)

            assertEquals(protocols, streamSettings.tlsSettings?.alpn, "$alpn")
            assertEquals(protocols.orEmpty(), CoreOutboundBuilder.alpnProtocols(alpn), "$alpn")
        }
    }

    @Test
    fun test_applyTargetStrategy_setsOutboundTargetStrategy() {
        val outbound = OutboundBean(protocol = "vless")

        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.VLESS).apply { targetStrategy = "ForceIPv6v4" })

        assertEquals("ForceIPv6v4", outbound.targetStrategy)
    }

    @Test
    fun test_applyTargetStrategy_leavesTheDefaultOut() {
        val outbound = OutboundBean(protocol = "vless", targetStrategy = "UseIP")

        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.VLESS).apply { targetStrategy = AppConfig.TARGET_STRATEGY_AS_IS })
        assertNull(outbound.targetStrategy)

        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.VLESS).apply { targetStrategy = " " })
        assertNull(outbound.targetStrategy)

        CoreOutboundBuilder.applyTargetStrategy(outbound, ProfileItem.create(EConfigType.VLESS))
        assertNull(outbound.targetStrategy)
    }

    @Test
    fun aWireGuardOutboundGetsOnlyAddressesAsRemoteDnsEachItsOwnEntry() {
        val none = emptyList<String>()
        // Blank: the default servers, one entry each, the IPv4 ones alone when IPv6 is off.
        assertEquals(
            listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001") to none,
            CoreOutboundBuilder.wireguardRemoteDns(null, ipv6Enabled = true),
        )
        assertEquals(listOf("1.1.1.1", "1.0.0.1") to none, CoreOutboundBuilder.wireguardRemoteDns(" , ", ipv6Enabled = false))
        // "local", which the core takes no more, a host name and an address with a port are left out.
        assertEquals(listOf("1.1.1.1", "1.0.0.1") to listOf("local"), CoreOutboundBuilder.wireguardRemoteDns("local", ipv6Enabled = false))
        assertEquals(
            listOf("8.8.8.8") to listOf("dns.google", "1.1.1.1:53"),
            CoreOutboundBuilder.wireguardRemoteDns("dns.google, 8.8.8.8, 1.1.1.1:53", ipv6Enabled = true),
        )
        // Only IPv6 servers while IPv6 is off: the default IPv4 ones.
        assertEquals(listOf("1.1.1.1", "1.0.0.1") to none, CoreOutboundBuilder.wireguardRemoteDns("2001:4860:4860::8888", ipv6Enabled = false))
        // An IPv6 address loses its brackets; with a port it is left out.
        assertEquals(
            listOf("2001:4860:4860::8888", "9.9.9.9") to listOf("[2001:4860:4860::8844]:53"),
            CoreOutboundBuilder.wireguardRemoteDns("[2001:4860:4860::8888], 9.9.9.9, [2001:4860:4860::8844]:53", ipv6Enabled = true),
        )
        // What the core refuses although it looks like an address is left out; what it takes is kept.
        assertEquals(
            listOf("::ffff:1.1.1.1", "64:ff9b::8.8.8.8") to listOf("8.8.8.08", "1:2:3:4:5:6:7::8", "[[::1]]"),
            CoreOutboundBuilder.wireguardRemoteDns("8.8.8.08, ::ffff:1.1.1.1, 1:2:3:4:5:6:7::8, [[::1]], 64:ff9b::8.8.8.8", ipv6Enabled = true),
        )
    }

    @Test
    fun anAddressIsWhatGosNetipParseAddrTakes() {
        listOf(
            "1.1.1.1", "0.0.0.0", "255.255.255.255", "::", "::1", "1::", "2001:db8::68", "2001:DB8:0:0:0:0:0:1",
            "1:2:3:4:5:6:7:8", "::ffff:1.1.1.1", "64:ff9b::8.8.8.8", "::1.2.3.4", "1:2:3:4:5:6:1.2.3.4", "fe80::1%eth0",
        ).forEach { assertTrue(CoreOutboundBuilder.isNetipAddress(it), it) }
        listOf(
            "", "local", "dns.google", "8.8.8.08", "010.0.0.1", "00.1.1.1", "256.1.1.1", "1.2.3", "1.2.3.4.5", "1..2.3",
            "1.2.3.4%eth0", "1.1.1.1:53", "1:2:3:4:5:6:7::8", "2001:db8:0:0:0:0:0::1", "1:::2", ":1::2", "1::2::3", "1:2",
            "[::1]", "12345::1", "::1%", "%eth0", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:1.2.3.4:5", "::1.2.3", "1:2:3:4:5:1.2.3.4",
            "::g", "1:2:3:4:5:6:7:8::", "１.1.1.1",
        ).forEach { assertFalse(CoreOutboundBuilder.isNetipAddress(it), it) }
    }
}
