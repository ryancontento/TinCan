package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.data.db.ConversationEntity
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

data class ConversationGroup(val label: String, val conversations: List<ConversationEntity>)

/** Pinned first, then by calendar day where the user is: 11pm yesterday is "Yesterday" even an hour later. */
fun groupConversations(
    conversations: List<ConversationEntity>,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<ConversationGroup> {
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val (pinned, rest) = conversations.partition { it.pinned }

    val byAge = rest.groupBy { conversation ->
        val day = Instant.fromEpochMilliseconds(conversation.updatedAt).toLocalDateTime(zone).date
        when (day.daysUntil(today)) {
            // Negative is a clock that moved backwards; treat it as today rather than hide it.
            in Int.MIN_VALUE..0 -> AGE_LABELS[0]
            1 -> AGE_LABELS[1]
            in 2..7 -> AGE_LABELS[2]
            in 8..30 -> AGE_LABELS[3]
            else -> AGE_LABELS[4]
        }
    }

    return buildList {
        if (pinned.isNotEmpty()) add(ConversationGroup(PINNED_LABEL, pinned))
        AGE_LABELS.forEach { label -> byAge[label]?.let { add(ConversationGroup(label, it)) } }
    }
}

const val PINNED_LABEL = "Pinned"
private val AGE_LABELS = listOf("Today", "Yesterday", "Previous 7 days", "Previous 30 days", "Older")
