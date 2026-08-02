package com.hermesandroid.relay.wake

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class WakeWordModelFiles(
    val directory: File,
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
    val keywords: File,
)

/**
 * Installs only the four runtime files needed by the fixed English phrase.
 *
 * Files come from the model repository linked by sherpa-onnx's official KWS
 * documentation. Every download is pinned by byte length and SHA-256 before
 * the install directory is promoted atomically.
 */
class WakeWordModelInstaller(
    context: Context,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val appContext = context.applicationContext
    private val modelRoot = File(appContext.filesDir, MODEL_DIRECTORY)

    suspend fun ensureInstalled(): Result<WakeWordModelFiles> = withContext(Dispatchers.IO) {
        runCatching {
            installedFiles()?.let { return@runCatching it }

            val staging = File(appContext.filesDir, "$MODEL_DIRECTORY.installing")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not create wake-word model directory" }

            try {
                MODEL_ARTIFACTS.forEach { artifact ->
                    downloadVerified(artifact, File(staging, artifact.fileName))
                }
                File(staging, KEYWORDS_FILE).writeText(KEYWORDS_CONTENT, Charsets.UTF_8)
                File(staging, INSTALL_MARKER).writeText(MODEL_VERSION, Charsets.UTF_8)

                modelRoot.deleteRecursively()
                if (!staging.renameTo(modelRoot)) {
                    throw IOException("Could not activate downloaded wake-word model")
                }
                installedFiles()
                    ?: throw IOException("Wake-word model failed post-install verification")
            } catch (t: Throwable) {
                staging.deleteRecursively()
                throw t
            }
        }
    }

    fun installedFiles(): WakeWordModelFiles? {
        if (File(modelRoot, INSTALL_MARKER).takeIf { it.isFile }?.readText()?.trim() != MODEL_VERSION) {
            return null
        }
        val byName = MODEL_ARTIFACTS.associate { artifact ->
            val file = File(modelRoot, artifact.fileName)
            if (!file.isFile || file.length() != artifact.byteLength) return null
            artifact.fileName to file
        }
        val keywords = File(modelRoot, KEYWORDS_FILE)
        if (!keywords.isFile || keywords.readText(Charsets.UTF_8) != KEYWORDS_CONTENT) return null
        return WakeWordModelFiles(
            directory = modelRoot,
            encoder = byName.getValue(ENCODER_FILE),
            decoder = byName.getValue(DECODER_FILE),
            joiner = byName.getValue(JOINER_FILE),
            tokens = byName.getValue(TOKENS_FILE),
            keywords = keywords,
        )
    }

    private fun downloadVerified(artifact: ModelArtifact, destination: File) {
        val request = Request.Builder().url("$MODEL_BASE_URL/${artifact.fileName}").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Wake-word model download failed (${response.code})")
            }
            val body = response.body
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            destination.outputStream().buffered().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                        if (written > artifact.byteLength) {
                            throw IOException("Wake-word model download exceeded expected size")
                        }
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (written != artifact.byteLength ||
                !actualHash.equals(artifact.sha256, ignoreCase = true)
            ) {
                destination.delete()
                throw IOException("Wake-word model integrity check failed for ${artifact.fileName}")
            }
        }
    }

    private data class ModelArtifact(
        val fileName: String,
        val byteLength: Long,
        val sha256: String,
    )

    companion object {
        const val MODEL_VERSION = "gigaspeech-3.3m-2024-01-01-int8-v1"
        private const val MODEL_DIRECTORY = "wake-word/$MODEL_VERSION"
        private const val INSTALL_MARKER = "installed.version"
        private const val KEYWORDS_FILE = "keywords.txt"
        private const val MODEL_BASE_URL =
            "https://www.modelscope.cn/models/pkufool/" +
                "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/resolve/master"

        const val ENCODER_FILE = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val DECODER_FILE = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        const val JOINER_FILE = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"

        // Generated with this model's published SentencePiece vocabulary.
        // No arbitrary-phrase tokenizer is bundled in the first release.
        const val KEYWORDS_CONTENT = "▁HE Y ▁HER ME S @HEY_HERMES\n"

        private val MODEL_ARTIFACTS = listOf(
            ModelArtifact(
                ENCODER_FILE,
                4_807_159,
                "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678",
            ),
            ModelArtifact(
                DECODER_FILE,
                1_063_189,
                "f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3",
            ),
            ModelArtifact(
                JOINER_FILE,
                163_380,
                "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c",
            ),
            ModelArtifact(
                TOKENS_FILE,
                5_006,
                "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53",
            ),
        )
    }
}
