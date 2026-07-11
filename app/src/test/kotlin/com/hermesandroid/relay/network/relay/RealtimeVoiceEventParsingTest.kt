package com.hermesandroid.relay.network.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeVoiceEventParsingTest {

    @Test
    fun brokerOkFieldMapsToTheSharedSuccessState() {
        val success = Json.parseToJsonElement("""{"ok":true}""").jsonObject
        val failure = Json.parseToJsonElement("""{"ok":false}""").jsonObject

        assertTrue(realtimeEventSuccess(success) == true)
        assertFalse(realtimeEventSuccess(failure) == true)
    }

    @Test
    fun explicitSuccessWinsWhenBothFieldsArePresent() {
        val event = Json.parseToJsonElement(
            """{"success":false,"ok":true}""",
        ).jsonObject

        assertEquals(false, realtimeEventSuccess(event))
    }

    @Test
    fun absentOrMalformedFieldsRemainUnknown() {
        assertNull(realtimeEventSuccess(Json.parseToJsonElement("{}").jsonObject))
        assertNull(
            realtimeEventSuccess(
                Json.parseToJsonElement("""{"ok":"not-a-boolean"}""").jsonObject,
            ),
        )
    }
}
