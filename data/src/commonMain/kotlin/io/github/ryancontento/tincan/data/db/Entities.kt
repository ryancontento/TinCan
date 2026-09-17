package io.github.ryancontento.tincan.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Default for the next reply, never a constraint. */
    val defaultModelId: String?,
    val backendId: String,
    val systemPrompt: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Model is recorded per message, not per conversation, so switching mid-thread
 * is lossless and the UI can mark where it changed. Same for [backendId].
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val thinking: String?,
    val modelId: String?,
    val backendId: String?,
    val status: MessageStatus,
    val errorCode: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val tokensPerSecond: Float?,
    val createdAt: Long,
)

enum class MessageRole { USER, ASSISTANT, SYSTEM }

/** PENDING = composed while offline. INCOMPLETE = stream died partway. Both recoverable. */
enum class MessageStatus { PENDING, STREAMING, COMPLETE, INCOMPLETE, FAILED }

/** Explicit so stored values are pinned to constant names, not ordinals. */
class Converters {
    @TypeConverter fun roleToString(value: MessageRole): String = value.name

    @TypeConverter
    fun stringToRole(value: String): MessageRole =
        MessageRole.entries.firstOrNull { it.name == value } ?: MessageRole.ASSISTANT

    @TypeConverter fun statusToString(value: MessageStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): MessageStatus =
        MessageStatus.entries.firstOrNull { it.name == value } ?: MessageStatus.COMPLETE
}
