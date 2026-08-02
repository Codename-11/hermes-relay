package com.hermesandroid.relay.petdex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PetdexCatalogClientTest {
    @Test
    fun `v2 parser resolves compact assets and attribution`() {
        val pets = PetdexCatalogParser.parseV2(
            """{
              "v":2,
              "assetBase":"https://assets.petdex.dev",
              "fields":["slug","displayName","kind","submittedBy","spritesheet","petJson","zip"],
              "pets":[["boba","Boba","creature","Maker","pets/boba/sprite.webp","pets/boba/pet.json",null]]
            }""",
        )

        assertEquals(1, pets.size)
        assertEquals("petdex-boba", pets.single().installedAvatarId)
        assertEquals("Maker", pets.single().submittedBy)
        assertEquals("https://assets.petdex.dev/pets/boba/sprite.webp", pets.single().spritesheetUrl)
        assertEquals("https://petdex.dev/pets/boba", pets.single().sourceUrl)
    }

    @Test
    fun `v1 parser drops untrusted asset hosts`() {
        val pets = PetdexCatalogParser.parseV1(
            """{"pets":[
              {"slug":"safe","displayName":"Safe","spritesheetUrl":"https://assets.petdex.dev/s.webp","petJsonUrl":"https://assets.petdex.dev/p.json"},
              {"slug":"unsafe","displayName":"Unsafe","spritesheetUrl":"https://assets.petdex.dev.evil.test/s.webp","petJsonUrl":"https://assets.petdex.dev/p.json"}
            ]}""",
        )

        assertEquals(listOf("safe"), pets.map { it.slug })
    }

    @Test
    fun `catalog uses five minute cache and v1 fallback`() = runBlocking {
        var now = 1_000L
        var v2Calls = 0
        var v1Calls = 0
        val fetcher = PetdexFetcher { url, _, _ ->
            if (url.endsWith("/v2")) {
                v2Calls++
                error("v2 unavailable")
            } else {
                v1Calls++
                V1.encodeToByteArray()
            }
        }
        val client = PetdexCatalogClient(fetcher) { now }

        client.fetchCatalog()
        client.fetchCatalog()
        assertEquals(1, v2Calls)
        assertEquals(1, v1Calls)

        now += 5 * 60 * 1000L
        client.fetchCatalog()
        assertEquals(2, v2Calls)
        assertEquals(2, v1Calls)
    }

    @Test
    fun `catalog cancellation does not trigger fallback`() {
        var calls = 0
        val client = PetdexCatalogClient(
            fetcher = PetdexFetcher { _, _, _ ->
                calls++
                throw CancellationException("cancelled")
            },
            nowMs = { 0L },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { client.fetchCatalog() }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `host policy is exact and HTTPS only`() {
        assertTrue(PetdexUrlPolicy.isTrusted("https://assets.petdex.dev/a.webp", PetdexRemoteKind.Asset))
        assertFalse(PetdexUrlPolicy.isTrusted("http://assets.petdex.dev/a.webp", PetdexRemoteKind.Asset))
        assertFalse(PetdexUrlPolicy.isTrusted("https://assets.petdex.dev.evil.test/a.webp", PetdexRemoteKind.Asset))
        assertFalse(PetdexUrlPolicy.isTrusted("https://petdex.dev/a.webp", PetdexRemoteKind.Asset))
        assertFalse(PetdexUrlPolicy.isTrusted("https://assets.petdex.dev:8443/a.webp", PetdexRemoteKind.Asset))
    }

    @Test
    fun `bounded reader rejects declared and streamed overflows`() {
        assertThrows(PetdexException::class.java) {
            readBounded(ByteArrayInputStream(byteArrayOf()), 11, 10)
        }
        assertThrows(PetdexException::class.java) {
            readBounded(ByteArrayInputStream(ByteArray(11)), -1, 10)
        }
        assertThrows(PetdexException::class.java) {
            readBounded(ByteArrayInputStream(byteArrayOf()), -2, 10)
        }
    }

    @Test
    fun `oversized catalog fields are rejected without becoming UI data`() {
        val hugeName = "x".repeat(257)
        val v1 = """{"pets":[{"slug":"boba","displayName":"$hugeName","spritesheetUrl":"https://assets.petdex.dev/s.webp","petJsonUrl":"https://assets.petdex.dev/p.json"}]}"""
        assertTrue(PetdexCatalogParser.parseV1(v1).isEmpty())

        val fields = (0 until 33).joinToString(",") { "\"field$it\"" }
        assertThrows(PetdexException::class.java) {
            PetdexCatalogParser.parseV2(
                """{"v":2,"assetBase":"https://assets.petdex.dev","fields":[$fields],"pets":[]}""",
            )
        }

        val oversizedRow = (0 until 33).joinToString(",") { "\"value$it\"" }
        val parsed = PetdexCatalogParser.parseV2(
            """{"v":2,"assetBase":"https://assets.petdex.dev","fields":["slug","displayName","spritesheet","petJson"],"pets":[[$oversizedRow]]}""",
        )
        assertTrue(parsed.isEmpty())
    }

    private companion object {
        const val V1 = """{"pets":[{"slug":"boba","displayName":"Boba","spritesheetUrl":"https://assets.petdex.dev/s.webp","petJsonUrl":"https://assets.petdex.dev/p.json"}]}"""
    }
}
