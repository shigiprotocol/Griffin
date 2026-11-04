package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class CoreConfigManagerTest {

    private fun socks(address: String, port: Int) = V2rayConfig.OutboundBean(
        protocol = "socks",
        settings = V2rayConfig.OutboundBean.OutSettingsBean(address = address, port = port),
    )

    @Test
    fun aConfigurationWhoseMainServerProducedNoOutboundIsRefusedRatherThanRunDirect() {
        fun config(vararg tags: String, balancer: String? = null) = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 10808, protocol = "socks")),
            outbounds = ArrayList(tags.map { V2rayConfig.OutboundBean(tag = it, protocol = if (it == AppConfig.TAG_DIRECT) "freedom" else "vless") }),
            routing = V2rayConfig.RoutingBean(
                domainStrategy = "AsIs",
                rules = arrayListOf(),
                balancers = balancer?.let { listOf(V2rayConfig.RoutingBean.BalancerBean(tag = it, selector = listOf("${AppConfig.TAG_PROXY}-"))) },
            ),
        )
        // The main server's outbound was skipped: what is left would send everything out directly.
        assertTrue(CoreConfigManager.lacksMainOutbound(config(AppConfig.TAG_DIRECT, "block")))
        // Nor does the policy group of a routing target stand in for it.
        assertTrue(
            CoreConfigManager.lacksMainOutbound(
                config(AppConfig.TAG_DIRECT, "proxy-germany-1-a", balancer = "${AppConfig.TAG_BALANCER_PRE}-germany")
            )
        )
        // The main server's outbound, or the balancer of a policy group that is the main server.
        assertFalse(CoreConfigManager.lacksMainOutbound(config(AppConfig.TAG_PROXY, AppConfig.TAG_DIRECT, "block")))
        assertFalse(CoreConfigManager.lacksMainOutbound(config("proxy-proxy-1-a", AppConfig.TAG_DIRECT, balancer = AppConfig.TAG_BALANCER)))
    }

    @Test
    fun aRuleWhoseTargetWasNotBuiltIsNamedForTheSessionToBeRefused() {
        fun config(vararg tags: String, rules: List<V2rayConfig.RoutingBean.RulesBean>) = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 10808, protocol = "socks")),
            outbounds = ArrayList(tags.map { V2rayConfig.OutboundBean(tag = it, protocol = "vless") }),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = ArrayList(rules)),
        )
        fun rule(outboundTag: String? = null, balancerTag: String? = null) =
            V2rayConfig.RoutingBean.RulesBean(domain = listOf("example.com"), outboundTag = outboundTag, balancerTag = balancerTag)

        // A rule to a profile that was built, to a built-in outbound, or to the balancer of a group has its way out.
        val built = listOf(rule("germany"), rule(AppConfig.TAG_DIRECT), rule(AppConfig.TAG_BLOCKED), rule(AppConfig.TAG_PROXY), rule(balancerTag = "balancer-group"))
        assertNull(CoreConfigManager.unbuiltRoutingTarget(config(AppConfig.TAG_PROXY, "germany", rules = built)))
        // One to a profile that was not built is named, the first of them.
        val unbuilt = listOf(rule(AppConfig.TAG_DIRECT), rule("routed chain"), rule("france"))
        assertEquals("routed chain", CoreConfigManager.unbuiltRoutingTarget(config(AppConfig.TAG_PROXY, rules = unbuilt)))
    }

    @Test
    fun aGroupFallbackThatWasNotBuiltIsNamedForTheSessionToBeRefused() {
        fun config(vararg tags: String, fallbacks: List<String?>?) = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 10808, protocol = "socks")),
            outbounds = ArrayList(tags.map { V2rayConfig.OutboundBean(tag = it, protocol = "vless") }),
            routing = V2rayConfig.RoutingBean(
                domainStrategy = "AsIs",
                rules = arrayListOf(),
                balancers = fallbacks?.mapIndexed { index, fallback ->
                    V2rayConfig.RoutingBean.BalancerBean(tag = "balancer-$index", selector = listOf("proxy-$index-"), fallbackTag = fallback)
                },
            ),
        )

        // Groups that fall back to a profile that was built, to their first member, to a built-in outbound, or to none.
        val built = listOf("exit", "proxy-1-1-a", AppConfig.TAG_DIRECT, AppConfig.TAG_BLOCKED, null)
        assertNull(CoreConfigManager.unbuiltGroupFallback(config(AppConfig.TAG_PROXY, "exit", "proxy-1-1-a", fallbacks = built)))
        // No group at all.
        assertNull(CoreConfigManager.unbuiltGroupFallback(config(AppConfig.TAG_PROXY, fallbacks = null)))
        // One that falls back to a profile that was not built is named, the first of them.
        val unbuilt = listOf("exit", "fb chain", "france")
        assertEquals("fb chain", CoreConfigManager.unbuiltGroupFallback(config(AppConfig.TAG_PROXY, "exit", fallbacks = unbuilt)))
        // A rule's target is not a fallback: the rules are looked at apart, see unbuiltRoutingTarget.
        assertNull(CoreConfigManager.unbuiltRoutingTarget(config(AppConfig.TAG_PROXY, "exit", fallbacks = unbuilt)))
    }

    @Test
    fun aProxyChainIsBuiltWholeOrNotAtAll() {
        val entry = ProfileItem.create(EConfigType.VLESS).apply { remarks = "entry" }
        val middle = ProfileItem.create(EConfigType.VLESS).apply { remarks = "middle" }
        val exit = ProfileItem.create(EConfigType.VLESS).apply { remarks = "exit" }
        val build = { profile: ProfileItem -> V2rayConfig.OutboundBean(tag = profile.remarks, protocol = "vless") }

        val whole = CoreConfigManager.chainHops(listOf(entry, middle, exit), build)
        assertEquals(listOf("entry", "middle", "exit"), whole?.map { it.second.tag })
        assertEquals(listOf(entry, middle, exit), whole?.map { it.first })
        // A hop that builds no outbound, wherever it stands, leaves no chain rather than a shorter one.
        for (broken in listOf(entry, middle, exit)) {
            assertNull(CoreConfigManager.chainHops(listOf(entry, middle, exit)) { profile -> build(profile).takeUnless { profile === broken } })
        }
        assertNull(CoreConfigManager.chainHops(emptyList(), build))
    }

    @Test
    fun aLeastPingOrLeastLoadGroupFallsBackToItsOwnFirstMember() {
        val group = ProfileItem.create(EConfigType.POLICYGROUP).apply { policyGroupFallbackTag = "other" }
        val first = "proxy-group-1-a"
        // Their own first member, whatever fallback the group names, so that a probe missing or failing does not send a
        // matched rule through the main profile.
        assertEquals(first, CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.LEAST_PING, group, first))
        assertEquals(first, CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.LEAST_LOAD, group, first))
        // Random and round robin that test their members: the fallback the group names, or else their first member.
        assertEquals("other", CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.RANDOM, group, first))
        assertEquals(first, CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.ROUND_ROBIN, group.copy(policyGroupFallbackTag = ""), first))
        assertEquals(first, CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.RANDOM, group.copy(policyGroupFallbackTag = AppConfig.TAG_PROXY), first))
        // The name as the outbound built for the fallback is tagged with: trimmed, and a blank one names none.
        assertEquals("other", CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.RANDOM, group.copy(policyGroupFallbackTag = " other "), first))
        assertEquals(first, CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.ROUND_ROBIN, group.copy(policyGroupFallbackTag = "  "), first))
        // Random or round robin that do not test their members: none.
        assertNull(CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.RANDOM, group.copy(policyGroupTestOutbounds = false), first))
    }

    @Test
    fun onlyLeastPingAndCheckedRandomOrRoundRobinGroupsGetTheStandardObservatory() {
        assertTrue(CoreConfigManager.shouldUseStandardObservatory(BalancerStrategyType.LEAST_PING, "proxy-group-1-a"))
        assertTrue(CoreConfigManager.shouldUseStandardObservatory(BalancerStrategyType.LEAST_PING, null))
        // Least load keeps its burst observatory alone, its fallback set now.
        assertFalse(CoreConfigManager.shouldUseStandardObservatory(BalancerStrategyType.LEAST_LOAD, "proxy-group-1-a"))
        assertTrue(CoreConfigManager.shouldUseStandardObservatory(BalancerStrategyType.RANDOM, "proxy-group-1-a"))
        assertFalse(CoreConfigManager.shouldUseStandardObservatory(BalancerStrategyType.ROUND_ROBIN, null))
    }

    @Test
    fun aChinaModeDomainGoesThroughEveryDomesticServerAndNeverReachesTheRemoteOne() {
        val configContext = CoreConfigContext(
            context = mock<Context>(),
            guid = "guid",
            routingDomainRules = listOf(
                CoreConfigContext.RoutingDomainRule(listOf("geosite:google"), AppConfig.TAG_PROXY),
                CoreConfigContext.RoutingDomainRule(listOf(AppConfig.GEOSITE_CN), AppConfig.TAG_DIRECT),
            ),
        )
        val domesticDns = listOf("223.5.5.5", "119.29.29.29")
        val servers = ArrayList<Any>()
        CoreConfigManager.buildDnsCnModeFromRoutingRules(configContext, servers, domesticDns)
        CoreConfigManager.buildDnsFromRoutingRules(configContext, servers, listOf("https://8.8.8.8/dns-query"), domesticDns)

        // The servers the core asks for a geosite:cn domain: those whose domains match, in order, up to the first
        // with finalQuery.
        val matching = servers.filterIsInstance<V2rayConfig.DnsBean.ServersBean>()
            .filter { AppConfig.GEOSITE_CN in it.domains.orEmpty() }
        val last = matching.indexOfFirst { it.finalQuery == true }
        assertTrue(last >= 0, "a geosite:cn domain must stop at a domestic server, before the remote one")
        val asked = matching.take(last + 1)
        // A Chinese IP from each domestic server first, then any answer from them, and never the remote server.
        assertEquals(domesticDns + domesticDns, asked.map { it.address })
        assertEquals(listOf(listOf(AppConfig.GEOIP_CN), listOf(AppConfig.GEOIP_CN), null, null), asked.map { it.expectIPs })
    }
}
