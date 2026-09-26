package io.github.ryancontento.tincan.attach

import io.github.ryancontento.tincan.data.ImageAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2)

class AttachmentsTest {

    @Test
    fun images_are_recognised_by_their_bytes_not_their_name() {
        val png = assertIs<Attachment.Image>(classify(PickedFile("notes.txt", PNG)))
        assertEquals("image/png", png.image.mimeType)
        assertEquals("image/jpeg", assertIs<Attachment.Image>(classify(PickedFile("a", JPEG))).image.mimeType)
    }

    @Test
    fun a_source_file_becomes_a_labelled_code_block() {
        val text = assertIs<Attachment.Text>(classify(PickedFile("Main.kt", "fun main() {}\n".encodeToByteArray())))
        assertEquals("`Main.kt`\n```kotlin\nfun main() {}\n```", text.block)
    }

    @Test
    fun a_file_that_contains_a_fence_gets_a_longer_one() {
        val markdown = "Example:\n```\ncode\n```"
        val block = assertIs<Attachment.Text>(classify(PickedFile("README.md", markdown.encodeToByteArray()))).block
        assertTrue(block.contains("\n````markdown\n"), block)
        assertTrue(block.endsWith("\n````"), block)
    }

    @Test
    fun binary_files_are_turned_away_with_a_reason() {
        val exe = byteArrayOf(0x4D, 0x5A, 0, 0, 1)
        val rejected = assertIs<Attachment.Rejected>(classify(PickedFile("tool.exe", exe)))
        assertTrue(rejected.reason.contains("tool.exe"))

        // Not valid UTF-8, though it has no NUL bytes.
        assertIs<Attachment.Rejected>(classify(PickedFile("latin1.txt", byteArrayOf(0x63, 0xE9.toByte(), 0x21))))
    }

    @Test
    fun oversized_files_are_turned_away_by_size_without_reading_them() {
        val bigImage = PickedFile("photo.jpg", ByteArray(0), sizeBytes = ImageAttachment.MAX_BYTES + 1L)
        assertTrue(assertIs<Attachment.Rejected>(classify(bigImage)).reason.contains("MB"))

        val bigText = PickedFile("log.txt", "a".repeat(MAX_TEXT_BYTES + 1).encodeToByteArray())
        assertTrue(assertIs<Attachment.Rejected>(classify(bigText)).reason.contains("KB"))
    }

    @Test
    fun a_byte_order_mark_does_not_end_up_in_the_message() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".encodeToByteArray()
        assertEquals("hi", decodeText(withBom))
    }
}
