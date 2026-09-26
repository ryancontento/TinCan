package io.github.ryancontento.tincan.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun user(text: String) = ChatMessage(Role.USER, text)
private fun assistant(text: String) = ChatMessage(Role.ASSISTANT, text)

/** A message costing roughly [tokens] tokens, for budgets that are easy to reason about. */
private fun sized(tokens: Int) = user("x".repeat((tokens * 3.6).toInt()))

class ContextWindowTest {

    @Test
    fun estimation_never_undershoots_a_plain_four_chars_per_token_rule() {
        // Under-estimating is the dangerous direction: it lets the server
        // truncate a conversation the client believed was safely within budget.
        val text = "The M1 Pro runs at 200GB/s of memory bandwidth."
        assertTrue(
            estimateTokens(text) >= text.length / 4,
            "estimate should be at least as large as the naive 4 chars/token rule",
        )
    }

    @Test
    fun empty_text_costs_nothing() {
        assertEquals(0, estimateTokens(""))
    }

    @Test
    fun a_conversation_that_fits_is_left_completely_alone() {
        val messages = listOf(user("hello"), assistant("hi"), user("how are you"))
        val plan = planContext(messages, budgetTokens = 4096)

        assertEquals(messages, plan.messages)
        assertEquals(0, plan.droppedCount)
        assertFalse(plan.trimmed)
    }

    @Test
    fun the_oldest_turns_are_dropped_first_when_the_budget_is_tight() {
        val messages = listOf(sized(100), sized(100), sized(100), sized(100))
        // Room for roughly two messages once the reply reserve is taken out.
        val plan = planContext(messages, budgetTokens = 300, reserveForReplyTokens = 50)

        assertTrue(plan.trimmed)
        assertTrue(plan.messages.size < messages.size)
        // Whatever survives must be the tail, in order.
        assertEquals(messages.takeLast(plan.messages.size), plan.messages)
    }

    @Test
    fun the_newest_message_is_kept_even_when_it_alone_blows_the_budget() {
        // A truncated question is useless. Sending an over-long one at least
        // lets the server answer it or say why it cannot.
        val plan = planContext(listOf(sized(5), sized(5000)), budgetTokens = 100, reserveForReplyTokens = 10)

        assertEquals(1, plan.messages.size)
        assertEquals(1, plan.droppedCount)
    }

    @Test
    fun the_system_prompt_is_never_dropped_and_is_charged_against_the_budget() {
        val systemPrompt = "You are terse.".repeat(20)
        val plan = planContext(
            messages = listOf(sized(50), sized(50)),
            systemPrompt = systemPrompt,
            budgetTokens = 200,
            reserveForReplyTokens = 20,
        )

        // Its cost is counted even though it is not in the message list.
        assertTrue(plan.estimatedTokens > estimateTokens(systemPrompt))
    }

    @Test
    fun a_larger_system_prompt_leaves_room_for_fewer_turns() {
        val messages = List(6) { sized(40) }
        val roomy = planContext(messages, systemPrompt = null, budgetTokens = 400, reserveForReplyTokens = 20)
        val cramped = planContext(
            messages,
            systemPrompt = "verbose instructions ".repeat(40),
            budgetTokens = 400,
            reserveForReplyTokens = 20,
        )

        assertTrue(
            cramped.messages.size < roomy.messages.size,
            "the system prompt has to come out of the same window",
        )
    }

    @Test
    fun no_configured_budget_means_nothing_is_trimmed_and_the_caller_is_told() {
        val messages = List(50) { sized(200) }
        val plan = planContext(messages, budgetTokens = null)

        assertEquals(messages.size, plan.messages.size)
        assertFalse(plan.trimmed)
        // Null is the signal that the server is deciding and will truncate
        // silently — the UI is expected to say so rather than show a fake bar.
        assertNull(plan.budgetTokens)
        assertNull(plan.fractionUsed)
    }

    @Test
    fun usage_is_reported_as_a_fraction_of_the_budget() {
        val plan = planContext(listOf(sized(100)), budgetTokens = 1000, reserveForReplyTokens = 0)
        val fraction = plan.fractionUsed

        assertTrue(fraction != null && fraction > 0f && fraction < 1f, "got $fraction")
    }

    @Test
    fun the_reply_reserve_really_is_held_back() {
        val messages = List(10) { sized(50) }
        val generous = planContext(messages, budgetTokens = 600, reserveForReplyTokens = 0)
        val reserved = planContext(messages, budgetTokens = 600, reserveForReplyTokens = 300)

        assertTrue(
            reserved.messages.size < generous.messages.size,
            "the reply shares the window and must be budgeted for",
        )
    }

    @Test
    fun an_image_costs_far_more_than_its_caption() {
        val withImage = ChatMessage(Role.USER, "what is this?", images = listOf(byteArrayOf(1)))
        val plain = ChatMessage(Role.USER, "what is this?")

        val difference = planContext(listOf(withImage)).estimatedTokens - planContext(listOf(plain)).estimatedTokens
        assertEquals(IMAGE_TOKENS, difference)
    }

    @Test
    fun an_empty_conversation_produces_an_empty_plan_rather_than_failing() {
        val plan = planContext(emptyList(), budgetTokens = 4096)
        assertTrue(plan.messages.isEmpty())
        assertEquals(0, plan.droppedCount)
    }
}
