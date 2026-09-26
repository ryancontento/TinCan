package io.github.ryancontento.tincan.attach

import io.github.ryancontento.tincan.data.ImageAttachment
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

/**
 * Caps the long edge at [maxEdge]: images are resent every turn, so full size slows the whole conversation.
 * PNG stays PNG (screenshots of text blur as JPEG). Returns [bytes] as is if already small or undecodable.
 */
fun shrinkImage(bytes: ByteArray, maxEdge: Int = MAX_IMAGE_EDGE): ByteArray {
    val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return bytes
    val longest = maxOf(image.width, image.height)
    if (longest <= maxEdge) return bytes

    val scale = maxEdge.toFloat() / longest
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(width.toFloat(), height.toFloat()),
        SamplingMode.MITCHELL,
        null,
        true,
    )

    val png = ImageAttachment.sniffMimeType(bytes) == "image/png"
    val encoded = surface.makeImageSnapshot().encodeToData(
        if (png) EncodedImageFormat.PNG else EncodedImageFormat.JPEG,
        JPEG_QUALITY,
    )
    return encoded?.bytes ?: bytes
}

/** Past what the common vision encoders actually look at. */
const val MAX_IMAGE_EDGE = 1568
private const val JPEG_QUALITY = 90
