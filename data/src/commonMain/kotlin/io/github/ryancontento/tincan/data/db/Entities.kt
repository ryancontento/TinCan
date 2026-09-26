package io.github.ryancontento.tincan.data.db

import androidx.room.ColumnInfo
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
    /** An opaque `ServerKey`, never an address. */
    val backendId: String,
    val systemPrompt: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Null uses the global setting, like the prompt did before it was snapshotted. */
    val temperature: Float? = null,
    /** Null uses the global setting. */
    val numCtx: Int? = null,
    /** Pinned conversations sit above the rest, whatever their age. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
)

/**
 * An image sent with a message. Stored in the database rather than beside it, so
 * secure_delete and conversation deletion cover it with no extra bookkeeping.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"]), Index(value = ["conversationId"])],
)
class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    /** Copied from the message so watching a thread's images never touches the busy messages table. */
    val conversationId: Long,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Model is recorded per message, not per conversation, so switching mid-thread
 * is lossless and the UI can mark where it changed. Same for [backendId], which
 * holds an opaque `ServerKey` rather than the address it was derived from.
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

/** One search result: the matching message plus the conversation it lives in. */
data class SearchHit(
    val messageId: Long,
    val conversationId: Long,
    val conversationTitle: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
)
