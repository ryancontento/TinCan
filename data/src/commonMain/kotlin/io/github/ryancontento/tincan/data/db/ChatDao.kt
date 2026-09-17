package io.github.ryancontento.tincan.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

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
     * Anything left mid-flight when the process died is not coming back, so it
     * is demoted to INCOMPLETE at startup. Without this, a crash during
     * generation would leave a row claiming to be STREAMING forever and the UI
     * would show a reply that never finishes.
     */
    @Query("UPDATE messages SET status = 'INCOMPLETE' WHERE status = 'STREAMING'")
    suspend fun demoteOrphanedStreamingMessages()
}
