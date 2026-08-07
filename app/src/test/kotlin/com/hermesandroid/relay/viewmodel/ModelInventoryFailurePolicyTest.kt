package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.ApiModelRoutingErrorCode
import com.hermesandroid.relay.network.upstream.ApiModelRoutingException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ModelInventoryFailurePolicyTest {
    @Test
    fun `background inventory failure stays out of global notices`() {
        val failure = ApiModelRoutingException(
            ApiModelRoutingErrorCode.INVENTORY_UNAVAILABLE,
            "Model inventory could not be loaded.",
        )

        assertNull(modelInventoryFailureNotice(failure, userInitiated = false))
    }

    @Test
    fun `user initiated inventory failure remains actionable`() {
        val failure = ApiModelRoutingException(
            ApiModelRoutingErrorCode.INVENTORY_UNAVAILABLE,
            "Model inventory unavailable (HTTP 503).",
        )

        assertEquals(
            "Couldn't refresh API model inventory: Model inventory unavailable (HTTP 503).",
            modelInventoryFailureNotice(failure, userInitiated = true),
        )
    }

    @Test
    fun `routing failure retains the network cause used by diagnostics`() {
        val cause = SocketTimeoutException("timeout")
        val failure = ApiModelRoutingException(
            ApiModelRoutingErrorCode.INVENTORY_UNAVAILABLE,
            "Model inventory could not be loaded.",
            cause,
        )

        assertSame(cause, failure.cause)
    }
}
