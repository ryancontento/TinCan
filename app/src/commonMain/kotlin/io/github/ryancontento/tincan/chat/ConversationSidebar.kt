package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.data.db.ConversationEntity

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
    conversations: List<ConversationEntity>,
    activeId: Long?,
    enabled: Boolean,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(width)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        OutlinedButton(
            onClick = onNew,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("New conversation")
        }

        if (conversations.isEmpty()) {
            Text(
                "Nothing saved yet. Send a message and it will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == activeId,
                        enabled = enabled,
                        onSelect = { onSelect(conversation.id) },
                        onDelete = { onDelete(conversation.id) },
                    )
                }
            }
        }

        // The drag handle. Clamped so the rail can never be dragged to nothing
        // or wide enough to squeeze the transcript out of the window.
        Box(
            Modifier
                .width(6.dp)
                .fillMaxHeight()
                .pointerHoverIcon(PointerIcon.Hand)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = width + with(density) { delta.toDp() }
                        onWidthChange(next.coerceIn(MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH))
                    },
                ),
        )
    }
}

private val MIN_SIDEBAR_WIDTH = 180.dp
private val MAX_SIDEBAR_WIDTH = 460.dp

@Composable
private fun ConversationRow(
    conversation: ConversationEntity,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            .clickable(enabled = enabled) { onSelect() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            conversation.defaultModelId?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Deleting takes a conversation and everything in it, so it asks once
        // rather than acting on a single stray click.
        if (confirming) {
            IconButton(onClick = { confirming = false; onDelete() }, enabled = enabled) {
                Text("✓", color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = { confirming = false }, enabled = enabled) { Text("✕") }
        } else {
            IconButton(onClick = { confirming = true }, enabled = enabled) {
                Text("🗑", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
