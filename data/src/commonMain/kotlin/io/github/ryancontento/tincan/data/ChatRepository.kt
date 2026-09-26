package io.github.ryancontento.tincan.data

import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.ryancontento.tincan.data.db.AttachmentEntity
import io.github.ryancontento.tincan.data.db.ChatDao
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.data.db.SearchHit
import io.github.ryancontento.tincan.data.db.createDatabase
import io.github.ryancontento.tincan.llm.ChatMessage
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.Role
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Exposes Room entities directly (a domain mirror would be ceremony); the database stays private. */
@OptIn(ExperimentalTime::class)
class ChatRepository internal constructor(
    private val dao: ChatDao,
    private val rewriteFile: suspend () -> Unit = {},
    private val flushLog: suspend () -> Unit = {},
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val closeDatabase: () -> Unit = {},
) : AutoCloseable {

    /** Releases the database file lock; only tests need it. */
    override fun close() = closeDatabase()

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

    /** Startup: rows left STREAMING belong to a dead process and will never finish. */
    suspend fun recoverInterruptedMessages() = dao.demoteOrphanedStreamingMessages()

    /** Startup: swaps raw addresses written by older versions for their keys. Idempotent. */
    suspend fun redactStoredServerAddresses(keyFor: suspend (String) -> ServerKey) {
        val addresses = dao.distinctBackendIds().filter { ServerKey.looksLikeAddress(it) }
        if (addresses.isEmpty()) return

        addresses.forEach { address ->
            val key = keyFor(address).value
            dao.replaceConversationBackendId(address, key)
            dao.replaceMessageBackendId(address, key)
        }
        // An UPDATE only supersedes the old bytes; this removes them.
        rewriteFile()
    }

    suspend fun createConversation(
        serverKey: ServerKey,
        defaultModelId: String?,
        systemPrompt: String?,
    ): Long {
        val now = now()
        return dao.insertConversation(
            ConversationEntity(
                title = UNTITLED,
                defaultModelId = defaultModelId,
                backendId = serverKey.value,
                systemPrompt = systemPrompt,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** secure_delete zeroes the pages; the flush drops the log's older copies of them. */
    suspend fun deleteConversation(id: Long) {
        dao.deleteConversation(id)
        flushLog()
    }

    suspend fun conversation(id: Long): ConversationEntity? = dao.conversation(id)

    suspend fun setDefaultModel(conversationId: Long, modelId: String?) =
        dao.setDefaultModel(conversationId, modelId, now())

    /** Null restores the global default; empty means this thread has no prompt. */
    suspend fun setSystemPrompt(conversationId: Long, prompt: String?) =
        dao.setSystemPrompt(conversationId, prompt, now())

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

    /** One transaction, so a crash cannot leave a question on disk without the image it asked about. */
    suspend fun appendUserMessage(
        conversationId: Long,
        content: String,
        serverKey: ServerKey,
        images: List<ImageAttachment> = emptyList(),
    ): Long {
        var id = 0L
        inTransaction {
            id = dao.insertMessage(
                newMessage(conversationId, MessageRole.USER, content, null, serverKey, MessageStatus.COMPLETE),
            )
            images.forEach {
                dao.insertAttachment(
                    AttachmentEntity(messageId = id, conversationId = conversationId, mimeType = it.mimeType, bytes = it.bytes),
                )
            }
        }
        return id
    }

    fun observeAttachments(conversationId: Long): Flow<List<AttachmentEntity>> =
        dao.observeAttachments(conversationId)

    suspend fun attachments(conversationId: Long): List<AttachmentEntity> = dao.attachments(conversationId)

    suspend fun setPinned(conversationId: Long, pinned: Boolean) = dao.setPinned(conversationId, pinned)

    /** Null for either hands it back to the global setting. */
    suspend fun setGenerationOptions(conversationId: Long, temperature: Float?, numCtx: Int?) =
        dao.setGenerationOptions(conversationId, temperature, numCtx, now())

    /** Creates the row the streaming reply will be written into. */
    suspend fun beginAssistantMessage(conversationId: Long, modelId: String, serverKey: ServerKey): Long =
        dao.insertMessage(
            newMessage(conversationId, MessageRole.ASSISTANT, "", modelId, serverKey, MessageStatus.STREAMING),
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

    /** Puts a discarded reply back under a new id; nothing referenced the old one. */
    suspend fun restoreMessage(message: MessageEntity): Long = dao.insertMessage(message.copy(id = 0))

    /** Oldest first; used for export. */
    suspend fun messages(conversationId: Long): List<MessageEntity> = dao.messages(conversationId)

    /** Rewinds to just before [messageId], so a regenerate or edit hides the turns it replaces from the model. */
    suspend fun truncateFrom(conversationId: Long, messageId: Long) =
        dao.deleteMessagesFrom(conversationId, messageId)

    /** The last reply, whatever its state — the one a regenerate would replace. */
    suspend fun lastAssistantMessage(conversationId: Long): MessageEntity? =
        dao.messages(conversationId).lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }

    /** Blank terms return nothing rather than every message ever sent. */
    suspend fun search(term: String, limit: Int = SEARCH_LIMIT): List<SearchHit> =
        if (term.isBlank()) emptyList() else dao.searchMessages(escapeForLike(term.trim()), limit)

    /** Composed but not delivered — already on disk, so it is queued, not lost. */
    suspend fun markPending(messageId: Long) = dao.setMessageStatus(messageId, MessageStatus.PENDING)

    suspend fun markDelivered(messageId: Long) = dao.setMessageStatus(messageId, MessageStatus.COMPLETE)

    suspend fun oldestPendingMessage(conversationId: Long) = dao.oldestPendingMessage(conversationId)

    fun observeConversationsWithPendingMessages(): Flow<List<Long>> =
        dao.observeConversationsWithPendingMessages()

    /** Only the final message; continuing an earlier one would rewrite read history. */
    suspend fun resumableReply(conversationId: Long): MessageEntity? =
        lastAssistantMessage(conversationId)?.takeIf { it.status == MessageStatus.INCOMPLETE }

    /** Reopens a truncated reply so generation can append to the same row. */
    suspend fun resumeAssistantMessage(messageId: Long) =
        dao.setMessageStatus(messageId, MessageStatus.STREAMING)

    /** Failed turns are excluded so errors do not poison the context. */
    suspend fun historyFor(conversationId: Long): List<ChatMessage> {
        val images = dao.attachments(conversationId).groupBy({ it.messageId }, { it.bytes })
        return dao.messages(conversationId)
            // An image with no words is still a question.
            .filter { it.status != MessageStatus.FAILED && (it.content.isNotBlank() || it.id in images) }
            .map { ChatMessage(role = it.role.toDomain(), content = it.content, images = images[it.id].orEmpty()) }
    }

    private fun newMessage(
        conversationId: Long,
        role: MessageRole,
        content: String,
        modelId: String?,
        serverKey: ServerKey,
        status: MessageStatus,
    ) = MessageEntity(
        conversationId = conversationId,
        role = role,
        content = content,
        thinking = null,
        modelId = modelId,
        backendId = serverKey.value,
        status = status,
        errorCode = null,
        promptTokens = null,
        completionTokens = null,
        tokensPerSecond = null,
        createdAt = now(),
    )

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        const val UNTITLED = "New conversation"
        private const val TITLE_MAX_CHARS = 60
        private const val SEARCH_LIMIT = 100
    }
}

/** Escapes LIKE's wildcards so "50%" matches literally; pairs with `ESCAPE '\'` in the query. */
internal fun escapeForLike(term: String): String =
    term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

fun MessageRole.toDomain(): Role = when (this) {
    MessageRole.USER -> Role.USER
    MessageRole.ASSISTANT -> Role.ASSISTANT
    MessageRole.SYSTEM -> Role.SYSTEM
}

private const val CHECKPOINT_WAL = "PRAGMA wal_checkpoint(TRUNCATE)"

/** Room stays out of the public API so consumers never get androidx.room on their classpath. */
fun createChatRepository(directory: String = appDataDir()): ChatRepository {
    val database = createDatabase(directory)
    return ChatRepository(
        dao = database.chatDao(),
        rewriteFile = {
            // VACUUM rebuilds the pages that held superseded rows; the checkpoint drops the log's old copies.
            database.useWriterConnection { connection ->
                connection.execSQL("VACUUM")
                connection.execSQL(CHECKPOINT_WAL)
            }
        },
        flushLog = { database.useWriterConnection { it.execSQL(CHECKPOINT_WAL) } },
        // DAO calls inside the block run on this connection, so they commit or roll back together.
        inTransaction = { block -> database.useWriterConnection { it.immediateTransaction { block() } } },
        closeDatabase = { database.close() },
    )
}
