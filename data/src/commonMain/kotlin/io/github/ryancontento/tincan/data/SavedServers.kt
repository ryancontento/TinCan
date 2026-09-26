package io.github.ryancontento.tincan.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A server the user named, so switching is a pick from a list rather than retyping an address. */
@Serializable
data class SavedServer(val name: String, val url: String)

/** Same address typed two ways is one server. */
internal fun sameServer(a: String, b: String): Boolean = normalizeUrl(a) == normalizeUrl(b)

internal fun normalizeUrl(url: String): String = url.trim().trimEnd('/').lowercase()

/** Saving an address already in the list renames it rather than adding a twin. */
internal fun List<SavedServer>.withServer(name: String, url: String): List<SavedServer> {
    val entry = SavedServer(name.trim().ifBlank { url.trim() }, url.trim())
    val index = indexOfFirst { sameServer(it.url, url) }
    return if (index >= 0) toMutableList().also { it[index] = entry } else this + entry
}

internal fun List<SavedServer>.withoutServer(url: String): List<SavedServer> =
    filterNot { sameServer(it.url, url) }

/** A corrupt or newer-format value must not stop the app starting; it reads as no saved servers. */
internal fun decodeServers(stored: String?): List<SavedServer> =
    stored?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

internal fun encodeServers(servers: List<SavedServer>): String = json.encodeToString(serializer, servers)

private val serializer = ListSerializer(SavedServer.serializer())
private val json = Json { ignoreUnknownKeys = true }
