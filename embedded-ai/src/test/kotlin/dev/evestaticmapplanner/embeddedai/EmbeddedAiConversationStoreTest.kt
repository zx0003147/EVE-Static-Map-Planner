package dev.evestaticmapplanner.embeddedai

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddedAiConversationStoreTest {
    @Test
    fun `chat archive survives two independent JVM processes`() {
        val root = createTempDirectory("embedded-ai-process-restart-")
        try {
            val path = root.resolve("conversations.json")
            runConversationProbe("write", path)
            val restored = runConversationProbe("read", path)

            assertTrue(restored.contains("RESTORED:Chat B:Chat A:4"), restored)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `multiple chats and visible message order survive a fresh controller`() = runTest {
        val root = createTempDirectory("embedded-ai-history-")
        try {
            val path = root.resolve("conversations.json")
            val dispatcher = StandardTestDispatcher(testScheduler)
            var responseIndex = 0
            val answers = listOf("answer:Chat A first", "answer:Chat A second", "answer:Chat B first")
            val first = EmbeddedAiController(
                agentFactory = EmbeddedAiAgentFactory { testConversationAgent { answers[responseIndex++] } },
                dispatcher = dispatcher,
                conversationStore = JsonFileEmbeddedAiConversationStore(path),
            )

            first.send("Chat A first")
            advanceUntilIdle()
            first.send("Chat A second")
            advanceUntilIdle()
            first.newChat()
            advanceUntilIdle()
            first.send("Chat B first")
            advanceUntilIdle()
            first.shutdown()

            val restarted = EmbeddedAiController(
                agentFactory = EmbeddedAiAgentFactory { testConversationAgent { "restarted:$it" } },
                dispatcher = dispatcher,
                conversationStore = JsonFileEmbeddedAiConversationStore(path),
            )
            val sessions = restarted.state.value.sessions
            assertEquals(2, sessions.size)
            assertEquals("Chat B first", sessions[0].title)
            assertEquals("Chat A first", sessions[1].title)
            assertEquals(
                listOf("Chat A first", "answer:Chat A first", "Chat A second", "answer:Chat A second"),
                sessions[1].messages.map(EmbeddedAiMessage::content),
            )
            restarted.shutdown()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `restored chat is bounded context and persisted runtime ids are never authoritative`() = runTest {
        val root = createTempDirectory("embedded-ai-safe-history-")
        try {
            val path = root.resolve("conversations.json")
            val store = JsonFileEmbeddedAiConversationStore(path)
            val old = EmbeddedAiChatSession.create().copy(
                messages = listOf(
                    EmbeddedAiMessage("u1", EmbeddedAiMessageRole.USER, "创建一条路线"),
                    EmbeddedAiMessage("a1", EmbeddedAiMessageRole.ASSISTANT, "已创建路线 routeId=old-process-17"),
                ),
            ).let { it.copy(title = chatSessionTitle(it.messages), updatedAt = it.messages.last().timestamp) }
            store.save(EmbeddedAiConversationArchive(listOf(old), old.id))
            val prompts = mutableListOf<String>()
            val controller = EmbeddedAiController(
                agentFactory = EmbeddedAiAgentFactory { testConversationAgent { prompts += it; "done" } },
                dispatcher = StandardTestDispatcher(testScheduler),
                conversationStore = store,
            )

            controller.send("把那条删掉")
            advanceUntilIdle()

            assertTrue(prompts.single().contains("routeId=old-process-17"))
            assertTrue(prompts.single().contains("not a Planner fact, a tool result, an object ID source"))
            assertTrue(prompts.single().contains("Resolve mutable Planner objects"))
            val persisted = Files.readString(path)
            assertFalse(persisted.contains("actionId"))
            assertFalse(persisted.contains("confirmation"))
            controller.shutdown()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private fun runConversationProbe(mode: String, path: Path): String {
    val javaExecutable = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
    )
    val classpath = listOf(
        EmbeddedAiConversationProcessProbe::class.java,
        JsonFileEmbeddedAiConversationStore::class.java,
        Json::class.java,
        KSerializer::class.java,
        kotlin.jvm.internal.Intrinsics::class.java,
    ).mapNotNull { type ->
        type.protectionDomain?.codeSource?.location?.toURI()?.let(Path::of)?.toString()
    }.distinct().joinToString(File.pathSeparator)
    val process = ProcessBuilder(
        javaExecutable.toString(),
        "-cp",
        classpath,
        EmbeddedAiConversationProcessProbe::class.java.name,
        mode,
        path.toString(),
    ).redirectErrorStream(true).start()
    check(process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        "Conversation restart probe timed out"
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.exitValue() == 0) { "Conversation restart probe failed ($mode): $output" }
    return output
}

object EmbeddedAiConversationProcessProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val mode = arguments.singleOrNull(0) ?: error("Missing probe mode")
        val path = Path.of(arguments.singleOrNull(1) ?: error("Missing archive path"))
        val store = JsonFileEmbeddedAiConversationStore(path)
        when (mode) {
            "write" -> {
                val created = Instant.parse("2026-09-16T00:00:00Z")
                val chatA = EmbeddedAiChatSession(
                    id = "chat-a",
                    createdAt = created,
                    messages = listOf(
                        EmbeddedAiMessage("a-u1", EmbeddedAiMessageRole.USER, "Chat A", timestamp = created),
                        EmbeddedAiMessage("a-a1", EmbeddedAiMessageRole.ASSISTANT, "Answer A", timestamp = created.plusSeconds(1)),
                        EmbeddedAiMessage("a-u2", EmbeddedAiMessageRole.USER, "Follow-up A", timestamp = created.plusSeconds(2)),
                        EmbeddedAiMessage("a-a2", EmbeddedAiMessageRole.ASSISTANT, "Answer A2", timestamp = created.plusSeconds(3)),
                    ),
                    title = "Chat A",
                    updatedAt = created.plusSeconds(3),
                )
                val chatB = EmbeddedAiChatSession(
                    id = "chat-b",
                    createdAt = created.plusSeconds(4),
                    messages = listOf(
                        EmbeddedAiMessage("b-u1", EmbeddedAiMessageRole.USER, "Chat B", timestamp = created.plusSeconds(4)),
                        EmbeddedAiMessage("b-a1", EmbeddedAiMessageRole.ASSISTANT, "Answer B", timestamp = created.plusSeconds(5)),
                    ),
                    title = "Chat B",
                    updatedAt = created.plusSeconds(5),
                )
                store.save(EmbeddedAiConversationArchive(listOf(chatB, chatA), chatB.id))
                println("WROTE")
            }
            "read" -> {
                val archive = store.load()
                check(archive.activeSessionId == "chat-b")
                check(archive.sessions.map(EmbeddedAiChatSession::title) == listOf("Chat B", "Chat A"))
                check(archive.sessions.single { it.id == "chat-a" }.messages.map(EmbeddedAiMessage::content) ==
                    listOf("Chat A", "Answer A", "Follow-up A", "Answer A2"))
                println("RESTORED:${archive.sessions[0].title}:${archive.sessions[1].title}:" +
                    archive.sessions.single { it.id == "chat-a" }.messages.size)
            }
            else -> error("Unknown probe mode: $mode")
        }
    }
}

private fun Array<String>.singleOrNull(index: Int): String? = getOrNull(index)

private fun testConversationAgent(run: suspend (String) -> String) = object : EmbeddedAiAgent {
    override suspend fun run(prompt: String) = run(prompt)
    override suspend fun close() = Unit
}
