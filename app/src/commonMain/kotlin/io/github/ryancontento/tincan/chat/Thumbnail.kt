package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.ui.Metrics
import io.github.ryancontento.tincan.ui.decodeImage

/** Sized by [modifier]'s height; a labelled placeholder if the bytes will not decode. */
@Composable
internal fun Thumbnail(bytes: ByteArray, description: String, modifier: Modifier = Modifier) {
    val bitmap = remember(bytes) { decodeImage(bytes) }
    val shape = MaterialTheme.shapes.small
    if (bitmap == null) {
        Box(modifier.width(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, shape), contentAlignment = Alignment.Center) {
            Text("image", style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = modifier.clip(shape).border(Metrics.hairline, MaterialTheme.colorScheme.outlineVariant, shape),
    )
}
