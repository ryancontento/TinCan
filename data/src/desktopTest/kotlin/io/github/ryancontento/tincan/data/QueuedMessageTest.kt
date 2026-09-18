package io.github.ryancontento.tincan.data

import io.github.ryancontento.tincan.data.db.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The behaviour that makes a sleeping MacBook survivable rather than lossy. */
class QueuedMessageTest {

    @Test
    fun a_message_composed_while_offline_is_queued_not_lost() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            val msg = repo.appendUserMessage(id, "are you awake", TEST_SERVER)
            repo.markPending(msg)

            val queued = repo.oldestPendingMessage(id)
            assertNotNull(queued)
            assertEquals("are you awake", queued.content)
            assertEquals(MessageStatus.PENDING, queued.status)
        }
    }

    @Test
    fun delivering_clears_the_queue() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            val msg = repo.appendUserMessage(id, "hello", TEST_SERVER)
            repo.markPending(msg)
            repo.markDelivered(msg)

            assertNull(repo.oldestPendingMessage(id))
        }
    }

    @Test
    fun the_oldest_queued_message_is_delivered_first() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            val first = repo.appendUserMessage(id, "first", TEST_SERVER)
            val second = repo.appendUserMessage(id, "second", TEST_SERVER)
            repo.markPending(second)
            repo.markPending(first)

            // Insertion order, not the order they were marked — otherwise a
            // conversation would be replayed out of sequence.
            assertEquals("first", repo.oldestPendingMessage(id)?.content)
        }
    }

    @Test
    fun a_queued_message_still_counts_as_history_so_context_is_not_lost() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            val msg = repo.appendUserMessage(id, "queued question", TEST_SERVER)
            repo.markPending(msg)

            assertEquals(1, repo.historyFor(id).size)
        }
    }

    @Test
    fun conversations_holding_queued_messages_are_reported_for_retry() = runTest {
        withRepo { repo, _ ->
            val a = repo.createConversation(TEST_SERVER, "phi4", null)
            val b = repo.createConversation(TEST_SERVER, "phi4", null)
            repo.markPending(repo.appendUserMessage(a, "one", TEST_SERVER))
            repo.appendUserMessage(b, "two", TEST_SERVER)

            val withPending = repo.observeConversationsWithPendingMessages().first()
            assertEquals(listOf(a), withPending)
        }
    }

    @Test
    fun only_a_trailing_incomplete_reply_can_be_continued() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            val msg = repo.beginAssistantMessage(id, "phi4", TEST_SERVER)
            repo.finishAssistantMessage(msg, id, "half an ans", MessageStatus.INCOMPLETE, null, null)

            assertNotNull(repo.resumableReply(id))

            // Once the user says something else, the truncated reply is history;
            // continuing it would rewrite text they have already read past.
            repo.appendUserMessage(id, "never mind", TEST_SERVER)
            assertNull(repo.resumableReply(id))
        }
    }

    @Test
    fun a_completed_reply_is_not_resumable() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            val msg = repo.beginAssistantMessage(id, "phi4", TEST_SERVER)
            repo.finishAssistantMessage(msg, id, "all done", MessageStatus.COMPLETE, null, null)

            assertNull(repo.resumableReply(id))
        }
    }

    @Test
    fun resuming_reopens_the_same_row_so_the_prefix_is_kept() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            val msg = repo.beginAssistantMessage(id, "phi4", TEST_SERVER)
            repo.finishAssistantMessage(msg, id, "The M1 Pro runs", MessageStatus.INCOMPLETE, null, null)

            repo.resumeAssistantMessage(msg)
            repo.updateStreamingBody(msg, "The M1 Pro runs at 200GB/s", null)

            val messages = repo.observeMessages(id).first()
            assertEquals(1, messages.size, "continuing must not create a second bubble")
            assertTrue(messages.single().content.startsWith("The M1 Pro runs"))
        }
    }
}
