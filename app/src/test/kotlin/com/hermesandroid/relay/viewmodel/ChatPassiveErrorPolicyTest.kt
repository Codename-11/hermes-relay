package com.hermesandroid.relay.viewmodel

import java.io.IOException
import java.net.ConnectException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPassiveErrorPolicyTest {
    @Test
    fun backgroundSessionAuthAndConnectivityStayOutOfGlobalSnackbar() {
        assertTrue(shouldSuppressPassiveSessionError("load_sessions", ConnectException("refused")))
        assertTrue(shouldSuppressPassiveSessionError("load_sessions", IOException("401 Unauthorized")))
        assertTrue(shouldSuppressPassiveSessionError("load_profile_sessions", IOException("HTTP 403")))
    }

    @Test
    fun serverAndInteractiveErrorsStillSurface() {
        assertFalse(shouldSuppressPassiveSessionError("load_sessions", IOException("HTTP 500")))
        assertFalse(shouldSuppressPassiveSessionError("create_session", IOException("401 Unauthorized")))
        assertFalse(shouldSuppressPassiveSessionError("send_message", IOException("401 Unauthorized")))
        assertFalse(shouldSuppressPassiveSessionError("media_fetch", IOException("401 Unauthorized")))
    }
}
