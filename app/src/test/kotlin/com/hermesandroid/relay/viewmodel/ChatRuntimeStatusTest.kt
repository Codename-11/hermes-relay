package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRuntimeStatusTest {

    @Test
    fun `gateway ready is healthy primary even when optional API is missing`() {
        assertEquals(
            ChatRuntimeStatus.Connected(ChatTransportPath.Gateway, fallback = false),
            resolveChatRuntimeStatus(
                gateway = ChatTransportReadiness.Ready,
                apiSse = ChatTransportReadiness.NotConfigured,
            ),
        )
    }

    @Test
    fun `gateway ready wins when both chat transports are ready`() {
        assertEquals(
            ChatRuntimeStatus.Connected(ChatTransportPath.Gateway, fallback = false),
            resolveChatRuntimeStatus(
                gateway = ChatTransportReadiness.Ready,
                apiSse = ChatTransportReadiness.Ready,
            ),
        )
    }

    @Test
    fun `API SSE ready is healthy fallback when gateway is unavailable`() {
        assertEquals(
            ChatRuntimeStatus.Connected(ChatTransportPath.ApiSse, fallback = true),
            resolveChatRuntimeStatus(
                gateway = ChatTransportReadiness.Unavailable,
                apiSse = ChatTransportReadiness.Ready,
            ),
        )
    }

    @Test
    fun `ready API fallback wins while gateway is still connecting`() {
        assertEquals(
            ChatRuntimeStatus.Connected(ChatTransportPath.ApiSse, fallback = true),
            resolveChatRuntimeStatus(
                gateway = ChatTransportReadiness.Connecting,
                apiSse = ChatTransportReadiness.Ready,
            ),
        )
    }

    @Test
    fun `connecting is exposed only when neither transport is ready`() {
        assertEquals(
            ChatRuntimeStatus.Connecting,
            resolveChatRuntimeStatus(
                gateway = ChatTransportReadiness.Connecting,
                apiSse = ChatTransportReadiness.NotConfigured,
            ),
        )
        assertEquals(
            ChatRuntimeStatus.Connecting,
            resolveChatRuntimeStatus(
                gateway = ChatTransportReadiness.Unavailable,
                apiSse = ChatTransportReadiness.Connecting,
            ),
        )
    }

    @Test
    fun `unavailable requires no ready or connecting chat transport`() {
        assertEquals(
            ChatRuntimeStatus.Unavailable,
            resolveChatRuntimeStatus(
                gateway = ChatTransportReadiness.Unavailable,
                apiSse = ChatTransportReadiness.NotConfigured,
            ),
        )
        assertEquals(
            ChatRuntimeStatus.Unavailable,
            resolveChatRuntimeStatus(
                gateway = ChatTransportReadiness.NotConfigured,
                apiSse = ChatTransportReadiness.NotConfigured,
            ),
        )
    }
}
