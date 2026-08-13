package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayProfileEditorModelsTest {
    @Test
    fun `patch distinguishes omitted sections from empty replace lists`() {
        val patch = GatewayProfilePatch(
            soul = "",
            disabledSkills = emptyList(),
            enabledToolsets = emptyList(),
        )

        assertEquals(
            setOf(
                GatewayProfileSection.Soul,
                GatewayProfileSection.Skills,
                GatewayProfileSection.Toolsets,
            ),
            patch.requestedSections,
        )
    }

    @Test
    fun `configure result treats every requested non-applied section as failed`() {
        val result = GatewayProfileConfigureResult(
            requested = setOf(GatewayProfileSection.Description, GatewayProfileSection.Model),
            applied = setOf(GatewayProfileSection.Description),
        )

        assertEquals(setOf(GatewayProfileSection.Model), result.failed)
    }
}
