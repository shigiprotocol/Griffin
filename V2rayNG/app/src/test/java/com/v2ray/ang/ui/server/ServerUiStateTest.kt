package com.v2ray.ang.ui.server

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ServerUiStateTest {

    @Test
    fun theEchOutboundIsCarriedToTheProfileAndStoredOnlyWhenSet() {
        val json = """{"tag": "ech-out", "protocol": "freedom"}"""
        val profile = ProfileItem.create(EConfigType.VLESS).apply { echOutbound = json }

        val state = ServerUiState.from(profile)
        assertEquals(json, state.echOutbound)
        assertEquals(json, state.toProfileItem(profile).echOutbound)

        state.echOutbound = "  "
        assertNull(state.toProfileItem(profile).echOutbound)
    }

    @Test
    fun aTargetStrategyIsStoredOnlyWhenItDiffersFromTheDefault() {
        val profile = ProfileItem.create(EConfigType.VLESS)
        val state = ServerUiState.from(profile)
        assertEquals(AppConfig.TARGET_STRATEGY_AS_IS, state.shownTargetStrategy)
        assertNull(state.toProfileItem(profile).targetStrategy)

        state.targetStrategy = AppConfig.TARGET_STRATEGY_AS_IS
        assertNull(state.toProfileItem(profile).targetStrategy)

        state.targetStrategy = "UseIPv4v6"
        assertEquals("UseIPv4v6", state.shownTargetStrategy)
        assertEquals("UseIPv4v6", state.toProfileItem(profile).targetStrategy)
    }

}
