package dev.evestaticmapplanner.embeddedai

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface EmbeddedAiConversationStore {
    fun load(): EmbeddedAiConversationArchive
    fun save(archive: EmbeddedAiConversationArchive)
}

object InMemoryOnlyEmbeddedAiConversationStore : EmbeddedAiConversationStore {
    override fun load() = EmbeddedAiConversationArchive()
    override fun save(archive: EmbeddedAiConversationArchive) = Unit
}

class JsonFileEmbeddedAiConversationStore(
    path: Path,
    private val warningSink: (String, Throwable?) -> Unit = { _, _ -> },
) : EmbeddedAiConversationStore {
    private val normalizedPath = path.toAbsolutePath().normalize()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override fun load(): EmbeddedAiConversationArchive {
        if (!Files.isRegularFile(normalizedPath)) return EmbeddedAiConversationArchive()
        return try {
            val persisted = json.decodeFromString<PersistedArchive>(Files.readString(normalizedPath, StandardCharsets.UTF_8))
            require(persisted.schemaVersion == SCHEMA_VERSION) {
                "Unsupported embedded AI conversation schema ${persisted.schemaVersion}"
            }
            persisted.toDomain()
        } catch (failure: Throwable) {
            warningSink("Embedded AI conversations could not be loaded; starting with an empty local history", failure)
            EmbeddedAiConversationArchive()
        }
    }

    override fun save(archive: EmbeddedAiConversationArchive) {
        val parent = normalizedPath.parent ?: error("Conversation history path has no parent")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".embedded-ai-conversations-", ".tmp")
        try {
            Files.writeString(
                temporary,
                json.encodeToString(PersistedArchive.from(archive)),
                StandardCharsets.UTF_8,
            )
            try {
                Files.move(
                    temporary,
                    normalizedPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, normalizedPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            warningSink("Embedded AI conversations could not be saved locally", failure)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Serializable
    private data class PersistedArchive(
        val schemaVersion: Int = SCHEMA_VERSION,
        val activeSessionId: String? = null,
        val sessions: List<PersistedSession> = emptyList(),
    ) {
        fun toDomain(): EmbeddedAiConversationArchive {
            val restored = sessions.mapNotNull(PersistedSession::toDomain)
            return EmbeddedAiConversationArchive(
                sessions = restored,
                activeSessionId = activeSessionId?.takeIf { id -> restored.any { it.id == id } },
            )
        }

        companion object {
            fun from(archive: EmbeddedAiConversationArchive) = PersistedArchive(
                activeSessionId = archive.activeSessionId,
                sessions = archive.sessions.map(PersistedSession::from),
            )
        }
    }

    @Serializable
    private data class PersistedSession(
        val sessionId: String,
        val title: String,
        val createdAt: String,
        val updatedAt: String,
        val messages: List<PersistedMessage>,
    ) {
        fun toDomain(): EmbeddedAiChatSession? = runCatching {
            val created = Instant.parse(createdAt)
            val restoredMessages = messages.mapNotNull(PersistedMessage::toDomain).takeLast(MAX_CHAT_MESSAGES)
            EmbeddedAiChatSession(
                id = sessionId.ifBlank { UUID.randomUUID().toString() },
                createdAt = created,
                messages = restoredMessages,
                title = title.trim().takeIf(String::isNotEmpty) ?: chatSessionTitle(restoredMessages),
                updatedAt = Instant.parse(updatedAt),
            )
        }.getOrNull()

        companion object {
            fun from(session: EmbeddedAiChatSession) = PersistedSession(
                sessionId = session.id,
                title = session.title,
                createdAt = session.createdAt.toString(),
                updatedAt = session.updatedAt.toString(),
                messages = session.messages.map(PersistedMessage::from),
            )
        }
    }

    @Serializable
    private data class PersistedMessage(
        val messageId: String,
        val role: String,
        val content: String,
        val timestamp: String,
        val status: String,
    ) {
        fun toDomain(): EmbeddedAiMessage? = runCatching {
            val restoredStatus = EmbeddedAiMessageStatus.valueOf(status)
            EmbeddedAiMessage(
                id = messageId.ifBlank { UUID.randomUUID().toString() },
                role = EmbeddedAiMessageRole.valueOf(role),
                content = if (restoredStatus == EmbeddedAiMessageStatus.THINKING) {
                    "The previous request was interrupted when the Planner closed."
                } else {
                    content
                },
                status = if (restoredStatus == EmbeddedAiMessageStatus.THINKING) {
                    EmbeddedAiMessageStatus.CANCELLED
                } else {
                    restoredStatus
                },
                timestamp = Instant.parse(timestamp),
            )
        }.getOrNull()

        companion object {
            fun from(message: EmbeddedAiMessage) = PersistedMessage(
                messageId = message.id,
                role = message.role.name,
                content = message.content,
                timestamp = message.timestamp.toString(),
                status = message.status.name,
            )
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
