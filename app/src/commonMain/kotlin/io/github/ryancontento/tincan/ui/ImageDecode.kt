package io.github.ryancontento.tincan.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Null for bytes that are not a decodable image; the UI shows a placeholder instead. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
