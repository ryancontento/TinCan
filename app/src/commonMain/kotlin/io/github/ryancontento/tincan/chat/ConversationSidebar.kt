package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.SearchHit
import io.github.ryancontento.tincan.ui.Metrics
import io.github.ryancontento.tincan.ui.MonoStyle
import io.github.ryancontento.tincan.ui.TinIcon
import io.github.ryancontento.tincan.ui.TinIconButton
import io.github.ryancontento.tincan.ui.TinIconGlyph
import io.github.ryancontento.tincan.ui.TinField
import io.github.ryancontento.tincan.ui.TinSectionLabel
import kotlinx.datetime.Clock

/**
 * A desktop-shaped layout, not a phone screen stretched wide.
 *
 * In v2 this becomes a navigation drawer rather than a permanent rail; keeping
 * that decision to this one composable is what stops the choice leaking through
 * the rest of the chat UI.
 */
@Composable
fun ConversationSidebar(
    width: Dp,
    onWidthChange: (Dp) -> Unit,
    onWidthCommit: (Dp) -> Unit,
    conversations: List<ConversationEntity>,
    activeId: Long?,
    enabled: Boolean,
    search: SearchState,
    onSearchChange: (String) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (Long) -> Unit,
    onEditConversation: (Long) -> Unit,
    onTogglePin: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current

    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(width)
                .fillMaxHeight()
                .background(colors.surface)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TinSectionLabel("Conversations", Modifier.weight(1f).padding(start = 4.dp))
                TinIconButton(
                    onClick = onNew,
                    icon = TinIcon.PLUS,
                    description = "New conversation",
                    enabled = enabled,
                )
            }

            TinField(
                value = search.term,
                onValueChange = onSearchChange,
                placeholder = "Search",
                textStyle = MaterialTheme.typography.bodySmall,
                leading = { TinIconGlyph(TinIcon.SEARCH, colors.onSurfaceVariant, size = 12.dp) },
                trailing = {
                    if (search.active) {
                        TinIconButton(
                            onClick = { onSearchChange("") },
                            icon = TinIcon.CLOSE,
                            description = "Clear search",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Searching replaces the list rather than filtering it: a hit is a
            // message, and listing only the conversations holding one would
            // hide which message actually matched.
            if (search.active) {
                SearchResults(search, enabled, onOpenHit)
            } else {
                ConversationList(conversations, activeId, enabled, onSelect, onDelete, onEditConversation, onTogglePin)
            }
        }

        // The rail's edge and its drag handle. The rule stays a hairline but the
        // target around it is several pixels wide, because a 1px grab target is
        // not one a pointer can reliably hit.
        Box(
            Modifier
                .width(HANDLE_WIDTH)
                .fillMaxHeight()
                .background(colors.background)
                .pointerHoverIcon(PointerIcon.Hand)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = width + with(density) { delta.toDp() }
                        onWidthChange(next.coerceIn(MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH))
                    },
                    // Saved once at the end; a write per pixel would hammer the store.
                    onDragStopped = { onWidthCommit(width) },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.width(Metrics.hairline).fillMaxHeight().background(colors.outlineVariant))
        }
    }
}

@Composable
private fun ConversationList(
    conversations: List<ConversationEntity>,
    activeId: Long?,
    enabled: Boolean,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onTogglePin: (Long, Boolean) -> Unit,
) {
    if (conversations.isEmpty()) {
        Text(
            "Nothing saved yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        return
    }

    // Recomputed with the list, which changes whenever a conversation does, so "Today" rolls over in use.
    val groups = remember(conversations) { groupConversations(conversations, Clock.System.now().toEpochMilliseconds()) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        groups.forEach { group ->
            item(key = "group-${group.label}") {
                TinSectionLabel(group.label, Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp))
            }
            items(group.conversations, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    selected = conversation.id == activeId,
                    enabled = enabled,
                    onSelect = { onSelect(conversation.id) },
                    onDelete = { onDelete(conversation.id) },
                    onEdit = { onEdit(conversation.id) },
                    onTogglePin = { onTogglePin(conversation.id, !conversation.pinned) },
                )
            }
        }
    }
}

@Composable
private fun SearchResults(search: SearchState, enabled: Boolean, onOpenHit: (SearchHit) -> Unit) {
    if (search.hits.isEmpty()) {
        Text(
            "No matches.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        return
    }

    TinSectionLabel("${search.hits.size} matches", Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        items(search.hits, key = { it.messageId }) { hit ->
            SearchResultRow(hit, search.term, enabled, onOpenHit)
        }
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, term: String, enabled: Boolean, onOpen: (SearchHit) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val snippet = remember(hit.messageId, term) { snippetAround(hit.content, term) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (hovered) colors.surfaceVariant else Color.Transparent)
            .hoverable(interaction)
            .clickable(enabled = enabled) { onOpen(hit) }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            "${hit.conversationTitle}  ·  ${if (hit.role == MessageRole.USER) "you" else "reply"}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            // Bolding the match is what makes a column of snippets scannable.
            buildAnnotatedString {
                append(snippet.text.take(snippet.matchStart))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.onSurface)) {
                    append(snippet.text.drop(snippet.matchStart).take(snippet.matchLength))
                }
                append(snippet.text.drop(snippet.matchStart + snippet.matchLength))
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val HANDLE_WIDTH = 5.dp
private val MIN_SIDEBAR_WIDTH = TinCanSettings.MIN_SIDEBAR_WIDTH.dp
private val MAX_SIDEBAR_WIDTH = TinCanSettings.MAX_SIDEBAR_WIDTH.dp

@Composable
private fun ConversationRow(
    conversation: ConversationEntity,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var confirming by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                when {
                    selected -> colors.surfaceContainerHigh
                    hovered -> colors.surfaceVariant
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(enabled = enabled) { onSelect() }
            .padding(start = 6.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A 2px rule rather than a filled row: the selection reads at a glance
        // without the rail turning into a block of colour.
        Box(
            Modifier
                .width(2.dp)
                .height(22.dp)
                .background(if (selected) colors.primary else Color.Transparent, MaterialTheme.shapes.extraSmall),
        )

        Column(Modifier.weight(1f).padding(start = 7.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                conversation.title,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) colors.onSurface else colors.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            conversation.defaultModelId?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium.merge(MonoStyle),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Deleting takes a conversation and everything in it, so it asks once
        // rather than acting on a single stray click. The actions stay hidden
        // until the row is pointed at, so the rail reads as a list of names.
        when {
            confirming -> {
                TinIconButton(
                    onClick = { confirming = false; onDelete() },
                    icon = TinIcon.TRASH,
                    description = "Confirm delete",
                    enabled = enabled,
                    tint = colors.error,
                )
                TinIconButton(
                    onClick = { confirming = false },
                    icon = TinIcon.CLOSE,
                    description = "Cancel delete",
                    enabled = enabled,
                )
            }

            hovered || selected -> {
                TinIconButton(
                    onClick = onTogglePin,
                    icon = TinIcon.PIN,
                    description = if (conversation.pinned) "Unpin" else "Pin",
                    enabled = enabled,
                    tint = if (conversation.pinned) colors.primary else null,
                )
                TinIconButton(onClick = onEdit, icon = TinIcon.PENCIL, description = "Rename", enabled = enabled)
                TinIconButton(
                    onClick = { confirming = true },
                    icon = TinIcon.TRASH,
                    description = "Delete",
                    enabled = enabled,
                )
            }
        }
    }
}
