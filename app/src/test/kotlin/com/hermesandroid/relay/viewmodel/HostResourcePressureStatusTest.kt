package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.DashboardDiskStatus
import com.hermesandroid.relay.network.upstream.DashboardMemoryStatus
import com.hermesandroid.relay.network.upstream.DashboardStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class HostResourcePressureStatusTest {
    @Test
    fun `older host without resource fields stays quiet`() {
        val pressure = DashboardStatus(authRequired = false).hostResourcePressure()

        assertEquals(false, pressure.needsAttention)
        assertEquals(false, pressure.critical)
    }

    @Test
    fun `server pressure enums drive severity without client thresholds`() {
        val pressure = DashboardStatus(
            authRequired = false,
            memory = DashboardMemoryStatus(
                pressure = "elevated",
                systemAvailableMb = 96,
            ),
            disk = DashboardDiskStatus(
                pressure = "critical",
                freeMb = 220,
            ),
        ).hostResourcePressure()

        assertEquals(true, pressure.needsAttention)
        assertEquals(true, pressure.critical)
        assertEquals(96, pressure.memoryAvailableMb)
        assertEquals(220, pressure.diskFreeMb)
    }
}
