package io.github.ryancontento.tincan.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    /** Not activity, so updatedAt is left alone and the conversation keeps its place in time. */
    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversations SET temperature = :temperature, numCtx = :numCtx, updatedAt = :at WHERE id = :id")
    suspend fun setGenerationOptions(id: Long, temperature: Float?, numCtx: Int?, at: Long)

    @Insert
    suspend fun insertAttachment(attachment: AttachmentEntity): Long

    @Query("SELECT * FROM attachments WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun attachments(conversationId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeAttachments(conversationId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: Long): ConversationEntity?

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("UPDATE conversations SET title = :title, updatedAt = :at WHERE id = :id")
    suspend fun renameConversation(id: Long, title: String, at: Long)

    @Query("UPDATE conversations SET defaultModelId = :modelId, updatedAt = :at WHERE id = :id")
    suspend fun setDefaultModel(id: Long, modelId: String?, at: Long)

    @Query("UPDATE conversations SET systemPrompt = :prompt, updatedAt = :at WHERE id = :id")
    suspend fun setSystemPrompt(id: Long, prompt: String?, at: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun messages(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    /**
     * Used on the streaming path, which is why it is a targeted UPDATE rather
     * than @Update on the whole row: the in-flight reply is written repeatedly
     * as it grows, and rewriting every column each time would be wasteful and
     * would clobber fields set elsewhere.
     */
    @Query("UPDATE messages SET content = :content, thinking = :thinking WHERE id = :id")
    suspend fun updateMessageBody(id: Long, content: String, thinking: String?)

    @Query(
        """
        UPDATE messages
        SET content = :content,
            status = :status,
            errorCode = :errorCode,
            promptTokens = :promptTokens,
            completionTokens = :completionTokens,
            tokensPerSecond = :tokensPerSecond
        WHERE id = :id
        """,
    )
    suspend fun finishMessage(
        id: Long,
        content: String,
        status: MessageStatus,
        errorCode: String?,
        promptTokens: Int?,
        completionTokens: Int?,
        tokensPerSecond: Float?,
    )

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setMessageStatus(id: Long, status: MessageStatus)

    /** The oldest message still waiting to be delivered, if any. */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND status = 'PENDING'
        ORDER BY createdAt ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun oldestPendingMessage(conversationId: Long): MessageEntity?

    /** Conversations holding undelivered messages, for retry once the server returns. */
    @Query("SELECT DISTINCT conversationId FROM messages WHERE status = 'PENDING'")
    fun observeConversationsWithPendingMessages(): Flow<List<Long>>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    /**
     * Drops a message and everything after it. Ids autoincrement, so within one
     * conversation they run in the same order the transcript is read in.
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id >= :fromMessageId")
    suspend fun deleteMessagesFrom(conversationId: Long, fromMessageId: Long)

    /**
     * LIKE rather than an FTS table: at one person's history it is instant, and
     * it costs no schema version, no migration and no shadow table to keep in
     * sync. Revisit if a transcript ever gets big enough to notice.
     */
    @Query(
        """
        SELECT m.id AS messageId, m.conversationId AS conversationId, c.title AS conversationTitle,
               m.role AS role, m.content AS content, m.createdAt AS createdAt
        FROM messages m
        JOIN conversations c ON c.id = m.conversationId
        WHERE m.content LIKE '%' || :term || '%' ESCAPE '\'
        ORDER BY m.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchMessages(term: String, limit: Int): List<SearchHit>

    /**
     * Anything left mid-flight when the process died is not coming back, so it
     * is demoted to INCOMPLETE at startup. Without this, a crash during
     * generation would leave a row claiming to be STREAMING forever and the UI
     * would show a reply that never finishes.
     */
    @Query("UPDATE messages SET status = 'INCOMPLETE' WHERE status = 'STREAMING'")
    suspend fun demoteOrphanedStreamingMessages()

    /** Distinct backend values across both tables, for the address sweep at startup. */
    @Query(
        """
        SELECT DISTINCT backendId FROM conversations
        UNION
        SELECT DISTINCT backendId FROM messages WHERE backendId IS NOT NULL
        """,
    )
    suspend fun distinctBackendIds(): List<String>

    @Query("UPDATE conversations SET backendId = :to WHERE backendId = :from")
    suspend fun replaceConversationBackendId(from: String, to: String)

    @Query("UPDATE messages SET backendId = :to WHERE backendId = :from")
    suspend fun replaceMessageBackendId(from: String, to: String)
}
