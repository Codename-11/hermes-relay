package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.network.upstream.GatewayProcess
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessDisplayPhaseTest {
    @Test
    fun unavailableAndCancelledDoNotBecomeSuccessfulWithoutExitCode() {
        assertEquals(ProcessDisplayPhase.UNKNOWN, phase("unknown"))
        assertEquals(ProcessDisplayPhase.UNKNOWN, phase("unrecognized"))
        assertEquals(ProcessDisplayPhase.CANCELLED, phase("cancelled"))
        assertEquals(ProcessDisplayPhase.FAILED, phase("failed"))
    }

    @Test
    fun knownLiveAndTerminalStatesRetainTheirMeaning() {
        assertEquals(ProcessDisplayPhase.RUNNING, phase("running"))
        assertEquals(ProcessDisplayPhase.COMPLETE, phase("complete"))
        assertEquals(ProcessDisplayPhase.COMPLETE, phase("completed"))
        assertEquals(ProcessDisplayPhase.COMPLETE, phase("exited", 0))
        assertEquals(ProcessDisplayPhase.FAILED, phase("exited", 1))
        assertEquals(ProcessDisplayPhase.UNKNOWN, phase("unknown", 0))
    }

    private fun phase(status: String, exitCode: Int? = null) = processDisplayPhase(
        GatewayProcess("process", "command", status = status, exitCode = exitCode),
    )
}
