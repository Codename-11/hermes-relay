package com.hermesandroid.relay.network.upstream

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlled decoder pressure, separate from Android rendering and the transport harness. */
class GatewayPayloadMemoryTest {
    @Test
    fun omittedHistoryAvoidsDecoderExhaustionUnderBoundedHeap() {
        val directory = Files.createTempDirectory("gateway-memory").toFile()
        try {
            val full = File(directory, "full.json")
            full.bufferedWriter().use { writer ->
                writer.write("{\"id\":1,\"result\":{\"session_id\":\"live\",\"messages\":[{\"text\":\"")
                // 32 Mi characters, with escapes exercising the release stack's string builder.
                val block = "a\\n".repeat(1024)
                repeat(10923) { writer.write(block) }
                writer.write("\"}],\"running\":false}}")
            }
            val omitted = File(directory, "omitted.json").apply {
                writeText("""{"id":1,"result":{"session_id":"live","messages":[],"running":false}}""")
            }
            assertEquals("full history should reproduce decoder OOM", 42, decodeInBoundedProcess(full))
            assertEquals("metadata-only response should decode repeatedly", 0, decodeInBoundedProcess(omitted))
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    private fun decodeInBoundedProcess(payload: File): Int {
        val classpath = listOf(
            GatewayPayloadMemoryProbe::class.java, Json::class.java,
            KSerializer::class.java, Unit::class.java,
        ).map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).absolutePath }
            .distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val log = File(payload.parentFile, "${payload.name}.log")
        val process = ProcessBuilder(
            java, "-Xmx128m", "-XX:-CompactStrings", "-cp", classpath,
            GatewayPayloadMemoryProbe::class.java.name, payload.absolutePath,
        ).redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue("memory probe timed out", process.waitFor(30, TimeUnit.SECONDS))
            assertTrue("probe failed before decoding: ${log.readText().take(4000)}", process.exitValue() in setOf(0, 42))
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

object GatewayPayloadMemoryProbe {
    private lateinit var retained: ByteArray

    @JvmStatic
    fun main(args: Array<String>) {
        // Fixed retained state models an already-open transcript. No Compose or network is present.
        retained = ByteArray(16 * 1024 * 1024)
        val text = Files.readString(File(args.single()).toPath())
        try {
            repeat(20) { Json.parseToJsonElement(text) }
            check(retained.size == 16 * 1024 * 1024)
        } catch (_: OutOfMemoryError) {
            // Only decoding is caught: a failure reading the frame is a different test result.
            kotlin.system.exitProcess(42)
        }
    }
}
