package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.data.ImageAttachment
import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.data.db.AttachmentEntity
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.data.db.SearchHit
import io.github.ryancontento.tincan.llm.ContextPlan
import io.github.ryancontento.tincan.llm.ModelInfo

data class ChatUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val activeConversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    /** Images already sent in the active conversation, by message id. */
    val attachments: Map<Long, List<AttachmentEntity>> = emptyMap(),
    /** The reply being streamed, overlaid on its own row so each chunk recomposes one message. */
    val streamingMessageId: Long? = null,
    val streamingText: String = "",
    val streamingThinking: String = "",
    val isGenerating: Boolean = false,
    val availableModels: List<ModelInfo> = emptyList(),
    val settings: TinCanSettings = TinCanSettings(),
    val notice: Notice? = null,
    val connection: ConnectionState = ConnectionState.UNKNOWN,
    /** Conversations holding a message composed while the server was unreachable. */
    val queuedCount: Int = 0,
    val context: ContextPlan? = null,
    val search: SearchState = SearchState(),
    /** Set when a search hit is opened; cleared once the transcript has scrolled to it. */
    val scrollToMessageId: Long? = null,
    val draftImages: List<DraftImage> = emptyList(),
) {
    val activeConversation: ConversationEntity?
        get() = conversations.firstOrNull { it.id == activeConversationId }

    /** What the next send will use: the conversation's own model before the global choice. */
    val activeModel: String?
        get() = resolveModel(activeConversation?.defaultModelId, settings.selectedModel)

    /** Older servers report no capabilities, so only an explicit list without "vision" says no. */
    val activeModelSeesImages: Boolean
        get() = availableModels.firstOrNull { it.id == activeModel }
            ?.let { it.capabilities.isEmpty() || it.supportsImages } ?: true

    val canContinue: Boolean
        get() = !isGenerating && messages.lastOrNull()
            ?.let { it.role == MessageRole.ASSISTANT && it.status == MessageStatus.INCOMPLETE } == true

    val canRegenerate: Boolean
        get() = !isGenerating && messages.lastOrNull()?.role == MessageRole.ASSISTANT
}

class DraftImage(val name: String, val image: ImageAttachment)

data class SearchState(val term: String = "", val hits: List<SearchHit> = emptyList()) {
    val active: Boolean get() = term.isNotBlank()
}
