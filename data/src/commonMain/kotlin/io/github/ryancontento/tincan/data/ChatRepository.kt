package io.github.ryancontento.tincan.data

import io.github.ryancontento.tincan.data.db.ChatDao
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.data.db.TinCanDatabase
import io.github.ryancontento.tincan.llm.ChatMessage
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.Role
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Conversation and message persistence.
 *
 * Deliberately exposes Room entities rather than a parallel set of domain
 * models: at this size a second layer of near-identical data classes would be
 * ceremony. The Room *database* stays private, though — the same rule that
 * keeps DataStore and Ktor out of module APIs.
 */
@OptIn(ExperimentalTime::class)
class ChatRepository internal constructor(private val dao: ChatDao) {

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

    /**
     * Called once at startup. A process that died mid-generation leaves rows
     * claiming to be STREAMING; nothing will ever finish them, so they are
     * demoted rather than left to render as a reply that never completes.
     */
    suspend fun recoverInterruptedMessages() = dao.demoteOrphanedStreamingMessages()

    suspend fun createConversation(
        backendId: String,
        defaultModelId: String?,
        systemPrompt: String?,
    ): Long {
        val now = now()
        return dao.insertConversation(
            ConversationEntity(
                title = UNTITLED,
                defaultModelId = defaultModelId,
                backendId = backendId,
                systemPrompt = systemPrompt,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)

    suspend fun setDefaultModel(conversationId: Long, modelId: String?) =
        dao.setDefaultModel(conversationId, modelId, now())

    suspend fun renameConversation(id: Long, title: String) =
        dao.renameConversation(id, title.ifBlank { UNTITLED }, now())

    /**
     * Derives a title from the first thing the user actually said, but only
     * while the conversation is still untitled — so a manual rename is never
     * silently overwritten.
     */
    suspend fun titleFromFirstMessageIfUnset(conversationId: Long, firstUserMessage: String) {
        val conversation = dao.conversation(conversationId) ?: return
        if (conversation.title != UNTITLED) return
        val title = firstUserMessage.trim().lineSequence().firstOrNull().orEmpty()
            .take(TITLE_MAX_CHARS)
            .ifBlank { UNTITLED }
        dao.renameConversation(conversationId, title, now())
    }

    suspend fun appendUserMessage(conversationId: Long, content: String, backendId: String): Long =
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = MessageRole.USER,
                content = content,
                thinking = null,
                modelId = null,
                backendId = backendId,
                status = MessageStatus.COMPLETE,
                errorCode = null,
                promptTokens = null,
                completionTokens = null,
                tokensPerSecond = null,
                createdAt = now(),
            ),
        )

    /** Creates the row the streaming reply will be written into. */
    suspend fun beginAssistantMessage(conversationId: Long, modelId: String, backendId: String): Long =
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = "",
                thinking = null,
                modelId = modelId,
                backendId = backendId,
                status = MessageStatus.STREAMING,
                errorCode = null,
                promptTokens = null,
                completionTokens = null,
                tokensPerSecond = null,
                createdAt = now(),
            ),
        )

    suspend fun updateStreamingBody(messageId: Long, content: String, thinking: String?) =
        dao.updateMessageBody(messageId, content, thinking)

    suspend fun finishAssistantMessage(
        messageId: Long,
        conversationId: Long,
        content: String,
        status: MessageStatus,
        errorCode: String?,
        stats: GenerationStats?,
    ) {
        dao.finishMessage(
            id = messageId,
            content = content,
            status = status,
            errorCode = errorCode,
            promptTokens = stats?.promptTokens,
            completionTokens = stats?.completionTokens,
            tokensPerSecond = stats?.tokensPerSecond,
        )
        dao.conversation(conversationId)?.let { dao.updateConversation(it.copy(updatedAt = now())) }
    }

    /** Drops an assistant row that produced nothing, so a failure leaves no empty bubble. */
    suspend fun discardMessage(messageId: Long) = dao.deleteMessage(messageId)

    /**
     * History for the next request. Failed turns are excluded — resending a
     * turn the server never answered would poison the context with an error.
     */
    suspend fun historyFor(conversationId: Long): List<ChatMessage> =
        dao.messages(conversationId)
            .filter { it.status != MessageStatus.FAILED && it.content.isNotBlank() }
            .map { ChatMessage(role = it.role.toDomain(), content = it.content) }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        const val UNTITLED = "New conversation"
        private const val TITLE_MAX_CHARS = 60
    }
}

fun MessageRole.toDomain(): Role = when (this) {
    MessageRole.USER -> Role.USER
    MessageRole.ASSISTANT -> Role.ASSISTANT
    MessageRole.SYSTEM -> Role.SYSTEM
}

/**
 * Builds a repository without exposing the database, matching how settings
 * persistence hides DataStore.
 */
fun createChatRepository(database: TinCanDatabase): ChatRepository =
    ChatRepository(database.chatDao())
