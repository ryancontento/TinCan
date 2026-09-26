package io.github.ryancontento.tincan.ui

import androidx.compose.ui.platform.UriHandler

/** Model output is untrusted; file:, smb: and OS protocol handlers must not open from a click. */
class WebOnlyUriHandler(private val delegate: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        // The platform handler throws on a malformed or unhandled URI.
        if (isWebUri(uri)) runCatching { delegate.openUri(uri) }
    }
}

internal fun isWebUri(uri: String): Boolean {
    val scheme = uri.trim().substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https"
}
