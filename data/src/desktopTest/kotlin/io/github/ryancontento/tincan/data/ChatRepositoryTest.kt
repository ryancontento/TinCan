package io.github.ryancontento.tincan.data

import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real SQLite on a real temp file, one database per test — Room holds a file
 * lock, so sharing a path across tests would deadlock rather than fail cleanly.
 */
private inline fun withRepo(block: (ChatRepository) -> Unit) {
    val dir = File(System.getProperty("java.io.tmpdir"), "tincan-db-${UUID.randomUUID()}")
    dir.mkdirs()
    val repo = createChatRepository(dir.absolutePath)
    try {
        block(repo)
    } finally {
        repo.close()
        dir.deleteRecursively()
    }
}

class ChatRepositoryTest {

    @Test
    fun a_conversation_round_trips_with_its_messages() = runTest {
        withRepo { repo ->
            val id = repo.createConversation("http://localhost:11434", "phi4", null)
            repo.appendUserMessage(id, "hello", "http://localhost:11434")

            val messages = repo.observeMessages(id).first()
            assertEquals(1, messages.size)
            assertEquals(MessageRole.USER, messages.single().role)
            assertEquals("hello", messages.single().content)
        }
    }

    @Test
    fun the_title_comes_from_the_first_message_but_never_overwrites_a_rename() = runTest {
        withRepo { repo ->
            val id = repo.createConversation("local", "phi4", null)
            repo.titleFromFirstMessageIfUnset(id, "Explain memory bandwidth\nand why it matters")

            // Only the first line, so a pasted essay does not become the title.
            assertEquals(
                "Explain memory bandwidth",
                repo.observeConversations().first().single().title,
            )

            repo.renameConversation(id, "Bandwidth notes")
            repo.titleFromFirstMessageIfUnset(id, "some later message")
            assertEquals(
                "Bandwidth notes",
                repo.observeConversations().first().single().title,
            )
        }
    }

    @Test
    fun a_streamed_reply_accumulates_and_then_records_its_stats() = runTest {
        withRepo { repo ->
            val id = repo.createConversation("local", "phi4", null)
            val msg = repo.beginAssistantMessage(id, "phi4", "local")

            assertEquals(MessageStatus.STREAMING, repo.observeMessages(id).first().single().status)

            repo.updateStreamingBody(msg, "Hel", null)
            repo.updateStreamingBody(msg, "Hello", null)
            repo.finishAssistantMessage(
                messageId = msg,
                conversationId = id,
                content = "Hello",
                status = MessageStatus.COMPLETE,
                errorCode = null,
                stats = GenerationStats(completionTokens = 3, evalDurationNanos = 173_639_000),
            )

            val stored = repo.observeMessages(id).first().single()
            assertEquals("Hello", stored.content)
            assertEquals(MessageStatus.COMPLETE, stored.status)
            assertEquals(3, stored.completionTokens)
            assertTrue(stored.tokensPerSecond!! > 15f, "tok/s should be derived from eval time only")
        }
    }

    @Test
    fun orphaned_streaming_rows_are_demoted_on_startup() = runTest {
        withRepo { repo ->
            val id = repo.createConversation("local", "phi4", null)
            val msg = repo.beginAssistantMessage(id, "phi4", "local")
            repo.updateStreamingBody(msg, "half a rep", null)

            // Simulates the process dying mid-generation: the row is left
            // STREAMING and nothing will ever finish it.
            repo.recoverInterruptedMessages()

            val stored = repo.observeMessages(id).first().single()
            assertEquals(
                MessageStatus.INCOMPLETE, stored.status,
                "a reply nobody is writing any more must not still claim to be streaming",
            )
            assertEquals("half a rep", stored.content, "partial output must survive")
        }
    }

    @Test
    fun deleting_a_conversation_takes_its_messages_with_it() = runTest {
        withRepo { repo ->
            val id = repo.createConversation("local", "phi4", null)
            repo.appendUserMessage(id, "hello", "local")
            repo.deleteConversation(id)

            // Cascade, not an orphaned row — enforced by the foreign key.
            assertTrue(repo.observeMessages(id).first().isEmpty())
            assertTrue(repo.observeConversations().first().isEmpty())
        }
    }

    @Test
    fun history_excludes_failed_turns_so_errors_do_not_poison_context() = runTest {
        withRepo { repo ->
            val id = repo.createConversation("local", "phi4", null)
            repo.appendUserMessage(id, "hello", "local")

            val failed = repo.beginAssistantMessage(id, "phi4", "local")
            repo.finishAssistantMessage(
                messageId = failed,
                conversationId = id,
                content = "half an answ",
                status = MessageStatus.FAILED,
                errorCode = "StreamInterrupted",
                stats = null,
            )

            val history = repo.historyFor(id)
            assertEquals(1, history.size)
            assertEquals(Role.USER, history.single().role)
        }
    }

    @Test
    fun a_reply_that_produced_nothing_leaves_no_empty_bubble() = runTest {
        withRepo { repo ->
            val id = repo.createConversation("local", "phi4", null)
            val msg = repo.beginAssistantMessage(id, "phi4", "local")
            repo.discardMessage(msg)

            assertTrue(repo.observeMessages(id).first().isEmpty())
        }
    }

    @Test
    fun each_message_records_the_model_that_produced_it() = runTest {
        withRepo { repo ->
            val id = repo.createConversation("local", "phi4", null)

            val first = repo.beginAssistantMessage(id, "phi4", "local")
            repo.finishAssistantMessage(first, id, "from phi4", MessageStatus.COMPLETE, null, null)

            // Switching models mid-thread must not rewrite what came before.
            val second = repo.beginAssistantMessage(id, "qwen3:8b", "local")
            repo.finishAssistantMessage(second, id, "from qwen", MessageStatus.COMPLETE, null, null)

            val models = repo.observeMessages(id).first().map { it.modelId }
            assertEquals(listOf("phi4", "qwen3:8b"), models)
        }
    }

    @Test
    fun a_user_message_records_no_model_because_nothing_generated_it() = runTest {
        withRepo { repo ->
            val id = repo.createConversation("local", "phi4", null)
            repo.appendUserMessage(id, "hello", "local")
            assertNull(repo.observeMessages(id).first().single().modelId)
        }
    }
}
