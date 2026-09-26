package io.github.ryancontento.tincan.attach

import io.github.ryancontento.tincan.data.ImageAttachment
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun encoded(width: Int, height: Int, format: EncodedImageFormat): ByteArray {
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.clear(Color.makeRGB(40, 120, 200))
    return surface.makeImageSnapshot().encodeToData(format, 90)!!.bytes
}

class ImageShrinkTest {

    @Test
    fun a_large_photo_shrinks_to_the_limit_and_keeps_its_shape() {
        val shrunk = Image.makeFromEncoded(shrinkImage(encoded(4032, 3024, EncodedImageFormat.JPEG)))

        assertEquals(MAX_IMAGE_EDGE, shrunk.width)
        assertEquals(1176, shrunk.height)
    }

    @Test
    fun a_screenshot_stays_png_so_text_stays_sharp() {
        val shrunk = shrinkImage(encoded(3840, 2160, EncodedImageFormat.PNG))
        assertEquals("image/png", ImageAttachment.sniffMimeType(shrunk))
    }

    @Test
    fun a_small_image_and_undecodable_bytes_come_back_untouched() {
        val small = encoded(800, 600, EncodedImageFormat.PNG)
        assertSame(small, shrinkImage(small))

        val junk = byteArrayOf(1, 2, 3)
        assertSame(junk, shrinkImage(junk))
    }

    @Test
    fun a_file_read_for_attaching_arrives_already_shrunk() {
        val file = File(System.getProperty("java.io.tmpdir"), "tincan-${UUID.randomUUID()}.jpg")
        try {
            file.writeBytes(encoded(3000, 3000, EncodedImageFormat.JPEG))
            val picked = readForAttaching(file)!!
            assertTrue(Image.makeFromEncoded(picked.bytes).width <= MAX_IMAGE_EDGE)
            assertEquals(picked.bytes.size.toLong(), picked.sizeBytes)
        } finally {
            file.delete()
        }
    }
}
