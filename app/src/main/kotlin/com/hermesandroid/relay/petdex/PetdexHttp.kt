package com.hermesandroid.relay.petdex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal enum class PetdexRemoteKind { Catalog, Asset }

internal fun interface PetdexFetcher {
    suspend fun fetch(url: String, maxBytes: Long, kind: PetdexRemoteKind): ByteArray
}

internal object PetdexUrlPolicy {
    private val catalogHosts = setOf("petdex.dev", "assets.petdex.dev")
    private val assetHosts = setOf("assets.petdex.dev")

    fun isTrusted(url: String, kind: PetdexRemoteKind): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        if (parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        val hosts = if (kind == PetdexRemoteKind.Catalog) catalogHosts else assetHosts
        return parsed.host in hosts
    }
}

internal class SecurePetdexFetcher(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : PetdexFetcher {
    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun fetch(url: String, maxBytes: Long, kind: PetdexRemoteKind): ByteArray =
        withContext(Dispatchers.IO) {
            var current = trustedUrl(url, kind)
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                val request = Request.Builder()
                    .url(current)
                    .header("User-Agent", "Hermes-Relay-Android-Petdex")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        if (redirectCount == MAX_REDIRECTS) throw PetdexException("Too many Petdex redirects.")
                        current = redirectTarget(response, current, kind)
                    } else {
                        if (!response.isSuccessful) throw PetdexException("Petdex request failed (${response.code}).")
                        return@withContext readBounded(
                            response.body.byteStream(),
                            response.body.contentLength(),
                            maxBytes,
                        )
                    }
                }
            }
            throw PetdexException("Too many Petdex redirects.")
        }

    private fun trustedUrl(raw: String, kind: PetdexRemoteKind): HttpUrl {
        if (!PetdexUrlPolicy.isTrusted(raw, kind)) throw PetdexException("Untrusted Petdex URL.")
        return raw.toHttpUrlOrNull() ?: throw PetdexException("Invalid Petdex URL.")
    }

    private fun redirectTarget(response: Response, current: HttpUrl, kind: PetdexRemoteKind): HttpUrl {
        val location = response.header("Location") ?: throw PetdexException("Petdex redirect had no destination.")
        val resolved = current.resolve(location) ?: throw PetdexException("Invalid Petdex redirect.")
        return trustedUrl(resolved.toString(), kind)
    }

    private companion object {
        const val MAX_REDIRECTS = 3
    }
}

internal fun readBounded(input: InputStream, declaredLength: Long, maxBytes: Long): ByteArray {
    if (declaredLength < -1L) throw PetdexException("Petdex response had an invalid length.")
    if (maxBytes <= 0L || declaredLength > maxBytes) throw PetdexException("Petdex download is too large.")
    val initial = when {
        declaredLength in 1..maxBytes -> declaredLength.toInt()
        else -> minOf(maxBytes, 32L * 1024L).toInt()
    }
    val out = ByteArrayOutputStream(initial)
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw PetdexException("Petdex download is too large.")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
