package io.github.ryancontento.tincan.chat

/**
 * A conversation snapshots the prompt it was created with, so editing the
 * default cannot rewrite threads already under way. Empty means deliberately
 * no prompt; null is a legacy row, written before the snapshot existed.
 */
fun resolveSystemPrompt(conversationPrompt: String?, globalPrompt: String): String? =
    (conversationPrompt ?: globalPrompt).takeIf { it.isNotBlank() }

/** The conversation's own model wins, so switching threads restores its model. */
fun resolveModel(conversationModel: String?, globalModel: String?): String? =
    conversationModel ?: globalModel
