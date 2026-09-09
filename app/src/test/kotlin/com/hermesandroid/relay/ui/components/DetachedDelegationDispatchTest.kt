package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetachedDelegationDispatchTest {
    @Test
    fun completedSuccessfulStructuredDispatchIsRecognized() {
        assertTrue(isDetachedDelegationDispatch(call("""{"status":"dispatched"}""")))
        assertTrue(isDetachedDelegationDispatch(call("""{"mode":"background"}""")))
    }

    @Test
    fun synchronousDelegationAndUnstructuredTextRemainOrdinaryCompletion() {
        listOf(
            """{"status":"completed","results":["done"]}""",
            """{"result":"status=dispatched mode=background"}""",
            "dispatched in background",
            """{"status":{"status":"dispatched"}}""",
            """[{"status":"dispatched"}]""",
            "{broken",
        ).forEach { assertFalse(isDetachedDelegationDispatch(call(it))) }
    }

    @Test
    fun failedRunningAndOtherToolCallsAreNeverDispatchSuccess() {
        val dispatch = call("""{"status":"dispatched"}""")
        assertFalse(isDetachedDelegationDispatch(dispatch.copy(success = false)))
        assertFalse(isDetachedDelegationDispatch(dispatch.copy(success = null)))
        assertFalse(isDetachedDelegationDispatch(dispatch.copy(isComplete = false)))
        assertFalse(isDetachedDelegationDispatch(dispatch.copy(name = "terminal")))
        assertFalse(isDetachedDelegationDispatch(dispatch.copy(result = null)))
    }

    private fun call(result: String) = ToolCall(
        name = "delegate_task",
        args = null,
        result = result,
        success = true,
        isComplete = true,
    )
}
