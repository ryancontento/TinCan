package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.data.db.ConversationEntity
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

private val zone = TimeZone.of("America/New_York")

private fun at(iso: String): Long = LocalDateTime.parse(iso).toInstant(zone).toEpochMilliseconds()

private fun conversation(id: Long, updated: String, pinned: Boolean = false) = ConversationEntity(
    id = id, title = "c$id", defaultModelId = null, backendId = "srv", systemPrompt = null,
    createdAt = 0, updatedAt = at(updated), pinned = pinned,
)

class ConversationGroupsTest {

    private val now = at("2026-09-26T09:00:00")

    @Test
    fun conversations_fall_into_calendar_day_groups_with_pinned_first() {
        val groups = groupConversations(
            listOf(
                conversation(1, "2026-09-26T08:00:00"),
                conversation(2, "2026-09-25T23:30:00"),   // under 10 hours ago, but yesterday
                conversation(3, "2026-09-21T12:00:00"),
                conversation(4, "2026-09-01T12:00:00"),
                conversation(5, "2025-01-01T12:00:00"),
                conversation(6, "2024-06-01T12:00:00", pinned = true),
            ),
            nowMillis = now,
            zone = zone,
        )

        assertEquals(
            listOf("Pinned" to listOf(6L), "Today" to listOf(1L), "Yesterday" to listOf(2L),
                "Previous 7 days" to listOf(3L), "Previous 30 days" to listOf(4L), "Older" to listOf(5L)),
            groups.map { g -> g.label to g.conversations.map { it.id } },
        )
    }

    @Test
    fun empty_groups_are_left_out() {
        val groups = groupConversations(listOf(conversation(1, "2026-09-26T08:00:00")), now, zone)
        assertEquals(listOf("Today"), groups.map { it.label })
    }

    @Test
    fun a_timestamp_from_a_clock_that_ran_ahead_counts_as_today() {
        val groups = groupConversations(listOf(conversation(1, "2026-09-28T08:00:00")), now, zone)
        assertEquals(listOf("Today"), groups.map { it.label })
    }
}
