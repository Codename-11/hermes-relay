package com.hermesandroid.relay.network.shared

import com.hermesandroid.relay.data.BrokerEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.ProxyEndpoint
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.data.hasHermesReach
import com.hermesandroid.relay.data.isValidHermesReach
import com.hermesandroid.relay.data.replaceHermesReachCredential
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.io.IOException

class HermesReachTransportTest {
    private val hostId = canonicalId(1)
    private val streamId = canonicalId(2)
    private val routeToken = canonicalToken(4)
    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test
    fun routeRequiresWssKnownCredentialAndPinnedInnerSecureLink() {
        val broker = BrokerEndpoint(
            url = "wss://broker.example/v1/connect",
            hostId = hostId,
            credentialKind = "bootstrap",
            token = routeToken,
        )
        assertTrue(broker.isValidHermesReach())
        assertFalse(broker.copy(url = "ws://broker.example/v1/connect").isValidHermesReach())
        assertFalse(broker.copy(credentialKind = "unknown").isValidHermesReach())

        val missingInnerTrust = EndpointCandidate(role = "plugin_proxy", broker = broker)
        assertFalse(missingInnerTrust.hasHermesReach())
        assertNull(missingInnerTrust.hermesReachRouteOrNull())
    }

    @Test
    fun registrationUsesCanonicalV1ShapeAndDoesNotExposeHermesCredentials() {
        val route = HermesReachRoute(
            brokerUrl = "wss://broker.example",
            hostId = hostId,
            credentialKind = "route",
            token = routeToken,
        )
        val connectionId = canonicalId(3)
        val payload = Json.parseToJsonElement(
            HermesReachHandshake.registration(route, connectionId),
        ).jsonObject
        assertEquals("register", payload.getValue("type").jsonPrimitive.content)
        assertEquals("1", payload.getValue("protocol_version").jsonPrimitive.content)
        assertEquals("client", payload.getValue("role").jsonPrimitive.content)
        assertEquals(hostId, payload.getValue("host_id").jsonPrimitive.content)
        assertEquals(connectionId, payload.getValue("connection_id").jsonPrimitive.content)
        assertEquals("route", payload.getValue("credential_kind").jsonPrimitive.content)
        assertEquals(routeToken, payload.getValue("token").jsonPrimitive.content)
        assertEquals(setOf("type", "protocol_version", "role", "host_id", "connection_id", "credential_kind", "token"), payload.keys)
    }

    @Test
    fun matchedResponseRequiresV1AndCanonical128BitStreamId() {
        assertNull(HermesReachHandshake.validateMatched(
            """{"type":"matched","protocol_version":1,"stream_id":"$streamId"}""",
        ))
        assertTrue(HermesReachHandshake.validateMatched(
            """{"type":"matched","protocol_version":1,"stream_id":"short"}""",
        )!!.contains("mismatched"))
        assertTrue(HermesReachHandshake.validateMatched(
            """{"type":"matched","protocol_version":2,"stream_id":"$streamId"}""",
        )!!.contains("mismatched"))
        assertTrue(HermesReachHandshake.validateMatched(
            """{"type":"error","code":"host_offline"}""",
        )!!.contains("host_offline"))
    }

    @Test
    fun reachPresentationStillRequiresPinnedSecureLink() {
        val candidate = EndpointCandidate(
            role = "outbound_broker",
            proxy = ProxyEndpoint(
                url = "https://paired-host.example:9443",
                transportHint = "brokered_tls",
                pinSha256 = pin,
                surfaces = listOf("relay", "api", "dashboard"),
            ),
            broker = BrokerEndpoint(
                url = "wss://broker.example/v1/connect",
                hostId = hostId,
                credentialKind = "bootstrap",
                token = routeToken,
            ),
        )
        assertTrue(candidate.hasHermesReach())
        assertEquals("Hermes Reach", candidate.displayLabel())
        assertEquals("wss://broker.example/v1/connect", candidate.hermesReachRouteOrNull()?.tunnelUrlOrNull())
    }

    @Test
    fun receiveQueueRejectsFrameFloodAndTracksPartialConsumption() {
        val input = ReachInputStream()
        repeat(REACH_MAX_QUEUED_FRAMES) {
            assertTrue(input.offer(byteArrayOf(1, 2, 3)))
        }
        assertFalse(input.offer(byteArrayOf(4)))

        val target = ByteArray(2)
        assertEquals(2, input.read(target, 0, target.size))
        // The first frame still occupies one frame slot until fully consumed.
        assertFalse(input.offer(byteArrayOf(4)))
        assertEquals(3, input.read())
        assertTrue(input.offer(byteArrayOf(4)))
        input.close(IOException("done"))
    }

    @Test
    fun receiveQueueEnforcesAggregateByteLimit() {
        val input = ReachInputStream()
        repeat(REACH_MAX_QUEUED_BYTES / (1024 * 1024)) {
            assertTrue(input.offer(ByteArray(1024 * 1024)))
        }
        assertFalse(input.offer(byteArrayOf(1)))
        input.close(null)
    }

    @Test
    fun durableCredentialRotationOnlyReplacesExactAuthorityAndHostLocator() {
        fun candidate(host: String, hostId: String, token: String) = EndpointCandidate(
            role = "outbound_broker",
            security = "e2ee_pinned_tls",
            proxy = ProxyEndpoint(
                url = "https://paired-host.example:9443",
                pinSha256 = pin,
                surfaces = listOf("relay", "api", "dashboard"),
            ),
            broker = BrokerEndpoint(
                url = "wss://$host/v1/connect",
                hostId = hostId,
                credentialKind = "route",
                token = token,
            ),
        )
        val expected = candidate("broker.example", hostId, routeToken)
        val otherHost = candidate("broker.example", canonicalId(9), canonicalToken(9))
        val otherAuthority = candidate("other.example", hostId, canonicalToken(8))
        val replacement = expected.copy(
            broker = expected.broker!!.copy(token = canonicalToken(7)),
        )
        val updated = replaceHermesReachCredential(
            listOf(expected, otherHost, otherAuthority),
            expected.broker!!,
            replacement,
        )
        assertEquals(canonicalToken(7), updated[0].broker?.token)
        assertEquals(otherHost, updated[1])
        assertEquals(otherAuthority, updated[2])
    }

    private fun canonicalId(seed: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(16) { (it + seed).toByte() })

    private fun canonicalToken(seed: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32) { (it + seed).toByte() })
}
