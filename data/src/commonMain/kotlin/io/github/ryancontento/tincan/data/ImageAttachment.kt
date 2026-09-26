package io.github.ryancontento.tincan.data

/** An image picked or pasted into the composer, not yet saved. */
class ImageAttachment(val mimeType: String, val bytes: ByteArray) {
    companion object {
        /** Big enough for a phone photo; a huge file only slows every later turn, since images are resent. */
        const val MAX_BYTES = 10 * 1024 * 1024

        /** What Ollama's vision models accept. */
        val SUPPORTED_TYPES = setOf("image/png", "image/jpeg", "image/webp")

        /** By content rather than file name, so a renamed file is judged by what it is. */
        fun sniffMimeType(bytes: ByteArray): String? = when {
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            bytes.size >= 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
            else -> null
        }

        private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
    }
}
