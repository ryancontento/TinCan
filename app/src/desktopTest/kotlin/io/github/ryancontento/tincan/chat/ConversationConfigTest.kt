package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.data.db.ConversationEntity
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

    @Test
    fun a_conversations_own_options_win_and_blanks_follow_settings() {
        val settings = TinCanSettings(temperature = 0.7f, numCtx = 8192, keepAlive = "5m")
        val conversation = ConversationEntity(
            title = "t", defaultModelId = null, backendId = "srv", systemPrompt = null,
            createdAt = 0, updatedAt = 0, temperature = 0.1f, numCtx = null,
        )

        val options = resolveOptions(conversation, settings)
        assertEquals(0.1f, options.temperature)
        assertEquals(8192, options.numCtx)
        assertEquals("5m", options.keepAlive, "keep_alive is a server concern and stays global")

        assertEquals(0.7f, resolveOptions(null, settings).temperature)
    }

    @Test
    fun option_text_that_does_not_make_sense_means_follow_settings() {
        assertEquals(0.5f, parseTemperature(" 0.5 "))
        assertNull(parseTemperature(""))
        assertNull(parseTemperature("hot"))
        assertNull(parseTemperature("7"), "Ollama's range is 0 to 2")

        assertEquals(16384, parseNumCtx("16384"))
        assertNull(parseNumCtx("0"))
        assertNull(parseNumCtx("-5"))
    }
}
