package io.github.ryancontento.tincan.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Search, and the two operations that rewrite a transcript in place. */
class SearchAndRewriteTest {

    @Test
    fun search_finds_a_message_and_names_the_conversation_it_is_in() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            repo.renameConversation(id, "Bandwidth")
            repo.appendUserMessage(id, "why does memory bandwidth matter", TEST_SERVER)
            repo.appendUserMessage(id, "unrelated question", TEST_SERVER)

            val hits = repo.search("bandwidth")

            assertEquals(1, hits.size)
            assertEquals("Bandwidth", hits.single().conversationTitle)
            assertEquals(id, hits.single().conversationId)
        }
    }

    @Test
    fun search_ignores_case() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            repo.appendUserMessage(id, "Ollama runs locally", TEST_SERVER)

            assertEquals(1, repo.search("ollama").size)
        }
    }

    /** Without ESCAPE these wildcards would match every message ever stored. */
    @Test
    fun a_search_term_containing_wildcards_is_treated_as_text() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            repo.appendUserMessage(id, "the battery is at 50% today", TEST_SERVER)
            repo.appendUserMessage(id, "nothing to do with power", TEST_SERVER)

            assertEquals(1, repo.search("50%").size)
            assertEquals(0, repo.search("%_%").size)
        }
    }

    @Test
    fun a_blank_term_returns_nothing_rather_than_the_whole_history() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            repo.appendUserMessage(id, "anything", TEST_SERVER)

            assertTrue(repo.search("   ").isEmpty())
        }
    }

    @Test
    fun truncating_removes_the_message_and_everything_after_it() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            val first = repo.appendUserMessage(id, "first question", TEST_SERVER)
            val reply = repo.beginAssistantMessage(id, "phi4", TEST_SERVER)
            repo.appendUserMessage(id, "second question", TEST_SERVER)

            repo.truncateFrom(id, reply)

            val remaining = repo.observeMessages(id).first()
            assertEquals(listOf(first), remaining.map { it.id })
        }
    }

    @Test
    fun the_last_reply_is_only_offered_when_the_transcript_ends_with_one() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", null)
            repo.appendUserMessage(id, "a question", TEST_SERVER)
            assertNull(repo.lastAssistantMessage(id), "a trailing question is not a reply")

            val reply = repo.beginAssistantMessage(id, "phi4", TEST_SERVER)
            assertEquals(reply, repo.lastAssistantMessage(id)?.id)
        }
    }

    @Test
    fun a_conversation_can_override_its_system_prompt() = runTest {
        withRepo { repo, _ ->
            val id = repo.createConversation(TEST_SERVER, "phi4", "inherited at creation")

            repo.setSystemPrompt(id, "just this thread")

            assertEquals("just this thread", repo.conversation(id)?.systemPrompt)
        }
    }

    @Test
    fun escaping_neutralises_the_characters_LIKE_treats_as_wildcards() {
        assertEquals("100\\% \\_ok\\_", escapeForLike("100% _ok_"))
        assertEquals("\\\\", escapeForLike("\\"))
    }
}
