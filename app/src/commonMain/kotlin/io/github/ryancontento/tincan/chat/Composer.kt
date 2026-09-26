package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.data.SendKey
import io.github.ryancontento.tincan.ui.TinButton
import io.github.ryancontento.tincan.ui.TinDivider
import io.github.ryancontento.tincan.ui.TinField
import io.github.ryancontento.tincan.ui.TinIcon
import io.github.ryancontento.tincan.ui.TinIconButton
import io.github.ryancontento.tincan.ui.TinOutlinedButton

@Composable
fun Composer(
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    draftImages: List<DraftImage>,
    canSend: Boolean,
    isGenerating: Boolean,
    sendKey: SendKey,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    /** True if it took an image off the clipboard; otherwise the paste goes ahead as text. */
    onPasteImage: () -> Boolean,
    onRemoveImage: (Int) -> Unit,
) {
    // An image alone is a complete question.
    val submittable = canSend && (draft.text.isNotBlank() || draftImages.isNotEmpty())

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TinDivider(Modifier.padding(bottom = 8.dp))
        if (draftImages.isNotEmpty()) DraftImageStrip(draftImages, onRemoveImage)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            TinField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = "Message",
                minLines = 2,
                // Preview, not onKeyEvent: the field consumes Enter itself, so Send would never see it.
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (event.isPaste() && onPasteImage()) return@onPreviewKeyEvent true
                    when (event.composerAction(sendKey)) {
                        // Consumed even when it cannot send, or a stray newline lands in a message the user thought was sent.
                        ComposerAction.SEND -> { if (submittable) onSend(); true }
                        ComposerAction.NEWLINE -> { onDraftChange(draft.withNewlineAtCaret()); true }
                        ComposerAction.IGNORE -> false
                    }
                },
            )
            TinOutlinedButton(onClick = onAttach, label = "Attach")
            if (isGenerating) {
                TinOutlinedButton(onClick = onStop, label = "Stop")
            } else {
                TinButton(onClick = onSend, enabled = submittable, label = "Send")
            }
        }
        Text(
            sendKey.hint() + " · Ctrl+V pastes an image",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DraftImageStrip(images: List<DraftImage>, onRemove: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        images.forEachIndexed { index, image ->
            Box {
                Thumbnail(image.image.bytes, image.name, Modifier.height(56.dp))
                TinIconButton(
                    onClick = { onRemove(index) },
                    icon = TinIcon.CLOSE,
                    description = "Remove ${image.name}",
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

private fun KeyEvent.isPaste() =
    key == Key.V && type == KeyEventType.KeyDown && (isCtrlPressed || isMetaPressed) && !isAltPressed

private fun KeyEvent.composerAction(sendKey: SendKey) = composerAction(
    isEnter = key == Key.Enter || key == Key.NumPadEnter,
    isKeyDown = type == KeyEventType.KeyDown,
    isCtrlPressed = isCtrlPressed,
    isShiftPressed = isShiftPressed,
    isMetaPressed = isMetaPressed,
    isAltPressed = isAltPressed,
    sendKey = sendKey,
)

/** Text files land at the end of the draft, each as its own block, with the caret after them. */
internal fun TextFieldValue.withBlocksAppended(blocks: List<String>): TextFieldValue {
    val joined = (listOf(text.trimEnd()).filter { it.isNotEmpty() } + blocks).joinToString("\n\n") + "\n"
    return TextFieldValue(joined, TextRange(joined.length))
}

/** Replaces the selection with a line break and leaves the caret after it. */
private fun TextFieldValue.withNewlineAtCaret(): TextFieldValue =
    TextFieldValue(text.substring(0, selection.min) + "\n" + text.substring(selection.max), TextRange(selection.min + 1))
