package io.github.ryancontento.tincan.llm

import kotlin.math.ceil

/**
 * Rough token count — not a tokeniser.
 *
 * Pessimistic on purpose: under-estimating lets the server truncate a
 * conversation the client believed was within budget.
 */
fun estimateTokens(text: String): Int =
    if (text.isEmpty()) 0 else ceil(text.length / CHARS_PER_TOKEN).toInt()

/** What will be sent, and what was dropped to make it fit. */
data class ContextPlan(
    val messages: List<ChatMessage>,
    val droppedCount: Int,
    val estimatedTokens: Int,
    /** Null when no limit is configured and the server decides. */
    val budgetTokens: Int?,
) {
    val trimmed: Boolean get() = droppedCount > 0

    val fractionUsed: Float?
        get() = budgetTokens?.takeIf { it > 0 }?.let { estimatedTokens.toFloat() / it }
}

/**
 * Trims history to fit the window.
 *
 * Ollama truncates at num_ctx silently, so a long conversation develops
 * amnesia that reads as the model being bad. Doing it here means the app knows.
 */
fun planContext(
    messages: List<ChatMessage>,
    systemPrompt: String? = null,
    budgetTokens: Int? = null,
    reserveForReplyTokens: Int = DEFAULT_REPLY_RESERVE_TOKENS,
): ContextPlan {
    val systemCost = systemPrompt?.let { cost(it) } ?: 0

    if (budgetTokens == null || budgetTokens <= 0) {
        return ContextPlan(messages, 0, systemCost + messages.sumOf { cost(it.content) }, null)
    }

    // The system prompt is never dropped, and the reply shares the window.
    val usable = budgetTokens - reserveForReplyTokens - systemCost

    val kept = ArrayDeque<ChatMessage>()
    var used = 0
    for (message in messages.asReversed()) {   // newest turns win the budget
        val messageCost = cost(message.content)
        // The newest is kept even if oversized; a truncated question is useless.
        if (kept.isNotEmpty() && used + messageCost > usable) break
        kept.addFirst(message)
        used += messageCost
    }

    return ContextPlan(kept.toList(), messages.size - kept.size, systemCost + used, budgetTokens)
}

/** Text plus the per-message framing carried on the wire. */
private fun cost(content: String): Int = estimateTokens(content) + MESSAGE_OVERHEAD_TOKENS

private const val CHARS_PER_TOKEN = 3.6
private const val MESSAGE_OVERHEAD_TOKENS = 4
const val DEFAULT_REPLY_RESERVE_TOKENS = 512

/** Share of budget above which the UI warns. */
const val CONTEXT_WARNING_THRESHOLD = 0.8f
