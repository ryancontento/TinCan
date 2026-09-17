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
    /**
     * The model to use for the *next* reply in this thread. A default, never a
     * constraint — individual messages record what actually produced them.
     */
    val defaultModelId: String?,
    val backendId: String,
    val systemPrompt: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * The model is recorded per message, not per conversation.
 *
 * This is what makes switching models mid-thread lossless: history is never
 * rewritten, and the UI can mark any message whose model differs from the one
 * before it. [backendId] earns its place for the same reason — on desktop you
 * genuinely will alternate between a local model and the MacBook inside one
 * conversation.
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

/**
 * [PENDING] and [INCOMPLETE] are the two that earn their keep: a message
 * composed while the server was down, and a reply whose stream died partway.
 * Both are recoverable states rather than lost data.
 */
enum class MessageStatus { PENDING, STREAMING, COMPLETE, INCOMPLETE, FAILED }

/**
 * Explicit rather than relying on Room's enum handling, so the stored form is
 * pinned to the constant name and a future reordering of the enum cannot
 * silently reinterpret existing rows.
 */
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
