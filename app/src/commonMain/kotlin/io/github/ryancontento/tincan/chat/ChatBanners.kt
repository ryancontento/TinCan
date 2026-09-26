package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.llm.CONTEXT_WARNING_THRESHOLD
import io.github.ryancontento.tincan.ui.Metrics
import io.github.ryancontento.tincan.ui.TinToolbarButton

/**
 * How much of the context window the next request uses, because Ollama truncates at num_ctx
 * silently. No limit is shown as a warning, not an empty bar: it is the setting most likely to lose history.
 */
@Composable
fun ContextMeter(state: ChatUiState) {
    val plan = state.context ?: return
    if (state.messages.isEmpty()) return

    val colors = MaterialTheme.colorScheme
    val fraction = plan.fractionUsed
    // Bound locally: a nullable property from another module does not smart-cast.
    val budget = plan.budgetTokens
    val used = plan.estimatedTokens.formatTokens()

    val (text, tint) = when {
        budget == null -> {
            val supported = state.availableModels.firstOrNull { it.id == state.activeModel }?.contextLength
            val hint = supported?.let { ", model supports ${it.formatTokens()}" }.orEmpty()
            "~$used used · no limit set, so the server silently drops old turns$hint" to colors.error
        }
        plan.trimmed -> "~$used of ${budget.formatTokens()} · oldest ${plan.droppedCount} trimmed to fit" to colors.error
        fraction != null && fraction >= CONTEXT_WARNING_THRESHOLD -> "~$used of ${budget.formatTokens()} · near the limit" to colors.error
        else -> "~$used of ${budget.formatTokens()}" to colors.onSurfaceVariant
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.width(90.dp).height(3.dp),
                color = tint,
                trackColor = colors.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** One sentence and at most one action: the one most likely to fix this particular failure. */
@Composable
fun NoticeBar(notice: Notice, onAction: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (notice.severity == Notice.Severity.ERROR) colors.error else colors.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(colors.surface, MaterialTheme.shapes.small)
            .border(Metrics.hairline, colors.outlineVariant, MaterialTheme.shapes.small)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(3.dp).height(14.dp).background(tint, MaterialTheme.shapes.extraSmall))
        Text(notice.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurface, modifier = Modifier.weight(1f))
        notice.action?.let { action ->
            TinToolbarButton(
                onClick = onAction,
                accent = true,
                label = when (action) {
                    NoticeAction.RETRY -> "Retry"
                    NoticeAction.CONTINUE -> "Continue"
                    NoticeAction.OPEN_SETTINGS -> "Settings"
                },
            )
        }
        TinToolbarButton(onClick = onDismiss, label = "Dismiss")
    }
}

/** 8192 reads better as 8.2k. */
internal fun Int.formatTokens(): String = when {
    this >= 1_000_000 -> "${this / 1_000_000}M"
    this >= 1_000 -> "${(this / 100) / 10.0}k"
    else -> toString()
}
