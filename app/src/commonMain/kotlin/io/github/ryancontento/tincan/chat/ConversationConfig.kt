package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.llm.GenerationOptions

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

/** Temperature and num_ctx follow the global settings unless this conversation sets its own. */
fun resolveOptions(conversation: ConversationEntity?, settings: TinCanSettings): GenerationOptions =
    settings.toGenerationOptions().copy(
        temperature = conversation?.temperature ?: settings.temperature,
        numCtx = conversation?.numCtx ?: settings.numCtx,
    )

/** Blank clears the override; anything unparseable or out of range is treated as blank. */
fun parseTemperature(raw: String): Float? =
    raw.trim().toFloatOrNull()?.takeIf { it in 0f..2f }

fun parseNumCtx(raw: String): Int? =
    raw.trim().toIntOrNull()?.takeIf { it > 0 }
