package io.github.ryancontento.tincan.ui

import androidx.compose.ui.platform.UriHandler

/** Model output is untrusted: only http(s) opens, and only after confirming unless the host is trusted. */
class GuardedUriHandler(
    private val platform: UriHandler,
    private val trustedHosts: () -> Set<String>,
    private val confirm: (url: String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        if (!isWebUri(uri)) return
        val host = linkHost(uri) ?: return
        if (host in trustedHosts()) open(uri) else confirm(uri)
    }

    /** The platform handler throws on a malformed or unhandled URI. */
    fun open(uri: String) {
        runCatching { platform.openUri(uri) }
    }
}

internal fun isWebUri(uri: String): Boolean {
    val scheme = uri.trim().substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https"
}

/**
 * The host a browser would actually visit. `https://google.com@evil.com` goes to evil.com:
 * everything before the last `@` is a username, which is exactly how such links deceive.
 */
fun linkHost(uri: String): String? {
    val range = hostRange(uri) ?: return null
    return uri.substring(range).lowercase().trimEnd('.').takeIf { it.isNotEmpty() }
}

/** Where the host sits in [uri], so the dialog can bold exactly the part that matters. */
fun hostRange(uri: String): IntRange? {
    val start = uri.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
    val authorityEnd = uri.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) uri.length else it }
    val hostStart = uri.lastIndexOf('@', authorityEnd - 1).let { if (it >= start) it + 1 else start }
    val hostEnd = if (uri.getOrNull(hostStart) == '[') {
        uri.indexOf(']', hostStart).let { if (it < 0) authorityEnd else it + 1 }
    } else {
        uri.indexOf(':', hostStart).let { if (it < 0 || it > authorityEnd) authorityEnd else it }
    }
    return if (hostEnd > hostStart) hostStart until hostEnd else null
}
