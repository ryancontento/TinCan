package io.github.ryancontento.tincan.data

import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * Identifies which server a stored row was sent to, without recording where
 * that server is. The conversation database is the file most likely to be
 * copied, backed up or attached to a bug report, so a hostname in every row
 * would put the user's network layout wherever the transcript goes.
 */
@JvmInline
value class ServerKey(val value: String) {

    companion object {
        /** Rows written before keys existed, or by an install whose salt is gone. */
        val Unknown = ServerKey("srv-unknown")

        /**
         * Salted so a copied database cannot be tested against guessed
         * addresses: without the salt, hashing a hostname proves nothing.
         */
        fun derive(url: String, salt: String): ServerKey =
            ServerKey(PREFIX + "$salt\n${normalize(url)}".encodeUtf8().sha256().hex().take(KEY_HEX_CHARS))

        /** New per install, and never leaves it. */
        fun newSalt(random: Random = Random.Default): String =
            random.nextBytes(SALT_BYTES).toByteString().hex()

        /** True for values still holding a raw address, which the sweep replaces. */
        fun looksLikeAddress(stored: String): Boolean =
            stored.startsWith("http://", ignoreCase = true) || stored.startsWith("https://", ignoreCase = true)

        /** So one server typed two ways does not read as two servers. */
        private fun normalize(url: String) = url.trim().trimEnd('/').lowercase()

        private const val PREFIX = "srv-"
        private const val KEY_HEX_CHARS = 12
        private const val SALT_BYTES = 16
    }
}
