package io.github.ryancontento.tincan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Link text can say anything; this shows the real address, with the host in bold. */
@Composable
fun LinkConfirmDialog(url: String, onOpen: (alwaysTrust: Boolean) -> Unit, onDismiss: () -> Unit) {
    val host = linkHost(url).orEmpty()
    var alwaysTrust by remember(url) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Open this link?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(withBoldHost(url), style = MaterialTheme.typography.bodyMedium.merge(MonoStyle))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = alwaysTrust, onCheckedChange = { alwaysTrust = it })
                    Text("Always open links to $host", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TinButton(onClick = { onOpen(alwaysTrust) }, label = "Open") },
        dismissButton = { TinToolbarButton(onClick = onDismiss, label = "Cancel") },
    )
}

private fun withBoldHost(url: String): AnnotatedString = buildAnnotatedString {
    val range = hostRange(url)
    if (range == null) {
        append(url)
        return@buildAnnotatedString
    }
    append(url.substring(0, range.first))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(url.substring(range)) }
    append(url.substring(range.last + 1))
}
