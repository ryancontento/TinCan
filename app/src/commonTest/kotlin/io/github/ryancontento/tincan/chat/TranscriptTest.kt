package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals

private fun message(id: Long, role: MessageRole, model: String? = null, status: MessageStatus = MessageStatus.COMPLETE) =
    MessageEntity(
        id = id, conversationId = 1, role = role, content = "", thinking = null, modelId = model,
        backendId = null, status = status, errorCode = null, promptTokens = null,
        completionTokens = null, tokensPerSecond = null, createdAt = id,
    )

class TranscriptTest {

    @Test
    fun the_model_is_named_only_where_it_changes() {
        val messages = listOf(
            message(1, MessageRole.USER),
            message(2, MessageRole.ASSISTANT, "phi4"),
            message(3, MessageRole.USER),
            message(4, MessageRole.ASSISTANT, "phi4"),
            message(5, MessageRole.USER),
            message(6, MessageRole.ASSISTANT, "qwen3:8b"),
        )
        assertEquals(setOf(2L, 6L), messagesShowingModel(messages))
    }

    @Test
    fun the_meta_line_reads_as_one_figure_per_fact() {
        val reply = message(1, MessageRole.ASSISTANT, "phi4", MessageStatus.INCOMPLETE)
            .copy(promptTokens = 29, completionTokens = 3, tokensPerSecond = 17.3f)
        assertEquals("phi4  ·  29 in / 3 out  ·  17 tok/s  ·  incomplete", messageMeta(reply, showModel = true))
        assertEquals("", messageMeta(message(2, MessageRole.USER), showModel = false))
    }
}
