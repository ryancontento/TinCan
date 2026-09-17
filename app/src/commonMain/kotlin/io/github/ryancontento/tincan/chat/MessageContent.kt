package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.delay

/**
 * Renders a reply's body.
 *
 * While streaming, the settled prefix is parsed as markdown and the tail is
 * drawn as plain text; see [splitStreamingMarkdown] for why. The parse is keyed
 * on the settled text via remember, so it runs once per paragraph rather than
 * once per chunk — which is the whole point of the split.
 */
@Composable
fun MessageContent(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isStreaming) {
        // A reply cut off mid-block still renders as code rather than prose.
        val complete = remember(text) { closeDanglingFence(text) }
        MarkdownBody(complete, modifier)
        return
    }

    val split = remember(text) { splitStreamingMarkdown(text) }

    Column(modifier) {
        if (split.settled.isNotBlank()) {
            // Keyed on settled alone: the tail changing every 30ms must not
            // drag the parser along with it.
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
    // Both slots are replaced: codeFence covers ``` blocks, codeBlock covers
    // the four-space indented form. Leaving either default would mean some code
    // silently has no copy button.
    val components = markdownComponents(
        codeFence = { model ->
            val raw = model.content.substring(model.node.startOffset, model.node.endOffset)
            val parsed = remember(raw) { parseFencedCode(raw) }
            CodeBlock(code = parsed.code, language = parsed.language)
        },
        codeBlock = { model ->
            val raw = model.content.substring(model.node.startOffset, model.node.endOffset)
            val parsed = remember(raw) { parseFencedCode(raw) }
            CodeBlock(code = parsed.code, language = parsed.language)
        },
    )

    Markdown(
        content = text,
        components = components,
        modifier = modifier,
    )
}

/**
 * A fenced code block with a copy button.
 *
 * Horizontally scrollable rather than wrapped: wrapped code is materially
 * harder to read, and a long line is better reached by scrolling than by
 * having its structure destroyed.
 */
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
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(code))
                copied = true
            }) {
                Text(if (copied) "Copied" else "Copy", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            text = code,
            style = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private const val COPIED_LABEL_MILLIS = 1_500L

/**
 * A model's reasoning trace, collapsed by default.
 *
 * Collapsed because it is usually long, often repetitive, and not what was
 * asked for — but discarding it outright would be worse. Reasoning models like
 * gpt-oss emit a substantial share of their output here, and hiding it
 * permanently means paying for tokens that are never seen.
 */
@Composable
fun ReasoningTrace(
    thinking: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (thinking.isBlank()) return
    var expanded by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) {
            Text(
                text = when {
                    expanded -> "Hide reasoning"
                    // While streaming, the trace is the only sign of life before
                    // the first visible token arrives.
                    isStreaming -> "Thinking…"
                    else -> "Show reasoning"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (expanded) {
            Text(
                thinking,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp),
            )
        }
    }
}
