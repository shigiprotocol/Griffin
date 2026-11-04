package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpeedtestManagerTest {

    @Test
    fun aCloudflareTraceGivesTheExitIpAndCountry() {
        val trace = "fl=123f45\nh=1.1.1.1\nip=203.0.113.7\nts=1730000000.123\nvisit_scheme=https\nloc=NL\ntls=TLSv1.3\n"
        assertEquals(SpeedtestManager.RemoteEndpointInfo(country = "NL", ipAddress = "203.0.113.7"), SpeedtestManager.parseCloudflareTrace(trace))
    }

    @Test
    fun aTraceWithoutAKnownCountryKeepsTheIpAndOneWithoutAnIpGivesNothing() {
        assertEquals(SpeedtestManager.RemoteEndpointInfo(country = null, ipAddress = "2001:db8::1"), SpeedtestManager.parseCloudflareTrace("ip=2001:db8::1\nloc=XX"))
        assertEquals(null, SpeedtestManager.parseCloudflareTrace("ip=198.51.100.2\nloc=T1")?.country)
        assertNull(SpeedtestManager.parseCloudflareTrace("loc=DE\n"))
        assertNull(SpeedtestManager.parseCloudflareTrace(""))
    }

    @Test
    fun anIpServicePrefersTheCountryCodeOverItsName() {
        val ipSb = """{"ip":"203.0.113.7","country":"Germany","country_code":"DE"}"""
        assertEquals(SpeedtestManager.RemoteEndpointInfo(country = "DE", ipAddress = "203.0.113.7"), SpeedtestManager.parseIpApi(ipSb))
        val ipApi = """{"query":"198.51.100.2","countryCode":"FR"}"""
        assertEquals(SpeedtestManager.RemoteEndpointInfo(country = "FR", ipAddress = "198.51.100.2"), SpeedtestManager.parseIpApi(ipApi))
    }
}
