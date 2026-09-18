package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ColumnScope
import io.github.ryancontento.tincan.ui.TinToolbarButton
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.delay

/** Streaming: settled prefix as markdown, tail as plain text. See [splitStreamingMarkdown]. */
@Composable
fun MessageContent(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isStreaming) {
        // Truncated replies still render as code, not prose.
        val complete = remember(text) { closeDanglingFence(text) }
        MarkdownBody(complete, modifier)
        return
    }

    val split = remember(text) { splitStreamingMarkdown(text) }

    Column(modifier) {
        if (split.settled.isNotBlank()) {
            // Keyed on settled alone so a 30ms tail does not re-run the parser.
            key(split.settled) { MarkdownBody(split.settled) }
        }
        if (split.pending.isNotEmpty()) {
            Text(
                split.pending,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    // Both slots replaced so fenced and indented code both get a copy button.
    val renderCode: @Composable ColumnScope.(MarkdownComponentModel) -> Unit = { model ->
        val raw = model.content.substring(model.node.startOffset, model.node.endOffset)
        val parsed = remember(raw) { parseFencedCode(raw) }
        CodeBlock(code = parsed.code, language = parsed.language)
    }
    val components = markdownComponents(codeFence = renderCode, codeBlock = renderCode)

    Markdown(
        content = text,
        components = components,
        modifier = modifier,
    )
}

/** Scrolls horizontally rather than wrapping; wrapped code is harder to read. */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LABEL_MILLIS)
            copied = false
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TinToolbarButton(
                onClick = { clipboard.setText(AnnotatedString(code)); copied = true },
                label = if (copied) "Copied" else "Copy",
            )
        }
        Text(
            text = code,
            style = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
            ),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
        )
    }
}

private const val COPIED_LABEL_MILLIS = 1_500L

/** Collapsed by default: long and repetitive, but too much output to discard. */
@Composable
fun ReasoningTrace(
    thinking: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (thinking.isBlank()) return
    var expanded by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        TinToolbarButton(
            onClick = { expanded = !expanded },
            label = when {
                expanded -> "Hide reasoning"
                // The only sign of life before the first visible token.
                isStreaming -> "Thinking…"
                else -> "Show reasoning"
            },
        )
        if (expanded) {
            Text(
                thinking,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(9.dp),
            )
        }
    }
}
