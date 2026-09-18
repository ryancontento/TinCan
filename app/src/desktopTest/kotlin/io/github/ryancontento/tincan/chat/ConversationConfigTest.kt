package io.github.ryancontento.tincan.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rule these tests pin down: a conversation runs under the prompt it was
 * created with. Before this, generation read the live setting, so editing the
 * default silently rewrote the behaviour of every conversation already on disk.
 */
class ConversationConfigTest {

    @Test
    fun a_conversation_keeps_its_own_prompt_when_the_default_changes() {
        assertEquals(
            "You are terse.",
            resolveSystemPrompt(conversationPrompt = "You are terse.", globalPrompt = "You are a pirate."),
        )
    }

    @Test
    fun an_empty_prompt_means_none_rather_than_fall_back_to_the_default() {
        assertNull(resolveSystemPrompt(conversationPrompt = "", globalPrompt = "You are a pirate."))
    }

    /** Rows written before conversations carried a copy have nothing to fall back on. */
    @Test
    fun a_legacy_conversation_still_follows_the_default() {
        assertEquals(
            "You are a pirate.",
            resolveSystemPrompt(conversationPrompt = null, globalPrompt = "You are a pirate."),
        )
    }

    @Test
    fun a_blank_default_is_no_prompt_at_all() {
        assertNull(resolveSystemPrompt(conversationPrompt = null, globalPrompt = "   "))
    }

    @Test
    fun the_conversation_model_wins_so_switching_threads_restores_it() {
        assertEquals("qwen3:8b", resolveModel(conversationModel = "qwen3:8b", globalModel = "phi4"))
        assertEquals("phi4", resolveModel(conversationModel = null, globalModel = "phi4"))
        assertNull(resolveModel(conversationModel = null, globalModel = null))
    }
}
