package io.github.ryancontento.tincan.llm

import kotlin.math.ceil

/**
 * A rough token count for a piece of text.
 *
 * Deliberately pessimistic. The usual English rule of thumb is about four
 * characters per token, but code, markdown and punctuation pack more tokens
 * into the same characters — and under-estimating is the dangerous direction,
 * because it lets the server quietly truncate a conversation the client
 * believed was within budget. Over-estimating only costs a little headroom.
 *
 * This is not a tokeniser and does not pretend to be. It exists so the app can
 * warn and trim on its own terms rather than discovering the limit by having
 * history disappear.
 */
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    return ceil(text.length / CHARS_PER_TOKEN).toInt()
}

/** What will actually be sent, and what had to be left behind to fit. */
data class ContextPlan(
    val messages: List<ChatMessage>,
    /** Oldest turns removed to fit the budget. Zero when everything fits. */
    val droppedCount: Int,
    /** Estimated tokens for what is being sent, including the system prompt. */
    val estimatedTokens: Int,
    /** Usable budget, or null when no limit is configured and the server decides. */
    val budgetTokens: Int?,
) {
    val trimmed: Boolean get() = droppedCount > 0

    /** Fraction of budget consumed, or null when there is no known budget. */
    val fractionUsed: Float?
        get() = budgetTokens?.takeIf { it > 0 }?.let { estimatedTokens.toFloat() / it }
}

/**
 * Decides what to send.
 *
 * Ollama truncates at `num_ctx` without telling the client, so a long
 * conversation silently loses its oldest turns and the model starts answering
 * with amnesia that looks like the model being bad. Doing the trimming here
 * means the app knows it happened and can say so.
 *
 * Rules:
 *  - The system prompt is never dropped; it is the thing most likely to matter.
 *  - The most recent turns are kept, oldest dropped first.
 *  - The final message is always kept even if it alone exceeds the budget.
 *    Sending a truncated question is useless; sending an over-long one at least
 *    lets the server answer or complain.
 *  - With no budget configured, nothing is trimmed, and the caller is expected
 *    to say that the server is deciding.
 */
fun planContext(
    messages: List<ChatMessage>,
    systemPrompt: String? = null,
    budgetTokens: Int? = null,
    /** Head-room kept free for the reply itself, which shares the window. */
    reserveForReplyTokens: Int = DEFAULT_REPLY_RESERVE_TOKENS,
): ContextPlan {
    val systemCost = systemPrompt?.let { cost(it) } ?: 0

    if (budgetTokens == null || budgetTokens <= 0) {
        return ContextPlan(
            messages = messages,
            droppedCount = 0,
            estimatedTokens = systemCost + messages.sumOf { cost(it.content) },
            budgetTokens = null,
        )
    }

    val usable = budgetTokens - reserveForReplyTokens - systemCost

    // Walk backwards so the newest turns win the budget.
    val kept = ArrayDeque<ChatMessage>()
    var used = 0
    for (message in messages.asReversed()) {
        val messageCost = cost(message.content)
        val isMostRecent = kept.isEmpty()
        if (!isMostRecent && used + messageCost > usable) break
        kept.addFirst(message)
        used += messageCost
    }

    return ContextPlan(
        messages = kept.toList(),
        droppedCount = messages.size - kept.size,
        estimatedTokens = systemCost + used,
        budgetTokens = budgetTokens,
    )
}

/** Text cost plus the framing every message carries on the wire. */
private fun cost(content: String): Int = estimateTokens(content) + MESSAGE_OVERHEAD_TOKENS

private const val CHARS_PER_TOKEN = 3.6
private const val MESSAGE_OVERHEAD_TOKENS = 4
const val DEFAULT_REPLY_RESERVE_TOKENS = 512

/** Above this share of the budget, the UI should start warning. */
const val CONTEXT_WARNING_THRESHOLD = 0.8f
