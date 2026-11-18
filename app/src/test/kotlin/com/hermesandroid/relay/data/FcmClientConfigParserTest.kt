package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmClientConfigParserTest {
    @Test
    fun parse_googleServicesJson_extractsFields() {
        val raw = """
            {
              "project_info": {
                "project_number": "123456789012",
                "project_id": "my-demo",
                "storage_bucket": "my-demo.appspot.com"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:123456789012:android:abcdef",
                    "android_client_info": {
                      "package_name": "com.axiomlabs.hermesrelay.sideload"
                    }
                  },
                  "api_key": [
                    { "current_key": "AIzaSyDemoKey" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val cfg = FcmClientConfigParser.parse(raw).getOrThrow()
        assertEquals("my-demo", cfg.projectId)
        assertEquals("1:123456789012:android:abcdef", cfg.applicationId)
        assertEquals("AIzaSyDemoKey", cfg.apiKey)
        assertEquals("123456789012", cfg.gcmSenderId)
        assertTrue(cfg.isComplete)
    }

    @Test
    fun parse_compactObject_works() {
        val raw = """
            {
              "project_id": "p",
              "application_id": "1:1:android:x",
              "api_key": "k",
              "gcm_sender_id": "99"
            }
        """.trimIndent()
        val cfg = FcmClientConfigParser.parse(raw).getOrThrow()
        assertEquals("p", cfg.projectId)
        assertEquals("99", cfg.gcmSenderId)
    }

    @Test
    fun parse_incomplete_fails() {
        val result = FcmClientConfigParser.parse("""{"project_id":"only"}""")
        assertTrue(result.isFailure)
        assertFalse(FcmClientConfig(projectId = "only").isComplete)
    }
}
