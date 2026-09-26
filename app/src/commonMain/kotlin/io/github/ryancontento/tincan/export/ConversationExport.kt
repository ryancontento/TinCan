package io.github.ryancontento.tincan.export

import io.github.ryancontento.tincan.data.db.AttachmentEntity
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class ExportFormat(val extension: String, val label: String) {
    /** A transcript meant to be read. Reasoning traces and image data are left out. */
    MARKDOWN("md", "Markdown"),

    /** The complete record, reasoning, token counts and images included. */
    JSON("json", "JSON"),
}

/** A file the platform layer can write, with no platform types involved. */
data class ExportDocument(val fileName: String, val content: String)

fun exportConversation(
    conversation: ConversationEntity,
    messages: List<MessageEntity>,
    format: ExportFormat,
    attachments: List<AttachmentEntity> = emptyList(),
): ExportDocument {
    val images = attachments.groupBy { it.messageId }
    return ExportDocument(
        fileName = "${slugify(conversation.title)}.${format.extension}",
        content = when (format) {
            ExportFormat.MARKDOWN -> toMarkdown(conversation, messages, images)
            ExportFormat.JSON -> toJson(conversation, messages, images)
        },
    )
}

private fun toMarkdown(
    conversation: ConversationEntity,
    messages: List<MessageEntity>,
    images: Map<Long, List<AttachmentEntity>>,
): String =
    buildString {
        appendLine("# ${conversation.title}")
        appendLine()
        conversation.systemPrompt?.takeIf { it.isNotBlank() }?.let {
            appendLine("> **System prompt:** $it")
            appendLine()
        }
        messages.filter { it.content.isNotBlank() || it.id in images }.forEach { message ->
            appendLine("## ${speaker(message)}")
            appendLine()
            images[message.id]?.size?.let { count ->
                // A marker rather than the data: base64 would bury the transcript.
                appendLine("_($count image${if (count == 1) "" else "s"} attached)_")
                appendLine()
            }
            if (message.content.isNotBlank()) appendLine(message.content.trimEnd())
            if (message.status == MessageStatus.INCOMPLETE) {
                appendLine()
                appendLine("_(reply was cut short)_")
            }
            appendLine()
        }
    }.trimEnd() + "\n"

/** The model that produced a reply is part of what the reply means. */
private fun speaker(message: MessageEntity): String = when (message.role) {
    MessageRole.USER -> "You"
    MessageRole.ASSISTANT -> message.modelId ?: "Assistant"
    MessageRole.SYSTEM -> "System"
}

@OptIn(ExperimentalEncodingApi::class)
private fun toJson(
    conversation: ConversationEntity,
    messages: List<MessageEntity>,
    images: Map<Long, List<AttachmentEntity>>,
): String =
    JSON.encodeToString(
        ExportedConversation(
            title = conversation.title,
            systemPrompt = conversation.systemPrompt,
            temperature = conversation.temperature,
            numCtx = conversation.numCtx,
            createdAt = conversation.createdAt,
            messages = messages.map {
                ExportedMessage(
                    role = it.role.name,
                    content = it.content,
                    thinking = it.thinking,
                    model = it.modelId,
                    status = it.status.name,
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    tokensPerSecond = it.tokensPerSecond,
                    createdAt = it.createdAt,
                    images = images[it.id].orEmpty().map { image ->
                        ExportedImage(image.mimeType, Base64.Default.encode(image.bytes))
                    },
                )
            },
        ),
    )

/**
 * The server key is deliberately absent: it identifies a machine and means
 * nothing outside the install that wrote it.
 */
@Serializable
private data class ExportedConversation(
    val title: String,
    val systemPrompt: String?,
    val temperature: Float?,
    val numCtx: Int?,
    val createdAt: Long,
    val messages: List<ExportedMessage>,
)

@Serializable
private data class ExportedMessage(
    val role: String,
    val content: String,
    val thinking: String?,
    val model: String?,
    val status: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val tokensPerSecond: Float?,
    val createdAt: Long,
    val images: List<ExportedImage>,
)

@Serializable
private data class ExportedImage(val mimeType: String, val base64: String)

/** A title is free text and ends up as a filename, so strip it to safe characters. */
internal fun slugify(title: String): String =
    title.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")
        .take(SLUG_MAX_CHARS)
        .ifEmpty { "conversation" }

private val JSON = Json { prettyPrint = true; encodeDefaults = true }
private const val SLUG_MAX_CHARS = 60
