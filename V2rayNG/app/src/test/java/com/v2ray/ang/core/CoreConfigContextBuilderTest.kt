package com.v2ray.ang.core

import com.v2ray.ang.R
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.CHAIN_AS_HOP
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.CUSTOM_AS_FALLBACK
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.CUSTOM_AS_HOP
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.GROUP_AS_FALLBACK
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.GROUP_AS_HOP
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.NO_SERVER
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.NOT_FOUND
import com.v2ray.ang.dto.CoreConfigContext.UnresolvedName.Reason.SEVERAL
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreConfigContextBuilderTest {

    private val vless = ProfileItem.create(EConfigType.VLESS).apply { remarks = "vless"; server = "1.2.3.4"; serverPort = "443" }

    @Test
    fun aChainFindsItsHopsByTheirNamesInItsOrder() {
        val entry = vless.copy(remarks = "entry")
        val profiles = mapOf("vless" to vless, "entry" to entry)
        val (hops, unresolved) = CoreConfigContextBuilder.proxyChainHops(listOf(" vless", "", "entry ")) { name, _ ->
            profiles[name]?.let { ByName.One(it) } ?: ByName.None
        }
        assertEquals(listOf(vless, entry), hops)
        assertNull(unresolved)
    }

    @Test
    fun aChainTellsTheFirstNameThatFindsNoProfileOrSeveral() {
        val found = mapOf<String, ByName<ProfileItem>>("vless" to ByName.One(vless), "twice" to ByName.Several)
        fun hops(vararg names: String) = CoreConfigContextBuilder.proxyChainHops(names.toList()) { name, _ -> found[name] ?: ByName.None }

        // Renamed or deleted: the chain does not run without that hop.
        assertEquals(listOf(vless) to CoreConfigContext.UnresolvedName("gone", NOT_FOUND), hops("gone", "vless", "twice"))
        // The name of two profiles: the chain does not guess which one it means.
        assertEquals(listOf(vless) to CoreConfigContext.UnresolvedName("twice", SEVERAL), hops("vless", "twice", "gone"))
    }

    @Test
    fun aHopOnlyAGroupAChainOrACustomConfigurationHasIsToldByWhatItIsAndAProfileOfItsNameStillGoes() {
        fun profile(name: String, type: EConfigType) = ProfileItem.create(type).apply { remarks = name; server = "203.0.113.7" }
        val shared = profile("shared", EConfigType.TROJAN)
        val profiles = listOf(
            vless,
            profile("group", EConfigType.POLICYGROUP),
            profile("chain", EConfigType.PROXYCHAIN),
            profile("custom", EConfigType.CUSTOM),
            profile("shared", EConfigType.POLICYGROUP),
            shared,
        )
        fun hops(vararg names: String) = CoreConfigContextBuilder.proxyChainHops(names.toList()) { name, takes ->
            ByName.find(name, profiles.asSequence().filter(takes)) { it.remarks }
        }

        assertEquals(listOf(vless) to CoreConfigContext.UnresolvedName("group", GROUP_AS_HOP), hops("vless", "group"))
        assertEquals(R.string.toast_profile_group_not_hop, GROUP_AS_HOP.message)
        // A chain or a custom configuration cannot be a hop either, and is told by what it is.
        assertEquals(CoreConfigContext.UnresolvedName("chain", CHAIN_AS_HOP), hops("chain").second)
        assertEquals(CoreConfigContext.UnresolvedName("custom", CUSTOM_AS_HOP), hops("custom").second)
        // A name no profile has is told as before.
        assertEquals(CoreConfigContext.UnresolvedName("gone", NOT_FOUND), hops("gone").second)
        // A group of the name stands aside for the one profile of it that can be a hop.
        assertEquals(listOf(shared) to null, hops("shared"))
    }

    @Test
    fun aChainKeepsACommaOrABackslashInsideTheNameOfAHop() {
        val names = listOf("US, Dallas", "Exit", """back\slash""", """a\,b""", "")
        assertEquals(names, ProfileItem.proxyChainMembersOf(ProfileItem.proxyChainProfilesOf(names)))
        // A name with neither is written as before, and a chain written before reads as it did.
        assertEquals("entry,warp,exit", ProfileItem.proxyChainProfilesOf(listOf("entry", "warp", "exit")))
        assertEquals(listOf("entry", " warp", "exit"), ProfileItem.proxyChainMembersOf("entry, warp,exit"))
        assertEquals(listOf("""a\b"""), ProfileItem.proxyChainMembersOf("""a\b"""))
        assertEquals(emptyList<String>(), ProfileItem.proxyChainMembersOf(null))
        assertEquals(emptyList<String>(), ProfileItem.proxyChainMembersOf(""))
        // The hops of such a chain are found by their whole names.
        val dallas = vless.copy(remarks = "US, Dallas")
        val profiles = listOf(vless, dallas).associateBy { it.remarks }
        val stored = ProfileItem.proxyChainProfilesOf(listOf("US, Dallas", "vless"))
        assertEquals(listOf(dallas, vless) to null, CoreConfigContextBuilder.proxyChainHops(ProfileItem.proxyChainMembersOf(stored)) { name, _ ->
            profiles[name]?.let { ByName.One(it) } ?: ByName.None
        })
    }

    @Test
    fun aRoutingRuleSendsToAnyProfileButACustomConfiguration() {
        for (type in EConfigType.entries.filter { it != EConfigType.CUSTOM }) {
            assertTrue(CoreConfigContextBuilder.takesAsRoutingTarget(ProfileItem.create(type)), type.name)
        }
        assertFalse(CoreConfigContextBuilder.takesAsRoutingTarget(ProfileItem.create(EConfigType.CUSTOM)))
    }

    @Test
    fun aGroupFallsBackToAnyProfileButAGroupOrACustomConfiguration() {
        val others = setOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP)
        for (type in EConfigType.entries.filter { it !in others }) {
            assertTrue(CoreConfigContextBuilder.takesAsFallback(ProfileItem.create(type)), type.name)
        }
        for (type in others) {
            assertFalse(CoreConfigContextBuilder.takesAsFallback(ProfileItem.create(type)), type.name)
        }
    }

    @Test
    fun aFallbackIsFoundAmongTheProfilesThatCanBeOneAndANameOnlyAGroupOrACustomConfigurationHasIsToldAsSuch() {
        fun profile(name: String, type: EConfigType) = ProfileItem.create(type).apply { remarks = name }
        val exit = profile("exit", EConfigType.VLESS)
        val shared = profile("shared", EConfigType.TROJAN)
        val profiles = listOf(
            exit,
            profile("group", EConfigType.POLICYGROUP),
            profile("custom", EConfigType.CUSTOM),
            profile("twice", EConfigType.VLESS),
            profile("twice", EConfigType.PROXYCHAIN),
            profile("shared", EConfigType.POLICYGROUP),
            shared,
        )
        val find = { name: String, takes: (ProfileItem) -> Boolean -> ByName.find(name, profiles.asSequence().filter(takes)) { it.remarks } }

        assertEquals(exit to null, CoreConfigContextBuilder.fallbackOf("exit", find))
        assertEquals(null to GROUP_AS_FALLBACK, CoreConfigContextBuilder.fallbackOf("group", find))
        assertEquals(null to NOT_FOUND, CoreConfigContextBuilder.fallbackOf("gone", find))
        // A custom configuration cannot be one either, and is told as such.
        assertEquals(null to CUSTOM_AS_FALLBACK, CoreConfigContextBuilder.fallbackOf("custom", find))
        assertEquals(null to SEVERAL, CoreConfigContextBuilder.fallbackOf("twice", find))
        // A group of the name stands aside for the one profile of it that can be the fallback.
        assertEquals(shared to null, CoreConfigContextBuilder.fallbackOf("shared", find))
    }

    @Test
    fun aNameIsToldByTheTypesThatCannotBeUsedThereAndNoOther() {
        // The types a name is told by are those a chain cannot go through, and those a group cannot fall back to.
        for (type in EConfigType.entries) {
            val profile = ProfileItem.create(type)
            assertEquals(!CoreConfigContextBuilder.takesAsHop(profile), type in CoreConfigContextBuilder.HOP_REASONS.map { it.first }, type.name)
            assertEquals(!CoreConfigContextBuilder.takesAsFallback(profile), type in CoreConfigContextBuilder.FALLBACK_REASONS.map { it.first }, type.name)
        }
        assertEquals(R.string.toast_profile_group_not_hop, GROUP_AS_HOP.message)
        assertEquals(R.string.toast_profile_chain_not_hop, CHAIN_AS_HOP.message)
        assertEquals(R.string.toast_profile_custom_not_hop, CUSTOM_AS_HOP.message)
        assertEquals(R.string.toast_profile_group_not_fallback, GROUP_AS_FALLBACK.message)
        assertEquals(R.string.toast_profile_custom_not_fallback, CUSTOM_AS_FALLBACK.message)

        // A name profiles of two such types share is told by the first of them in order: a group before a chain.
        val shared = listOf(
            ProfileItem.create(EConfigType.PROXYCHAIN).apply { remarks = "shared" },
            ProfileItem.create(EConfigType.POLICYGROUP).apply { remarks = "shared" },
        )
        val find = { name: String, takes: (ProfileItem) -> Boolean -> ByName.find(name, shared.asSequence().filter(takes)) { it.remarks } }
        assertEquals(GROUP_AS_HOP, CoreConfigContextBuilder.reasonByType("shared", CoreConfigContextBuilder.HOP_REASONS, find))
        assertNull(CoreConfigContextBuilder.reasonByType("gone", CoreConfigContextBuilder.HOP_REASONS, find))
    }

    @Test
    fun aNameNoUsableProfileHasCostsOneLookupMoreHoweverManyTypesTellIt() {
        val profiles = listOf(
            ProfileItem.create(EConfigType.PROXYCHAIN).apply { remarks = "chain" },
            ProfileItem.create(EConfigType.PROXYCHAIN).apply { remarks = "shared" },
            ProfileItem.create(EConfigType.POLICYGROUP).apply { remarks = "shared" },
        )
        var lookups = 0
        val find = { name: String, takes: (ProfileItem) -> Boolean ->
            lookups++
            ByName.find(name, profiles.asSequence().filter(takes)) { it.remarks }
        }
        fun lookupsOf(name: String): Int {
            lookups = 0
            CoreConfigContextBuilder.proxyChainHops(listOf(name), find)
            return lookups
        }
        // Each lookup reads every profile: one for the hop, and one more among all the types a hop cannot be.
        assertEquals(2, lookupsOf("gone"))
        assertEquals(2, lookupsOf("chain"))
        // Several such profiles have the name: the types one by one, until the first in order, the group, has it.
        assertEquals(3, lookupsOf("shared"))
    }

    @Test
    fun aGroupFallbackNameIsReadTrimmedAndABlankOneNamesNone() {
        fun group(fallback: String?) = ProfileItem.create(EConfigType.POLICYGROUP).apply { policyGroupFallbackTag = fallback }
        assertEquals("exit", CoreConfigContextBuilder.fallbackNameOf(group("exit")))
        assertEquals("exit", CoreConfigContextBuilder.fallbackNameOf(group(" exit ")))
        assertNull(CoreConfigContextBuilder.fallbackNameOf(group("   ")))
        assertNull(CoreConfigContextBuilder.fallbackNameOf(group("")))
        assertNull(CoreConfigContextBuilder.fallbackNameOf(group(null)))
    }
}
