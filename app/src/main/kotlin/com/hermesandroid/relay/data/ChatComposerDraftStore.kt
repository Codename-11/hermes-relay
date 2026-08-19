package com.hermesandroid.relay.data

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.WeakHashMap
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Immutable owner of one composer draft.
 *
 * Callers must supply stable ids rather than display labels. [sessionId] may be
 * a server id or a stable client-generated id for a not-yet-created session.
 * [draftId] separates the primary composer from any future named draft slot.
 */
data class ChatComposerDraftKey(
    val connectionId: String,
    val profileId: String,
    val sessionId: String,
    val draftId: String = PRIMARY_DRAFT_ID,
) {
    init {
        require(connectionId.isNotBlank()) { "connectionId must not be blank" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(draftId.isNotBlank()) { "draftId must not be blank" }
    }

    companion object {
        const val PRIMARY_DRAFT_ID = "primary"
        const val DEFAULT_PROFILE_ID = "default"
    }
}

/** Message references associated with composer content. */
data class ChatComposerDraftContext(
    val quotedMessageId: String? = null,
    val editingMessageId: String? = null,
) {
    internal fun normalized(): ChatComposerDraftContext = copy(
        quotedMessageId = quotedMessageId?.takeIf(String::isNotBlank),
        editingMessageId = editingMessageId?.takeIf(String::isNotBlank),
    )
}

/**
 * Complete restorable state for one composer.
 *
 * Selection offsets use the same start-inclusive/end-exclusive convention as
 * Compose text fields. The store clamps them whenever the text changes so a
 * restored selection can never address outside the restored string.
 */
data class ChatComposerDraft(
    val text: String = "",
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val context: ChatComposerDraftContext = ChatComposerDraftContext(),
    val attachments: List<Attachment> = emptyList(),
) {
    val isEmpty: Boolean
        get() = text.isEmpty() &&
            context.quotedMessageId == null &&
            context.editingMessageId == null &&
            attachments.isEmpty()

    internal fun normalized(): ChatComposerDraft {
        val normalizedStart = selectionStart.coerceIn(0, text.length)
        val normalizedEnd = selectionEnd.coerceIn(0, text.length)
        return copy(
            selectionStart = minOf(normalizedStart, normalizedEnd),
            selectionEnd = maxOf(normalizedStart, normalizedEnd),
            context = context.normalized(),
            attachments = attachments.toList(),
        )
    }
}

/**
 * Session-owned composer state.
 *
 * This store is deliberately memory-only: outbound [Attachment.content] can
 * contain large Base64 payloads and must not enter Preferences DataStore. Keep
 * one instance in the chat owner (normally its ViewModel) so drafts survive
 * navigation and Activity recreation. Process death starts with empty drafts;
 * a future durable implementation should persist URI grants, not attachment
 * bytes.
 */
interface ChatComposerDraftStore {
    fun observe(key: ChatComposerDraftKey): Flow<ChatComposerDraft>
    suspend fun snapshot(key: ChatComposerDraftKey): ChatComposerDraft
    suspend fun save(key: ChatComposerDraftKey, draft: ChatComposerDraft)
    suspend fun update(
        key: ChatComposerDraftKey,
        transform: (ChatComposerDraft) -> ChatComposerDraft,
    )
    suspend fun remove(key: ChatComposerDraftKey)
    suspend fun removeSession(connectionId: String, profileId: String, sessionId: String)
    suspend fun clear()
}

class InMemoryChatComposerDraftStore : ChatComposerDraftStore {
    private val drafts = MutableStateFlow<Map<ChatComposerDraftKey, ChatComposerDraft>>(emptyMap())

    override fun observe(key: ChatComposerDraftKey): Flow<ChatComposerDraft> =
        drafts
            .map { it[key] ?: ChatComposerDraft() }
            .distinctUntilChanged()

    override suspend fun snapshot(key: ChatComposerDraftKey): ChatComposerDraft =
        drafts.value[key] ?: ChatComposerDraft()

    override suspend fun save(key: ChatComposerDraftKey, draft: ChatComposerDraft) {
        synchronized(drafts) {
            val normalized = draft.normalized()
            drafts.value = if (normalized.isEmpty) {
                drafts.value - key
            } else {
                drafts.value + (key to normalized)
            }
        }
    }

    override suspend fun update(
        key: ChatComposerDraftKey,
        transform: (ChatComposerDraft) -> ChatComposerDraft,
    ) {
        save(key, transform(snapshot(key)))
    }

    override suspend fun remove(key: ChatComposerDraftKey) {
        synchronized(drafts) {
            drafts.value = drafts.value - key
        }
    }

    override suspend fun removeSession(connectionId: String, profileId: String, sessionId: String) {
        synchronized(drafts) {
            drafts.value = drafts.value.filterKeys { key ->
                key.connectionId != connectionId ||
                    key.profileId != profileId ||
                    key.sessionId != sessionId
            }
        }
    }

    override suspend fun clear() {
        synchronized(drafts) {
            drafts.value = emptyMap()
        }
    }
}

/**
 * App-private durable composer storage.
 *
 * The caller supplies a directory under `noBackupFilesDir`: drafts survive
 * process death and ordinary app exits but never enter Android cloud backup.
 * Metadata stays small JSON while attachment bytes are content-addressed blobs,
 * so typing does not repeatedly rewrite Base64 payloads.
 */
class PersistentChatComposerDraftStore(
    private val root: File,
) : ChatComposerDraftStore {
    private val mutex = Mutex()
    private val updates = MutableSharedFlow<ChatComposerDraftKey>(extraBufferCapacity = 64)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val draftsDir = File(root, "drafts")
    private val blobsDir = File(root, "blobs")
    private val contentBlobIds = WeakHashMap<String, String>()

    override fun observe(key: ChatComposerDraftKey): Flow<ChatComposerDraft> = flow {
        emit(snapshot(key))
        emitAll(
            updates
                .filter { it == key }
                .map { snapshot(key) }
                .distinctUntilChanged(),
        )
    }.distinctUntilChanged()

    override suspend fun snapshot(key: ChatComposerDraftKey): ChatComposerDraft = withContext(Dispatchers.IO) {
        mutex.withLock { readDraft(key) }
    }

    override suspend fun save(key: ChatComposerDraftKey, draft: ChatComposerDraft) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val normalized = draft.normalized()
                if (normalized.isEmpty) {
                    draftFile(key).delete()
                } else {
                    ensureDirectories()
                    val persisted = normalized.toPersisted(key)
                    atomicWrite(
                        draftFile(key),
                        json.encodeToString(PersistedDraft.serializer(), persisted)
                            .toByteArray(Charsets.UTF_8),
                    )
                }
                pruneAndCollect(except = key)
            }
        }
        updates.tryEmit(key)
    }

    override suspend fun update(
        key: ChatComposerDraftKey,
        transform: (ChatComposerDraft) -> ChatComposerDraft,
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val normalized = transform(readDraft(key)).normalized()
                if (normalized.isEmpty) {
                    draftFile(key).delete()
                } else {
                    ensureDirectories()
                    atomicWrite(
                        draftFile(key),
                        json.encodeToString(
                            PersistedDraft.serializer(),
                            normalized.toPersisted(key),
                        ).toByteArray(Charsets.UTF_8),
                    )
                }
                pruneAndCollect(except = key)
            }
        }
        updates.tryEmit(key)
    }

    override suspend fun remove(key: ChatComposerDraftKey) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                draftFile(key).delete()
                collectOrphanBlobs()
            }
        }
        updates.tryEmit(key)
    }

    override suspend fun removeSession(connectionId: String, profileId: String, sessionId: String) {
        val removed = mutableListOf<ChatComposerDraftKey>()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                draftFiles().forEach { file ->
                    val persisted = readPersisted(file) ?: return@forEach
                    val key = persisted.key.toDomain()
                    if (
                        key.connectionId == connectionId &&
                        key.profileId == profileId &&
                        key.sessionId == sessionId
                    ) {
                        file.delete()
                        removed += key
                    }
                }
                collectOrphanBlobs()
            }
        }
        removed.forEach(updates::tryEmit)
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                root.listFiles().orEmpty().forEach(File::deleteRecursively)
                contentBlobIds.clear()
            }
        }
    }

    private fun ChatComposerDraft.toPersisted(key: ChatComposerDraftKey): PersistedDraft =
        PersistedDraft(
            key = PersistedKey.from(key),
            text = text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            quotedMessageId = context.quotedMessageId,
            editingMessageId = context.editingMessageId,
            attachments = attachments.mapNotNull(::persistAttachment),
            savedAtEpochMs = System.currentTimeMillis(),
        )

    private fun persistAttachment(attachment: Attachment): PersistedAttachment? {
        val rawBytes = attachment.composerRawText
            ?.takeIf { attachment.isLargePaste }
            ?.toByteArray(Charsets.UTF_8)
        val cachedBlobId = if (rawBytes == null) contentBlobIds[attachment.content] else null
        val cachedBlob = cachedBlobId?.let { File(blobsDir, "$it.blob") }
        if (cachedBlobId != null && cachedBlob?.exists() == true) {
            return attachment.toPersistedAttachment(cachedBlobId)
        }
        val bytes = rawBytes
            ?: runCatching { Base64.getDecoder().decode(attachment.content) }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        val blobId = sha256(bytes)
        val blob = File(blobsDir, "$blobId.blob")
        if (!blob.exists()) atomicWrite(blob, bytes)
        if (rawBytes == null) contentBlobIds[attachment.content] = blobId
        return attachment.toPersistedAttachment(blobId)
    }

    private fun Attachment.toPersistedAttachment(blobId: String): PersistedAttachment =
        PersistedAttachment(
            contentType = contentType,
            blobId = blobId,
            fileName = fileName,
            fileSize = fileSize,
            sensitive = sensitive,
            isLargePaste = isLargePaste,
            composerId = composerId,
        )

    private fun readDraft(key: ChatComposerDraftKey): ChatComposerDraft {
        val persisted = readPersisted(draftFile(key)) ?: return ChatComposerDraft()
        if (persisted.key.toDomain() != key) return ChatComposerDraft()
        return ChatComposerDraft(
            text = persisted.text,
            selectionStart = persisted.selectionStart,
            selectionEnd = persisted.selectionEnd,
            context = ChatComposerDraftContext(
                quotedMessageId = persisted.quotedMessageId,
                editingMessageId = persisted.editingMessageId,
            ),
            attachments = persisted.attachments.mapNotNull { attachment ->
                val blob = File(blobsDir, "${attachment.blobId}.blob")
                val bytes = runCatching { blob.readBytes() }.getOrNull()
                    ?.takeIf(ByteArray::isNotEmpty) ?: return@mapNotNull null
                val content = Base64.getEncoder().encodeToString(bytes)
                contentBlobIds[content] = attachment.blobId
                Attachment(
                    contentType = attachment.contentType,
                    content = content,
                    fileName = attachment.fileName,
                    fileSize = attachment.fileSize ?: bytes.size.toLong(),
                    sensitive = attachment.sensitive,
                    isLargePaste = attachment.isLargePaste,
                    composerId = attachment.composerId,
                )
            },
        ).normalized()
    }

    private fun readPersisted(file: File): PersistedDraft? = runCatching {
        json.decodeFromString(PersistedDraft.serializer(), file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun pruneAndCollect(except: ChatComposerDraftKey) {
        val exceptFile = draftFile(except)
        val candidates = draftFiles()
            .filterNot { it == exceptFile }
            .sortedBy(File::lastModified)
        candidates
            .take((draftFiles().size - MAX_DRAFTS).coerceAtLeast(0))
            .forEach { it.delete() }
        collectOrphanBlobs()
        for (oldest in candidates) {
            if (blobsDir.listFiles().orEmpty().sumOf(File::length) <= MAX_BLOB_BYTES) break
            if (oldest.exists()) {
                oldest.delete()
                collectOrphanBlobs()
            }
        }
    }

    private fun collectOrphanBlobs() {
        val referenced = draftFiles()
            .mapNotNull(::readPersisted)
            .flatMap { draft -> draft.attachments.map(PersistedAttachment::blobId) }
            .toSet()
        blobsDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "blob" && it.nameWithoutExtension !in referenced }
            .forEach(File::delete)
    }

    private fun ensureDirectories() {
        check(draftsDir.exists() || draftsDir.mkdirs()) { "Could not create composer draft directory" }
        check(blobsDir.exists() || blobsDir.mkdirs()) { "Could not create composer blob directory" }
    }

    private fun draftFiles(): List<File> = draftsDir.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }

    private fun draftFile(key: ChatComposerDraftKey): File =
        File(draftsDir, "${sha256(key.storageIdentity().toByteArray(Charsets.UTF_8))}.json")

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "Could not create composer storage directory" }
        }
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun ChatComposerDraftKey.storageIdentity(): String =
        listOf(connectionId, profileId, sessionId, draftId).joinToString("\u0000")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val MAX_DRAFTS = 64
        private const val MAX_BLOB_BYTES = 128L * 1024L * 1024L
    }
}

@Serializable
private data class PersistedDraft(
    val key: PersistedKey,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val quotedMessageId: String? = null,
    val editingMessageId: String? = null,
    val attachments: List<PersistedAttachment> = emptyList(),
    val savedAtEpochMs: Long,
)

@Serializable
private data class PersistedKey(
    val connectionId: String,
    val profileId: String,
    val sessionId: String,
    val draftId: String,
) {
    fun toDomain(): ChatComposerDraftKey = ChatComposerDraftKey(
        connectionId = connectionId,
        profileId = profileId,
        sessionId = sessionId,
        draftId = draftId,
    )

    companion object {
        fun from(key: ChatComposerDraftKey): PersistedKey = PersistedKey(
            connectionId = key.connectionId,
            profileId = key.profileId,
            sessionId = key.sessionId,
            draftId = key.draftId,
        )
    }
}

@Serializable
private data class PersistedAttachment(
    val contentType: String,
    val blobId: String,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val sensitive: Boolean = false,
    val isLargePaste: Boolean = false,
    val composerId: String? = null,
)
