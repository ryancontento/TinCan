package io.github.ryancontento.tincan.data

import io.github.ryancontento.tincan.data.db.ChatDao
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.data.db.createDatabase
import io.github.ryancontento.tincan.llm.ChatMessage
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.Role
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposes Room entities directly — a parallel domain model would be ceremony at
 * this size. The database itself stays private, like DataStore and Ktor do.
 */
@OptIn(ExperimentalTime::class)
class ChatRepository internal constructor(
    private val dao: ChatDao,
    private val closeDatabase: () -> Unit = {},
) : AutoCloseable {

    /** Releases the underlying database file lock. Tests need it; the app does not. */
    override fun close() = closeDatabase()

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

    /** Startup: rows left STREAMING belong to a dead process and will never finish. */
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

    /** Only while still untitled, so a manual rename is never overwritten. */
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

    /** Composed but not delivered — already on disk, so it is queued, not lost. */
    suspend fun markPending(messageId: Long) = dao.setMessageStatus(messageId, MessageStatus.PENDING)

    suspend fun markDelivered(messageId: Long) = dao.setMessageStatus(messageId, MessageStatus.COMPLETE)

    suspend fun oldestPendingMessage(conversationId: Long) = dao.oldestPendingMessage(conversationId)

    fun observeConversationsWithPendingMessages(): Flow<List<Long>> =
        dao.observeConversationsWithPendingMessages()

    /** Only the final message; continuing an earlier one would rewrite read history. */
    suspend fun resumableReply(conversationId: Long): MessageEntity? =
        dao.messages(conversationId).lastOrNull()
            ?.takeIf { it.role == MessageRole.ASSISTANT && it.status == MessageStatus.INCOMPLETE }

    /** Reopens a truncated reply so generation can append to the same row. */
    suspend fun resumeAssistantMessage(messageId: Long) =
        dao.setMessageStatus(messageId, MessageStatus.STREAMING)

    /** Failed turns are excluded so errors do not poison the context. */
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

/** Room stays out of the public API so consumers never get androidx.room on their classpath. */
fun createChatRepository(directory: String = appDataDir()): ChatRepository {
    val database = createDatabase(directory)
    return ChatRepository(database.chatDao()) { database.close() }
}
